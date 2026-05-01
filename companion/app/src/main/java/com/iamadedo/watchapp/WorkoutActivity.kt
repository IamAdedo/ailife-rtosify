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
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Watch-side workout tracking screen.
 *
 * Supported types: RUNNING, CYCLING, SWIMMING, HIKING, HIIT, ROWING
 *
 * Metrics tracked:
 *  - Elapsed time, HR + HR zone, step cadence (accel), estimated calories (MET-based)
 *  - Sends WorkoutMetricData to phone every 5 s via WORKOUT_METRIC
 *  - On stop: sends WorkoutEndData via WORKOUT_END
 *
 * GPS distance comes from the phone via LOCATION_UPDATE messages and is stored
 * in the session accumulator.
 */
class WorkoutActivity : Activity(), SensorEventListener {

    companion object {
        const val EXTRA_WORKOUT_TYPE = "workout_type"
        private const val METRIC_INTERVAL_MS = 5_000L
        private const val STEP_CADENCE_WINDOW_MS = 5_000L

        // MET values per workout type (kcal/kg/h approx)
        private val MET_MAP = mapOf(
            "RUNNING" to 9.8f, "CYCLING" to 7.5f, "SWIMMING" to 8.0f,
            "HIKING" to 5.3f, "HIIT" to 10.0f, "ROWING" to 7.0f
        )
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var tvType: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvHr: TextView
    private lateinit var tvZone: TextView
    private lateinit var tvPace: TextView
    private lateinit var tvCalories: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvCadence: TextView
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button

    // ── State ─────────────────────────────────────────────────────────────────
    private var workoutType = "RUNNING"
    private var startTimeMs = 0L
    private var pausedMs = 0L
    private var isPaused = false
    private var distanceKm = 0f
    private var calories = 0
    private var currentHr = 0
    private var currentCadence = 0
    private var lastLocationLat = 0.0
    private var lastLocationLon = 0.0

    // Accelerometer step counting (simple peak detection)
    private val stepTimestamps = ArrayDeque<Long>()
    private var lastAccelMag = 0f

    // ── Hardware ──────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothService: BluetoothService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bluetoothService = (binder as? BluetoothService.LocalBinder)?.getService()
            sendWorkoutStart()
        }
        override fun onServiceDisconnected(name: ComponentName?) { bluetoothService = null }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workout)

        workoutType = intent.getStringExtra(EXTRA_WORKOUT_TYPE) ?: "RUNNING"
        startTimeMs = System.currentTimeMillis()

        bindViews()
        tvType.text = workoutType.replaceFirstChar { it.uppercase() }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        registerSensors()
        bindService(Intent(this, BluetoothService::class.java), serviceConnection, BIND_AUTO_CREATE)

        handler.post(elapsedRunnable)
        handler.postDelayed(metricRunnable, METRIC_INTERVAL_MS)

        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun bindViews() {
        tvType      = findViewById(R.id.tv_workout_type)
        tvElapsed   = findViewById(R.id.tv_workout_elapsed)
        tvHr        = findViewById(R.id.tv_workout_hr)
        tvZone      = findViewById(R.id.tv_workout_zone)
        tvPace      = findViewById(R.id.tv_workout_pace)
        tvCalories  = findViewById(R.id.tv_workout_calories)
        tvDistance  = findViewById(R.id.tv_workout_distance)
        tvCadence   = findViewById(R.id.tv_workout_cadence)
        btnPause    = findViewById(R.id.btn_workout_pause)
        btnStop     = findViewById(R.id.btn_workout_stop)

        btnPause.setOnClickListener { togglePause() }
        btnStop.setOnClickListener  { stopWorkout() }
    }

    // ── Timer runnables ───────────────────────────────────────────────────────

    private val elapsedRunnable = object : Runnable {
        override fun run() {
            if (!isPaused) updateElapsedDisplay()
            handler.postDelayed(this, 1_000)
        }
    }

    private val metricRunnable = object : Runnable {
        override fun run() {
            if (!isPaused) sendMetric()
            handler.postDelayed(this, METRIC_INTERVAL_MS)
        }
    }

    private fun updateElapsedDisplay() {
        val elapsed = elapsedSeconds()
        val h = elapsed / 3600
        val m = (elapsed % 3600) / 60
        val s = elapsed % 60
        tvElapsed.text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        updateCalories()
        updatePaceDisplay()
    }

    private fun elapsedSeconds(): Long {
        val raw = (System.currentTimeMillis() - startTimeMs) / 1000
        return raw - pausedMs / 1000
    }

    // ── HR zone calculation ───────────────────────────────────────────────────

    private fun hrZone(hr: Int): Int {
        // Defaults based on 220-age; user-configurable in future
        val maxHr = 190 // placeholder
        return when {
            hr < maxHr * 0.60 -> 1
            hr < maxHr * 0.70 -> 2
            hr < maxHr * 0.80 -> 3
            hr < maxHr * 0.90 -> 4
            else -> 5
        }
    }

    // ── Calorie estimate (MET × weight × time) ────────────────────────────────

    private fun updateCalories() {
        val met = MET_MAP[workoutType] ?: 7f
        val weightKg = 70f // TODO: pull from health settings
        val hours = elapsedSeconds() / 3600f
        calories = (met * weightKg * hours).toInt()
        tvCalories.text = "$calories kcal"
    }

    private fun updatePaceDisplay() {
        if (distanceKm > 0.01f && elapsedSeconds() > 0) {
            val paceSecPerKm = (elapsedSeconds() / distanceKm).toInt()
            val pm = paceSecPerKm / 60
            val ps = paceSecPerKm % 60
            tvPace.text = "%d:%02d /km".format(pm, ps)
        }
        tvDistance.text = "%.2f km".format(distanceKm)
        tvCadence.text = if (currentCadence > 0) "$currentCadence spm" else "--"
    }

    // ── Sensor handling ───────────────────────────────────────────────────────

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0].toInt()
                if (hr > 30) {
                    currentHr = hr
                    tvHr.text = "$hr bpm"
                    val zone = hrZone(hr)
                    tvZone.text = "Zone $zone"
                }
            }
            Sensor.TYPE_ACCELEROMETER -> detectStep(event)
        }
    }

    private fun detectStep(event: SensorEvent) {
        val mag = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        // Simple threshold peak detection: rising then falling past 12 m/s²
        if (lastAccelMag < 12f && mag >= 12f) {
            val now = System.currentTimeMillis()
            stepTimestamps.addLast(now)
            // Remove steps outside cadence window
            while (stepTimestamps.isNotEmpty() &&
                   now - stepTimestamps.first() > STEP_CADENCE_WINDOW_MS) {
                stepTimestamps.removeFirst()
            }
            currentCadence = (stepTimestamps.size * (60_000f / STEP_CADENCE_WINDOW_MS)).toInt()
        }
        lastAccelMag = mag
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── Protocol messages ─────────────────────────────────────────────────────

    private fun sendWorkoutStart() {
        val msg = ProtocolHelper.createWorkoutStart(WorkoutStartData(workoutType, startTimeMs))
        bluetoothService?.sendMessage(msg)
    }

    private fun sendMetric() {
        val elapsed = elapsedSeconds()
        val paceSecPerKm = if (distanceKm > 0.01f) (elapsed / distanceKm).toInt() else null
        val zone = if (currentHr > 0) hrZone(currentHr) else null
        val metric = WorkoutMetricData(
            elapsedSeconds = elapsed,
            heartRate = currentHr.takeIf { it > 0 },
            heartRateZone = zone,
            paceSecondsPerKm = paceSecPerKm,
            distanceKm = distanceKm,
            calories = calories,
            cadence = currentCadence.takeIf { it > 0 }
        )
        bluetoothService?.sendMessage(ProtocolHelper.createWorkoutMetric(metric))
    }

    private fun sendWorkoutEnd() {
        val endData = WorkoutEndData(
            type = workoutType,
            startTime = startTimeMs,
            endTime = System.currentTimeMillis(),
            distanceKm = distanceKm,
            calories = calories,
            avgHeartRate = currentHr.takeIf { it > 0 },
            maxHeartRate = null
        )
        bluetoothService?.sendMessage(ProtocolHelper.createWorkoutEnd(endData))
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun togglePause() {
        isPaused = !isPaused
        if (isPaused) {
            pausedMs -= System.currentTimeMillis()
            btnPause.text = getString(R.string.workout_resume)
            bluetoothService?.sendMessage(ProtocolHelper.createWorkoutPause())
        } else {
            pausedMs += System.currentTimeMillis()
            btnPause.text = getString(R.string.workout_pause)
            bluetoothService?.sendMessage(ProtocolHelper.createWorkoutResume())
        }
        vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun stopWorkout() {
        sendWorkoutEnd()
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        finish()
    }

    /**
     * Called by BluetoothService when a LOCATION_UPDATE message arrives from the phone.
     * Updates the distance accumulator using the Haversine formula.
     */
    fun onLocationUpdate(lat: Double, lon: Double) {
        if (lastLocationLat != 0.0) {
            distanceKm += haversineKm(lastLocationLat, lastLocationLon, lat, lon)
        }
        lastLocationLat = lat
        lastLocationLon = lon
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return (2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))).toFloat()
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        unbindService(serviceConnection)
        super.onDestroy()
    }
}
