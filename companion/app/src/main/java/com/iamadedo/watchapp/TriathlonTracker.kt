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
import kotlin.math.sqrt

/**
 * Multi-sport triathlon tracker — Galaxy Watch Ultra / Pixel Watch feature.
 *
 * Tracks sequential sport segments: SWIM → T1 → BIKE → T2 → RUN (standard triathlon),
 * or any custom sequence defined via TRIATHLON_MODE_START.
 *
 * Transitions (T1, T2) are detected automatically via accelerometer signature change
 * OR triggered manually via a button long-press.
 *
 * Each segment accumulates:
 *   - Duration
 *   - Distance (GPS via phone LOCATION_UPDATE, or step-estimated)
 *   - Avg / peak HR
 *
 * Sends TRIATHLON_MODE_SEGMENT on each transition, TRIATHLON_MODE_END on finish.
 */
class TriathlonTracker(
    private val context: Context,
    private val onSendMessage: (ProtocolMessage) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler = Handler(Looper.getMainLooper())

    private var segments: List<String> = listOf("SWIM", "BIKE", "RUN")
    private var currentIndex = 0
    private var segmentStartMs = 0L
    private var triathlonStartMs = 0L
    private var running = false

    // Per-segment accumulators
    private val segmentResults = mutableListOf<TriathlonSegmentResult>()
    private var segmentDistanceKm = 0f
    private var segmentHrSum = 0
    private var segmentHrCount = 0
    private var currentHr = 0

    fun start(sportSequence: List<String> = listOf("SWIM", "BIKE", "RUN")) {
        if (running) return
        segments = sportSequence
        currentIndex = 0
        segmentResults.clear()
        triathlonStartMs = System.currentTimeMillis()
        segmentStartMs   = triathlonStartMs
        running = true
        resetSegmentAccumulators()

        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        onSendMessage(ProtocolHelper.createTriathlonStart(segments))
        notifySegment()
    }

    /** Called by button long-press or automatic detection */
    fun nextSegment() {
        if (!running) return
        finaliseCurrentSegment()
        currentIndex++
        if (currentIndex >= segments.size) {
            finish()
            return
        }
        segmentStartMs = System.currentTimeMillis()
        resetSegmentAccumulators()
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        onSendMessage(ProtocolHelper.createTriathlonSegment(segments[currentIndex], currentIndex))
        notifySegment()
    }

    fun finish() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        if (currentIndex < segments.size) finaliseCurrentSegment()
        val result = TriathlonResultData(
            totalDurationMs = System.currentTimeMillis() - triathlonStartMs,
            segments = segmentResults.toList()
        )
        onSendMessage(ProtocolHelper.createTriathlonEnd(result))
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 1000), -1))
    }

    /** Called when a LOCATION_UPDATE arrives with GPS data during a bike/run segment */
    fun onLocationUpdate(distanceDeltaKm: Float) {
        segmentDistanceKm += distanceDeltaKm
    }

    // ── Sensor ────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0].toInt()
                if (hr in 30..220) {
                    currentHr = hr
                    segmentHrSum += hr
                    segmentHrCount++
                }
            }
            Sensor.TYPE_ACCELEROMETER -> detectTransition(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── Auto-transition detection ─────────────────────────────────────────────
    // Simple heuristic: large multi-axis impulse + stillness pattern = transition

    private var highImpactCount = 0
    private var stillCount = 0

    private fun detectTransition(event: SensorEvent) {
        val mag = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        val deviation = kotlin.math.abs(mag - 9.81f)
        if (deviation > 8f) highImpactCount++ else highImpactCount = (highImpactCount - 1).coerceAtLeast(0)
        if (deviation < 0.3f) stillCount++ else stillCount = (stillCount - 1).coerceAtLeast(0)

        // High impact followed by stillness for 5+ seconds = transition event
        if (highImpactCount > 5 && stillCount > (5 * 25)) {
            highImpactCount = 0; stillCount = 0
            val elapsed = System.currentTimeMillis() - segmentStartMs
            if (elapsed > 60_000L) nextSegment()  // only after ≥1 min in segment
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun finaliseCurrentSegment() {
        val duration = System.currentTimeMillis() - segmentStartMs
        val avgHr = if (segmentHrCount > 0) segmentHrSum / segmentHrCount else null
        segmentResults.add(TriathlonSegmentResult(
            sport       = segments[currentIndex],
            durationMs  = duration,
            distanceKm  = segmentDistanceKm,
            avgHr       = avgHr
        ))
    }

    private fun resetSegmentAccumulators() {
        segmentDistanceKm = 0f
        segmentHrSum      = 0
        segmentHrCount    = 0
    }

    private fun notifySegment() {
        val sport = segments.getOrNull(currentIndex) ?: return
        val intent = Intent(context, BluetoothService::class.java).apply {
            action = "com.iamadedo.watchapp.TRIATHLON_SEGMENT"
            putExtra("sport", sport)
            putExtra("index", currentIndex)
        }
        context.startService(intent)
    }
}
