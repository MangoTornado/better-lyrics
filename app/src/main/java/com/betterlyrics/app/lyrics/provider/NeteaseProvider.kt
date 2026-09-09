package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricLine
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.lyrics.model.inferEndTimes
import com.betterlyrics.app.lyrics.model.withInterludes
import com.betterlyrics.app.lyrics.parse.LrcParser
import com.betterlyrics.app.lyrics.parse.YrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * NetEase Cloud Music.
 *
 * Worth its own provider because of what it carries for East Asian music that almost
 * nothing else does, all in one response:
 *
 * - `yrc` / `klyric` — per-word timings (karaoke)
 * - `romalrc`        — a hand-checked romanization, better than any transliterator
 * - `tlyric`         — a hand-written translation
 *
 * For a Japanese or Korean track this is usually the best result available anywhere,
 * which is why it sits above the generic databases for CJK titles.
 */
class NeteaseProvider(private val credentials: ProviderCredentials) : LyricsProvider {

    override val id = "netease"
    override val displayName = "NetEase Cloud Music"
    override val canBeWordSynced = true

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            val songId = search(request) ?: return@withContext null
            val document = lyricsFor(songId, request)
            if (document == null && noMatchReason == null) {
                noMatchReason = "Matched the track, but NetEase has no lyrics stored for it"
            }
            document
        }

    @Volatile
    override var noMatchReason: String? = null
        private set

    /** Returns the NetEase song id of the best match, or null. */
    private suspend fun search(request: LyricsRequest): Long? {
        val queries = buildList {
            if (request.artist.isNotBlank()) add("${request.title} ${request.primaryArtist}")
            add(request.title)
            if (request.cleanTitle != request.title) {
                add("${request.cleanTitle} ${request.primaryArtist}".trim())
            }
        }.distinct()

        // Enough to tell three failures apart afterwards: nothing came back at all, results came
        // back but none matched, or the endpoint answered with something unreadable.
        var reachedEndpoint = false
        var candidates = 0
        var bestOverall = 0f
        var bestTitle: String? = null

        for (query in queries) {
            // `/api/search/get`, not `/api/search/get/web`. The `/web` variant now answers
            // with `{"result": "<hex>"}` — an encrypted blob rather than the song list it
            // used to return — so every NetEase lookup failed at the first step and the
            // provider looked simply broken. The plain endpoint still returns readable
            // JSON with the same field names.
            val url = credentials.neteaseBaseUrl + "/api/search/get" +
                "?s=${Http.encode(query)}&type=1&offset=0&total=true&limit=12"

            val songs = Http.get(url, headers()) { body ->
                runCatching {
                    // A string here rather than an object means the encrypted shape came
                    // back anyway; treat it as no result rather than crashing on it.
                    Json.parseToJsonElement(body).jsonObject["result"]
                        ?.jsonObject?.get("songs")?.jsonArray
                }.getOrNull()
            }
            if (songs == null) continue
            reachedEndpoint = true
            candidates += songs.size

            var bestId: Long? = null
            var bestScore = 0f
            for (element in songs) {
                val song = runCatching { element.jsonObject }.getOrNull() ?: continue
                val id = song["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val name = song["name"]?.jsonPrimitive?.content.orEmpty()
                val artists = runCatching {
                    song["artists"]?.jsonArray?.mapNotNull {
                        it.jsonObject["name"]?.jsonPrimitive?.content
                    }
                }.getOrNull().orEmpty()
                val duration = song["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

                val score = Matching.score(request, name, artists.joinToString(", "), duration)
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                }
                // `bestTitle == null` as well as a higher score, because a score of exactly zero is
                // now reachable: a candidate whose artist contradicts the request is vetoed outright
                // rather than scored down. With `score > bestOverall` alone, every candidate being
                // rejected left the diagnostic reading `closest "null" at 0%` — which is the one
                // case where naming the candidate matters most, since seeing the wrong artist in it
                // is what explains the rejection.
                if (bestTitle == null || score > bestOverall) {
                    bestOverall = score
                    bestTitle = if (artists.isEmpty()) name else "$name — ${artists.first()}"
                }
            }
            if (bestId != null && bestScore >= Matching.MATCH_THRESHOLD) return bestId
        }

        // Which of these it is decides what to do about it, and they are not otherwise
        // distinguishable from the outside.
        noMatchReason = when {
            // Not the same as unreachable: `Http.get` throws for that, and the source test prints
            // the exception. This is the endpoint answering with a 404 or an empty body — which is
            // also what the encrypted `/web` variant looks like from here.
            !reachedEndpoint -> "NetEase answered with nothing readable"
            candidates == 0 -> "NetEase has no results for this title"
            else ->
                "$candidates results, closest \"$bestTitle\" at " +
                    "${(bestOverall * 100).toInt()}% — under the " +
                    "${(Matching.MATCH_THRESHOLD * 100).toInt()}% needed to be sure it is the " +
                    "same recording"
        }
        return null
    }

    private suspend fun lyricsFor(songId: Long, request: LyricsRequest): LyricsDocument? {
        // Asking for every variant at once: lv=lyric, kv=karaoke, tv=translation,
        // rv=romanization, yv=word-timed. Servers that don't know a key ignore it.
        val url = credentials.neteaseBaseUrl + "/api/song/lyric" +
            "?id=$songId&lv=-1&kv=-1&tv=-1&rv=-1&yv=-1&ytv=-1&yrv=-1"

        val root = Http.get(url, headers()) { body ->
            runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        } ?: return null

        fun lyric(key: String): String? = runCatching {
            root[key]?.jsonObject?.get("lyric")?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val yrc = lyric("yrc") ?: lyric("klyric")
        val lrc = lyric("lrc")
        // Word-timed responses carry their own translation/romanization tracks.
        val translation = lyric("ytlrc") ?: lyric("tlyric")
        val romanization = lyric("yromalrc") ?: lyric("romalrc")

        var lines: List<LyricLine>
        var kind: LyricsKind

        val wordTimed = yrc?.takeIf { YrcParser.looksLikeYrc(it) }?.let { YrcParser.parse(it) }
        if (!wordTimed.isNullOrEmpty()) {
            lines = wordTimed
            kind = LyricsKind.SYLLABLE
        } else {
            val parsed = lrc?.let { LrcParser.parse(it, request.durationMs) } ?: return null
            if (parsed.lines.isEmpty()) return null
            // withInterludes() runs again at the end; strip the markers this parse added
            // so translation matching only sees real vocal lines.
            lines = parsed.lines.filter { it.role != LineRole.INTERLUDE }
            kind = parsed.kind
        }

        if (lines.isEmpty()) return null

        var hasTranslation = false
        translation?.let { track ->
            val merged = LrcParser.mergeAlternateTrack(lines, track) { line, text ->
                line.copy(translated = text)
            }
            if (merged.any { it.translated != null }) {
                lines = merged
                hasTranslation = true
            }
        }

        var hasRomanization = false
        romanization?.let { track ->
            val merged = LrcParser.mergeAlternateTrack(lines, track) { line, text ->
                line.copy(romanized = text)
            }
            if (merged.any { it.romanized != null }) {
                lines = merged
                hasRomanization = true
            }
        }

        val finished = lines.inferEndTimes(request.durationMs).withInterludes()

        return LyricsDocument(
            kind = kind,
            lines = finished,
            providerName = displayName,
            providerId = id,
            hasRomanization = hasRomanization,
            hasTranslation = hasTranslation,
        )
    }

    /**
     * NetEase rejects requests that do not look like they came from its own web player,
     * and applies tighter per-IP limits without a session cookie — so an optional one
     * from Settings is merged in when present.
     */
    private fun headers(): Map<String, String> = mapOf(
        "Referer" to "${credentials.neteaseBaseUrl}/",
        "Origin" to credentials.neteaseBaseUrl,
        "Cookie" to listOfNotNull(
            "appver=8.9.70; os=pc",
            credentials.neteaseCookie?.takeIf { it.isNotBlank() },
        ).joinToString("; "),
        "User-Agent" to WEB_USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
    )

    private companion object {
        val WEB_USER_AGENT = SpotifyWebToken.WEB_USER_AGENT
    }
}
