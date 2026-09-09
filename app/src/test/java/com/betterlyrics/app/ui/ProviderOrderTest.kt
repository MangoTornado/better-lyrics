package com.betterlyrics.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Writing a dragged order back into the stored one.
 *
 * The list on screen is not the list in preferences: the cache server sits in the stored order
 * with no row of its own unless developer options are on. So the reorder that comes back out of
 * the drag describes only the rows that were visible, and laying it back down carelessly would
 * either drop the hidden entry or move it somewhere the user never put it — the first silently
 * disables a source, the second silently changes which one is asked first.
 */
class ProviderOrderTest {

    private val stored = listOf("local", "cacheserver", "amll", "netease", "lrclib")

    @Test
    fun `a hidden entry keeps its place`() {
        // The rows shown, with the last two dragged past each other.
        val shown = listOf("local", "amll", "lrclib", "netease")
        assertEquals(
            listOf("local", "cacheserver", "amll", "lrclib", "netease"),
            stored.withVisibleOrder(shown),
        )
    }

    @Test
    fun `a hidden entry is not dragged over`() {
        // "amll" moved to the front of the visible rows. The cache server is not one of them, so
        // it stays in the second slot and ends up ranked between them — which is the same thing
        // the arrows did, and the only answer that does not invent a position for a row the user
        // cannot see.
        val shown = listOf("amll", "local", "netease", "lrclib")
        assertEquals(
            listOf("amll", "cacheserver", "local", "netease", "lrclib"),
            stored.withVisibleOrder(shown),
        )
    }

    @Test
    fun `every row visible means the order is taken as given`() {
        val shown = listOf("lrclib", "netease", "amll", "cacheserver", "local")
        assertEquals(shown, stored.withVisibleOrder(shown))
    }

    @Test
    fun `an unchanged order changes nothing`() {
        val shown = listOf("local", "amll", "netease", "lrclib")
        assertEquals(stored, stored.withVisibleOrder(shown))
    }

    @Test
    fun `a list that is not a rearrangement is refused`() {
        // The guard against a stale drag: if the rows changed while a finger was down — developer
        // options switched off in another window, a provider list that has since grown — the drag
        // describes a list that no longer exists, and applying it would drop or duplicate a
        // source. Better to lose the reorder than the source.
        assertEquals(stored, stored.withVisibleOrder(listOf("amll", "spotify")))
        assertEquals(stored, stored.withVisibleOrder(listOf("amll", "amll")))
        assertEquals(stored, stored.withVisibleOrder(emptyList()))
    }
}
