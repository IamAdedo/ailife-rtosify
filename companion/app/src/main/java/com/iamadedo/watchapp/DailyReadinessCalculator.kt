package com.iamadedo.watchapp

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Computes a Daily Readiness Score (0–100) mirroring Pixel Watch 3 / Fitbit's
 * readiness algorithm.
 *
 * Three pillars weighted equally:
 *  1. Sleep Score        — from the last SleepSessionData recorded
 *  2. Resting Heart Rate — deviation from 30-day personal baseline
 *  3. HRV (SDNN)        — deviation from 30-day personal baseline
 *
 * Output:
 *  score         0–100
 *  recommendation  PUSH / MODERATE / RECOVER
 *
 * The score is persisted daily and sent to the phone as DAILY_READINESS_SCORE.
 * Also drives CARDIO_LOAD_UPDATE and TARGET_LOAD_UPDATE.
 */
class DailyReadinessCalculator(private val context: Context) {

    companion object {
        private const val PREFS = "readiness_prefs"
        private const val KEY_RHR_HISTORY  = "rhr_history"   // CSV of last 30 resting HRs
        private const val KEY_HRV_HISTORY  = "hrv_history"   // CSV of last 30 SDNNs
        private const val KEY_TODAY_SCORE  = "today_score"
        private const val KEY_TODAY_DATE   = "today_date"
        private const val HISTORY_DAYS     = 30
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record overnight resting HR and HRV, then compute today's readiness.
     * Call once per morning after SleepSessionData arrives.
     */
    fun compute(
        sleepScore: Int,
        restingHrBpm: Int,
        overnightSdnn: Float
    ): DailyReadinessData {
        recordRhr(restingHrBpm)
        recordHrv(overnightSdnn)

        val rhrScore  = rhrScore(restingHrBpm)
        val hrvScore  = hrvScore(overnightSdnn)
        val sleepPart = sleepScore.coerceIn(0, 100)

        // Weighted average: sleep 40%, RHR 30%, HRV 30%
        val composite = (sleepPart * 0.40f + rhrScore * 0.30f + hrvScore * 0.30f).roundToInt()
            .coerceIn(0, 100)

        val recommendation = when {
            composite >= 70 -> "PUSH"
            composite >= 45 -> "MODERATE"
            else            -> "RECOVER"
        }

        val result = DailyReadinessData(
            score             = composite,
            sleepScore        = sleepPart,
            restingHrScore    = rhrScore,
            hrvScore          = hrvScore.roundToInt(),
            recommendation    = recommendation
        )

        // Persist for widget / watch face
        prefs.edit()
            .putInt(KEY_TODAY_SCORE, composite)
            .putString(KEY_TODAY_DATE, todayKey())
            .apply()

        return result
    }

    fun todayScore(): Int = if (prefs.getString(KEY_TODAY_DATE, "") == todayKey())
        prefs.getInt(KEY_TODAY_SCORE, -1) else -1

    // ── Scoring helpers ───────────────────────────────────────────────────────

    /**
     * RHR score: 100 if at/below 30-day average, drops as it rises above.
     * +5 bpm above baseline → ~60 pts, +10 bpm → ~30 pts.
     */
    private fun rhrScore(rhr: Int): Int {
        val baseline = rhrBaseline() ?: return 70   // no history yet → neutral
        val delta = rhr - baseline
        return when {
            delta <= 0  -> 100
            delta <= 3  -> 85
            delta <= 5  -> 70
            delta <= 8  -> 50
            delta <= 12 -> 30
            else        -> 10
        }
    }

    /**
     * HRV score: 100 if at/above 30-day average, drops as it falls below.
     * -10% → ~70 pts, -30% → ~30 pts.
     */
    private fun hrvScore(sdnn: Float): Float {
        val baseline = hrvBaseline() ?: return 70f
        if (baseline <= 0f) return 70f
        val ratio = sdnn / baseline
        return when {
            ratio >= 1.0f -> 100f
            ratio >= 0.9f -> 85f
            ratio >= 0.8f -> 70f
            ratio >= 0.7f -> 55f
            ratio >= 0.6f -> 35f
            else          -> 15f
        }
    }

    // ── History management ────────────────────────────────────────────────────

    private fun recordRhr(bpm: Int) {
        val history = loadHistory(KEY_RHR_HISTORY).toMutableList()
        history.add(bpm.toFloat())
        if (history.size > HISTORY_DAYS) history.removeAt(0)
        saveHistory(KEY_RHR_HISTORY, history)
    }

    private fun recordHrv(sdnn: Float) {
        val history = loadHistory(KEY_HRV_HISTORY).toMutableList()
        history.add(sdnn)
        if (history.size > HISTORY_DAYS) history.removeAt(0)
        saveHistory(KEY_HRV_HISTORY, history)
    }

    private fun rhrBaseline(): Int? {
        val h = loadHistory(KEY_RHR_HISTORY)
        return if (h.size >= 3) h.average().roundToInt() else null
    }

    private fun hrvBaseline(): Float? {
        val h = loadHistory(KEY_HRV_HISTORY)
        return if (h.size >= 3) h.average().toFloat() else null
    }

    private fun loadHistory(key: String): List<Float> {
        val csv = prefs.getString(key, "") ?: ""
        return if (csv.isEmpty()) emptyList()
        else csv.split(",").mapNotNull { it.trim().toFloatOrNull() }
    }

    private fun saveHistory(key: String, list: List<Float>) {
        prefs.edit().putString(key, list.joinToString(",")).apply()
    }

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
}
