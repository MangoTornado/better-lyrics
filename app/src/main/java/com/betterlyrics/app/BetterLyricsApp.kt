package com.betterlyrics.app

import android.app.Application
import android.content.Context
import com.betterlyrics.app.lyrics.LyricsCache
import com.betterlyrics.app.lyrics.LyricsRepository
import com.betterlyrics.app.lyrics.provider.AmllTtmlProvider
import com.betterlyrics.app.lyrics.provider.AppleMusicProvider
import com.betterlyrics.app.lyrics.provider.LocalLyricsStore
import com.betterlyrics.app.lyrics.provider.LrcLibProvider
import com.betterlyrics.app.lyrics.provider.LyricsProvider
import com.betterlyrics.app.lyrics.provider.MusixmatchProvider
import com.betterlyrics.app.lyrics.provider.NeteaseProvider
import com.betterlyrics.app.lyrics.provider.SpotifyLyricsProvider
import com.betterlyrics.app.lyrics.romanize.Romanizer
import com.betterlyrics.app.lyrics.translate.LyricsTranslator
import com.betterlyrics.app.media.MediaSessionRepository
import com.betterlyrics.app.media.NowPlayingExtras
import com.betterlyrics.app.media.SpotifyExtras
import com.betterlyrics.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hand-rolled container instead of a DI framework: there are eight objects, they are
 * all singletons, and the wiring between them is the one interesting line in the file.
 */
class AppContainer(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)
    val media = MediaSessionRepository(context)

    private val cache = LyricsCache(context)
    private val romanizer = Romanizer()
    private val translator = LyricsTranslator()
    val localLyrics = LocalLyricsStore(context)

    /** Every provider that exists; Settings decides which are asked and in what order. */
    val providers: List<LyricsProvider> = listOf(
        localLyrics,
        AppleMusicProvider(settings),
        AmllTtmlProvider(settings),
        SpotifyLyricsProvider(settings),
        NeteaseProvider(settings),
        MusixmatchProvider(settings),
        LrcLibProvider(settings),
    )

    val lyrics = LyricsRepository(
        context = context,
        settingsStore = settings,
        cache = cache,
        romanizer = romanizer,
        translator = translator,
        localStore = localLyrics,
        providers = providers,
        scope = scope,
    )

    private val spotifyExtras = SpotifyExtras(settings)

    private val _extras = MutableStateFlow(NowPlayingExtras())

    /**
     * The parts of "now playing" only Spotify can tell us: the artist's image, the cover
     * at full size, and the tempo. Empty without a cookie, and empty the instant the track
     * changes so a stale artist image never lingers over the wrong song.
     */
    val extras: StateFlow<NowPlayingExtras> = _extras.asStateFlow()

    /** True when a Spotify cookie is present, so Settings can say whether extras will work. */
    val spotifyExtrasAvailable: Boolean get() = spotifyExtras.isAvailable

    /** How much disk the cached lyrics take, for the settings screen. */
    suspend fun lyricsCacheSizeBytes(): Long = cache.sizeBytes()

    init {
        // The whole app in one line: whatever the phone is playing decides what we look up.
        scope.launch {
            media.snapshot
                .map { it.track }
                .distinctUntilChanged { old, new -> old?.cacheKey == new?.cacheKey }
                .collect { track -> lyrics.setTrack(track) }
        }

        scope.launch {
            media.snapshot
                .map { it.track?.spotifyTrackId }
                .distinctUntilChanged()
                .collect { trackId -> loadExtras(trackId) }
        }
    }

    private suspend fun loadExtras(trackId: String?) {
        _extras.value = NowPlayingExtras()
        if (trackId == null || !settings.current.useSpotifyExtras) return

        val details = spotifyExtras.extrasFor(trackId) ?: return
        // Publish the tempo straight away; the images arrive when they arrive.
        _extras.value = NowPlayingExtras(trackId = trackId, tempo = details.tempo)

        val cover = details.coverUrl?.let { spotifyExtras.image(it) }
        val artist = details.artistImageUrl?.let { spotifyExtras.image(it) }

        // Guard against a track change while the images were downloading.
        if (media.snapshot.value.track?.spotifyTrackId != trackId) return
        _extras.value = NowPlayingExtras(
            trackId = trackId,
            artistImage = artist,
            cover = cover,
            tempo = details.tempo,
        )
    }
}

class BetterLyricsApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
