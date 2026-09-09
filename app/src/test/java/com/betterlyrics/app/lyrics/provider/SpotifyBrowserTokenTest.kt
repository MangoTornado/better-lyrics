package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gate around the WebView harvest, and the token bookkeeping either side of it.
 *
 * The interception itself needs a real WebView loading a real page, so it cannot be tested here —
 * what can, and what is worth pinning, is that a browser never launches unless somebody asked for
 * one, that a harvested token is cached with its real expiry, and that a failure is reported rather
 * than swallowed. Those are the parts that decide whether this feature is quiet or a nuisance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpotifyBrowserTokenTest {

    private class FakeCredentials(
        override var spDcCookie: String? = null,
        override var spotifyWebToken: String? = null,
        override val spotifyBrowserTokenEnabled: Boolean = false,
    ) : ProviderCredentials {
        override val lrcLibBaseUrl = Settings.DEFAULT_LRCLIB_URL
        override val neteaseBaseUrl = Settings.DEFAULT_NETEASE_URL
        override val amllBaseUrl = Settings.DEFAULT_AMLL_URL
        override val neteaseCookie: String? = null
        override val musixmatchUserToken: String? = null
        override val appleDeveloperToken: String? = null
        override val appleMusicUserToken: String? = null
        override val appleStorefront = "us"
        override val cacheServerUrl: String? = null
        override val cacheServerKey: String? = null
        override var musixmatchGuestToken: String? = null
        override var cachedSpotifyToken: String? = null
        override var cachedSpotifyTokenExpiresAt = 0L
    }

    /** Records whether it was asked, so "did a browser launch" is observable. */
    private class RecordingHarvester(
        private val result: SpotifyBrowserToken.Result,
    ) : SpotifyBrowserToken(org.robolectric.RuntimeEnvironment.getApplication()) {
        var calls = 0
            private set

        override suspend fun harvest(spDcCookie: String, timeoutMs: Long): SpotifyBrowserToken.Result {
            calls++
            return result
        }
    }

    @After
    fun tearDown() {
        SpotifyWebToken.harvester = null
    }

    /** A JWT whose `exp` is [secondsFromNow], with the padding a real token has. */
    private fun jwt(secondsFromNow: Long): String {
        val exp = System.currentTimeMillis() / 1000 + secondsFromNow
        val payload = android.util.Base64.encodeToString(
            """{"exp":$exp}""".toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or
                android.util.Base64.NO_WRAP,
        )
        return "eyJhbGciOiJIUzI1NiJ9.$payload.${"s".repeat(120)}"
    }

    @Test
    fun `no browser launches when the switch is off`() {
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(jwt(3600), null),
        )
        SpotifyWebToken.harvester = harvester
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = false)

        assertNull(runBlocking { SpotifyWebToken.get(credentials) })
        // The expensive, visible thing. Off has to mean off.
        assertEquals(0, harvester.calls)
    }

    @Test
    fun `no browser launches without a cookie to use`() {
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(jwt(3600), null),
        )
        SpotifyWebToken.harvester = harvester
        val credentials = FakeCredentials(spDcCookie = null, spotifyBrowserTokenEnabled = true)

        assertNull(runBlocking { SpotifyWebToken.get(credentials) })
        assertEquals(0, harvester.calls)
    }

    @Test
    fun `a harvested token is cached with the expiry it claims`() {
        val token = jwt(3600)
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(token, SpotifyWebToken.expiryOf(token)),
        )
        SpotifyWebToken.harvester = harvester
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)

        assertEquals(token, runBlocking { SpotifyWebToken.get(credentials) })
        assertEquals(1, harvester.calls)
        assertEquals(token, credentials.cachedSpotifyToken)
        // Its own claim, not a guess — so the next launch happens when it actually needs to.
        val expected = SpotifyWebToken.expiryOf(token)!!
        assertEquals(expected, credentials.cachedSpotifyTokenExpiresAt)
    }

    @Test
    fun `the cache means one browser launch, not one per lookup`() {
        val token = jwt(3600)
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(token, SpotifyWebToken.expiryOf(token)),
        )
        SpotifyWebToken.harvester = harvester
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)

        // A token lasts about an hour, so launching a WebView per track would be indefensible.
        repeat(5) { assertEquals(token, runBlocking { SpotifyWebToken.get(credentials) }) }
        assertEquals(1, harvester.calls)
    }

    @Test
    fun `a failure is reported rather than swallowed`() {
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Failed("the cookie has expired"),
        )
        SpotifyWebToken.harvester = harvester
        val credentials = FakeCredentials(spDcCookie = "a-stale-cookie", spotifyBrowserTokenEnabled = true)

        assertNull(runBlocking { SpotifyWebToken.get(credentials) })
        // Settings shows this. Without it, "no lyrics" is the only symptom of a stale cookie.
        assertEquals("the cookie has expired", SpotifyWebToken.lastHarvest)
        assertNull(credentials.cachedSpotifyToken)
    }

    @Test
    fun `a token pasted by hand still wins`() {
        val pasted = jwt(3600)
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(jwt(3600), null),
        )
        SpotifyWebToken.harvester = harvester
        val credentials = FakeCredentials(
            spDcCookie = "a-cookie",
            spotifyWebToken = pasted,
            spotifyBrowserTokenEnabled = true,
        )

        // An explicit choice outranks an automatic one, and it costs no browser launch.
        assertEquals(pasted, runBlocking { SpotifyWebToken.get(credentials) })
        assertEquals(0, harvester.calls)
    }

    @Test
    fun `a refused request gets one more attempt, unlike a pasted token`() {
        val fresh = jwt(3600)
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(fresh, SpotifyWebToken.expiryOf(fresh)),
        )
        SpotifyWebToken.harvester = harvester
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        credentials.cachedSpotifyToken = "a-token-spotify-just-refused"
        credentials.cachedSpotifyTokenExpiresAt = System.currentTimeMillis() + 600_000

        // A harvested token can be replaced, which is the whole point of having a browser to ask.
        assertEquals(fresh, runBlocking { SpotifyWebToken.refresh(credentials) })
        assertEquals(1, harvester.calls)
    }

    @Test
    fun `an expired harvest is not handed out`() {
        // The cache check is what stops a stale token being served for the rest of its window.
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = false)
        credentials.cachedSpotifyToken = "an-old-token"
        credentials.cachedSpotifyTokenExpiresAt = System.currentTimeMillis() - 1

        assertNull(runBlocking { SpotifyWebToken.get(credentials) })
    }

    @Test
    fun `the injected script only reads, and reaches both request paths`() {
        val script = SpotifyBrowserToken.injectedScriptForTest()

        // Both, because the player has used each of them.
        assertTrue(script.contains("window.fetch"))
        assertTrue(script.contains("XMLHttpRequest.prototype.setRequestHeader"))
        // Everything is passed through untouched: this watches, it does not interfere.
        assertTrue(script.contains("nativeFetch.apply(this, arguments)"))
        assertTrue(script.contains("nativeSetHeader.apply(this, arguments)"))
        // Only the one header, and only bearers.
        assertTrue(script.contains("'authorization'"))
        assertTrue(script.contains("'Bearer '"))
        // Nothing is sent anywhere but the bridge.
        assertFalse(script.contains("XMLHttpRequest()"))
        assertFalse(script.contains("navigator.sendBeacon"))
    }
}
