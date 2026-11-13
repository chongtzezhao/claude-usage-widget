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
        // A different account must not inherit this account's calibration.
        snapshot.edit()
            .remove("usage_history")
            .remove("sessions_c")
            .remove("sessions_n")
            .apply()
    }

    /** Removes just the provider session, keeping the snapshot for diagnostics. */
    fun clearSessionCookie() {
        when (provider) {
            Provider.CLAUDE -> sessionKey = null
            Provider.CODEX -> chatGptCookie = null
        }
        orgId = null
    }

    /**
     * Monotonic marker bumped by sign-out. A refresh that started before the
     * sign-out compares it before writing, so a late worker cannot resurrect
     * credentials or a snapshot for a signed-out provider.
     */
    val sessionEpoch: Long get() = snapshot.getLong("session_epoch", 0L)

    /** Full sign-out: credentials, history/calibration and the on-widget snapshot. */
    @Synchronized
    fun signOut() {
        val next = sessionEpoch + 1
        clearCredentials()
        snapshot.edit().clear().putLong("session_epoch", next).apply()
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
     * The recent 5H readings. Returns an empty list when the stored readings
     * belong to a different 5H window, so the widget never computes a rate
     * across a reset. The readings themselves may be any distance apart.
     */
    @Synchronized
    fun fiveHourSamples(): List<FiveHourSample> {
        val reset = snapshot.getLong("fh_samples_reset", 0L)
        if (reset <= 0L || !sameWindow(reset, fiveHourReset)) return emptyList()
        return decodeFiveHourSamples(snapshot.getString("fh_samples", "").orEmpty())
    }

    /**
     * Appends a 5H reading. A new 5H window (reset time jumped, or utilisation
     * fell) starts the buffer over; a slow refresh does not, because the OS
     * never delivers the requested 15-minute cadence. The readings and the
     * reset they belong to are committed together so a render can never see
     * them disagree.
     */
    @Synchronized
    fun recordFiveHourSample(atEpochMs: Long, pct: Float, resetAtEpochMs: Long) {
        val previousReset = snapshot.getLong("fh_samples_reset", 0L)
        val next = mergeFiveHourSamples(
            existing = fiveHourSamples(),
            previousResetAtEpochMs = previousReset,
            atEpochMs = atEpochMs,
            pct = pct,
            resetAtEpochMs = resetAtEpochMs,
        )
        if (next.isEmpty()) {
            clearFiveHourSamples()
            return
        }
        snapshot.edit()
            .putString("fh_samples", encodeFiveHourSamples(next))
            .putLong("fh_samples_reset", resetAtEpochMs)
            // Keep the window keys in the same commit; saveSnapshot writes the
            // same value again and a render can never catch them mid-flight.
            .putLong("fh_reset", resetAtEpochMs)
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

    /** Rolling history of successful snapshots, oldest first (one file per provider). */
    @Synchronized
    fun usageHistory(): List<UsageRecord> =
        SessionEstimate.decodeHistory(snapshot.getString("usage_history", "").orEmpty())

    /**
     * Appends a usable record to [editor]. A changed 5H reset (beyond
     * [sameWindow]'s drift tolerance) closes the previous window, whose
     * calibration sample is folded into the stored EWMA when it carries
     * signal. Records with unknown resets or bad numbers, and refreshes less
     * than [SessionEstimate.MIN_RECORD_GAP_MS] into the same window, are
     * ignored so bursts cannot manufacture tiny windows.
     *
     * Reset timestamps drift slightly between fetches, so "same window" and
     * "weekly reset happened" both use [sameWindow]'s tolerance instead of
     * strict equality. Claude and Codex share this code; each call writes to
     * its own provider's snapshot prefs file, so their histories and EWMAs
     * never mix.
     */
    @Synchronized
    private fun appendUsage(editor: SharedPreferences.Editor, record: UsageRecord) {
        if (!SessionEstimate.isUsableRecord(record)) return
        val history = usageHistory()
        val last = history.lastOrNull()
        if (last != null) {
            if (record.atEpochMs <= last.atEpochMs) return
            if (sameWindow(record.fiveHourResetMs, last.fiveHourResetMs) &&
                record.atEpochMs - last.atEpochMs < SessionEstimate.MIN_RECORD_GAP_MS
            ) {
                return
            }
        }

        val closed = last?.let {
            if (sameWindow(it.fiveHourResetMs, record.fiveHourResetMs)) return@let null
            SessionEstimate.windowSample(
                history.takeLastWhile { r -> sameWindow(r.fiveHourResetMs, it.fiveHourResetMs) }
            )
        }

        val calibration = closed?.let { SessionEstimate.fold(calibration(), it) }
        editor.putString(
            "usage_history",
            SessionEstimate.encodeHistory(
                (history + record).takeLast(SessionEstimate.HISTORY_LIMIT)
            ),
        )
        if (calibration != null) {
            editor
                .putFloat("sessions_c", calibration.pointsPerSession)
                .putInt("sessions_n", calibration.samples)
        }
    }

    /** Stored EWMA; null while no window has been calibrated yet. */
    private fun calibration(): SessionCalibration? {
        val samples = snapshot.getInt("sessions_n", 0)
        if (samples <= 0) return null
        return SessionCalibration(snapshot.getFloat("sessions_c", 0f), samples)
    }

    val hasSnapshot: Boolean get() = fetchedAt > 0L

    /** Codex plans may expose only one window; -1f means "not provided by the plan". */
    val fiveHourMissing: Boolean get() = fiveHourUtil < 0f
    val sevenDayMissing: Boolean get() = sevenDayUtil < 0f

    /**
     * Estimated number of fully-used 5H sessions the remaining weekly quota
     * covers, based on the recorded history. Full float precision; formatting
     * belongs to the UI. Null until calibration exists or when the weekly
     * window is unknown or already reset.
     */
    @Synchronized
    fun remainingFullSessions(now: Long = System.currentTimeMillis()): Float? =
        SessionEstimate.remainingFullSessions(calibration(), sevenDayUtil, sevenDayReset, now)

    @Synchronized
    fun saveSnapshot(s: UsageSnapshot) {
        recordFiveHourSample(
            s.fetchedAtEpochMs,
            s.fiveHour.utilization,
            s.fiveHour.resetsAtEpochMs,
        )
        // One editor: history, calibration and the snapshot commit atomically.
        val editor = snapshot.edit()
        appendUsage(
            editor,
            UsageRecord(
                atEpochMs = s.fetchedAtEpochMs,
                fiveHourPct = s.fiveHour.utilization,
                fiveHourResetMs = s.fiveHour.resetsAtEpochMs,
                weekPct = s.sevenDay.utilization,
                weekResetMs = s.sevenDay.resetsAtEpochMs,
            ),
        )
        editor
            .putFloat("fh_util", s.fiveHour.utilization)
            .putLong("fh_reset", s.fiveHour.resetsAtEpochMs)
            .putFloat("wk_util", s.sevenDay.utilization)
            .putLong("wk_reset", s.sevenDay.resetsAtEpochMs)
            .putLong("wk_seconds", 604_800L)
            .putLong("fetched_at", s.fetchedAtEpochMs)
            .apply()
    }

    /**
     * Persists a Codex snapshot and, when the plan exposes both windows,
     * records the same history/calibration Claude keeps. Weekly-only and
     * 5H-only plans record nothing: a weekly delta is meaningless without a
     * matching 5H window. The history shares the snapshot editor so the
     * record commits atomically with the values the widget renders.
     *
     * A plan or window-length change starts the calibration over: the stored
     * points-per-session belong to the old plan's allowance units.
     */
    @Synchronized
    fun saveCodexSnapshot(s: CodexSnapshot) {
        val planChanged =
            (s.planType.isNotBlank() && planType.isNotBlank() && s.planType != planType) ||
                s.weeklyWindowSeconds != weeklyWindowSeconds
        if (planChanged) {
            snapshot.edit()
                .remove("usage_history")
                .remove("sessions_c")
                .remove("sessions_n")
                .apply()
        }
        val five = s.fiveHour
        if (five != null) {
            recordFiveHourSample(s.fetchedAtEpochMs, five.utilization, five.resetsAtEpochMs)
        } else {
            clearFiveHourSamples()
        }
        // One editor: history, calibration and the snapshot commit atomically.
        val editor = snapshot.edit()
        val weekly = s.weekly
        if (five != null && weekly != null) {
            appendUsage(
                editor,
                UsageRecord(
                    atEpochMs = s.fetchedAtEpochMs,
                    fiveHourPct = five.utilization,
                    fiveHourResetMs = five.resetsAtEpochMs,
                    weekPct = weekly.utilization,
                    weekResetMs = weekly.resetsAtEpochMs,
                ),
            )
        }
        editor
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
