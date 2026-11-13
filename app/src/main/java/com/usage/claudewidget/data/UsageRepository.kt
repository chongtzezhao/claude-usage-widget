package com.usage.claudewidget.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

sealed interface FetchResult {
    data class Success(val snapshot: UsageSnapshot) : FetchResult
    data class CodexSuccess(val snapshot: CodexSnapshot) : FetchResult
    /** sessionKey is dead; user must log in again. */
    data object NeedsLogin : FetchResult
    /** Transient (no network, Cloudflare unresolved, server error). Keep last snapshot. */
    data class Soft(val reason: String) : FetchResult
}

class UsageRepository(private val context: Context) {

    private val storage = Storage.get(context)
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false) // a 302 to /login means the session is dead
        .build()

    suspend fun refresh(): FetchResult = withContext(Dispatchers.IO) {
        if (!storage.isLoggedIn) return@withContext FetchResult.NeedsLogin
        val epoch = storage.sessionEpoch

        // Make sure we know which org to query.
        val org = storage.orgId ?: when (val o = discoverOrg()) {
            is OrgResult.Found -> o.id.also { storage.orgId = it }
            OrgResult.NeedsLogin -> return@withContext FetchResult.NeedsLogin
            OrgResult.Soft -> return@withContext FetchResult.Soft("org-unknown")
        }

        val result = when (val r = getUsage(org)) {
            is FetchResult.Soft -> {
                // Could be an expired cf_clearance: try one silent WebView re-solve.
                if (r.reason == "cloudflare") {
                    val resolved = CloudflareResolver.resolve(context, storage)
                    if (resolved) getUsage(org) else FetchResult.Soft("cloudflare-unresolved")
                } else r
            }
            else -> r
        }
        if (storage.sessionEpoch != epoch) return@withContext FetchResult.Soft("signed-out")
        storage.authState =
            if (result is FetchResult.NeedsLogin) AuthState.NEEDS_LOGIN else AuthState.OK
        if (result is FetchResult.Success) storage.saveSnapshot(result.snapshot)
        result
    }

    private fun getUsage(org: String): FetchResult {
        val req = buildRequest(Const.usageUrl(org)) ?: return FetchResult.NeedsLogin
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 200 -> FetchResult.Success(
                        UsageSnapshot.parse(body, System.currentTimeMillis())
                    )
                    resp.code == 401 || resp.isRedirect -> FetchResult.NeedsLogin
                    resp.code == 403 || resp.code == 503 || body.contains("Just a moment") ->
                        FetchResult.Soft("cloudflare")
                    else -> FetchResult.Soft("http-${resp.code}")
                }
            }
        } catch (e: Exception) {
            FetchResult.Soft(e.message ?: "io")
        }
    }

    private sealed interface OrgResult {
        data class Found(val id: String) : OrgResult
        data object NeedsLogin : OrgResult
        data object Soft : OrgResult
    }

    private fun discoverOrg(): OrgResult {
        val req = buildRequest(Const.ORGS_URL) ?: return OrgResult.NeedsLogin
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.isRedirect) return OrgResult.NeedsLogin
                if (resp.code != 200) return OrgResult.Soft
                val arr = JSONArray(resp.body?.string().orEmpty())
                if (arr.length() == 0) return OrgResult.Soft
                // Single-org accounts: take [0]. Multi-org: prefer one with a chat capability.
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val caps = o.optJSONArray("capabilities")
                    val isChat = caps != null && (0 until caps.length()).any {
                        caps.optString(it).contains("chat", true) ||
                            caps.optString(it).contains("claude_ai", true)
                    }
                    if (isChat) {
                        return OrgResult.Found(o.optString("uuid").ifBlank { o.optString("id") })
                    }
                }
                OrgResult.Found(
                    arr.getJSONObject(0).let { it.optString("uuid").ifBlank { it.optString("id") } }
                )
            }
        } catch (_: Exception) {
            OrgResult.Soft
        }
    }

    private fun buildRequest(url: String): Request? {
        val session = storage.sessionKey ?: return null
        val cf = storage.cfClearance
        val cookie = buildString {
            append("${Const.COOKIE_SESSION}=$session")
            if (!cf.isNullOrBlank()) append("; ${Const.COOKIE_CF}=$cf")
        }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Cookie", cookie)
        storage.userAgent?.let { builder.header("User-Agent", it) }
        return builder.get().build()
    }
}
