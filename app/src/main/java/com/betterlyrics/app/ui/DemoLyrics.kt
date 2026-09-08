package com.betterlyrics.app.ui

import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricLine
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.lyrics.model.Syllable
import com.betterlyrics.app.lyrics.model.withInterludes

/**
 * A synthetic word-timed song, for looking at the renderer without a phone playing
 * music.
 *
 * Deliberately covers the cases that are easy to get wrong and hard to notice: a
 * syllable held long enough to break into individually animated letters, Japanese with
 * a romanization attached, a duet line pinned to the far edge, a backing vocal under
 * its lead, a translated line, and instrumental gaps at both ends.
 *
 * Debug builds only — the entry point is behind `BuildConfig.DEBUG`, so R8 drops it.
 */
object DemoLyrics {

    const val DURATION_MS = 34_000L

    val document: LyricsDocument by lazy { build() }

    /** Stand-in cover art, so the animated background has something to work from. */
    val artwork: android.graphics.Bitmap by lazy {
        val size = 220
        val bitmap = android.graphics.Bitmap.createBitmap(
            size,
            size,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(24, 14, 32))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val blobs = listOf(
            Triple(0.28f, 0.30f, android.graphics.Color.rgb(214, 66, 96)),
            Triple(0.74f, 0.36f, android.graphics.Color.rgb(88, 74, 214)),
            Triple(0.44f, 0.78f, android.graphics.Color.rgb(232, 158, 62)),
            Triple(0.86f, 0.86f, android.graphics.Color.rgb(52, 176, 158)),
        )
        for ((x, y, color) in blobs) {
            paint.shader = android.graphics.RadialGradient(
                x * size,
                y * size,
                size * 0.42f,
                color,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(x * size, y * size, size * 0.42f, paint)
        }
        bitmap
    }

    private fun build(): LyricsDocument {
        val lines = mutableListOf<LyricLine>()

        // Opens after a long enough silence that the intro dots appear.
        lines += lead(
            words(
                4_000,
                "Hold" to 700,
                "on" to 400,
                "to" to 350,
                "the" to 300,
                "light" to 1_800, // long enough to animate letter by letter
            ),
        )

        lines += lead(
            words(
                8_000,
                "we" to 400,
                "are" to 400,
                "almost" to 700,
                "home" to 1_600,
            ),
        )

        // Japanese with a romanization on every syllable, as NetEase or Apple supply it.
        lines += lead(
            // Word starts as Kuromoji reports them: 君 / の / 声 / が are four words, while
            // 聞こえる is one — so the romanization must read "kimi no koe ga kikoeru".
            // `kana` is the furigana gloss, only present on the kanji — printing キミ over
            // 君 helps, printing ノ over の does not.
            listOf(
                Syllable("君", 12_000, 12_500, romanized = "kimi", romanizedStartsWord = true, kana = "キミ"),
                Syllable("の", 12_500, 12_800, true, "no", romanizedStartsWord = true),
                Syllable("声", 12_800, 13_500, true, "koe", romanizedStartsWord = true, kana = "コエ"),
                Syllable("が", 13_500, 13_900, true, "ga", romanizedStartsWord = true),
                Syllable("聞", 14_100, 14_700, romanized = "ki", romanizedStartsWord = true, kana = "キ"),
                Syllable("こ", 14_700, 15_000, true, "ko", romanizedStartsWord = false),
                Syllable("え", 15_000, 15_300, true, "e", romanizedStartsWord = false),
                Syllable("る", 15_300, 16_200, true, "ru", romanizedStartsWord = false),
            ),
            romanized = "kimi no koe ga kikoeru",
            translated = "I can hear your voice",
        )

        // Second voice, pinned to the opposite edge.
        lines += lead(
            words(
                17_000,
                "and" to 350,
                "I'm" to 400,
                "still" to 500,
                "here" to 1_400,
            ),
            oppositeAligned = true,
        )

        // A backing vocal, rendered smaller directly beneath its lead line.
        lines += lead(words(20_000, "carry" to 600, "me" to 500, "under" to 900))
        lines += LyricLine(
            role = LineRole.BACKGROUND,
            startMs = 20_400,
            endMs = 22_000,
            text = "(under, under)",
            syllables = listOf(
                Syllable("(under,", 20_400, 21_100),
                Syllable("under)", 21_100, 22_000),
            ),
        )

        // Long instrumental gap: three dots breathe in turn.
        lines += lead(
            words(
                28_000,
                "everything" to 900,
                "we" to 350,
                "were" to 450,
                "is" to 350,
                "waiting" to 2_100,
            ),
            translated = "todo lo que fuimos está esperando",
        )

        return LyricsDocument(
            kind = LyricsKind.SYLLABLE,
            lines = lines.withInterludes(),
            providerName = "Demo",
            providerId = "demo",
            songWriters = listOf("Renderer preview"),
            hasRomanization = true,
            hasTranslation = true,
        )
    }

    private fun lead(
        syllables: List<Syllable>,
        oppositeAligned: Boolean = false,
        romanized: String? = null,
        translated: String? = null,
    ): LyricLine = LyricLine(
        role = LineRole.LEAD,
        startMs = syllables.first().startMs,
        endMs = syllables.last().endMs,
        text = syllables.joinToString("") { (if (it.partOfWord) "" else " ") + it.text }.trim(),
        syllables = syllables,
        oppositeAligned = oppositeAligned,
        romanized = romanized,
        translated = translated,
    )

    /** Lay `text to durationMs` pairs end to end from [startMs], one word each. */
    private fun words(startMs: Int, vararg parts: Pair<String, Int>): List<Syllable> {
        var cursor = startMs
        return parts.map { (text, duration) ->
            val syllable = Syllable(text = text, startMs = cursor, endMs = cursor + duration)
            cursor += duration
            syllable
        }
    }
}
