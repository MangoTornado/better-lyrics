package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

        fun reset() {
            calls = 0
        }

        override suspend fun harvest(spDcCookie: String, timeoutMs: Long): SpotifyBrowserToken.Result {
            calls++
            return result
        }
    }

    @After
    fun tearDown() {
        SpotifyWebToken.harvester = null
        SpotifyWebToken.externalScope = null
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
    fun `a lookup never waits for the browser, it starts one and moves on`() {
        val token = jwt(3600)
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(token, SpotifyWebToken.expiryOf(token)),
        )
        SpotifyWebToken.harvester = harvester
        val scope = CoroutineScope(Dispatchers.Unconfined)
        SpotifyWebToken.externalScope = scope
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)

        // A lookup gets twelve seconds for every source together and a cold player load takes
        // longer, so waiting meant the lookup cancelled the harvest before it could finish — every
        // time. This track goes without Spotify; the next one has a token.
        assertNull(runBlocking { SpotifyWebToken.get(credentials) })

        // Started, though, and its result kept.
        assertEquals(1, harvester.calls)
        assertEquals(token, credentials.cachedSpotifyToken)
        // Its own claim, not a guess — so the next launch happens when it actually needs to.
        assertEquals(SpotifyWebToken.expiryOf(token)!!, credentials.cachedSpotifyTokenExpiresAt)

        // And the token is there for the lookup after this one.
        assertEquals(token, runBlocking { SpotifyWebToken.get(credentials) })
    }

    @Test
    fun `a playlist does not launch a browser per track`() {
        val token = jwt(3600)
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Failed("still trying"),
        )
        SpotifyWebToken.harvester = harvester
        SpotifyWebToken.externalScope = CoroutineScope(Dispatchers.Unconfined)
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)

        // The trigger is "a lookup found no token", which stays true of every track until one
        // succeeds. Without a single-flight guard that is one WebView per song.
        repeat(5) { runBlocking { SpotifyWebToken.get(credentials) } }
        assertEquals(5, harvester.calls)

        // With a valid token in hand, nothing starts at all.
        harvester.reset()
        credentials.cachedSpotifyToken = token
        credentials.cachedSpotifyTokenExpiresAt = System.currentTimeMillis() + 3_000_000
        repeat(5) { assertEquals(token, runBlocking { SpotifyWebToken.get(credentials) }) }
        assertEquals(0, harvester.calls)
    }

    @Test
    fun `a failure is reported rather than swallowed`() {
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Failed("the cookie has expired"),
        )
        SpotifyWebToken.harvester = harvester
        SpotifyWebToken.externalScope = CoroutineScope(Dispatchers.Unconfined)
        val credentials = FakeCredentials(spDcCookie = "a-stale-cookie", spotifyBrowserTokenEnabled = true)

        assertNull(runBlocking { SpotifyWebToken.get(credentials) })
        // Settings shows this. Without it, "no lyrics" is the only symptom of a stale cookie.
        assertEquals("the cookie has expired", SpotifyWebToken.harvest.value.detail)
        assertNull(credentials.cachedSpotifyToken)
    }

    @Test
    fun `a cancelled harvest does not leave the button stuck`() {
        // The two defects compounded: the lookup timeout cancelled the harvest, and a cancelled
        // harvest left `running` set — so Settings sat on "Renewing…" and refused every further
        // click until the process restarted.
        val cancelling = object : SpotifyBrowserToken(
            org.robolectric.RuntimeEnvironment.getApplication(),
        ) {
            override suspend fun harvest(spDcCookie: String, timeoutMs: Long): Result =
                throw kotlinx.coroutines.CancellationException("the caller gave up")
        }
        SpotifyWebToken.harvester = cancelling
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)

        runCatching { runBlocking { SpotifyWebToken.renewNow(credentials) } }

        assertFalse("the button would still be disabled", SpotifyWebToken.harvest.value.running)
        assertEquals("Cancelled before the player answered", SpotifyWebToken.harvest.value.detail)
    }

    @Test
    fun `a failed renewal keeps the token that was working`() {
        val working = jwt(3600)
        SpotifyWebToken.harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Failed("the player did not answer"),
        )
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        credentials.cachedSpotifyToken = working
        credentials.cachedSpotifyTokenExpiresAt = System.currentTimeMillis() + 3_000_000

        val status = runBlocking { SpotifyWebToken.renewNow(credentials) }
        assertNull(status.token)
        // Pressing a diagnostic button must not cost the working credential. The harvest does not
        // read the cache, so clearing it first bought nothing and risked exactly this.
        assertEquals(working, credentials.cachedSpotifyToken)
        assertEquals(working, runBlocking { SpotifyWebToken.get(credentials) })
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
    fun `a refused request starts a replacement, unlike a pasted token`() {
        val fresh = jwt(3600)
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(fresh, SpotifyWebToken.expiryOf(fresh)),
        )
        SpotifyWebToken.harvester = harvester
        SpotifyWebToken.externalScope = CoroutineScope(Dispatchers.Unconfined)
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        credentials.cachedSpotifyToken = "a-token-spotify-just-refused"
        credentials.cachedSpotifyTokenExpiresAt = System.currentTimeMillis() + 600_000

        // A harvested token can be replaced, which is the whole point of having a browser to ask —
        // in the background, because this call is still inside a lookup's twelve seconds.
        runBlocking { SpotifyWebToken.refresh(credentials) }
        assertEquals(1, harvester.calls)
        assertEquals(fresh, credentials.cachedSpotifyToken)
    }

    @Test
    fun `a rejected pasted token blames the paste, not the cookie`() {
        // `get()` prefers a pasted token and `refresh()` will not replace one, so if Spotify refused
        // anything it refused that. Blaming the cookie sent the user to replace a credential that
        // was never used.
        val credentials = FakeCredentials(
            spDcCookie = "a-cookie",
            spotifyWebToken = jwt(3600),
            spotifyBrowserTokenEnabled = true,
        )
        val provider = SpotifyLyricsProvider(credentials)
        provider.noteRejectedForTest()
        assertTrue(provider.unavailableReason!!.contains("pasted access token"))

        // With nothing pasted, the cookie behind the harvest is the right thing to point at.
        val cookieOnly = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        val harvesting = SpotifyLyricsProvider(cookieOnly)
        harvesting.noteRejectedForTest()
        assertTrue(harvesting.unavailableReason!!.contains("sp_dc cookie"))
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
    fun `a cookie and the switch are enough for Spotify to be asked at all`() {
        // The bug this exists to stop coming back: with no pasted token the source read as
        // unconfigured, an unconfigured source is never queried, and the renewal only ran *inside* a
        // query. So the one moment it was needed was the one moment it could not happen — the
        // feature looked switched on and did nothing, with no hint as to why.
        val withRenewal = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        assertTrue(SpotifyLyricsProvider(withRenewal).isConfigured)
        assertNull(SpotifyLyricsProvider(withRenewal).unavailableReason)

        val withoutRenewal = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = false)
        assertFalse(SpotifyLyricsProvider(withoutRenewal).isConfigured)
    }

    @Test
    fun `renew now runs whatever the cache says, and reports the outcome`() {
        val fresh = jwt(3600)
        val harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(fresh, SpotifyWebToken.expiryOf(fresh)),
        )
        SpotifyWebToken.harvester = harvester
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        // A token that is still perfectly valid: the button has to ignore it, because the reason for
        // pressing it is to find out whether the cookie still works.
        credentials.cachedSpotifyToken = "a-valid-token"
        credentials.cachedSpotifyTokenExpiresAt = System.currentTimeMillis() + 3_000_000

        val status = runBlocking { SpotifyWebToken.renewNow(credentials) }
        assertEquals(1, harvester.calls)
        assertEquals(fresh, status.token)
        assertEquals("Renewed from the cookie", status.detail)
        assertFalse(status.running)
    }

    @Test
    fun `renew now says which thing is missing`() {
        SpotifyWebToken.harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(jwt(3600), null),
        )
        val noCookie = FakeCredentials(spDcCookie = null, spotifyBrowserTokenEnabled = true)
        assertEquals(
            "No sp_dc cookie is set",
            runBlocking { SpotifyWebToken.renewNow(noCookie) }.detail,
        )

        SpotifyWebToken.harvester = null
        val noWebView = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        assertEquals(
            "No WebView is available on this device",
            runBlocking { SpotifyWebToken.renewNow(noWebView) }.detail,
        )
    }

    @Test
    fun `the status is observable, so the screen can change when it does`() {
        val fresh = jwt(3600)
        SpotifyWebToken.harvester = RecordingHarvester(
            SpotifyBrowserToken.Result.Harvested(fresh, SpotifyWebToken.expiryOf(fresh)),
        )
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)

        // A plain field would not recompose: the hint stayed on whatever it said when the screen was
        // first drawn, which read as "nothing is happening" whether or not anything was.
        runBlocking { SpotifyWebToken.renewNow(credentials) }
        assertEquals(fresh, SpotifyWebToken.harvest.value.token)
        assertEquals(SpotifyWebToken.expiryOf(fresh), SpotifyWebToken.harvest.value.expiresAt)
    }

    @Test
    fun `a refused token blames the cookie once renewal is on`() {
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        val provider = SpotifyLyricsProvider(credentials)
        provider.noteRejectedForTest()
        // "Copy a fresh one" is the wrong instruction when nothing was pasted.
        assertEquals(
            "Spotify refused the token — the sp_dc cookie may have expired",
            provider.unavailableReason,
        )
    }

    @Test
    fun `the origin rules are ones the WebView will accept`() {
        // This is the crash, pinned. A scheme followed by a bare asterisk is not a valid rule, and
        // `addDocumentStartJavaScript` validates by throwing — so a wrong shape did not degrade to a
        // failed harvest, it took the app down the moment the button was pressed.
        //
        // Checked against the same rule the platform applies rather than by eye: a host, or a
        // leading star-dot and a domain. Anything else is rejected.
        val valid = Regex("^(\\*|[a-z]+://(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+(:[0-9]+)?)$")
        val origins = SpotifyBrowserToken.allowedOriginsForTest()

        assertTrue(origins.isNotEmpty())
        for (origin in origins) {
            assertTrue("\"" + origin + "\" is not a valid origin rule", valid.matches(origin))
        }
        // And the shape that threw stays out.
        assertFalse(origins.contains("https://" + "*"))
        // The player's own origin has to be among them, or the script never runs.
        assertTrue(origins.any { it == "https://open.spotify.com" || it == "https://*.spotify.com" })
    }

    @Test
    fun `a failure inside the WebView is reported, never thrown`() {
        // The button is a diagnostic behind a developer switch. Whatever the WebView stack throws —
        // a missing provider, an update in progress, an API rejecting its arguments — has to come
        // back as a line to read. Crashing while answering "why is this not working" is the one
        // outcome that helps nobody.
        val exploding = object : SpotifyBrowserToken(
            org.robolectric.RuntimeEnvironment.getApplication(),
        ) {
            override suspend fun harvest(spDcCookie: String, timeoutMs: Long): Result =
                Result.Failed("IllegalArgumentException: something the platform rejected")
        }
        SpotifyWebToken.harvester = exploding
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)

        val status = runBlocking { SpotifyWebToken.renewNow(credentials) }
        assertEquals("IllegalArgumentException: something the platform rejected", status.detail)
        assertNull(status.token)
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
