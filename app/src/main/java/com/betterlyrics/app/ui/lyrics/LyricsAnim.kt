package com.betterlyrics.app.ui.lyrics

import com.betterlyrics.app.core.Spline
import com.betterlyrics.app.core.Spring
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * The animation curves and spring constants behind the lyric renderer, taken from
 * Spicy Lyrics so the motion reads the same.
 *
 * Two layers: a spline maps "how far through this word are we" to a target value, and
 * a spring chases that target. The spline gives the shape (a word overshoots to 1.05
 * at 70% through, then settles back to 1.0), the spring gives the weight — and because
 * it is a spring rather than a tween, a seek or a dropped frame lands smoothly instead
 * of snapping.
 */
object LyricsAnim {

    enum class State { NOT_SUNG, ACTIVE, SUNG }

    // ---- word / syllable ----------------------------------------------------

    val wordScale = Spline(listOf(0f to 0.95f, 0.7f to 1.0505f, 1f to 1f))
    val wordYOffset = Spline(listOf(0f to 0.01f, 0.9f to -(1f / 60f), 1f to 0f))
    val simpleWordYOffset = Spline(listOf(0f to 0.01f, 1f to -0.033f))

    /** Shared by words, letters and lines: rises fast, holds, fades out. */
    val glow = Spline(listOf(0f to 0f, 0.15f to 1f, 0.6f to 1f, 1f to 0f))

    // ---- emphasised letters -------------------------------------------------

    val letterScale = Spline(listOf(0f to 0.95f, 0.7f to 1.175f, 1f to 1f))
    val simpleLetterScale = Spline(listOf(0f to 0.95f, 0.7f to 1.07f, 1f to 1f))
    val letterYOffset = Spline(listOf(0f to 0.01f, 0.9f to -(1f / 56f), 1f to 0f))
    val simpleLetterYOffset = Spline(listOf(0f to 0.01f, 0.9f to -(1f / 62f), 1f to 0f))

    /** Glow a letter keeps once sung, so a finished word doesn't go flat. */
    const val SUNG_LETTER_GLOW = 0.2f

    /** Multiplies the letter glow into a text-shadow alpha (percent). */
    const val LETTER_GLOW_OPACITY = 185f

    // ---- interlude dots -----------------------------------------------------

    val dotScale = Spline(listOf(0f to 0.75f, 0.7f to 1.05f, 1f to 1f))
    val dotYOffset = Spline(listOf(0f to 0f, 0.9f to -0.12f, 1f to 0f))
    val dotGlow = Spline(listOf(0f to 0f, 0.6f to 1f, 1f to 1f))
    fun dotOpacity(simpleMode: Boolean) = Spline(
        listOf(0f to (if (simpleMode) 0.27f else 0.35f), 0.6f to 1f, 1f to 1f),
    )

    // ---- spring tuning ------------------------------------------------------

    const val SCALE_FREQUENCY = 0.88f
    const val SCALE_DAMPING = 0.64f
    const val Y_OFFSET_FREQUENCY = 1.45f
    const val Y_OFFSET_DAMPING = 0.4f
    const val GLOW_FREQUENCY = 1.18f
    const val GLOW_DAMPING = 0.56f

    const val DOT_SCALE_FREQUENCY = 0.7f
    const val DOT_SCALE_DAMPING = 0.6f
    const val DOT_Y_OFFSET_FREQUENCY = 1.25f
    const val DOT_Y_OFFSET_DAMPING = 0.4f
    const val DOT_GLOW_FREQUENCY = 1f
    const val DOT_GLOW_DAMPING = 0.5f
    const val DOT_OPACITY_FREQUENCY = 1f
    const val DOT_OPACITY_DAMPING = 0.5f

    // ---- line opacity / defocus --------------------------------------------

    /** Opacity of a line by state, before the fill alpha is applied. */
    /**
     * How long a line takes to dim once it has been sung, in milliseconds.
     *
     * Without it the line drops from lit to dim in a single frame the instant its last
     * syllable ends, which reads as a glitch — most obviously across a breathing gap, where
     * nothing else is moving to distract from it. Spicy Lyrics eases the same transition.
     *
     * Driven from the playhead rather than a spring, so it is correct after a scrub and
     * costs no state: a line that is 200 ms past its end is 200 ms into its fade, whatever
     * happened in between.
     */
    const val SUNG_FADE_MS = 420f

    const val OPACITY_ACTIVE = 1f
    const val OPACITY_SUNG = 0.497f
    const val OPACITY_NOT_SUNG = 0.51f
    const val SIMPLE_OPACITY_SUNG = 0.35f
    const val SIMPLE_OPACITY_NOT_SUNG = 0.45f

    /** Alpha of the bright end of the fill gradient, and of a fully sung line. */
    const val FILL_ALPHA_SUNG = 0.85f

    /** Alpha of the dim end — an unsung line's brightness. */
    const val FILL_ALPHA_UNSUNG = 0.35f

    const val SIMPLE_FILL_ALPHA_SUNG = 1f
    const val SIMPLE_FILL_ALPHA_UNSUNG = 0.3f

    /** How much a line is defocused per line of distance from the active one, in px per em. */
    const val BLUR_PER_LINE_EM = 1.25f / 40f

    /** Ceiling for that defocus, also in px per em. */
    const val BLUR_MAX_EM = (1.25f * 5f + 1.25f * 0.465f) / 40f

    /** A line-synced line grows slightly while it is the active one. */
    const val LINE_ACTIVE_SCALE = 1.05f

    // ---- minimal mode -------------------------------------------------------
    // Spicy Lyrics' Minimal mode: a sung line does not just dim, it shrinks and goes.

    const val MINIMAL_SUNG_SCALE = 0.95f
    const val MINIMAL_NOT_SUNG_SCALE = 0.965f
    const val MINIMAL_NOT_SUNG_OPACITY = 0.5f

    /** Fill gradient softness: the bright-to-dim band spans this fraction of the box. */
    const val GRADIENT_BAND = 0.2f

    // ---- helpers ------------------------------------------------------------

    fun stateOf(positionMs: Int, startMs: Int, endMs: Int): State = when {
        positionMs < startMs -> State.NOT_SUNG
        positionMs > endMs -> State.SUNG
        else -> State.ACTIVE
    }

    fun progressOf(positionMs: Int, startMs: Int, endMs: Int): Float {
        val span = (endMs - startMs).toFloat()
        if (span <= 0f) return if (positionMs >= endMs) 1f else 0f
        return ((positionMs - startMs) / span).coerceIn(0f, 1f)
    }

    /** d3's `easeSinOut`, used to shape the fill sweep inside an emphasised letter. */
    fun easeSinOut(t: Float): Float = sin(t.coerceIn(0f, 1f) * (Math.PI.toFloat() / 2f))

    /**
     * How strongly a letter [distance] away from the one being sung should join in.
     * Steep on purpose: it is what makes a single letter stand out inside a long
     * held-out word instead of the whole word swelling as one.
     */
    fun letterFalloff(distance: Int): Float =
        (1f / (1f + abs(distance).toFloat().pow(2.8f))).coerceAtLeast(0f)

    fun letterGlowFalloff(distance: Int): Float =
        (1f / (1f + abs(distance) * 0.9f)).coerceAtLeast(0f)
}

/** Springs for one animated thing — a word, a letter, or a dot. */
class UnitSprings(
    scaleStart: Float,
    yOffsetStart: Float,
    glowStart: Float,
    opacityStart: Float? = null,
    dot: Boolean = false,
) {
    val scale = Spring(
        scaleStart,
        if (dot) LyricsAnim.DOT_SCALE_FREQUENCY else LyricsAnim.SCALE_FREQUENCY,
        if (dot) LyricsAnim.DOT_SCALE_DAMPING else LyricsAnim.SCALE_DAMPING,
    )
    val yOffset = Spring(
        yOffsetStart,
        if (dot) LyricsAnim.DOT_Y_OFFSET_FREQUENCY else LyricsAnim.Y_OFFSET_FREQUENCY,
        if (dot) LyricsAnim.DOT_Y_OFFSET_DAMPING else LyricsAnim.Y_OFFSET_DAMPING,
    )
    val glow = Spring(
        glowStart,
        if (dot) LyricsAnim.DOT_GLOW_FREQUENCY else LyricsAnim.GLOW_FREQUENCY,
        if (dot) LyricsAnim.DOT_GLOW_DAMPING else LyricsAnim.GLOW_DAMPING,
    )
    val opacity: Spring? = opacityStart?.let {
        Spring(it, LyricsAnim.DOT_OPACITY_FREQUENCY, LyricsAnim.DOT_OPACITY_DAMPING)
    }

    /** Fill sweep position, in the same -20…100 percent space the CSS used. */
    var gradient: Float = -20f
}
