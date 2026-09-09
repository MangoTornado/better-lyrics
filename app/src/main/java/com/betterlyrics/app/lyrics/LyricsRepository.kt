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
import com.betterlyrics.app.media.IsrcStore
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
import java.util.concurrent.TimeUnit

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
    private val isrcStore: IsrcStore,
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

    /** Tracks already re-asked this run. See [upgradeAfter]. */
    private val upgraded = HashSet<String>()

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

    private fun TrackInfo.toRequest(isrc: String? = null): LyricsRequest {
        val track = this
        return LyricsRequest(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            spotifyTrackId = spotifyTrackId,
            isrc = isrc,
        ).apply {
            // Several sources hand one over without being asked, and two of them need no token to
            // do it — so this is how a phone with nothing configured still ends up matching exactly.
            onIsrc = { learned -> noteIsrc(track, learned) }
        }
    }

    /**
     * Tell the repository a track's ISRC, learned from somewhere that knows.
     *
     * If it arrives while that track's lookup is still the current one, the lookup is redone with
     * it: an ISRC turns AMLL and Apple from a title search into an exact match, and the sources
     * that had to guess may well have guessed wrong. On a replay it is simply used from the start.
     */
    fun noteIsrc(track: TrackInfo, isrc: String) {
        val request = currentRequest ?: return
        if (request.cacheIdentity() != track.cacheKey) return
        if (!request.isrc.isNullOrBlank()) return

        scope.launch {
            // Written down first and unconditionally. Even when re-asking now is not worth it, the
            // next play of this track starts from an exact identity — which is most of the value.
            isrcStore.put(track.cacheKey, isrc)
            // Only worth redoing if the answer we have is not already the best kind. Word-by-word
            // is as good as it gets, so an ISRC that arrives alongside one — which is the usual
            // case, since AMLL and Apple are the sources that volunteer it — is simply banked.
            val ready = base.value as? Base.Ready
            if (ready == null || ready.document.kind == LyricsKind.SYLLABLE) return@launch

            // And only while this is still the track playing: the lookup takes time, and by now the
            // listener may have moved on.
            if (currentRequest?.cacheIdentity() != track.cacheKey) return@launch
            // `freshen`, because the answer already on screen was cached under a key that does not
            // include the ISRC — so re-asking would be served the same fuzzy result straight back
            // out of the cache, and the ISRC would change nothing for thirty days.
            trackGeneration++
            setTrack(track, forceIsrc = isrc, freshen = true)
        }
    }

    fun setTrack(track: TrackInfo?) {
        // Claimed synchronously, before anything suspends. Reading the stored ISRC involves a file,
        // so two tracks arriving in quick succession could otherwise finish out of order and leave
        // the app showing the earlier one's lyrics — the reader wins the race and the listener
        // loses.
        val generation = ++trackGeneration

        scope.launch {
            // The ISRC, if this track has been played before with something that knew it. Read
            // first because it changes which sources can answer exactly rather than approximately.
            val isrc = track?.let { isrcStore.get(it.cacheKey) }
            if (generation != trackGeneration) return@launch
            setTrack(track, forceIsrc = isrc)
        }
    }

    /** Counts track changes, so a slow read cannot install a track that has been superseded. */
    private var trackGeneration = 0

    private fun setTrack(track: TrackInfo?, forceIsrc: String?, freshen: Boolean = false) {
        val request = track?.takeIf { !it.isEmpty }?.toRequest(forceIsrc)

        // The identity ignores the ISRC, so learning one does not make this look like a new track.
        if (request?.cacheIdentity() == currentRequest?.cacheIdentity() &&
            request?.isrc == currentRequest?.isrc
        ) {
            return
        }

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
        fetchJob = scope.launch { fetchBase(request, freshen = freshen) }
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
                !provider.isConfigured -> SourceReport(
                    provider.id,
                    provider.displayName,
                    provider.unavailableReason ?: "Skipped — needs a token",
                )

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
                            document == null ->
                                // The reason, when the source has one. "No match" alone conflates
                                // "asked and it has nothing" with "never got as far as asking".
                                provider.noMatchReason
                                    ?.let { "$it (${took}ms)" }
                                    ?: "No match (${took}ms)"
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

    /**
     * @param freshen skip the stored answer and ask the sources again, keeping whatever comes back.
     *   For when the question has improved rather than the answer having aged: an ISRC learned since
     *   last time makes an exact lookup possible where only a guess was before.
     */
    private suspend fun fetchBase(request: LyricsRequest, freshen: Boolean = false) {
        val key = request.cacheIdentity()

        if (!freshen) {
            when (val cached = cache.get(key)) {
                is LyricsCache.Result.Hit -> {
                    base.value = Base.Ready(key, cached.document)
                    // Answered from the cache, then quietly improved for next time if anyone was
                    // never asked. Deliberately after the answer is on screen and never awaited.
                    upgradeAfter(request, key, cached)
                    return
                }

                null -> Unit
            }
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
                    cache.put(key, answer.document, answer.outcomes)
                }
                base.value = answer.document
                    ?.let { Base.Ready(key, it) }
                    ?: Base.NotFound
            }
        }
    }

    /**
     * Sources worth asking again about a track that is already cached.
     *
     * Only two cases, and the rest are left alone on purpose:
     *
     * - **Never asked.** No outcome recorded — the source was off, had no token, or did not exist in
     *   the version that cached this. It has never had its chance.
     * - **Did not answer.** A timeout, a transport error, a 5xx. Whatever went wrong may be over, and
     *   after [RETRY_FAILED_MS] it is worth finding out.
     *
     * A source that answered "nothing for this track" is *not* re-asked. That is a real answer, and
     * asking it again every play would spend a request per source per song on a result that will not
     * have changed. The thirty-day expiry already covers a catalogue that grows.
     */
    private fun staleProviders(
        settings: Settings,
        cached: LyricsCache.Result.Hit,
    ): List<LyricsProvider> = staleProviders(
        candidates = providersFor(settings, providers, localStore.id),
        outcomes = cached.outcomes,
        outcomesAgeMs = System.currentTimeMillis() - cached.outcomesAtMs,
    )

    /**
     * Re-asks the sources a cached track never heard from, and keeps a better answer.
     *
     * Detached and never awaited: the words are already on screen, and none of this may make a lookup
     * slower. The improvement shows on the next play — or immediately, if the track is still the one
     * playing when the answer lands.
     */
    private fun upgradeAfter(request: LyricsRequest, key: String, cached: LyricsCache.Result.Hit) {
        if (!upgraded.add(key)) return
        // A memo, not a record: it only has to stop the same track being re-asked once per play.
        if (upgraded.size > 500) upgraded.clear()

        scope.launch {
            val settings = settingsStore.current
            val stale = staleProviders(settings, cached)
            if (stale.isEmpty() || !isOnline()) return@launch

            // Null only when the lookup itself broke, in which case nothing was learned and the
            // outcomes must stay as they were.
            val result = queryOnly(request, stale) ?: return@launch
            val outcomes = cached.outcomes + result.outcomes
            val better = result.document

            // Strictly better only. An equal answer is churn: it would rewrite the cache and could
            // swap the words on screen for no gain the reader can see.
            if (better == null || qualityScore(better) <= qualityScore(cached.document)) {
                // The document is unchanged, but these sources have now been asked — and recording
                // that is the whole point, or every play would ask them again.
                cache.put(key, cached.document, outcomes, cached.savedAtMs)
                return@launch
            }

            Log.i(TAG, "upgraded $key to ${better.providerId}")
            cache.put(key, better, outcomes, cached.savedAtMs)

            // Only if the reader is still looking at this track. Replacing the lyrics under someone
            // who has moved on would be worse than leaving the cache to do its job next time.
            if (currentRequest?.cacheIdentity() == key) {
                derived.keys.removeAll { it.startsWith(key) }
                base.value = Base.Ready(key, better)
            }
        }
    }

    /**
     * The best answer from a named subset of the sources, and what each of them said.
     *
     * [document] is null when none of them had anything — which is not a failure, and the outcomes
     * are the valuable part of that answer.
     */
    private data class Upgrade(
        val document: LyricsDocument?,
        val outcomes: Map<String, LyricsCache.Outcome>,
    )

    private suspend fun queryOnly(
        request: LyricsRequest,
        subset: List<LyricsProvider>,
    ): Upgrade? {
        val results = try {
            coroutineScope {
                subset.map { provider ->
                    async {
                        var failed = false
                        val finished = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            Finished(
                                runCatching { provider.fetch(request) }
                                    .onFailure { error ->
                                        if (error is kotlinx.coroutines.CancellationException) throw error
                                        failed = true
                                        Log.w(TAG, "${provider.id} failed during upgrade: ${error.message}")
                                    }
                                    .getOrNull(),
                            )
                        }
                        Answer(provider.id, finished?.document, failed || finished == null)
                    }
                }.awaitAll()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "upgrade lookup failed: ${e.message}")
            return null
        }

        return Upgrade(
            document = results.mapNotNull { it.document }.maxByOrNull { qualityScore(it) },
            outcomes = results.associate { answer ->
                answer.id to when {
                    answer.failed -> LyricsCache.Outcome.FAILED
                    answer.document != null -> LyricsCache.Outcome.LYRICS
                    else -> LyricsCache.Outcome.NONE
                }
            },
        )
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
            /**
             * What each source said, by provider id.
             *
             * Cached alongside the winning document, because the document alone cannot say who else
             * was asked — and that is exactly what decides whether a later lookup has anyone left
             * worth asking.
             */
            val outcomes: Map<String, LyricsCache.Outcome> = emptyMap(),
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
            outcomes = results.associate { answer ->
                answer.id to when {
                    answer.failed -> LyricsCache.Outcome.FAILED
                    answer.document != null -> LyricsCache.Outcome.LYRICS
                    else -> LyricsCache.Outcome.NONE
                }
            },
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

    internal companion object {
        const val TAG = "LyricsRepository"
        const val PROVIDER_TIMEOUT_MS = 12_000L

        /**
         * How long to leave a source alone after it failed to answer.
         *
         * Six hours. Long enough that a service having a bad day is not asked once per play, short
         * enough that a token fixed this morning is used this afternoon.
         */
        val RETRY_FAILED_MS = TimeUnit.HOURS.toMillis(6)
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
/**
 * Which of [candidates] never got to answer about a track that is already cached.
 *
 * A top-level function so the rule can be tested without a repository, a context and six providers:
 * it is a decision about a map, and the consequence of getting it wrong is either a stale answer kept
 * for a month or a request per source on every play.
 *
 * @param outcomes what each source said last time, by provider id. Empty for an entry cached before
 *   outcomes were recorded — in which case every source counts as never asked, which is right: there
 *   is no evidence any of them were.
 * @param outcomesAgeMs how long ago those outcomes were recorded.
 */
internal fun staleProviders(
    candidates: List<LyricsProvider>,
    outcomes: Map<String, LyricsCache.Outcome>,
    outcomesAgeMs: Long,
    retryFailedMs: Long = LyricsRepository.RETRY_FAILED_MS,
): List<LyricsProvider> = candidates.filter { provider ->
    when (outcomes[provider.id]) {
        // Never asked: off, unconfigured, or not present in the version that cached this.
        null -> true
        // Did not answer. Whatever went wrong may be over by now.
        LyricsCache.Outcome.FAILED -> outcomesAgeMs > retryFailedMs
        // Answered — with lyrics, or with a settled "nothing for this track". Neither is worth
        // re-asking: one already contributed, and the other will say the same thing.
        else -> false
    }
}

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
