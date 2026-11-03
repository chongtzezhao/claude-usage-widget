package com.usage.claudewidget.data

import android.webkit.CookieManager

/** Reads the signed-in provider's cookies out of the WebView jar into [Storage]. */
object CookieHarvester {

    fun harvest(storage: Storage): Boolean {
        val raw = CookieManager.getInstance().getCookie(storage.provider.base) ?: return false
        return when (storage.provider) {
            Provider.CLAUDE -> harvestClaude(raw, storage)
            Provider.CODEX -> harvestCodex(raw, storage)
        }
    }

    /** True when [name] is present, including NextAuth's chunked `name.0`, `name.1` forms. */
    fun hasSessionCookie(raw: String, name: String): Boolean =
        raw.split(';').any { part ->
            val cookie = part.trim()
            cookie.startsWith("$name=") || cookie.startsWith("$name.")
        }

    /** Codex has rotated its session-cookie name across Auth.js versions. */
    fun hasProviderSession(raw: String, provider: Provider): Boolean = when (provider) {
        Provider.CLAUDE -> hasSessionCookie(raw, provider.sessionCookie)
        Provider.CODEX -> CodexConst.COOKIE_SESSIONS.any { hasSessionCookie(raw, it) }
    }

    private fun harvestClaude(raw: String, storage: Storage): Boolean {
        val map = parse(raw)
        var changed = false
        chunkedValue(map, Const.COOKIE_SESSION)?.let {
            storage.sessionKey = it
            changed = true
        }
        map[Const.COOKIE_CF]?.takeIf { it.isNotBlank() }?.let {
            storage.cfClearance = it
            changed = true
        }
        // Best-effort org id fallback straight from the cookie.
        map[Const.COOKIE_LAST_ORG]?.let { if (storage.orgId.isNullOrBlank()) storage.orgId = it }
        return changed
    }

    /** Reads a cookie, joining NextAuth's optional `.0`, `.1`, ... chunks in order. */
    private fun chunkedValue(map: Map<String, String>, name: String): String? {
        map[name]?.takeIf { it.isNotBlank() }?.let { return it }
        val chunks = map.entries
            .mapNotNull { (key, value) ->
                if (!key.startsWith("$name.")) return@mapNotNull null
                key.removePrefix("$name.").toIntOrNull()?.let { it to value }
            }
            .sortedBy { it.first }
        if (chunks.isEmpty()) return null
        return chunks.joinToString("") { it.second }.takeIf { it.isNotBlank() }
    }

    private fun harvestCodex(raw: String, storage: Storage): Boolean {
        if (!hasProviderSession(raw, Provider.CODEX)) return false
        // Store the whole jar string: rebuilding it would break .0/.1 chunk ordering.
        storage.chatGptCookie = raw
        parse(raw)[Const.COOKIE_CF]?.takeIf { it.isNotBlank() }?.let { storage.cfClearance = it }
        return true
    }

    private fun parse(raw: String): Map<String, String> =
        raw.split(';')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()
}
