package com.usage.claudewidget.data

/**
 * One persisted Claude usage observation.
 *
 * Percentages keep the API's full float precision: nothing here is rounded,
 * truncated, or coerced to Int before storage. Only the widget's display path
 * may format a value for humans.
 */
data class UsageRecord(
    val atEpochMs: Long,
    val fiveHourPct: Float,
    val fiveHourResetMs: Long,
    val weekPct: Float,
    val weekResetMs: Long,
)

/** Weekly points a fully-used 5H session consumes, with the sample count behind it. */
data class SessionCalibration(
    val pointsPerSession: Float,
    val samples: Int,
)

/**
 * Estimates how many fully-used 5H sessions the remaining weekly quota covers.
 *
 * A completed 5H window (same reset, two or more readings) shows how many weekly
 * points that window consumed: 100 * dWeek / dFive. Windows worth trusting are
 * folded into an EWMA; the estimate then divides the remaining weekly quota by
 * that figure and clamps it by the time left in the week.
 */
object SessionEstimate {
    /** Newest records kept in the rolling usage history. */
    const val HISTORY_LIMIT = 1000

    /** Weight of the newest window sample. */
    const val ALPHA = 0.2f

    /** Below this much 5H progress a window is too quiet to calibrate from. */
    const val MIN_FIVE_HOUR_DELTA = 1.0f

    /** A window's last reading must be this close to the 5H reset. */
    const val RESET_SLACK_MS = 45 * 60 * 1000L

    const val FIVE_HOUR_MS = 5 * 60 * 60 * 1000L

    /**
     * The calibration sample one closed 5H window yields, or null when the
     * window carries no usable signal.
     */
    fun windowSample(window: List<UsageRecord>): Float? {
        if (window.size < 2) return null
        val first = window.first()
        val last = window.last()
        // A weekly reset inside the window makes the weekly delta meaningless.
        if (window.any { it.weekResetMs != first.weekResetMs }) return null
        if (first.weekPct < 0f || last.weekPct < 0f) return null
        if (first.fiveHourResetMs - last.atEpochMs > RESET_SLACK_MS) return null

        val dFive = last.fiveHourPct - first.fiveHourPct
        val dWeek = last.weekPct - first.weekPct
        if (dFive <= MIN_FIVE_HOUR_DELTA || dWeek <= 0f) return null

        return 100f * dWeek / dFive
    }

    /** Folds one window sample into the EWMA; the first sample initialises it. */
    fun fold(previous: SessionCalibration?, sample: Float): SessionCalibration =
        if (previous == null || previous.samples <= 0) {
            SessionCalibration(sample, 1)
        } else {
            SessionCalibration(
                ALPHA * sample + (1f - ALPHA) * previous.pointsPerSession,
                previous.samples + 1,
            )
        }

    /**
     * Remaining full sessions at [now], limited by both the weekly quota and the
     * time left before the weekly reset. Full float precision; null until
     * calibration exists or when the weekly window is unusable.
     */
    fun remainingFullSessions(
        calibration: SessionCalibration?,
        sevenDayPct: Float,
        sevenDayResetMs: Long,
        now: Long,
    ): Float? {
        if (calibration == null || calibration.samples <= 0) return null
        val perSession = calibration.pointsPerSession
        if (!perSession.isFinite() || perSession <= 0f) return null
        if (sevenDayPct < 0f || sevenDayResetMs <= now) return null

        val remaining = (100f - sevenDayPct).coerceAtLeast(0f)
        val quotaSessions = remaining / perSession
        val timeSessions = (sevenDayResetMs - now) / FIVE_HOUR_MS.toFloat()
        return minOf(quotaSessions, timeSessions)
    }
}
