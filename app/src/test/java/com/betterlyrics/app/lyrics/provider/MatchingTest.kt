package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.util.Script
import com.betterlyrics.app.util.detectScript
import com.betterlyrics.app.util.isRtlText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCleanupTest {

    @Test
    fun `strips the decorations catalogues disagree about`() {
        assertEquals("Song", cleanTrackTitle("Song (feat. Someone)"))
        assertEquals("Song", cleanTrackTitle("Song - 2011 Remaster"))
        assertEquals("Song", cleanTrackTitle("Song (Remastered 2015)"))
        assertEquals("Song", cleanTrackTitle("Song - Radio Edit"))
    }

    @Test
    fun `leaves a title that is only decoration alone`() {
        // Better a title we can search for than an empty string.
        assertEquals("(Live)", cleanTrackTitle("(Live)"))
    }

    @Test
    fun `keeps meaningful parentheses`() {
        assertEquals("Song (Reprise)", cleanTrackTitle("Song (Reprise)"))
    }

    @Test
    fun `splits collaborations into individual artists`() {
        assertEquals(listOf("A", "B"), splitArtists("A, B"))
        assertEquals(listOf("A", "B"), splitArtists("A & B"))
        assertEquals(listOf("A", "B"), splitArtists("A feat. B"))
    }
}

class MatchingTest {

    private val request = LyricsRequest(
        title = "Song Title",
        artist = "The Artist",
        album = "The Album",
        durationMs = 210_000,
    )

    @Test
    fun `an exact match scores at the top`() {
        val score = Matching.score(request, "Song Title", "The Artist", 210_000)
        assertTrue("got $score", score > 0.95f)
    }

    @Test
    fun `a different song is rejected`() {
        val score = Matching.score(request, "Something Else Entirely", "Another Band", 130_000)
        assertTrue("got $score", score < Matching.MATCH_THRESHOLD)
    }

    @Test
    fun `a remaster of the same track still matches`() {
        val score = Matching.score(request, "Song Title - 2011 Remaster", "The Artist", 211_000)
        assertTrue("got $score", score >= Matching.MATCH_THRESHOLD)
    }

    @Test
    fun `a name in another script is unknown, not wrong`() {
        // The community and East Asian catalogues credit artists in the original script,
        // while the media session reports whatever Spotify chose — usually Latin. Letter
        // similarity between the two is zero, but they are the same person, so a correct
        // match must survive it.
        val japanese = LyricsRequest(
            title = "Lemon",
            artist = "Kenshi Yonezu",
            album = "Lemon",
            durationMs = 255_000,
        )
        val score = Matching.score(japanese, "Lemon", "米津玄師", 255_000)
        assertTrue("got $score", score >= Matching.MATCH_THRESHOLD)
    }

    @Test
    fun `an unrelated song is still rejected across scripts`() {
        // The allowance above must not become a way in for the wrong track: with the
        // artist unknown, the title and duration have to carry the match on their own.
        val japanese = LyricsRequest(
            title = "Lemon",
            artist = "Kenshi Yonezu",
            album = "Lemon",
            durationMs = 255_000,
        )
        val score = Matching.score(japanese, "残酷な天使のように", "高橋洋子", 88_000)
        assertTrue("got $score", score < Matching.MATCH_THRESHOLD)
    }

    @Test
    fun `a wildly different duration drags the score down`() {
        val close = Matching.score(request, "Song Title", "The Artist", 210_000)
        val far = Matching.score(request, "Song Title", "The Artist", 400_000)
        assertTrue(far < close)
    }
}

class ScriptDetectionTest {

    @Test
    fun `kana anywhere means the kanji are Japanese`() {
        // The important case: 君の名は contains Han characters, but the kana prove it is
        // Japanese, so it must never be read as Mandarin.
        assertEquals(Script.JAPANESE, detectScript("君の名は"))
    }

    @Test
    fun `han without kana is Chinese`() {
        assertEquals(Script.CHINESE, detectScript("我的心里只有你"))
    }

    @Test
    fun `hangul is Korean`() {
        assertEquals(Script.KOREAN, detectScript("사랑해요"))
    }

    @Test
    fun `cyrillic and greek need more than one letter to count`() {
        assertEquals(Script.CYRILLIC, detectScript("Привет"))
        assertEquals(Script.GREEK, detectScript("Καλημέρα"))
    }

    @Test
    fun `latin needs no romanization`() {
        assertEquals(Script.LATIN, detectScript("Hello there"))
    }

    @Test
    fun `right-to-left text is detected`() {
        assertTrue("مرحبا بالعالم".isRtlText())
        assertTrue("שלום עולם".isRtlText())
        assertFalse("Hello".isRtlText())
    }
}
