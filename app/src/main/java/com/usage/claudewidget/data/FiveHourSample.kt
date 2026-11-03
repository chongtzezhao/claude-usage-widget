package com.usage.claudewidget.data

/** One 5H utilisation observation used for the local burn-rate estimate. */
data class FiveHourSample(
    val atEpochMs: Long,
    val pct: Float,
)

/** Sampling rules for the local 5H rate: three readings, one 15-minute cadence. */
object FiveHourSampling {
    const val COUNT = 3
    const val GAP_MS = 15 * 60 * 1000L

    /** WorkManager is usually on time, but allow a little scheduling jitter. */
    const val TOLERANCE_MS = 2 * 60 * 1000L
}
