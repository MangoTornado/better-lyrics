package com.melisma.app.media

import android.graphics.Bitmap
import com.melisma.app.lyrics.provider.Http
import com.melisma.app.lyrics.provider.ProviderCredentials
import com.melisma.app.lyrics.provider.SpotifyWebToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Everything extra a Spotify token can buy, over and above the lyrics.
 *
 * A media session gives us a track title, an artist name and a small bitmap. With the same
 * `sp_dc`-derived token the lyrics provider already uses, three better things become
 * available:
 *
 * - the **artist image**, which is what Spicy Lyrics' "Artist Header" background is,
 * - the **full-resolution album cover** (640 px, against the 200–300 px thumbnail most
 *   players publish), which the background and the Cinema view both look better for,
 * - the **audio analysis** — tempo and loudness — which lets the drifting background move
 *   in time with the song rather than at a fixed rate.
 *
 * Everything here is best-effort. No token, no request; a failed request just means the
 * app carries on with what the media session gave it.
 */
class SpotifyExtras(private val credentials: ProviderCredentials) {

    data class TrackExtras(
        val trackId: String,
        /** Artist image, for the artist-header background. */
        val artistImageUrl: String? = null,
        /** Full-size album art, better than the session's thumbnail. */
        val coverUrl: String? = null,
        /** Beats per minute, for pacing the background. */
        val tempo: Float? = null,
        /** Overall loudness in dB (negative); a rough stand-in for energy. */
        val loudness: Float? = null,
        /** Identifies the recording, so a later lookup can be exact instead of a guess. */
        val isrc: String? = null,
    ) {
        val hasAnything: Boolean
            get() = artistImageUrl != null || coverUrl != null || tempo != null || isrc != null
    }

    /** True when a cookie is present, so the caller knows whether to bother asking. */
    /**
     * Whether the artist image, the full-size cover and the tempo can be fetched.
     *
     * All three ride the same web access token as the lyrics do, so the answer is the same:
     * a token pasted out of the web player works, and the cookie on its own no longer does.
     */
    val isAvailable: Boolean
        get() = SpotifyWebToken.pasted(credentials) != null ||
            // The same gate the lyrics provider uses, and it has to say the same thing: these read
            // the artist image, the full-size cover and the tempo with the very token the lyrics
            // use. Accepting renewal there and not here meant lyrics worked while the artwork it
            // is drawn over stayed missing.
            credentials.spotifyBrowserTokenEnabled ||
            (!SpotifyWebToken.BLOCKED_BY_SPOTIFY && !credentials.spDcCookie.isNullOrBlank())

    private val extrasCache = LinkedHashMap<String, TrackExtras>()
    private val bitmapCache = LinkedHashMap<String, Bitmap>()

    suspend fun extrasFor(trackId: String): TrackExtras? = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext null
        extrasCache[trackId]?.let { return@withContext it }

        val token = SpotifyWebToken.get(credentials) ?: return@withContext null

        // Two services, kept independent on purpose.
        //
        // `api.spotify.com` is rate-limited hard for a web-player token — persistently `429 API rate
        // limit exceeded`, and it survives a change of address, so the limit follows the token. The
        // details used to be fetched first and a failure returned from the whole function, which threw
        // away the tempo as well. The analysis lives on a different host and is keyed by the track id
        // already in hand, so it never needed the details at all.
        //
        // `Http.get` throws for a 429 rather than returning null — 429 means "not now", not "not
        // here" — so this has to be caught rather than checked for null.
        var track = runCatching { trackDetails(trackId, token) }.getOrNull()
        if (track == null) {
            // Worth one retry with a fresh token, since an expired one looks much the same from here.
            // Not worth abandoning the tempo over: a null from `refresh` is the ordinary answer for a
            // harvested token, whose replacement arrives in the background.
            val renewed = SpotifyWebToken.refresh(credentials)
            if (renewed != null) track = runCatching { trackDetails(trackId, renewed) }.getOrNull()
        }

        val artistImage = track?.artistId?.let { runCatching { artistImage(it, token) }.getOrNull() }
        val analysis = runCatching { audioAnalysis(trackId, token) }.getOrNull()

        val extras = TrackExtras(
            trackId = trackId,
            artistImageUrl = artistImage,
            coverUrl = track?.coverUrl,
            tempo = analysis?.first,
            loudness = analysis?.second,
            isrc = track?.isrc,
        )
        if (extras.hasAnything) remember(extrasCache, trackId, extras)
        extras.takeIf { it.hasAnything }
    }

    /** Downloads and decodes an image URL, keeping the last few in memory. */
    suspend fun image(url: String): Bitmap? = withContext(Dispatchers.IO) {
        bitmapCache[url]?.let { return@withContext it }
        val bytes = runCatching {
            Http.client.newCall(Http.request(url)).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        }.getOrNull() ?: return@withContext null

        val bitmap = runCatching {
            ArtworkDecoding.decode(bytes)
        }.getOrNull() ?: return@withContext null

        remember(bitmapCache, url, bitmap)
        bitmap
    }

    private class TrackDetails(
        val artistId: String?,
        val coverUrl: String?,
        /**
         * The recording's ISRC, from the same response as the cover.
         *
         * The reason to bother: the token that fetched it expires within the hour, and the ISRC
         * never does. One track played with a token in hand is matched exactly for good.
         */
        val isrc: String?,
    )

    private suspend fun trackDetails(trackId: String, token: String): TrackDetails? =
        Http.get("$WEB_API/tracks/$trackId", bearer(token)) { body ->
            runCatching {
                val root = Json.parseToJsonElement(body).jsonObject
                val artistId = root["artists"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                // Spotify returns images widest-first.
                val cover = root["album"]?.jsonObject?.get("images")?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                val isrc = root["external_ids"]?.jsonObject
                    ?.get("isrc")?.jsonPrimitive?.contentOrNull
                TrackDetails(artistId, cover, isrc)
            }.getOrNull()
        }

    /**
     * The artist's own image.
     *
     * Spicy Lyrics reaches the true wide *header* banner through Spotify's internal
     * GraphQL gateway, which needs a persisted-query hash that changes with every client
     * release. The documented endpoint gives the artist image instead — the same artwork,
     * square rather than letterboxed — which is indistinguishable once it has been blurred
     * into a background, and does not break when Spotify ships a new web player.
     */
    private suspend fun artistImage(artistId: String, token: String): String? =
        Http.get("$WEB_API/artists/$artistId", bearer(token)) { body ->
            runCatching {
                Json.parseToJsonElement(body).jsonObject["images"]?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }

    /** Returns tempo (BPM) and loudness (dB), or null when Spotify has no analysis. */
    private suspend fun audioAnalysis(trackId: String, token: String): Pair<Float, Float>? {
        val url = "$SPCLIENT/audio-attributes/v1/audio-analysis/$trackId?format=json"
        return Http.get(url, bearer(token)) { body ->
            runCatching {
                val track = Json.parseToJsonElement(body).jsonObject["track"]?.jsonObject
                    ?: return@runCatching null
                val tempo = track["tempo"]?.jsonPrimitive?.doubleOrNull?.toFloat()
                    ?: track["tempo"]?.jsonPrimitive?.intOrNull?.toFloat()
                    ?: return@runCatching null
                val loudness = track["loudness"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: -8f
                tempo to loudness
            }.getOrNull()
        }
    }

    private fun bearer(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "App-Platform" to "WebPlayer",
        "User-Agent" to SpotifyWebToken.WEB_USER_AGENT,
        "Accept" to "application/json",
    )

    /** Tiny LRU: only the current track and the couple before it are ever wanted again. */
    private fun <T> remember(cache: LinkedHashMap<String, T>, key: String, value: T) {
        cache.remove(key)
        cache[key] = value
        while (cache.size > CACHE_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    private companion object {
        const val WEB_API = "https://api.spotify.com/v1"
        const val SPCLIENT = "https://spclient.wg.spotify.com"
        /**
         * Shared by the details cache and the bitmap cache. Three, because the second holds decoded
         * covers and artist images — megabytes each, where the first holds a handful of strings.
         */
        const val CACHE_ENTRIES = 3
    }
}

/**
 * The resolved extras for the track playing now — images decoded, tempo in hand.
 *
 * Separate from [SpotifyExtras.TrackExtras] because that carries URLs; this is what the UI
 * consumes, and it is deliberately cleared on every track change so a previous artist's
 * face can never sit behind the wrong song.
 */
data class NowPlayingExtras(
    val trackId: String? = null,
    val artistImage: Bitmap? = null,
    val cover: Bitmap? = null,
    val tempo: Float? = null,
)
