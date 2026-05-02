package com.iamadedo.phoneapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.Calendar

/**
 * Morning Briefing — Pixel Watch 3 / Fitbit feature.
 *
 * At a configurable time each morning (default 07:00) the phone assembles a
 * MorningBriefingData payload and pushes it to the watch as MORNING_BRIEFING.
 *
 * Payload includes:
 *  - Daily Readiness score (from last night's sleep + HRV)
 *  - Sleep score from last session
 *  - Target load recommendation for the day
 *  - One-line weather summary (from last WeatherSyncManager cache)
 *  - Weekly step goal progress (%)
 *
 * The watch displays this as a full-screen "Good morning" card on first raise.
 */
class MorningBriefingManager(
    private val context: Context,
    private val onSendMessage: (ProtocolMessage) -> Unit
) {
    companion object {
        private const val PREFS           = "morning_briefing"
        private const val KEY_HOUR        = "briefing_hour"
        private const val KEY_MINUTE      = "briefing_minute"
        private const val DEFAULT_HOUR    = 7
        private const val DEFAULT_MINUTE  = 0
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    fun start() {
        if (running) return
        running = true
        scheduleNext()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    fun setBriefingTime(hour: Int, minute: Int) {
        prefs.edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
        if (running) { handler.removeCallbacksAndMessages(null); scheduleNext() }
    }

    private fun scheduleNext() {
        val hour   = prefs.getInt(KEY_HOUR, DEFAULT_HOUR)
        val minute = prefs.getInt(KEY_MINUTE, DEFAULT_MINUTE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val delayMs = cal.timeInMillis - System.currentTimeMillis()
        handler.postDelayed(::sendBriefing, delayMs)
    }

    private fun sendBriefing() {
        val weatherPrefs = context.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)
        val weatherSummary = buildWeatherSummary(weatherPrefs.getString("last_weather", null))

        // Read today's readiness from shared prefs (written by phone's health aggregation)
        val healthPrefs = context.getSharedPreferences("health_summary", Context.MODE_PRIVATE)
        val readiness   = healthPrefs.getInt("readiness_score", 0)
        val sleepScore  = healthPrefs.getInt("last_sleep_score", 0)
        val stepGoalPct = healthPrefs.getInt("weekly_step_pct", 0)

        // Derive target load text
        val targetLoad = when {
            readiness >= 70 -> "Push — your body is ready for a hard effort"
            readiness >= 45 -> "Moderate — a comfortable steady workout fits today"
            else            -> "Recover — rest or light movement only"
        }

        val briefing = MorningBriefingData(
            readinessScore  = readiness,
            sleepScore      = sleepScore,
            targetLoad      = targetLoad,
            weatherSummary  = weatherSummary,
            stepGoalProgress = stepGoalPct
        )

        onSendMessage(ProtocolHelper.createMorningBriefing(briefing))

        // Schedule the next day
        if (running) scheduleNext()
    }

    private fun buildWeatherSummary(json: String?): String {
        if (json == null) return "Weather unavailable"
        return try {
            val obj = org.json.JSONObject(json)
            val temp = obj.optDouble("currentTempC", 0.0).toInt()
            val cond = obj.optString("conditionCode", "CLEAR")
                .replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() }
            "$cond, ${temp}°C"
        } catch (e: Exception) { "Weather unavailable" }
    }
}
