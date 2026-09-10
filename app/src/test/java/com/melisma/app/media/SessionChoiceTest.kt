package com.melisma.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which media session the app follows.
 *
 * The reason this is tested away from the framework: the answer decides more than what is on
 * screen. Everything that asks "is something playing?" — the timer that lets the process go, the
 * screen-awake flag, whether the background drifts — asks it by reading the session this chose. So
 * a player the user switched off under "Players to follow" being chosen here would not look like a
 * wrong lyric; it would look like the app refusing to close while a YouTube video played in another
 * room.
 */
class SessionChoiceTest {

    private fun candidate(
        name: String,
        playing: Boolean = false,
        hasMetadata: Boolean = true,
        lastPlayingAt: Long = 0L,
    ) = Candidate(
        packageName = name,
        hasMetadata = hasMetadata,
        isPlaying = playing,
        lastPlayingAt = lastPlayingAt,
        owner = name,
    )

    @Test
    fun `an ignored player is never chosen, even while it is the only thing playing`() {
        val chosen = chooseSession(
            listOf(candidate("com.google.android.youtube", playing = true, lastPlayingAt = 500L)),
            ignored = setOf("com.google.android.youtube"),
        )
        // Nothing at all, rather than the video: this is what makes the rest of the app agree that
        // nothing is playing, and it is why an ignored video cannot hold the app open.
        assertNull(chosen)
    }

    @Test
    fun `a paused player you follow beats an ignored one that is playing`() {
        val chosen = chooseSession(
            listOf(
                candidate("com.spotify.music", playing = false, lastPlayingAt = 100L),
                candidate("com.google.android.youtube", playing = true, lastPlayingAt = 900L),
            ),
            ignored = setOf("com.google.android.youtube"),
        )
        assertEquals("com.spotify.music", chosen?.owner)
        // And it is honestly reported as not playing, which is the point.
        assertEquals(false, chosen?.isPlaying)
    }

    @Test
    fun `something playing wins over something that played more recently`() {
        val chosen = chooseSession(
            listOf(
                candidate("a", playing = false, lastPlayingAt = 900L),
                candidate("b", playing = true, lastPlayingAt = 100L),
            ),
            ignored = emptySet(),
        )
        assertEquals("b", chosen?.owner)
    }

    @Test
    fun `between two playing sessions the most recent to start wins`() {
        val chosen = chooseSession(
            listOf(
                candidate("a", playing = true, lastPlayingAt = 100L),
                candidate("b", playing = true, lastPlayingAt = 800L),
            ),
            ignored = emptySet(),
        )
        assertEquals("b", chosen?.owner)
    }

    @Test
    fun `with nothing playing the last one to play stays on screen`() {
        // Rather than jumping to whichever silent session happens to be first: the paused track you
        // were listening to is the one you want the lyrics of.
        val chosen = chooseSession(
            listOf(
                candidate("a", lastPlayingAt = 100L),
                candidate("b", lastPlayingAt = 700L),
            ),
            ignored = emptySet(),
        )
        assertEquals("b", chosen?.owner)
    }

    @Test
    fun `a session with no title is not a track`() {
        // Players publish an empty session while they start up, and a browser publishes one for a
        // tab with no media at all.
        assertNull(chooseSession(listOf(candidate("a", playing = true, hasMetadata = false)), emptySet()))
    }

    @Test
    fun `nothing at all is nothing`() {
        assertNull(chooseSession(emptyList<Candidate<String>>(), emptySet()))
    }
}
