package com.betterlyrics.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The extras half of the cache server contract, against a response a real server sent.
 *
 * The two sides live in different repositories, so nothing but a test like this notices when one
 * of them renames a field — and the failure is invisible in use, because a response the app
 * cannot read is indistinguishable from a track the server has never seen.
 *
 * The body below was captured from `better-lyrics-server` at `GET /v1/extras`, not written by
 * hand.
 */
class CacheServerExtrasContractTest {

    private val realResponse = """
        {
         "coverUrl": "https://i.scdn.co/image/cover.jpg",
         "artistImageUrl": "https://i.scdn.co/image/artist.jpg",
         "tempo": 87.5,
         "isrc": "JPU901800227",
         "palette": {
          "bgColor": "1f1f24",
          "spotifyBackground": "#1f1f24"
         },
         "analysis": {
          "beats": [ { "start": 0.5 } ],
          "key": 5,
          "loudness": -6.2
         },
         "metadata": {
          "composerName": "A Writer",
          "albumName": "An Album",
          "hasTimeSyncedLyrics": true
         },
         "source": "spotify"
        }
    """.trimIndent()

    @Test
    fun `a real server response is read`() {
        val extras = parseCachedExtras(realResponse)
        assertEquals("https://i.scdn.co/image/cover.jpg", extras?.coverUrl)
        assertEquals("https://i.scdn.co/image/artist.jpg", extras?.artistImageUrl)
        assertEquals(87.5f, extras?.tempo)
    }

    @Test
    fun `fields the app does not read yet cost nothing`() {
        // The server holds an ISRC, a palette, an audio analysis and album metadata that nothing
        // here consumes. Ignoring them has to be free, or every addition on the server becomes a
        // breaking change for the app.
        assertEquals(87.5f, parseCachedExtras(realResponse)?.tempo)
    }

    @Test
    fun `a data wrapper is allowed, as with the lyrics`() {
        val wrapped = """{"status":200,"data":{"tempo":120.0,"cover":"https://x/y.jpg"}}"""
        val extras = parseCachedExtras(wrapped)
        assertEquals(120f, extras?.tempo)
        // `cover` and `artistImage` are accepted as aliases.
        assertEquals("https://x/y.jpg", extras?.coverUrl)
    }

    @Test
    fun `an answer with nothing in it is nothing`() {
        // Same meaning as the 404 the server sends when it holds nothing: no cover, no fallback
        // chain interrupted.
        assertNull(parseCachedExtras("""{"source":"spotify"}"""))
        assertNull(parseCachedExtras("""{"error":"nothing held for this track"}"""))
        assertNull(parseCachedExtras(""))
        assertNull(parseCachedExtras("not json at all"))
    }

    @Test
    fun `a nonsense tempo is refused rather than passed on`() {
        // It paces the animated background; zero or negative would stop or reverse it.
        assertNull(parseCachedExtras("""{"tempo":0}"""))
        assertNull(parseCachedExtras("""{"tempo":-4}"""))
    }
}
