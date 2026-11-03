package com.usage.claudewidget.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches ChatGPT/Codex subscription usage using the cookies harvested from the
 * chatgpt.com WebView login.
 *
 * Flow:
 *   session cookie -> GET /api/auth/session -> short-lived accessToken
 *   accessToken    -> GET /backend-api/wham/usage
 *
 * The access token is never persisted; it is re-minted on every refresh.
 */
class CodexUsageRepository(private val context: Context) {

    private val storage = Storage.get(context, Provider.CODEX)
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false) // a redirect to /auth/login means the session is dead
        .build()

    suspend fun refresh(): FetchResult = withContext(Dispatchers.IO) {
        if (!storage.isLoggedIn) return@withContext FetchResult.NeedsLogin

        // Prefer the live WebView jar: NextAuth rotates the session cookie, so the
        // copy saved at login can be older than the still-valid one on disk.
        CookieHarvester.harvest(storage)
        var cookie = storage.chatGptCookie ?: return@withContext FetchResult.NeedsLogin

        var result = fetchWithCookie(cookie)

        // A Cloudflare challenge is transient: re-mint clearance in an off-screen
        // WebView, then retry once. Never log the user out for a 403/HTML page.
        if (result is FetchResult.Soft && result.reason == "cloudflare") {
            result = if (CloudflareResolver.resolve(context, storage)) {
                cookie = storage.chatGptCookie ?: return@withContext FetchResult.NeedsLogin
                fetchWithCookie(cookie)
            } else {
                FetchResult.Soft("cloudflare-unresolved")
            }
        }

        // A 200 with no usable window must not overwrite the last good snapshot.
        if (result is FetchResult.CodexSuccess &&
            result.snapshot.fiveHour == null &&
            result.snapshot.weekly == null
        ) {
            result = FetchResult.Soft("empty")
        }

        storage.authState =
            if (result is FetchResult.NeedsLogin) AuthState.NEEDS_LOGIN else AuthState.OK
        if (result is FetchResult.CodexSuccess) storage.saveCodexSnapshot(result.snapshot)
        result
    }

    private fun fetchWithCookie(initialCookie: String): FetchResult {
        var cookie = initialCookie
        var token: String

        when (val first = mintToken(cookie)) {
            is TokenResult.Ok -> token = first.token
            TokenResult.NeedsLogin -> {
                // The jar may have rotated to a fresh session cookie; try it once.
                val refreshed = refreshedCookie(cookie) ?: return FetchResult.NeedsLogin
                cookie = refreshed
                when (val second = mintToken(cookie)) {
                    is TokenResult.Ok -> token = second.token
                    TokenResult.NeedsLogin -> return FetchResult.NeedsLogin
                    is TokenResult.Soft -> return FetchResult.Soft(second.reason)
                }
            }
            is TokenResult.Soft -> return FetchResult.Soft(first.reason)
        }

        var usage = getUsage(cookie, token)
        if (usage is FetchResult.NeedsLogin) {
            // The token can expire between calls; mint once more before giving up.
            val refreshed = refreshedCookie(cookie) ?: cookie
            cookie = refreshed
            val fresh = when (val t = mintToken(cookie)) {
                is TokenResult.Ok -> t.token
                TokenResult.NeedsLogin -> return FetchResult.NeedsLogin
                is TokenResult.Soft -> return FetchResult.Soft(t.reason)
            }
            usage = getUsage(cookie, fresh)
        }
        return usage
    }

    /** Re-reads the WebView jar; returns a different cookie only when it actually changed. */
    private fun refreshedCookie(current: String): String? {
        if (!CookieHarvester.harvest(storage)) return null
        return storage.chatGptCookie?.takeIf { it != current }
    }

    private fun mintToken(cookie: String): TokenResult {
        val req = baseRequest(CodexConst.SESSION_URL, cookie)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    looksLikeHtml(body) || resp.header("cf-mitigated") == "challenge" ->
                        TokenResult.Soft("cloudflare")
                    resp.code == 200 -> {
                        val json = runCatching { JSONObject(body) }.getOrNull()
                        val token = json?.stringOrNull("accessToken")
                            ?: json?.stringOrNull("access_token")
                        if (!token.isNullOrBlank()) TokenResult.Ok(token)
                        else TokenResult.NeedsLogin
                    }
                    resp.code == 401 || resp.isRedirect -> TokenResult.NeedsLogin
                    else -> TokenResult.Soft("session-http-${resp.code}")
                }
            }
        } catch (e: Exception) {
            TokenResult.Soft(e.message ?: "io")
        }
    }

    private fun getUsage(cookie: String, token: String): FetchResult {
        val primary = getUsageAt(CodexConst.USAGE_URL, cookie, token)
        if (primary is FetchResult.Soft && primary.reason == "http-404") {
            return getUsageAt(CodexConst.USAGE_FALLBACK_URL, cookie, token)
        }
        return primary
    }

    private fun getUsageAt(url: String, cookie: String, token: String): FetchResult {
        val builder = baseRequest(url, cookie)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
        accountIdFromToken(token)?.let { builder.header("ChatGPT-Account-Id", it) }
        val req = builder.get().build()

        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 200 && looksLikeHtml(body) -> FetchResult.Soft("cloudflare")
                    resp.code == 200 -> {
                        runCatching {
                            FetchResult.CodexSuccess(
                                CodexSnapshot.parse(body, System.currentTimeMillis())
                            )
                        }.getOrElse { FetchResult.Soft("parse") }
                    }
                    isCloudflare(resp.code, body, resp.header("cf-mitigated")) ->
                        FetchResult.Soft("cloudflare")
                    resp.code == 401 || resp.isRedirect -> FetchResult.NeedsLogin
                    else -> FetchResult.Soft("http-${resp.code}")
                }
            }
        } catch (e: Exception) {
            FetchResult.Soft(e.message ?: "io")
        }
    }

    private fun baseRequest(url: String, cookie: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", cookie) // raw jar string, so NextAuth .0/.1 chunks stay intact
            .header("Referer", CodexConst.REFERER)
            .header("Origin", CodexConst.BASE)
        storage.userAgent?.let { builder.header("User-Agent", it) }
        return builder
    }

    /** Reads chatgpt_account_id from the unverified JWT payload, when present. */
    private fun accountIdFromToken(token: String): String? {
        return try {
            val payload = token.split('.').getOrNull(1) ?: return null
            val decoded = String(
                Base64.decode(
                    payload,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
            )
            JSONObject(decoded)
                .optJSONObject("https://api.openai.com/auth")
                ?.optString("chatgpt_account_id")
                ?.takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) {
            null
        }
    }

    private fun isCloudflare(code: Int, body: String, mitigated: String?): Boolean =
        (code == 401 || code == 403 || code == 503) &&
            (mitigated == "challenge" || looksLikeHtml(body))

    private fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<") || body.contains("Just a moment", ignoreCase = true)
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null
        else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private sealed interface TokenResult {
        data class Ok(val token: String) : TokenResult
        data object NeedsLogin : TokenResult
        data class Soft(val reason: String) : TokenResult
    }
}
