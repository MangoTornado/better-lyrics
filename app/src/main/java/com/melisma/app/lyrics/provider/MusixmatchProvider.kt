package com.melisma.app.lyrics.provider

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.lyrics.model.inferEndTimes
import com.melisma.app.lyrics.model.withInterludes
import com.melisma.app.lyrics.parse.LrcParser
import com.melisma.app.util.isRtlText
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

/** A Musixmatch token and the client id it was issued for. Neither works without the other. */
data class Credential(val token: String, val appId: String)

/**
 * Whether a token from `token.get` is worth keeping.
 *
 * The discontinued desktop endpoint answers 200 with fifty-six zeros, and a request carrying
 * that is accepted and returns lyrics for an unrelated song — asking for Kenshi Yonezu's
 * "Lemon" came back with Drake. Worse than no token at all, so any token made of a single
 * repeated character is rejected: the zeros, the older `UpgradeOnly…` placeholder, and
 * whatever they do next.
 */
internal fun String.isUsableToken(): Boolean {
    val value = trim()
    if (value.length < 8) return false
    if (value.startsWith("UpgradeOnly")) return false
    return value.toSet().size > 1
}

/**
 * Read whatever the user pasted into the Musixmatch field.
 *
 * Accepts a bare token, or the `musixmatchUserToken` cookie from a signed-in
 * musixmatch.com session — on its own, URL-encoded or not, or inside a whole
 * `name=value; name=value` string. That cookie is what a person actually has to hand, and it
 * carries one token per Musixmatch client, of which only some are accepted by the lyrics
 * endpoint; picking the right one is this function's job rather than the user's.
 *
 * A bare token has no client id attached, so it is paired with the one the app uses. If that
 * guess is wrong the request fails and the caller falls back to an anonymous token, which is
 * why pasting the whole cookie is better: it says.
 */
internal fun parseMusixmatchCredential(raw: String?): Credential? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    // Decode before deciding what this is. A cookie copied out of a browser arrives
    // percent-encoded, and `%7B%22tokens%22…` contains neither a brace nor an equals sign —
    // so testing for those first read the whole encoded cookie as one long bare token.
    val decoded = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    val json = decoded.substringAfter("musixmatchUserToken=", decoded)
        .substringBefore(';')
        .trim()

    if (json.startsWith("{")) {
        val tokens = runCatching {
            Json.parseToJsonElement(json).jsonObject["tokens"]?.jsonObject
        }.getOrNull() ?: return null

        for (appId in MusixmatchProvider.PREFERRED_APP_IDS) {
            val token = tokens[appId]?.jsonPrimitive?.contentOrNull ?: continue
            if (token.isUsableToken()) return Credential(token, appId)
        }
        return null
    }

    return decoded.takeIf { it.looksLikeToken() }?.let {
        Credential(it, MusixmatchProvider.APP_ID)
    }
}

/**
 * Whether this could be a token at all, as opposed to a stray line of text.
 *
 * Musixmatch tokens are long strings of hex. Without this check, anything the user typed
 * into the field was sent as a credential — and since a bad user token used to shadow the
 * anonymous one, a typo was enough to make Musixmatch stop working.
 */
private fun String.looksLikeToken(): Boolean {
    val value = trim()
    if (value.length < 32) return false
    if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return false
    return value.isUsableToken()
}

/**
 * Musixmatch — the widest source of *richsync*, true per-word timings, for Western music.
 *
 * Works with no account, via the token the mobile client mints for itself. Note the host and
 * client id: the **desktop** app is discontinued and its endpoint now hands out a token of
 * fifty-six zeros, which is accepted and returns lyrics for a different song entirely. The
 * mobile one issues real tokens and still reaches richsync.
 *
 * That anonymous token is rate-limited per address, so it is minted once and kept. A token
 * from a signed-in musixmatch.com session can be pasted in instead — the whole
 * `musixmatchUserToken` cookie is accepted, since that is the form a person actually has to
 * hand — which sidesteps the anonymous rate limit. Not required, though.
 *
 * It is an undocumented endpoint, so every step degrades: no token means no provider, a
 * token that stops working is dropped and re-fetched once, a throttle is reported as the
 * service being unavailable rather than as the track having no lyrics, a match that does not
 * look like the track playing is refused, and a missing richsync falls back to line-synced
 * subtitles and then to plain lyrics.
 */
class MusixmatchProvider(private val credentials: ProviderCredentials) : LyricsProvider {

    override val id = "musixmatch"
    override val displayName = "Musixmatch"
    override val canBeWordSynced = true

    /**
     * Set once the token endpoint has answered and had nothing usable to give.
     *
     * A throttle does not count — that is a wait — so this only trips on a real refusal. In
     * memory rather than in preferences, so the next launch tries again: an endpoint that
     * broke can be fixed, and this app is in no position to decide it never will be.
     */
    private var anonymousTokenDead = false

    /**
     * A token of the user's own, a cached anonymous one, or the benefit of the doubt.
     *
     * The last of those matters: the token is minted on the first lookup, so a provider that
     * called itself unconfigured until it had one would never get the request it needs.
     */
    override val isConfigured: Boolean
        get() = !credentials.musixmatchUserToken.isNullOrBlank() ||
            !credentials.musixmatchGuestToken.isNullOrBlank() ||
            !anonymousTokenDead

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            val user = userCredential()
            var credential = user
                ?: credentials.musixmatchGuestToken?.let { Credential(it, APP_ID) }
                ?: obtainToken()?.also { credentials.musixmatchGuestToken = it.token }
                ?: run {
                    // Asked, and there is nothing usable to be had. Stop claiming to be a
                    // working source for the rest of this run.
                    anonymousTokenDead = true
                    return@withContext null
                }

            var macro = macroCall(request, credential)
            if (macro == null) {
                // Either the cached anonymous token aged out, or the user pasted one this
                // endpoint will not accept. Both are answered the same way: mint a fresh
                // anonymous token and try once more. A wrong paste must not be able to break
                // a source that works perfectly well without one.
                val fresh = obtainToken()
                if (fresh == null) {
                    if (user == null) anonymousTokenDead = true
                    return@withContext null
                }
                credentials.musixmatchGuestToken = fresh.token
                credential = fresh
                macro = macroCall(request, credential)
            }
            if (macro == null) return@withContext null

            documentFrom(macro, request, credential)
        }

    /**
     * The user's own token, from whatever they pasted into the settings field.
     *
     * Accepts either a bare token or the whole `musixmatchUserToken` cookie from
     * musixmatch.com, because that is what a person actually has to hand — a URL-encoded
     * JSON map of one token per client id.
     *
     * Only the entry for [APP_ID] is any use. The tokens on that cookie are scoped to the
     * client they were issued for: sending the site's `web-desktop-app-v1.0` token to this
     * host answers `401 renew`, and sending it to the old desktop host answers 200 with the
     * lyrics of an unrelated song. So a cookie without an [APP_ID] entry — which is every
     * cookie the website hands out today — yields nothing, and the anonymous token the app
     * mints for itself is used instead.
     */
    private fun userCredential(): Credential? =
        parseMusixmatchCredential(credentials.musixmatchUserToken)

    /**
     * Mint an anonymous token.
     *
     * Needed once per install: the result is kept in preferences, because the endpoint is
     * rate-limited per address and will answer `401 captcha` to a caller that asks twice in
     * quick succession. That is a wait, not a refusal, so it is reported as the endpoint
     * being unavailable rather than as this track having no lyrics.
     *
     * @throws Http.Unavailable when the endpoint is throttling.
     */
    private suspend fun obtainToken(): Credential? {
        val url = "$API/token.get?app_id=$APP_ID&t=${System.currentTimeMillis()}"
        val outcome = Http.get(url, HEADERS) { body ->
            runCatching {
                val message = Json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
                val status = message?.get("header")?.jsonObject?.get("status_code")
                    ?.jsonPrimitive?.intOrNull
                val hint = message?.get("header")?.jsonObject?.get("hint")
                    ?.jsonPrimitive?.contentOrNull

                when {
                    // The whole response is 200; the real status is inside it.
                    status == 401 -> TokenOutcome.Throttled(hint ?: "rate limited")
                    status != 200 -> TokenOutcome.Refused
                    else -> message["body"]?.jsonObject?.get("user_token")
                        ?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isUsableToken() }
                        ?.let { TokenOutcome.Minted(it) }
                        ?: TokenOutcome.Refused
                }
            }.getOrNull()
        }

        return when (outcome) {
            is TokenOutcome.Minted -> Credential(outcome.token, APP_ID)
            is TokenOutcome.Throttled -> throw Http.Unavailable("$API/token.get (${outcome.hint})")
            else -> null
        }
    }

    private sealed interface TokenOutcome {
        data class Minted(val token: String) : TokenOutcome
        data class Throttled(val hint: String) : TokenOutcome

        /** Answered, and had nothing usable to give. */
        data object Refused : TokenOutcome
    }

    /**
     * Whether a token from `token.get` is worth keeping.
     *
     * The discontinued desktop endpoint answers 200 with fifty-six zeros, and a request
     * carrying that is accepted and returns lyrics for an unrelated song — asking for Kenshi
     * Yonezu's "Lemon" came back with Drake. Worse than no token at all, so any token made
     * of a single repeated character is rejected: the zeros, the older `UpgradeOnly…`
     * placeholder, and whatever they do next.
     */
    private suspend fun macroCall(request: LyricsRequest, credential: Credential): JsonObject? {
        val url = buildString {
            append("$API/macro.subtitles.get")
            append("?format=json&namespace=lyrics_richsynched&subtitle_format=mxm")
            append("&app_id=").append(credential.appId)
            append("&usertoken=").append(Http.encode(credential.token))
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
        credential: Credential,
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
            ?: trackId?.let { fetchRichsync(it, credential) }

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

    private suspend fun fetchRichsync(trackId: String, credential: Credential): String? {
        val url = "$API/track.richsync.get" +
            "?format=json&app_id=${credential.appId}" +
            "&usertoken=${Http.encode(credential.token)}" +
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

    internal companion object {
        /**
         * The host and client the mobile app uses.
         *
         * Not `apic-desktop.musixmatch.com` with `web-desktop-app-v1.0`: the desktop app is
         * discontinued and its endpoint now hands out a token of fifty-six zeros, which is
         * accepted and answers with lyrics for an unrelated song. The mobile client's host
         * still issues real tokens, and they still reach richsync — the per-word timings that
         * are the reason to use Musixmatch at all.
         */
        const val API = "https://apic.musixmatch.com/ws/1.1"
        const val APP_ID = "android-player-v1.0"

        /**
         * Which client id to take from a pasted `musixmatchUserToken` cookie, in order.
         *
         * The cookie holds one token per client, and a token only works with the client it
         * was issued for. Tested against the live endpoint: on this host almost all of them
         * work — the same track, the same subtitles, and richsync — with two exceptions
         * worth encoding rather than rediscovering.
         *
         * `web-desktop-app-v1.0` is refused outright (`401 upgrade`), which is the discontinued
         * desktop client. And the `-dev` and `-pp` variants are staging clients that have no
         * business being pointed at the live API, so they are not listed at all.
         *
         * `android-player-v1.0` leads because it is the one the app mints for itself, and a
         * cookie that ever starts carrying it should be preferred.
         */
        val PREFERRED_APP_IDS = listOf(
            "android-player-v1.0",
            "mxm-pro-web-v1.0",
            "mxm-pro-android-v1.0",
            "mxm-pro-ios-v1.0",
            "mxm-com-v1.0",
            "mxm-account-v1.0",
            "community-app-v1.0",
            "mxm-studio-v1.0",
            "mxm-experiments-v1.0",
            "musixmatch-podcasts-v2.0",
            "musixmatch-publishers-v2.0",
            "mxm-backoffice-v1.0",
        )

        val HEADERS = mapOf(
            "authority" to "apic.musixmatch.com",
            "Cookie" to "x-mxm-token-guid=",
            "User-Agent" to
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Accept" to "application/json",
        )
    }
}
