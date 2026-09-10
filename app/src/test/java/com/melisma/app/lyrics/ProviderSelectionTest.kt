package com.melisma.app.lyrics

import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.provider.LyricsProvider
import com.melisma.app.lyrics.provider.LyricsRequest
import com.melisma.app.settings.CacheServerMode
import com.melisma.app.settings.Settings
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
        // Ranked and enabled, but the developer switch is off: it is a row explaining something the
        // user has not set up, not a source.
        val settings = base.copy(
            cacheServerUrl = "https://lyrics.example",
            providerOrder = listOf("local", "cacheserver", "netease", "lrclib"),
            enabledProviders = base.enabledProviders + "cacheserver",
        )
        assertEquals(listOf("netease", "lrclib"), select(settings))
    }

    @Test
    fun `the cache server does nothing without a URL`() {
        val settings = base.copy(
            developerMode = true,
            cacheServerUrl = null,
            providerOrder = listOf("local", "cacheserver", "netease", "lrclib"),
            enabledProviders = base.enabledProviders + "cacheserver",
        )
        assertEquals(listOf("netease", "lrclib"), select(settings))
    }

    @Test
    fun `in parallel it is ranked like any other source`() {
        // It used to be prepended unconditionally, which made its priority a special case nobody
        // could see or change. It sits in the order now, first by default because an answer already
        // held cost nobody a request — but that is a default, not a rule.
        val settings = base.copy(
            developerMode = true,
            cacheServerUrl = "https://lyrics.example",
            cacheServerMode = CacheServerMode.PARALLEL,
            providerOrder = listOf("local", "cacheserver", "netease", "lrclib"),
            enabledProviders = base.enabledProviders + "cacheserver",
        )
        assertEquals(listOf("cacheserver", "netease", "lrclib"), select(settings))
    }

    @Test
    fun `it can be ranked below the others`() {
        // The point of putting it in the list: somebody who would rather ask the real sources first
        // and fall back to their cache can say so.
        val settings = base.copy(
            developerMode = true,
            cacheServerUrl = "https://lyrics.example",
            providerOrder = listOf("local", "netease", "lrclib", "cacheserver"),
            enabledProviders = base.enabledProviders + "cacheserver",
        )
        assertEquals(listOf("netease", "lrclib", "cacheserver"), select(settings))
    }

    @Test
    fun `it can be switched off from the list like anything else`() {
        val settings = base.copy(
            developerMode = true,
            cacheServerUrl = "https://lyrics.example",
            providerOrder = listOf("local", "cacheserver", "netease", "lrclib"),
            // Present in the order, absent from the enabled set.
            enabledProviders = base.enabledProviders,
        )
        assertEquals(listOf("netease", "lrclib"), select(settings))
    }

    @Test
    fun `only means only, whatever the ranking says`() {
        // Which is why the ranking stops mattering in this mode: nothing else is asked, so there is
        // nothing for an order to order. Ranked last here to prove it.
        val settings = base.copy(
            developerMode = true,
            cacheServerUrl = "https://lyrics.example",
            cacheServerMode = CacheServerMode.ONLY,
            providerOrder = listOf("local", "netease", "lrclib", "cacheserver"),
            enabledProviders = base.enabledProviders + "cacheserver",
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
