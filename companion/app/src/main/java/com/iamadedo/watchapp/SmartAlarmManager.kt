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
 * Smart Alarm — wakes the user during the lightest sleep stage within a
 * configurable window before their target alarm time.
 *
 * How it works:
 *  - Monitors accelerometer motion level every 30 seconds
 *  - "Light sleep" = motion RMS > LIGHT_SLEEP_MOTION_THRESHOLD (some body movement)
 *  - Once inside the wake window AND light sleep is detected → trigger gentle wake
 *  - If no light sleep detected → fall back to exact target time
 *
 * Gentle wake: escalating haptic pattern (soft → medium → strong) over 60 seconds,
 * much less jarring than a sudden full-alarm.
 *
 * Integrates with WatchAlarmManager — SmartAlarmData is sent from the phone and
 * registered here on the watch.
 */
class SmartAlarmManager(private val context: Context) : SensorEventListener {

    companion object {
        private const val LIGHT_SLEEP_MOTION_THRESHOLD = 0.06f  // g deviation RMS
        private const val MOTION_WINDOW_SAMPLES = 20             // ~30s at 0.67Hz
        private const val CHECK_INTERVAL_MS = 30_000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler = Handler(Looper.getMainLooper())
    private val accelBuffer = ArrayDeque<Float>()

    private var targetWakeMs = 0L
    private var windowMs = 30 * 60_000L   // 30 minutes before target
    private var enabled = false
    private var triggered = false

    fun schedule(data: SmartAlarmData) {
        cancel()
        targetWakeMs = data.targetWakeTime
        windowMs = data.windowMinutes * 60_000L
        enabled = data.enabled
        if (!enabled) return

        val windowStart = targetWakeMs - windowMs
        val now = System.currentTimeMillis()
        val delayToStart = (windowStart - now).coerceAtLeast(0)

        handler.postDelayed({
            startMonitoring()
        }, delayToStart)

        // Absolute fallback at target time regardless
        val delayToTarget = (targetWakeMs - now).coerceAtLeast(0)
        handler.postDelayed({
            if (!triggered) triggerAlarm("EXACT")
        }, delayToTarget)
    }

    fun cancel() {
        enabled = false
        triggered = false
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        accelBuffer.clear()
    }

    private fun startMonitoring() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        handler.post(monitorRunnable)
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!enabled || triggered) return
            val now = System.currentTimeMillis()
            if (now >= targetWakeMs) {
                if (!triggered) triggerAlarm("FALLBACK")
                return
            }
            val motionScore = computeMotionRms()
            if (motionScore > LIGHT_SLEEP_MOTION_THRESHOLD) {
                // Light sleep detected within window — gentle wake
                triggerAlarm("SMART")
            } else {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    private fun computeMotionRms(): Float {
        val buf = accelBuffer.toList()
        if (buf.isEmpty()) return 0f
        val mean = buf.average().toFloat()
        return sqrt(buf.map { (it - mean) * (it - mean) }.average().toFloat())
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val mag = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        accelBuffer.addLast(abs(mag - 9.81f) / 9.81f)
        if (accelBuffer.size > MOTION_WINDOW_SAMPLES) accelBuffer.removeFirst()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun triggerAlarm(reason: String) {
        triggered = true
        sensorManager.unregisterListener(this)

        // Escalating haptic: soft → medium → strong, 20s apart
        val phases = listOf(
            Pair(0L,      VibrationEffect.createWaveform(longArrayOf(0, 300, 400, 300), -1)),
            Pair(20_000L, VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), -1)),
            Pair(40_000L, VibrationEffect.createWaveform(longArrayOf(0, 800, 200, 800, 200, 800), -1))
        )
        phases.forEach { (delay, effect) ->
            handler.postDelayed({ if (triggered) vibrator.vibrate(effect) }, delay)
        }
    }
}
