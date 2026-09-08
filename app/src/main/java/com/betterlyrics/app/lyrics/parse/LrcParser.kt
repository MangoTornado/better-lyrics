package com.betterlyrics.app.lyrics.parse

import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricLine
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.lyrics.model.Syllable
import com.betterlyrics.app.lyrics.model.inferEndTimes
import com.betterlyrics.app.lyrics.model.withInterludes
import com.betterlyrics.app.util.isRtlText

/**
 * LRC and Enhanced-LRC (A2) parser.
 *
 * Handles the three shapes that actually turn up in the wild:
 *   `[01:23.45]a line`                      → line-synced
 *   `[00:10.00][01:10.00]a repeated chorus`  → one text, several timestamps
 *   `[00:10.00]<00:10.00>Hel<00:10.30>lo`   → word-synced (A2)
 */
object LrcParser {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_TAG = Regex("""^\[(ti|ar|al|by|au|offset|length|re|ve|tool):(.*)]$""", RegexOption.IGNORE_CASE)

    data class Metadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val author: String? = null,
        val offsetMs: Int = 0,
    )

    data class ParsedLrc(
        val lines: List<LyricLine>,
        val kind: LyricsKind,
        val metadata: Metadata,
    )

    fun parse(raw: String, trackDurationMs: Long = 0L): ParsedLrc {
        var metadata = Metadata()
        // Several timestamps can point at the same text, so collect flat then sort.
        val collected = ArrayList<LyricLine>()
        var sawWordTimings = false

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val meta = META_TAG.matchEntire(line)
            if (meta != null) {
                val value = meta.groupValues[2].trim()
                metadata = when (meta.groupValues[1].lowercase()) {
                    "ti" -> metadata.copy(title = value)
                    "ar" -> metadata.copy(artist = value)
                    "al" -> metadata.copy(album = value)
                    "by", "au" -> metadata.copy(author = value)
                    "offset" -> metadata.copy(
                        offsetMs = value.removePrefix("+").toIntOrNull() ?: 0,
                    )
                    else -> metadata
                }
                continue
            }

            val stamps = TIME_TAG.findAll(line).toList()
            if (stamps.isEmpty()) continue

            // Timestamps are only a prefix; the first non-timestamp character starts
            // the body. Anything after that is content even if it looks like a tag.
            var bodyStart = 0
            for (stamp in stamps) {
                if (stamp.range.first != bodyStart) break
                bodyStart = stamp.range.last + 1
            }
            val body = line.substring(bodyStart)
            val starts = stamps.takeWhile { it.range.last < bodyStart }.map { it.toMillis() }
            if (starts.isEmpty()) continue

            val syllables = parseWordTimings(body)
            if (syllables.isNotEmpty()) sawWordTimings = true

            val plainText = if (syllables.isNotEmpty()) {
                syllables.joinToString("") { s -> (if (s.partOfWord) "" else " ") + s.text }.trim()
            } else {
                stripTags(body).trim()
            }
            if (plainText.isEmpty() && syllables.isEmpty()) continue

            for (start in starts) {
                val shifted = if (syllables.isEmpty()) emptyList() else {
                    // A2 word times are absolute; a repeated chorus shifts them all.
                    val delta = start - (syllables.first().startMs)
                    syllables.map { it.copy(startMs = it.startMs + delta, endMs = it.endMs + delta) }
                }
                collected += LyricLine(
                    role = LineRole.LEAD,
                    startMs = start,
                    endMs = shifted.lastOrNull()?.endMs ?: 0,
                    text = plainText,
                    syllables = shifted,
                    rtl = plainText.isRtlText(),
                )
            }
        }

        if (collected.isEmpty()) {
            return ParsedLrc(emptyList(), LyricsKind.STATIC, metadata)
        }

        val sorted = collected.sortedBy { it.startMs }
        val offset = metadata.offsetMs
        val offsetApplied = if (offset == 0) sorted else sorted.map { line ->
            // The LRC `offset` tag is "shift playback by N ms", i.e. subtract from the
            // timestamps to move the words earlier.
            line.copy(
                startMs = line.startMs - offset,
                endMs = line.endMs - offset,
                syllables = line.syllables.map {
                    it.copy(startMs = it.startMs - offset, endMs = it.endMs - offset)
                },
            )
        }

        val withEnds = offsetApplied.inferEndTimes(trackDurationMs)
        val closed = if (sawWordTimings) closeSyllableGaps(withEnds) else withEnds

        return ParsedLrc(
            lines = closed.withInterludes(),
            kind = if (sawWordTimings) LyricsKind.SYLLABLE else LyricsKind.LINE,
            metadata = metadata,
        )
    }

    fun toDocument(
        raw: String,
        providerName: String,
        providerId: String,
        trackDurationMs: Long = 0L,
    ): LyricsDocument? {
        val parsed = parse(raw, trackDurationMs)
        if (parsed.lines.isEmpty()) return null
        return LyricsDocument(
            kind = parsed.kind,
            lines = parsed.lines,
            providerName = providerName,
            providerId = providerId,
            songWriters = listOfNotNull(parsed.metadata.author),
        )
    }

    /** Plain, untimed lyrics — used when a provider only has an unsynced body. */
    fun plainToDocument(raw: String, providerName: String, providerId: String): LyricsDocument? {
        val lines = raw.lineSequence().map { it.trim() }.toList().dropLastWhile { it.isEmpty() }
        if (lines.none { it.isNotEmpty() }) return null
        return LyricsDocument(
            kind = LyricsKind.STATIC,
            lines = lines.map {
                LyricLine(
                    role = LineRole.LEAD,
                    startMs = 0,
                    endMs = 0,
                    text = it,
                    rtl = it.isRtlText(),
                )
            },
            providerName = providerName,
            providerId = providerId,
        )
    }

    /**
     * Split an A2 body such as `<00:10.00>Hel<00:10.30>lo <00:10.60>world` into
     * syllables. Returns empty when the body carries no `<…>` tags.
     */
    private fun parseWordTimings(body: String): List<Syllable> {
        val tags = WORD_TAG.findAll(body).toList()
        if (tags.isEmpty()) return emptyList()

        val out = ArrayList<Syllable>(tags.size)
        var previousEndedWithSpace = true

        for ((index, tag) in tags.withIndex()) {
            val textStart = tag.range.last + 1
            val textEnd = tags.getOrNull(index + 1)?.range?.first ?: body.length
            if (textStart > textEnd) continue
            val rawText = body.substring(textStart, textEnd)
            val text = rawText.trim()
            val start = tag.toMillis()

            if (text.isEmpty()) {
                // A bare tag with no text is an end marker: it closes the previous
                // syllable rather than adding one.
                if (out.isNotEmpty()) {
                    out[out.lastIndex] = out.last().copy(endMs = start)
                }
                previousEndedWithSpace = true
                continue
            }

            val partOfWord = !previousEndedWithSpace && !rawText.startsWith(" ") &&
                !rawText.startsWith("\t")
            out += Syllable(text = text, startMs = start, endMs = start, partOfWord = partOfWord)
            previousEndedWithSpace = rawText.endsWith(" ") || rawText.endsWith("\t")
        }
        return out
    }

    /**
     * A2 only gives start times. Close each syllable at the next one's start, and the
     * last one at the line's end (already filled in by `inferEndTimes`).
     */
    private fun closeSyllableGaps(lines: List<LyricLine>): List<LyricLine> = lines.map { line ->
        if (line.syllables.isEmpty()) return@map line
        val closed = line.syllables.mapIndexed { index, syllable ->
            val next = line.syllables.getOrNull(index + 1)
            val end = when {
                syllable.endMs > syllable.startMs -> syllable.endMs
                next != null -> next.startMs
                else -> line.endMs
            }
            syllable.copy(endMs = end.coerceAtLeast(syllable.startMs))
        }
        line.copy(
            syllables = closed,
            endMs = maxOf(line.endMs, closed.last().endMs),
        )
    }

    private fun stripTags(body: String): String = WORD_TAG.replace(body, "")

    private fun MatchResult.toMillis(): Int {
        val minutes = groupValues[1].toIntOrNull() ?: 0
        val seconds = groupValues[2].toIntOrNull() ?: 0
        val fractionText = groupValues[3]
        val fraction = when (fractionText.length) {
            0 -> 0
            1 -> fractionText.toInt() * 100
            2 -> fractionText.toInt() * 10
            else -> fractionText.take(3).toInt()
        }
        return minutes * 60_000 + seconds * 1_000 + fraction
    }

    /**
     * Attach a second LRC track (a translation or a romanization) to [base] by
     * matching timestamps, tolerating the small drift between the two files that
     * some sources have.
     */
    fun mergeAlternateTrack(
        base: List<LyricLine>,
        alternateLrc: String,
        toleranceMs: Int = 400,
        assign: (LyricLine, String) -> LyricLine,
    ): List<LyricLine> {
        val alternate = parse(alternateLrc).lines
            .filter { it.role == LineRole.LEAD && it.text.isNotBlank() }
        if (alternate.isEmpty()) return base

        return base.map { line ->
            if (line.role != LineRole.LEAD) return@map line
            val match = alternate.minByOrNull { kotlin.math.abs(it.startMs - line.startMs) }
                ?: return@map line
            if (kotlin.math.abs(match.startMs - line.startMs) > toleranceMs) return@map line
            assign(line, match.text)
        }
    }
}
