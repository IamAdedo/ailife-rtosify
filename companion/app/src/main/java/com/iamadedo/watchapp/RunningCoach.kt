package com.iamadedo.watchapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Real-time running form coach — Galaxy Watch Ultra / Pixel Watch Fitbit feature.
 *
 * Analyses cadence and vertical oscillation every 30 seconds during a run and
 * sends haptic + protocol cues when form deviates from optimal ranges.
 *
 * Optimal targets (per running science consensus):
 *  Cadence           160–180 spm
 *  Vertical oscill.  6–8 cm (approximated from accel Z-axis amplitude)
 *
 * Cue types:
 *  CADENCE_LOW      → "Increase step rate"
 *  CADENCE_HIGH     → "Slow your turnover"  (unusual but possible)
 *  OVERSTRIDING     → "Shorten your stride" (high Z-oscillation + low cadence)
 *  GOOD_FORM        → positive reinforcement every 5 min of good form
 *
 * Sends RunningCoachCueData via RUNNING_COACH_CUE to phone and vibrates watch.
 */
class RunningCoach(
    private val context: Context,
    private val onCue: (RunningCoachCueData) -> Unit
) : SensorEventListener {

    companion object {
        private const val TARGET_CADENCE_MIN = 160f
        private const val TARGET_CADENCE_MAX = 180f
        private const val TARGET_VERT_MIN_CM  = 6f
        private const val TARGET_VERT_MAX_CM  = 8f
        private const val ANALYSIS_WINDOW_MS = 30_000L
        private const val GOOD_FORM_INTERVAL = 5        // good-form cue every N windows
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val vibrator      = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler       = Handler(Looper.getMainLooper())

    private val stepTimestamps = ArrayDeque<Long>()
    private val vertBuffer     = ArrayDeque<Float>()
    private var lastMag        = 0f
    private var running        = false
    private var goodFormCount  = 0

    fun start() {
        if (running) return
        running = true
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        handler.postDelayed(analysisRunnable, ANALYSIS_WINDOW_MS)
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        stepTimestamps.clear()
        vertBuffer.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)

        // Step detection (impact peak)
        if (lastMag < 13f && mag >= 13f) {
            stepTimestamps.addLast(System.currentTimeMillis())
            val cutoff = System.currentTimeMillis() - ANALYSIS_WINDOW_MS
            while (stepTimestamps.isNotEmpty() && stepTimestamps.first() < cutoff)
                stepTimestamps.removeFirst()
        }
        lastMag = mag

        // Vertical oscillation via Z-axis (gravity-compensated)
        vertBuffer.addLast(abs(z - 9.81f))
        if (vertBuffer.size > 500) vertBuffer.removeFirst()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private val analysisRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            analyse()
            handler.postDelayed(this, ANALYSIS_WINDOW_MS)
        }
    }

    private fun analyse() {
        // Cadence: steps in last 30 s → spm
        val windowMin = ANALYSIS_WINDOW_MS / 60_000f
        val cadence = stepTimestamps.size / windowMin

        // Vertical oscillation estimate: peak-to-peak of Z deviation in cm
        // Raw accel in m/s², convert to cm via rough empirical factor
        val vertCm = if (vertBuffer.isNotEmpty()) {
            val pp = (vertBuffer.maxOrNull() ?: 0f) - (vertBuffer.minOrNull() ?: 0f)
            pp * 3.5f   // empirical: 1 m/s² deviation ≈ 3.5 cm oscillation
        } else 0f

        val cue = when {
            cadence < TARGET_CADENCE_MIN && vertCm > TARGET_VERT_MAX_CM ->
                RunningCoachCueData("OVERSTRIDING",   "cadence_and_oscillation", cadence, TARGET_CADENCE_MIN)
            cadence < TARGET_CADENCE_MIN ->
                RunningCoachCueData("CADENCE_LOW",    "cadence_spm", cadence, TARGET_CADENCE_MIN)
            cadence > TARGET_CADENCE_MAX ->
                RunningCoachCueData("CADENCE_HIGH",   "cadence_spm", cadence, TARGET_CADENCE_MAX)
            else -> {
                goodFormCount++
                if (goodFormCount % GOOD_FORM_INTERVAL == 0)
                    RunningCoachCueData("GOOD_FORM", "cadence_spm", cadence, cadence)
                else null
            }
        }

        cue?.let {
            onCue(it)
            hapticForCue(it.cueType)
        }
    }

    private fun hapticForCue(type: String) {
        val pattern = when (type) {
            "OVERSTRIDING" -> longArrayOf(0, 200, 100, 200, 100, 200)
            "CADENCE_LOW"  -> longArrayOf(0, 300, 200, 300)
            "CADENCE_HIGH" -> longArrayOf(0, 100, 100, 100)
            "GOOD_FORM"    -> longArrayOf(0, 400)
            else           -> longArrayOf(0, 200)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
