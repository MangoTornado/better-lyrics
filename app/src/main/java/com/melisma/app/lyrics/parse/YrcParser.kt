package com.melisma.app.lyrics.parse

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.util.isRtlText

/**
 * Parser for NetEase Cloud Music's word-timed lyric format (`yrc`, and the older
 * `klyric` which shares its shape):
 *
 * ```
 * [1160,2570](1160,470,0)言(1630,300,0)う(1930,290,0)な
 * ```
 *
 * `[lineStart,lineDuration]` then one `(wordStart,wordDuration,0)` per word. This is
 * the only free source of per-syllable timings for a large Japanese, Korean and
 * Chinese catalogue, which is exactly where karaoke matters most.
 */
object YrcParser {

    private val LINE_HEADER = Regex("""^\[(\d+),(\d+)]""")
    private val WORD = Regex("""\((\d+),(\d+),(-?\d+)\)([^(]*)""")

    fun parse(raw: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // NetEase prefixes yrc with JSON credit blocks; they are not lyrics.
            if (line.startsWith("{")) continue

            val header = LINE_HEADER.find(line) ?: continue
            val lineStart = header.groupValues[1].toIntOrNull() ?: continue
            val lineDuration = header.groupValues[2].toIntOrNull() ?: 0

            val body = line.substring(header.range.last + 1)
            val matches = WORD.findAll(body).toList()
            if (matches.isEmpty()) continue

            val syllables = ArrayList<Syllable>(matches.size)
            val text = StringBuilder()
            var previousEndedWithSpace = true

            for (match in matches) {
                val start = match.groupValues[1].toIntOrNull() ?: continue
                val duration = match.groupValues[2].toIntOrNull() ?: 0
                val rawText = match.groupValues[4]
                val trimmed = rawText.trim()
                if (trimmed.isEmpty()) {
                    previousEndedWithSpace = true
                    continue
                }
                val partOfWord = !previousEndedWithSpace && !rawText.startsWith(" ")
                syllables += Syllable(
                    text = trimmed,
                    startMs = start,
                    endMs = start + duration,
                    partOfWord = partOfWord,
                )
                if (!partOfWord && text.isNotEmpty()) text.append(' ')
                text.append(trimmed)
                previousEndedWithSpace = rawText.endsWith(" ")
            }

            if (syllables.isEmpty()) continue
            val plain = text.toString()
            out += LyricLine(
                role = LineRole.LEAD,
                startMs = minOf(lineStart, syllables.first().startMs),
                endMs = maxOf(lineStart + lineDuration, syllables.last().endMs),
                text = plain,
                syllables = syllables,
                rtl = plain.isRtlText(),
            )
        }

        return out.sortedBy { it.startMs }
    }

    /** True when [raw] looks like yrc rather than plain LRC. */
    fun looksLikeYrc(raw: String): Boolean =
        raw.lineSequence().take(40).any { LINE_HEADER.containsMatchIn(it.trim()) }
}
