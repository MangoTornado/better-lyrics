package com.betterlyrics.app.lyrics.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the Musixmatch field accepts.
 *
 * The thing a user can actually get hold of is the whole `musixmatchUserToken` cookie, which
 * holds one token per Musixmatch client — and a token only works with the client it was
 * issued for. Two of those clients are traps: `web-desktop-app-v1.0` is the discontinued
 * desktop app and the endpoint refuses it outright, and the `-dev` and `-pp` entries are
 * staging clients. Picking the right one has to be the app's job, because nothing about the
 * cookie tells the user which is which.
 *
 * The tokens below are made up. Real ones belong to somebody's account.
 */
class MusixmatchCredentialTest {

    private fun cookie(vararg entries: Pair<String, String>): String {
        val body = entries.joinToString(",") { (id, token) -> """"$id":"$token"""" }
        return """{"tokens":{$body},"version":1}"""
    }

    private val goodToken = "2609aaaabbbbccccddddeeeeffff0000111122223333"
    private val otherToken = "2609999988887777666655554444333322221111abcd"

    @Test
    fun `a bare token is paired with the client the app uses`() {
        // Nothing says which client it belongs to, so this is a guess that is allowed to fail.
        val credential = parseMusixmatchCredential(goodToken)
        assertEquals(goodToken, credential?.token)
        assertEquals(MusixmatchProvider.APP_ID, credential?.appId)
    }

    @Test
    fun `a cookie yields the token and its client id together`() {
        val credential = parseMusixmatchCredential(cookie("mxm-com-v1.0" to goodToken))
        assertEquals(goodToken, credential?.token)
        assertEquals("mxm-com-v1.0", credential?.appId)
    }

    @Test
    fun `a URL-encoded cookie is decoded first`() {
        // Which is how it appears when copied out of a browser.
        val encoded = java.net.URLEncoder.encode(
            cookie("mxm-com-v1.0" to goodToken),
            "UTF-8",
        )
        assertEquals(goodToken, parseMusixmatchCredential(encoded)?.token)
    }

    @Test
    fun `the cookie can arrive inside a whole cookie header`() {
        val header = "mxm_bab=AB; _ga=GA1.1.886; musixmatchUserToken=" +
            java.net.URLEncoder.encode(cookie("mxm-account-v1.0" to goodToken), "UTF-8") +
            "; _gcl_au=1.1.818"
        val credential = parseMusixmatchCredential(header)
        assertEquals(goodToken, credential?.token)
        assertEquals("mxm-account-v1.0", credential?.appId)
    }

    @Test
    fun `the refused desktop client is never chosen`() {
        // `web-desktop-app-v1.0` answers 401 on the live endpoint. Preferring it over a
        // client that works would break the source for anyone who pasted their cookie.
        val credential = parseMusixmatchCredential(
            cookie(
                "web-desktop-app-v1.0" to otherToken,
                "mxm-com-v1.0" to goodToken,
            ),
        )
        assertEquals(goodToken, credential?.token)
        assertEquals("mxm-com-v1.0", credential?.appId)
    }

    @Test
    fun `staging clients are never chosen`() {
        // -dev and -pp point at Musixmatch's own test environments.
        assertNull(
            parseMusixmatchCredential(
                cookie(
                    "mxm-com-v1.0-dev" to goodToken,
                    "musixmatch-podcasts-v2.0-pp" to otherToken,
                ),
            ),
        )
    }

    @Test
    fun `the app's own client wins when the cookie ever carries it`() {
        val credential = parseMusixmatchCredential(
            cookie(
                "mxm-com-v1.0" to otherToken,
                MusixmatchProvider.APP_ID to goodToken,
            ),
        )
        assertEquals(goodToken, credential?.token)
        assertEquals(MusixmatchProvider.APP_ID, credential?.appId)
    }

    @Test
    fun `a dead token is refused wherever it comes from`() {
        // The discontinued endpoint hands out fifty-six zeros, and a request carrying one
        // returns lyrics for an unrelated song.
        val zeros = "0".repeat(56)
        assertNull(parseMusixmatchCredential(zeros))
        assertNull(parseMusixmatchCredential(cookie("mxm-com-v1.0" to zeros)))
        assertNull(parseMusixmatchCredential("UpgradeOnlyUpgradeOnlyUpgradeOnly"))
    }

    @Test
    fun `nothing in the field means nothing to use`() {
        assertNull(parseMusixmatchCredential(null))
        assertNull(parseMusixmatchCredential("   "))
        assertNull(parseMusixmatchCredential("not a token"))
        assertNull(parseMusixmatchCredential("""{"tokens":{}}"""))
    }
}
