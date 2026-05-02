package com.iamadedo.watchapp

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Energy Score Calculator — Samsung Galaxy Watch 7 / Health+ feature.
 *
 * Produces a single 0-100 "Energy Score" from five health signals,
 * matching Samsung Health's approach:
 *
 *  Pillar         Weight   Source
 *  ─────────────────────────────────────────────
 *  Sleep          30%      SleepSessionData.score
 *  Activity       20%      Steps + exercise minutes vs goals
 *  HRV            20%      Overnight SDNN vs personal baseline
 *  SpO2           15%      Overnight average SpO2
 *  Skin Temp      15%      Deviation from personal baseline (±0.5°C = full score)
 *
 * Updated once per morning after sleep session ends.
 * Persisted in SharedPreferences; sent via ENERGY_SCORE_UPDATE.
 */
class EnergyScoreCalculator(private val context: Context) {

    companion object {
        private const val PREFS           = "energy_score_prefs"
        private const val KEY_HRV_HISTORY = "hrv_history_30d"
        private const val KEY_TEMP_HISTORY = "temp_history_30d"
        private const val KEY_SCORE       = "today_energy_score"
        private const val KEY_DATE        = "score_date"
        private const val HISTORY_DAYS    = 30
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Compute the energy score after a sleep session completes.
     *
     * @param sleepScore        From SleepSessionData.score (0-100)
     * @param stepsToday        Steps accumulated today
     * @param stepGoal          Daily step goal (default 8000)
     * @param exerciseMinutes   Exercise minutes today
     * @param exerciseGoal      Exercise minute goal (default 30)
     * @param overnightSdnn     HRV (SDNN in ms) from overnight measurement
     * @param avgSpo2           Average SpO2 during sleep (0-100)
     * @param wristTempC        Wrist skin temperature (°C)
     */
    fun compute(
        sleepScore: Int,
        stepsToday: Int,
        stepGoal: Int = 8000,
        exerciseMinutes: Int,
        exerciseGoal: Int = 30,
        overnightSdnn: Float,
        avgSpo2: Int,
        wristTempC: Float
    ): EnergyScoreData {

        // Record histories for baseline computation
        recordHrv(overnightSdnn)
        recordTemp(wristTempC)

        // ── Sleep pillar (30%) ────────────────────────────────────────────────
        val sleepFactor = sleepScore.coerceIn(0, 100)

        // ── Activity pillar (20%) ─────────────────────────────────────────────
        val stepRatio    = (stepsToday.toFloat() / stepGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
        val exerciseRatio = (exerciseMinutes.toFloat() / exerciseGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
        val activityFactor = ((stepRatio * 0.6f + exerciseRatio * 0.4f) * 100).roundToInt()

        // ── HRV pillar (20%) ──────────────────────────────────────────────────
        val hrvBaseline = hrvBaseline() ?: overnightSdnn
        val hrvRatio    = if (hrvBaseline > 0f) overnightSdnn / hrvBaseline else 1f
        val hrvFactor   = when {
            hrvRatio >= 1.1f -> 100
            hrvRatio >= 1.0f -> 90
            hrvRatio >= 0.9f -> 75
            hrvRatio >= 0.8f -> 55
            hrvRatio >= 0.7f -> 35
            else             -> 15
        }

        // ── SpO2 pillar (15%) ─────────────────────────────────────────────────
        val spo2Factor = when {
            avgSpo2 >= 97 -> 100
            avgSpo2 >= 95 -> 85
            avgSpo2 >= 93 -> 60
            avgSpo2 >= 90 -> 35
            else          -> 10
        }

        // ── Skin temperature pillar (15%) ─────────────────────────────────────
        val tempBaseline = tempBaseline() ?: wristTempC
        val tempDev      = kotlin.math.abs(wristTempC - tempBaseline)
        val skinTempFactor = when {
            tempDev <= 0.3f -> 100
            tempDev <= 0.5f -> 80
            tempDev <= 0.8f -> 55
            tempDev <= 1.2f -> 30
            else            -> 10
        }

        // ── Composite ─────────────────────────────────────────────────────────
        val score = (
            sleepFactor     * 0.30f +
            activityFactor  * 0.20f +
            hrvFactor       * 0.20f +
            spo2Factor      * 0.15f +
            skinTempFactor  * 0.15f
        ).roundToInt().coerceIn(0, 100)

        prefs.edit()
            .putInt(KEY_SCORE, score)
            .putString(KEY_DATE, todayKey())
            .apply()

        return EnergyScoreData(
            score           = score,
            sleepFactor     = sleepFactor,
            activityFactor  = activityFactor,
            hrvFactor       = hrvFactor,
            spo2Factor      = spo2Factor,
            skinTempFactor  = skinTempFactor
        )
    }

    fun todayScore(): Int? {
        if (prefs.getString(KEY_DATE, "") != todayKey()) return null
        return prefs.getInt(KEY_SCORE, -1).takeIf { it >= 0 }
    }

    // ── Baseline helpers ──────────────────────────────────────────────────────

    private fun recordHrv(sdnn: Float) = appendHistory(KEY_HRV_HISTORY, sdnn)
    private fun recordTemp(tempC: Float) = appendHistory(KEY_TEMP_HISTORY, tempC)

    private fun hrvBaseline(): Float?  = baseline(KEY_HRV_HISTORY)
    private fun tempBaseline(): Float? = baseline(KEY_TEMP_HISTORY)

    private fun baseline(key: String): Float? {
        val h = loadHistory(key)
        return if (h.size >= 5) h.average().toFloat() else null
    }

    private fun appendHistory(key: String, value: Float) {
        val h = loadHistory(key).toMutableList()
        h.add(value)
        if (h.size > HISTORY_DAYS) h.removeAt(0)
        prefs.edit().putString(key, h.joinToString(",")).apply()
    }

    private fun loadHistory(key: String): List<Float> {
        val csv = prefs.getString(key, "") ?: ""
        return if (csv.isEmpty()) emptyList()
        else csv.split(",").mapNotNull { it.trim().toFloatOrNull() }
    }

    private fun todayKey() =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
}
