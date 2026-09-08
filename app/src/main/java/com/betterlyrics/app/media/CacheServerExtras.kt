package com.betterlyrics.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.betterlyrics.app.lyrics.provider.Http
import com.betterlyrics.app.lyrics.provider.ProviderCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a cache server knows about a track besides its words. */
data class CachedExtras(
    val coverUrl: String? = null,
    val artistImageUrl: String? = null,
    val tempo: Float? = null,
)

/**
 * Artwork and tempo through your own cache server. Asking only.
 *
 * The server holds these because it outlives the tokens: a Spotify access token is good for
 * about an hour and an Apple developer token for a few months, but a cover art URL, an ISRC and
 * a tempo, once known, are true forever. It collects them itself every time it looks a track up,
 * so the tokens live on one machine instead of on every phone.
 *
 * Which is why there is nothing to contribute from here, and no credential to send. The phone
 * asks; being wrong about a track is not something it can make stick.
 */
class CacheServerExtras(private val credentials: ProviderCredentials) {

    private val cache = LinkedHashMap<String, CachedExtras?>()

    val isAvailable: Boolean get() = base() != null

    private fun base(): String? =
        credentials.cacheServerUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    suspend fun fetch(track: TrackInfo): CachedExtras? = withContext(Dispatchers.IO) {
        val base = base() ?: return@withContext null
        if (track.isEmpty) return@withContext null
        val key = track.cacheKey
        if (cache.containsKey(key)) return@withContext cache[key]

        val extras = runCatching {
            Http.get("$base/v1/extras?${query(track)}", headers()) { body -> parse(body) }
        }.getOrNull()

        if (cache.size >= CACHE_SIZE) cache.keys.firstOrNull()?.let(cache::remove)
        cache[key] = extras
        extras
    }

    /** Load one of the URLs [fetch] returned. */
    suspend fun image(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Http.request(url, headers())).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull()
    }

    private fun parse(body: String): CachedExtras? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: root
        CachedExtras(
            coverUrl = data.string("coverUrl") ?: data.string("cover"),
            artistImageUrl = data.string("artistImageUrl") ?: data.string("artistImage"),
            tempo = data["tempo"]?.jsonPrimitive?.floatOrNull?.takeIf { it > 0f },
        ).takeIf { it.coverUrl != null || it.artistImageUrl != null || it.tempo != null }
    }.getOrNull()

    private fun query(track: TrackInfo): String = buildString {
        append("title=").append(Http.encode(track.title))
        append("&artist=").append(Http.encode(track.artist))
        if (track.album.isNotBlank()) append("&album=").append(Http.encode(track.album))
        if (track.durationMs > 0) append("&durationMs=").append(track.durationMs)
        track.spotifyTrackId?.let { append("&spotifyId=").append(Http.encode(it)) }
    }

    private fun headers(): Map<String, String> {
        val json = mapOf("Accept" to "application/json")
        val key = credentials.cacheServerKey?.trim()?.takeIf { it.isNotEmpty() } ?: return json
        return json + ("Authorization" to "Bearer $key")
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val CACHE_SIZE = 8
    }
}
