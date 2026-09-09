package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.util.detectScript
import com.betterlyrics.app.util.hasLatinLetters
import kotlinx.serialization.json.Json
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Everything a provider needs to look a track up. */
data class LyricsRequest(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val spotifyTrackId: String? = null,
    /**
     * The recording's ISRC, when it has been learned.
     *
     * Worth more than any amount of name matching: it identifies the recording globally, so a
     * source that indexes on it — AMLL does, Apple filters on it — becomes an exact lookup rather
     * than a guess between similar titles. Android does not publish one, so it arrives from
     * Spotify or from a cache server and is remembered.
     */
    val isrc: String? = null,
) {
    val durationSeconds: Int get() = (durationMs / 1000).toInt()

    /**
     * The title with the noise most catalogues disagree about removed —
     * `Song (feat. X) - 2011 Remaster` becomes `Song`. Tried as a second pass when
     * the exact title finds nothing.
     */
    val cleanTitle: String by lazy { cleanTrackTitle(title) }

    /** First credited artist. Databases index on it far more reliably than the full list. */
    val primaryArtist: String by lazy { splitArtists(artist).firstOrNull() ?: artist }

    val allArtists: List<String> by lazy { splitArtists(artist) }

    val isUsable: Boolean get() = title.isNotBlank()
}

private val PARENTHETICAL_NOISE = Regex(
    """\s*[(\[](?:feat\.?|ft\.?|featuring|with|prod\.?|remaster(?:ed)?|live|acoustic|""" +
        """radio edit|single version|album version|explicit|clean|bonus track|deluxe)""" +
        """[^)\]]*[)\]]""",
    RegexOption.IGNORE_CASE,
)

private val TRAILING_NOISE = Regex(
    """\s+-\s+(?:\d{4}\s+)?(?:remaster(?:ed)?(?:\s+\d{4})?|single version|album version|""" +
        """radio edit|live(?:\s+at\s+.*)?|acoustic|instrumental|mono|stereo|""" +
        """from\s+.*|feat\..*|ft\..*)\s*$""",
    RegexOption.IGNORE_CASE,
)

fun cleanTrackTitle(title: String): String =
    title.replace(PARENTHETICAL_NOISE, "")
        .replace(TRAILING_NOISE, "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .ifEmpty { title }

fun splitArtists(artist: String): List<String> =
    artist.split(",", "、", " & ", " x ", " X ", " feat. ", " ft. ", ";", " with ")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

interface LyricsProvider {
    /** Stable id, also used as the settings key for enabling/disabling. */
    val id: String

    /** Shown in the credits line under the lyrics. */
    val displayName: String

    /** True when the provider can return per-word timings. */
    val canBeWordSynced: Boolean get() = false

    /**
     * False when the user has not supplied what this provider needs (a token, a cookie).
     * Such a provider is skipped rather than queried, and Settings says why.
     */
    val isConfigured: Boolean get() = true

    /**
     * Why this provider cannot be used, when the reason is not simply a missing token.
     *
     * For a source that has been closed off at the other end: "you have not set this up" and
     * "this no longer exists" are different things to be told, and the second one is not the
     * user's to fix.
     */
    val unavailableReason: String? get() = null

    /** Returns null when this provider simply has nothing for the track. */
    suspend fun fetch(request: LyricsRequest): LyricsDocument?
}

/**
 * Everything the providers need from Settings: endpoints the user may repoint, tokens
 * they may supply, and the two caches that back the tokens we mint ourselves.
 *
 * One interface rather than one per provider so a provider never has to know where its
 * secrets are stored, and Settings never has to know how they are used.
 */
interface ProviderCredentials {
    /** So a self-hosted or mirrored instance can be used instead. */
    val lrcLibBaseUrl: String
    val neteaseBaseUrl: String

    /**
     * The community TTML index. Self-hostable, so this is a setting rather than a constant —
     * the official instance is one volunteer's server and a heavy user should run their own.
     */
    val amllBaseUrl: String

    /** A caching server of the user's own, set under the developer options. */
    val cacheServerUrl: String?

    /**
     * Optional key for [cacheServerUrl], sent as a bearer token.
     *
     * A server holding an Apple Music token has no business answering strangers, so one
     * reachable from anywhere but the local network will want a key. Left empty for a server
     * on your own Wi-Fi, which is the ordinary case and which such a server should let
     * through unauthenticated.
     */
    val cacheServerKey: String?

    /**
     * Whether a hidden WebView may renew the Spotify token from [spDcCookie].
     *
     * Behind developer options: see `Settings.spotifyBrowserTokenActive`.
     */
    val spotifyBrowserTokenEnabled: Boolean

    /** Optional: raises NetEase's per-IP limits and unlocks some regional catalogues. */
    val neteaseCookie: String?

    /** Optional: a real Musixmatch token beats the anonymous one for coverage. */
    val musixmatchUserToken: String?

    /** Apple Music needs both, plus an active subscription on the account. */
    val appleDeveloperToken: String?
    val appleMusicUserToken: String?
    val appleStorefront: String

    /** `sp_dc` cookie from a signed-in open.spotify.com session. No longer usable on its own. */
    var spDcCookie: String?

    /**
     * A web access token lifted straight from the player, skipping the closed mint.
     *
     * Short-lived, so this is a developer setting rather than something to rely on — but it
     * is a real one: the endpoints it opens are alive and answer to it.
     */
    val spotifyWebToken: String?

    /** Anonymous Musixmatch token, cached between launches. */
    var musixmatchGuestToken: String?

    /** Short-lived Spotify web token minted from [spDcCookie]. */
    var cachedSpotifyToken: String?
    var cachedSpotifyTokenExpiresAt: Long
}

/**
 * A token as the user pasted it, reduced to the token.
 *
 * Every one of these is copied out of a browser's developer tools, where the easy thing to copy is
 * the whole `Authorization` header. Sending that back with another `Bearer` in front of it is an
 * invalid header and a 401 that looks exactly like an expired token — so accept what people
 * actually have to hand.
 */
fun bearerValue(raw: String?): String? {
    var value = raw?.trim() ?: return null
    // Case-insensitively, because developer tools copy header names in lower case.
    if (value.startsWith("authorization:", ignoreCase = true)) {
        value = value.substring("authorization:".length).trim()
    }
    if (value.startsWith("bearer", ignoreCase = true)) {
        // The scheme word only, not a token that happens to begin with those letters.
        val rest = value.substring("bearer".length)
        if (rest.isEmpty() || rest.first().isWhitespace()) value = rest.trim()
    }
    return value.takeIf { it.isNotEmpty() }
}

/** Shared HTTP plumbing. One client, so connections and DNS are reused. */
object Http {
    private const val USER_AGENT =
        "BetterLyrics/0.1.0 (Android; https://github.com/better-lyrics)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun request(url: String, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", headers["User-Agent"] ?: USER_AGENT)
            .apply { headers.forEach { (k, v) -> if (k != "User-Agent") header(k, v) } }
            .get()
            .build()

    /**
     * Thrown when a service did not answer, as distinct from answering "I do not have it".
     *
     * The difference decides whether a miss is worth remembering. `404` means this track
     * has no lyrics here, and caching that saves everybody a request. A timeout, a DNS
     * failure, a `503` or a `429` means we still do not know — cache it as a miss and the
     * track stays blank for two days because an endpoint hiccuped once.
     */
    class Unavailable(url: String, cause: Throwable? = null) :
        java.io.IOException("no answer from $url", cause)

    /**
     * Runs [url] and hands the body to [block].
     *
     * Returns null when the service answered and had nothing — a 404, or an empty body.
     * Throws [Unavailable] when it did not answer at all.
     *
     * Enqueued rather than executed: a blocking `execute()` ignores coroutine
     * cancellation, so the caller's timeout would expire while the socket read carried on
     * regardless, and a hung endpoint could hold up a whole lookup well past its budget.
     * This way cancelling the coroutine cancels the call.
     */
    suspend fun <T> get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        /**
         * Called with the HTTP status before the body is read.
         *
         * For the callers that need to tell a refusal from an absence — a `401` means a
         * credential has expired, which is worth saying, while a `404` means this track has
         * no lyrics here, which is not.
         */
        onStatus: (Int) -> Unit = {},
        block: (String) -> T?,
    ): T? = body(url, headers, onStatus)?.let(block)

    private suspend fun body(
        url: String,
        headers: Map<String, String>,
        onStatus: (Int) -> Unit = {},
    ): String? =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request(url, headers))
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(Unavailable(url, e))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    runCatching { onStatus(response.code) }
                    response.use {
                        when {
                            it.isSuccessful -> continuation.resume(it.body?.string())
                            // Rate limiting and server errors are the service saying "not
                            // now", which is not the same as "not here".
                            it.code == 429 || it.code >= 500 ->
                                continuation.resumeWithException(Unavailable(url))

                            else -> continuation.resume(null)
                        }
                    }
                }
            })
        }

    fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * How well a candidate from a search endpoint matches what is playing. Providers use
 * it to pick between results; anything under [MATCH_THRESHOLD] is discarded rather
 * than shown, because wrong lyrics are worse than none.
 */
object Matching {
    const val MATCH_THRESHOLD = 0.62f

    fun score(
        request: LyricsRequest,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long,
    ): Float {
        val titleScore = maxOf(
            similarity(request.title, candidateTitle),
            similarity(request.cleanTitle, cleanTrackTitle(candidateTitle)),
        )
        val artistScore = when {
            request.artist.isBlank() || candidateArtist.isBlank() -> 0.5f

            // Two names for the same person in different scripts — `Kenshi Yonezu`
            // against `米津玄師` — share no characters at all, so letter similarity reads
            // as a flat contradiction when it is really an absence of evidence. Scoring
            // it zero rejects correct matches from catalogues that index in the original
            // script; treating it as unknown lets the title and duration decide.
            !comparableScripts(request.artist, candidateArtist) -> 0.5f

            else -> maxOf(
                similarity(request.artist, candidateArtist),
                similarity(request.primaryArtist, candidateArtist),
                if (candidateArtist.containsFold(request.primaryArtist)) 1f else 0f,
            )
        }
        val durationScore = when {
            request.durationMs <= 0 || candidateDurationMs <= 0 -> 0.5f
            else -> {
                val delta = kotlin.math.abs(request.durationMs - candidateDurationMs)
                when {
                    delta <= 2_000 -> 1f
                    delta <= 5_000 -> 0.75f
                    delta <= 10_000 -> 0.35f
                    else -> 0f
                }
            }
        }
        return titleScore * 0.5f + artistScore * 0.3f + durationScore * 0.2f
    }

    /**
     * Whether two names are written in scripts that can meaningfully be compared letter
     * by letter. A Latin name and a CJK, Cyrillic or Greek one cannot be.
     */
    private fun comparableScripts(a: String, b: String): Boolean {
        // A mixed name — `YOASOBI` credited as `YOASOBI (ヨアソビ)` — still has Latin in
        // common, so the usual comparison holds.
        if (hasLatinLetters(a) && hasLatinLetters(b)) return true
        return detectScript(a) == detectScript(b)
    }

    private fun String.containsFold(other: String): Boolean =
        other.isNotBlank() && lowercase().contains(other.lowercase())

    /** Normalised Levenshtein similarity on folded text. */
    fun similarity(a: String, b: String): Float {
        val x = fold(a)
        val y = fold(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        if (x == y) return 1f
        val distance = levenshtein(x, y)
        val longest = maxOf(x.length, y.length)
        return (1f - distance.toFloat() / longest).coerceIn(0f, 1f)
    }

    private fun fold(value: String): String = value.lowercase()
        .replace(Regex("[\\p{Punct}\\s]+"), " ")
        .trim()

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }
}
