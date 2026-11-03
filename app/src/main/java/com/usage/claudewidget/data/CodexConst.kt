package com.usage.claudewidget.data

/**
 * Unofficial ChatGPT/Codex web endpoints.
 *
 * The usage endpoint expects a short-lived OAuth access token, which a signed-in
 * WebView session can mint from /api/auth/session using its session cookie.
 */
object CodexConst {
    const val BASE = "https://chatgpt.com"
    const val LOGIN_URL = "$BASE/auth/login"
    const val SESSION_URL = "$BASE/api/auth/session"
    const val USAGE_URL = "$BASE/backend-api/wham/usage"

    /** Older/alternate path seen in the wild; used only if the primary 404s. */
    const val USAGE_FALLBACK_URL = "$BASE/backend-api/codex/usage"

    const val CHALLENGE_URL = "$BASE/"

    /** NextAuth may chunk this cookie as .0, .1, ...; the prefix is stable. */
    const val COOKIE_SESSION = "__Secure-next-auth.session-token"

    /** Newer Auth.js builds (and a few rollout cohorts) use these names instead. */
    const val COOKIE_SESSION_AUTHJS = "__Secure-authjs.session-token"
    const val COOKIE_SESSION_HOST = "__Host-next-auth.session-token"

    val COOKIE_SESSIONS = listOf(COOKIE_SESSION, COOKIE_SESSION_AUTHJS, COOKIE_SESSION_HOST)
    const val REFERER = "$BASE/codex/settings/usage"

    const val WORK_NAME = "codex-usage-refresh"
}
