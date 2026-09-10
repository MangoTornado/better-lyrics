package com.melisma.app.lyrics.provider

import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wire contract with a cache server, tested against a response a real one actually sent.
 *
 * `docs/CACHE-SERVER.md` describes it, but a document cannot fail. This can: the fixture is a
 * captured reply from melisma-server, so if either side drifts — the envelope shape, the
 * TTML dialect, the field the credit lives in — the build says so instead of the app quietly
 * showing no lyrics. A cache server that returns something unreadable looks exactly like a
 * track nobody has transcribed, which is the failure worth making loud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheServerContractTest {

    private class FakeCredentials(
        override val cacheServerUrl: String? = "http://192.168.1.20:8787",
        override val cacheServerKey: String? = null,
        override val spotifyBrowserTokenEnabled: Boolean = false,
    ) : ProviderCredentials {
        override val lrcLibBaseUrl = Settings.DEFAULT_LRCLIB_URL
        override val neteaseBaseUrl = Settings.DEFAULT_NETEASE_URL
        override val amllBaseUrl = Settings.DEFAULT_AMLL_URL
        override val spotifyWebToken: String? = null
        override val neteaseCookie: String? = null
        override val musixmatchUserToken: String? = null
        override val appleDeveloperToken: String? = null
        override val appleMusicUserToken: String? = null
        override val appleStorefront = "us"
        override var spDcCookie: String? = null
        override var musixmatchGuestToken: String? = null
        override var cachedSpotifyToken: String? = null
        override var cachedSpotifyTokenExpiresAt = 0L
    }

    private val provider = CacheServerProvider(FakeCredentials())

    private fun fixture(): String =
        javaClass.classLoader!!
            .getResourceAsStream("cache-server-response.json")!!
            .reader()
            .readText()

    @Test
    fun `a real server response reads as word-by-word lyrics`() {
        val document = provider.documentFrom(fixture())
        assertNotNull("the envelope did not parse at all", document)
        document!!

        assertEquals(LyricsKind.SYLLABLE, document.kind)
        val lines = document.lines.filterNot { it.isInterlude }
        assertEquals(3, lines.size)
        assertEquals("夢ならば", lines[0].text)
        assertEquals(1_372, lines[0].startMs)
        assertEquals(4, lines[0].syllables.size)
        assertTrue(lines[0].syllables.all { it.startMs > 0 })
    }

    @Test
    fun `the readings and translations the server merged survive the hop`() {
        // The whole reason for going through a server: it assembled these from several sources.
        // Losing them here would make the round trip pointless.
        val lines = provider.documentFrom(fixture())!!.lines.filterNot { it.isInterlude }
        assertEquals("yu me na ra ba", lines[0].romanized)
        assertEquals("如果只是一场梦", lines[0].translated)
        assertTrue(provider.documentFrom(fixture())!!.hasRomanization)
        assertTrue(provider.documentFrom(fixture())!!.hasTranslation)
    }

    @Test
    fun `the credit names the sources rather than the cache`() {
        // Without this every track would say it came from a cache server, and the volunteer who
        // hand-timed the file would go unnamed.
        val document = provider.documentFrom(fixture())!!
        assertEquals("AMLL TTML Database · timed by cybaka520", document.providerName)
        assertEquals("cacheserver", document.providerId)
    }

    @Test
    fun `the metadata comment the server writes is not mistaken for content`() {
        // It records which sources the merge drew on. A parser that surfaced it would put
        // "merged by melisma-server" on screen as a lyric.
        val document = provider.documentFrom(fixture())!!
        assertTrue(document.lines.none { it.text.contains("merged by") })
    }

    @Test
    fun `a bare TTML body works without any envelope`() {
        // So the app can point at a directory of files, not only at a server that wraps them.
        val ttml = """
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word">
              <body><div>
                <p begin="00:01.000" end="00:03.000" itunes:key="L1"
                   ><span begin="00:01.000" end="00:02.000">bare</span
                   ><span begin="00:02.000" end="00:03.000"> file</span></p>
              </div></body>
            </tt>
        """.trimIndent()
        val document = provider.documentFrom(ttml)
        assertNotNull(document)
        assertEquals(LyricsKind.SYLLABLE, document!!.kind)
        assertEquals("bare file", document.lines.first { !it.isInterlude }.text)
    }

    @Test
    fun `an LRC body works too, enhanced or plain`() {
        val enhanced = provider.documentFrom("[00:01.00]<00:01.00>word <00:02.00>by")!!
        assertEquals(LyricsKind.SYLLABLE, enhanced.kind)

        val plain = provider.documentFrom("[00:01.00]just a line\n[00:05.00]and another")!!
        assertEquals(LyricsKind.LINE, plain.kind)
    }

    @Test
    fun `an envelope with no lyrics in it is nothing, not an empty document`() {
        // A blank document would be shown as a track with no words rather than falling through
        // to the other sources.
        assertNull(provider.documentFrom("""{"status":404,"key":"q:x|y|0"}"""))
        assertNull(provider.documentFrom("""{"status":200,"data":{"format":"ttml"}}"""))
        assertNull(provider.documentFrom(""))
        assertNull(provider.documentFrom("   \n  "))
    }

    @Test
    fun `a page that is markup but not lyrics is refused`() {
        // The realistic accident: a misconfigured server, or something in front of it, answering
        // 200 with an error page. Rendering that as a lyric would be worse than finding nothing.
        assertNull(provider.documentFrom("<!doctype html><html><body>502 Bad Gateway</body></html>"))
        assertNull(provider.documentFrom("<?xml version=\"1.0\"?><error>nope</error>"))
    }

    @Test
    fun `plain text with no timestamps is still lyrics`() {
        // Unsynced lyrics are a real answer — LRCLIB returns them — so this must not be caught
        // by the guard above. They are worth showing, just without a sweep.
        val document = provider.documentFrom("First line\nSecond line")
        assertNotNull(document)
        assertEquals(LyricsKind.STATIC, document!!.kind)
        assertEquals(2, document.lines.size)
    }

    @Test
    fun `the source name is used as the credit when there is no provider name`() {
        val document = provider.documentFrom(
            """{"data":{"format":"lrc","lyrics":"[00:01.00]hi","source":"applemusic"}}""",
        )!!
        assertEquals("Cache server · applemusic", document.providerName)
    }

    @Test
    fun `the key is only sent when one is set`() {
        // A server on your own Wi-Fi should let a lookup through unauthenticated; one further
        // away should not. Sending an empty bearer would fail against both.
        assertTrue(CacheServerProvider(FakeCredentials()).isConfigured)
        assertTrue(
            CacheServerProvider(FakeCredentials(cacheServerKey = "abc123")).isConfigured,
        )
        assertTrue(!CacheServerProvider(FakeCredentials(cacheServerUrl = null)).isConfigured)
    }
}
