package com.melisma.app.lyrics.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Mints (and caches) a short-lived Spotify web access token from the user's `sp_dc`
 * cookie.
 *
 * Shared because two providers need the same token: Spotify's own `color-lyrics`, and
 * the Spicy Lyrics API, which authenticates with a Spotify token rather than an account
 * of its own.
 *
 * Everything here is undocumented and Spotify has moved the endpoint before, so both
 * spellings are tried and a failure is just a missing token, never an error.
 */
object SpotifyWebToken {

    /**
     * Spotify has closed this route, and said so.
     *
     * `open.spotify.com/get_access_token` answers `403 URL Blocked` at the CDN, and
     * `open.spotify.com/api/token` answers 400 with:
     *
     * > Usage of this endpoint is not permitted under the Spotify Developer Terms and
     * > Developer Policy, and applicable law
     *
     * Identically, with or without a cookie — so it is the endpoint, not anybody's session.
     * The web player still gets a token by signing its request with a time-based code
     * derived from a secret in its own JavaScript. Reproducing that would be working around
     * an access control whose owner has explicitly said not to, so this app does not.
     *
     * The code below is kept rather than deleted: it is correct, it costs nothing, and if
     * Spotify opens the endpoint again this constant is the only thing that needs to change.
     */
    const val BLOCKED_BY_SPOTIFY = true

    /** What to tell the user, in Settings and in the source diagnostic. */
    const val BLOCKED_REASON =
        "Spotify closed this endpoint to anything but its own web player — no cookie works"


    const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val ENDPOINTS = listOf(
        "https://open.spotify.com/get_access_token?reason=transport&productType=web_player",
        "https://open.spotify.com/api/token?reason=transport&productType=web_player",
    )

    /**
     * A usable web access token, or null.
     *
     * Three sources, in order:
     *
     * 1. **One the user pasted in.** The mint is closed, but the endpoints it fed are not —
     *    `color-lyrics` and `audio-attributes` both answer `401`, meaning "bring a token",
     *    rather than `403`. A token copied out of the web player's own network traffic is
     *    such a token, and it works until it expires.
     * 2. A cached token from the mint, while one is still valid.
     * 3. A fresh mint — which currently fails, see [BLOCKED_BY_SPOTIFY].
     */
    suspend fun get(credentials: ProviderCredentials): String? {
        pasted(credentials)?.let { return it }

        val cached = credentials.cachedSpotifyToken
        if (!cached.isNullOrBlank() &&
            credentials.cachedSpotifyTokenExpiresAt > System.currentTimeMillis() + 30_000
        ) {
            return cached
        }
        // Start one, but do not wait for it. A lookup gets twelve seconds for every source
        // together, and a cold WebView loading the whole player takes longer than that on its own —
        // so waiting here meant the lookup cancelled the harvest before it could finish, every
        // time, and automatic renewal could only ever work by accident.
        //
        // So this track goes without Spotify and the next one has a token. Which is the right trade:
        // the other sources have already answered by now, and the alternative is holding every
        // lookup open for the best part of a minute.
        startHarvest(credentials)
        return mint(credentials)
    }

    /**
     * Runs a harvest in the background, at most one at a time.
     *
     * Single-flight because the trigger is "a lookup found no token", and that is true of every
     * track until one succeeds — without the guard, a playlist would launch a WebView per song.
     */
    private fun startHarvest(credentials: ProviderCredentials) {
        val browser = harvester ?: return
        if (!credentials.spotifyBrowserTokenEnabled) return
        if (credentials.spDcCookie.isNullOrBlank()) return
        if (!inFlight.compareAndSet(false, true)) return

        val scope = externalScope
        if (scope == null) {
            inFlight.set(false)
            return
        }
        scope.launch {
            try {
                harvest(browser, credentials)
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * Gets a token before anything asks for one.
     *
     * Called once at startup. Without it the first lookup of every run is the thing that discovers
     * there is no token, reports so, and gets a token only in time for the *next* one — so the first
     * source test after launch always failed and running it again worked. Renewal was doing its job;
     * it was just always one lookup late.
     *
     * Cheap when there is nothing to do: a valid cached token or a pasted one and this returns
     * without starting anything.
     */
    fun warmUp(credentials: ProviderCredentials) {
        if (pasted(credentials) != null) return
        val cached = credentials.cachedSpotifyToken
        if (!cached.isNullOrBlank() &&
            credentials.cachedSpotifyTokenExpiresAt > System.currentTimeMillis() + 60_000
        ) {
            return
        }
        startHarvest(credentials)
    }

    /**
     * Keeps a token in hand for as long as the process lives.
     *
     * Renews [RENEW_MARGIN_MS] before the stated expiry rather than waiting for a lookup to discover
     * the token is dead. Harvesting takes the best part of a minute, so discovering it at the moment
     * of use is always too late — the lookup that notices is the lookup that fails.
     *
     * Sleeps in bounded steps rather than one long one so that turning the switch off, or pasting a
     * token, is noticed within a few minutes instead of at the end of a half-hour nap. Backs off
     * after a failure, because the alternative when a cookie has expired is a WebView every time
     * round the loop, forever.
     */
    fun keepFresh(credentials: ProviderCredentials) {
        val scope = externalScope ?: return
        synchronized(this) {
            if (keeper?.isActive == true) return
            keeper = scope.launch {
                while (isActive) {
                    // Re-read every pass: all three can change while this is asleep.
                    val usable = credentials.spotifyBrowserTokenEnabled &&
                        !credentials.spDcCookie.isNullOrBlank() &&
                        pasted(credentials) == null
                    if (!usable) {
                        delay(IDLE_POLL_MS)
                        continue
                    }

                    val due = credentials.cachedSpotifyTokenExpiresAt - RENEW_MARGIN_MS
                    val wait = due - System.currentTimeMillis()
                    if (wait > 0) {
                        delay(minOf(wait, IDLE_POLL_MS))
                        continue
                    }

                    if (awaitFresh(credentials, KEEPER_HARVEST_MS) == null) {
                        delay(RETRY_AFTER_FAILURE_MS)
                    }
                }
            }
        }
    }

    @Volatile
    private var keeper: Job? = null

    /**
     * Drops the transient state a test would otherwise inherit.
     *
     * This is an object, so the single-flight flag, the keeper job and the last status outlive any
     * one test. A keeper left running from an earlier test makes [keepFresh] a no-op in the next one,
     * and a stuck single-flight flag makes every harvest a no-op — both of which look like the code
     * under test deciding not to act.
     */
    internal fun resetForTest() {
        keeper?.cancel()
        keeper = null
        inFlight.set(false)
        _harvest.value = HarvestStatus()
    }

    /**
     * Throws away a token that has just been refused and waits [timeoutMs] for its replacement.
     *
     * Only for a token the service has actually rejected — a 401, or the 400 that a signed-out
     * token gets. Unlike [renewNow] this *does* clear the cache, and the difference matters: a
     * renewal that times out must not discard a token that still works, but one the service just
     * refused is worthless and handing it back for the next hour of its nominal life is how a single
     * bad token kept failing long after the cookie behind it was fixed.
     *
     * Bounded because the caller is inside a lookup's budget. A cold WebView does not always fit, so
     * this is an opportunity rather than a guarantee: it returns null and the harvest carries on in
     * the background for the next track.
     */
    suspend fun replaceRefused(credentials: ProviderCredentials, timeoutMs: Long): String? {
        // A pasted token is whatever the user copied; nothing here can produce a different one.
        if (pasted(credentials) != null) return null

        credentials.cachedSpotifyToken = null
        credentials.cachedSpotifyTokenExpiresAt = 0L
        return awaitFresh(credentials, timeoutMs)
    }

    /**
     * Starts a harvest if one can run and waits for it to settle.
     *
     * The wait is for a status that both differs from the one already showing and is no longer
     * running: the flow keeps the last result, so matching on content alone returns a previous
     * failure instantly.
     */
    private suspend fun awaitFresh(credentials: ProviderCredentials, timeoutMs: Long): String? {
        if (harvester == null) return null
        if (!credentials.spotifyBrowserTokenEnabled) return null
        if (credentials.spDcCookie.isNullOrBlank()) return null

        val before = _harvest.value
        startHarvest(credentials)

        return withTimeoutOrNull(timeoutMs) {
            harvest.first { it !== before && !it.running }.token
        }
    }

    /** Set at startup. Lets a harvest outlive the lookup that noticed it was needed. */
    @Volatile
    var externalScope: CoroutineScope? = null

    private val inFlight = AtomicBoolean(false)

    /**
     * The WebView harvester, if the app has installed one.
     *
     * Set once at startup rather than passed in, because the two callers reach this through
     * [ProviderCredentials], which is settings and has no business holding a `Context`. Null in a
     * unit test, which is the point — nothing here launches a browser unless something asked for it.
     */
    @Volatile
    var harvester: SpotifyBrowserToken? = null

    /**
     * What the WebView harvest last did.
     *
     * A flow rather than a field: Compose cannot see a plain `var` change, so the hint under the
     * switch stayed on whatever it said when the screen was first drawn — which read as "nothing is
     * happening" whether or not anything was.
     */
    data class HarvestStatus(
        val running: Boolean = false,
        val detail: String? = null,
        /** The token itself, so Settings can show that it worked and let it be copied. */
        val token: String? = null,
        val expiresAt: Long? = null,
    )

    private val _harvest = MutableStateFlow(HarvestStatus())
    val harvest: StateFlow<HarvestStatus> = _harvest.asStateFlow()

    private suspend fun harvest(
        browser: SpotifyBrowserToken,
        credentials: ProviderCredentials,
    ): String? {
        val cookie = credentials.spDcCookie?.takeIf { it.isNotBlank() }
            ?: run {
                _harvest.value = HarvestStatus(detail = "No sp_dc cookie is set")
                return null
            }

        _harvest.value = _harvest.value.copy(running = true, detail = "Asking the player…")
        try {
            return when (val result = browser.harvest(cookie)) {
                is SpotifyBrowserToken.Result.Harvested -> {
                    credentials.cachedSpotifyToken = result.token
                    // The player states the expiry in its token reply, so this is normally the real
                    // one. The fallback is short on purpose: it used to claim 55 minutes, where a
                    // measured token had 29, so a dead token counted as fresh for half an hour and
                    // the warm-up left it alone. Under-guessing costs an extra harvest; over-guessing
                    // costs every lookup in between.
                    val expiresAt = result.expiresAt
                        ?: (System.currentTimeMillis() + FALLBACK_LIFETIME_MS)
                    credentials.cachedSpotifyTokenExpiresAt = expiresAt
                    _harvest.value = HarvestStatus(
                        detail = "Renewed from the cookie",
                        token = result.token,
                        expiresAt = expiresAt,
                    )
                    result.token
                }

                is SpotifyBrowserToken.Result.Failed -> {
                    _harvest.value = HarvestStatus(detail = result.reason)
                    null
                }
            }
        } catch (cancellation: CancellationException) {
            // Neither branch above runs when the caller gives up, so without this the flag stayed
            // set: Settings sat on "Renewing…" and refused every further click until the process
            // restarted. Which is exactly what a cancelled harvest used to leave behind.
            _harvest.value = HarvestStatus(detail = "Cancelled before the player answered")
            throw cancellation
        } finally {
            if (_harvest.value.running) {
                _harvest.value = _harvest.value.copy(running = false)
            }
        }
    }

    /**
     * Runs the harvest now, whatever the caches say.
     *
     * The button in Settings. Without it the only way to find out whether a cookie still works was
     * to play a track and watch for lyrics that never came — and the answer to "is my cookie
     * expired" should not require guessing.
     */
    suspend fun renewNow(credentials: ProviderCredentials): HarvestStatus {
        val browser = harvester
            ?: return HarvestStatus(detail = "No WebView is available on this device").also {
                _harvest.value = it
            }
        if (credentials.spDcCookie.isNullOrBlank()) {
            return HarvestStatus(detail = "No sp_dc cookie is set").also { _harvest.value = it }
        }

        // The cache is deliberately left alone. The harvest does not consult it, so clearing it
        // bought nothing — and it meant a renewal that timed out threw away a token that was still
        // working. Only a success replaces it, inside `harvest`.
        harvest(browser, credentials)
        return _harvest.value
    }

    /**
     * The pasted token, if there is one and it has not expired.
     *
     * Accepts the whole `Authorization` header value as well as the bare token, because
     * copying the header is what a browser's developer tools make easy.
     */
    fun pasted(credentials: ProviderCredentials): String? {
        val token = bearerValue(credentials.spotifyWebToken) ?: return null

        // An expired token is worse than none: every request fails and the reason is
        // invisible. Its own expiry claim says when, so there is no need to guess.
        val expiry = expiryOf(token)
        if (expiry != null && expiry <= System.currentTimeMillis()) return null
        return token
    }

    /**
     * How long a harvested token is assumed to last when nothing says.
     *
     * Twenty minutes, under the 29 measured from a live token, because the cost of being wrong is
     * asymmetric: guess short and there is an extra harvest, guess long and every lookup in between
     * uses a token that has already died. Rarely reached — the player's token reply states the real
     * expiry.
     */
    private const val FALLBACK_LIFETIME_MS = 20 * 60_000L

    /**
     * How far ahead of expiry [keepFresh] renews.
     *
     * Five minutes, which comfortably covers a harvest that has to load the whole player. Renewing
     * at the moment of expiry would mean every lookup in the harvest's window using a dead token,
     * which is the failure this exists to prevent.
     */
    private const val RENEW_MARGIN_MS = 5 * 60_000L

    /** How long the keeper allows a harvest, unlike a lookup it is not inside anyone's budget. */
    private const val KEEPER_HARVEST_MS = 60_000L

    /** Longest the keeper sleeps in one go, so a settings change is noticed reasonably soon. */
    private const val IDLE_POLL_MS = 5 * 60_000L

    /** After a failed harvest — an expired cookie must not mean a WebView every second. */
    private const val RETRY_AFTER_FAILURE_MS = 5 * 60_000L

    /**
     * When a token expires, from its own `exp` claim, or null if it does not say.
     *
     * Returns null for Spotify's own access tokens, which are opaque rather than JWTs — measured
     * against the live player, 403 characters with no `.` in them. Kept for a pasted token, which
     * is whatever the user copied from wherever, and because a null answer here is handled
     * everywhere it is asked. The authoritative expiry for a harvested token comes from the
     * player's token reply instead.
     */
    fun expiryOf(token: String): Long? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return null
        val json = String(
            android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or
                    android.util.Base64.NO_WRAP,
            ),
        )
        Json.parseToJsonElement(json).jsonObject["exp"]?.jsonPrimitive?.longOrNull
            ?.let { it * 1000L }
    }.getOrNull()

    /**
     * Forces a fresh token — call once after a request is refused, then give up.
     *
     * A pasted token cannot be refreshed: it is whatever the user copied. Returning it again
     * would loop, so this reports nothing and the caller gives up, which is correct — the
     * token has expired and only the user can replace it.
     */
    suspend fun refresh(credentials: ProviderCredentials): String? {
        if (pasted(credentials) != null) return null
        credentials.cachedSpotifyToken = null

        // A harvested token *can* be replaced, unlike a pasted one — that is the whole point of
        // having a browser to ask. In the background for the same reason as above: this is still
        // inside a lookup's twelve seconds, and a browser does not fit in them.
        startHarvest(credentials)
        return mint(credentials)
    }

    private suspend fun mint(credentials: ProviderCredentials): String? {
        if (BLOCKED_BY_SPOTIFY) return null
        val cookie = credentials.spDcCookie?.takeIf { it.isNotBlank() } ?: return null
        val headers = mapOf(
            "Cookie" to "sp_dc=$cookie",
            "App-Platform" to "WebPlayer",
            "User-Agent" to WEB_USER_AGENT,
            "Accept" to "application/json",
        )

        for (url in ENDPOINTS) {
            val parsed = Http.get(url, headers) { body ->
                runCatching {
                    val root = Json.parseToJsonElement(body).jsonObject
                    val anonymous = root["isAnonymous"]?.jsonPrimitive?.contentOrNull == "true"
                    val token = root["accessToken"]?.jsonPrimitive?.contentOrNull
                    val expiry = root["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.longOrNull
                    if (anonymous || token.isNullOrBlank()) {
                        null
                    } else {
                        token to (expiry ?: (System.currentTimeMillis() + 30 * 60_000L))
                    }
                }.getOrNull()
            }
            if (parsed != null) {
                credentials.cachedSpotifyToken = parsed.first
                credentials.cachedSpotifyTokenExpiresAt = parsed.second
                return parsed.first
            }
        }
        return null
    }
}
