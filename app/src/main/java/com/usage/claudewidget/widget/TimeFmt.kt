package com.usage.claudewidget.widget

object TimeFmt {
    /** Reset countdown, e.g. "3h 12m", "5d 2h", "<1m", or "-" if unknown. */
    fun resetsIn(resetEpochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (resetEpochMs <= 0L) return "-"
        var s = (resetEpochMs - now) / 1000
        if (s <= 0) return "now"
        val d = s / 86_400; s %= 86_400
        val h = s / 3_600; s %= 3_600
        val m = s / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "<1m"
        }
    }

    /** Snapshot is considered stale once older than ~2 refresh cycles. */
    fun isStale(fetchedAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (fetchedAt <= 0L) return true
        return now - fetchedAt > 31 * 60 * 1000L // > 31 min
    }

    /**
     * Projects current usage to the end of the window and classifies the pace.
     * Returns null when the window is unknown, not started, already reset, or
     * during its first 5% (too noisy to be meaningful).
     */
    fun pace(
        usedPct: Int,
        resetAtEpochMs: Long,
        windowSeconds: Long,
        now: Long = System.currentTimeMillis(),
    ): Pace? {
        if (resetAtEpochMs <= 0L || windowSeconds <= 0L) return null
        val windowMillis = windowSeconds.toDouble() * 1000.0
        if (windowMillis <= 0.0) return null
        val start = resetAtEpochMs - windowMillis
        if (now <= start || now >= resetAtEpochMs) return null
        val elapsedFraction = (now - start) / windowMillis
        if (elapsedFraction < 0.05) return null

        val used = usedPct.coerceIn(0, 100).toDouble()
        val projected = used / elapsedFraction // percentage at reset
        return when {
            // Already burning through the window near its end: no time to recover.
            used >= 90.0 && elapsedFraction < 1.0 -> Pace.CRITICAL
            projected < 85.0 -> Pace.UNDER
            projected <= 105.0 -> Pace.ON_TRACK
            projected <= 125.0 -> Pace.OVER
            else -> Pace.CRITICAL
        }
    }

    /** Short duration label for a local rate projection, e.g. "1h 20m" or "45m". */
    fun minutesLabel(minutes: Long): String {
        if (minutes <= 0L) return "now"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0L && m > 0L -> "${h}h ${m}m"
            h > 0L -> "${h}h"
            else -> "${m}m"
        }
    }

    /** Human label for a rate-limit window duration; Codex plans vary by tier. */
    fun windowLabel(seconds: Long): String {
        if (seconds <= 0L || seconds == 604_800L) return "1W"
        return when {
            seconds % 86_400L == 0L -> "${seconds / 86_400L}D"
            seconds % 3_600L == 0L -> "${seconds / 3_600L}H"
            else -> "1W"
        }
    }
}
