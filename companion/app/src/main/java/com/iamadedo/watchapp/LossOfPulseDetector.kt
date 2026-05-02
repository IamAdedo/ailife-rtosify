package com.iamadedo.watchapp

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Loss of Pulse Detection — Pixel Watch 3's landmark safety feature.
 *
 * Continuously monitors the heart-rate sensor. If no valid pulse reading
 * is received for ABSENCE_THRESHOLD_MS (default 30 seconds) AND the watch
 * is being worn (motion check ensures it wasn't just removed), the watch:
 *
 *  1. Vibrates urgently to check if the user is responsive
 *  2. Waits CONFIRM_WINDOW_MS (15 s) for any movement (user dismissal)
 *  3. If no movement → fires LOSS_OF_PULSE_ALERT → phone dials emergency services
 *     and sends automated message
 *
 * Wear detection uses a simple heuristic: if accelerometer has been
 * completely still for > STILL_THRESHOLD_MS, the watch may have been removed —
 * in that case suppress the alert to avoid false positives.
 *
 * This is a life-safety feature. Always disable during known low-HR activities
 * (sleep, yoga) via the ignorePeriodMs parameter if needed.
 */
class LossOfPulseDetector(
    private val context: Context,
    private val onPulseAbsent: () -> Unit
) : SensorEventListener {

    companion object {
        private const val ABSENCE_THRESHOLD_MS = 30_000L   // 30 s no pulse
        private const val CONFIRM_WINDOW_MS    = 15_000L   // 15 s confirm wait
        private const val STILL_THRESHOLD_MS   = 60_000L   // watch probably removed
        private const val HR_VALID_MIN         = 25         // bpm — below = noise
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler = Handler(Looper.getMainLooper())

    private var lastValidPulseMs = System.currentTimeMillis()
    private var lastMotionMs     = System.currentTimeMillis()
    private var alertPending     = false
    private var running          = false

    fun start() {
        if (running) return
        running = true
        lastValidPulseMs = System.currentTimeMillis()
        lastMotionMs     = System.currentTimeMillis()

        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        handler.postDelayed(watchRunnable, ABSENCE_THRESHOLD_MS)
    }

    fun stop() {
        running = false
        alertPending = false
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
    }

    /** Call when user explicitly dismisses an alert from the watch UI. */
    fun dismiss() {
        alertPending = false
        handler.removeCallbacksAndMessages(null)
        if (running) handler.postDelayed(watchRunnable, ABSENCE_THRESHOLD_MS)
    }

    // ── Sensor callbacks ──────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0].toInt()
                if (hr >= HR_VALID_MIN) {
                    lastValidPulseMs = System.currentTimeMillis()
                    // Reset watch cycle — pulse is present
                    if (!alertPending) {
                        handler.removeCallbacksAndMessages(null)
                        handler.postDelayed(watchRunnable, ABSENCE_THRESHOLD_MS)
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val mag = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                // Any non-gravity motion means the watch is moving
                if (kotlin.math.abs(mag - 9.81f) > 0.5f) {
                    lastMotionMs = System.currentTimeMillis()
                    // If alert pending, user movement = responsive → cancel
                    if (alertPending) dismiss()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── Monitoring loop ───────────────────────────────────────────────────────

    private val watchRunnable = Runnable {
        if (!running) return@Runnable
        val now = System.currentTimeMillis()
        val pulseAbsent = now - lastValidPulseMs >= ABSENCE_THRESHOLD_MS
        val watchStill  = now - lastMotionMs      >= STILL_THRESHOLD_MS

        if (!pulseAbsent) {
            // All good — reschedule
            handler.postDelayed(watchRunnable, ABSENCE_THRESHOLD_MS)
            return@Runnable
        }

        if (watchStill) {
            // Watch probably removed — suppress, keep monitoring
            handler.postDelayed(watchRunnable, ABSENCE_THRESHOLD_MS)
            return@Runnable
        }

        // Pulse absent, watch is on wrist — trigger alert stage
        alertPending = true
        vibrator.vibrate(VibrationEffect.createWaveform(
            longArrayOf(0, 500, 200, 500, 200, 500), -1
        ))

        // Show dismissible notification on watch
        val intent = Intent(context, BluetoothService::class.java).apply {
            action = "com.iamadedo.watchapp.SHOW_PULSE_ALERT"
        }
        context.startService(intent)

        // If no dismissal in CONFIRM_WINDOW_MS → escalate to phone
        handler.postDelayed({
            if (alertPending && running) {
                alertPending = false
                onPulseAbsent()
            }
        }, CONFIRM_WINDOW_MS)
    }
}
