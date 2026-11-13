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
 *
 * Claude and Codex calibrate independently: each provider records its own
 * history and EWMA in its own snapshot prefs file.
 */
object SessionEstimate {
    /** Newest records kept in the rolling usage history (~7 days at 15 minutes). */
    const val HISTORY_LIMIT = 700

    /** Ignore refreshes this soon after the previous record in the same 5H window. */
    const val MIN_RECORD_GAP_MS = 5 * 60 * 1000L

    /** Weight of the newest window sample. */
    const val ALPHA = 0.2f

    /** Below this much 5H progress a window is too quiet to calibrate from. */
    const val MIN_FIVE_HOUR_DELTA = 10.0f

    /** One 5H session cannot consume more than the whole weekly quota. */
    const val MAX_POINTS_PER_SESSION = 100f

    /**
     * A window's last reading must be this close to the 5H reset. WorkManager
     * batches refreshes to roughly hourly, so the last reading of a window can
     * legitimately be up to ~90 minutes before the reset.
     */
    const val RESET_SLACK_MS = 90 * 60 * 1000L

    const val FIVE_HOUR_MS = 5 * 60 * 60 * 1000L

    /** A record with unknown resets or non-finite/negative percentages is unusable. */
    fun isUsableRecord(record: UsageRecord): Boolean =
        record.atEpochMs > 0L &&
            record.fiveHourResetMs > 0L &&
            record.weekResetMs > 0L &&
            record.fiveHourPct.isFinite() &&
            record.weekPct.isFinite() &&
            record.fiveHourPct >= 0f &&
            record.weekPct >= 0f

    /** `at:fh:fhReset:wk:wkReset`; Float.toString round-trips the stored precision. */
    fun encodeHistory(records: List<UsageRecord>): String =
        records.joinToString(";") {
            "${it.atEpochMs}:${it.fiveHourPct}:${it.fiveHourResetMs}:${it.weekPct}:${it.weekResetMs}"
        }

    fun decodeHistory(raw: String): List<UsageRecord> =
        raw.split(';').mapNotNull { entry ->
            val f = entry.split(':')
            val at = f.getOrNull(0)?.toLongOrNull()
            val fh = f.getOrNull(1)?.toFloatOrNull()
            val fhReset = f.getOrNull(2)?.toLongOrNull()
            val wk = f.getOrNull(3)?.toFloatOrNull()
            val wkReset = f.getOrNull(4)?.toLongOrNull()
            if (at != null && fh != null && fhReset != null && wk != null && wkReset != null) {
                UsageRecord(at, fh, fhReset, wk, wkReset)
            } else null
        }.takeLast(HISTORY_LIMIT)

    /**
     * The calibration sample one closed 5H window yields, or null when the
     * window carries no usable signal.
     *
     * Reset timestamps drift slightly between fetches, so records belong to
     * one window when their 5H resets are within [sameWindow]'s tolerance
     * rather than exactly equal. The same tolerance decides whether the weekly
     * reset happened inside the window. Records with non-positive resets stay
     * unusable. Both providers use this, each against its own prefs history.
     */
    fun windowSample(window: List<UsageRecord>): Float? {
        if (window.size < 2) return null
        val first = window.first()
        val last = window.last()
        if (first.fiveHourResetMs <= 0L) return null
        // The endpoints define the deltas, so they must describe one window.
        // Comparing them directly (rather than every record to the first) also
        // tolerates drift accumulating across the window's readings.
        if (!sameWindow(first.fiveHourResetMs, last.fiveHourResetMs)) return null
        // A weekly reset inside the window makes the weekly delta meaningless.
        if (window.any { !sameWindow(it.weekResetMs, first.weekResetMs) }) return null
        if (first.fiveHourPct < 0f || last.fiveHourPct < 0f) return null
        if (first.weekPct < 0f || last.weekPct < 0f) return null
        if (!first.fiveHourPct.isFinite() || !last.fiveHourPct.isFinite()) return null
        if (!first.weekPct.isFinite() || !last.weekPct.isFinite()) return null
        if (last.fiveHourResetMs - last.atEpochMs > RESET_SLACK_MS) return null

        val dFive = last.fiveHourPct - first.fiveHourPct
        val dWeek = last.weekPct - first.weekPct
        if (!dFive.isFinite() || dFive <= MIN_FIVE_HOUR_DELTA) return null
        if (!dWeek.isFinite() || dWeek <= 0f) return null

        val sample = 100f * dWeek / dFive
        if (!sample.isFinite() || sample <= 0f || sample > MAX_POINTS_PER_SESSION) return null
        return sample
    }

    /**
     * Folds one window sample into the EWMA; the first valid sample initialises
     * it. An invalid sample leaves the calibration untouched.
     */
    fun fold(previous: SessionCalibration?, sample: Float): SessionCalibration? {
        if (!sample.isFinite() || sample <= 0f || sample > MAX_POINTS_PER_SESSION) {
            return previous
        }
        if (previous == null || previous.samples <= 0 || !previous.pointsPerSession.isFinite()) {
            return SessionCalibration(sample, 1)
        }
        return SessionCalibration(
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
        if (!sevenDayPct.isFinite() || sevenDayPct < 0f || sevenDayResetMs <= now) return null

        val remaining = (100f - sevenDayPct).coerceAtLeast(0f)
        val quotaSessions = remaining / perSession
        // Time bound credits the current partial window; it is a cap, not a floor.
        val timeSessions = (sevenDayResetMs - now) / FIVE_HOUR_MS.toFloat()
        return minOf(quotaSessions, timeSessions)
    }
}
