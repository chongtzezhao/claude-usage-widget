package com.usage.claudewidget.data

/**
 * A subscription provider supported by the app. Each provider keeps its own
 * encrypted credentials, plain-text usage snapshot, WorkManager jobs, and widget.
 *
 * The Claude values are deliberately identical to the pre-Codex app so existing
 * installs keep their encrypted session and snapshot after an in-place update.
 */
enum class Provider(
    val displayName: String,
    val base: String,
    val loginUrl: String,
    val sessionCookie: String,
    val challengeUrl: String,
    val snapshotPrefsName: String,
    val securePrefsName: String,
    val workName: String,
    val workNameNow: String,
    /** Strip WebView's `; wv` marker so Cloudflare/OpenAI treat us like Chrome. */
    val cleanWebViewUserAgent: Boolean,
) {
    CLAUDE(
        displayName = "Claude",
        base = Const.BASE,
        loginUrl = Const.LOGIN_URL,
        sessionCookie = Const.COOKIE_SESSION,
        challengeUrl = Const.CHALLENGE_URL,
        snapshotPrefsName = "claude_usage",
        securePrefsName = "claude_secure",
        workName = Const.WORK_NAME,
        workNameNow = "${Const.WORK_NAME}-now",
        cleanWebViewUserAgent = false,
    ),
    CODEX(
        displayName = "Codex",
        base = CodexConst.BASE,
        loginUrl = CodexConst.LOGIN_URL,
        sessionCookie = CodexConst.COOKIE_SESSION,
        challengeUrl = CodexConst.CHALLENGE_URL,
        snapshotPrefsName = "codex_usage",
        securePrefsName = "codex_secure",
        workName = CodexConst.WORK_NAME,
        workNameNow = "${CodexConst.WORK_NAME}-now",
        cleanWebViewUserAgent = true,
    );

    companion object {
        fun fromName(value: String?): Provider =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CLAUDE
    }
}
