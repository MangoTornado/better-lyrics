package com.betterlyrics.app.lyrics

import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.provider.LyricsProvider
import com.betterlyrics.app.lyrics.provider.LyricsRequest
import com.betterlyrics.app.settings.CacheServerMode
import com.betterlyrics.app.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which sources a given settings object will actually hit.
 *
 * Easy to get subtly wrong and impossible to notice from the outside: a provider silently
 * dropped looks exactly like a track with no lyrics, and a cache server silently still in
 * use looks exactly like one that is working.
 */
class ProviderSelectionTest {

    private class Fake(
        override val id: String,
        override val isConfigured: Boolean = true,
    ) : LyricsProvider {
        override val displayName = id
        override suspend fun fetch(request: LyricsRequest): LyricsDocument? = null
    }

    private val local = Fake("local")
    private val cacheServer = Fake("cacheserver")
    private val netease = Fake("netease")
    private val lrclib = Fake("lrclib")
    private val spotify = Fake("spotify", isConfigured = false)

    private val all = listOf(local, cacheServer, netease, lrclib, spotify)

    private fun select(settings: Settings) = providersFor(settings, all, local.id).map { it.id }

    private val base = Settings(
        providerOrder = listOf("local", "spotify", "netease", "lrclib"),
        enabledProviders = setOf("local", "spotify", "netease", "lrclib"),
    )

    @Test
    fun `local files are never asked over the network`() {
        // They are answered before anything is asked, and asking twice would be a bug that
        // costs a round trip on every single track.
        assertEquals(listOf("netease", "lrclib"), select(base))
    }

    @Test
    fun `a source without its token is dropped, not queried`() {
        // "spotify" is enabled and ordered, but unconfigured. Leaving it enabled while you
        // go and find the cookie has to cost nothing.
        assertEquals(listOf("netease", "lrclib"), select(base))
    }

    @Test
    fun `a disabled source is not asked`() {
        val settings = base.copy(enabledProviders = setOf("netease"))
        assertEquals(listOf("netease"), select(settings))
    }

    @Test
    fun `the cache server does nothing until the developer options are on`() {
        val settings = base.copy(cacheServerUrl = "https://lyrics.example")
        assertEquals(listOf("netease", "lrclib"), select(settings))
    }

    @Test
    fun `the cache server does nothing without a URL`() {
        val settings = base.copy(developerMode = true, cacheServerUrl = null)
        assertEquals(listOf("netease", "lrclib"), select(settings))
    }

    @Test
    fun `in parallel it goes first and the others still follow`() {
        // First because a tie should be won by the answer that cost nobody a request; the
        // others follow so a gap in the server costs nothing while it is being built.
        val settings = base.copy(
            developerMode = true,
            cacheServerUrl = "https://lyrics.example",
            cacheServerMode = CacheServerMode.PARALLEL,
        )
        assertEquals(listOf("cacheserver", "netease", "lrclib"), select(settings))
    }

    @Test
    fun `only means only`() {
        // The point of the mode: if nothing else can answer, what the server is missing
        // becomes visible instead of being papered over.
        val settings = base.copy(
            developerMode = true,
            cacheServerUrl = "https://lyrics.example",
            cacheServerMode = CacheServerMode.ONLY,
        )
        assertEquals(listOf("cacheserver"), select(settings))
    }

    @Test
    fun `only still needs a configured server`() {
        // Otherwise the mode would silently disable lyrics entirely.
        val settings = base.copy(
            developerMode = true,
            cacheServerUrl = "   ",
            cacheServerMode = CacheServerMode.ONLY,
        )
        assertEquals(listOf("netease", "lrclib"), select(settings))
    }

    @Test
    fun `the user's order is respected`() {
        val settings = base.copy(providerOrder = listOf("lrclib", "netease", "local"))
        assertEquals(listOf("lrclib", "netease"), select(settings))
    }
}
