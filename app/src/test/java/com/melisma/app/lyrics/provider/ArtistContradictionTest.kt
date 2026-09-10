package com.melisma.app.lyrics.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A candidate by a different artist is not a match, however well the title agrees.
 *
 * Reported as the wrong lyrics for `Mirror` by Ado. The AMLL database holds nothing under that
 * artist, so the title-and-artist search comes back empty and the fallback asks by title alone —
 * which returns Porter Robinson's `Mirror`. The arithmetic then let it through: an exact title is
 * 0.5 of the score, an unknown duration abstains for another 0.1, and any scrap of artist similarity
 * carries it past the 0.62 threshold. 0.63, and the wrong words look exactly like the right ones.
 *
 * The server already vetoed this; the app scored the same situation and accepted it. These tests
 * exist because the veto is absolute — the risk of getting it wrong is rejecting *correct* matches,
 * so the cases that must still pass matter more here than the one that must not.
 */
class ArtistContradictionTest {

    private fun request(title: String, artist: String, durationMs: Long = 0L) =
        LyricsRequest(title = title, artist = artist, album = "", durationMs = durationMs)

    // ---- the case that was wrong -------------------------------------------

    @Test
    fun `a different artist with the same title is rejected outright`() {
        val score = Matching.score(
            request("Mirror", "Ado"),
            candidateTitle = "Mirror",
            candidateArtist = "Porter Robinson",
            candidateDurationMs = 0L,
        )
        assertEquals("a different artist is not a weak match, it is no match", 0f, score, 0.0001f)
    }

    @Test
    fun `the near miss it used to be is documented, not merely fixed`() {
        // Without the veto this scored just over the threshold. Worth pinning the shape of the
        // arithmetic: an exact title plus an abstaining duration is 0.60 on its own, so anything
        // at all from the artist term used to be enough.
        val titleAndDurationAlone = 1f * 0.5f + 0.5f * 0.2f
        assertTrue(
            "an exact title and an unknown duration alone reach $titleAndDurationAlone",
            titleAndDurationAlone > Matching.MATCH_THRESHOLD - 0.03f,
        )
    }

    // ---- and the cases that must still match -------------------------------

    @Test
    fun `the same artist still matches`() {
        val score = Matching.score(
            request("Mirror", "Ado"),
            candidateTitle = "Mirror",
            candidateArtist = "Ado",
            candidateDurationMs = 0L,
        )
        assertTrue("$score", score >= Matching.MATCH_THRESHOLD)
    }

    @Test
    fun `another script is an absence of evidence, not evidence against`() {
        // The reason the veto is scoped to comparable scripts: these two names for one person share
        // no characters at all, and rejecting them would break every catalogue that indexes in the
        // original script.
        val score = Matching.score(
            request("Lemon", "Kenshi Yonezu"),
            candidateTitle = "Lemon",
            candidateArtist = "米津玄師",
            candidateDurationMs = 0L,
        )
        assertTrue("$score", score >= Matching.MATCH_THRESHOLD)
    }

    @Test
    fun `a missing artist on either side does not veto`() {
        assertTrue(
            Matching.score(request("Mirror", ""), "Mirror", "Porter Robinson", 0L) >=
                Matching.MATCH_THRESHOLD,
        )
        assertTrue(
            Matching.score(request("Mirror", "Ado"), "Mirror", "", 0L) >= Matching.MATCH_THRESHOLD,
        )
    }

    @Test
    fun `a collaboration credited to the other name still matches`() {
        // The case the per-credited-artist maximum exists for. A veto without it would reject this,
        // which is worse than the bug it fixes: catalogues disagree about who "the" artist is.
        val score = Matching.score(
            request("Renai Circulation", "Kana Hanazawa, ClariS"),
            candidateTitle = "Renai Circulation",
            candidateArtist = "ClariS",
            candidateDurationMs = 0L,
        )
        assertTrue("$score", score >= Matching.MATCH_THRESHOLD)
    }

    @Test
    fun `a featured artist written differently still matches`() {
        val score = Matching.score(
            request("Stay", "The Kid LAROI & Justin Bieber"),
            candidateTitle = "Stay",
            candidateArtist = "The Kid LAROI",
            candidateDurationMs = 0L,
        )
        assertTrue("$score", score >= Matching.MATCH_THRESHOLD)
    }

    @Test
    fun `a matching duration does not rescue a different artist`() {
        // Two unrelated songs sharing a common title and a runtime is not rare — and before this,
        // that combination scored 0.7.
        val score = Matching.score(
            request("Alone", "Alan Walker", durationMs = 161_000),
            candidateTitle = "Alone",
            candidateArtist = "Heart",
            candidateDurationMs = 161_000,
        )
        assertEquals(0f, score, 0.0001f)
    }
}
