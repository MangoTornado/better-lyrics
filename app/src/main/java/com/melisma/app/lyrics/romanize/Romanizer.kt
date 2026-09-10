package com.melisma.app.lyrics.romanize

import android.icu.text.Transliterator
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.util.Script
import com.melisma.app.util.detectScript
import com.melisma.app.util.needsRomanization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns non-Latin lyrics into something you can actually sing.
 *
 * Two engines, picked by script:
 *
 * - **Japanese** goes through Kuromoji, because kanji have no fixed reading — 生 is
 *   *ki*, *sei*, *nama* or *i* depending on the word around it. Only a morphological
 *   analyser gets this right, and it is the single reason the app carries a
 *   dictionary. Notably, running kanji through a Han transliterator instead would
 *   produce Mandarin readings, which is wrong in a way that is hard to notice.
 * - **Everything else** (Hangul, Han, Cyrillic, Greek) uses the ICU transliterators
 *   built into Android, which are per-character and need no context.
 *
 * A romanization the provider already shipped is never overwritten — a human-checked
 * `romalrc` beats anything generated here.
 */
class Romanizer {

    private val tokenizerLock = Mutex()

    @Volatile
    private var tokenizer: Tokenizer? = null

    private val transliterators = HashMap<String, Transliterator?>()

    /** Drops tone marks and other diacritics, for people who find `nǐ hǎo` harder to read. */
    private val diacriticStripper: Transliterator? by lazy {
        runCatching {
            Transliterator.getInstance("NFD; [:Nonspacing Mark:] Remove; NFC")
        }.getOrNull()
    }

    /**
     * Fills in whichever annotations the reader asked for.
     *
     * One pass rather than two, because both come out of the same analysis: the romaji and
     * the kana gloss for 君 are two readings of one dictionary lookup. Furigana is
     * Japanese-only by definition, so [furigana] is ignored for every other script.
     */
    suspend fun annotate(
        document: LyricsDocument,
        romanize: Boolean,
        furigana: Boolean,
        stripDiacritics: Boolean = false,
    ): LyricsDocument = withContext(Dispatchers.Default) {
        if (!romanize && !furigana) return@withContext document

        val corpus = document.lines.joinToString("\n") { it.text }
        val script = detectScript(corpus)
        val wantFurigana = furigana && script == Script.JAPANESE
        val wantRomaji = romanize && script.needsRomanization()
        if (!wantRomaji && !wantFurigana) return@withContext document

        if (script == Script.JAPANESE) prepareTokenizer()

        var produced = false
        val lines = document.lines.map { line ->
            if (line.isInterlude || line.text.isBlank()) return@map line
            val annotated = annotateLine(line, script, stripDiacritics, wantRomaji)
            if (annotated !== line) produced = true
            annotated
        }

        if (!produced) return@withContext document
        document.copy(
            lines = lines,
            hasRomanization = document.hasRomanization || wantRomaji,
            language = document.language ?: scriptLanguage(script),
        )
    }

    private fun scriptLanguage(script: Script): String? = when (script) {
        Script.JAPANESE -> "ja"
        Script.CHINESE -> "zh"
        Script.KOREAN -> "ko"
        Script.CYRILLIC -> "ru"
        Script.GREEK -> "el"
        else -> null
    }

    private fun annotateLine(
        line: LyricLine,
        script: Script,
        stripDiacritics: Boolean,
        wantRomaji: Boolean,
    ): LyricLine {
        if (line.syllables.isEmpty()) {
            if (!wantRomaji || !line.romanized.isNullOrBlank()) return line
            val text = romanizeText(line.text, script, stripDiacritics) ?: return line
            return line.copy(romanized = text)
        }

        val syllables = annotateSyllables(line.syllables, script, stripDiacritics, wantRomaji)
            ?: return line
        if (!wantRomaji) return line.copy(syllables = syllables)

        val joined = syllables.joinToString("") { syllable ->
            val part = syllable.romanized ?: syllable.text
            (if (syllable.partOfWord) "" else " ") + part
        }.trim()

        return line.copy(
            syllables = syllables,
            romanized = line.romanized ?: joined,
        )
    }

    /**
     * Romanize a line's syllables while keeping the one-to-one mapping the renderer
     * needs — every syllable must come out with its own text, or the karaoke wipe has
     * nothing to fill.
     */
    private fun annotateSyllables(
        syllables: List<Syllable>,
        script: Script,
        stripDiacritics: Boolean,
        wantRomaji: Boolean,
    ): List<Syllable>? {
        if (script != Script.JAPANESE) {
            if (!wantRomaji) return null
            // Per-character scripts: each syllable converts independently.
            var changed = false
            val out = syllables.map { syllable ->
                if (!syllable.romanized.isNullOrBlank()) return@map syllable
                val romanized = romanizeText(syllable.text, script, stripDiacritics)
                    ?: return@map syllable
                changed = true
                syllable.copy(romanized = romanized)
            }
            return if (changed) out else null
        }

        val tokenizer = this.tokenizer ?: return null

        // Reconstruct the line exactly as written so the analyser sees real words,
        // and remember where each syllable sits inside it.
        val builder = StringBuilder()
        val ranges = ArrayList<IntRange>(syllables.size)
        for (syllable in syllables) {
            val start = builder.length
            builder.append(syllable.text)
            ranges += start until builder.length
        }
        val text = builder.toString()
        if (text.isBlank()) return null

        val tokens = runCatching { tokenizer.tokenize(text) }.getOrNull() ?: return null
        val spans = tokens.map { token ->
            TokenSpan(
                start = token.position,
                end = token.position + token.surface.length,
                romaji = token.romaji(),
                kana = token.kanaReading(),
            )
        }

        var changed = false
        val out = syllables.mapIndexed { index, syllable ->
            val range = ranges[index]
            val kana = syllable.kana ?: kanaFor(range, spans, syllable.text)

            if (!wantRomaji || !syllable.romanized.isNullOrBlank()) {
                if (kana == syllable.kana) return@mapIndexed syllable
                changed = true
                return@mapIndexed syllable.copy(kana = kana)
            }

            val romanized = romajiFor(range, spans).trim()
            if (romanized.isEmpty()) {
                if (kana == syllable.kana) return@mapIndexed syllable
                changed = true
                return@mapIndexed syllable.copy(kana = kana)
            }
            changed = true
            syllable.copy(
                romanized = romanized,
                // A syllable that a token starts at is the start of a word; one that
                // merely continues a token is the middle of one.
                romanizedStartsWord = spans.any { it.start == range.first },
                kana = kana,
            )
        }
        return if (changed) out else null
    }

    private class TokenSpan(
        val start: Int,
        val end: Int,
        val romaji: String,
        /** Katakana reading, for furigana. Null when the surface is already kana. */
        val kana: String?,
    )

    /**
     * The romaji covering [range].
     *
     * A token usually lines up with a syllable, but not always: `食べて` may arrive as
     * three timed syllables inside one token whose reading (`tabete`) is only defined
     * for the whole thing. When that happens the reading is split across the syllables
     * in proportion to how many characters each covers — approximate per syllable, but
     * it never drops or duplicates a sound across the line, which is what would
     * actually look broken.
     */
    private fun romajiFor(range: IntRange, spans: List<TokenSpan>): String {
        val builder = StringBuilder()
        for (span in spans) {
            if (span.end <= range.first || span.start > range.last) continue
            val spanLength = span.end - span.start
            if (spanLength <= 0 || span.romaji.isEmpty()) continue

            val overlapStart = maxOf(range.first, span.start)
            val overlapEnd = minOf(range.last + 1, span.end)
            if (overlapStart >= overlapEnd) continue

            if (overlapStart == span.start && overlapEnd == span.end) {
                builder.append(span.romaji)
            } else {
                val from = ((overlapStart - span.start).toFloat() / spanLength *
                    span.romaji.length).toInt().coerceIn(0, span.romaji.length)
                val to = ((overlapEnd - span.start).toFloat() / spanLength *
                    span.romaji.length).toInt().coerceIn(from, span.romaji.length)
                builder.append(span.romaji, from, to)
            }
        }
        return builder.toString()
    }

    /**
     * The kana gloss for [range], or null when it needs none.
     *
     * A syllable that is already kana is left alone — printing おも over 思 is useful,
     * printing う over う is noise. A kanji syllable that sits inside a longer token
     * takes a proportional slice of that token's reading, on the same basis as the
     * romaji: approximate per syllable, but never losing or repeating a sound.
     */
    private fun kanaFor(range: IntRange, spans: List<TokenSpan>, surface: String): String? {
        if (!surface.any { it.isHanCharacter() }) return null

        val builder = StringBuilder()
        for (span in spans) {
            if (span.end <= range.first || span.start > range.last) continue
            val reading = span.kana ?: continue
            val spanLength = span.end - span.start
            if (spanLength <= 0 || reading.isEmpty()) continue

            val overlapStart = maxOf(range.first, span.start)
            val overlapEnd = minOf(range.last + 1, span.end)
            if (overlapStart >= overlapEnd) continue

            if (overlapStart == span.start && overlapEnd == span.end) {
                builder.append(reading)
            } else {
                val from = ((overlapStart - span.start).toFloat() / spanLength *
                    reading.length).toInt().coerceIn(0, reading.length)
                val to = ((overlapEnd - span.start).toFloat() / spanLength *
                    reading.length).toInt().coerceIn(from, reading.length)
                builder.append(reading, from, to)
            }
        }
        return builder.toString().trim().takeIf { it.isNotEmpty() && it != surface }
    }

    private fun Char.isHanCharacter(): Boolean =
        this in '\u4e00'..'\u9fff' || this in '\u3400'..'\u4dbf' || this in '\uf900'..'\ufaff'

    /** Katakana reading straight from the dictionary, or null if there is nothing to add. */
    private fun Token.kanaReading(): String? =
        (reading ?: pronunciation)?.takeIf { it.isNotBlank() && it != "*" && it != surface }

    private fun Token.romaji(): String {
        val reading = pronunciation?.takeIf { it.isNotBlank() && it != "*" }
            ?: this.reading?.takeIf { it.isNotBlank() && it != "*" }
        return when {
            reading != null -> Romaji.fromKana(reading)
            Romaji.isPureKana(surface) -> Romaji.fromKana(surface)
            // Unknown word (often a foreign name in katakana already handled above, or
            // punctuation): leave it as it is rather than inventing a reading.
            else -> surface
        }
    }

    fun romanizeText(text: String, script: Script, stripDiacritics: Boolean = false): String? {
        if (text.isBlank()) return null
        val converted = when (script) {
            Script.JAPANESE -> {
                val tokenizer = this.tokenizer ?: return null
                runCatching {
                    tokenizer.tokenize(text).joinToString("") { token ->
                        val romaji = token.romaji()
                        if (romaji.isEmpty()) "" else "$romaji "
                    }.trim()
                }.getOrNull()
            }

            Script.CHINESE -> transliterate("Han-Latin", text)
            Script.KOREAN -> transliterate("Hangul-Latin", text)
            Script.CYRILLIC -> transliterate("Cyrillic-Latin", text)
            Script.GREEK -> transliterate("Greek-Latin", text)
            Script.LATIN, Script.OTHER -> null
        } ?: return null

        val cleaned = converted.replace(Regex("\\s{2,}"), " ").trim()
        if (cleaned.isEmpty() || cleaned == text) return null
        return if (stripDiacritics) {
            diacriticStripper?.transliterate(cleaned) ?: cleaned
        } else {
            cleaned
        }
    }

    private fun transliterate(id: String, text: String): String? {
        val instance = transliterators.getOrPut(id) {
            runCatching { Transliterator.getInstance(id) }.getOrNull()
        } ?: return null
        return runCatching { instance.transliterate(text) }.getOrNull()
    }

    /**
     * Build the Kuromoji dictionary once. It costs a second or two and a few tens of
     * megabytes of heap, so it is only paid for on the first Japanese track of the
     * session and never on the main thread.
     */
    suspend fun prepareTokenizer() {
        if (tokenizer != null) return
        tokenizerLock.withLock {
            if (tokenizer != null) return
            tokenizer = withContext(Dispatchers.IO) {
                runCatching { Tokenizer() }.getOrNull()
            }
        }
    }
}
