package com.iamadedo.watchapp

/**
 * Post-workout fitness metric estimator.
 *
 * Estimates:
 *  1. VO2Max — using the Uth-Sørensen-Overgaard-Pedersen formula:
 *              VO2Max ≈ 15 × (HRmax / HRrest)
 *     Then corrected using pace data when available (running pace method).
 *
 *  2. Running Ability Index (0-100) — composite of VO2Max estimate + pace + age factor.
 *
 *  3. HR Recovery — drop from peak HR at 1-minute post exercise.
 *     (caller must provide hrAt1MinPost from HealthDataCollector polling)
 *
 *  4. Recovery Time — recommended rest hours based on training load (TRIMP).
 *
 *  5. Workout Evaluation — aggregated score 0-100 with zone breakdown.
 *
 * All estimates use only data available on the watch — no network required.
 */
object Vo2MaxEstimator {

    // ── VO2Max ────────────────────────────────────────────────────────────────

    /**
     * Uth-Sørensen formula. Simple, requires only max and resting HR.
     * @param hrMax    Max HR recorded during workout (bpm)
     * @param hrRest   Resting HR (bpm) from overnight monitoring
     */
    fun estimateVo2MaxFromHr(hrMax: Int, hrRest: Int): Float {
        if (hrRest <= 0 || hrMax <= hrRest) return 0f
        return 15f * hrMax.toFloat() / hrRest.toFloat()
    }

    /**
     * Running pace method (more accurate for running workouts).
     * Uses Daniels' Running Formula approximation.
     * @param paceSecPerKm  Average pace in seconds/km
     * @param hrAvg         Average HR during run
     * @param hrMax         Max HR of runner (220 - age)
     */
    fun estimateVo2MaxFromPace(paceSecPerKm: Int, hrAvg: Int, hrMax: Int): Float {
        if (paceSecPerKm <= 0 || hrMax <= 0) return 0f
        val speedKmH = 3600f / paceSecPerKm
        // VDOT approximation: VO2 = -4.60 + 0.182258 × speed + 0.000104 × speed²  (Daniels)
        val vo2 = -4.60f + 0.182258f * speedKmH + 0.000104f * speedKmH * speedKmH
        // Fraction of VO2Max = fraction of HRmax
        val hrFraction = hrAvg.toFloat() / hrMax
        return if (hrFraction > 0) vo2 / hrFraction else 0f
    }

    fun fitnessLevel(vo2Max: Float, ageYears: Int, isMale: Boolean): String {
        // WHO / ACE percentile tables simplified
        val thresholds = if (isMale) {
            when {
                ageYears < 30 -> listOf(25f, 34f, 42f, 52f, 60f)
                ageYears < 40 -> listOf(24f, 32f, 40f, 49f, 57f)
                ageYears < 50 -> listOf(22f, 30f, 37f, 46f, 54f)
                else          -> listOf(20f, 27f, 34f, 42f, 50f)
            }
        } else {
            when {
                ageYears < 30 -> listOf(22f, 29f, 36f, 45f, 52f)
                ageYears < 40 -> listOf(20f, 27f, 34f, 43f, 50f)
                ageYears < 50 -> listOf(18f, 25f, 31f, 39f, 46f)
                else          -> listOf(16f, 22f, 28f, 35f, 42f)
            }
        }
        return when {
            vo2Max < thresholds[0] -> "POOR"
            vo2Max < thresholds[1] -> "BELOW_AVERAGE"
            vo2Max < thresholds[2] -> "FAIR"
            vo2Max < thresholds[3] -> "GOOD"
            vo2Max < thresholds[4] -> "EXCELLENT"
            else                   -> "SUPERIOR"
        }
    }

    // ── Running Ability Index ─────────────────────────────────────────────────

    fun runningAbilityIndex(vo2Max: Float, bestPace5kmSecPerKm: Int?): RunningAbilityData {
        // Index = VO2Max-based score (0-100), capped at 85 without pace
        val vo2Score = (vo2Max / 75f * 85f).coerceIn(0f, 85f)
        val paceBonus = if (bestPace5kmSecPerKm != null && bestPace5kmSecPerKm > 0) {
            // Sub-3:00/km = 15 pts, 8:00/km = 0 pts — linear
            ((480 - bestPace5kmSecPerKm) / 300f * 15f).coerceIn(0f, 15f)
        } else 0f
        val index = (vo2Score + paceBonus).toInt().coerceIn(0, 100)

        // Project paces
        val pace5km  = if (bestPace5kmSecPerKm != null) bestPace5kmSecPerKm
                       else paceFromVo2Max(vo2Max, 5000)
        val pace10km = paceFromVo2Max(vo2Max * 0.94f, 10000)  // 10k effort ~94% VO2Max

        return RunningAbilityData(
            index = index,
            paceProjection5km = pace5km,
            paceProjection10km = pace10km
        )
    }

    private fun paceFromVo2Max(vo2: Float, distanceM: Int): Int {
        if (vo2 <= 0) return 0
        // Inverse of Daniels' approximation — solve for speed
        // vo2 = -4.60 + 0.182258v + 0.000104v²  (v in km/h)
        val a = 0.000104f; val b = 0.182258f; val c = -4.60f - vo2
        val disc = b * b - 4 * a * c
        if (disc < 0) return 0
        val speedKmH = (-b + kotlin.math.sqrt(disc)) / (2 * a)
        return (3600f / speedKmH).toInt()
    }

    // ── HR Recovery ───────────────────────────────────────────────────────────

    fun hrRecovery(hrPeakBpm: Int, hrAt1MinBpm: Int): HrRecoveryData {
        val drop = hrPeakBpm - hrAt1MinBpm
        val classification = when {
            drop >= 25 -> "EXCELLENT"
            drop >= 18 -> "GOOD"
            drop >= 12 -> "AVERAGE"
            else       -> "POOR"
        }
        return HrRecoveryData(
            hrAtPeakBpm = hrPeakBpm,
            hrAt1MinBpm = hrAt1MinBpm,
            dropBpm = drop,
            classification = classification
        )
    }

    // ── Recovery Time ─────────────────────────────────────────────────────────

    /**
     * TRIMP (Training Impulse) score → recommended recovery hours.
     * TRIMP = duration(min) × hrRatio × 0.64 × e^(1.92 × hrRatio)
     * @param durationMin  Workout duration in minutes
     * @param avgHr        Average HR during workout
     * @param restHr       Resting HR
     * @param maxHr        Max HR of the runner (220 - age)
     */
    fun recoveryHours(durationMin: Int, avgHr: Int, restHr: Int, maxHr: Int): Int {
        if (maxHr <= restHr) return 12
        val hrRatio = (avgHr - restHr).toFloat() / (maxHr - restHr)
        val trimp = durationMin * hrRatio * 0.64f * Math.exp((1.92f * hrRatio).toDouble()).toFloat()
        return when {
            trimp < 50  -> 12
            trimp < 100 -> 24
            trimp < 200 -> 36
            trimp < 350 -> 48
            else        -> 72
        }
    }

    // ── Workout Evaluation ────────────────────────────────────────────────────

    fun evaluateWorkout(
        workoutType: String,
        durationMinutes: Int,
        avgHrBpm: Int,
        peakHrBpm: Int,
        zoneDistribution: Map<Int, Int>,
        vo2MaxEstimate: Float?,
        hrRest: Int,
        hrMax: Int
    ): WorkoutEvaluationData {
        val recHours = if (hrMax > hrRest)
            recoveryHours(durationMinutes, avgHrBpm, hrRest, hrMax) else 12

        // Score = 40% zone quality + 30% duration + 30% intensity
        val highZoneMin = (zoneDistribution[3] ?: 0) + (zoneDistribution[4] ?: 0) + (zoneDistribution[5] ?: 0)
        val zoneScore = (highZoneMin.toFloat() / durationMinutes.coerceAtLeast(1) * 40f).coerceIn(0f, 40f)
        val durationScore = (durationMin.toFloat() / 60f * 30f).coerceIn(0f, 30f)
        val intensityRatio = if (hrMax > 0) peakHrBpm.toFloat() / hrMax else 0.8f
        val intensityScore = (intensityRatio * 30f).coerceIn(0f, 30f)
        val overall = (zoneScore + durationScore + intensityScore).toInt().coerceIn(0, 100)

        return WorkoutEvaluationData(
            workoutType      = workoutType,
            durationMinutes  = durationMinutes,
            avgHrBpm         = avgHrBpm,
            peakHrBpm        = peakHrBpm,
            zoneDistribution = zoneDistribution,
            vo2MaxEstimate   = vo2MaxEstimate,
            recoveryHours    = recHours,
            overallScore     = overall
        )
    }
}
