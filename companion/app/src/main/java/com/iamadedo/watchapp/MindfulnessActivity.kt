package com.iamadedo.watchapp

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Guided breathing exercise activity for the watch.
 *
 * Patterns supported:
 *  - BOX        : 4s in — 4s hold — 4s out — 4s hold
 *  - 4_7_8      : 4s in — 7s hold — 8s out
 *  - COHERENCE  : 5s in — 5s out (heart-rate coherence / HRV optimisation)
 *
 * HRV (SDNN) is measured via heart rate sensor for 60 s before and after
 * the session, and the delta is reported as MindfulnessSessionData to the phone.
 *
 * Haptic cues: long pulse = inhale start, short pulse = exhale start,
 *              double pulse = hold start.
 */
class MindfulnessActivity : Activity(), SensorEventListener {

    companion object {
        const val EXTRA_PATTERN = "breathing_pattern"   // "BOX", "4_7_8", "COHERENCE"
        const val EXTRA_DURATION_MIN = "duration_minutes"

        private val PATTERNS = mapOf(
            "BOX"       to listOf("IN" to 4, "HOLD" to 4, "OUT" to 4, "HOLD" to 4),
            "4_7_8"     to listOf("IN" to 4, "HOLD" to 7, "OUT" to 8),
            "COHERENCE" to listOf("IN" to 5, "OUT" to 5)
        )
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var tvPhase: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvHrv: TextView
    private lateinit var progressCircle: ProgressBar
    private lateinit var btnStop: Button

    // ── State ─────────────────────────────────────────────────────────────────
    private var patternKey = "COHERENCE"
    private var durationMin = 5
    private var phaseIndex = 0
    private var phaseSecondsLeft = 0
    private val rrIntervals = mutableListOf<Long>()   // ms between beats
    private var lastBeatMs = 0L
    private var hrvBefore = 0f
    private var startTimeMs = 0L
    private var measuring = false

    // ── Hardware ──────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothService: BluetoothService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            bluetoothService = (b as? BluetoothService.LocalBinder)?.getService()
        }
        override fun onServiceDisconnected(n: ComponentName?) { bluetoothService = null }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mindfulness)

        patternKey  = intent.getStringExtra(EXTRA_PATTERN) ?: "COHERENCE"
        durationMin = intent.getIntExtra(EXTRA_DURATION_MIN, 5)

        tvPhase      = findViewById(R.id.tv_breathing_phase)
        tvCountdown  = findViewById(R.id.tv_breathing_countdown)
        tvHrv        = findViewById(R.id.tv_hrv_score)
        progressCircle = findViewById(R.id.progress_breathing)
        btnStop      = findViewById(R.id.btn_mindfulness_stop)
        btnStop.setOnClickListener { endSession() }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        bindService(Intent(this, BluetoothService::class.java), serviceConnection, BIND_AUTO_CREATE)

        // Measure baseline HRV for 60 s then begin breathing
        tvPhase.text = getString(R.string.mindfulness_measuring_hrv)
        handler.postDelayed({
            hrvBefore = computeSdnn()
            rrIntervals.clear()
            startTimeMs = System.currentTimeMillis()
            measuring = true
            initPhase()
            handler.post(tickRunnable)
            scheduleSessionEnd()
        }, 60_000L)
    }

    // ── Breathing engine ──────────────────────────────────────────────────────

    private fun initPhase() {
        val steps = PATTERNS[patternKey] ?: PATTERNS["COHERENCE"]!!
        val (phaseName, seconds) = steps[phaseIndex % steps.size]
        phaseSecondsLeft = seconds
        progressCircle.max = seconds
        tvPhase.text = phaseLabel(phaseName)
        progressCircle.progress = seconds
        hapticForPhase(phaseName)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            phaseSecondsLeft--
            tvCountdown.text = phaseSecondsLeft.toString()
            progressCircle.progress = phaseSecondsLeft
            if (phaseSecondsLeft <= 0) {
                phaseIndex++
                initPhase()
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private fun scheduleSessionEnd() {
        handler.postDelayed({ endSession() }, durationMin * 60_000L.toLong())
    }

    private fun phaseLabel(phase: String) = when (phase) {
        "IN"   -> getString(R.string.breathe_in)
        "OUT"  -> getString(R.string.breathe_out)
        "HOLD" -> getString(R.string.hold)
        else   -> phase
    }

    private fun hapticForPhase(phase: String) {
        when (phase) {
            "IN"   -> vibrator.vibrate(VibrationEffect.createOneShot(400, 180))
            "OUT"  -> vibrator.vibrate(VibrationEffect.createOneShot(200, 100))
            "HOLD" -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 100), -1))
        }
    }

    // ── HRV ──────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        val hr = event.values[0].toInt()
        if (hr < 30) return
        val now = System.currentTimeMillis()
        if (lastBeatMs > 0) rrIntervals.add(now - lastBeatMs)
        lastBeatMs = now
        val sdnn = computeSdnn()
        tvHrv.text = "HRV ${"%.0f".format(sdnn)} ms"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun computeSdnn(): Float {
        val rr = rrIntervals.toList()
        if (rr.size < 5) return 0f
        val mean = rr.average()
        val variance = rr.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance).toFloat()
    }

    // ── End session ───────────────────────────────────────────────────────────

    private fun endSession() {
        if (!measuring) { finish(); return }
        measuring = false
        handler.removeCallbacksAndMessages(null)
        val hrvAfter = computeSdnn()
        val session = MindfulnessSessionData(
            pattern = patternKey,
            durationMinutes = durationMin,
            hrvBefore = hrvBefore,
            hrvAfter = hrvAfter
        )
        bluetoothService?.sendMessage(ProtocolHelper.createMindfulnessSession(session))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        unbindService(serviceConnection)
        super.onDestroy()
    }
}
