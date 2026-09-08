package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.parse.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Apple Music — the best lyrics data that exists, when you have access to it.
 *
 * Its `syllable-lyrics` endpoint returns the same TTML that drives Apple's own karaoke
 * view: per-syllable timings, duet agents, background vocals, and — the part nothing
 * else offers officially — hand-made romanizations and translations attached to the
 * lines they belong to. That TTML goes straight into [TtmlParser], which was written for
 * this dialect.
 *
 * It needs two things the user has to supply, because Apple issues them to its own web
 * player rather than to third parties:
 *
 * - a **developer token** (a JWT), and
 * - a **music user token**, which requires an active subscription.
 *
 * Without both, the provider stays dormant.
 */
class AppleMusicProvider(private val credentials: ProviderCredentials) : LyricsProvider {

    override val id = "applemusic"
    override val displayName = "Apple Music"
    override val canBeWordSynced = true

    override val isConfigured: Boolean
        get() = !credentials.appleDeveloperToken.isNullOrBlank() &&
            !credentials.appleMusicUserToken.isNullOrBlank()

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext null
            val songId = search(request) ?: return@withContext null

            // Syllable first — it is the whole reason to come here. The line-level
            // endpoint is the fallback for tracks Apple has not timed by word.
            ttml(songId, "syllable-lyrics")?.let { xml ->
                TtmlParser.parse(xml, displayName, id)?.let { return@withContext it }
            }
            ttml(songId, "lyrics")?.let { xml ->
                TtmlParser.parse(xml, displayName, id)?.let { return@withContext it }
            }
            null
        }

    private suspend fun search(request: LyricsRequest): String? {
        val storefront = credentials.appleStorefront.ifBlank { "us" }

        // An ISRC names the recording itself, so Apple can be asked for exactly it rather than
        // for something with a similar title. No scoring needed, and no wrong-song risk.
        request.isrc?.takeIf { it.isNotBlank() }?.let { isrc ->
            val url = "$API_BASE/v1/catalog/$storefront/songs" +
                "?filter[isrc]=${Http.encode(isrc)}&limit=1"
            Http.get(url, headers()) { body ->
                runCatching {
                    Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("id")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }?.let { return it }
        }

        val queries = buildList {
            if (request.artist.isNotBlank()) add("${request.title} ${request.primaryArtist}")
            if (request.cleanTitle != request.title) {
                add("${request.cleanTitle} ${request.primaryArtist}".trim())
            }
            add(request.title)
        }.distinct()

        for (query in queries) {
            val url = "$API_BASE/v1/catalog/$storefront/search" +
                "?term=${Http.encode(query)}&types=songs&limit=10"

            val songs = Http.get(url, headers()) { body ->
                runCatching {
                    Json.parseToJsonElement(body).jsonObject["results"]
                        ?.jsonObject?.get("songs")
                        ?.jsonObject?.get("data")?.jsonArray
                }.getOrNull()
            } ?: continue

            var bestId: String? = null
            var bestScore = 0f
            for (element in songs) {
                val song = runCatching { element.jsonObject }.getOrNull() ?: continue
                val songId = song["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val attributes = song["attributes"]?.jsonObject ?: continue
                val name = attributes["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val artist = attributes["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val duration = attributes["durationInMillis"]?.jsonPrimitive?.longOrNull
                    ?: (attributes["durationInMillis"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L)

                val score = Matching.score(request, name, artist, duration)
                if (score > bestScore) {
                    bestScore = score
                    bestId = songId
                }
            }
            if (bestId != null && bestScore >= Matching.MATCH_THRESHOLD) return bestId
        }
        return null
    }

    private suspend fun ttml(songId: String, relationship: String): String? {
        val storefront = credentials.appleStorefront.ifBlank { "us" }
        val url = "$API_BASE/v1/catalog/$storefront/songs/$songId/$relationship"
        return Http.get(url, headers()) { body ->
            runCatching {
                Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("attributes")?.jsonObject
                    ?.get("ttml")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }

    private fun headers(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${credentials.appleDeveloperToken.orEmpty()}",
        "Media-User-Token" to credentials.appleMusicUserToken.orEmpty(),
        "Origin" to "https://music.apple.com",
        "Referer" to "https://music.apple.com/",
        "User-Agent" to SpotifyWebToken.WEB_USER_AGENT,
        "Accept" to "application/json",
    )

    private companion object {
        const val API_BASE = "https://amp-api.music.apple.com"
    }
}
