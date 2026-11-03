package com.usage.claudewidget.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Two-tier storage per provider:
 *  - [secure]   EncryptedSharedPreferences for credentials (session cookies, UA, ids).
 *  - [snapshot] Plain prefs for the non-secret usage snapshot the widget renders on every frame.
 *
 * Each provider has its own pair of preference files. The Claude file names and keys
 * are unchanged from v1.0.x, so upgrading in place keeps the existing sign-in.
 */
class Storage private constructor(
    val provider: Provider,
    private val secure: SharedPreferences,
    private val snapshot: SharedPreferences,
) {
    // ---- credentials (encrypted) ----
    var sessionKey: String?
        get() = secure.getString("sessionKey", null)
        set(v) = secure.edit().putString("sessionKey", v).apply()

    /** Raw chatgpt.com Cookie header, replayed verbatim so NextAuth .0/.1 chunks survive. */
    var chatGptCookie: String?
        get() = secure.getString("chatgpt_cookie", null)
        set(v) = secure.edit().putString("chatgpt_cookie", v).apply()

    var cfClearance: String?
        get() = secure.getString("cf_clearance", null)
        set(v) = secure.edit().putString("cf_clearance", v).apply()

    var userAgent: String?
        get() = secure.getString("user_agent", null)
        set(v) = secure.edit().putString("user_agent", v).apply()

    var orgId: String?
        get() = secure.getString("org_id", null)
        set(v) = secure.edit().putString("org_id", v).apply()

    val isLoggedIn: Boolean
        get() = when (provider) {
            Provider.CLAUDE -> !sessionKey.isNullOrBlank()
            Provider.CODEX -> !chatGptCookie.isNullOrBlank()
        }

    fun clearCredentials() {
        secure.edit().clear().apply()
    }

    // ---- usage snapshot (plain, read by the widget) ----
    var fiveHourUtil: Float
        get() = snapshot.getFloat("fh_util", -1f)
        set(v) = snapshot.edit().putFloat("fh_util", v).apply()

    var fiveHourReset: Long
        get() = snapshot.getLong("fh_reset", 0L)
        set(v) = snapshot.edit().putLong("fh_reset", v).apply()

    var sevenDayUtil: Float
        get() = snapshot.getFloat("wk_util", -1f)
        set(v) = snapshot.edit().putFloat("wk_util", v).apply()

    var sevenDayReset: Long
        get() = snapshot.getLong("wk_reset", 0L)
        set(v) = snapshot.edit().putLong("wk_reset", v).apply()

    /** Duration of the weekly-style window; used only to pick a "1W" vs "30D" label. */
    var weeklyWindowSeconds: Long
        get() = snapshot.getLong("wk_seconds", 604_800L)
        set(v) = snapshot.edit().putLong("wk_seconds", v).apply()

    var planType: String
        get() = snapshot.getString("plan_type", "").orEmpty()
        set(v) = snapshot.edit().putString("plan_type", v).apply()

    var fetchedAt: Long
        get() = snapshot.getLong("fetched_at", 0L)
        set(v) = snapshot.edit().putLong("fetched_at", v).apply()

    /** AuthState ordinal; widget shows "Tap to sign in" when NEEDS_LOGIN. */
    var authState: AuthState
        get() = AuthState.entries.getOrElse(snapshot.getInt("auth_state", 0)) { AuthState.OK }
        set(v) = snapshot.edit().putInt("auth_state", v.ordinal).apply()

    /**
     * The last three 5H readings, sampled at the normal ~15-minute cadence.
     * Returns an empty list when the window reset or the cadence was broken,
     * so the widget never computes a rate across a gap.
     */
    @Synchronized
    fun fiveHourSamples(): List<FiveHourSample> {
        val reset = snapshot.getLong("fh_samples_reset", 0L)
        if (reset <= 0L || reset != fiveHourReset) return emptyList()
        return decodeFiveHourSamples(snapshot.getString("fh_samples", "").orEmpty())
    }

    /**
     * Appends a 5H reading. A changed reset time or a drop in utilisation
     * starts a new window. An early reading (for example a manual tap) is
     * ignored so it cannot break the 15-minute cadence; a late reading starts
     * a new window because the local rate is no longer fresh.
     */
    @Synchronized
    fun recordFiveHourSample(atEpochMs: Long, pct: Float, resetAtEpochMs: Long) {
        if (atEpochMs <= 0L || pct < 0f || resetAtEpochMs <= 0L) {
            clearFiveHourSamples()
            return
        }

        var samples = fiveHourSamples()
        val previousReset = snapshot.getLong("fh_samples_reset", 0L)
        if (previousReset != 0L && previousReset != resetAtEpochMs) samples = emptyList()

        if (samples.isNotEmpty()) {
            val last = samples.last()
            val gap = atEpochMs - last.atEpochMs
            when {
                pct + 0.01f < last.pct -> samples = emptyList()
                gap <= 0L -> return
                gap < FiveHourSampling.GAP_MS - FiveHourSampling.TOLERANCE_MS -> return
                gap > FiveHourSampling.GAP_MS + FiveHourSampling.TOLERANCE_MS -> samples = emptyList()
            }
        }

        val next = (samples + FiveHourSample(atEpochMs, pct)).takeLast(FiveHourSampling.COUNT)
        snapshot.edit()
            .putString("fh_samples", encodeFiveHourSamples(next))
            .putLong("fh_samples_reset", resetAtEpochMs)
            .apply()
    }

    @Synchronized
    fun clearFiveHourSamples() {
        snapshot.edit()
            .remove("fh_samples")
            .remove("fh_samples_reset")
            .apply()
    }

    private fun decodeFiveHourSamples(raw: String): List<FiveHourSample> =
        raw.split(';').mapNotNull { entry ->
            val at = entry.substringBefore(':', "").toLongOrNull()
            val pct = entry.substringAfter(':', "").toFloatOrNull()
            if (at != null && pct != null) FiveHourSample(at, pct) else null
        }.takeLast(FiveHourSampling.COUNT)

    private fun encodeFiveHourSamples(samples: List<FiveHourSample>): String =
        samples.joinToString(";") { "${it.atEpochMs}:${it.pct}" }

    val hasSnapshot: Boolean get() = fetchedAt > 0L

    /** Codex plans may expose only one window; -1f means "not provided by the plan". */
    val fiveHourMissing: Boolean get() = fiveHourUtil < 0f
    val sevenDayMissing: Boolean get() = sevenDayUtil < 0f

    fun saveSnapshot(s: UsageSnapshot) {
        recordFiveHourSample(
            s.fetchedAtEpochMs,
            s.fiveHour.utilization,
            s.fiveHour.resetsAtEpochMs,
        )
        snapshot.edit()
            .putFloat("fh_util", s.fiveHour.utilization)
            .putLong("fh_reset", s.fiveHour.resetsAtEpochMs)
            .putFloat("wk_util", s.sevenDay.utilization)
            .putLong("wk_reset", s.sevenDay.resetsAtEpochMs)
            .putLong("wk_seconds", 604_800L)
            .putLong("fetched_at", s.fetchedAtEpochMs)
            .apply()
    }

    fun saveCodexSnapshot(s: CodexSnapshot) {
        val five = s.fiveHour
        if (five != null) {
            recordFiveHourSample(s.fetchedAtEpochMs, five.utilization, five.resetsAtEpochMs)
        } else {
            clearFiveHourSamples()
        }
        snapshot.edit()
            .putFloat("fh_util", s.fiveHour?.utilization ?: -1f)
            .putLong("fh_reset", s.fiveHour?.resetsAtEpochMs ?: 0L)
            .putFloat("wk_util", s.weekly?.utilization ?: -1f)
            .putLong("wk_reset", s.weekly?.resetsAtEpochMs ?: 0L)
            .putLong("wk_seconds", s.weeklyWindowSeconds)
            .putString("plan_type", s.planType)
            .putLong("fetched_at", s.fetchedAtEpochMs)
            .apply()
    }

    companion object {
        private val instances = mutableMapOf<Provider, Storage>()

        fun get(context: Context, provider: Provider = Provider.CLAUDE): Storage =
            synchronized(this) {
                instances.getOrPut(provider) { build(context.applicationContext, provider) }
            }

        private fun build(app: Context, provider: Provider): Storage {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                app,
                provider.securePrefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val snapshot = app.getSharedPreferences(provider.snapshotPrefsName, Context.MODE_PRIVATE)
            return Storage(provider, secure, snapshot)
        }
    }
}

enum class AuthState { OK, NEEDS_LOGIN }
