package com.betterlyrics.app.lyrics.provider

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

    const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val ENDPOINTS = listOf(
        "https://open.spotify.com/get_access_token?reason=transport&productType=web_player",
        "https://open.spotify.com/api/token?reason=transport&productType=web_player",
    )

    /** Cached until 30 s before expiry, then re-minted. Null when there is no cookie. */
    fun get(credentials: ProviderCredentials): String? {
        val cached = credentials.cachedSpotifyToken
        if (!cached.isNullOrBlank() &&
            credentials.cachedSpotifyTokenExpiresAt > System.currentTimeMillis() + 30_000
        ) {
            return cached
        }
        return mint(credentials)
    }

    /** Forces a fresh token — call once after a request is refused, then give up. */
    fun refresh(credentials: ProviderCredentials): String? {
        credentials.cachedSpotifyToken = null
        return mint(credentials)
    }

    private fun mint(credentials: ProviderCredentials): String? {
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
