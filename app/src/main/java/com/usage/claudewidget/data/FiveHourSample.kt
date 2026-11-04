package com.usage.claudewidget.data

import kotlin.math.abs

/** One 5H utilisation observation used for the local burn-rate estimate. */
data class FiveHourSample(
    val atEpochMs: Long,
    val pct: Float,
)

/**
 * Sampling rules for the local 5H rate.
 *
 * WorkManager only guarantees an interval of *at least* 15 minutes: on a real
 * device a refresh lands anywhere between 15 minutes and over an hour apart
 * (JobScheduler batches minimum-latency jobs whenever it likes). So the buffer
 * never treats a slow refresh as an error; the rate's trustworthiness lives in
 * [com.usage.claudewidget.widget.computeFiveHourRate], which bounds the span
 * and the age of the readings instead of demanding an exact cadence.
 */
object FiveHourSampling {
    /** Readings kept in the buffer. */
    const val COUNT = 4

    /** Two points already define a slope; more only smooth it. */
    const val MIN_COUNT = 2

    /** Readings closer than this are the same observation (e.g. a rapid tap). */
    const val MIN_GAP_MS = 5 * 60 * 1000L

    /** Shortest span that gives a meaningful slope. */
    const val MIN_SPAN_MS = 10 * 60 * 1000L

    /** Longest span the rate is computed over; older readings are discarded. */
    const val MAX_SPAN_MS = 2 * 60 * 60 * 1000L

    /** A newest reading older than this no longer describes "now". */
    const val MAX_AGE_MS = 2 * 60 * 60 * 1000L

    /** Reset times drift slightly between fetches; a real new window is hours away. */
    const val RESET_TOLERANCE_MS = 5 * 60 * 1000L

    /** A dip smaller than this is rounding noise, not a new 5H window. */
    const val DROP_TOLERANCE_PCT = 1.0f
}

/** True when two reset timestamps describe the same 5H window. */
fun sameWindow(a: Long, b: Long): Boolean =
    a > 0L && b > 0L && abs(a - b) <= FiveHourSampling.RESET_TOLERANCE_MS

/**
 * Applies one reading to the sample buffer.
 *
 * A new 5H window (reset time jumped) or a real utilisation drop starts the
 * buffer over; a slow refresh does not, because the OS never promises a
 * 15-minute cadence. Pure, so the rules can be unit tested without Android.
 */
fun mergeFiveHourSamples(
    existing: List<FiveHourSample>,
    previousResetAtEpochMs: Long,
    atEpochMs: Long,
    pct: Float,
    resetAtEpochMs: Long,
): List<FiveHourSample> {
    if (atEpochMs <= 0L || pct < 0f || resetAtEpochMs <= 0L) return emptyList()

    var samples = existing
    if (previousResetAtEpochMs != 0L && !sameWindow(previousResetAtEpochMs, resetAtEpochMs)) {
        samples = emptyList()
    }

    val last = samples.lastOrNull()
    if (last != null) {
        when {
            // Utilisation fell: the 5H window rolled over (or the server recalculated).
            pct < last.pct - FiveHourSampling.DROP_TOLERANCE_PCT -> samples = emptyList()
            // Out-of-order reading (clock skew): keep what we have.
            atEpochMs < last.atEpochMs -> return samples
            // Same observation, e.g. a tap just after a scheduled refresh.
            atEpochMs - last.atEpochMs < FiveHourSampling.MIN_GAP_MS -> samples = samples.dropLast(1)
        }
    }

    val cutoff = atEpochMs - FiveHourSampling.MAX_SPAN_MS
    return (samples + FiveHourSample(atEpochMs, pct))
        .filter { it.atEpochMs >= cutoff }
        .takeLast(FiveHourSampling.COUNT)
}
