package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a source says when it comes back with nothing.
 *
 * `fetch` returns null for reasons that have nothing to do with each other, and the source test used
 * to print one bare "No match" for all of them. That reads as "this service does not have your
 * song" — which, for a Spotify lookup with no track id or a token that has not arrived yet, is a
 * claim about the wrong thing entirely, and sends whoever is reading it to fix something that was
 * never broken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoMatchReasonTest {

    private class FakeCredentials(
        override var spDcCookie: String? = null,
        override var spotifyWebToken: String? = null,
        override val spotifyBrowserTokenEnabled: Boolean = false,
        override val neteaseBaseUrl: String = Settings.DEFAULT_NETEASE_URL,
    ) : ProviderCredentials {
        override val lrcLibBaseUrl = Settings.DEFAULT_LRCLIB_URL
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

    private val lemon = LyricsRequest(
        title = "Lemon",
        artist = "Kenshi Yonezu",
        album = "Lemon",
        durationMs = 255_000,
    )

    @After
    fun tearDown() {
        SpotifyWebToken.harvester = null
        SpotifyWebToken.externalScope = null
    }

    @Test
    fun `a source with nothing to add says nothing`() {
        // The default. Only a source that knows something specific should override it.
        assertNull(LrcLibProvider(FakeCredentials()).noMatchReason)
    }

    @Test
    fun `Spotify says when there is no track id to look up`() = runBlocking {
        // Keyed by Spotify's own id, and nothing else publishes one — so on YouTube Music or a local
        // player this source can never match, and no token would change that. "No match" implied
        // Spotify had been asked and had nothing.
        val credentials = FakeCredentials(spotifyWebToken = "a-token-long-enough-to-be-real".repeat(5))
        val provider = SpotifyLyricsProvider(credentials)

        assertNull(provider.fetch(lemon))
        assertEquals(
            "Not playing from Spotify, so there is no track id to look up",
            provider.noMatchReason,
        )
    }

    @Test
    fun `Spotify says when the token has not arrived yet`() = runBlocking {
        // Automatic renewal makes the source configured *before* a token exists, so this is the
        // state a fresh setup is in — and it reported as "No match", which sounds like a dead end
        // rather than "wait a moment".
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        val provider = SpotifyLyricsProvider(credentials)

        assertNull(provider.fetch(lemon.copy(spotifyTrackId = "7Cd17G3oNQ34OWUwS8ZxfR")))
        assertTrue(provider.noMatchReason!!.contains("No token yet"))
        assertTrue(provider.noMatchReason!!.contains("try again"))
    }

    @Test
    fun `Spotify says plainly when there is no token and no renewal`() = runBlocking {
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = false)
        val provider = SpotifyLyricsProvider(credentials)

        assertNull(provider.fetch(lemon.copy(spotifyTrackId = "7Cd17G3oNQ34OWUwS8ZxfR")))
        assertEquals("No access token", provider.noMatchReason)
    }

    @Test
    fun `NetEase says what it found and how close it came`() = runBlocking {
        // The case that matters, because it is the one that fires: the endpoint answers, results
        // come back, and none of them is the right recording. "No match" gave no way to tell that
        // apart from a region block — and the two want opposite responses, one a wider search and
        // the other a cookie.
        val server = FakeNetease(
            """
            {"result":{"songs":[
              {"id":1,"name":"Lemon Tree","duration":189000,"artists":[{"name":"Fools Garden"}]},
              {"id":2,"name":"Lemonade","duration":195000,"artists":[{"name":"Internet Money"}]}
            ]}}
            """.trimIndent(),
        )
        try {
            val provider = NeteaseProvider(FakeCredentials(neteaseBaseUrl = server.url))
            assertNull(provider.fetch(lemon))

            val reason = provider.noMatchReason
            assertNotNull("NetEase reported nothing about its own failure", reason)
            // How many, which was closest, and how far off — enough to decide whether the search
            // needs widening or the threshold is right to have rejected it.
            assertTrue(reason!!, reason.contains("results"))
            assertTrue(reason, reason.contains("Lemon Tree") || reason.contains("Lemonade"))
            assertTrue(reason, reason.contains("%"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `NetEase says when it has nothing for the title at all`() = runBlocking {
        val server = FakeNetease("""{"result":{"songs":[]}}""")
        try {
            val provider = NeteaseProvider(FakeCredentials(neteaseBaseUrl = server.url))
            assertNull(provider.fetch(lemon))
            assertEquals("NetEase has no results for this title", provider.noMatchReason)
        } finally {
            server.close()
        }
    }

    @Test
    fun `an unreachable source is an exception, not a no-match`() = runBlocking {
        // Worth pinning because it is why the reachability branch above is not the one that fires:
        // `Http.get` throws for a host that does not answer, and the source test prints that
        // exception rather than "No match". So a NetEase "no match" is always a matching problem.
        val provider = NeteaseProvider(FakeCredentials(neteaseBaseUrl = "http://127.0.0.1:9"))
        try {
            provider.fetch(lemon)
            org.junit.Assert.fail("expected Http.Unavailable")
        } catch (expected: Http.Unavailable) {
            assertTrue(expected.message!!, expected.message!!.contains("no answer from"))
        }
    }

    @Test
    fun `a track Spotify has no lyrics for does not blame the token`() = runBlocking {
        // The report that prompted this: "Spotify refused the token and there is no replacement
        // yet", for a token that had just been accepted. The retry ran for *any* empty answer, could
        // never produce a different token, and overwrote the real reason with a complaint about the
        // one thing that was working.
        val server = FakeHttp(status = 404, body = """{"error":"not found"}""")
        try {
            val provider = SpotifyLyricsProvider(
                FakeCredentials(spotifyWebToken = "x".repeat(120)),
                lyricsBase = server.url,
            )
            assertNull(provider.fetch(lemon.copy(spotifyTrackId = "7Cd17G3oNQ34OWUwS8ZxfR")))

            val reason = provider.noMatchReason!!
            assertTrue(reason, reason.contains("no lyrics for this track"))
            assertTrue(reason, reason.contains("404"))
            // And nothing in it points at the credential.
            assertFalse(reason, reason.contains("token"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `a refused token says so, and says a renewal is coming`() = runBlocking {
        val server = FakeHttp(status = 401, body = """{"error":"unauthorised"}""")
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        // A token has to exist for the request to be made at all.
        credentials.cachedSpotifyToken = "x".repeat(120)
        credentials.cachedSpotifyTokenExpiresAt = System.currentTimeMillis() + 3_000_000
        try {
            val provider = SpotifyLyricsProvider(credentials, lyricsBase = server.url)
            assertNull(provider.fetch(lemon.copy(spotifyTrackId = "7Cd17G3oNQ34OWUwS8ZxfR")))

            val reason = provider.noMatchReason!!
            assertTrue(reason, reason.contains("refused the token"))
            assertTrue(reason, reason.contains("401"))
            // Renewal is on, so the next step is waiting rather than pasting.
            assertTrue(reason, reason.contains("renewal is running"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `an unexpected status is named rather than guessed at`() = runBlocking {
        // Not 502: `Http.get` throws for 429 and anything 5xx, treating them as "not now" rather
        // than "not here", so those never reach this path — the source test prints the exception.
        // Not 400 either: that one has a known, specific meaning here and its own message.
        val server = FakeHttp(status = 418, body = "teapot")
        try {
            val provider = SpotifyLyricsProvider(
                FakeCredentials(spotifyWebToken = "x".repeat(120)),
                lyricsBase = server.url,
            )
            assertNull(provider.fetch(lemon.copy(spotifyTrackId = "7Cd17G3oNQ34OWUwS8ZxfR")))
            assertEquals("Spotify answered HTTP 418", provider.noMatchReason)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a signed-out token is named as such, not reported as a bad request`() = runBlocking {
        // Reported as "http 400 from spotify" for Blinding Lights — a track that certainly has
        // lyrics. Verified against the live endpoint: a signed-out player still issues a real,
        // full-length token, it is accepted rather than refused, and this endpoint answers 400
        // because no user is attached. The same request with a signed-in token returned 200.
        val server = FakeHttp(status = 400, body = "")
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        credentials.cachedSpotifyToken = "x".repeat(140)
        credentials.cachedSpotifyTokenExpiresAt = System.currentTimeMillis() + 3_000_000
        try {
            val provider = SpotifyLyricsProvider(credentials, lyricsBase = server.url)
            assertNull(provider.fetch(lemon.copy(spotifyTrackId = "7Cd17G3oNQ34OWUwS8ZxfR")))

            val reason = provider.noMatchReason!!
            assertTrue(reason, reason.contains("not signed in"))
            // Not blamed on the track, which is what a bare "400" invited.
            assertFalse(reason, reason.contains("no lyrics"))

            // And the token is gone. Nothing else invalidated it, so one signed-out token kept
            // failing for the full hour of its nominal life — which is what "it works if I run the
            // test again later" was.
            assertNull("a refused token must not be kept", credentials.cachedSpotifyToken)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a refused token is replaced and the lookup retried once`() = runBlocking {
        // What "the first test after launch fails and the second one works" should have been. The
        // token that gets refused is replaced and the same lookup asks again, so the answer arrives
        // on the attempt the user is actually watching.
        val lyrics = """
            {"lyrics":{"syncType":"LINE_SYNCED","providerDisplayName":"Musixmatch",
             "lines":[{"startTimeMs":"1000","words":"Yeah","endTimeMs":"0"},
                      {"startTimeMs":"2000","words":"I said ooh","endTimeMs":"0"}]}}
        """.trimIndent()
        val server = FakeHttp(listOf(400 to "", 200 to lyrics))
        val credentials = FakeCredentials(spDcCookie = "a-cookie", spotifyBrowserTokenEnabled = true)
        credentials.cachedSpotifyToken = "stale".repeat(30)
        credentials.cachedSpotifyTokenExpiresAt = System.currentTimeMillis() + 3_000_000

        val fresh = "fresh".repeat(30)
        val previousHarvester = SpotifyWebToken.harvester
        val previousScope = SpotifyWebToken.externalScope
        SpotifyWebToken.harvester = object : SpotifyBrowserToken(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        ) {
            override suspend fun harvest(spDcCookie: String, timeoutMs: Long) =
                SpotifyBrowserToken.Result.Harvested(fresh, System.currentTimeMillis() + 3_000_000)
        }
        SpotifyWebToken.externalScope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)

        try {
            val provider = SpotifyLyricsProvider(credentials, lyricsBase = server.url)
            val document = provider.fetch(lemon.copy(spotifyTrackId = "7Cd17G3oNQ34OWUwS8ZxfR"))

            assertNotNull("the retry should have produced lyrics", document)
            assertEquals(2, document!!.lines.size)
            assertEquals("both attempts should have been made", 2, server.requests)
            assertEquals("the fresh token should be cached", fresh, credentials.cachedSpotifyToken)
            assertNull("a successful lookup has no reason to give", provider.noMatchReason)
        } finally {
            SpotifyWebToken.harvester = previousHarvester
            SpotifyWebToken.externalScope = previousScope
            server.close()
        }
    }

    /**
     * A stand-in for NetEase's search endpoint.
     *
     * A raw socket rather than a real server: `com.sun.net.httpserver` is not on the Android unit
     * test classpath, and the provider only needs a well-formed reply to whatever it asks for. It
     * answers every connection with the same body, because the provider tries several queries.
     */
    private class FakeNetease(body: String) {
        private val delegate = FakeHttp(200, body)
        val url: String get() = delegate.url
        fun close() = delegate.close()
    }

    /**
     * Answers connections with canned statuses and bodies.
     *
     * A list rather than one reply, because the retry after a refused token can only be observed by
     * answering the second request differently from the first. The last entry repeats, so a single
     * reply still means "always this".
     */
    private class FakeHttp(private val replies: List<Pair<Int, String>>) {
        constructor(status: Int, body: String) : this(listOf(status to body))

        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        private val thread: Thread
        private val served = java.util.concurrent.atomic.AtomicInteger(0)

        val url: String = "http://127.0.0.1:${socket.localPort}"

        /** How many requests arrived, so a test can tell a retry from a single attempt. */
        val requests: Int get() = served.get()

        init {
            val encoded = replies.map { (status, body) ->
                val payload = body.toByteArray()
                buildString {
                    append("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray() + payload
            }

            thread = Thread {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            // Drain the request line and headers so the client is not left writing
                            // into a closed pipe, then answer.
                            val input = client.getInputStream().bufferedReader()
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val index = served.getAndIncrement()
                            client.getOutputStream().apply {
                                write(encoded[minOf(index, encoded.lastIndex)])
                                flush()
                            }
                        }
                    } catch (stopped: java.io.IOException) {
                        return@Thread
                    }
                }
            }
            thread.isDaemon = true
            thread.start()
        }

        fun close() {
            socket.close()
        }
    }
}
