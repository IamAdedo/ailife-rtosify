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
 * Estimates respiratory rate (breaths per minute) using the wrist accelerometer.
 *
 * Method: During rest or sleep the Z-axis of the accelerometer shows subtle
 * periodic oscillation (~0.1–0.5 Hz) corresponding to chest wall rise and fall
 * transmitted through the arm. A zero-crossing counter over a 30-second window
 * gives a reasonable breath-rate estimate without a dedicated respiratory sensor.
 *
 * Accuracy note: This is a proxy method. Results are most reliable during still,
 * resting conditions. During sleep TruSleep-style tracking the estimate improves
 * because motion artefacts are minimised.
 *
 * Usage:
 *   val monitor = RespiratoryRateMonitor(context) { data -> send(data) }
 *   monitor.startMeasurement("SLEEP")   // or "REST"
 *   // 30 s later callback fires automatically
 *   monitor.stop()
 */
class RespiratoryRateMonitor(
    private val context: Context,
    private val onResult: (RespiratoryRateData) -> Unit
) : SensorEventListener {

    companion object {
        private const val WINDOW_SECONDS = 30
        private const val SAMPLE_RATE_HZ = 25          // SENSOR_DELAY_GAME ≈ 50Hz, we downsample
        private const val BAND_LOW_HZ = 0.1f           // min breath rate (6 bpm)
        private const val BAND_HIGH_HZ = 0.7f          // max breath rate (42 bpm)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val zSamples = mutableListOf<Float>()
    private var context_label = "REST"
    private var running = false
    private var sampleCount = 0

    fun startMeasurement(ctx: String = "REST") {
        if (running) return
        running = true
        context_label = ctx
        zSamples.clear()
        sampleCount = 0
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        handler.postDelayed(::computeAndReport, WINDOW_SECONDS * 1000L)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        zSamples.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // Downsample to ~25 Hz
        sampleCount++
        if (sampleCount % 2 != 0) return
        zSamples.add(event.values[2])   // Z-axis captures vertical chest movement
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun computeAndReport() {
        sensorManager.unregisterListener(this)
        running = false
        if (zSamples.size < 50) return   // not enough data

        val bpm = estimateBreathsPerMinute(zSamples)
        if (bpm in 6f..42f) {   // plausible range
            onResult(RespiratoryRateData(
                breathsPerMinute = bpm,
                context = context_label
            ))
        }
        zSamples.clear()
    }

    /**
     * Simple zero-crossing rate on a high-pass filtered signal.
     * Counts how many times the detrended Z-axis signal crosses zero per minute.
     */
    private fun estimateBreathsPerMinute(samples: List<Float>): Float {
        if (samples.size < 10) return 0f

        // Remove DC offset (mean subtraction)
        val mean = samples.average().toFloat()
        val centred = samples.map { it - mean }

        // Count zero crossings (rising only)
        var crossings = 0
        for (i in 1 until centred.size) {
            if (centred[i - 1] < 0f && centred[i] >= 0f) crossings++
        }

        // crossings per window → breaths per minute
        val windowMinutes = WINDOW_SECONDS / 60f
        return crossings / windowMinutes
    }
}
