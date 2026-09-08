package com.betterlyrics.app.lyrics.provider

import com.betterlyrics.app.lyrics.model.LyricsDocument
import kotlinx.serialization.json.Json
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

    /** Optional: raises NetEase's per-IP limits and unlocks some regional catalogues. */
    val neteaseCookie: String?

    /** Optional: a real Musixmatch token beats the anonymous one for coverage. */
    val musixmatchUserToken: String?

    /** Apple Music needs both, plus an active subscription on the account. */
    val appleDeveloperToken: String?
    val appleMusicUserToken: String?
    val appleStorefront: String

    /** `sp_dc` cookie from a signed-in open.spotify.com session. */
    var spDcCookie: String?

    /** Anonymous Musixmatch token, cached between launches. */
    var musixmatchGuestToken: String?

    /** Short-lived Spotify web token minted from [spDcCookie]. */
    var cachedSpotifyToken: String?
    var cachedSpotifyTokenExpiresAt: Long
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
     * Runs [url] and hands the body to [block]. Returns null on any non-2xx or
     * transport failure: a provider that is down must never break the chain.
     */
    inline fun <T> get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        block: (String) -> T?,
    ): T? = try {
        client.newCall(request(url, headers)).execute().use { response: Response ->
            if (!response.isSuccessful) null else response.body?.string()?.let(block)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
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
        val artistScore = if (request.artist.isBlank() || candidateArtist.isBlank()) {
            0.5f
        } else {
            maxOf(
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
