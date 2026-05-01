package com.iamadedo.watchapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * Detects premature cardiac beats (ectopic beats) using RR-interval analysis
 * from the heart rate sensor.
 *
 * Method: A premature beat appears as a short RR interval followed by a
 * compensatory long interval. We flag intervals that deviate > THRESHOLD_PERCENT
 * from the running median as potential premature beats.
 *
 * Classification:
 *  OCCASIONAL  < 1 event / 5 min
 *  FREQUENT    1-5 events / 5 min
 *  BIGEMINY    >5 events / 5 min (every-other-beat pattern)
 *
 * This is a wellness indicator only — not medical-grade arrhythmia diagnosis.
 */
class PrematureBeatDetector(
    private val context: Context,
    private val onAlert: (PrematureBeatData) -> Unit
) : SensorEventListener {

    companion object {
        private const val THRESHOLD_PERCENT = 0.20f   // 20% deviation = premature
        private const val WINDOW_MINUTES = 5
        private const val RR_BUFFER_SIZE = 60          // ~1 min of beats at 60bpm
        private const val MIN_HR = 40
        private const val MAX_HR = 200
        private const val CHECK_INTERVAL_MS = 5 * 60_000L
        private const val ALERT_COOLDOWN_MS = 10 * 60_000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private val rrIntervals = ArrayDeque<Long>()   // ms between consecutive HR samples
    private val eventTimestamps = ArrayDeque<Long>() // timestamps of detected premature beats
    private var lastBeatMs = 0L
    private var lastAlertMs = 0L
    private var running = false

    fun start() {
        if (running) return
        running = true
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        handler.postDelayed(evaluateRunnable, CHECK_INTERVAL_MS)
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        rrIntervals.clear()
        eventTimestamps.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        val hr = event.values[0].toInt()
        if (hr < MIN_HR || hr > MAX_HR) return

        val now = System.currentTimeMillis()
        if (lastBeatMs > 0) {
            val rr = now - lastBeatMs
            // Only record plausible inter-beat intervals (300ms–1500ms = 40-200bpm)
            if (rr in 300L..1500L) {
                rrIntervals.addLast(rr)
                if (rrIntervals.size > RR_BUFFER_SIZE) rrIntervals.removeFirst()
                checkForPrematureBeat(rr, now)
            }
        }
        lastBeatMs = now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun checkForPrematureBeat(rr: Long, now: Long) {
        if (rrIntervals.size < 10) return
        val median = rrIntervals.sorted().let { it[it.size / 2] }
        val deviation = abs(rr - median).toFloat() / median
        // Short-then-long pattern: short RR (premature) or long RR (compensatory)
        if (deviation > THRESHOLD_PERCENT && rr < median) {
            eventTimestamps.addLast(now)
        }
        // Clean old events outside the window
        val windowStart = now - WINDOW_MINUTES * 60_000L
        while (eventTimestamps.isNotEmpty() && eventTimestamps.first() < windowStart) {
            eventTimestamps.removeFirst()
        }
    }

    private val evaluateRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val eventsInWindow = eventTimestamps.size
            val perHour = eventsInWindow.toFloat() * (60f / WINDOW_MINUTES)

            if (eventsInWindow >= 2) {
                val now = System.currentTimeMillis()
                if (now - lastAlertMs > ALERT_COOLDOWN_MS) {
                    lastAlertMs = now
                    val classification = when {
                        perHour < 12  -> "OCCASIONAL"
                        perHour < 60  -> "FREQUENT"
                        else          -> "BIGEMINY"
                    }
                    onAlert(PrematureBeatData(
                        eventsPerHour  = perHour,
                        classification = classification
                    ))
                }
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }
}
