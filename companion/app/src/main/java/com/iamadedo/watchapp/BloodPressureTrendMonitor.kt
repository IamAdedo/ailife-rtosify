package com.iamadedo.watchapp

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * Blood Pressure Trend Monitor — Samsung Galaxy Watch 7 / Ultra feature.
 *
 * Estimates BP trend using Pulse Transit Time (PTT) — the time difference
 * between an ECG R-peak (or HR sensor peak) and the PPG pulse arrival at the wrist.
 *
 * This is a TREND estimator, not a clinical BP measurement.
 * Requires initial calibration against a cuff reading (systolic + diastolic).
 * After calibration the PTT-to-BP mapping is personalised.
 *
 * Algorithm (simplified):
 *  PTT ∝ 1/BP  — longer transit = lower pressure, shorter = higher
 *  ΔBP ≈ k × ΔPTT  (k derived from calibration)
 *
 * Triggers BLOOD_PRESSURE_TREND message after each measurement.
 * Sends calibration prompt if no calibration stored or calibration is stale (>30 days).
 */
class BloodPressureTrendMonitor(
    private val context: Context,
    private val onResult: (BloodPressureTrendData) -> Unit
) : SensorEventListener {

    companion object {
        private const val PREFS              = "bp_trend_prefs"
        private const val KEY_CALIB_SYSTOLIC = "calib_systolic"
        private const val KEY_CALIB_DIASTOLIC= "calib_diastolic"
        private const val KEY_CALIB_PTT      = "calib_ptt"
        private const val KEY_CALIB_DATE     = "calib_date"
        private const val CALIB_TTL_MS       = 30L * 24 * 60 * 60_000   // 30 days
        private const val MEASUREMENT_MS     = 30_000L
        private const val POLL_INTERVAL_MS   = 4 * 60 * 60_000L          // every 4 hours
        private const val PTT_K_FACTOR       = -0.8f  // empirical: 1ms PTT ≈ 0.8 mmHg
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs: SharedPreferences   = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    // Beat timing buffers
    private val hrTimestamps  = ArrayDeque<Long>()   // from TYPE_HEART_RATE events
    private var measuring     = false
    private var running       = false

    fun start() {
        if (running) return
        running = true
        handler.post(measureRunnable)
    }

    fun stop() {
        running = false
        measuring = false
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
    }

    /** Store a calibration reading from a cuff measurement. */
    fun calibrate(systolicMmHg: Int, diastolicMmHg: Int, currentPtt: Float) {
        prefs.edit()
            .putInt(KEY_CALIB_SYSTOLIC,  systolicMmHg)
            .putInt(KEY_CALIB_DIASTOLIC, diastolicMmHg)
            .putFloat(KEY_CALIB_PTT,     currentPtt)
            .putLong(KEY_CALIB_DATE,     System.currentTimeMillis())
            .apply()
    }

    fun isCalibrated(): Boolean {
        val date = prefs.getLong(KEY_CALIB_DATE, 0L)
        return date > 0 && System.currentTimeMillis() - date < CALIB_TTL_MS
    }

    // ── Measurement cycle ─────────────────────────────────────────────────────

    private val measureRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            startMeasurement()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun startMeasurement() {
        hrTimestamps.clear()
        measuring = true
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        handler.postDelayed(::endMeasurement, MEASUREMENT_MS)
    }

    private fun endMeasurement() {
        measuring = false
        sensorManager.unregisterListener(this)
        val ptt = estimatePtt()
        if (ptt > 0f) {
            val bp = computeBp(ptt)
            if (bp != null) onResult(bp)
        }
    }

    // ── Sensor ────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE || !measuring) return
        val hr = event.values[0].toInt()
        if (hr in 30..200) {
            hrTimestamps.addLast(System.currentTimeMillis())
            if (hrTimestamps.size > 60) hrTimestamps.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── Computation ───────────────────────────────────────────────────────────

    /**
     * Estimate PTT from RR interval variability.
     * Proxy: average RR interval in ms (1000 / HR * 1000).
     * True PTT would need synchronised ECG + PPG — this is a simplified proxy.
     */
    private fun estimatePtt(): Float {
        if (hrTimestamps.size < 5) return 0f
        val rrIntervals = (1 until hrTimestamps.size)
            .map { (hrTimestamps[it] - hrTimestamps[it - 1]).toFloat() }
            .filter { it in 300f..1500f }
        return if (rrIntervals.isEmpty()) 0f
        else rrIntervals.average().toFloat() * 0.3f  // PTT ≈ 30% of RR interval
    }

    private fun computeBp(currentPtt: Float): BloodPressureTrendData? {
        if (!isCalibrated()) {
            return BloodPressureTrendData(
                systolicEstimate  = 0,
                diastolicEstimate = 0,
                trend             = "UNCALIBRATED",
                calibrationRequired = true
            )
        }
        val calibSys    = prefs.getInt(KEY_CALIB_SYSTOLIC, 120)
        val calibDias   = prefs.getInt(KEY_CALIB_DIASTOLIC, 80)
        val calibPtt    = prefs.getFloat(KEY_CALIB_PTT, currentPtt)

        val deltaPtt    = currentPtt - calibPtt
        val deltaBp     = deltaPtt * PTT_K_FACTOR
        val estimSys    = (calibSys  + deltaBp).toInt().coerceIn(60, 220)
        val estimDias   = (calibDias + deltaBp * 0.6f).toInt().coerceIn(40, 140)

        val trend = when {
            estimSys >= 140 || estimDias >= 90  -> "HIGH_STAGE_2"
            estimSys >= 130 || estimDias >= 80  -> "HIGH_STAGE_1"
            estimSys >= 120                     -> "ELEVATED"
            else                                -> "NORMAL"
        }

        return BloodPressureTrendData(
            systolicEstimate   = estimSys,
            diastolicEstimate  = estimDias,
            trend              = trend,
            calibrationRequired = false
        )
    }
}
