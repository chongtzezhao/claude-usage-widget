package com.usage.claudewidget.data

import org.json.JSONObject

/** One usage window (5-hour or 7-day). */
data class Window(
    val utilization: Float,      // percent, 0..100
    val resetsAtEpochMs: Long,   // absolute reset time
)

/** Parsed snapshot of the Claude /usage endpoint. */
data class UsageSnapshot(
    val fiveHour: Window,
    val sevenDay: Window,
    val fetchedAtEpochMs: Long,
) {
    companion object {
        fun parse(body: String, now: Long): UsageSnapshot {
            val root = JSONObject(body)
            return UsageSnapshot(
                fiveHour = root.getJSONObject("five_hour").toWindow(),
                sevenDay = root.getJSONObject("seven_day").toWindow(),
                fetchedAtEpochMs = now,
            )
        }

        private fun JSONObject.toWindow(): Window {
            val util = optDouble("utilization", 0.0).toFloat()
            val reset = optString("resets_at", "")
            return Window(util, Iso8601.toEpochMs(reset))
        }
    }
}

/**
 * Parsed snapshot of ChatGPT/Codex's /backend-api/wham/usage endpoint.
 * Either window can be null: some plans expose only a weekly (or monthly) cap.
 */
data class CodexSnapshot(
    val fiveHour: Window?,
    val weekly: Window?,
    val weeklyWindowSeconds: Long,
    val planType: String,
    val fetchedAtEpochMs: Long,
) {
    companion object {
        fun parse(body: String, now: Long): CodexSnapshot {
            val root = JSONObject(body)
            val rateLimit = root.optJSONObject("rate_limit")

            // A wrong-shaped 200 (e.g. {"detail": ...}) must not erase good data.
            require(
                root.has("rate_limit") ||
                    root.has("primary_window") ||
                    root.has("secondary_window")
            ) { "missing rate-limit shape" }

            val candidates = buildList {
                (rateLimit?.optJSONObject("primary_window")
                    ?: root.optJSONObject("primary_window"))
                    ?.let { add(true to it) }
                (rateLimit?.optJSONObject("secondary_window")
                    ?: root.optJSONObject("secondary_window"))
                    ?.let { add(false to it) }
            }

            var fiveHour: Window? = null
            var weekly: Window? = null
            var weeklySeconds = 604_800L
            var other: Pair<Long, Window>? = null

            candidates.forEach { (isPrimary, window) ->
                val parsed = window.toWindow(now) ?: return@forEach
                val seconds = window.optLong("limit_window_seconds", -1L)
                when {
                    seconds in 1..21_600 -> fiveHour = parsed          // ~5 hours
                    seconds >= 518_400 -> {                            // >= 6 days
                        weekly = parsed
                        weeklySeconds = seconds
                    }
                    seconds < 0 && isPrimary -> fiveHour = parsed       // legacy positional
                    seconds < 0 -> weekly = parsed
                    else -> if (other == null) other = seconds to parsed
                }
            }

            // A single monthly window (free tier) still gets a bar, labelled by duration.
            if (fiveHour == null && weekly == null) {
                other?.let { (seconds, window) ->
                    weekly = window
                    weeklySeconds = seconds
                }
            }

            return CodexSnapshot(
                fiveHour = fiveHour,
                weekly = weekly,
                weeklyWindowSeconds = weeklySeconds,
                planType = root.stringOrNull("plan_type").orEmpty(),
                fetchedAtEpochMs = now,
            )
        }

        private fun JSONObject.toWindow(now: Long): Window? {
            if (!has("used_percent")) return null
            val used = optDouble("used_percent", -1.0)
            if (used < 0.0 || used.isNaN()) return null

            val resetAt = optLong("reset_at", 0L)
            val resetMs = when {
                // Some cohorts have been observed returning milliseconds.
                resetAt > 100_000_000_000L -> resetAt
                resetAt > 0L -> resetAt * 1000L
                else -> {
                    val after = optLong("reset_after_seconds", -1L)
                    if (after >= 0L) now + after * 1000L else 0L
                }
            }
            return Window(used.toFloat().coerceIn(0f, 100f), resetMs)
        }

        /** org.json's optString returns the literal "null" for a JSON null. */
        private fun JSONObject.stringOrNull(key: String): String? =
            if (isNull(key)) null
            else optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}
