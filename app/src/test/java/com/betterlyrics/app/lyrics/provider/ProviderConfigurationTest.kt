package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.settings.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A provider that needs a token must stay quiet until it has one.
 *
 * This is the difference between "you have not set this up yet" and "the lyrics lookup is
 * broken": an unconfigured provider that fires anyway wastes a request per track and
 * reports a failure the user cannot act on.
 */
class ProviderConfigurationTest {

    private class FakeCredentials(
        override var spDcCookie: String? = null,
        override val spotifyWebToken: String? = null,
        override val cacheServerUrl: String? = null,
        override val cacheServerKey: String? = null,
        override val appleDeveloperToken: String? = null,
        override val appleMusicUserToken: String? = null,
    ) : ProviderCredentials {
        override val lrcLibBaseUrl = Settings.DEFAULT_LRCLIB_URL
        override val neteaseBaseUrl = Settings.DEFAULT_NETEASE_URL
        override val amllBaseUrl = Settings.DEFAULT_AMLL_URL
        override val neteaseCookie: String? = null
        override val musixmatchUserToken: String? = null
        override val appleStorefront = "us"
        override var musixmatchGuestToken: String? = null
        override var cachedSpotifyToken: String? = null
        override var cachedSpotifyTokenExpiresAt = 0L
    }

    @Test
    fun `spotify is unavailable whatever cookie you have`() {
        // Spotify closed the token endpoint to third parties, so the provider must report
        // itself unusable rather than being asked once per track and failing quietly. If
        // this test starts failing because the flag was flipped back, the cookie check
        // below is what should be asserted instead.
        assertFalse(SpotifyLyricsProvider(FakeCredentials()).isConfigured)
        assertFalse(SpotifyLyricsProvider(FakeCredentials(spDcCookie = "abc")).isConfigured)
        assertTrue(
            SpotifyLyricsProvider(FakeCredentials(spDcCookie = "abc")).unavailableReason != null,
        )
    }

    @Test
    fun `apple music needs both tokens, not just one`() {
        assertFalse(AppleMusicProvider(FakeCredentials()).isConfigured)
        assertFalse(
            AppleMusicProvider(FakeCredentials(appleDeveloperToken = "jwt")).isConfigured,
        )
        assertFalse(
            AppleMusicProvider(FakeCredentials(appleMusicUserToken = "user")).isConfigured,
        )
        assertTrue(
            AppleMusicProvider(
                FakeCredentials(appleDeveloperToken = "jwt", appleMusicUserToken = "user"),
            ).isConfigured,
        )
    }

    @Test
    fun `the cache server needs a URL and nothing else`() {
        assertFalse(CacheServerProvider(FakeCredentials()).isConfigured)
        assertFalse(CacheServerProvider(FakeCredentials(cacheServerUrl = "  ")).isConfigured)
        assertTrue(
            CacheServerProvider(FakeCredentials(cacheServerUrl = "https://x.example")).isConfigured,
        )
    }

    @Test
    fun `the key-less providers are always ready`() {
        val credentials = FakeCredentials()
        assertTrue(LrcLibProvider(credentials).isConfigured)
        assertTrue(NeteaseProvider(credentials).isConfigured)
        // Musixmatch can mint an anonymous token for itself, so it needs nothing either.
        assertTrue(MusixmatchProvider(credentials).isConfigured)
        // The community database is public and needs no account at all.
        assertTrue(AmllTtmlProvider(credentials).isConfigured)
    }

    @Test
    fun `the word-synced providers are the ones that advertise it`() {
        val credentials = FakeCredentials()
        assertTrue(AppleMusicProvider(credentials).canBeWordSynced)
        assertTrue(NeteaseProvider(credentials).canBeWordSynced)
        assertTrue(MusixmatchProvider(credentials).canBeWordSynced)
        assertTrue(AmllTtmlProvider(credentials).canBeWordSynced)
        // Spotify's colour-lyrics endpoint is line-synced only.
        assertFalse(SpotifyLyricsProvider(credentials).canBeWordSynced)
        assertFalse(LrcLibProvider(credentials).canBeWordSynced)
    }

    @Test
    fun `every provider in the default order has an implementation id`() {
        val credentials = FakeCredentials()
        val implemented = listOf(
            AppleMusicProvider(credentials).id,
            AmllTtmlProvider(credentials).id,
            SpotifyLyricsProvider(credentials).id,
            NeteaseProvider(credentials).id,
            MusixmatchProvider(credentials).id,
            LrcLibProvider(credentials).id,
            "local",
        )
        // Guards against adding a provider to the settings order and forgetting to build
        // it — the row would render and quietly never be queried.
        assertTrue(
            "unimplemented: ${Settings.DEFAULT_PROVIDER_ORDER - implemented.toSet()}",
            Settings.DEFAULT_PROVIDER_ORDER.all { it in implemented },
        )
    }

    @Test
    fun `a provider enabled by default must be skippable without its token`() {
        // Being on by default is fine for a provider that needs a credential, as long as it
        // says so and is skipped rather than queried. What is not fine is being on and
        // failing once per track — that is indistinguishable from the app being broken.
        val credentials = FakeCredentials()
        val implementations = listOf(
            AppleMusicProvider(credentials),
            SpotifyLyricsProvider(credentials),
            AmllTtmlProvider(credentials),
            NeteaseProvider(credentials),
            MusixmatchProvider(credentials),
            LrcLibProvider(credentials),
        )
        for (provider in implementations) {
            if (provider.id !in Settings.DEFAULT_ENABLED_PROVIDERS) continue
            assertTrue(
                "${provider.id} is on by default but neither configured nor explained",
                provider.isConfigured || provider.unavailableReason != null,
            )
        }
    }

    @Test
    fun `spotify is on by default so that pasting a token is enough`() {
        // It was off, which meant a token changed nothing until the user also found the
        // provider list — and nothing said so.
        assertTrue("spotify" in Settings.DEFAULT_ENABLED_PROVIDERS)
    }
}
