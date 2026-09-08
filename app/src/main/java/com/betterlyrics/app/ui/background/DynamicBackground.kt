package com.betterlyrics.app.ui.background

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import com.betterlyrics.app.settings.BackgroundStyle
import kotlin.math.cos
import kotlin.math.sin

/** ~30 fps. See the comment where it is used. */
private const val BACKGROUND_FRAME_INTERVAL_NANOS = 33_000_000L

/**
 * The living background.
 *
 * Spicy Lyrics runs the cover art through a WebGL domain-warp shader; the same result
 * comes out of something much cheaper here. The cover is reduced to a handful of
 * pixels — at which point it *is* a colour field, not a picture — and three copies of
 * that field are drifted, rotated and scaled past each other on very long periods. The
 * bilinear upscale supplies the blur for free, and saturation is pushed hard while
 * brightness is pulled down, matching the `saturate(2.5) brightness(0.65)` the
 * stylesheet applies.
 *
 * Doing it this way needs no shader support, so it looks identical on every device the
 * app runs on.
 */
@Composable
fun DynamicBackground(
    artwork: Bitmap?,
    colors: ArtworkColors,
    style: BackgroundStyle,
    modifier: Modifier = Modifier,
    /** Blur radius for the still styles, 0–67 px. */
    blurRadius: Int = 24,
    /** Auto resolves to a still background here — a floating window, or battery saver. */
    preferStill: Boolean = false,
    /** The artist's image, when Spotify has given us one. */
    artistImage: Bitmap? = null,
    /**
     * Song tempo in BPM, when known.
     *
     * Paces the drift: a ballad's background should not churn at the same rate as a
     * dance track's. Clamped hard, because the point is a hint of the song's energy, not
     * a strobe.
     */
    tempoBpm: Float? = null,
) {
    val resolved = when (style) {
        BackgroundStyle.AUTO ->
            if (preferStill) BackgroundStyle.COVER_ART else BackgroundStyle.ANIMATED

        // Without a Spotify cookie there is no artist image to show, so fall back rather
        // than render an empty background.
        BackgroundStyle.ARTIST_HEADER ->
            if (artistImage != null) BackgroundStyle.ARTIST_HEADER else BackgroundStyle.COVER_ART

        else -> style
    }

    // A background that never changes is worth caching: promoted to its own render node,
    // the GPU keeps the rasterised result and re-blits it instead of re-drawing the
    // gradients and the upscaled artwork on every lyric frame.
    val staticModifier = modifier.fillMaxSize().graphicsLayer()

    if (resolved == BackgroundStyle.BLACK) {
        Canvas(staticModifier) { drawRect(Color.Black) }
        return
    }

    if (resolved == BackgroundStyle.COVER_ART || resolved == BackgroundStyle.ARTIST_HEADER) {
        // The real image, softened. How soft is the user's call, and the blur is done by
        // downscaling before the upscale rather than by a shader, so it costs nothing and
        // works on every API level.
        val source = if (resolved == BackgroundStyle.ARTIST_HEADER) artistImage else artwork
        StillCoverBackground(source, colors, blurRadius, staticModifier)
        return
    }

    if (resolved == BackgroundStyle.COLOR) {
        // Two flat washes and a floor gradient: the same construction as the
        // stylesheet's colour background, which uses a high-contrast panel over a base.
        Canvas(staticModifier) {
            drawRect(colors.base)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to colors.darkVibrant.copy(alpha = 0.85f),
                    1f to Color.Transparent,
                ),
            )
            drawFloorShade()
        }
        return
    }

    val field = remember(artwork) { artwork?.toColourField() }
    val animated = resolved == BackgroundStyle.ANIMATED

    // Cross-fade the new cover in, matching the 850 ms cover transition.
    //
    // Driven from zero on every change rather than from a null check: once the first cover
    // was in place `current` stayed non-null, so the target stayed at 1 and no later track
    // ever faded — it simply cut. An Animatable is snapped to 0 and run up to 1 each time
    // the artwork changes, which is what the previous layer is drawn against.
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    var current by remember { mutableStateOf<Bitmap?>(null) }
    val fadeAnim = remember { Animatable(0f) }
    LaunchedEffect(field) {
        if (current === field) return@LaunchedEffect
        previous = current
        current = field
        if (field == null) {
            // Nothing to fade to; let the old one go rather than holding it at full strength.
            fadeAnim.snapTo(0f)
            previous = null
            return@LaunchedEffect
        }
        fadeAnim.snapTo(0f)
        fadeAnim.animateTo(1f, tween(durationMillis = 850, easing = LinearEasing))
        // The outgoing cover is only needed while it is still visible.
        previous = null
    }
    val fade = fadeAnim.value

    // 120 BPM is the neutral point; the clamp keeps a very slow or very fast song from
    // making the background either static or frantic.
    val driftSpeed = ((tempoBpm ?: 120f) / 120f).coerceIn(0.65f, 1.7f)

    var timeSeconds by remember { mutableStateOf(0f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        var start = 0L
        var lastPublished = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (start == 0L) start = nanos
                // The layers drift on 30–70 second orbits, so publishing a new time every
                // frame would redraw three full-screen textured layers for a change nobody
                // can see. A third of the frames is indistinguishable and costs a third as
                // much — and on this screen the background is the expensive part, not the
                // lyrics.
                if (nanos - lastPublished >= BACKGROUND_FRAME_INTERVAL_NANOS) {
                    lastPublished = nanos
                    timeSeconds = (nanos - start) / 1_000_000_000f
                }
            }
        }
    }

    val paint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(2.35f)
                    // Multiply everything down: the lyrics need the headroom.
                    postConcat(ColorMatrix(floatArrayOf(
                        0.62f, 0f, 0f, 0f, 0f,
                        0f, 0.62f, 0f, 0f, 0f,
                        0f, 0f, 0.62f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )))
                },
            )
        }
    }
    val matrix = remember { Matrix() }

    Canvas(modifier.fillMaxSize()) {
        drawRect(colors.base)

        val bitmap = current
        if (bitmap != null) {
            drawIntoCanvas { canvas ->
                val previousBitmap = previous
                if (previousBitmap != null && fade < 1f) {
                    paint.alpha = ((1f - fade) * 255).toInt()
                    drawField(canvas.nativeCanvas, previousBitmap, matrix, paint, timeSeconds * driftSpeed)
                }
                paint.alpha = (fade.coerceIn(0f, 1f) * 255).toInt()
                drawField(canvas.nativeCanvas, bitmap, matrix, paint, timeSeconds * driftSpeed)
                paint.alpha = 255
            }
        }

        drawFloorShade()
    }
}

/**
 * Album art held still and blurred — Spicy Lyrics' "Cover Art" static background.
 *
 * The blur comes from how far the bitmap is reduced before it is scaled back up, so a
 * radius of 0 leaves the artwork nearly sharp and 67 leaves a colour wash.
 */
@Composable
private fun StillCoverBackground(
    artwork: Bitmap?,
    colors: ArtworkColors,
    blurRadius: Int,
    modifier: Modifier,
) {
    val reduced = remember(artwork, blurRadius) { artwork?.reduced(blurRadius) }
    // Already promoted by the caller; `modifier` arrives with fillMaxSize applied.
    val paint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(1.7f)
                    postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                0.55f, 0f, 0f, 0f, 0f,
                                0f, 0.55f, 0f, 0f, 0f,
                                0f, 0f, 0.55f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f,
                            ),
                        ),
                    )
                },
            )
        }
    }
    val matrix = remember { Matrix() }

    Canvas(modifier) {
        drawRect(colors.base)
        if (reduced != null) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                // Over-scale slightly so the softened edges never show.
                val scale = 1.15f * maxOf(
                    native.width.toFloat() / reduced.width,
                    native.height.toFloat() / reduced.height,
                )
                matrix.reset()
                matrix.postScale(scale, scale)
                matrix.postTranslate(
                    native.width / 2f - reduced.width * scale / 2f,
                    native.height / 2f - reduced.height * scale / 2f,
                )
                native.drawBitmap(reduced, matrix, paint)
            }
        }
        drawFloorShade()
    }
}

/** Downscale to the size a given blur radius implies. Bilinear upscaling does the rest. */
private fun Bitmap.reduced(blurRadius: Int): Bitmap? = runCatching {
    val target = (320f / (1f + blurRadius / 4f)).toInt().coerceIn(6, 320)
    val aspect = height.toFloat() / width
    Bitmap.createScaledBitmap(this, target, (target * aspect).toInt().coerceAtLeast(1), true)
}.getOrNull()

/**
 * Three copies of the colour field, each on its own slow orbit.
 *
 * The periods are long (30–70 s) and deliberately not multiples of one another, so the
 * composition never visibly loops while a song plays.
 */
private fun drawField(
    canvas: android.graphics.Canvas,
    bitmap: Bitmap,
    matrix: Matrix,
    paint: Paint,
    time: Float,
) {
    val width = canvas.width.toFloat()
    val height = canvas.height.toFloat()
    if (width <= 0f || height <= 0f) return

    val layers = 3
    val baseAlpha = paint.alpha

    for (layer in 0 until layers) {
        val phase = layer * 2.1f
        val rotationPeriod = 47f + layer * 11f
        val driftPeriod = 31f + layer * 9f

        // Over-scale so the drifting copies never expose an edge.
        val scale = (2.6f + 0.35f * sin(time / 17f + phase)) *
            maxOf(width / bitmap.width, height / bitmap.height)

        val angle = 360f * ((time / rotationPeriod + layer * 0.33f) % 1f)
        val driftX = 0.12f * width * sin(time / driftPeriod + phase)
        val driftY = 0.12f * height * cos(time / (driftPeriod * 1.3f) + phase)

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            width / 2f - bitmap.width * scale / 2f + driftX,
            height / 2f - bitmap.height * scale / 2f + driftY,
        )
        matrix.postRotate(angle, width / 2f, height / 2f)

        paint.alpha = (baseAlpha * if (layer == 0) 1f else 0.55f).toInt()
        canvas.drawBitmap(bitmap, matrix, paint)
    }
    paint.alpha = baseAlpha
}

/**
 * The dark floor that keeps the now-playing bar legible over any artwork, plus a lighter
 * wash at the top for the controls.
 *
 * Both are declared without explicit coordinates so Compose resolves them against the
 * draw size — which means they can be built once and reused on every frame rather than
 * allocating two full-screen gradients per frame.
 */
private val FLOOR_SHADE = Brush.verticalGradient(
    0.45f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.55f),
)

private val CEILING_SHADE = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.3f),
    0.28f to Color.Transparent,
)

private fun DrawScope.drawFloorShade() {
    drawRect(brush = CEILING_SHADE)
    drawRect(brush = FLOOR_SHADE)
}

/**
 * Reduce the cover to a colour field.
 *
 * Two steps down and one back up: scaling to 5 px throws away every detail, and the
 * halfway stop keeps the result from collapsing to a single average colour. Whatever
 * comes out is a smooth blend of the record's palette, which is all the background is.
 */
private fun Bitmap.toColourField(size: Int = 10): Bitmap? = runCatching {
    val stage = Bitmap.createScaledBitmap(this, size * 3, size * 3, true)
    val tiny = Bitmap.createScaledBitmap(stage, size / 2, size / 2, true)
    val field = Bitmap.createScaledBitmap(tiny, size, size, true)
    if (stage !== field) stage.recycle()
    if (tiny !== field) tiny.recycle()
    field
}.getOrNull()
