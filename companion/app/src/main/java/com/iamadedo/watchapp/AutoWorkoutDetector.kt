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
 * Automatically detects when the user starts a workout and what type it is,
 * mirroring Huawei Band 9's auto-detection of 4 common exercise types.
 *
 * Detected types:
 *  RUNNING  — high-cadence (~150-180 spm), regular vertical oscillation
 *  WALKING  — low-cadence (~80-120 spm), moderate oscillation
 *  CYCLING  — low vertical oscillation, regular pedalling rhythm (~60-100 rpm)
 *  ROWING   — strong periodic pull pattern on all axes
 *
 * Detection requires CONFIRM_WINDOWS consecutive positive windows (30 s each)
 * before broadcasting AUTO_WORKOUT_DETECT to avoid false positives.
 *
 * Once detected, notifies BluetoothService to send AUTO_WORKOUT_DETECT to phone,
 * and optionally starts WorkoutActivity automatically.
 */
class AutoWorkoutDetector(
    private val context: Context,
    private val onDetected: (String) -> Unit
) : SensorEventListener {

    companion object {
        private const val WINDOW_MS = 30_000L
        private const val CONFIRM_WINDOWS = 2          // ~60s of sustained activity
        private const val IDLE_RESET_WINDOWS = 3       // reset after 90s of inactivity

        // Cadence thresholds (steps per min via peak detection)
        private const val WALK_MIN_SPM = 60f
        private const val WALK_MAX_SPM = 130f
        private const val RUN_MIN_SPM  = 130f
        private const val RUN_MAX_SPM  = 210f

        // Motion amplitude thresholds
        private const val CYCLING_MAX_VERT = 0.4f      // g — very low vertical oscillation
        private const val ROWING_MULTI_AXIS = 1.5f     // g — strong multi-axis movement
        private const val ACTIVITY_MIN_AMPLITUDE = 0.3f // minimum to count as exercise
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private val accelX = ArrayDeque<Float>()
    private val accelY = ArrayDeque<Float>()
    private val accelZ = ArrayDeque<Float>()
    private var stepTimestamps = ArrayDeque<Long>()
    private var lastMag = 0f

    private var candidateType = ""
    private var confirmCount = 0
    private var idleCount = 0
    private var running = false
    private var detectedType = ""

    fun start() {
        if (running) return
        running = true
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        handler.postDelayed(windowRunnable, WINDOW_MS)
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        reset()
    }

    /** Call when workout is manually started/stopped to avoid duplicate detection */
    fun suppressDetection() {
        detectedType = "MANUAL"
        confirmCount = 0
        candidateType = ""
    }

    fun clearSuppression() {
        detectedType = ""
    }

    // ── Sensor ────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)

        accelX.addLast(x); accelY.addLast(y); accelZ.addLast(z)
        if (accelX.size > 300) { accelX.removeFirst(); accelY.removeFirst(); accelZ.removeFirst() }

        // Simple step/impact detection: rising past threshold
        if (lastMag < 12f && mag >= 12f) {
            stepTimestamps.addLast(System.currentTimeMillis())
            val cutoff = System.currentTimeMillis() - 5_000L
            while (stepTimestamps.isNotEmpty() && stepTimestamps.first() < cutoff)
                stepTimestamps.removeFirst()
        }
        lastMag = mag
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── Analysis window ───────────────────────────────────────────────────────

    private val windowRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            analyseWindow()
            handler.postDelayed(this, WINDOW_MS)
        }
    }

    private fun analyseWindow() {
        if (detectedType.isNotEmpty()) return   // already detected or suppressed

        val xBuf = accelX.toList(); val yBuf = accelY.toList(); val zBuf = accelZ.toList()
        if (xBuf.size < 50) return

        val amplitudeX = peakToPeak(xBuf)
        val amplitudeY = peakToPeak(yBuf)
        val amplitudeZ = peakToPeak(zBuf)
        val totalAmplitude = amplitudeX + amplitudeY + amplitudeZ

        if (totalAmplitude < ACTIVITY_MIN_AMPLITUDE) {
            idleCount++
            if (idleCount >= IDLE_RESET_WINDOWS) reset()
            return
        }
        idleCount = 0

        // Cadence from step timestamps
        val stepsIn5s = stepTimestamps.size
        val cadenceSpm = stepsIn5s * 12f  // steps in 5s → per minute

        val type = when {
            amplitudeZ < CYCLING_MAX_VERT && amplitudeX > 0.5f ->
                "CYCLING"                                               // low vert, lateral motion
            totalAmplitude > ROWING_MULTI_AXIS && amplitudeX > 1f && amplitudeZ > 1f ->
                "ROWING"                                                // strong multi-axis pull
            cadenceSpm >= RUN_MIN_SPM && cadenceSpm <= RUN_MAX_SPM ->
                "RUNNING"
            cadenceSpm >= WALK_MIN_SPM && cadenceSpm < RUN_MIN_SPM ->
                "WALKING"
            else -> ""
        }

        if (type.isEmpty()) { reset(); return }

        if (type == candidateType) {
            confirmCount++
            if (confirmCount >= CONFIRM_WINDOWS) {
                detectedType = type
                onDetected(type)
            }
        } else {
            candidateType = type
            confirmCount = 1
        }
    }

    private fun peakToPeak(buf: List<Float>): Float =
        (buf.maxOrNull() ?: 0f) - (buf.minOrNull() ?: 0f)

    private fun reset() {
        candidateType = ""; confirmCount = 0; idleCount = 0
    }
}
