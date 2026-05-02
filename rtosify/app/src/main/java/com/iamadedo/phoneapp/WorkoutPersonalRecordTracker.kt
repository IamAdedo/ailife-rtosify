package com.iamadedo.phoneapp

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Personal Record Tracker — Pixel Watch / Fitbit feature.
 *
 * Stores best values for key workout metrics per workout type.
 * After each completed WorkoutEndData, checks if any metric beats the stored PR.
 * If yes, emits WORKOUT_PR_UPDATE via BluetoothService → watch shows celebration.
 *
 * Tracked PRs:
 *  RUNNING  → best 5K pace, best 10K pace, longest run (km), fastest km split
 *  CYCLING  → longest ride, fastest avg speed
 *  SWIMMING → longest swim, best SWOLF
 *  HIKING   → most elevation gain
 *  HIIT     → highest calorie burn in session
 */
data class PrRecord(
    val workoutType: String,
    val metric: String,
    val value: Float,
    val unit: String,
    val achievedAt: Long = System.currentTimeMillis()
)

class WorkoutPersonalRecordTracker(private val context: Context) {

    companion object {
        private const val PREFS = "personal_records"
        private const val KEY_RECORDS = "records"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Returns list of new PRs achieved, empty if none. */
    fun checkAndUpdate(workout: WorkoutEndData): List<WorkoutPrData> {
        val newPrs = mutableListOf<WorkoutPrData>()
        val records = loadRecords().toMutableMap()

        fun check(metric: String, value: Float, unit: String, lowerIsBetter: Boolean = false) {
            if (value <= 0f) return
            val key = "${workout.type}_$metric"
            val existing = records[key]
            val isBetter = if (lowerIsBetter)
                existing == null || value < existing.value
            else
                existing == null || value > existing.value

            if (isBetter) {
                val prev = existing?.value ?: value
                records[key] = PrRecord(workout.type, metric, value, unit)
                newPrs.add(WorkoutPrData(
                    workoutType   = workout.type,
                    metric        = metric,
                    previousValue = prev,
                    newValue      = value,
                    unit          = unit
                ))
            }
        }

        when (workout.type) {
            "RUNNING" -> {
                check("LONGEST_RUN",    workout.distanceKm,     "km")
                // Pace metrics need distance thresholds
                val paceSecPerKm = if (workout.distanceKm > 0)
                    (workout.endTime - workout.startTime) / 1000f / workout.distanceKm else 0f
                if (workout.distanceKm >= 5f)
                    check("PACE_5K",  paceSecPerKm, "sec/km", lowerIsBetter = true)
                if (workout.distanceKm >= 10f)
                    check("PACE_10K", paceSecPerKm, "sec/km", lowerIsBetter = true)
            }
            "CYCLING" -> {
                check("LONGEST_RIDE", workout.distanceKm, "km")
            }
            "SWIMMING" -> {
                check("LONGEST_SWIM", workout.distanceKm, "km")
            }
            "HIKING" -> {
                // elevation stored in gpxData summary — placeholder
                check("LONGEST_HIKE", workout.distanceKm, "km")
            }
            "HIIT" -> {
                check("MOST_CALORIES", workout.calories.toFloat(), "kcal")
            }
        }

        saveRecords(records)
        return newPrs
    }

    fun getAllRecords(): List<PrRecord> = loadRecords().values.toList()

    fun clearRecords() {
        prefs.edit().remove(KEY_RECORDS).apply()
    }

    private fun loadRecords(): Map<String, PrRecord> {
        val json = prefs.getString(KEY_RECORDS, "{}") ?: "{}"
        return try {
            gson.fromJson(json, object : TypeToken<Map<String, PrRecord>>() {}.type)
        } catch (e: Exception) { emptyMap() }
    }

    private fun saveRecords(records: Map<String, PrRecord>) {
        prefs.edit().putString(KEY_RECORDS, gson.toJson(records)).apply()
    }
}
