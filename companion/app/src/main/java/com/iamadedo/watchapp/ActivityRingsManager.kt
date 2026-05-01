package com.iamadedo.watchapp

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Tracks the three Activity Rings inspired by Huawei's Band 9:
 *
 *  🔴 MOVE   — Active calories burned today vs goal
 *  🟢 EXERCISE — Minutes of elevated HR activity vs goal (default 30 min)
 *  🔵 STAND  — Hours in which at least 1 min of movement vs goal (default 12)
 *
 * Also handles:
 *  - Sedentary reminder: no significant movement for 50+ consecutive minutes
 *  - Stand reminder: no stand credit for the current hour by ~50 min past
 *  - Syncs ring progress to phone via BluetoothService every 5 minutes
 */
class ActivityRingsManager(
    private val context: Context,
    private val onSendMessage: (ProtocolMessage) -> Unit
) : SensorEventListener {

    companion object {
        private const val PREFS = "activity_rings"
        private const val KEY_DATE = "date"
        private const val KEY_MOVE_CALS = "move_cals"
        private const val KEY_EXERCISE_MIN = "exercise_min"
        private const val KEY_STAND_HOURS = "stand_hours_bitmask"  // bitmask of 24 bits

        private const val SYNC_INTERVAL_MS = 5 * 60_000L
        private const val SEDENTARY_THRESHOLD_MS = 50 * 60_000L
        private const val STAND_WINDOW_MS = 50 * 60_000L           // remind at 50 min if not stood
        private const val STEP_MOTION_THRESHOLD = 5.0f             // m/s² peak to count movement
        private const val EXERCISE_HR_ZONE_MIN = 2                 // zone ≥ 2 counts as exercise
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Ring state
    private var moveCalories = 0
    private var exerciseMinutes = 0
    private var standHoursBitmask = 0  // bit N = hour N had movement

    // Goals (defaults, overridable via ACTIVITY_GOAL_SET)
    var moveGoalCalories = 500
    var exerciseGoalMinutes = 30
    var standGoalHours = 12

    // Sedentary / stand tracking
    private var lastMotionMs = System.currentTimeMillis()
    private var sedentaryReminderSent = false
    private var currentHourHasStand = false
    private var standReminderSent = false

    // HR zone (fed from HealthDataCollector)
    var currentHrZone = 0
    private var exerciseTickMs = 0L    // ms since last exercise-zone tick

    init {
        loadToday()
    }

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        handler.post(syncRunnable)
        handler.post(sedentaryCheckRunnable)
        handler.post(standCheckRunnable)
        handler.post(exerciseTickRunnable)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        saveToday()
    }

    // ── Sensor ────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val mag = kotlin.math.sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        val deviation = kotlin.math.abs(mag - 9.81f)
        if (deviation > STEP_MOTION_THRESHOLD) {
            lastMotionMs = System.currentTimeMillis()
            sedentaryReminderSent = false
            markStandForCurrentHour()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun markStandForCurrentHour() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (standHoursBitmask and (1 shl hour) == 0) {
            standHoursBitmask = standHoursBitmask or (1 shl hour)
            val stood = Integer.bitCount(standHoursBitmask)
            if (stood > Integer.bitCount(prefs.getInt(KEY_STAND_HOURS, 0))) {
                saveToday()
            }
        }
        currentHourHasStand = true
        standReminderSent = false
    }

    // ── HR-zone exercise accumulation ─────────────────────────────────────────

    private val exerciseTickRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (currentHrZone >= EXERCISE_HR_ZONE_MIN) {
                if (exerciseTickMs == 0L) exerciseTickMs = now
                val elapsed = (now - exerciseTickMs) / 60_000
                if (elapsed >= 1) {
                    exerciseMinutes += elapsed.toInt()
                    exerciseTickMs = now
                }
            } else {
                exerciseTickMs = 0L
            }
            handler.postDelayed(this, 30_000)
        }
    }

    // ── Calorie update (called by HealthDataCollector / WorkoutActivity) ──────

    fun addActiveCalories(kcal: Int) {
        moveCalories += kcal
    }

    // ── Sedentary check ───────────────────────────────────────────────────────

    private val sedentaryCheckRunnable = object : Runnable {
        override fun run() {
            val idle = System.currentTimeMillis() - lastMotionMs
            if (idle >= SEDENTARY_THRESHOLD_MS && !sedentaryReminderSent) {
                sedentaryReminderSent = true
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 150, 200), -1))
                onSendMessage(ProtocolHelper.createSedentaryReminder())
            }
            handler.postDelayed(this, 60_000)
        }
    }

    // ── Stand reminder ────────────────────────────────────────────────────────

    private val standCheckRunnable = object : Runnable {
        override fun run() {
            val cal = java.util.Calendar.getInstance()
            val minute = cal.get(java.util.Calendar.MINUTE)
            // At :50 of each hour, check if user hasn't stood yet
            if (minute >= 50 && !currentHourHasStand && !standReminderSent) {
                standReminderSent = true
                vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                onSendMessage(ProtocolHelper.createStandReminder())
            }
            // Reset hourly
            if (minute == 0) {
                currentHourHasStand = false
                standReminderSent = false
            }
            handler.postDelayed(this, 60_000)
        }
    }

    // ── Sync to phone ─────────────────────────────────────────────────────────

    private val syncRunnable = object : Runnable {
        override fun run() {
            pushRings()
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    fun pushRings() {
        val rings = ActivityRingsData(
            moveCaloriesBurned   = moveCalories,
            moveCaloriesGoal     = moveGoalCalories,
            exerciseMinutes      = exerciseMinutes,
            exerciseGoalMinutes  = exerciseGoalMinutes,
            standHours           = Integer.bitCount(standHoursBitmask),
            standGoalHours       = standGoalHours
        )
        onSendMessage(ProtocolHelper.createActivityRingsUpdate(rings))
    }

    // ── Goal setting ──────────────────────────────────────────────────────────

    fun applyGoal(data: ActivityGoalData) {
        moveGoalCalories = data.moveCaloriesGoal
        exerciseGoalMinutes = data.exerciseGoalMinutes
        standGoalHours = data.standGoalHours
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun todayKey(): String {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    private fun loadToday() {
        val today = todayKey()
        if (prefs.getString(KEY_DATE, "") != today) {
            // New day — reset
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_MOVE_CALS, 0)
                .putInt(KEY_EXERCISE_MIN, 0)
                .putInt(KEY_STAND_HOURS, 0)
                .apply()
        }
        moveCalories    = prefs.getInt(KEY_MOVE_CALS, 0)
        exerciseMinutes = prefs.getInt(KEY_EXERCISE_MIN, 0)
        standHoursBitmask = prefs.getInt(KEY_STAND_HOURS, 0)
    }

    private fun saveToday() {
        prefs.edit()
            .putString(KEY_DATE, todayKey())
            .putInt(KEY_MOVE_CALS, moveCalories)
            .putInt(KEY_EXERCISE_MIN, exerciseMinutes)
            .putInt(KEY_STAND_HOURS, standHoursBitmask)
            .apply()
    }
}
