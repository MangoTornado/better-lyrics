package com.melisma.app.lyrics

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of several answers to show.
 *
 * The rule used to be the document's `kind` and nothing else, and `kind` is set from *any* word timing
 * at all — so a source with word timings on three lines out of forty was scored as the best thing
 * available and beat a complete line-synced transcription outright. Same for a source that matched the
 * wrong recording and returned a tenth of the words with perfect timings.
 *
 * The property to keep while fixing that: a genuine word-timed answer still beats every line-timed
 * one. Only an answer that is not word-timed *for this song* — barely any lines timed, or barely any
 * of the song present — drops to where completeness decides.
 */
class QualityScoreTest {

    private fun line(text: String, timed: Boolean) = LyricLine(
        role = LineRole.LEAD,
        startMs = 0,
        endMs = 1_000,
        text = text,
        syllables = if (timed) {
            listOf(Syllable(text = text, startMs = 0, endMs = 1_000))
        } else {
            emptyList()
        },
    )

    /** [timed] of [lines] lines carry syllables. */
    private fun document(lines: Int, timed: Int, kind: LyricsKind) = LyricsDocument(
        kind = kind,
        lines = (0 until lines).map { index -> line("line $index", timed = index < timed) },
        providerName = "test",
        providerId = "test",
    )

    private fun syllable(lines: Int, timed: Int = lines) =
        document(lines, timed, LyricsKind.SYLLABLE)

    private fun lineTimed(lines: Int) = document(lines, 0, LyricsKind.LINE)

    @Test
    fun `real word timings still beat line timings`() {
        // The property worth protecting: none of the coverage rules may invert this.
        val words = syllable(lines = 40)
        val lines = lineTimed(40)
        assertTrue(qualityScore(words, bestLines = 40) > qualityScore(lines, bestLines = 40))
    }

    @Test
    fun `a document with only a few timed lines is not treated as word-timed`() {
        // Three of forty. `kind` says syllable because *something* was timed; it is a line-timed
        // document with a handful of timed lines in it.
        val barelyTimed = syllable(lines = 40, timed = 3)
        assertEquals(LyricsKind.LINE, effectiveKind(barelyTimed, bestLines = 40))

        // And a complete line-timed answer is at least as good, rather than losing outright.
        val complete = lineTimed(40)
        assertTrue(qualityScore(complete, bestLines = 40) >= qualityScore(barelyTimed, bestLines = 40))
    }

    @Test
    fun `a fragment does not beat a complete answer just for being word-timed`() {
        // The reported shape: perfect timings for a tenth of the song. A truncated transcription or
        // the wrong recording — not a better answer.
        val fragment = syllable(lines = 4)
        val complete = lineTimed(40)
        assertTrue(
            "a 4-line fragment should not beat a 40-line transcription",
            qualityScore(complete, bestLines = 40) > qualityScore(fragment, bestLines = 40),
        )
    }

    @Test
    fun `a word-timed answer that is merely shorter still wins`() {
        // 28 of 40 lines: plausibly the same song with the interludes counted differently, or a
        // repeated chorus written once. Above the threshold, so its timings are trusted.
        val wordTimed = syllable(lines = 28)
        val complete = lineTimed(40)
        assertTrue(qualityScore(wordTimed, bestLines = 40) > qualityScore(complete, bestLines = 40))
    }

    @Test
    fun `completeness orders answers of the same kind`() {
        assertTrue(
            qualityScore(lineTimed(40), bestLines = 40) > qualityScore(lineTimed(20), bestLines = 40),
        )
        assertTrue(
            qualityScore(syllable(40), bestLines = 40) > qualityScore(syllable(30), bestLines = 40),
        )
    }

    @Test
    fun `with nothing to compare against a document is taken at face value`() {
        // One source answered, so there is no yardstick. Demoting it would mean showing nothing.
        val only = syllable(lines = 4)
        assertEquals(LyricsKind.SYLLABLE, effectiveKind(only, bestLines = 0))
        assertTrue(qualityScore(only, bestLines = 0) > qualityScore(lineTimed(4), bestLines = 0))
    }

    @Test
    fun `shipped romanization and translation are a nudge, not a tier`() {
        val plain = lineTimed(40)
        val withExtras = plain.copy(hasRomanization = true, hasTranslation = true)

        assertTrue(qualityScore(withExtras, 40) > qualityScore(plain, 40))
        // The app generates both itself, so they must never outweigh real timings.
        assertTrue(qualityScore(syllable(40), 40) > qualityScore(withExtras, 40))
    }

    @Test
    fun `interludes do not count as words`() {
        // We generate these from the gaps, so counting them would make a document look fuller than
        // it is — and would let a source win by inventing pauses.
        val withInterludes = LyricsDocument(
            kind = LyricsKind.LINE,
            lines = listOf(
                line("a real line", timed = false),
                LyricLine(role = LineRole.INTERLUDE, startMs = 1_000, endMs = 2_000, text = ""),
                LyricLine(role = LineRole.INTERLUDE, startMs = 2_000, endMs = 3_000, text = ""),
            ),
            providerName = "test",
            providerId = "test",
        )
        assertEquals(1, lineCount(withInterludes))
    }
}
