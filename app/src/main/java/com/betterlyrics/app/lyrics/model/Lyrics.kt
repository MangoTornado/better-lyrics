package com.betterlyrics.app.lyrics.model

import kotlinx.serialization.Serializable

/**
 * How precise the timing data is. Mirrors Spicy Lyrics' three lyric types, so a
 * TTML file written for it (or for Apple Music) round-trips into this model.
 *
 * - [SYLLABLE] — per-word / per-syllable timings. Karaoke.
 * - [LINE]     — one timestamp per line. What plain LRC gives you.
 * - [STATIC]   — no timings at all.
 */
@Serializable
enum class LyricsKind { SYLLABLE, LINE, STATIC }

@Serializable
enum class LineRole {
    /** The main vocal. */
    LEAD,

    /** Backing vocal, rendered smaller directly under its lead line. */
    BACKGROUND,

    /** An instrumental gap, rendered as three breathing dots. */
    INTERLUDE,
}

@Serializable
data class Syllable(
    val text: String,
    val startMs: Int,
    val endMs: Int,
    /**
     * True when this syllable continues the previous one with no space between
     * them — "to-geth-er" is three syllables but one word, and must not be split
     * across a line break.
     */
    val partOfWord: Boolean = false,
    val romanized: String? = null,
    /**
     * Whether [romanized] begins a new word.
     *
     * Only meaningful for scripts written without spaces: 君の声が arrives as four timed
     * syllables with no gaps to inherit, but its romanization has to read
     * `kimi no koe ga` — while 聞こえる is one word and must stay `kikoeru`. Only the
     * morphological analyser knows which is which, so it records the answer here.
     * `null` means nobody worked it out.
     */
    val romanizedStartsWord: Boolean? = null,
    /**
     * Kana reading, for furigana — the small gloss printed over kanji.
     *
     * Katakana as the analyser reports it; the renderer converts to hiragana when that is
     * what the reader asked for. Null when the syllable needs no gloss (it is already
     * kana, or it is not Japanese).
     */
    val kana: String? = null,
) {
    val durationMs: Int get() = (endMs - startMs).coerceAtLeast(0)
}

@Serializable
data class LyricLine(
    val role: LineRole,
    val startMs: Int,
    val endMs: Int,
    val text: String,
    val syllables: List<Syllable> = emptyList(),
    /** Duet / second voice: pinned to the opposite edge, like Apple Music. */
    val oppositeAligned: Boolean = false,
    val rtl: Boolean = false,
    val romanized: String? = null,
    val translated: String? = null,
) {
    val durationMs: Int get() = (endMs - startMs).coerceAtLeast(0)
    val isInterlude: Boolean get() = role == LineRole.INTERLUDE
}

@Serializable
data class LyricsDocument(
    val kind: LyricsKind,
    val lines: List<LyricLine>,
    /** Human-readable source, shown under the last line. */
    val providerName: String,
    val providerId: String,
    val songWriters: List<String> = emptyList(),
    /** BCP-47-ish language tag of the original text, when we could work it out. */
    val language: String? = null,
    val hasRomanization: Boolean = false,
    val hasTranslation: Boolean = false,
    /** Milliseconds to add to every timestamp; set by the provider, not the user. */
    val providerOffsetMs: Int = 0,
) {
    val isSynced: Boolean get() = kind != LyricsKind.STATIC

    val vocalLines: List<LyricLine> get() = lines.filter { !it.isInterlude }

    fun withRomanization(lines: List<LyricLine>): LyricsDocument =
        copy(lines = lines, hasRomanization = true)

    fun withTranslation(lines: List<LyricLine>): LyricsDocument =
        copy(lines = lines, hasTranslation = true)

    companion object {
        /** Gap (ms) between two vocal lines that earns an interlude marker. */
        const val INTERLUDE_MIN_GAP_MS = 3_000

        /** How long before the next vocal line the dots collapse away again. */
        const val INTERLUDE_PRE_HIDE_MS = 500

        fun static(text: List<String>, providerName: String, providerId: String) =
            LyricsDocument(
                kind = LyricsKind.STATIC,
                lines = text.map {
                    LyricLine(role = LineRole.LEAD, startMs = 0, endMs = 0, text = it)
                },
                providerName = providerName,
                providerId = providerId,
            )
    }
}

/**
 * Insert [LineRole.INTERLUDE] markers wherever the song goes quiet for at least
 * [LyricsDocument.INTERLUDE_MIN_GAP_MS] — including before the first line, which is
 * where the intro dots come from.
 *
 * Background lines never open a gap of their own: they are timed inside their lead
 * line, so only lead lines are considered when measuring silence.
 */
fun List<LyricLine>.withInterludes(
    minGapMs: Int = LyricsDocument.INTERLUDE_MIN_GAP_MS,
): List<LyricLine> {
    if (isEmpty()) return this
    val out = ArrayList<LyricLine>(size + 8)

    var previousLeadEnd: Int? = null
    for (line in this) {
        if (line.role == LineRole.LEAD) {
            val gapStart = previousLeadEnd ?: 0
            val gap = line.startMs - gapStart
            if (gap >= minGapMs) {
                out += LyricLine(
                    role = LineRole.INTERLUDE,
                    startMs = gapStart,
                    endMs = line.startMs,
                    text = "",
                    oppositeAligned = line.oppositeAligned,
                )
            }
            previousLeadEnd = maxOf(previousLeadEnd ?: 0, line.endMs)
        }
        out += line
    }
    return out
}

/**
 * Fill in [LyricLine.endMs] for formats that only carry start times (plain LRC):
 * a line runs until the next one starts, capped at [maxLineMs] so a long outro gap
 * doesn't leave the last sung line highlighted for a minute.
 */
fun List<LyricLine>.inferEndTimes(
    trackDurationMs: Long,
    maxLineMs: Int = 10_000,
): List<LyricLine> = mapIndexed { index, line ->
    if (line.endMs > line.startMs) return@mapIndexed line
    val next = getOrNull(index + 1)
    val hardEnd = when {
        next != null -> next.startMs
        trackDurationMs > 0 -> trackDurationMs.toInt()
        else -> line.startMs + maxLineMs
    }
    line.copy(endMs = minOf(hardEnd, line.startMs + maxLineMs).coerceAtLeast(line.startMs))
}
