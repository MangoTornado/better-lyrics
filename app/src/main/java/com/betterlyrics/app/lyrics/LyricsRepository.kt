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
import com.betterlyrics.app.settings.Settings
import com.betterlyrics.app.settings.SettingsStore
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
        val translationAvailable: Boolean,
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

    private var fetchJob: Job? = null
    private var currentRequest: LyricsRequest? = null

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

    fun setTrack(track: TrackInfo?) {
        val request = track?.takeIf { !it.isEmpty }?.let {
            LyricsRequest(
                title = it.title,
                artist = it.artist,
                album = it.album,
                durationMs = it.durationMs,
                spotifyTrackId = it.spotifyTrackId,
            )
        }

        if (request?.cacheIdentity() == currentRequest?.cacheIdentity()) return

        fetchJob?.cancel()
        currentRequest = request
        derived.clear()

        if (request == null || !request.isUsable) {
            base.value = Base.Idle
            return
        }

        base.value = Base.Loading
        fetchJob = scope.launch { fetchBase(request) }
    }

    fun retry() {
        val request = currentRequest ?: return
        fetchJob?.cancel()
        derived.clear()
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

    suspend fun clearCache() {
        cache.clear()
        derived.clear()
    }

    // ---- fetching -----------------------------------------------------------

    private suspend fun fetchBase(request: LyricsRequest) {
        val key = request.cacheIdentity()

        when (val cached = cache.get(key)) {
            is LyricsCache.Result.Hit -> {
                base.value = Base.Ready(key, cached.document)
                return
            }

            LyricsCache.Result.NotFound -> {
                // Re-check the user's own files: they may have imported one since.
                localStore.fetch(request)?.let {
                    base.value = Base.Ready(key, it)
                    return
                }
                base.value = Base.NotFound
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

        val settings = settingsStore.current
        val enabled = settings.providerOrder
            .filter { it in settings.enabledProviders && it != localStore.id }
            .mapNotNull { id -> providers.firstOrNull { it.id == id } }
            // A provider missing its token is skipped rather than queried, so it costs
            // nothing to leave enabled while you go and find the token.
            .filter { it.isConfigured }

        if (enabled.isEmpty()) {
            base.value = Base.NotFound
            return
        }

        val results = try {
            coroutineScope {
                enabled.map { provider ->
                    async {
                        val document = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            runCatching { provider.fetch(request) }
                                .onFailure { error ->
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    Log.w(TAG, "${provider.id} failed: ${error.message}")
                                }
                                .getOrNull()
                        }
                        provider.id to document
                    }
                }.awaitAll()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            base.value = Base.Failed(e.message ?: "Lookup failed")
            return
        }

        val best = results.mapNotNull { (id, document) -> document?.let { id to it } }
            .maxWithOrNull(
                compareBy(
                    { (_, document) -> qualityScore(document) },
                    // Earlier in the user's order wins a tie.
                    { (id, _) -> -settings.providerOrder.indexOf(id) },
                ),
            )?.second

        cache.put(key, best)
        base.value = if (best == null) Base.NotFound else Base.Ready(key, best)
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

        if (settings.showTranslation) {
            document = deriveTranslated(ready.key, document, settings)
        }

        return LyricsState.Loaded(
            document = document,
            romanizationAvailable = document.hasRomanization ||
                document.lines.any { it.romanized != null },
            translationAvailable = document.hasTranslation,
            translating = isTranslating,
        )
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
    ): LyricsDocument {
        if (document.lines.all { !it.translated.isNullOrBlank() || it.isInterlude }) {
            return document
        }
        val cacheKey = "$key|tr|${settings.translationTarget}|" +
            settings.romanizationStripsDiacritics + settings.showRomanization
        derived[cacheKey]?.let { return it }

        translating.value = true
        val result = try {
            translator.translate(
                document = document,
                targetTag = settings.translationTarget,
                requireWifi = settings.translationWifiOnly,
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
