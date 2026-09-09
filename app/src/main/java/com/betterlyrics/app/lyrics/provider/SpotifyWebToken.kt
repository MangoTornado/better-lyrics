package com.betterlyrics.app.lyrics.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        // The one route that still works, when it is switched on: let the real player mint the
        // token and read what it received. A token lasts about an hour, so this runs a handful of
        // times a day rather than per lookup — the cache above is what makes that true.
        harvester?.let { browser ->
            if (credentials.spotifyBrowserTokenEnabled) {
                harvest(browser, credentials)?.let { return it }
            }
        }
        return mint(credentials)
    }

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
        return when (val result = browser.harvest(cookie)) {
            is SpotifyBrowserToken.Result.Harvested -> {
                credentials.cachedSpotifyToken = result.token
                // Trust the token's own claim; fall back to the hour Spotify actually grants.
                val expiresAt = result.expiresAt ?: (System.currentTimeMillis() + 55 * 60_000L)
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

        credentials.cachedSpotifyToken = null
        credentials.cachedSpotifyTokenExpiresAt = 0
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
     * When a token expires, from its own `exp` claim, or null if it does not say.
     *
     * Spotify's access tokens are JWTs; the middle segment is base64url JSON. Read rather
     * than assumed so Settings can show how much time is left, which is the difference
     * between "this is broken" and "paste a fresh one".
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
        // having a browser to ask. So a 401 is worth one more attempt before giving up.
        harvester?.let { browser ->
            if (credentials.spotifyBrowserTokenEnabled) {
                harvest(browser, credentials)?.let { return it }
            }
        }
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
