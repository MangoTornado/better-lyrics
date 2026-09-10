package com.melisma.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache key and the prefetch guard are the same subject seen twice: a prefetch is only
 * worth doing if the key it writes under is the key the track will be read back by.
 */
class TrackInfoTest {

    private fun track(
        title: String = "Lemon",
        artist: String = "Kenshi Yonezu",
        durationMs: Long = 255_000,
        mediaId: String? = null,
    ) = TrackInfo(
        title = title,
        artist = artist,
        album = "",
        durationMs = durationMs,
        mediaId = mediaId,
        artworkUri = null,
        packageName = "com.example.player",
    )

    @Test
    fun `a spotify media id becomes the key`() {
        val id = "7Cd17G3oNQ34OWUwS8ZxfR"
        assertEquals("sp:$id", track(mediaId = "spotify:track:$id").cacheKey)
    }

    @Test
    fun `a one-second reporting difference does not split the entry`() {
        assertEquals(track(durationMs = 255_000).cacheKey, track(durationMs = 255_900).cacheKey)
    }

    @Test
    fun `a queue entry with no duration is not worth prefetching`() {
        // Its key would be `lemon|kenshi yonezu|0`, which is not the key the track gets
        // once it starts playing — the request would be spent and never read back.
        assertFalse(track(durationMs = 0).isPrefetchable)
    }

    @Test
    fun `a duration or an id is enough to prefetch`() {
        assertTrue(track(durationMs = 255_000).isPrefetchable)
        assertTrue(
            track(durationMs = 0, mediaId = "spotify:track:7Cd17G3oNQ34OWUwS8ZxfR").isPrefetchable,
        )
    }

    @Test
    fun `a queue entry with no title is not worth anything`() {
        assertFalse(track(title = "", durationMs = 255_000).isPrefetchable)
    }
}
