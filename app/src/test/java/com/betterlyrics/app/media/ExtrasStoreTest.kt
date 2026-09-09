package com.betterlyrics.app.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tempo and artwork addresses outlive the tokens that found them.
 *
 * They were held in memory only, so every play re-fetched the lot — and a play without a working
 * token got no beat at all, for a track whose tempo the app had already been told. The tempo is the
 * sharp end of it: it comes from Spotify's audio analysis, which needs a live token and which Spotify
 * has closed to new applications, so one not written down may be gone for good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtrasStoreTest {

    private lateinit var context: Context
    private lateinit var store: ExtrasStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ExtrasStore(context)
        runBlocking { store.clear() }
    }

    @Test
    fun `what was learned survives a new store, which is a new launch`() = runBlocking {
        store.put("track", tempo = 171f, coverUrl = "https://i.scdn.co/image/big")

        val reopened = ExtrasStore(context)
        val known = reopened.get("track")
        assertEquals(171f, known?.tempo)
        assertEquals("https://i.scdn.co/image/big", known?.coverUrl)
    }

    @Test
    fun `a later source without a tempo does not erase one`() = runBlocking {
        // Spotify is the only source with a tempo; Apple and the cache server report artwork. A
        // whole-record write would have wiped the tempo the moment Apple answered.
        store.put("track", tempo = 171f)
        store.put("track", coverUrl = "https://is1-ssl.mzstatic.com/image/big")

        val known = store.get("track")
        assertEquals(171f, known?.tempo)
        assertEquals("https://is1-ssl.mzstatic.com/image/big", known?.coverUrl)
    }

    @Test
    fun `a better address replaces a worse one`() = runBlocking {
        store.put("track", coverUrl = "https://small")
        store.put("track", coverUrl = "https://large")

        // Unlike an ISRC, where a disagreement means somebody matched the wrong recording, a second
        // cover address is simply a second cover — and the newest source is the one that just ran.
        assertEquals("https://large", store.get("track")?.coverUrl)
    }

    @Test
    fun `nothing learned is nothing stored`() = runBlocking {
        store.put("track")
        assertNull(store.get("track"))
        assertEquals(0L, store.sizeBytes())
    }

    @Test
    fun `blank addresses are not mistaken for answers`() = runBlocking {
        store.put("track", tempo = 90f, coverUrl = "  ")
        assertNull(store.get("track")?.coverUrl)
        assertEquals(90f, store.get("track")?.tempo)
    }

    @Test
    fun `an unreadable file is not a crash`() = runBlocking {
        java.io.File(context.filesDir, "extras-index.json").writeText("{ not json")
        // A corrupt index must cost the memory of some tempos, not the app.
        assertNull(ExtrasStore(context).get("track"))
    }
}
