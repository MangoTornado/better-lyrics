package com.melisma.app.ui.lyrics

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A romanized line has to stay on the screen.
 *
 * Reported for Chinese: switching romanization on ran the line off the side. `partOfWord` is derived
 * from the absence of a space and Chinese is written without spaces, so a whole Chinese line arrives
 * as one word — and the row loop placed a word too wide for a row rather than breaking it, because a
 * word was assumed to be something that could fit. In the original characters it usually does. The
 * pinyin for the same line is around three times wider.
 *
 * ### About the sizes
 *
 * Robolectric's `Paint.measureText` returns **one unit per character** and ignores the text size. So
 * the numbers here are character counts, not pixels, and they are chosen against that: 40 Chinese
 * characters measure 40, their pinyin measures about 127, and the column is ~100 wide. A real device
 * would put all three an order of magnitude higher in the same proportion. Getting this wrong is how
 * the first version of this test passed with the fix deliberately disabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RomanizedWrapTest {

    /** Wide enough that the pinyin needs two rows, narrow enough that the characters need one. */
    private val widthPx = 105f

    /** What the layout will actually use: the width less the 5 % inset it always reserves. */
    private val column = widthPx - widthPx * 0.05f

    private val syllableCount = 40

    /**
     * A Chinese line as a source hands it over: no spaces anywhere, so every syllable after the
     * first continues the one before, and the whole line is a single word.
     */
    private fun chineseLine(): LyricLine {
        // Three-letter pinyin throughout, so the romanized line is three times the width of the
        // characters — which is the ratio that causes the overflow in practice.
        val characters = List(syllableCount) { index -> "字" to "z${'a' + (index % 5)}i" }
        return LyricLine(
            role = LineRole.LEAD,
            startMs = 0,
            endMs = 4_000,
            text = characters.joinToString("") { it.first },
            syllables = characters.mapIndexed { index, (character, pinyin) ->
                Syllable(
                    text = character,
                    startMs = index * 100,
                    endMs = (index + 1) * 100,
                    // No space before it, which is what makes the whole line one word.
                    partOfWord = index > 0,
                    romanized = pinyin,
                )
            },
        )
    }

    private fun layout(useRomanization: Boolean, width: Float = widthPx): LyricsLayout =
        LyricsLayoutBuilder.build(
            document = LyricsDocument(
                kind = LyricsKind.SYLLABLE,
                lines = listOf(chineseLine()),
                providerName = "test",
                providerId = "test",
            ),
            metrics = LyricsMetrics(fontSizePx = 10f, simpleMode = false, showSecondaryLine = false),
            widthPx = width,
            useRomanization = useRomanization,
            showTranslation = false,
            showCredits = false,
        )

    private fun LyricsLayout.rightEdge(): Float = lines[0].units.maxOf { it.x + it.width }

    @Test
    fun `the unromanized line is the case that always fitted`() {
        // Establishes that the fixture is calibrated: the characters fit, so any overflow below is
        // caused by romanizing rather than by a line that was too long to begin with.
        val plain = layout(useRomanization = false)
        assertTrue("expected the characters to fit in $column", plain.rightEdge() <= column)
        assertEquals(1, plain.lines[0].units.map { it.baseline }.distinct().size)
    }

    @Test
    fun `a romanized line stays inside the width it was given`() {
        val romanized = layout(useRomanization = true)

        // The fixture is only meaningful if the romanization really is too wide for one row.
        val oneRow = romanized.lines[0].units.sumOf { it.width.toDouble() }
        assertTrue("the pinyin ($oneRow) should not fit in $column", oneRow > column)

        assertTrue(
            "the line runs to ${romanized.rightEdge()} in a $column column",
            romanized.rightEdge() <= column + 0.5f,
        )
        // Which it can only manage by using more than one row.
        assertTrue(romanized.lines[0].units.map { it.baseline }.distinct().size > 1)
    }

    @Test
    fun `breaking the line keeps every syllable and its timing`() {
        val units = layout(useRomanization = true).lines[0].units

        // Nothing dropped by the split, and the windows still run forward — the break must not cost
        // the karaoke fill, which is why the split falls between syllables rather than between
        // characters.
        assertEquals(syllableCount, units.size)
        for ((earlier, later) in units.zipWithNext()) {
            assertTrue(
                "${earlier.text}@${earlier.startMs} then ${later.text}@${later.startMs}",
                later.startMs >= earlier.startMs,
            )
        }
    }

    /**
     * A whole line as one syllable, which is what TTML from the cache server produces for Chinese:
     * there are no spaces to split spans on, so the span is the line.
     */
    private fun oneSyllableLine(): LyricsDocument {
        val characters = "字".repeat(syllableCount)
        val pinyin = List(syllableCount) { "zai" }.joinToString("")
        return LyricsDocument(
            kind = LyricsKind.SYLLABLE,
            lines = listOf(
                LyricLine(
                    role = LineRole.LEAD,
                    startMs = 0,
                    endMs = 4_000,
                    text = characters,
                    syllables = listOf(
                        Syllable(text = characters, startMs = 0, endMs = 4_000, romanized = pinyin),
                    ),
                ),
            ),
            providerName = "test",
            providerId = "test",
        )
    }

    @Test
    fun `a line that is one long syllable is broken rather than left to overflow`() {
        val layout = LyricsLayoutBuilder.build(
            document = oneSyllableLine(),
            metrics = LyricsMetrics(fontSizePx = 10f, simpleMode = false, showSecondaryLine = false),
            widthPx = widthPx,
            useRomanization = true,
            showTranslation = false,
            showCredits = false,
        )

        val units = layout.lines[0].units
        assertTrue("should have been divided", units.size > 1)
        assertTrue(
            "runs to ${units.maxOf { it.x + it.width }} in a $column column",
            units.maxOf { it.x + it.width } <= column + 0.5f,
        )

        // The syllable's window is shared out in order, so the fill still tracks the singing across
        // the break rather than restarting or jumping.
        assertEquals(0, units.first().startMs)
        assertEquals(4_000, units.last().endMs)
        for ((earlier, later) in units.zipWithNext()) {
            assertTrue("${earlier.endMs} then ${later.startMs}", later.startMs >= earlier.startMs)
        }
        // And nothing of the text was lost.
        assertEquals(
            syllableCount * 3,
            units.sumOf { it.text.length },
        )
    }

    @Test
    fun `a line that already fits is left on one row`() {
        val romanized = layout(useRomanization = true, width = 4_000f)
        assertEquals(
            "should not have wrapped",
            1,
            romanized.lines[0].units.map { it.baseline }.distinct().size,
        )
    }
}
