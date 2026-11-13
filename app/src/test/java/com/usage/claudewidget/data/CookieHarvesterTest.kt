package com.usage.claudewidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieHarvesterTest {

    @Test
    fun readsSimpleNames() {
        val raw = "sessionKey=abc123; cf_clearance=xyz; lastActiveOrg=org-1"

        assertEquals(
            listOf("sessionKey", "cf_clearance", "lastActiveOrg"),
            CookieHarvester.cookieNames(raw),
        )
    }

    /** NextAuth splits a long session across `.0`, `.1`, ...; every chunk is its own cookie. */
    @Test
    fun readsChunkedNames() {
        val raw = "__Secure-next-auth.session-token.0=partA; " +
            "__Secure-next-auth.session-token.1=partB; cf_clearance=xyz"

        assertEquals(
            listOf(
                "__Secure-next-auth.session-token.0",
                "__Secure-next-auth.session-token.1",
                "cf_clearance",
            ),
            CookieHarvester.cookieNames(raw),
        )
    }

    /** Base64 session values end in `=` padding; only the first `=` separates the name. */
    @Test
    fun keepsEqualsSignsOutOfTheName() {
        val raw = "sessionKey=abc==; __Secure-authjs.session-token=dGVzdA==; other=x=y=z"

        assertEquals(
            listOf("sessionKey", "__Secure-authjs.session-token", "other"),
            CookieHarvester.cookieNames(raw),
        )
    }

    @Test
    fun trimsWhitespaceAroundNames() {
        val raw = "  sessionKey =abc ;\tcf_clearance\t= xyz ;\n lastActiveOrg=org"

        assertEquals(
            listOf("sessionKey", "cf_clearance", "lastActiveOrg"),
            CookieHarvester.cookieNames(raw),
        )
    }

    @Test
    fun ignoresMalformedEntries() {
        val raw = "sessionKey=abc; =noName; noSeparator; ;   ; cf_clearance="

        assertEquals(listOf("sessionKey", "cf_clearance"), CookieHarvester.cookieNames(raw))
    }

    @Test
    fun returnsNothingForAnEmptyJar() {
        assertTrue(CookieHarvester.cookieNames("").isEmpty())
        assertTrue(CookieHarvester.cookieNames("   ;  ; ").isEmpty())
    }

    @Test
    fun listsEachNameOnce() {
        assertEquals(
            listOf("sessionKey", "cf_clearance"),
            CookieHarvester.cookieNames("sessionKey=a; cf_clearance=b; sessionKey=c"),
        )
    }
}
