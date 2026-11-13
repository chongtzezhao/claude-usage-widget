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
