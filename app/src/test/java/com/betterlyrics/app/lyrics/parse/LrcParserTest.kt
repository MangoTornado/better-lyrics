package com.betterlyrics.app.lyrics.parse

import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricsKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses plain line timestamps`() {
        val parsed = LrcParser.parse(
            """
            [ar:Someone]
            [ti:A Song]
            [00:12.34]First line
            [00:15.00]Second line
            """.trimIndent(),
            trackDurationMs = 30_000,
        )

        assertEquals(LyricsKind.LINE, parsed.kind)
        assertEquals("Someone", parsed.metadata.artist)
        assertEquals("A Song", parsed.metadata.title)

        val vocals = parsed.lines.filter { it.role == LineRole.LEAD }
        assertEquals(2, vocals.size)
        assertEquals(12_340, vocals[0].startMs)
        assertEquals("First line", vocals[0].text)
        // A line runs until the next one begins.
        assertEquals(15_000, vocals[0].endMs)
    }

    @Test
    fun `two-digit fractions are hundredths, three are milliseconds`() {
        val hundredths = LrcParser.parse("[00:01.50]x").lines.first { it.text == "x" }
        assertEquals(1_500, hundredths.startMs)

        val millis = LrcParser.parse("[00:01.050]x").lines.first { it.text == "x" }
        assertEquals(1_050, millis.startMs)
    }

    @Test
    fun `repeated timestamps duplicate the line`() {
        val parsed = LrcParser.parse("[00:10.00][01:10.00]chorus")
        val vocals = parsed.lines.filter { it.role == LineRole.LEAD }
        assertEquals(2, vocals.size)
        assertEquals(10_000, vocals[0].startMs)
        assertEquals(70_000, vocals[1].startMs)
        assertTrue(vocals.all { it.text == "chorus" })
    }

    @Test
    fun `enhanced lrc yields per-word timings`() {
        val parsed = LrcParser.parse(
            "[00:10.00]<00:10.00>Hel<00:10.30>lo <00:10.60>world",
            trackDurationMs = 30_000,
        )

        assertEquals(LyricsKind.SYLLABLE, parsed.kind)
        val line = parsed.lines.first { it.role == LineRole.LEAD }
        assertEquals(3, line.syllables.size)

        assertEquals("Hel", line.syllables[0].text)
        assertFalse(line.syllables[0].partOfWord)
        // "lo" continues "Hel" with no space, so it must not be split across a wrap.
        assertEquals("lo", line.syllables[1].text)
        assertTrue(line.syllables[1].partOfWord)
        // "world" follows a space, so it starts a new word.
        assertEquals("world", line.syllables[2].text)
        assertFalse(line.syllables[2].partOfWord)

        // Each syllable closes where the next one opens.
        assertEquals(10_300, line.syllables[0].endMs)
        assertEquals(10_600, line.syllables[1].endMs)
    }

    @Test
    fun `offset tag shifts every timestamp earlier`() {
        val parsed = LrcParser.parse(
            """
            [offset:+500]
            [00:10.00]line
            """.trimIndent(),
        )
        assertEquals(9_500, parsed.lines.first { it.text == "line" }.startMs)
    }

    @Test
    fun `a long silent opening becomes an interlude`() {
        val parsed = LrcParser.parse("[00:20.00]late start", trackDurationMs = 60_000)
        val first = parsed.lines.first()
        assertEquals(LineRole.INTERLUDE, first.role)
        assertEquals(0, first.startMs)
        assertEquals(20_000, first.endMs)
    }

    @Test
    fun `short gaps do not become interludes`() {
        val parsed = LrcParser.parse(
            """
            [00:01.00]one
            [00:02.00]two
            """.trimIndent(),
            trackDurationMs = 60_000,
        )
        assertTrue(parsed.lines.none { it.role == LineRole.INTERLUDE })
    }

    @Test
    fun `lines without timestamps are ignored, not treated as lyrics`() {
        val parsed = LrcParser.parse(
            """
            some header junk
            [00:05.00]real line
            """.trimIndent(),
        )
        assertEquals(1, parsed.lines.count { it.role == LineRole.LEAD })
    }

    @Test
    fun `plain lyrics become an unsynced document`() {
        val document = LrcParser.plainToDocument("one\ntwo\n\n", "Test", "test")
        assertNotNull(document)
        assertEquals(LyricsKind.STATIC, document!!.kind)
        assertEquals(listOf("one", "two"), document.lines.map { it.text })
    }

    @Test
    fun `an alternate track merges onto matching timestamps`() {
        val base = LrcParser.parse("[00:10.00]original", trackDurationMs = 30_000).lines
        val merged = LrcParser.mergeAlternateTrack(
            base = base,
            alternateLrc = "[00:10.10]translated",
        ) { line, text -> line.copy(translated = text) }

        val line = merged.first { it.role == LineRole.LEAD }
        assertEquals("translated", line.translated)
    }

    @Test
    fun `an alternate track drifting past the tolerance is left alone`() {
        val base = LrcParser.parse("[00:10.00]original", trackDurationMs = 30_000).lines
        val merged = LrcParser.mergeAlternateTrack(
            base = base,
            alternateLrc = "[00:19.00]unrelated",
        ) { line, text -> line.copy(translated = text) }

        assertEquals(null, merged.first { it.role == LineRole.LEAD }.translated)
    }
}
