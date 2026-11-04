package com.usage.claudewidget.widget

import com.usage.claudewidget.data.FiveHourSample
import com.usage.claudewidget.data.FiveHourSampling
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Local 5H burn rate derived from the recent 5H readings. */
data class FiveHourRate(
    val pctPerHour: Float,
    val projectedPct: Int,
    val capsInMinutes: Long?,
)

/**
 * Computes the local burn rate from the readings in the current 5H window.
 *
 * The interval between readings is whatever WorkManager and JobScheduler
 * deliver (15 minutes at best, often an hour), so this accepts any span between
 * [FiveHourSampling.MIN_SPAN_MS] and [FiveHourSampling.MAX_SPAN_MS] and only
 * trusts readings that are recent. Returns null when there is no trustworthy
 * local rate.
 */
fun computeFiveHourRate(
    samples: List<FiveHourSample>,
    usedPct: Int,
    resetAtEpochMs: Long,
    now: Long,
): FiveHourRate? {
    if (samples.size < FiveHourSampling.MIN_COUNT || resetAtEpochMs <= now) return null

    val last = samples.last()
    if (now - last.atEpochMs > FiveHourSampling.MAX_AGE_MS) return null

    // Use the freshest window that still fits the span bound; older readings
    // describe a previous pace, not the current one.
    val cutoff = last.atEpochMs - FiveHourSampling.MAX_SPAN_MS
    val window = samples.filter { it.atEpochMs >= cutoff }
    if (window.size < FiveHourSampling.MIN_COUNT) return null

    val first = window.first()
    val spanMs = last.atEpochMs - first.atEpochMs
    if (spanMs < FiveHourSampling.MIN_SPAN_MS) return null

    val spanHours = spanMs / 3_600_000.0
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
