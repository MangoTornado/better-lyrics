package com.betterlyrics.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * Decode cover art at a size a phone can actually show.
 *
 * Every one of these sources returns something large — Apple is asked for 1200 square, Spotify's
 * widest is 640, the Cover Art Archive serves 1200 — and a 1200-square bitmap is 5.8 MB decoded,
 * whatever the JPEG weighed on the wire. Several of those in several caches is tens of megabytes of
 * heap for images being drawn behind blurred text, which costs a phone twice: once in memory
 * pressure, and again in the garbage collection that pressure causes.
 *
 * So decode to roughly what will be drawn. `inSampleSize` only halves, so the result is never
 * smaller than asked for and the quality loss is nothing next to the blur it goes under.
 */
object ArtworkDecoding {

    /**
     * The longest edge worth keeping.
     *
     * A full-screen background on a tall phone is around 1100 px wide; the cover in Cinema view is
     * smaller than that. 1024 covers both with room to spare, and quarters the memory of a 2048
     * source.
     */
    const val MAX_EDGE = 1024

    fun decode(bytes: ByteArray, maxEdge: Int = MAX_EDGE): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            },
        )
    }.getOrNull()

    /**
     * The stream variant, for artwork a player published as a URI.
     *
     * Read into memory first: measuring and then decoding needs two passes, and a content stream
     * cannot be rewound. The bytes are a few hundred kilobytes, unlike the bitmap they become.
     */
    fun decode(stream: InputStream, maxEdge: Int = MAX_EDGE): Bitmap? =
        runCatching { decode(stream.readBytes(), maxEdge) }.getOrNull()

    private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        // Halve until one more halving would take it under the target — never below it.
        while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }
}
