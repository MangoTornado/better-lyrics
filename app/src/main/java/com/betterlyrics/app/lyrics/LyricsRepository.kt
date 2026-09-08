package com.betterlyrics.app.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.lyrics.provider.LocalLyricsStore
import com.betterlyrics.app.lyrics.provider.LyricsProvider
import com.betterlyrics.app.lyrics.provider.LyricsRequest
import com.betterlyrics.app.lyrics.romanize.Romanizer
import com.betterlyrics.app.lyrics.translate.LyricsTranslator
import com.betterlyrics.app.media.TrackInfo
import com.betterlyrics.app.util.detectScript
import com.betterlyrics.app.util.needsRomanization
import com.betterlyrics.app.settings.CacheServerMode
import com.betterlyrics.app.settings.Settings
import com.betterlyrics.app.settings.SettingsStore
import com.betterlyrics.app.settings.TranslationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data object NotFound : LyricsState
    data object Offline : LyricsState
    data class Failed(val message: String) : LyricsState

    data class Loaded(
        val document: LyricsDocument,
        /** True when a romanization exists and could be shown. */
        val romanizationAvailable: Boolean,
        /** True when a translation is on screen, or would be if it were switched on. */
        val translationAvailable: Boolean,

        /**
         * True when a translation could be had at all — supplied with the lyrics, or
         * within reach of the on-device translator. False for an English song being read
         * in English, where the button would only ever do nothing.
         */
        val translationPossible: Boolean,
        val translating: Boolean = false,
    ) : LyricsState
}

/**
 * Owns the answer to "what are the words to this, and when".
 *
 * Fetching is deliberately split from presenting: [fetchBase] asks the providers once
 * per track and caches the result, while romanization and translation are derived on
 * top and recomputed when the relevant setting changes. Turning romaji on and off
 * therefore never re-hits the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRepository(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val cache: LyricsCache,
    private val romanizer: Romanizer,
    private val translator: LyricsTranslator,
    private val localStore: LocalLyricsStore,
    private val providers: List<LyricsProvider>,
    private val scope: CoroutineScope,
) {

    private sealed interface Base {
        data object Idle : Base
        data object Loading : Base
        data object NotFound : Base
        data object Offline : Base
        data class Failed(val message: String) : Base
        data class Ready(val key: String, val document: LyricsDocument) : Base
    }

    private val base = MutableStateFlow<Base>(Base.Idle)
    private val translating = MutableStateFlow(false)

    /** Memoised derivations, so toggling romanization is instant after the first time. */
    private val derived = HashMap<String, LyricsDocument>()

    /** Whether each track could be romanized, asked once of the document as fetched. */
    private val romanizable = HashMap<String, Boolean>()

    /** The language each track turned out to be in. Null is an answer: unknown. */
    private val languages = HashMap<String, String?>()

    private var fetchJob: Job? = null
    private var currentRequest: LyricsRequest? = null

    private var prefetchJob: Job? = null

    /** So a queue that keeps re-publishing does not re-run the same lookup. */
    private var prefetchedKey: String? = null

    val state: StateFlow<LyricsState> =
        combine(base, settingsStore.settings, translating) { base, settings, isTranslating ->
            Triple(base, settings, isTranslating)
        }.mapLatest { (base, settings, isTranslating) ->
            when (base) {
                Base.Idle -> LyricsState.Idle
                Base.Loading -> LyricsState.Loading
                Base.NotFound -> LyricsState.NotFound
                Base.Offline -> LyricsState.Offline
                is Base.Failed -> LyricsState.Failed(base.message)
                is Base.Ready -> present(base, settings, isTranslating)
            }
        }.stateIn(scope, SharingStarted.Eagerly, LyricsState.Idle)

    // ---- track lifecycle ----------------------------------------------------

    private fun TrackInfo.toRequest() = LyricsRequest(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        spotifyTrackId = spotifyTrackId,
    )

    fun setTrack(track: TrackInfo?) {
        val request = track?.takeIf { !it.isEmpty }?.toRequest()

        if (request?.cacheIdentity() == currentRequest?.cacheIdentity()) return

        fetchJob?.cancel()
        currentRequest = request
        derived.clear()
        romanizable.clear()
        languages.clear()

        if (request == null || !request.isUsable) {
            base.value = Base.Idle
            return
        }

        base.value = Base.Loading
        fetchJob = scope.launch { fetchBase(request) }
    }

    /**
     * Look up the next track in the queue now, so it is already cached when it starts.
     *
     * Worth doing because the cache is what makes this app work offline and what keeps it
     * from hammering volunteer-run endpoints: one query per track, ideally before you ever
     * need it. It is best-effort in every direction — no state is published, failures are
     * silent, and it waits for the track actually on screen to finish resolving first so
     * it never competes with it for bandwidth.
     */
    fun prefetchNext(track: TrackInfo?) {
        if (!settingsStore.current.prefetchNextTrack) return
        val next = track?.takeIf { !it.isEmpty } ?: return
        if (!next.isPrefetchable) return

        val request = next.toRequest()
        if (!request.isUsable) return

        val key = request.cacheIdentity()
        if (key == currentRequest?.cacheIdentity() || key == prefetchedKey) return

        prefetchJob?.cancel()
        prefetchedKey = key
        prefetchJob = scope.launch {
            // The track on screen comes first, always.
            fetchJob?.join()
            if (currentRequest?.cacheIdentity() == key) return@launch
            if (cache.get(key) != null || !isOnline()) return@launch
            if (localStore.fetch(request) != null) return@launch

            val answer = query(request)
            // Only a hit is worth keeping. A miss here may say more about the thin
            // metadata a queue entry carries than about the track, and caching it would
            // mean the real lookup never happens.
            if (answer is Query.Answered && answer.document != null) {
                cache.put(key, answer.document)
                Log.d(TAG, "prefetched ${request.title}")
            }
        }
    }


    fun retry() {
        val request = currentRequest ?: return
        fetchJob?.cancel()
        derived.clear()
        romanizable.clear()
        languages.clear()
        base.value = Base.Loading
        fetchJob = scope.launch {
            cache.remove(request.cacheIdentity())
            fetchBase(request)
        }
    }

    /** Adopts a user-supplied `.lrc`/`.ttml` for the current track. */
    suspend fun importLocal(uri: Uri): Boolean {
        val request = currentRequest ?: return false
        val document = localStore.import(uri, request) ?: return false
        cache.remove(request.cacheIdentity())
        derived.clear()
        romanizable.clear()
        languages.clear()
        base.value = Base.Ready(request.cacheIdentity(), document)
        return true
    }

    suspend fun removeLocal(): Boolean {
        val request = currentRequest ?: return false
        if (!localStore.remove(request)) return false
        retry()
        return true
    }

    suspend fun hasLocal(): Boolean =
        currentRequest?.let { localStore.has(it) } ?: false

    /** One source's answer for one track, in words, for the developer diagnostic. */
    data class SourceReport(val id: String, val name: String, val outcome: String)

    /**
     * Ask every source about the track on screen and report what each one said.
     *
     * "Only LRCLIB works" is impossible to act on: a source that is skipped, one that
     * cannot reach its endpoint, and one that reached it and found nothing all look exactly
     * the same from the lyrics screen. This distinguishes them, per source, in one pass —
     * bypassing the cache, because the point is what happens on the wire right now.
     */
    suspend fun diagnose(): List<SourceReport> {
        val request = currentRequest ?: return listOf(
            SourceReport("none", "No track", "Nothing is playing, so there is nothing to ask about"),
        )
        val settings = settingsStore.current

        return providers.map { provider ->
            val enabled = provider.id == localStore.id ||
                provider.id in settings.enabledProviders ||
                (provider.id == "cacheserver" && settings.cacheServerActive)

            when {
                !enabled -> SourceReport(provider.id, provider.displayName, "Off in settings")
                !provider.isConfigured ->
                    SourceReport(provider.id, provider.displayName, "Skipped — needs a token")

                else -> {
                    val started = System.currentTimeMillis()
                    val outcome = try {
                        val finished = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            Finished(provider.fetch(request))
                        }
                        val took = System.currentTimeMillis() - started
                        val document = finished?.document
                        when {
                            finished == null -> "Timed out after ${PROVIDER_TIMEOUT_MS / 1000}s"
                            document == null -> "No match (${took}ms)"
                            else -> "${document.lines.size} lines, " +
                                "${document.kind.name.lowercase()}-synced (${took}ms)"
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "${e.javaClass.simpleName}: ${e.message?.take(120) ?: "no detail"}"
                    }
                    SourceReport(provider.id, provider.displayName, outcome)
                }
            }
        }
    }

    suspend fun clearCache() {
        cache.clear()
        derived.clear()
        romanizable.clear()
        languages.clear()
    }

    // ---- fetching -----------------------------------------------------------

    private suspend fun fetchBase(request: LyricsRequest) {
        val key = request.cacheIdentity()

        when (val cached = cache.get(key)) {
            is LyricsCache.Result.Hit -> {
                base.value = Base.Ready(key, cached.document)
                return
            }

            null -> Unit
        }

        // A local file always wins, and answers without touching the network.
        localStore.fetch(request)?.let { document ->
            base.value = Base.Ready(key, document)
            return
        }

        if (!isOnline()) {
            base.value = Base.Offline
            return
        }

        when (val answer = query(request)) {
            is Query.Broke -> base.value = Base.Failed(answer.message)
            is Query.Answered -> {
                // A miss is only worth remembering when it is a real answer. If a source
                // timed out or returned a 503, we do not know that this track has no
                // lyrics — and writing "none" into the cache would keep it blank for two
                // days because an endpoint hiccuped once.
                if (answer.document != null || !answer.provisional) {
                    cache.put(key, answer.document)
                }
                base.value = answer.document
                    ?.let { Base.Ready(key, it) }
                    ?: Base.NotFound
            }
        }
    }

    /** One provider's contribution to a lookup. */
    private data class Answer(
        val id: String,
        val document: LyricsDocument?,
        /** True when it did not answer, as opposed to answering that it has nothing. */
        val failed: Boolean,
    )

    /** Proof that a provider's own code ran to completion, whatever it returned. */
    private class Finished(val document: LyricsDocument?)

    private sealed interface Query {
        /**
         * The providers were asked and this is what they had, null included.
         *
         * @param provisional true when a null result cannot be trusted as "this track has
         *   no lyrics": a source did not answer at all — a timeout, a transport error, a
         *   429 or a 5xx — or there was no source enabled to ask. Either way the answer
         *   must not be cached, or enabling a provider (or an endpoint recovering) would
         *   change nothing for two days.
         */
        data class Answered(
            val document: LyricsDocument?,
            val provisional: Boolean = false,
        ) : Query

        /** The lookup itself failed, which is not the same as the track having no lyrics. */
        data class Broke(val message: String) : Query
    }

    /**
     * Asks every configured provider at once and keeps the best answer.
     *
     * Touches no state, so it serves both the track on screen and the one queued behind
     * it.
     */
    private suspend fun query(request: LyricsRequest): Query {
        val settings = settingsStore.current
        val enabled = providersFor(settings, providers, localStore.id)

        // Nothing was asked, so nothing was learned — least of all that this track has no
        // lyrics.
        if (enabled.isEmpty()) return Query.Answered(null, provisional = true)

        val results = try {
            coroutineScope {
                enabled.map { provider ->
                    async {
                        var failed = false
                        // Wrapped so that "the provider finished and had nothing" can be
                        // told apart from "the provider ran out of time" — withTimeoutOrNull
                        // returns null for both, and only one of them is a real answer.
                        val finished = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            Finished(
                                runCatching { provider.fetch(request) }
                                    .onFailure { error ->
                                        if (error is kotlinx.coroutines.CancellationException) {
                                            throw error
                                        }
                                        failed = true
                                        Log.w(TAG, "${provider.id} failed: ${error.message}")
                                    }
                                    .getOrNull(),
                            )
                        }
                        if (finished == null) Log.w(TAG, "${provider.id} timed out")
                        Answer(provider.id, finished?.document, failed || finished == null)
                    }
                }.awaitAll()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Query.Broke(e.message ?: "Lookup failed")
        }

        return Query.Answered(
            provisional = results.any { it.failed },
            document = results.mapNotNull { (id, document) -> document?.let { id to it } }
                .maxWithOrNull(
                    compareBy(
                        { (_, document) -> qualityScore(document) },
                        // Earlier in the user's order wins a tie. A provider that is not
                        // in that order at all — the cache server — is not last by
                        // accident of `indexOf` returning -1; it is first on purpose,
                        // because an equally good answer from the cache is the one that
                        // cost nobody a request.
                        { (id, _) ->
                            settings.providerOrder.indexOf(id).let { if (it < 0) 1 else -it }
                        },
                    ),
                )?.second,
        )
    }

    /**
     * Which of several results to show. Word timings beat line timings beat no
     * timings; within a tier, a result that ships its own romanization or translation
     * beats one we would have to generate.
     */
    private fun qualityScore(document: LyricsDocument): Int {
        val tier = when (document.kind) {
            LyricsKind.SYLLABLE -> 100
            LyricsKind.LINE -> 50
            LyricsKind.STATIC -> 10
        }
        val extras = (if (document.hasRomanization) 3 else 0) +
            (if (document.hasTranslation) 2 else 0)
        return tier + extras
    }

    // ---- presentation -------------------------------------------------------

    private suspend fun present(
        ready: Base.Ready,
        settings: Settings,
        isTranslating: Boolean,
    ): LyricsState {
        var document = ready.document

        if (settings.showRomanization ||
            settings.furigana != com.betterlyrics.app.settings.FuriganaMode.OFF
        ) {
            document = deriveAnnotated(ready.key, document, settings)
        }

        // Only the on-device source derives anything. "From the source" is a display
        // decision about text the document already carries, so it costs nothing here —
        // no model, no download, no work on a track that has no translation anyway.
        if (settings.translationSource == TranslationSource.DEVICE) {
            document = deriveTranslated(ready.key, document, settings, sourceLanguage(ready))
        }

        return LyricsState.Loaded(
            document = document,
            // Asked of the document as fetched, not as displayed. Deriving it from the
            // rendered document made the toggle a one-way door: turning romanization off
            // removed the romanization, which removed the reason to show the button that
            // turns it back on.
            romanizationAvailable = romanizationPossible(ready),
            translationAvailable = document.hasTranslation,
            translationPossible = document.hasTranslation ||
                LyricsTranslator.canTranslate(
                    sourceLanguage(ready),
                    settings.translationTarget,
                ),
            translating = isTranslating,
        )
    }

    /**
     * What language this track is in, worked out once and remembered.
     *
     * Most providers declare nothing, and for Latin-script lyrics the script cannot say —
     * so this may involve running the on-device identifier. Doing it here rather than inside
     * the translator means the answer is shared between deciding whether to offer
     * translation and actually doing it.
     */
    private suspend fun sourceLanguage(ready: Base.Ready): String? {
        if (languages.containsKey(ready.key)) return languages[ready.key]
        val tag = translator.identify(ready.document)
        languages[ready.key] = tag
        return tag
    }

    /**
     * Whether this track could be romanized at all — which is not the same question as
     * whether it currently is.
     *
     * True when the lyrics already ship a romanization, or when they are written in a
     * script that has one. Computed from the fetched document and memoised per track, so
     * flipping the setting cannot change the answer.
     */
    private fun romanizationPossible(ready: Base.Ready): Boolean =
        romanizable.getOrPut(ready.key) {
            val document = ready.document
            document.hasRomanization ||
                document.lines.any { it.romanized != null } ||
                document.lines.any { line -> line.syllables.any { it.romanized != null } } ||
                detectScript(document.lines.joinToString("\n") { it.text }).needsRomanization()
        }

    private suspend fun deriveAnnotated(
        key: String,
        document: LyricsDocument,
        settings: Settings,
    ): LyricsDocument {
        val furigana = settings.furigana != com.betterlyrics.app.settings.FuriganaMode.OFF
        val cacheKey = "$key|ann|${settings.showRomanization}|$furigana|" +
            settings.romanizationStripsDiacritics
        derived[cacheKey]?.let { return it }
        val result = romanizer.annotate(
            document = document,
            romanize = settings.showRomanization,
            furigana = furigana,
            stripDiacritics = settings.romanizationStripsDiacritics,
        )
        derived[cacheKey] = result
        return result
    }

    private suspend fun deriveTranslated(
        key: String,
        document: LyricsDocument,
        settings: Settings,
        sourceTag: String?,
    ): LyricsDocument {
        val cacheKey = "$key|tr|${settings.translationTarget}|" +
            settings.romanizationStripsDiacritics + settings.showRomanization
        derived[cacheKey]?.let { return it }

        translating.value = true
        val result = try {
            translator.translate(
                document = document,
                targetTag = settings.translationTarget,
                requireWifi = settings.translationWifiOnly,
                // The user picked this language; a Chinese translation shipped with a
                // Japanese song does not satisfy a request for English.
                replaceProvided = true,
                sourceTagOverride = sourceTag,
            )
        } finally {
            translating.value = false
        }
        derived[cacheKey] = result
        return result
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun LyricsRequest.cacheIdentity(): String =
        spotifyTrackId?.let { "sp:$it" }
            ?: "${title.lowercase().trim()}|${artist.lowercase().trim()}|${durationMs / 2000}"

    private companion object {
        const val TAG = "LyricsRepository"
        const val PROVIDER_TIMEOUT_MS = 12_000L
    }
}

/**
 * Which providers to ask for a track, in the order the user put them in.
 *
 * Three rules, in order:
 *
 * 1. Local files never appear. They are answered before anything is asked at all, and a
 *    file the user imported outranks every network source by definition.
 * 2. A provider missing its token is dropped rather than queried, so leaving one enabled
 *    while you go and find the token costs nothing.
 * 3. A cache server, when the developer options are on, either joins the front of the
 *    fan-out or replaces it entirely — see [CacheServerMode]. It goes first so that a
 *    hit is what the tie-break sees.
 *
 * Free of the repository's state so it can be tested directly: which sources a given
 * settings object will actually hit is exactly the sort of thing that is easy to get
 * subtly wrong and impossible to notice.
 */
internal fun providersFor(
    settings: Settings,
    providers: List<LyricsProvider>,
    localProviderId: String,
): List<LyricsProvider> {
    val cacheServer = providers
        .firstOrNull { it.id == CACHE_SERVER_ID }
        ?.takeIf { settings.cacheServerActive && it.isConfigured }

    if (cacheServer != null && settings.cacheServerMode == CacheServerMode.ONLY) {
        return listOf(cacheServer)
    }

    val ordinary = settings.providerOrder
        .filter { it in settings.enabledProviders && it != localProviderId }
        .filter { it != CACHE_SERVER_ID }
        .mapNotNull { id -> providers.firstOrNull { it.id == id } }
        .filter { it.isConfigured }

    return listOfNotNull(cacheServer) + ordinary
}

private const val CACHE_SERVER_ID = "cacheserver"
