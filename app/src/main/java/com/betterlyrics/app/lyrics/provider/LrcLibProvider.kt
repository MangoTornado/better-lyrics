package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.parse.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * LRCLIB — an open, key-less, community-run LRC database.
 *
 * The dependable baseline: no account, no scraping, no rate limit worth worrying
 * about. Line-synced (or plain) only, so a word-synced provider will outrank it when
 * one has the track.
 */
class LrcLibProvider(private val credentials: ProviderCredentials) : LyricsProvider {

    override val id = "lrclib"
    override val displayName = "LRCLIB"

    @Serializable
    private data class Entry(
        val id: Int = 0,
        val trackName: String = "",
        val artistName: String = "",
        val albumName: String = "",
        val duration: Double = 0.0,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    ) {
        val durationMs: Long get() = (duration * 1000).toLong()
    }

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            // The signature endpoint is an exact match on all four fields and is by far
            // the most accurate, so try it with the raw title first, then the cleaned one.
            exactGet(request, request.title)
                ?: exactGet(request, request.cleanTitle).takeIf { request.cleanTitle != request.title }
                ?: search(request)
        }

    private suspend fun exactGet(request: LyricsRequest, title: String): LyricsDocument? {
        if (title.isBlank() || request.artist.isBlank()) return null
        val url = buildString {
            append(credentials.lrcLibBaseUrl).append("/api/get")
            append("?track_name=").append(Http.encode(title))
            append("&artist_name=").append(Http.encode(request.artist))
            if (request.album.isNotBlank()) {
                append("&album_name=").append(Http.encode(request.album))
            }
            if (request.durationSeconds > 0) {
                append("&duration=").append(request.durationSeconds)
            }
        }
        val entry = Http.get(url, HEADERS) { body ->
            runCatching { Http.json.decodeFromString<Entry>(body) }.getOrNull()
        } ?: return null
        return entry.toDocument(request)
    }

    private suspend fun search(request: LyricsRequest): LyricsDocument? {
        val url = buildString {
            append(credentials.lrcLibBaseUrl).append("/api/search")
            append("?track_name=").append(Http.encode(request.cleanTitle))
            if (request.primaryArtist.isNotBlank()) {
                append("&artist_name=").append(Http.encode(request.primaryArtist))
            }
        }
        val entries = Http.get(url, HEADERS) { body ->
            runCatching { Http.json.decodeFromString<List<Entry>>(body) }.getOrNull()
        }.orEmpty()
        if (entries.isEmpty()) return null

        val best = entries
            .map { it to Matching.score(request, it.trackName, it.artistName, it.durationMs) }
            .filter { (entry, score) ->
                score >= Matching.MATCH_THRESHOLD &&
                    (entry.syncedLyrics != null || entry.plainLyrics != null)
            }
            // Prefer a synced result even if a plain one scored a touch higher.
            .sortedWith(
                compareByDescending<Pair<Entry, Float>> { it.first.syncedLyrics != null }
                    .thenByDescending { it.second },
            )
            .firstOrNull()?.first
            ?: return null

        return best.toDocument(request)
    }

    private fun Entry.toDocument(request: LyricsRequest): LyricsDocument? {
        if (instrumental) return null
        // `id` on Entry is LRCLIB's row id, so name the provider's own id explicitly.
        val providerId = this@LrcLibProvider.id
        syncedLyrics?.takeIf { it.isNotBlank() }?.let { lrc ->
            LrcParser.toDocument(lrc, displayName, providerId, request.durationMs)
                ?.let { return it }
        }
        return plainLyrics?.takeIf { it.isNotBlank() }?.let {
            LrcParser.plainToDocument(it, displayName, providerId)
        }
    }

    private companion object {
        // LRCLIB asks clients to identify themselves.
        val HEADERS = mapOf(
            "Lrclib-Client" to "BetterLyrics/0.1.0 (Android)",
            "Accept" to "application/json",
        )
    }
}
