package com.betterlyrics.app.media

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cover art is decoded to something a phone can show, not to whatever the source sent.
 *
 * The cost being avoided is easy to underestimate: a 1200-square bitmap is 5.8 MB decoded whatever
 * the JPEG weighed, and there are four separate caches of these. Getting it wrong does not fail
 * visibly — it shows up as memory pressure, garbage collection, and a warm phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtworkDecodingTest {

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    @Test
    fun `an oversized cover is brought down`() {
        val decoded = ArtworkDecoding.decode(jpeg(2048, 2048), maxEdge = 512)
        assertNotNull(decoded)
        // Halving only, so it lands on a power-of-two fraction rather than exactly 512.
        assertTrue("got ${decoded!!.width}", decoded.width <= 1024)
        assertTrue("got ${decoded.width}", decoded.width >= 512)
    }

    @Test
    fun `it is never decoded smaller than asked for`() {
        // The whole point is to save memory without softening what is drawn, so the result has to
        // stay at or above the target rather than falling under it.
        val decoded = ArtworkDecoding.decode(jpeg(1500, 1500), maxEdge = 1000)
        assertNotNull(decoded)
        assertTrue("got ${decoded!!.width}", decoded.width >= 1000)
    }

    @Test
    fun `something already small is left alone`() {
        val decoded = ArtworkDecoding.decode(jpeg(300, 300), maxEdge = 1024)
        assertEquals(300, decoded?.width)
    }

    @Test
    fun `a non-square cover is measured on its longest edge`() {
        val decoded = ArtworkDecoding.decode(jpeg(2000, 500), maxEdge = 500)
        assertNotNull(decoded)
        assertTrue("got ${decoded!!.width}x${decoded.height}", decoded.width <= 1000)
    }

    @Test
    fun `rubbish does not throw`() {
        // These bytes come off the network, from an endpoint that may be serving an error page
        // rather than an image. Real Android returns null for them; Robolectric's shadow
        // BitmapFactory hands back a stub whatever it is given, so what can be asserted here is
        // that nothing escapes — the caller is written to treat either as "no cover".
        ArtworkDecoding.decode(byteArrayOf(1, 2, 3, 4))
        ArtworkDecoding.decode(ByteArray(0))
    }

    @Test
    fun `a stream is read once and measured before decoding`() {
        // Two passes are needed and a content stream cannot be rewound, which is why the bytes are
        // pulled into memory first — a few hundred kilobytes against the megabytes they become.
        val bytes = jpeg(1200, 1200)
        val decoded = ArtworkDecoding.decode(bytes.inputStream(), maxEdge = 300)
        assertNotNull(decoded)
        assertTrue("got ${decoded!!.width}", decoded.width <= 600)
    }
}
