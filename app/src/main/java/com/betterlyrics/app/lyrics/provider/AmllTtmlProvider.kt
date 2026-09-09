package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.parse.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The AMLL TTML Database — community-made, word-by-word, and free.
 *
 * This is the best answer in the app to "karaoke lyrics with no account": a public corpus
 * of hand-timed TTML, released under **CC0**, indexed by Spotify, Apple Music, NetEase, QQ
 * and ISRC, served by a documented API that can also be self-hosted. The files carry the
 * same syllable timings, duet agents, background vocals, romanizations and translations
 * Apple's own lyrics do, because that is the format they are authored in.
 *
 * Three lookups, in order of confidence:
 *
 * 1. **By ISRC**, when one has been learned. The strongest identity there is: it names the
 *    recording itself, independently of any service, and the database indexes on it.
 * 2. **By Spotify track id**, when the media session gave us one. Also exact — no title
 *    guessing, no wrong-song risk.
 * 3. **By title and artist**, scored like every other search provider, for everything else.
 *
 * https://github.com/amll-dev/amll-ttml-db · https://amll.dev
 */
class AmllTtmlProvider(private val credentials: ProviderCredentials) : LyricsProvider {

    override val id = "amll"
    override val displayName = "AMLL TTML DB"
    override val canBeWordSynced = true

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            val base = credentials.amllBaseUrl.trimEnd('/')

            /** Report the identity before returning, whatever the lyrics turn out to be. */
            fun Entry.reported(): Entry = also { entry ->
                entry.isrcs.firstOrNull { it.isNotBlank() }
                    ?.let { request.onIsrc?.invoke(it) }
            }

            request.isrc
                ?.takeIf { it.isNotBlank() }
                ?.let { get(base, "isrc=${Http.encode(it)}") }
                ?.let { return@withContext it.reported().toDocument() }

            request.spotifyTrackId
                ?.let { get(base, "spotifyId=${Http.encode(it)}") }
                ?.let { return@withContext it.reported().toDocument() }

            searchThenGet(base, request)?.reported()?.toDocument()
        }

    /** `/v1/lyrics/get` returns the metadata and the whole TTML in one response. */
    private suspend fun get(base: String, query: String): Entry? =
        Http.get("$base/v1/lyrics/get?$query", HEADERS) { body ->
            runCatching {
                val root = Json.parseToJsonElement(body).jsonObject
                root["data"]?.jsonObject?.let(::entryOf)
            }.getOrNull()
        }

    /**
     * Search, score, then fetch the winner by its id.
     *
     * The search endpoint deliberately omits the lyrics themselves, so this is two round
     * trips — but only for tracks that are not playing from Spotify.
     */
    private suspend fun searchThenGet(base: String, request: LyricsRequest): Entry? {
        val queries = buildList {
            if (request.primaryArtist.isNotBlank()) {
                add(
                    "musicName=${Http.encode(request.cleanTitle)}" +
                        "&artistName=${Http.encode(request.primaryArtist)}",
                )
            }
            add("musicName=${Http.encode(request.cleanTitle)}")
        }

        for (query in queries) {
            val items = Http.get("$base/v1/lyrics/search?$query&pageSize=20", HEADERS) { body ->
                runCatching {
                    Json.parseToJsonElement(body).jsonObject["data"]
                        ?.jsonObject?.get("items")?.jsonArray
                        ?.mapNotNull { runCatching { entryOf(it.jsonObject) }.getOrNull() }
                }.getOrNull()
            }.orEmpty()
            if (items.isEmpty()) continue

            // The corpus has no durations, so scoring leans on title and artist. Each
            // entry can carry several names (alternate titles, every credited artist), and
            // the best of them counts.
            val best = items
                .map { entry -> entry to entry.score(request) }
                .filter { it.second >= Matching.MATCH_THRESHOLD }
                .maxByOrNull { it.second }
                ?.first
                ?: continue

            return get(base, "id=${best.id}") ?: best.takeIf { it.lyrics != null }
        }
        return null
    }

    private class Entry(
        val id: Long,
        val musicNames: List<String>,
        val artistNames: List<String>,
        val authors: List<String>,
        /**
         * The recording's ISRC, which this database carries for nearly everything it holds.
         *
         * The reason it matters here rather than anywhere else: this is the only source that gives
         * one away with no token whatsoever. A phone with nothing configured still learns the
         * identity of every track it finds lyrics for, and every later lookup of that track — by
         * Apple, or by this database again — becomes exact.
         */
        val isrcs: List<String>,
        val lyrics: String?,
    ) {
        fun score(request: LyricsRequest): Float {
            val titles = musicNames.ifEmpty { listOf("") }
            val artists = artistNames.ifEmpty { listOf("") }
            var best = 0f
            for (title in titles) {
                for (artist in artists) {
                    // Duration is unknown here, so pass 0 — Matching treats that as neutral
                    // rather than as a mismatch.
                    best = maxOf(best, Matching.score(request, title, artist, 0L))
                }
            }
            return best
        }

        fun toDocument(): LyricsDocument? {
            val xml = lyrics?.takeIf { it.isNotBlank() } ?: return null
            // Contributors are credited by name, the way the database asks people to be —
            // somebody hand-timed this.
            val label = when {
                authors.isEmpty() -> "AMLL TTML DB"
                else -> "AMLL TTML DB · by ${authors.joinToString(", ")}"
            }
            return TtmlParser.parse(xml, label, "amll")
        }
    }

    private fun entryOf(data: JsonObject): Entry = Entry(
        id = data["id"]?.jsonPrimitive?.longOrNull ?: 0L,
        musicNames = data.strings("musicNames"),
        artistNames = data.strings("artistNames"),
        authors = data.strings("authorUsernames"),
        isrcs = data.strings("isrcs"),
        lyrics = data["lyrics"]?.jsonPrimitive?.contentOrNull,
    )

    private fun JsonObject.strings(key: String): List<String> = runCatching {
        get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
    }.getOrNull().orEmpty()

    private companion object {
        val HEADERS = mapOf("Accept" to "application/json")
    }
}
