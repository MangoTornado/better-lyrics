package com.betterlyrics.app.ui.lyrics

import android.graphics.Paint
import android.graphics.Typeface
import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricLine
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.lyrics.model.Syllable
import com.betterlyrics.app.util.isSpacelessScript
import java.text.BreakIterator

/**
 * Type metrics and the paints the renderer draws with.
 *
 * Everything is derived from one number — the lyric font size — so a single font-scale
 * setting moves the whole composition (line spacing, the defocus radius, the size of
 * the interlude dots) in proportion, exactly as the container-relative units in Spicy
 * Lyrics' stylesheet do.
 */
class LyricsMetrics(
    val fontSizePx: Float,
    val simpleMode: Boolean,
    val showSecondaryLine: Boolean,
    /** Null means the platform default; otherwise a family name such as `serif`. */
    val fontFamily: String? = null,
    /** Tighter spacing, for a small window or split screen. */
    val compact: Boolean = false,
) {
    val lineHeightPx: Float = fontSizePx * 1.1818182f
    val lineSpacingPx: Float = fontSizePx * (if (compact) 0.18f else 0.36f)
    val backgroundFontSizePx: Float = fontSizePx * 0.75f
    val secondaryFontSizePx: Float = fontSizePx * (if (compact) 0.4f else 0.44f)
    val dotFontSizePx: Float = fontSizePx * 1.3f

    /** Songwriter credits, sized as Spicy Lyrics sizes them: 0.47em at 60 % opacity. */
    val creditsFontSizePx: Float = fontSizePx * 0.47f

    /** The "provided by" line: 0.34em at 50 % opacity. */
    val providerFontSizePx: Float = fontSizePx * 0.34f

    /** Furigana: small enough to read as a gloss, large enough to actually read. */
    val rubyFontSizePx: Float = fontSizePx * 0.36f

    val leadPaint: Paint = paint(fontSizePx, 700)
    val backgroundPaint: Paint = paint(backgroundFontSizePx, 600)
    val secondaryPaint: Paint = paint(secondaryFontSizePx, 500)
    val dotPaint: Paint = paint(dotFontSizePx, 700)
    val creditsPaint: Paint = paint(creditsFontSizePx, 600)
    val providerPaint: Paint = paint(providerFontSizePx, 600)
    val rubyPaint: Paint = paint(rubyFontSizePx, 500)

    /** Vertical room a line needs above it once furigana is switched on. */
    val rubyHeightPx: Float = rubyFontSizePx * 1.3f

    /** CSS `0.32ch` — the gap Spicy Lyrics puts between words. */
    val wordGapPx: Float = 0.32f * leadPaint.measureText("0")

    val backgroundWordGapPx: Float = 0.32f * backgroundPaint.measureText("0")

    val dotGapPx: Float = dotFontSizePx * 0.14f

    val blurPerLinePx: Float = LyricsAnim.BLUR_PER_LINE_EM * fontSizePx
    val blurMaxPx: Float = LyricsAnim.BLUR_MAX_EM * fontSizePx

    private fun paint(size: Float, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        val base = fontFamily?.let { Typeface.create(it, Typeface.NORMAL) } ?: Typeface.SANS_SERIF
        typeface = Typeface.create(base, weight, false)
        isSubpixelText = true
        isLinearText = true
    }

    fun paintFor(role: LineRole): Paint =
        if (role == LineRole.BACKGROUND) backgroundPaint else leadPaint

    fun fontSizeFor(role: LineRole): Float =
        if (role == LineRole.BACKGROUND) backgroundFontSizePx else fontSizePx

    fun key(): String = "$fontSizePx|$simpleMode|$showSecondaryLine|$fontFamily|$compact"
}

/** One drawable piece of a line: a syllable, a whole word, a wrapped row, or a dot. */
class PlacedUnit(
    val text: String,
    /** Left edge, relative to the line's content box. */
    val x: Float,
    /** Baseline, relative to the line's top. */
    val baseline: Float,
    val width: Float,
    val startMs: Int,
    val endMs: Int,
    /** Vertical box the fill gradient sweeps through, relative to the line's top. */
    val gradientTop: Float,
    val gradientHeight: Float,
    val letters: List<PlacedLetter>? = null,
    val isDot: Boolean = false,
    /** Kana gloss to print above this unit, already in the script the reader chose. */
    val ruby: String? = null,
    val rubyX: Float = 0f,
    val rubyBaseline: Float = 0f,
) {
    /**
     * Wall clock (`elapsedRealtime`) at which this unit became the active one.
     *
     * Only used by [com.betterlyrics.app.settings.TextAnimationStyle.ANIMATE], which runs
     * the fill sweep at a steady rate from that moment instead of deriving it from the
     * reported playhead every frame.
     */
    var sweepStartedAt: Long = 0L

    val centerX: Float get() = x + width / 2f
    val springs: UnitSprings = UnitSprings(
        scaleStart = if (isDot) LyricsAnim.dotScale.at(0f) else LyricsAnim.wordScale.at(0f),
        yOffsetStart = if (isDot) LyricsAnim.dotYOffset.at(0f) else LyricsAnim.wordYOffset.at(0f),
        glowStart = if (isDot) LyricsAnim.dotGlow.at(0f) else LyricsAnim.glow.at(0f),
        opacityStart = if (isDot) 0.35f else null,
        dot = isDot,
    )
}

class PlacedLetter(
    val text: String,
    val x: Float,
    val width: Float,
    val startMs: Int,
    val endMs: Int,
) {
    val centerX: Float get() = x + width / 2f
    val springs: UnitSprings = UnitSprings(
        scaleStart = LyricsAnim.letterScale.at(0f),
        yOffsetStart = LyricsAnim.letterYOffset.at(0f),
        glowStart = LyricsAnim.glow.at(0f),
    )
}

/** A wrapped row of plain text — used for the translation shown under a line. */
class TextRow(val text: String, val x: Float, val baseline: Float)

class LineLayout(
    val source: LyricLine,
    val index: Int,
    val units: List<PlacedUnit>,
    val secondaryRows: List<TextRow>,
    /**
     * The same text as [units], but pre-joined one string per wrapped row.
     *
     * A line that is not the active one is drawn as a single flat pass, so this saves
     * one `drawText` (and one blur) per syllable on every line on screen — which is most
     * of them.
     */
    val flatRows: List<TextRow>,
    val contentHeight: Float,
    val height: Float,
    val top: Float,
    val alignRight: Boolean,
) {
    val role: LineRole get() = source.role
    val isInterlude: Boolean get() = source.isInterlude
    val startMs: Int get() = source.startMs
    val endMs: Int get() = source.endMs
    val bottom: Float get() = top + height

    /** Springs for the line as a whole: only line-synced lyrics use these. */
    val springs: UnitSprings = UnitSprings(
        scaleStart = 1f,
        yOffsetStart = 0f,
        glowStart = 0f,
    )

    /**
     * Whole-line scale and opacity, for Minimal mode — where a sung line does not merely
     * dim, it shrinks slightly and fades out of the page altogether.
     */
    val minimalScale = com.betterlyrics.app.core.Spring(1f, 1.5f, 1f)
    val minimalOpacity = com.betterlyrics.app.core.Spring(1f, 1.9f, 1f)
}

/**
 * A line of the credits block drawn after the last lyric — songwriters, then the source.
 *
 * Spicy Lyrics puts these inside the scroll area rather than in the chrome, so they read
 * as the end of the song rather than as app furniture. Same here.
 */
class FooterRow(
    val text: String,
    val x: Float,
    val baseline: Float,
    val sizePx: Float,
    val alpha: Float,
    val bold: Boolean,
)

class LyricsLayout(
    val document: LyricsDocument,
    val lines: List<LineLayout>,
    val footer: List<FooterRow>,
    val contentHeight: Float,
    val metrics: LyricsMetrics,
    val widthPx: Float,
) {
    /**
     * Index of the line the playhead is inside.
     *
     * Background lines are skipped: they sit inside their lead line's window, so
     * letting one win would make the highlight jump back and forth between a line and
     * its own backing vocal.
     */
    fun activeIndexAt(positionMs: Int): Int {
        var result = -1
        for (line in lines) {
            if (line.role == LineRole.BACKGROUND) continue
            if (line.startMs <= positionMs) result = line.index else break
        }
        return result
    }

    fun lineAt(y: Float): LineLayout? = lines.firstOrNull { y >= it.top && y < it.bottom }
}

/**
 * Turns a [LyricsDocument] into absolute positions.
 *
 * Done once per (document, width, style) rather than per frame — the renderer then only
 * has to decide colour, scale and lift, which is what keeps a 60 fps draw affordable.
 */
object LyricsLayoutBuilder {

    /** A syllable held for at least this long is broken into individually animated letters. */
    private const val EMPHASIS_MIN_DURATION_MS = 1_000

    /** Beyond this many characters, letter-level emphasis stops reading as deliberate. */
    private const val EMPHASIS_MAX_LETTERS = 12

    /** Emphasised letters finish slightly before the syllable does, as Spicy Lyrics does. */
    private const val EMPHASIS_TAIL_MS = 250

    fun build(
        document: LyricsDocument,
        metrics: LyricsMetrics,
        widthPx: Float,
        useRomanization: Boolean,
        showTranslation: Boolean,
        duetPadding: Boolean = true,
        showCredits: Boolean = true,
        /** Null switches furigana off; true renders it as hiragana, false as katakana. */
        furiganaHiragana: Boolean? = null,
    ): LyricsLayout {
        // Reserve the ruby band across the whole document rather than per line, so the
        // spacing does not jump between a line with kanji and one without.
        val rubyRoom = furiganaHiragana != null &&
            document.lines.any { line -> line.syllables.any { !it.kana.isNullOrBlank() } }
        val hasDuet = document.lines.any { it.oppositeAligned }
        // A duet gets a wide inset so the two voices read as separate columns; a single
        // voice only gets the small trailing inset every line has.
        val insetPx = if (hasDuet && duetPadding) widthPx * 0.15f else widthPx * 0.05f

        val out = ArrayList<LineLayout>(document.lines.size)
        var y = 0f

        for ((index, line) in document.lines.withIndex()) {
            val alignRight = line.oppositeAligned != line.rtl
            val maxWidth = (widthPx - insetPx).coerceAtLeast(metrics.fontSizePx * 2f)

            val layout = when {
                line.isInterlude -> interludeLayout(line, index, metrics, maxWidth, alignRight, y)
                line.syllables.isNotEmpty() && document.kind == LyricsKind.SYLLABLE ->
                    syllableLayout(
                        line, index, metrics, maxWidth, alignRight, y,
                        useRomanization, showTranslation,
                        if (rubyRoom) furiganaHiragana else null,
                    )

                else -> plainLayout(
                    line, index, metrics, maxWidth, alignRight, y,
                    useRomanization, showTranslation,
                    timed = document.kind != LyricsKind.STATIC,
                )
            }
            out += layout
            y += layout.height
        }

        val footer = if (showCredits) {
            buildFooter(document, metrics, widthPx - insetPx, y)
        } else {
            emptyList()
        }
        val footerHeight = footer.sumOf { (it.sizePx * 1.5f).toDouble() }.toFloat()

        return LyricsLayout(document, out, footer, y + footerHeight, metrics, widthPx)
    }

    private fun buildFooter(
        document: LyricsDocument,
        metrics: LyricsMetrics,
        maxWidth: Float,
        top: Float,
    ): List<FooterRow> {
        val rows = ArrayList<FooterRow>(4)
        var y = top + metrics.lineSpacingPx

        if (document.songWriters.isNotEmpty()) {
            val text = document.songWriters.joinToString(", ")
            for (row in wrap(text, metrics.creditsPaint, maxWidth)) {
                y += metrics.creditsFontSizePx * 1.5f
                rows += FooterRow(
                    text = row,
                    x = 0f,
                    baseline = y - metrics.creditsFontSizePx * 0.4f,
                    sizePx = metrics.creditsFontSizePx,
                    alpha = 0.6f,
                    bold = true,
                )
            }
        }

        val provider = document.providerName.takeIf { it.isNotBlank() }
        if (provider != null) {
            for (row in wrap("Lyrics from $provider", metrics.providerPaint, maxWidth)) {
                y += metrics.providerFontSizePx * 1.5f
                rows += FooterRow(
                    text = row,
                    x = 0f,
                    baseline = y - metrics.providerFontSizePx * 0.4f,
                    sizePx = metrics.providerFontSizePx,
                    alpha = 0.5f,
                    bold = false,
                )
            }
        }
        return rows
    }

    // ---- syllable-timed lines ----------------------------------------------

    private fun syllableLayout(
        line: LyricLine,
        index: Int,
        metrics: LyricsMetrics,
        maxWidth: Float,
        alignRight: Boolean,
        top: Float,
        useRomanization: Boolean,
        showTranslation: Boolean,
        furiganaHiragana: Boolean?,
    ): LineLayout {
        val paint = metrics.paintFor(line.role)
        val gap = if (line.role == LineRole.BACKGROUND) {
            metrics.backgroundWordGapPx
        } else {
            metrics.wordGapPx
        }
        val fontSize = metrics.fontSizeFor(line.role)
        val rubyRoom = if (furiganaHiragana != null) metrics.rubyHeightPx else 0f
        val lineHeight = fontSize * 1.1818182f + rubyRoom

        // Group syllables into words: a syllable flagged partOfWord continues the
        // previous one, and a word is the smallest thing a line break may fall between.
        val words = ArrayList<MutableList<Syllable>>()
        for (syllable in line.syllables) {
            if (syllable.partOfWord && words.isNotEmpty()) {
                words.last() += syllable
            } else {
                words += mutableListOf(syllable)
            }
        }

        // Japanese, Chinese and Korean are written without spaces, so a romanized
        // syllable has no gap to inherit from the original and the Latin text would run
        // together as `kiminokoega`. A narrow gap restores the word boundaries the
        // reading needs without fragmenting the line into separate words.
        val romanizedGap = metrics.wordGapPx * 0.55f

        val hasSyllableRomanization = line.syllables.any { !it.romanized.isNullOrBlank() }

        // RTL text is shaped as a run; splitting it per syllable would break the joins,
        // so each word stays whole and takes its syllables' combined window.
        val displayWords: List<List<DisplayPiece>> = words.map { word ->
            if (line.rtl) {
                listOf(
                    DisplayPiece(
                        text = word.joinToString("") { pieceText(it, useRomanization) },
                        startMs = word.minOf { it.startMs },
                        endMs = word.maxOf { it.endMs },
                        leadingGap = 0f,
                        ruby = null,
                    ),
                )
            } else {
                word.mapIndexed { pieceIndex, syllable ->
                    val ruby = if (furiganaHiragana != null && !useRomanization) {
                        syllable.kana?.let { kanaIn(it, furiganaHiragana) }
                    } else {
                        null
                    }
                    val romanizedFromSpaceless = useRomanization &&
                        !syllable.romanized.isNullOrBlank() &&
                        isSpacelessScript(syllable.text)
                    // Trust the analyser when it told us where the words are; otherwise
                    // space every syllable, which is at least always readable.
                    val startsWord = syllable.romanizedStartsWord != false
                    DisplayPiece(
                        text = pieceText(syllable, useRomanization),
                        startMs = syllable.startMs,
                        endMs = syllable.endMs,
                        leadingGap = if (pieceIndex > 0 && romanizedFromSpaceless && startsWord) {
                            romanizedGap
                        } else {
                            0f
                        },
                        ruby = ruby,
                    )
                }
            }
        }

        // Measure, then greedily wrap by word.
        val wordWidths = displayWords.map { pieces ->
            pieces.sumOf { (paint.measureText(it.text) + it.leadingGap).toDouble() }.toFloat()
        }

        val rows = ArrayList<MutableList<Int>>()
        var rowWidth = 0f
        rows += mutableListOf<Int>()
        for ((wordIndex, width) in wordWidths.withIndex()) {
            val needed = if (rows.last().isEmpty()) width else rowWidth + gap + width
            if (needed > maxWidth && rows.last().isNotEmpty()) {
                rows += mutableListOf<Int>()
                rowWidth = width
            } else {
                rowWidth = needed
            }
            rows.last() += wordIndex
        }
        if (rows.last().isEmpty()) rows.removeAt(rows.lastIndex)

        val units = ArrayList<PlacedUnit>(line.syllables.size)
        val flatRows = ArrayList<TextRow>(rows.size)
        val contentHeight = rows.size * lineHeight
        val gradientTop = 0f

        for ((rowIndex, row) in rows.withIndex()) {
            val widthOfRow = row.sumOf { wordWidths[it].toDouble() }.toFloat() +
                gap * (row.size - 1).coerceAtLeast(0)
            val rowStartX = if (alignRight) maxWidth - widthOfRow else 0f
            var x = rowStartX
            val baseline = rowIndex * lineHeight + rubyRoom + (lineHeight - rubyRoom) * 0.78f
            val rowText = StringBuilder()

            val orderedRow = if (line.rtl) row.reversed() else row
            for (wordIndex in orderedRow) {
                if (rowText.isNotEmpty()) rowText.append(' ')
                for (piece in displayWords[wordIndex]) {
                    x += piece.leadingGap
                    if (piece.leadingGap > 0f && rowText.isNotEmpty()) rowText.append(' ')
                    val width = paint.measureText(piece.text)
                    units += placeUnit(
                        piece = piece,
                        paint = paint,
                        x = x,
                        baseline = baseline,
                        width = width,
                        rowTop = rowIndex * lineHeight,
                        rowHeight = lineHeight,
                        gradientTop = gradientTop,
                        rubyPaint = metrics.rubyPaint,
                        rubyBaseline = rowIndex * lineHeight + metrics.rubyFontSizePx * 1.05f,
                    )
                    rowText.append(piece.text)
                    x += width
                }
                x += gap
            }
            flatRows += TextRow(rowText.toString(), rowStartX, baseline)
        }

        // A romanization that exists only for the whole line cannot be swapped in per
        // syllable — the community corpus writes it mora by mora (`fu ka n ze n na bo ku
        // wo` against six sung syllables), so there is no honest way to map one onto the
        // other. Rather than guess an alignment and desynchronise the karaoke, the
        // original text keeps its timings and the reading is shown underneath.
        val lineRomanization = if (useRomanization && !hasSyllableRomanization) {
            line.romanized?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        val secondary = secondaryRows(
            line, metrics, maxWidth, alignRight, contentHeight, showTranslation,
            romanization = lineRomanization,
        )
        val secondaryHeight = secondary.size * metrics.secondaryFontSizePx * 1.35f

        return LineLayout(
            source = line,
            index = index,
            units = units,
            secondaryRows = secondary,
            flatRows = flatRows,
            contentHeight = contentHeight + secondaryHeight,
            height = contentHeight + secondaryHeight + metrics.lineSpacingPx,
            top = top,
            alignRight = alignRight,
        )
    }

    private class DisplayPiece(
        val text: String,
        val startMs: Int,
        val endMs: Int,
        /** Extra space in front of this piece, inside its word. */
        val leadingGap: Float,
        val ruby: String?,
    )

    /** Katakana as the analyser gives it, or hiragana if that is what was asked for. */
    private fun kanaIn(katakana: String, hiragana: Boolean): String {
        if (!hiragana) return katakana
        return buildString(katakana.length) {
            for (c in katakana) {
                append(if (c in '\u30a1'..'\u30f6') c - 0x60 else c)
            }
        }
    }

    private fun pieceText(syllable: Syllable, useRomanization: Boolean): String =
        if (useRomanization) syllable.romanized?.takeIf { it.isNotBlank() } ?: syllable.text
        else syllable.text

    private fun placeUnit(
        piece: DisplayPiece,
        paint: Paint,
        x: Float,
        baseline: Float,
        width: Float,
        rowTop: Float,
        rowHeight: Float,
        gradientTop: Float,
        rubyPaint: Paint,
        rubyBaseline: Float,
    ): PlacedUnit {
        val letters = emphasisLetters(piece, paint, x)
        val rubyWidth = piece.ruby?.let { rubyPaint.measureText(it) } ?: 0f
        return PlacedUnit(
            text = piece.text,
            x = x,
            baseline = baseline,
            width = width,
            startMs = piece.startMs,
            endMs = piece.endMs,
            // The sweep runs through the syllable's own row box, so each word lights up
            // top-to-bottom as it is sung rather than the whole line washing over.
            gradientTop = rowTop + gradientTop,
            gradientHeight = rowHeight,
            letters = letters,
            ruby = piece.ruby,
            // Centred over the syllable it glosses, which is what makes it read as ruby
            // rather than as another line of text.
            rubyX = x + (width - rubyWidth) / 2f,
            rubyBaseline = rubyBaseline,
        )
    }

    /**
     * Split a long-held syllable into letters, each with its own slice of the window.
     *
     * This is what makes a held note *look* held: the letters of "aliiiive" light up one
     * after another instead of the whole word sitting bright for two seconds.
     */
    private fun emphasisLetters(
        piece: DisplayPiece,
        paint: Paint,
        originX: Float,
    ): List<PlacedLetter>? {
        val duration = piece.endMs - piece.startMs
        if (duration < EMPHASIS_MIN_DURATION_MS) return null

        val graphemes = graphemesOf(piece.text)
        if (graphemes.size < 2 || graphemes.size > EMPHASIS_MAX_LETTERS) return null

        val start = piece.startMs
        val end = (piece.endMs - EMPHASIS_TAIL_MS).coerceAtLeast(start + 1)
        val each = (end - start).toFloat() / graphemes.size

        var x = originX
        return graphemes.mapIndexed { index, grapheme ->
            val width = paint.measureText(grapheme)
            val letter = PlacedLetter(
                text = grapheme,
                x = x,
                width = width,
                startMs = (start + index * each).toInt(),
                endMs = (start + (index + 1) * each).toInt(),
            )
            x += width
            letter
        }
    }

    private fun graphemesOf(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val out = ArrayList<String>(text.length)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return out
    }

    // ---- line-timed and untimed lines --------------------------------------

    private fun plainLayout(
        line: LyricLine,
        index: Int,
        metrics: LyricsMetrics,
        maxWidth: Float,
        alignRight: Boolean,
        top: Float,
        useRomanization: Boolean,
        showTranslation: Boolean,
        timed: Boolean,
    ): LineLayout {
        val paint = metrics.paintFor(line.role)
        val fontSize = metrics.fontSizeFor(line.role)
        val lineHeight = fontSize * 1.1818182f
        val text = if (useRomanization) {
            line.romanized?.takeIf { it.isNotBlank() } ?: line.text
        } else {
            line.text
        }

        val rows = wrap(text, paint, maxWidth)
        val contentHeight = rows.size * lineHeight

        // One unit per wrapped row, all sharing the line's window. The gradient box
        // spans the whole line so the sweep runs continuously down through the rows
        // instead of restarting on each one.
        val units = rows.mapIndexed { rowIndex, rowText ->
            val width = paint.measureText(rowText)
            val x = if (alignRight) maxWidth - width else 0f
            PlacedUnit(
                text = rowText,
                x = x,
                baseline = rowIndex * lineHeight + lineHeight * 0.78f,
                width = width,
                startMs = line.startMs,
                endMs = if (timed) line.endMs else line.startMs,
                gradientTop = 0f,
                gradientHeight = contentHeight,
            )
        }

        // No syllables to desynchronise here, so the romanization simply replaces the
        // text above and needs no row of its own.
        val secondary = secondaryRows(
            line, metrics, maxWidth, alignRight, contentHeight, showTranslation,
            romanization = null,
        )
        val secondaryHeight = secondary.size * metrics.secondaryFontSizePx * 1.35f

        return LineLayout(
            source = line,
            index = index,
            units = units,
            secondaryRows = secondary,
            flatRows = units.map { TextRow(it.text, it.x, it.baseline) },
            contentHeight = contentHeight + secondaryHeight,
            height = contentHeight + secondaryHeight + metrics.lineSpacingPx,
            top = top,
            alignRight = alignRight,
        )
    }

    /**
     * The smaller rows under a line: its reading first, then its translation.
     *
     * Reading before meaning, because that is the order they are useful in — you sing
     * from the romanization and glance at the translation.
     */
    private fun secondaryRows(
        line: LyricLine,
        metrics: LyricsMetrics,
        maxWidth: Float,
        alignRight: Boolean,
        contentTop: Float,
        showTranslation: Boolean,
        romanization: String?,
    ): List<TextRow> {
        val texts = buildList {
            romanization?.let { add(it) }
            if (showTranslation) line.translated?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (texts.isEmpty()) return emptyList()

        val paint = metrics.secondaryPaint
        val rowHeight = metrics.secondaryFontSizePx * 1.35f
        val out = ArrayList<TextRow>(texts.size)
        for (text in texts) {
            for (rowText in wrap(text, paint, maxWidth)) {
                val width = paint.measureText(rowText)
                out += TextRow(
                    text = rowText,
                    x = if (alignRight) maxWidth - width else 0f,
                    baseline = contentTop + out.size * rowHeight + rowHeight * 0.8f,
                )
            }
        }
        return out
    }

    // ---- interlude ----------------------------------------------------------

    private fun interludeLayout(
        line: LyricLine,
        index: Int,
        metrics: LyricsMetrics,
        maxWidth: Float,
        alignRight: Boolean,
        top: Float,
    ): LineLayout {
        val paint = metrics.dotPaint
        val dotWidth = paint.measureText(DOT)
        val totalWidth = dotWidth * 3 + metrics.dotGapPx * 2
        val startX = if (alignRight) maxWidth - totalWidth else 0f
        val height = metrics.dotFontSizePx * 1.1f

        // Three dots sharing the gap, each finishing a little early so the last one has
        // time to collapse before the next vocal line arrives.
        val preHide = LyricsDocument.INTERLUDE_PRE_HIDE_MS
        val total = (line.endMs - line.startMs).coerceAtLeast(1)
        val padding = -(preHide + 50) / 3
        val third = total / 3
        val firstEnd = (third + padding).coerceAtLeast(0)
        val secondEnd = (third * 2 + padding * 2).coerceAtLeast(firstEnd)
        val thirdEnd = (total - preHide).coerceAtLeast(secondEnd)

        val bounds = listOf(0 to firstEnd, firstEnd to secondEnd, secondEnd to thirdEnd)
        val units = bounds.mapIndexed { dotIndex, (from, to) ->
            PlacedUnit(
                text = DOT,
                x = startX + dotIndex * (dotWidth + metrics.dotGapPx),
                baseline = height * 0.72f,
                width = dotWidth,
                startMs = line.startMs + from,
                endMs = line.startMs + to,
                gradientTop = 0f,
                gradientHeight = height,
                isDot = true,
            )
        }

        return LineLayout(
            source = line,
            index = index,
            units = units,
            secondaryRows = emptyList(),
            // Dots are only ever drawn by the animated path: when the interlude is not
            // the active line they are collapsed to nothing.
            flatRows = emptyList(),
            contentHeight = height,
            height = height + metrics.lineSpacingPx,
            top = top,
            alignRight = alignRight,
        )
    }

    private const val DOT = "•"

    // ---- shared -------------------------------------------------------------

    /** Greedy word wrap. Falls back to breaking mid-word for scripts without spaces. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        if (paint.measureText(text) <= maxWidth) return listOf(text)

        val out = ArrayList<String>()
        val words = text.split(' ')
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
            }
        }

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current.setLength(0)
                current.append(candidate)
                continue
            }
            flush()
            if (paint.measureText(word) <= maxWidth) {
                current.append(word)
            } else {
                // No space to break at (CJK, or a very long word): break by character.
                var chunk = StringBuilder()
                for (character in word) {
                    if (paint.measureText("$chunk$character") > maxWidth && chunk.isNotEmpty()) {
                        out += chunk.toString()
                        chunk = StringBuilder()
                    }
                    chunk.append(character)
                }
                current.append(chunk)
            }
        }
        flush()
        return out
    }
}
