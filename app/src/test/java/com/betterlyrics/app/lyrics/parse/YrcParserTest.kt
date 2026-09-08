package com.betterlyrics.app.lyrics.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YrcParserTest {

    @Test
    fun `parses NetEase word timings`() {
        val lines = YrcParser.parse(
            "[1160,2570](1160,470,0)言(1630,300,0)う(1930,290,0)な",
        )

        assertEquals(1, lines.size)
        val line = lines.first()
        assertEquals(1_160, line.startMs)
        assertEquals(3_730, line.endMs)
        assertEquals(listOf("言", "う", "な"), line.syllables.map { it.text })
        assertEquals(1_630, line.syllables[0].endMs)
        // No spaces in the source, so every syllable continues the same word.
        assertTrue(line.syllables.drop(1).all { it.partOfWord })
    }

    @Test
    fun `spaces separate words`() {
        val line = YrcParser.parse("[0,1000](0,300,0)hello (300,300,0)there").first()
        assertEquals(listOf("hello", "there"), line.syllables.map { it.text })
        assertFalse(line.syllables[1].partOfWord)
        assertEquals("hello there", line.text)
    }

    @Test
    fun `credit blocks are skipped`() {
        val lines = YrcParser.parse(
            """
            {"t":0,"c":[{"tx":"作词: Someone"}]}
            [1000,500](1000,500,0)word
            """.trimIndent(),
        )
        assertEquals(1, lines.size)
        assertEquals("word", lines.first().text)
    }

    @Test
    fun `plain lrc is not mistaken for yrc`() {
        assertFalse(YrcParser.looksLikeYrc("[00:12.34]a line"))
        assertTrue(YrcParser.looksLikeYrc("[1160,2570](1160,470,0)x"))
    }
}
