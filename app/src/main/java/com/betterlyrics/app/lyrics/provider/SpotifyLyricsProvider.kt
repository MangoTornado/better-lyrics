package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricLine
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.lyrics.model.inferEndTimes
import com.betterlyrics.app.lyrics.model.withInterludes
import com.betterlyrics.app.util.isRtlText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Spotify's own lyrics (`color-lyrics/v2`) — the exact same lines the Spotify app
 * shows, matched to the exact track that is playing rather than guessed from a title.
 *
 * Needs one thing from the user: the `sp_dc` cookie from a signed-in
 * open.spotify.com session, pasted into settings. Without it this provider stays
 * dormant. It is an internal endpoint, so treat a failure as ordinary.
 */
class SpotifyLyricsProvider(private val credentials: ProviderCredentials) : LyricsProvider {

    override val id = "spotify"
    override val displayName = "Spotify"

    override val isConfigured: Boolean
        get() = !credentials.spDcCookie.isNullOrBlank()

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            val trackId = request.spotifyTrackId ?: return@withContext null
            if (credentials.spDcCookie.isNullOrBlank()) return@withContext null

            var token = SpotifyWebToken.get(credentials) ?: return@withContext null
            var document = colorLyrics(trackId, token, request)
            if (document == null) {
                // Force a token refresh once before giving up.
                token = SpotifyWebToken.refresh(credentials) ?: return@withContext null
                document = colorLyrics(trackId, token, request)
            }
            document
        }

    private suspend fun colorLyrics(
        trackId: String,
        token: String,
        request: LyricsRequest,
    ): LyricsDocument? {
        val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId" +
            "?format=json&vocalRemoval=false&market=from_token"
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "App-Platform" to "WebPlayer",
            "User-Agent" to WEB_UA,
            "Accept" to "application/json",
        )

        return Http.get(url, headers) { body ->
            runCatching {
                val lyrics = Json.parseToJsonElement(body).jsonObject["lyrics"]?.jsonObject
                    ?: return@runCatching null
                val syncType = lyrics["syncType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val providerName = lyrics["providerDisplayName"]?.jsonPrimitive?.contentOrNull
                    ?: "Spotify"
                val language = lyrics["language"]?.jsonPrimitive?.contentOrNull

                val lines = lyrics["lines"]?.jsonArray?.mapNotNull { element ->
                    val entry = element.jsonObject
                    val words = entry["words"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val start = entry["startTimeMs"]?.jsonPrimitive?.contentOrNull
                        ?.toIntOrNull() ?: return@mapNotNull null
                    val end = entry["endTimeMs"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    // Spotify emits "♪" as its own interlude marker; we generate those
                    // from the gaps instead, so drop them.
                    if (words.isEmpty() || words == "♪") return@mapNotNull null
                    LyricLine(
                        role = LineRole.LEAD,
                        startMs = start,
                        endMs = end,
                        text = words,
                        rtl = words.isRtlText(),
                    )
                }.orEmpty()

                if (lines.isEmpty()) return@runCatching null

                val unsynced = syncType.equals("UNSYNCED", ignoreCase = true)
                LyricsDocument(
                    kind = if (unsynced) LyricsKind.STATIC else LyricsKind.LINE,
                    lines = if (unsynced) {
                        lines
                    } else {
                        lines.inferEndTimes(request.durationMs).withInterludes()
                    },
                    providerName = "$displayName · $providerName",
                    providerId = id,
                    language = language,
                )
            }.getOrNull()
        }
    }

    private companion object {
        val WEB_UA = SpotifyWebToken.WEB_USER_AGENT
    }
}
