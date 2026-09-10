package com.melisma.app.ui.background

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlin.math.max
import kotlin.math.min

/**
 * The handful of colours the background and accents are built from.
 *
 * Deliberately biased dark: the lyrics are white text with a soft glow, so the
 * background has to stay well below them in luminance or the whole thing turns to mush.
 */
data class ArtworkColors(
    val base: Color,
    val vibrant: Color,
    val darkVibrant: Color,
    val muted: Color,
    val accent: Color,
) {
    companion object {
        val Default = ArtworkColors(
            base = Color(0xFF121212),
            vibrant = Color(0xFF4A3B55),
            darkVibrant = Color(0xFF1E1622),
            muted = Color(0xFF2A2630),
            accent = Color(0xFF8E7BA8),
        )

        fun from(bitmap: Bitmap?): ArtworkColors {
            if (bitmap == null || bitmap.width < 2 || bitmap.height < 2) return Default

            val palette = runCatching {
                Palette.from(bitmap).clearFilters().maximumColorCount(20).generate()
            }.getOrNull() ?: return Default

            val vibrant = palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: return Default
            val darkVibrant = palette.darkVibrantSwatch?.rgb
                ?: palette.darkMutedSwatch?.rgb
                ?: vibrant
            val muted = palette.mutedSwatch?.rgb ?: palette.darkMutedSwatch?.rgb ?: darkVibrant
            val dominant = palette.dominantSwatch?.rgb ?: vibrant

            return ArtworkColors(
                base = Color(dominant).darken(0.72f),
                vibrant = Color(vibrant),
                darkVibrant = Color(darkVibrant).darken(0.35f),
                muted = Color(muted).darken(0.25f),
                accent = Color(vibrant).ensureReadable(),
            )
        }
    }
}

private fun Color.darken(amount: Float): Color =
    Color(
        red = red * (1f - amount),
        green = green * (1f - amount),
        blue = blue * (1f - amount),
        alpha = alpha,
    )

/** Lifts a colour until it reads against a near-black background. */
private fun Color.ensureReadable(): Color {
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    if (luminance >= 0.45f) return this
    val factor = min(2.4f, 0.45f / max(luminance, 0.04f))
    return Color(
        red = (red * factor).coerceAtMost(1f),
        green = (green * factor).coerceAtMost(1f),
        blue = (blue * factor).coerceAtMost(1f),
        alpha = alpha,
    )
}
