package com.betterlyrics.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
 * Artwork and tempo through your own cache server, in both directions.
 *
 * The point is that the server outlives the tokens. A Spotify access token is good for an
 * hour and an Apple developer token for a few months; a cover art URL and a tempo, once
 * known, are true forever. So when a token does produce them, the app tells the server — and
 * from then on the server can answer for that track with no token at all, for this phone
 * after the token expires and for anything else pointed at it.
 *
 * Contributing sends only what was learned: the track's identity and the URLs. Never a
 * credential, and never the images themselves — the server fetches those itself, which is
 * both a smaller payload and the only way it ends up holding its own copy.
 */
class CacheServerExtras(private val credentials: ProviderCredentials) {

    private val cache = LinkedHashMap<String, CachedExtras?>()

    /** Tracks already contributed this run, so a replay does not re-send the same thing. */
    private val contributed = HashSet<String>()

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

    /**
     * Tell the server what a token turned up, so it does not need one next time.
     *
     * Best-effort in every direction: one attempt, no retry, failures logged and forgotten.
     * A cache that misses is a slower app; a cache that interrupts the app is a worse one.
     */
    suspend fun contribute(track: TrackInfo, extras: CachedExtras, source: String) {
        val base = base() ?: return
        if (track.isEmpty) return
        if (extras.coverUrl == null && extras.artistImageUrl == null && extras.tempo == null) {
            return
        }
        if (!contributed.add(track.cacheKey)) return

        val payload = buildString {
            append('{')
            append("\"title\":").append(quote(track.title))
            append(",\"artist\":").append(quote(track.artist))
            if (track.album.isNotBlank()) append(",\"album\":").append(quote(track.album))
            if (track.durationMs > 0) append(",\"durationMs\":").append(track.durationMs)
            track.spotifyTrackId?.let { append(",\"spotifyId\":").append(quote(it)) }
            extras.coverUrl?.let { append(",\"coverUrl\":").append(quote(it)) }
            extras.artistImageUrl?.let { append(",\"artistImageUrl\":").append(quote(it)) }
            extras.tempo?.let { append(",\"tempo\":").append(it) }
            append(",\"source\":").append(quote(source))
            append('}')
        }

        val accepted = runCatching {
            Http.post("$base/v1/extras", payload, headers())
        }.getOrDefault(false)
        if (!accepted) Log.d(TAG, "cache server did not take the extras for ${track.title}")
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

    /** Minimal JSON string escaping. The fields here are titles and URLs, not arbitrary data. */
    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private companion object {
        const val TAG = "CacheServerExtras"
        const val CACHE_SIZE = 8
    }
}
