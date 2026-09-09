package com.betterlyrics.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.betterlyrics.app.lyrics.provider.Http
import com.betterlyrics.app.lyrics.provider.bearerValue
import com.betterlyrics.app.lyrics.provider.LyricsRequest
import com.betterlyrics.app.lyrics.provider.Matching
import com.betterlyrics.app.lyrics.provider.ProviderCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Cover and artist images for a track, as Apple has them — and the recording's identity. */
data class AppleImages(
    val coverUrl: String? = null,
    val artistImageUrl: String? = null,
    /**
     * The ISRC, from the same search that found the artwork.
     *
     * Worth taking here as well as in the lyrics provider, because this runs on the developer token
     * alone: somebody with no Apple subscription — so no syllable lyrics — still learns the identity
     * of what they are playing, and every later lookup becomes exact.
     */
    val isrc: String? = null,
)

/**
 * Cover art and artist images from the Apple Music catalogue.
 *
 * Worth having alongside Spotify's for one practical reason: **the token lasts**. Spotify's
 * web access token is good for about an hour; the developer token the Apple Music web player
 * carries is good for months. So this is the source that keeps working after you have stopped
 * thinking about it.
 *
 * Only the developer token is needed — these are catalogue lookups, not library ones, so the
 * `Media-User-Token` that syllable lyrics require is not involved. Artwork URLs come back as
 * templates with `{w}` and `{h}` in them, which is Apple asking what size you want.
 */
class AppleArtwork(private val credentials: ProviderCredentials) {

    private val cache = LinkedHashMap<String, AppleImages?>()

    val isAvailable: Boolean get() = !credentials.appleDeveloperToken.isNullOrBlank()

    suspend fun imagesFor(track: TrackInfo): AppleImages? = withContext(Dispatchers.IO) {
        if (!isAvailable || track.isEmpty) return@withContext null
        val key = track.cacheKey
        if (cache.containsKey(key)) return@withContext cache[key]

        val images = runCatching { lookUp(track) }.getOrNull()
        if (cache.size >= CACHE_SIZE) cache.keys.firstOrNull()?.let(cache::remove)
        cache[key] = images
        images
    }

    /** Load one of the URLs from [imagesFor] at [size] pixels square. */
    suspend fun image(urlTemplate: String, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        val url = urlTemplate.replace("{w}", size.toString()).replace("{h}", size.toString())
            // Some templates carry a format placeholder too.
            .replace("{f}", "jpg")
        runCatching {
            Http.client.newCall(Http.request(url)).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull()
    }

    /**
     * Search the catalogue for the track, then follow it to its artist.
     *
     * Scored with the same matcher the lyrics providers use, and discarded below the same
     * threshold — a cover from the wrong song is a worse mistake than no cover, because it
     * looks deliberate.
     */
    private suspend fun lookUp(track: TrackInfo): AppleImages? {
        val storefront = credentials.appleStorefront.ifBlank { "us" }
        val request = LyricsRequest(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
        )

        val term = "${track.title} ${request.primaryArtist}".trim()
        val url = "$API/v1/catalog/$storefront/search" +
            "?term=${Http.encode(term)}&types=songs&limit=10"

        val song = Http.get(url, headers()) { body ->
            runCatching {
                val songs = Json.parseToJsonElement(body).jsonObject["results"]
                    ?.jsonObject?.get("songs")
                    ?.jsonObject?.get("data")?.jsonArray
                    ?: return@runCatching null

                songs.mapNotNull { element ->
                    val song = runCatching { element.jsonObject }.getOrNull()
                        ?: return@mapNotNull null
                    val attributes = song["attributes"]?.jsonObject ?: return@mapNotNull null
                    val score = Matching.score(
                        request,
                        attributes["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        attributes["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        attributes["durationInMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                    if (score < Matching.MATCH_THRESHOLD) null else song to score
                }.maxByOrNull { it.second }?.first
            }.getOrNull()
        } ?: return null

        val cover = song["attributes"]?.jsonObject
            ?.get("artwork")?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull

        // The artist id is in the relationships, and the image is one hop further on —
        // Apple does not inline it on the song.
        val artistId = song["relationships"]?.jsonObject
            ?.get("artists")?.jsonObject
            ?.get("data")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull

        val isrc = song["attributes"]?.jsonObject
            ?.get("isrc")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        val artistImage = artistId?.let { artistImage(storefront, it) }
        return AppleImages(cover, artistImage, isrc).takeIf {
            it.coverUrl != null || it.artistImageUrl != null || it.isrc != null
        }
    }

    /**
     * The artist's photo.
     *
     * Not every artist has one — Apple only carries them where a label supplied one — so a
     * null here is ordinary rather than a failure.
     */
    private suspend fun artistImage(storefront: String, artistId: String): String? {
        val url = "$API/v1/catalog/$storefront/artists/$artistId"
        return Http.get(url, headers()) { body ->
            runCatching {
                Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("attributes")?.jsonObject
                    ?.get("artwork")?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
    }

    private fun headers(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${bearerValue(credentials.appleDeveloperToken).orEmpty()}",
        "Origin" to "https://music.apple.com",
        "Referer" to "https://music.apple.com/",
        "Accept" to "application/json",
    )

    private companion object {
        /**
         * The player's own host rather than `api.music.apple.com`.
         *
         * It accepts the same developer token the web player carries, which is the token a
         * user can actually get hold of without a paid developer membership.
         */
        const val API = "https://amp-api.music.apple.com"
        const val CACHE_SIZE = 8
    }
}

/**
 * What size to ask Apple for. Bigger than any phone needs, so it never looks soft, and the
 * one place to change it if that turns out to be wasteful.
 */
internal const val APPLE_IMAGE_SIZE = 1200
