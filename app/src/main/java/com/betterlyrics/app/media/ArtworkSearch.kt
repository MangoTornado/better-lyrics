package com.betterlyrics.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.betterlyrics.app.lyrics.provider.Http
import com.betterlyrics.app.settings.ArtworkSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bigger cover art than the player published, from somewhere that needs no account.
 *
 * A media session's artwork is often 300px or smaller — fine for a notification, visibly
 * soft behind full-screen lyrics. Both sources here are public and keyless, which is the
 * point: this has to work for someone who has set nothing up.
 *
 * Only ever a supplement. The player's own artwork is the truth about what is playing, so it
 * is preferred whenever it is large enough, and a search result is used only to replace one
 * that is too small to look at.
 */
class ArtworkSearch {

    /** Below this on the short edge, a cover is worth trying to improve on. */
    private val smallEnoughToReplace = 500

    private val cache = LinkedHashMap<String, Bitmap?>()

    fun wouldImproveOn(existing: Bitmap?): Boolean =
        existing == null || minOf(existing.width, existing.height) < smallEnoughToReplace

    /**
     * Look for a cover for [track]. Null when nothing convincing turns up.
     *
     * Matching is by album where there is one, because that is what cover art belongs to —
     * two different singles by the same artist share neither an album nor a cover, but a
     * track title alone matches far too much.
     */
    suspend fun find(track: TrackInfo, source: ArtworkSource): Bitmap? =
        withContext(Dispatchers.IO) {
            if (source == ArtworkSource.PLAYER) return@withContext null
            val key = "${source.name}|${track.cacheKey}"
            if (cache.containsKey(key)) return@withContext cache[key]

            val url = when (source) {
                ArtworkSource.ITUNES -> itunesUrl(track)
                ArtworkSource.COVER_ART_ARCHIVE -> coverArtArchiveUrl(track)
                ArtworkSource.PLAYER -> null
            }
            val bitmap = url?.let { load(it) }

            // Remembered either way: a track with no cover anywhere must not be searched for
            // again every time the screen redraws.
            if (cache.size >= CACHE_SIZE) cache.keys.firstOrNull()?.let(cache::remove)
            cache[key] = bitmap
            bitmap
        }

    /**
     * Apple's public search. One request, and the artwork URL carries its own size.
     *
     * The `100x100bb` in the returned URL is a resizing instruction rather than a fact about
     * the file, so asking for 1000 gets 1000 — a trick the endpoint has supported for as long
     * as it has existed.
     */
    private suspend fun itunesUrl(track: TrackInfo): String? {
        val term = listOfNotNull(
            track.artist.takeIf { it.isNotBlank() },
            track.album.takeIf { it.isNotBlank() } ?: track.title.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { return null }

        val entity = if (track.album.isNotBlank()) "album" else "song"
        val url = "https://itunes.apple.com/search?term=${Http.encode(term)}" +
            "&entity=$entity&limit=1"

        return Http.get(url, ACCEPT_JSON) { body ->
            runCatching {
                Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("artworkUrl100")?.jsonPrimitive?.contentOrNull
                    ?.replace("100x100bb", "1000x1000bb")
            }.getOrNull()
        }
    }

    /**
     * MusicBrainz for the release, then the Cover Art Archive for its front cover.
     *
     * Two requests and slower than iTunes, but community-run and comprehensive in places
     * Apple is not. MusicBrainz asks callers to identify themselves, which the shared User-Agent
     * does, and to keep to one request a second — the per-track cache above is what makes that
     * true in practice.
     */
    private suspend fun coverArtArchiveUrl(track: TrackInfo): String? {
        val query = buildList {
            track.album.takeIf { it.isNotBlank() }?.let { add("release:\"$it\"") }
            track.title.takeIf { it.isNotBlank() && track.album.isBlank() }
                ?.let { add("recording:\"$it\"") }
            track.artist.takeIf { it.isNotBlank() }?.let { add("artist:\"$it\"") }
        }.joinToString(" AND ").ifBlank { return null }

        val url = "https://musicbrainz.org/ws/2/release?query=${Http.encode(query)}" +
            "&fmt=json&limit=1"

        val id = Http.get(url, ACCEPT_JSON) { body ->
            runCatching {
                Json.parseToJsonElement(body).jsonObject["releases"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    // Below this the match is a guess, and a wrong cover is worse than a
                    // soft one.
                    ?.takeIf { (it["score"]?.jsonPrimitive?.intOrNull ?: 0) >= 80 }
                    ?.get("id")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        } ?: return null

        return "https://coverartarchive.org/release/$id/front-1200"
    }

    private fun load(url: String): Bitmap? = runCatching {
        Http.client.newCall(Http.request(url)).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }.getOrNull()

    private companion object {
        val ACCEPT_JSON = mapOf("Accept" to "application/json")
        const val CACHE_SIZE = 8
    }
}
