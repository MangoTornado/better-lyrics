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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read the server's answer.
 *
 * Top-level so it can be tested against a response a real server actually sent — the two sides
 * of this contract live in different repositories, and a field renamed on one of them is
 * indistinguishable from a track the server has never seen.
 *
 * Tolerant by design: `cover` and `artistImage` are accepted as aliases, and the fields may sit
 * at the top level or inside a `data` wrapper. The server also returns `isrc`, `palette`,
 * `analysis` and `metadata`, which nothing here reads yet — ignoring an unknown field has to be
 * free, or every addition on the server becomes a breaking change.
 */
internal fun parseCachedExtras(body: String): CachedExtras? = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    val data = root["data"]?.jsonObject ?: root
    CachedExtras(
        coverUrl = data.extrasString("coverUrl") ?: data.extrasString("cover"),
        artistImageUrl = data.extrasString("artistImageUrl") ?: data.extrasString("artistImage"),
        tempo = data["tempo"]?.jsonPrimitive?.floatOrNull?.takeIf { it > 0f },
        isrc = data.extrasString("isrc"),
    ).takeIf {
        it.coverUrl != null || it.artistImageUrl != null || it.tempo != null || it.isrc != null
    }
}.getOrNull()

private fun kotlinx.serialization.json.JsonObject.extrasString(key: String): String? =
    runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
        ?.takeIf { it.isNotBlank() }

/** One of the server's sources, as the server describes itself. */
data class ServerSource(
    val id: String,
    val name: String,
    val ok: Boolean,
    val detail: String,
    val ms: Int? = null,
)

/**
 * Read `GET /v1/status`.
 *
 * A source the app does not recognise is still shown: the server may have one this build has
 * never heard of, and the point of the screen is to report what is there.
 */
internal fun parseServerStatus(body: String): List<ServerSource>? = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    val sources = (root["data"]?.jsonObject ?: root)["sources"]?.jsonArray ?: return@runCatching null
    sources.mapNotNull { element ->
        val source = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val id = source.extrasString("id") ?: return@mapNotNull null
        ServerSource(
            id = id,
            name = source.extrasString("name") ?: id,
            ok = source["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
            detail = source.extrasString("detail") ?: "",
            ms = source["ms"]?.jsonPrimitive?.intOrNull,
        )
    }.takeIf { it.isNotEmpty() }
}.getOrNull()

/** What a cache server knows about a track besides its words. */
data class CachedExtras(
    val coverUrl: String? = null,
    val artistImageUrl: String? = null,
    val tempo: Float? = null,
    /**
     * The recording's ISRC, if the server has learned one.
     *
     * The most valuable field here by a distance, and the only one that needs no token to be
     * useful: it turns the next lookup of this track into an exact match rather than a guess
     * between similar titles.
     */
    val isrc: String? = null,
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

    /**
     * What the server says it can currently do, source by source.
     *
     * The app's own "test the sources" cannot answer this. Pointing it at a server puts every
     * source behind one hop, and "the server returned no lyrics" covers a source switched off,
     * a token that expired last week, and a track nobody has transcribed. Those need telling
     * apart, and only the server can tell them.
     *
     * No credential comes back, by design on both sides — only whether one works.
     */
    suspend fun status(): List<ServerSource>? = withContext(Dispatchers.IO) {
        val base = base() ?: return@withContext null
        runCatching {
            Http.get("$base/v1/status", headers()) { body -> parseServerStatus(body) }
        }.getOrNull()
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

    private fun parse(body: String): CachedExtras? = parseCachedExtras(body)

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


    private companion object {
        const val CACHE_SIZE = 8
    }
}
