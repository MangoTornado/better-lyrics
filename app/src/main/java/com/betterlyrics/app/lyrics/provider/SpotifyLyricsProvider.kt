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
 * Needs a web access token, and the only way to get one now is to copy it out of the web
 * player: Spotify closed the endpoint that turned an `sp_dc` cookie into a token, but not the
 * endpoints that token opens — `color-lyrics` still answers `401`, meaning "bring a token",
 * rather than `403`. So a token pasted into the developer options works, for the hour or so
 * until it expires. Without one the provider stays dormant and says why.
 */
class SpotifyLyricsProvider(
    private val credentials: ProviderCredentials,
    /**
     * Where `color-lyrics` lives. A parameter only so a test can stand a server in its place — the
     * status this endpoint returns is the difference between "your token is bad" and "this song has
     * no lyrics", and that was worth being able to test rather than reason about.
     */
    private val lyricsBase: String = "https://spclient.wg.spotify.com",
) : LyricsProvider {

    override val id = "spotify"
    override val displayName = "Spotify"

    override val isConfigured: Boolean
        get() = SpotifyWebToken.pasted(credentials) != null ||
            // A cookie counts when a WebView can turn it into a token. Without this the feature was
            // unreachable: removing the pasted token made the source unconfigured, an unconfigured
            // source is never queried, and the renewal only ran *inside* a query. So the one moment
            // it was needed was the one moment it could not happen.
            credentials.spotifyBrowserTokenEnabled ||
            (!SpotifyWebToken.BLOCKED_BY_SPOTIFY && !credentials.spDcCookie.isNullOrBlank())

    override val unavailableReason: String?
        get() = when {
            // A pasted token first, because `get()` prefers one and `refresh()` will not replace
            // it — so if Spotify refused anything, it refused that. Blaming the cookie here sent
            // the user to replace a credential that was never used.
            tokenRejected && !credentials.spotifyWebToken.isNullOrBlank() ->
                "The pasted access token has expired — copy a fresh one, or clear it to let the " +
                    "cookie renew instead"
            // Nothing pasted, so the refusal is the harvested token's, and the cookie behind it.
            tokenRejected && credentials.spotifyBrowserTokenEnabled ->
                "Spotify refused the token — the sp_dc cookie may have expired"
            tokenRejected -> "The pasted access token has expired — copy a fresh one"
            isConfigured -> null
            !credentials.spotifyWebToken.isNullOrBlank() ->
                "The pasted access token has expired — copy a fresh one"
            !credentials.spDcCookie.isNullOrBlank() ->
                "Add a token, or switch on automatic renewal under Developer options"
            else -> SpotifyWebToken.BLOCKED_REASON
        }

    /**
     * Set when the endpoint refuses the token.
     *
     * A pasted token lasts about an hour and cannot be renewed from here, so its expiry is
     * the normal end of its life rather than an error. Without noticing the `401` the app
     * would report every track as having no Spotify lyrics, which points the user at the
     * wrong problem entirely.
     */
    private var tokenRejected = false

    /** Lets a test reach the refused-token state without a network round trip. */
    internal fun noteRejectedForTest() {
        tokenRejected = true
    }

    @Volatile
    override var noMatchReason: String? = null
        private set

    /**
     * The status of the last lyrics request.
     *
     * Kept because it is the difference between "your credential is bad" and "this song has no
     * lyrics", and those were reported identically. A 404 here is the ordinary case — Spotify has
     * lyrics for a fraction of its catalogue.
     */
    @Volatile
    private var lastStatus: Int? = null

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            noMatchReason = null
            lastStatus = null

            val trackId = request.spotifyTrackId
            if (trackId == null) {
                // Not a failure, and not something a token would fix: this source is keyed by
                // Spotify's own track id, and nothing else publishes one. Saying "no match" here
                // reads as "Spotify does not have this song", which is a different claim entirely.
                noMatchReason = "Not playing from Spotify, so there is no track id to look up"
                return@withContext null
            }

            var token = SpotifyWebToken.get(credentials)
            if (token == null) {
                // With automatic renewal on, the source counts as configured before a token
                // exists — so this is where "waiting for the first token" used to surface, as
                // "No match".
                noMatchReason = if (credentials.spotifyBrowserTokenEnabled) {
                    "No token yet — a renewal is running in the background, so try again shortly"
                } else {
                    "No access token"
                }
                return@withContext null
            }

            val document = colorLyrics(trackId, token, request)
            if (document != null) return@withContext document

            // Only a refusal is worth retrying, and only asynchronously. The retry used to run for
            // *any* empty answer and then report "Spotify refused the token" — so a track Spotify
            // simply has no lyrics for came back blaming the credential, which is the one thing
            // that was working. It could not have succeeded either way: a pasted token cannot be
            // replaced, and a harvested one is replaced in the background, so nothing here can hand
            // back a different token to try within this call.
            if (tokenRejected) {
                SpotifyWebToken.refresh(credentials)
                noMatchReason = when {
                    !credentials.spotifyWebToken.isNullOrBlank() ->
                        "Spotify refused the pasted token (HTTP ${lastStatus ?: "401"}) — it has expired"
                    credentials.spotifyBrowserTokenEnabled ->
                        "Spotify refused the token (HTTP ${lastStatus ?: "401"}) — a renewal is " +
                            "running, so try again shortly"
                    else -> "Spotify refused the token (HTTP ${lastStatus ?: "401"})"
                }
                return@withContext null
            }

            // The token was accepted and the answer was still empty. Which is ordinary: Spotify has
            // no lyrics at all for a great many tracks. The status says which, so there is no need
            // to guess.
            noMatchReason = when (val status = lastStatus) {
                null -> "Spotify did not answer"
                404 -> "Spotify has no lyrics for this track (404)"
                200 -> "Spotify answered with no usable lines"
                else -> "Spotify answered HTTP $status"
            }
            null
        }

    private suspend fun colorLyrics(
        trackId: String,
        token: String,
        request: LyricsRequest,
    ): LyricsDocument? {
        val url = "$lyricsBase/color-lyrics/v2/track/$trackId" +
            "?format=json&vocalRemoval=false&market=from_token"
        // Tested against the live endpoint with a token copied from the player: these four
        // headers are enough. The `client-token` the web player also sends is not required.
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "App-Platform" to "WebPlayer",
            "User-Agent" to WEB_UA,
            "Accept" to "application/json",
        )

        return Http.get(url, headers, onStatus = { code ->
            lastStatus = code
            tokenRejected = code == 401 || code == 403
        }) { body ->
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
