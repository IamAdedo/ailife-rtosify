package com.iamadedo.watchapp

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Tracks Cardio Load and derives Target Load — matching Pixel Watch 3's Fitbit integration.
 *
 * Cardio Load = TRIMP-based daily training impulse, accumulated per workout.
 * Target Load = recommended daily load based on 7-day acute vs 28-day chronic ratio (ATL/CTL).
 *
 * ATL/CTL model (like Training Peaks TSB):
 *  ATL(n) = ATL(n-1) × e^(-1/7)  + TRIMP(n) × (1 - e^(-1/7))
 *  CTL(n) = CTL(n-1) × e^(-1/28) + TRIMP(n) × (1 - e^(-1/28))
 *
 * Acute:Chronic ratio > 1.3 → overtraining risk → recommend REST / EASY.
 * Ratio 0.8–1.3 → optimal training zone.
 * Ratio < 0.8   → undertrained → encourage more load.
 */
class CardioLoadTracker(private val context: Context) {

    companion object {
        private const val PREFS       = "cardio_load_prefs"
        private const val KEY_ATL     = "atl"
        private const val KEY_CTL     = "ctl"
        private const val KEY_TODAY   = "today_trimp"
        private const val KEY_DATE    = "date"
        private val ATL_DECAY = exp(-1.0 / 7.0).toFloat()
        private val CTL_DECAY = exp(-1.0 / 28.0).toFloat()
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Public API ────────────────────────────────────────────────────────────

    /** Call after each completed workout with its TRIMP value. */
    fun addWorkoutLoad(trimp: Float) {
        val today = todayKey()
        val existing = if (prefs.getString(KEY_DATE, "") == today)
            prefs.getFloat(KEY_TODAY, 0f) else 0f
        prefs.edit()
            .putFloat(KEY_TODAY, existing + trimp)
            .putString(KEY_DATE, today)
            .apply()
        updateModels(existing + trimp)
    }

    /** Call once at midnight to advance the model with zero load if no workout. */
    fun advanceDayNoWorkout() {
        if (prefs.getString(KEY_DATE, "") != todayKey()) {
            updateModels(0f)
        }
    }

    fun getCardioLoadData(): CardioLoadData {
        val atl = prefs.getFloat(KEY_ATL, 0f)
        val ctl = prefs.getFloat(KEY_CTL, 0f)
        val today = prefs.getFloat(KEY_TODAY, 0f)
        val ratio = if (ctl > 0f) atl / ctl else 1f
        val trend = when {
            ratio > 1.15f -> "INCREASING"
            ratio < 0.85f -> "DECREASING"
            else          -> "STABLE"
        }
        return CardioLoadData(
            todayLoad          = today,
            weeklyLoad         = atl * 7f,        // approx weekly ATL
            acuteLoad          = atl,
            chronicLoad        = ctl,
            acuteChronicRatio  = ratio,
            trend              = trend
        )
    }

    fun getTargetLoadData(readinessScore: Int): TargetLoadData {
        val atl = prefs.getFloat(KEY_ATL, 0f)
        val ctl = prefs.getFloat(KEY_CTL, 0f)
        val ratio = if (ctl > 0f) atl / ctl else 1f

        // Target load = bring ratio toward 1.05 (slight positive progression)
        val targetTrimp = (ctl * 1.05f - atl * ATL_DECAY) / (1 - ATL_DECAY)
        val targetMin = (targetTrimp * 0.80f).coerceAtLeast(0f)
        val targetMax = (targetTrimp * 1.20f).coerceAtLeast(targetMin + 10f)

        val (workout, reasoning) = when {
            readinessScore < 40 ->
                "REST" to "Low readiness — prioritise recovery today"
            ratio > 1.5f ->
                "REST" to "Acute:chronic ratio too high — injury risk elevated"
            ratio > 1.3f ->
                "EASY_RUN" to "High load — keep effort easy to absorb training"
            ratio in 0.9f..1.3f && readinessScore >= 70 ->
                "INTERVAL" to "Good readiness and optimal load balance — push hard"
            ratio in 0.9f..1.3f ->
                "TEMPO" to "Moderate readiness — steady-state effort recommended"
            else ->
                "CROSS_TRAIN" to "Building base — light cross-training to increase chronic load"
        }

        return TargetLoadData(
            targetMin        = targetMin,
            targetMax        = targetMax,
            suggestedWorkout = workout,
            reasoning        = reasoning
        )
    }

    // ── Internal model update ─────────────────────────────────────────────────

    private fun updateModels(trimp: Float) {
        val atl = prefs.getFloat(KEY_ATL, 0f)
        val ctl = prefs.getFloat(KEY_CTL, 0f)
        val newAtl = atl * ATL_DECAY + trimp * (1f - ATL_DECAY)
        val newCtl = ctl * CTL_DECAY + trimp * (1f - CTL_DECAY)
        prefs.edit()
            .putFloat(KEY_ATL, newAtl)
            .putFloat(KEY_CTL, newCtl)
            .apply()
    }

    private fun todayKey() =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
}
