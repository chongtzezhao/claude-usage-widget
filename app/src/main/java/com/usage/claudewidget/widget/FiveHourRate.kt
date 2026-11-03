package com.usage.claudewidget.widget

import com.usage.claudewidget.data.FiveHourSample
import com.usage.claudewidget.data.FiveHourSampling
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Local 5H burn rate derived from the last three 15-minute samples. */
data class FiveHourRate(
    val pctPerHour: Float,
    val projectedPct: Int,
    val capsInMinutes: Long?,
)

/**
 * Computes the local burn rate from exactly three readings at the normal
 * 15-minute cadence. Returns null when there is no trustworthy local rate.
 */
fun computeFiveHourRate(
    samples: List<FiveHourSample>,
    usedPct: Int,
    resetAtEpochMs: Long,
    now: Long,
): FiveHourRate? {
    if (samples.size != FiveHourSampling.COUNT || resetAtEpochMs <= now) return null

    val first = samples[0]
    val second = samples[1]
    val last = samples[2]
    if (!isCadence(second.atEpochMs - first.atEpochMs)) return null
    if (!isCadence(last.atEpochMs - second.atEpochMs)) return null

    val spanHours = (last.atEpochMs - first.atEpochMs) / 3_600_000.0
    if (spanHours <= 0.0) return null

    val rawRate = (last.pct - first.pct) / spanHours
    val rate = if (rawRate.isFinite()) rawRate.coerceAtLeast(0.0) else 0.0
    val hoursToReset = (resetAtEpochMs - now) / 3_600_000.0
    if (hoursToReset <= 0.0) return null

    val projected = (usedPct + rate * hoursToReset).coerceIn(0.0, 100.0)
    val capsInMinutes = when {
        usedPct >= 100 -> 0L
        rate > 0.05 && projected >= 100.0 ->
            ((100 - usedPct) / rate * 60).roundToLong().coerceAtLeast(1L)
        else -> null
    }

    return FiveHourRate(rate.toFloat(), projected.roundToInt(), capsInMinutes)
}

private fun isCadence(gapMs: Long): Boolean =
    gapMs >= 0L && abs(gapMs - FiveHourSampling.GAP_MS) <= FiveHourSampling.TOLERANCE_MS
