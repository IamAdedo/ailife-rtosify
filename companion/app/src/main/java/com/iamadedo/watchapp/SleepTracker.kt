package com.iamadedo.watchapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects sleep onset and wake using wrist accelerometer stillness + HR drop.
 *
 * Algorithm:
 *  - Motion score = rolling 1-minute RMS of accelerometer magnitude deviation from 1g
 *  - HR score     = heart rate relative to user resting baseline
 *  - Sleep onset  = motion score < MOTION_SLEEP_THRESHOLD AND hr score < HR_SLEEP_THRESHOLD
 *                   sustained for ONSET_WINDOW_MINUTES
 *  - Wake         = motion score > MOTION_WAKE_THRESHOLD OR hr rises > WAKE_HR_THRESHOLD
 *
 * All data is local — no network required. Sessions are reported via the callback and
 * forwarded to the phone via SLEEP_SESSION_START / SLEEP_SESSION_END when connected.
 */
class SleepTracker(
    private val context: Context,
    private val onSleepSessionComplete: (SleepSessionData) -> Unit
) : SensorEventListener {

    companion object {
        private const val MOTION_SLEEP_THRESHOLD = 0.08f   // g deviation RMS
        private const val MOTION_WAKE_THRESHOLD  = 0.20f
        private const val HR_SLEEP_THRESHOLD     = 0.85f   // fraction of resting HR
        private const val ONSET_WINDOW_MINUTES   = 10
        private const val SAMPLE_WINDOW_MS       = 60_000L
        private const val POLL_INTERVAL_MS       = 30_000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private val accelBuffer = ArrayDeque<Float>()
    private var currentHr = 0
    private var restingHr = 60        // loaded from SharedPreferences in a full impl
    private var consecutiveStillMinutes = 0

    private var isTracking = false
    private var sleepStart = 0L
    private var isSleeping = false
    private var deepMinutes = 0
    private var remMinutes = 0
    private var lightMinutes = 0
    private var lastStage = ""

    fun startTracking() {
        if (isTracking) return
        isTracking = true
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        handler.postDelayed(assessRunnable, POLL_INTERVAL_MS)
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        if (isSleeping) finaliseSession()
    }

    // ── Sensor callbacks ──────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val mag = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                // Deviation from 1g (9.81 m/s²) in g units
                accelBuffer.addLast(abs(mag - 9.81f) / 9.81f)
                if (accelBuffer.size > 300) accelBuffer.removeFirst() // keep ~5 min at 1Hz
            }
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0].toInt()
                if (hr in 30..200) currentHr = hr
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── Assessment loop ───────────────────────────────────────────────────────

    private val assessRunnable = object : Runnable {
        override fun run() {
            assess()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun assess() {
        val motionScore = if (accelBuffer.isEmpty()) 1f
            else accelBuffer.toList().let { buf ->
                val mean = buf.average().toFloat()
                val rms = sqrt(buf.map { (it - mean) * (it - mean) }.average().toFloat())
                rms
            }
        val hrRatio = if (restingHr > 0 && currentHr > 0) currentHr.toFloat() / restingHr else 1f

        val isStill = motionScore < MOTION_SLEEP_THRESHOLD
        val isLowHr = hrRatio < HR_SLEEP_THRESHOLD + 0.1f

        if (isStill) consecutiveStillMinutes++ else consecutiveStillMinutes = 0

        when {
            !isSleeping && consecutiveStillMinutes >= ONSET_WINDOW_MINUTES && isLowHr -> {
                // Sleep onset detected
                isSleeping = true
                sleepStart = System.currentTimeMillis() - (ONSET_WINDOW_MINUTES * 60_000L)
            }
            isSleeping && motionScore > MOTION_WAKE_THRESHOLD -> {
                // Wake detected
                finaliseSession()
            }
            isSleeping -> {
                // Classify current stage
                val stage = classifyStage(motionScore, hrRatio)
                when (stage) {
                    "DEEP"  -> deepMinutes++
                    "REM"   -> remMinutes++
                    "LIGHT" -> lightMinutes++
                }
                lastStage = stage
            }
        }
    }

    /**
     * Very simplified sleep stage classification.
     * In a real implementation this would use 30-second epoch FFT on HR + motion.
     */
    private fun classifyStage(motion: Float, hrRatio: Float): String = when {
        motion < 0.03f && hrRatio < 0.78f -> "DEEP"
        motion < 0.06f && hrRatio > 0.85f -> "REM"
        else -> "LIGHT"
    }

    private fun finaliseSession() {
        isSleeping = false
        consecutiveStillMinutes = 0
        val endTime = System.currentTimeMillis()
        val totalMinutes = (endTime - sleepStart) / 60_000
        val awakeMinutes = (totalMinutes - deepMinutes - remMinutes - lightMinutes)
            .coerceAtLeast(0).toInt()

        // Simple sleep score: penalise short sleep, low deep%, high awake%
        val efficiency = 1f - (awakeMinutes.toFloat() / totalMinutes.coerceAtLeast(1))
        val deepRatio   = deepMinutes.toFloat() / totalMinutes.coerceAtLeast(1)
        val durationScore = (totalMinutes / 480f).coerceIn(0f, 1f) // 8h = full score
        val score = ((efficiency * 0.4f + deepRatio * 0.3f + durationScore * 0.3f) * 100).toInt()
            .coerceIn(0, 100)

        val session = SleepSessionData(
            startTime     = sleepStart,
            endTime       = endTime,
            score         = score,
            deepMinutes   = deepMinutes,
            remMinutes    = remMinutes,
            lightMinutes  = lightMinutes,
            awakeMinutes  = awakeMinutes
        )

        // Reset stage counters for next session
        deepMinutes = 0; remMinutes = 0; lightMinutes = 0

        onSleepSessionComplete(session)
    }
}
