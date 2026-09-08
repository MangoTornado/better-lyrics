package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricLine
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.lyrics.model.Syllable
import com.betterlyrics.app.lyrics.model.inferEndTimes
import com.betterlyrics.app.lyrics.model.withInterludes
import com.betterlyrics.app.lyrics.parse.LrcParser
import com.betterlyrics.app.util.isRtlText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Musixmatch — the widest source of *richsync*, true per-word timings, for Western music.
 *
 * **It needs a token of your own now.** The anonymous token the desktop web player used to
 * hand out is no longer issued: `token.get` answers 200 with a row of zeros, and a request
 * carrying that gets lyrics for an unrelated song. So the provider still tries for one — in
 * case it starts working again — but reports itself unconfigured until either that succeeds
 * or the user pastes in a real one, rather than being asked on every track and quietly
 * returning nothing.
 *
 * It is an undocumented endpoint, so every step degrades: no token means no provider, a
 * token that stops working is dropped and re-fetched once, a match that does not look like
 * the track playing is refused, and a missing richsync falls back to line-synced subtitles
 * and then to plain lyrics.
 */
class MusixmatchProvider(private val credentials: ProviderCredentials) : LyricsProvider {

    override val id = "musixmatch"
    override val displayName = "Musixmatch"
    override val canBeWordSynced = true

    /**
     * Set once the anonymous endpoint has been asked and found to be useless.
     *
     * In memory rather than in preferences, so a launch after Musixmatch fixes their end
     * tries again. Until it is set the provider reports itself configured, or it would
     * never get the one request it needs to find out.
     */
    private var anonymousTokenDead = false

    /**
     * A token of the user's own, a cached anonymous one, or the benefit of the doubt.
     *
     * Deliberately not "always true": with the anonymous endpoint handing out a dead token,
     * claiming to be configured meant a request per track that could only fail — which is
     * exactly what "Musixmatch doesn't work" looked like from the outside.
     */
    override val isConfigured: Boolean
        get() = !credentials.musixmatchUserToken.isNullOrBlank() ||
            !credentials.musixmatchGuestToken.isNullOrBlank() ||
            !anonymousTokenDead

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            // A token the user pasted in from the desktop app covers far more of the
            // catalogue than the anonymous one, so it always wins when present.
            val userToken = credentials.musixmatchUserToken?.takeIf { it.isNotBlank() }
            var token = userToken
                ?: credentials.musixmatchGuestToken
                ?: obtainToken()?.also { credentials.musixmatchGuestToken = it }
                ?: run {
                    // Asked, and there is nothing usable to be had. Stop claiming to be a
                    // working source for the rest of this run.
                    anonymousTokenDead = true
                    return@withContext null
                }

            var macro = macroCall(request, token)
            if (macro == null && userToken == null) {
                // The cached anonymous token may have aged out; one retry with a fresh one.
                token = obtainToken() ?: run {
                    anonymousTokenDead = true
                    return@withContext null
                }
                credentials.musixmatchGuestToken = token
                macro = macroCall(request, token)
            }
            if (macro == null) return@withContext null

            documentFrom(macro, request, token)
        }

    private suspend fun obtainToken(): String? {
        val url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get" +
            "?app_id=web-desktop-app-v1.0&t=${System.currentTimeMillis()}"
        return Http.get(url, HEADERS) { body ->
            runCatching {
                val message = Json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
                val status = message?.get("header")?.jsonObject?.get("status_code")
                    ?.jsonPrimitive?.intOrNull
                if (status != 200) return@runCatching null
                message["body"]?.jsonObject?.get("user_token")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isUsableToken() }
            }.getOrNull()
        }
    }

    /**
     * Whether a token from `token.get` is worth keeping.
     *
     * The endpoint stopped issuing real anonymous tokens: it answers 200 with fifty-six
     * zeros. A request carrying one is accepted and returns lyrics for an unrelated song —
     * asking for Kenshi Yonezu's "Lemon" came back with Drake — so it is worse than no
     * token at all. Any token made of a single repeated character is rejected, which covers
     * the zeros, the old `UpgradeOnly…` placeholder and whatever comes next.
     */
    private fun String.isUsableToken(): Boolean {
        val value = trim()
        if (value.length < 8) return false
        if (value.startsWith("UpgradeOnly")) return false
        return value.toSet().size > 1
    }

    private suspend fun macroCall(request: LyricsRequest, token: String): JsonObject? {
        val url = buildString {
            append("https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get")
            append("?format=json&namespace=lyrics_richsynched&subtitle_format=mxm")
            append("&app_id=web-desktop-app-v1.0")
            append("&usertoken=").append(Http.encode(token))
            append("&q_track=").append(Http.encode(request.cleanTitle))
            append("&q_artist=").append(Http.encode(request.primaryArtist))
            append("&q_artists=").append(Http.encode(request.artist))
            if (request.album.isNotBlank()) {
                append("&q_album=").append(Http.encode(request.album))
            }
            if (request.durationSeconds > 0) {
                append("&q_duration=").append(request.durationSeconds)
                append("&f_subtitle_length=").append(request.durationSeconds)
            }
            append("&optional_calls=track.richsync")
        }
        return Http.get(url, HEADERS) { body ->
            runCatching {
                val message = Json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
                val status = message?.get("header")?.jsonObject?.get("status_code")
                    ?.jsonPrimitive?.intOrNull
                if (status != 200) return@runCatching null
                message["body"]?.jsonObject?.get("macro_calls")?.jsonObject
            }.getOrNull()
        }
    }

    private suspend fun documentFrom(
        macro: JsonObject,
        request: LyricsRequest,
        token: String,
    ): LyricsDocument? {
        val track = macro.call("matcher.track.get")?.get("track")?.jsonObject
        val trackId = track?.get("track_id")?.jsonPrimitive?.contentOrNull

        // Guard against Musixmatch matching a different song than the one playing.
        if (track != null) {
            val score = Matching.score(
                request,
                track["track_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                track["artist_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                (track["track_length"]?.jsonPrimitive?.intOrNull ?: 0) * 1000L,
            )
            if (score < Matching.MATCH_THRESHOLD) return null
        }

        // 1. Richsync — per-word timings.
        val richsyncBody = macro.call("track.richsync.get")
            ?.get("richsync")?.jsonObject?.get("richsync_body")?.jsonPrimitive?.contentOrNull
            ?: trackId?.let { fetchRichsync(it, token) }

        richsyncBody?.let { body ->
            parseRichsync(body, request.durationMs)?.let { lines ->
                return LyricsDocument(
                    kind = LyricsKind.SYLLABLE,
                    lines = lines,
                    providerName = displayName,
                    providerId = id,
                )
            }
        }

        // 2. Subtitles — line timings, in Musixmatch's own JSON shape.
        val subtitleBody = macro.call("track.subtitles.get")
            ?.get("subtitle_list")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("subtitle")?.jsonObject?.get("subtitle_body")
            ?.jsonPrimitive?.contentOrNull

        subtitleBody?.let { body ->
            parseMxmSubtitles(body, request.durationMs)?.let { lines ->
                return LyricsDocument(
                    kind = LyricsKind.LINE,
                    lines = lines,
                    providerName = displayName,
                    providerId = id,
                )
            }
            // Some responses put an LRC string here instead of JSON.
            LrcParser.toDocument(body, displayName, id, request.durationMs)?.let { return it }
        }

        // 3. Plain lyrics.
        val plain = macro.call("track.lyrics.get")
            ?.get("lyrics")?.jsonObject?.get("lyrics_body")?.jsonPrimitive?.contentOrNull
        return plain?.takeIf { it.isNotBlank() }?.let {
            LrcParser.plainToDocument(stripMusixmatchNotice(it), displayName, id)
        }
    }

    private suspend fun fetchRichsync(trackId: String, token: String): String? {
        val url = "https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get" +
            "?format=json&app_id=web-desktop-app-v1.0&usertoken=${Http.encode(token)}" +
            "&track_id=${Http.encode(trackId)}"
        return Http.get(url, HEADERS) { body ->
            runCatching {
                Json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
                    ?.get("body")?.jsonObject?.get("richsync")?.jsonObject
                    ?.get("richsync_body")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
    }

    /**
     * `[{"ts":1.2,"te":3.4,"x":"Hello world","l":[{"c":"Hello","o":0.0},{"c":" ","o":0.5}]}]`
     *
     * `ts`/`te` bound the line, `o` is each fragment's offset from `ts`. Whitespace
     * fragments are separators, not syllables.
     */
    private fun parseRichsync(body: String, trackDurationMs: Long): List<LyricLine>? {
        val array = runCatching { Json.parseToJsonElement(body).jsonArray }.getOrNull()
            ?: return null
        if (array.isEmpty()) return null

        val lines = ArrayList<LyricLine>(array.size)
        for (element in array) {
            val entry = runCatching { element.jsonObject }.getOrNull() ?: continue
            val ts = entry["ts"]?.jsonPrimitive?.doubleOrNull ?: continue
            val te = entry["te"]?.jsonPrimitive?.doubleOrNull ?: ts
            val fullText = entry["x"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val fragments = runCatching { entry["l"]?.jsonArray }.getOrNull()

            val lineStart = (ts * 1000).toInt()
            val lineEnd = (te * 1000).toInt()

            if (fragments == null || fragments.isEmpty()) {
                if (fullText.isEmpty()) continue
                lines += LyricLine(
                    role = LineRole.LEAD,
                    startMs = lineStart,
                    endMs = lineEnd,
                    text = fullText,
                    rtl = fullText.isRtlText(),
                )
                continue
            }

            // Collect (text, startMs) first so each fragment can be closed at the
            // next one's start.
            val raw = ArrayList<Pair<String, Int>>(fragments.size)
            for (fragment in fragments) {
                val obj = runCatching { fragment.jsonObject }.getOrNull() ?: continue
                val chars = obj["c"]?.jsonPrimitive?.contentOrNull ?: continue
                val offset = obj["o"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                raw += chars to (ts * 1000 + offset * 1000).toInt()
            }

            val syllables = ArrayList<Syllable>(raw.size)
            val text = StringBuilder()
            var previousEndedWithSpace = true
            for ((index, pair) in raw.withIndex()) {
                val (chars, start) = pair
                val trimmed = chars.trim()
                if (trimmed.isEmpty()) {
                    previousEndedWithSpace = true
                    continue
                }
                val end = raw.getOrNull(index + 1)?.second ?: lineEnd
                val partOfWord = !previousEndedWithSpace && !chars.startsWith(" ")
                syllables += Syllable(
                    text = trimmed,
                    startMs = start,
                    endMs = end.coerceAtLeast(start),
                    partOfWord = partOfWord,
                )
                if (!partOfWord && text.isNotEmpty()) text.append(' ')
                text.append(trimmed)
                previousEndedWithSpace = chars.endsWith(" ")
            }

            if (syllables.isEmpty()) continue
            lines += LyricLine(
                role = LineRole.LEAD,
                startMs = minOf(lineStart, syllables.first().startMs),
                endMs = maxOf(lineEnd, syllables.last().endMs),
                text = text.toString().ifEmpty { fullText },
                syllables = syllables,
                rtl = text.toString().isRtlText(),
            )
        }

        if (lines.isEmpty()) return null
        return lines.sortedBy { it.startMs }.inferEndTimes(trackDurationMs).withInterludes()
    }

    /** `[{"text":"line","time":{"total":12.34}}]` */
    private fun parseMxmSubtitles(body: String, trackDurationMs: Long): List<LyricLine>? {
        val array = runCatching { Json.parseToJsonElement(body).jsonArray }.getOrNull()
            ?: return null
        if (array.isEmpty()) return null

        val lines = ArrayList<LyricLine>(array.size)
        for (element in array) {
            val entry = runCatching { element.jsonObject }.getOrNull() ?: continue
            val text = entry["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val total = entry["time"]?.jsonObject?.get("total")?.jsonPrimitive?.floatOrNull
                ?: continue
            if (text.isEmpty()) continue
            lines += LyricLine(
                role = LineRole.LEAD,
                startMs = (total * 1000).toInt(),
                endMs = 0,
                text = text,
                rtl = text.isRtlText(),
            )
        }
        if (lines.isEmpty()) return null
        return lines.sortedBy { it.startMs }.inferEndTimes(trackDurationMs).withInterludes()
    }

    /**
     * Remove the trailer Musixmatch appends to an unlicensed body.
     *
     * The old version cut at the first `...`, which took the rest of any song containing an
     * ordinary ellipsis with it — a common thing in a lyric. Only the notice itself is
     * removed now, and the `...` only when it is the truncation marker: on its own line, at
     * the end.
     */
    private fun stripMusixmatchNotice(body: String): String =
        body.substringBefore("******* This Lyrics is NOT")
            .trimEnd()
            .removeSuffix("...")
            .trimEnd()

    private fun JsonObject.call(name: String): JsonObject? = runCatching {
        get(name)?.jsonObject?.get("message")?.jsonObject?.get("body")?.asObjectOrNull()
    }.getOrNull()

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private companion object {
        val HEADERS = mapOf(
            "authority" to "apic-desktop.musixmatch.com",
            "Cookie" to "x-mxm-token-guid=",
            "User-Agent" to
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Accept" to "application/json",
        )
    }
}
