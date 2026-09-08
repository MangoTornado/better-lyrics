package com.betterlyrics.app.lyrics.parse

import com.betterlyrics.app.lyrics.model.LyricsKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TTML is the only format that carries syllable timings, readings and translations at
 * once, and the community database writes all three into the same `<p>`. The reading and
 * the translation are untimed siblings of the sung syllables, which makes them easy to
 * mistake for lyrics — a mistake that shows up as romaji spliced into the song and every
 * affected line starting at 0:00.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtmlParserTest {

    /** One real line from the community database, cut down but structurally untouched. */
    private val communityTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
            itunes:timing="Word" xml:lang="ja">
          <head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head>
          <body>
            <div>
              <p begin="00:06.617" end="00:08.449" ttm:agent="v1" itunes:key="L1"
                 ><span begin="00:06.617" end="00:06.740">不</span
                 ><span begin="00:06.740" end="00:07.104">完全</span
                 ><span begin="00:07.104" end="00:07.719">な僕</span
                 ><span begin="00:07.719" end="00:08.048">を</span
                 ><span ttm:role="x-translation" xml:lang="zh-CN">这些音色</span
                 ><span ttm:role="x-roman">fu ka n ze n na bo ku wo</span></p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    /** The sung lines, without the interlude the 6.6s intro earns. */
    private fun vocals() =
        TtmlParser.parse(communityTtml, "Community", "amll")!!.lines.filterNot { it.isInterlude }

    @Test
    fun `the reading and the translation do not become syllables`() {
        val lines = vocals()
        assertEquals(1, lines.size)
        val line = lines.single()

        assertEquals(4, line.syllables.size)
        assertEquals("不完全な僕を", line.syllables.joinToString("") { it.text })
        // The line stops when the singing does, not when the trailing spans appear.
        assertEquals(6_617, line.startMs)
        assertEquals(8_449, line.endMs)
        // Nothing untimed slipped in at zero.
        assertTrue(line.syllables.all { it.startMs > 0 })
    }

    @Test
    fun `the reading and the translation are read as the line's own`() {
        val line = vocals().single()
        assertEquals("fu ka n ze n na bo ku wo", line.romanized)
        assertEquals("这些音色", line.translated)
    }

    @Test
    fun `a document with those spans advertises both`() {
        val document = TtmlParser.parse(communityTtml, "Community", "amll")!!
        assertEquals(LyricsKind.SYLLABLE, document.kind)
        assertTrue(document.hasRomanization)
        assertTrue(document.hasTranslation)
        assertEquals("Community", document.providerName)
    }

    @Test
    fun `a line-level reading is not pushed down onto the syllables`() {
        // Readings are written mora by mora — nine here against four sung syllables — so
        // there is no mapping to be had, and pretending otherwise would desynchronise the
        // karaoke. Each syllable must stay unromanized and let the layout show the reading
        // on its own row.
        val line = vocals().single()
        assertTrue(line.syllables.all { it.romanized == null })
    }

    @Test
    fun `a real file from the community database parses`() {
        // src/test/resources/amll-sample.ttml is the head and first four lines of an
        // actual response, unedited — a hand-written sample can only test the dialect I
        // already believe in. Note the timestamps: bare seconds, not mm:ss.
        val xml = javaClass.classLoader!!
            .getResourceAsStream("amll-sample.ttml")!!
            .reader()
            .readText()

        val document = TtmlParser.parse(xml, "AMLL TTML DB", "amll")!!
        assertEquals(LyricsKind.SYLLABLE, document.kind)
        assertTrue(document.hasRomanization)
        assertTrue(document.hasTranslation)

        val lines = document.lines.filterNot { it.isInterlude }
        assertEquals(4, lines.size)
        assertEquals("夢ならば", lines[0].text)
        assertEquals(1_372, lines[0].startMs)
        assertEquals(2_705, lines[0].endMs)
        assertEquals("yu me na ra ba", lines[0].romanized)
        assertEquals("如果只是一场梦", lines[0].translated)
        // Four sung syllables, and not one of them is the reading or the translation.
        assertEquals(4, lines[0].syllables.size)
        assertTrue(lines.all { it.syllables.isNotEmpty() && it.startMs > 0 })
    }

    @Test
    fun `background vocals still read as background`() {
        val ttml = """
            <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word">
              <body><div>
                <p begin="00:01.000" end="00:03.000" itunes:key="L1"
                   ><span begin="00:01.000" end="00:02.000">lead</span
                   ><span ttm:role="x-bg"
                     ><span begin="00:02.000" end="00:03.000">ooh</span></span></p>
              </div></body>
            </tt>
        """.trimIndent()
        val lines = TtmlParser.parse(ttml, "Test", "test")!!.lines
        assertEquals(2, lines.size)
        assertEquals("lead", lines[0].text)
        assertEquals("ooh", lines[1].text)
        assertNull(lines[0].romanized)
    }
}
