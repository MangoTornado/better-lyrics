package com.betterlyrics.app.ui.lyrics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import com.betterlyrics.app.core.Spring
import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.settings.TextAnimationStyle
import kotlin.math.abs

/**
 * Draws the lyrics, and owns everything that moves.
 *
 * One canvas for the whole view rather than a composable per line: with word-level
 * timings there are hundreds of independently animating pieces, and stepping them all
 * inside a single draw pass means playback costs no recomposition at all — the frame
 * loop only invalidates the draw phase.
 *
 * The visual language is Spicy Lyrics':
 *
 * - The active line is *filled* by a soft gradient that sweeps down through each
 *   syllable as it is sung, while the syllable lifts, swells past its resting size and
 *   glows.
 * - Every other line is drawn as a **blur of itself** — transparent glyphs plus a
 *   white shadow — with the radius growing the further it is from the active line. That
 *   is the trick that gives the page its depth, and it is why the text is drawn with a
 *   shadow layer rather than a colour.
 * - Instrumental gaps become three dots that breathe in turn.
 * - In Minimal mode a sung line does not just dim, it shrinks and leaves the page.
 */
class LyricsRenderer(
    layout: LyricsLayout,
    var simpleMode: Boolean = false,
    var blurEnabled: Boolean = true,
    var minimalMode: Boolean = false,
    var animationStyle: TextAnimationStyle = TextAnimationStyle.CALCULATE,
) {

    var layout: LyricsLayout = layout
        set(value) {
            if (field !== value) {
                field = value
                activeIndex = -1
                snapNextFrame = true
            }
        }

    /** Current scroll position, in content pixels from the top. */
    var scrollY: Float = 0f
        private set

    var viewportHeight: Float = 0f
    var contentLeftPx: Float = 0f

    /** Set while the user is dragging: auto-scroll stands down and the blur lifts. */
    var userScrolling: Boolean = false
        private set

    /** Line under the finger, for the hover highlight. -1 for none. */
    var pressedIndex: Int = -1

    /** Lines the user has picked out to copy. */
    var selectedIndices: Set<Int> = emptySet()

    /**
     * True while something is still moving of its own accord — a fling, a scroll settling,
     * a highlight fading. The view uses it to decide whether the next frame is worth
     * drawing at all: a paused, settled screen should cost nothing.
     */
    val isSettling: Boolean
        get() = userScrolling ||
            abs(flingVelocity) > FLING_STOP_PX_PER_SEC ||
            !scrollSpring.canSleep() ||
            !pressSpring.canSleep() ||
            pressedIndex >= 0 ||
            System.currentTimeMillis() < awakeUntil ||
            // A pending snap is work that has not happened yet, and it is only ever
            // carried out inside a frame. Without this, asking to jump back to the playing
            // line on a paused screen sets the flag and then never draws the frame that
            // would act on it — the button does nothing at all.
            snapNextFrame

    private val scrollSpring = Spring(0f, SCROLL_FREQUENCY, SCROLL_DAMPING)
    private val pressSpring = Spring(0f, 2.4f, 0.75f)
    private var flingVelocity = 0f
    private var resumeAutoScrollAt = 0L
    private var lastFrameNanos = 0L
    private var lastPositionMs = 0
    private var activeIndex = -1
    private var snapNextFrame = true

    /** Set by [followAfterSeek]: the next big position jump animates rather than snapping. */
    private var ignoreNextSeekJump = false

    /** Keep drawing until this moment even with nothing moving. See [followAfterSeek]. */
    private var awakeUntil = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxRect = RectF()

    /** Where the active line should sit: a little above centre reads better than dead centre. */
    private val focusFraction = 0.42f

    // ---- input --------------------------------------------------------------

    fun onDragStart() {
        userScrolling = true
        flingVelocity = 0f
    }

    fun onDrag(deltaPx: Float) {
        userScrolling = true
        scrollY = (scrollY - deltaPx).coerceIn(minScroll(), maxScroll())
        scrollSpring.snapTo(scrollY)
    }

    /** [velocityPx] is in pixels/second, positive downward, as Compose reports it. */
    fun onDragEnd(velocityPx: Float, resumeAfterMs: Int) {
        flingVelocity = -velocityPx
        resumeAutoScrollAt = System.currentTimeMillis() + resumeAfterMs
    }

    /** Returns the line under [yInView], for tap-to-seek, copy and the hover highlight. */
    fun lineAtViewY(yInView: Float): LineLayout? = layout.lineAt(yInView + scrollY)

    fun jumpToActive() {
        userScrolling = false
        flingVelocity = 0f
        resumeAutoScrollAt = 0L
        snapNextFrame = true
    }

    /**
     * Hand the scroll back to the music, gliding rather than jumping.
     *
     * For tapping a line to seek to it. Following had stopped the moment the finger went
     * down, and would not have resumed until the timeout expired — so the lyrics sat
     * still while the song played on somewhere else. This puts them back in charge
     * immediately, and the one position jump the seek causes is allowed to animate
     * instead of snapping: the user pointed at the line they wanted, and watching the
     * page travel there is the confirmation that it worked.
     */
    fun followAfterSeek() {
        userScrolling = false
        flingVelocity = 0f
        resumeAutoScrollAt = 0L
        snapNextFrame = false
        ignoreNextSeekJump = true
        // A seek is a request to the player, not a local change: the media session reports
        // the new position some time later, and by then a settled screen has stopped
        // drawing. Stay awake long enough to see the answer arrive, or the tap appears to
        // do nothing until the screen is touched again.
        awakeUntil = System.currentTimeMillis() + SEEK_AWAKE_MS
    }

    // ---- frame --------------------------------------------------------------

    fun draw(canvas: Canvas, positionMs: Int, frameNanos: Long) {
        val delta = when {
            lastFrameNanos == 0L -> 1f / 60f
            else -> ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 1f / 15f)
        }
        lastFrameNanos = frameNanos

        // A seek — as opposed to ordinary playback — should land, not glide. Unless the
        // user caused it by tapping a line, in which case the glide is the point.
        if (abs(positionMs - lastPositionMs) > SEEK_THRESHOLD_MS) {
            if (ignoreNextSeekJump) ignoreNextSeekJump = false else snapNextFrame = true
        }
        lastPositionMs = positionMs

        val newActiveIndex = layout.activeIndexAt(positionMs)
        if (newActiveIndex != activeIndex) {
            if (activeIndex >= 0 && abs(newActiveIndex - activeIndex) > 6) snapNextFrame = true
            activeIndex = newActiveIndex
        }

        stepScroll(delta)

        val metrics = layout.metrics
        // Unsynced lyrics have no "current" line, so there is nothing to focus and
        // nothing to push out of focus.
        val defocusEnabled = blurEnabled && !userScrolling && !isStatic

        pressSpring.setGoal(if (pressedIndex >= 0) 1f else 0f)
        val press = pressSpring.step(delta)

        for (line in layout.lines) {
            val y = line.top - scrollY
            if (y + line.height < -metrics.lineHeightPx || y > viewportHeight + metrics.lineHeightPx) {
                continue
            }

            val distance = if (activeIndex < 0) 0 else abs(line.index - activeIndex)
            val isActive = positionMs >= line.startMs && positionMs <= line.endMs &&
                layout.document.kind != LyricsKind.STATIC

            canvas.save()
            canvas.translate(contentLeftPx, y)
            drawHighlightBox(canvas, line, press)
            drawLine(canvas, line, positionMs, delta, isActive, distance, defocusEnabled)
            canvas.restore()
        }

        drawFooter(canvas)
    }

    /**
     * The tinted box behind the line under your finger.
     *
     * Spicy Lyrics shows this on pointer hover; a finger has no hover, so it appears
     * while a line is held — which is also the gesture that copies it.
     */
    private fun drawHighlightBox(canvas: Canvas, line: LineLayout, press: Float) {
        val selected = line.index in selectedIndices
        val pressed = line.index == pressedIndex
        val strength = when {
            selected -> 1f
            pressed -> press
            else -> 0f
        }
        if (strength <= 0.01f || line.isInterlude) return

        val metrics = layout.metrics
        val padX = metrics.fontSizePx * 0.22f
        val padY = metrics.fontSizePx * 0.16f
        val scale = 0.9f + 0.15f * strength

        canvas.save()
        canvas.scale(scale, scale, line.contentWidthCentre(), line.contentHeight / 2f)
        boxRect.set(-padX, -padY, line.rightEdge() + padX, line.contentHeight + padY)
        boxPaint.color = Color.argb(
            ((if (selected) 0.22f else 0.10f) * strength * 255).toInt(),
            255, 255, 255,
        )
        canvas.drawRoundRect(boxRect, padY * 1.6f, padY * 1.6f, boxPaint)
        canvas.restore()
    }

    private fun LineLayout.rightEdge(): Float =
        units.maxOfOrNull { it.x + it.width } ?: 0f

    private fun LineLayout.contentWidthCentre(): Float = rightEdge() / 2f

    private fun stepScroll(delta: Float) {
        val now = System.currentTimeMillis()

        // Nothing to follow in an unsynced document: the scroll belongs to the reader.
        if (isStatic) {
            if (abs(flingVelocity) > FLING_STOP_PX_PER_SEC) {
                scrollY = (scrollY + flingVelocity * delta).coerceIn(minScroll(), maxScroll())
                flingVelocity *= FLING_DECAY_PER_FRAME
            } else {
                flingVelocity = 0f
            }
            scrollSpring.snapTo(scrollY)
            return
        }

        if (userScrolling) {
            if (abs(flingVelocity) > FLING_STOP_PX_PER_SEC) {
                scrollY = (scrollY + flingVelocity * delta).coerceIn(minScroll(), maxScroll())
                scrollSpring.snapTo(scrollY)
                // Exponential decay, tuned to feel like the platform's own fling.
                flingVelocity *= FLING_DECAY_PER_FRAME
                if (scrollY <= minScroll() || scrollY >= maxScroll()) flingVelocity = 0f
            } else {
                flingVelocity = 0f
                if (now >= resumeAutoScrollAt) userScrolling = false
            }
            return
        }

        val target = targetScrollFor(activeIndex)
        if (snapNextFrame) {
            snapNextFrame = false
            scrollY = target
            scrollSpring.snapTo(target)
            return
        }
        scrollSpring.setGoal(target)
        scrollY = scrollSpring.step(delta).coerceIn(minScroll(), maxScroll())
    }

    private fun targetScrollFor(index: Int): Float {
        val line = layout.lines.getOrNull(index) ?: return minScroll()
        val focus = line.top + line.contentHeight / 2f - viewportHeight * focusFraction
        return focus.coerceIn(minScroll(), maxScroll())
    }

    private val isStatic: Boolean
        get() = layout.document.kind == LyricsKind.STATIC

    private fun minScroll(): Float =
        if (isStatic) 0f else -viewportHeight * focusFraction

    private fun maxScroll(): Float =
        (layout.contentHeight - viewportHeight * (1f - focusFraction))
            .coerceAtLeast(minScroll())

    // ---- lines --------------------------------------------------------------

    private fun drawLine(
        canvas: Canvas,
        line: LineLayout,
        positionMs: Int,
        delta: Float,
        isActive: Boolean,
        distance: Int,
        defocusEnabled: Boolean,
    ) {
        val metrics = layout.metrics

        if (line.isInterlude) {
            if (isActive) drawInterlude(canvas, line, positionMs, delta)
            return
        }

        val sung = positionMs > line.endMs
        var opacity = when {
            isStatic -> 1f
            isActive -> LyricsAnim.OPACITY_ACTIVE
            sung -> if (simpleMode) LyricsAnim.SIMPLE_OPACITY_SUNG else LyricsAnim.OPACITY_SUNG
            else -> if (simpleMode) LyricsAnim.SIMPLE_OPACITY_NOT_SUNG else LyricsAnim.OPACITY_NOT_SUNG
        }

        // Line-synced lyrics have no words to animate, so the line itself takes the
        // swell the words would otherwise have had.
        val lineIsWordless = layout.document.kind != LyricsKind.SYLLABLE
        var lineScale = if (lineIsWordless) {
            line.springs.scale.setGoal(if (isActive) LyricsAnim.LINE_ACTIVE_SCALE else 1f)
            line.springs.scale.step(delta)
        } else {
            1f
        }

        if (minimalMode && !isStatic) {
            // Sung lines leave the page entirely; unsung ones sit back and dim.
            line.minimalScale.setGoal(
                when {
                    isActive -> 1f
                    sung -> LyricsAnim.MINIMAL_SUNG_SCALE
                    else -> LyricsAnim.MINIMAL_NOT_SUNG_SCALE
                },
            )
            line.minimalOpacity.setGoal(
                when {
                    isActive -> 1f
                    sung -> 0f
                    else -> LyricsAnim.MINIMAL_NOT_SUNG_OPACITY
                },
            )
            lineScale *= line.minimalScale.step(delta)
            opacity *= line.minimalOpacity.step(delta)
            if (opacity <= 0.01f) return
        }

        val pivotX = if (line.alignRight) line.rightEdge() else 0f
        canvas.save()
        if (lineScale != 1f) canvas.scale(lineScale, lineScale, pivotX, line.contentHeight / 2f)

        val paintForLine = metrics.paintFor(line.role)
        val fontSize = metrics.fontSizeFor(line.role)
        // Backing vocals sit behind the lead even when they are the ones being sung.
        val roleAlpha = if (line.role == LineRole.BACKGROUND) 0.7f else 1f

        if (isActive) {
            for (unit in line.units) {
                drawActiveUnit(
                    canvas, unit, paintForLine, fontSize, positionMs, delta, opacity * roleAlpha,
                )
            }
        } else {
            val fillAlpha = if (sung) {
                if (simpleMode) LyricsAnim.SIMPLE_FILL_ALPHA_SUNG else LyricsAnim.FILL_ALPHA_SUNG
            } else {
                if (simpleMode) LyricsAnim.SIMPLE_FILL_ALPHA_UNSUNG else LyricsAnim.FILL_ALPHA_UNSUNG
            }
            val blur = if (!defocusEnabled || distance == 0) {
                0f
            } else {
                (metrics.blurPerLinePx * distance).coerceAtMost(metrics.blurMaxPx)
            }
            drawFlat(canvas, line, paintForLine, fillAlpha * opacity * roleAlpha, blur)
            // Keep the springs parked so the line doesn't animate from a stale value the
            // next time it becomes active.
            resetLineSprings(line, sung)
        }

        drawRuby(canvas, line, isActive, opacity * roleAlpha)
        drawSecondary(canvas, line, isActive, opacity)
        canvas.restore()
    }

    /**
     * Furigana — the kana gloss over the kanji.
     *
     * Drawn for every line, active or not, and never defocused: a gloss you cannot read is
     * worse than no gloss, so it keeps a flat alpha tied to the line's state instead of
     * joining the depth blur.
     */
    private fun drawRuby(canvas: Canvas, line: LineLayout, isActive: Boolean, opacity: Float) {
        if (line.units.none { it.ruby != null }) return
        val source = layout.metrics.rubyPaint
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = source.textSize
        paint.typeface = source.typeface
        paint.color = whiteWithAlpha(opacity * if (isActive) 0.82f else 0.4f)
        for (unit in line.units) {
            val ruby = unit.ruby ?: continue
            canvas.drawText(ruby, unit.rubyX, unit.rubyBaseline, paint)
        }
    }

    /** The cheap pass: whole rows, one draw each, defocused by [blur]. */
    private fun drawFlat(
        canvas: Canvas,
        line: LineLayout,
        source: Paint,
        alpha: Float,
        blur: Float,
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = source.textSize
        paint.typeface = source.typeface
        paint.isSubpixelText = true

        val color = whiteWithAlpha(alpha)
        if (blur > MIN_BLUR_PX) {
            // Transparent glyphs plus a white shadow: what is left on screen is purely
            // the blur of the text, which is exactly the defocus Spicy Lyrics renders.
            paint.color = Color.TRANSPARENT
            paint.setShadowLayer(blur, 0f, 0f, color)
        } else {
            paint.color = color
            paint.clearShadowLayer()
        }

        for (row in line.flatRows) {
            if (row.text.isEmpty()) continue
            canvas.drawText(row.text, row.x, row.baseline, paint)
        }
        paint.clearShadowLayer()
    }

    private fun resetLineSprings(line: LineLayout, sung: Boolean) {
        val target = if (sung) 1f else 0f
        for (unit in line.units) {
            if (unit.letters != null) {
                for (letter in unit.letters) {
                    letter.springs.scale.snapTo(LyricsAnim.letterScale.at(target))
                    letter.springs.yOffset.snapTo(letterYOffsetSpline().at(target))
                    letter.springs.glow.snapTo(LyricsAnim.glow.at(target))
                }
            }
            unit.springs.scale.snapTo(LyricsAnim.wordScale.at(target))
            unit.springs.yOffset.snapTo(wordYOffsetSpline().at(target))
            unit.springs.glow.snapTo(LyricsAnim.glow.at(target))
            unit.sweepStartedAt = 0L
        }
    }

    // ---- the active line ----------------------------------------------------

    /**
     * How far through a unit the fill has swept.
     *
     * [TextAnimationStyle.CALCULATE] derives it from the reported playhead, which is
     * exact but only as smooth as the player's reporting. [TextAnimationStyle.ANIMATE]
     * starts a steady sweep the moment the unit becomes active and reads it off the wall
     * clock instead — smoother, at the cost of a little drift across a seek.
     */
    private fun sweepProgress(unit: PlacedUnit, positionMs: Int): Float {
        val fromPlayhead = LyricsAnim.progressOf(positionMs, unit.startMs, unit.endMs)
        if (animationStyle == TextAnimationStyle.CALCULATE) return fromPlayhead

        val duration = (unit.endMs - unit.startMs).coerceAtLeast(1)
        if (unit.sweepStartedAt == 0L) {
            // Begin where the playhead already is, so a mid-word seek does not restart it.
            unit.sweepStartedAt = SystemClock.elapsedRealtime() - (fromPlayhead * duration).toLong()
        }
        val elapsed = SystemClock.elapsedRealtime() - unit.sweepStartedAt
        val fromClock = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
        // Never let the steady sweep run away from the truth by more than a quarter of
        // the word; past that, the playhead wins.
        return if (abs(fromClock - fromPlayhead) > 0.25f) fromPlayhead else fromClock
    }

    private fun drawActiveUnit(
        canvas: Canvas,
        unit: PlacedUnit,
        source: Paint,
        fontSize: Float,
        positionMs: Int,
        delta: Float,
        opacity: Float,
    ) {
        val state = LyricsAnim.stateOf(positionMs, unit.startMs, unit.endMs)
        if (state != LyricsAnim.State.ACTIVE) unit.sweepStartedAt = 0L
        val progress = if (state == LyricsAnim.State.ACTIVE) {
            sweepProgress(unit, positionMs)
        } else {
            LyricsAnim.progressOf(positionMs, unit.startMs, unit.endMs)
        }

        val targetIndex = when (state) {
            LyricsAnim.State.ACTIVE -> progress
            LyricsAnim.State.NOT_SUNG -> 0f
            LyricsAnim.State.SUNG -> 1f
        }

        unit.springs.scale.setGoal(LyricsAnim.wordScale.at(targetIndex))
        unit.springs.yOffset.setGoal(wordYOffsetSpline().at(targetIndex))
        unit.springs.glow.setGoal(LyricsAnim.glow.at(targetIndex))

        val scale = unit.springs.scale.step(delta)
        val lift = unit.springs.yOffset.step(delta) * fontSize
        val glow = unit.springs.glow.step(delta)

        val gradient = when (state) {
            LyricsAnim.State.ACTIVE -> GRADIENT_START + 120f * progress
            LyricsAnim.State.NOT_SUNG -> GRADIENT_START
            LyricsAnim.State.SUNG -> 100f
        }

        canvas.save()
        canvas.translate(0f, lift)
        canvas.scale(scale, scale, unit.centerX, unit.baseline - fontSize * 0.32f)

        if (unit.letters != null) {
            drawEmphasisLetters(canvas, unit, source, fontSize, positionMs, delta, opacity, state)
        } else {
            paint.reset()
            paint.isAntiAlias = true
            paint.textSize = source.textSize
            paint.typeface = source.typeface
            paint.isSubpixelText = true
            paint.shader = fillShader(unit, gradient, opacity)
            applyGlow(glow, GLOW_BASE_PX, GLOW_RANGE_PX, WORD_GLOW_OPACITY, opacity, fontSize)
            canvas.drawText(unit.text, unit.x, unit.baseline, paint)
            paint.shader = null
            paint.clearShadowLayer()
        }
        canvas.restore()
    }

    /**
     * A long-held syllable animates letter by letter.
     *
     * Only the letter actually being sung takes the full swell; its neighbours join in
     * with a steep falloff, which is what makes a held note ripple instead of bulging.
     */
    private fun drawEmphasisLetters(
        canvas: Canvas,
        unit: PlacedUnit,
        source: Paint,
        fontSize: Float,
        positionMs: Int,
        delta: Float,
        opacity: Float,
        wordState: LyricsAnim.State,
    ) {
        val letters = unit.letters ?: return
        val activeLetter = letters.indexOfFirst {
            positionMs >= it.startMs && positionMs <= it.endMs
        }

        val scaleSpline = if (simpleMode) LyricsAnim.simpleLetterScale else LyricsAnim.letterScale
        val yOffsetSpline = letterYOffsetSpline()

        val restingScale = scaleSpline.at(0f)
        val restingYOffset = yOffsetSpline.at(0f)
        val restingGlow = LyricsAnim.glow.at(0f)

        val activePercentage = if (activeLetter >= 0) {
            LyricsAnim.progressOf(
                positionMs,
                letters[activeLetter].startMs,
                letters[activeLetter].endMs,
            )
        } else {
            0f
        }

        for ((index, letter) in letters.withIndex()) {
            val letterState = LyricsAnim.stateOf(positionMs, letter.startMs, letter.endMs)

            var targetScale = restingScale
            var targetYOffset = restingYOffset
            var targetGlow = restingGlow

            if (activeLetter >= 0) {
                val falloff = LyricsAnim.letterFalloff(index - activeLetter)
                val glowFalloff = LyricsAnim.letterGlowFalloff(index - activeLetter)
                val baseScale = scaleSpline.at(activePercentage)
                val baseYOffset = yOffsetSpline.at(activePercentage)
                val baseGlow = LyricsAnim.glow.at(activePercentage)

                targetScale = restingScale + (baseScale - restingScale) * falloff
                targetYOffset = restingYOffset + (baseYOffset - restingYOffset) * falloff
                targetGlow = restingGlow + (baseGlow - restingGlow) * glowFalloff
            }

            if (letterState == LyricsAnim.State.NOT_SUNG && !simpleMode) {
                targetScale = restingScale
                targetYOffset = restingYOffset
                targetGlow = restingGlow
            } else if (letterState == LyricsAnim.State.SUNG && activeLetter < 0) {
                targetGlow = LyricsAnim.glow.at(LyricsAnim.SUNG_LETTER_GLOW)
            }

            letter.springs.scale.setGoal(targetScale)
            letter.springs.yOffset.setGoal(targetYOffset)
            letter.springs.glow.setGoal(targetGlow)

            val scale = letter.springs.scale.step(delta)
            // Letters lift twice as far as whole words do, so the emphasis reads.
            val lift = letter.springs.yOffset.step(delta) * fontSize * 2f
            val glow = letter.springs.glow.step(delta)

            val gradient = when {
                letterState == LyricsAnim.State.SUNG -> 100f
                letterState == LyricsAnim.State.NOT_SUNG -> GRADIENT_START
                index == activeLetter ->
                    GRADIENT_START + 120f * LyricsAnim.easeSinOut(activePercentage)

                else -> GRADIENT_START
            }.let { if (wordState == LyricsAnim.State.SUNG) 100f else it }

            canvas.save()
            canvas.translate(0f, lift)
            canvas.scale(scale, scale, letter.centerX, unit.baseline - fontSize * 0.32f)

            paint.reset()
            paint.isAntiAlias = true
            paint.textSize = source.textSize
            paint.typeface = source.typeface
            paint.isSubpixelText = true
            paint.shader = letterShader(unit, gradient, opacity)
            applyGlow(
                glow, GLOW_BASE_PX, LETTER_GLOW_RANGE_PX, LyricsAnim.LETTER_GLOW_OPACITY,
                opacity, fontSize,
            )
            canvas.drawText(letter.text, letter.x, unit.baseline, paint)
            paint.shader = null
            paint.clearShadowLayer()
            canvas.restore()
        }
    }

    private fun drawInterlude(canvas: Canvas, line: LineLayout, positionMs: Int, delta: Float) {
        val metrics = layout.metrics
        val opacitySpline = LyricsAnim.dotOpacity(simpleMode)
        val fontSize = metrics.dotFontSizePx

        for (unit in line.units) {
            val state = LyricsAnim.stateOf(positionMs, unit.startMs, unit.endMs)
            val progress = LyricsAnim.progressOf(positionMs, unit.startMs, unit.endMs)
            val at = when (state) {
                LyricsAnim.State.ACTIVE -> progress
                LyricsAnim.State.NOT_SUNG -> 0f
                LyricsAnim.State.SUNG -> 1f
            }

            unit.springs.scale.setGoal(LyricsAnim.dotScale.at(at))
            unit.springs.yOffset.setGoal(LyricsAnim.dotYOffset.at(at))
            unit.springs.glow.setGoal(LyricsAnim.dotGlow.at(at))
            unit.springs.opacity?.setGoal(opacitySpline.at(at))

            val scale = unit.springs.scale.step(delta)
            val lift = unit.springs.yOffset.step(delta) * fontSize
            val glow = unit.springs.glow.step(delta)
            val alpha = unit.springs.opacity?.step(delta) ?: 1f

            canvas.save()
            canvas.translate(0f, lift)
            // Dots rest at 0.75 of their box, matching the stylesheet.
            canvas.scale(
                scale * 0.75f, scale * 0.75f, unit.centerX, unit.baseline - fontSize * 0.25f,
            )

            paint.reset()
            paint.isAntiAlias = true
            paint.textSize = metrics.dotPaint.textSize
            paint.typeface = metrics.dotPaint.typeface
            paint.color = whiteWithAlpha(alpha)
            applyGlow(glow, GLOW_BASE_PX, DOT_GLOW_RANGE_PX, DOT_GLOW_OPACITY, alpha, fontSize)
            canvas.drawText(unit.text, unit.x, unit.baseline, paint)
            paint.clearShadowLayer()
            canvas.restore()
        }
    }

    private fun drawSecondary(
        canvas: Canvas,
        line: LineLayout,
        isActive: Boolean,
        opacity: Float,
    ) {
        if (line.secondaryRows.isEmpty()) return
        val source = layout.metrics.secondaryPaint
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = source.textSize
        paint.typeface = source.typeface
        paint.color = whiteWithAlpha(opacity * if (isActive) 0.8f else 0.45f)
        for (row in line.secondaryRows) {
            canvas.drawText(row.text, row.x, row.baseline, paint)
        }
    }

    /** Songwriters and the source, after the last line. */
    private fun drawFooter(canvas: Canvas) {
        if (layout.footer.isEmpty()) return
        val metrics = layout.metrics
        for (row in layout.footer) {
            val y = row.baseline - scrollY
            if (y < -metrics.lineHeightPx || y > viewportHeight + metrics.lineHeightPx) continue
            val source = if (row.bold) metrics.creditsPaint else metrics.providerPaint
            paint.reset()
            paint.isAntiAlias = true
            paint.textSize = source.textSize
            paint.typeface = source.typeface
            paint.color = whiteWithAlpha(row.alpha)
            canvas.drawText(row.text, contentLeftPx + row.x, y, paint)
        }
    }

    // ---- paint helpers ------------------------------------------------------

    /**
     * The fill sweep.
     *
     * A vertical gradient whose bright edge starts above the syllable and travels down
     * through it as the syllable is sung — bright behind the edge, dim ahead of it. The
     * band is only [LyricsAnim.GRADIENT_BAND] of the box tall, so it reads as a soft
     * wipe rather than a fade.
     */
    private fun fillShader(unit: PlacedUnit, gradientPercent: Float, opacity: Float): Shader? =
        buildShader(unit.gradientTop, unit.gradientHeight, gradientPercent, opacity)

    private fun letterShader(unit: PlacedUnit, gradientPercent: Float, opacity: Float): Shader? =
        buildShader(unit.gradientTop, unit.gradientHeight, gradientPercent, opacity)

    private fun buildShader(
        top: Float,
        height: Float,
        gradientPercent: Float,
        opacity: Float,
    ): Shader? {
        if (height <= 0f) return null
        val brightAlpha = if (simpleMode) {
            LyricsAnim.SIMPLE_FILL_ALPHA_SUNG
        } else {
            LyricsAnim.FILL_ALPHA_SUNG
        } * opacity
        val dimAlpha = if (simpleMode) {
            LyricsAnim.SIMPLE_FILL_ALPHA_UNSUNG
        } else {
            LyricsAnim.FILL_ALPHA_UNSUNG
        } * opacity

        val fraction = gradientPercent / 100f
        val y0 = top + fraction * height
        val y1 = y0 + LyricsAnim.GRADIENT_BAND * height
        if (y1 - y0 < 0.5f) {
            return null
        }
        return LinearGradient(
            0f, y0, 0f, y1,
            whiteWithAlpha(brightAlpha), whiteWithAlpha(dimAlpha),
            Shader.TileMode.CLAMP,
        )
    }

    private fun applyGlow(
        glow: Float,
        basePx: Float,
        rangePx: Float,
        opacityMultiplier: Float,
        opacity: Float,
        fontSize: Float,
    ) {
        val alpha = ((glow * opacityMultiplier / 100f) * opacity).coerceIn(0f, 1f)
        if (alpha <= 0.01f) {
            paint.clearShadowLayer()
            return
        }
        // Radii come from a 40px reference size, so scale them with the type.
        val radius = (basePx + rangePx * glow) * (fontSize / 40f)
        if (radius <= MIN_BLUR_PX) {
            paint.clearShadowLayer()
            return
        }
        paint.setShadowLayer(radius, 0f, 0f, whiteWithAlpha(alpha))
    }

    private fun wordYOffsetSpline() =
        if (simpleMode) LyricsAnim.simpleWordYOffset else LyricsAnim.wordYOffset

    private fun letterYOffsetSpline() =
        if (simpleMode) LyricsAnim.simpleLetterYOffset else LyricsAnim.letterYOffset

    private fun whiteWithAlpha(alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), 255, 255, 255)

    private companion object {
        /** Where the fill sweep begins, in the CSS percentage space it was authored in. */
        const val GRADIENT_START = -20f

        const val GLOW_BASE_PX = 4f
        const val GLOW_RANGE_PX = 2f
        const val LETTER_GLOW_RANGE_PX = 12f
        const val DOT_GLOW_RANGE_PX = 6f
        const val WORD_GLOW_OPACITY = 35f
        const val DOT_GLOW_OPACITY = 90f

        const val MIN_BLUR_PX = 0.4f

        const val SCROLL_FREQUENCY = 1.15f
        const val SCROLL_DAMPING = 1f
        const val SEEK_THRESHOLD_MS = 1_500

        /**
         * How long to keep drawing after asking the player to seek.
         *
         * Long enough for a slow player to acknowledge, short enough that a mistaken tap
         * does not hold the screen awake.
         */
        const val SEEK_AWAKE_MS = 1_500L
        const val FLING_DECAY_PER_FRAME = 0.94f
        const val FLING_STOP_PX_PER_SEC = 40f
    }
}
