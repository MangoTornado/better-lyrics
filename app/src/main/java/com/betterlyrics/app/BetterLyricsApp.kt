package com.betterlyrics.app

import android.app.Application
import android.content.Context
import com.betterlyrics.app.lyrics.LyricsCache
import com.betterlyrics.app.lyrics.LyricsRepository
import com.betterlyrics.app.lyrics.provider.AmllTtmlProvider
import com.betterlyrics.app.lyrics.provider.AppleMusicProvider
import com.betterlyrics.app.lyrics.provider.CacheServerProvider
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
import com.betterlyrics.app.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.betterlyrics.app.media.ArtworkSearch
import com.betterlyrics.app.settings.ArtworkSource
import android.graphics.Bitmap
import com.betterlyrics.app.media.TrackInfo
import com.betterlyrics.app.media.AppleArtwork
import com.betterlyrics.app.media.APPLE_IMAGE_SIZE
import com.betterlyrics.app.media.CacheServerExtras
import com.betterlyrics.app.media.IsrcStore
import com.betterlyrics.app.media.ServerSource

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
    /**
     * ISRCs the app has learned, remembered across launches.
     *
     * The tokens that produce them expire; an ISRC does not. So a track played once with a token
     * in hand — or once against a cache server that had one — is matched exactly from then on.
     */
    private val isrcStore = IsrcStore(context)

    /** Every provider that exists; Settings decides which are asked and in what order. */
    val providers: List<LyricsProvider> = listOf(
        localLyrics,
        CacheServerProvider(settings),
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
        isrcStore = isrcStore,
        providers = providers,
        scope = scope,
    )

    /** Finds and installs new releases, since nothing else will. */
    val updater = Updater(context, settings)

    private val spotifyExtras = SpotifyExtras(settings)
    private val appleArtwork = AppleArtwork(settings)
    private val cacheServerExtras = CacheServerExtras(settings)

    private val artworkSearch = ArtworkSearch()

    private val _extras = MutableStateFlow(NowPlayingExtras())

    /**
     * The parts of "now playing" only Spotify can tell us: the artist's image, the cover
     * at full size, and the tempo. Empty without a cookie, and empty the instant the track
     * changes so a stale artist image never lingers over the wrong song.
     */
    val extras: StateFlow<NowPlayingExtras> = _extras.asStateFlow()

    /** True when a Spotify cookie is present, so Settings can say whether extras will work. */
    val spotifyExtrasAvailable: Boolean get() = spotifyExtras.isAvailable

    /** What the cache server says about its own sources, for the developer menu. */
    suspend fun cacheServerStatus(): List<ServerSource>? = cacheServerExtras.status()

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

        // Warm the cache for whatever is queued next, when the player says what that is.
        scope.launch {
            combine(
                media.snapshot.map { it.track },
                settings.settings.map { it.artworkSource },
            ) { track, source -> track to source }
                .distinctUntilChanged { old, new ->
                    old.first?.cacheKey == new.first?.cacheKey && old.second == new.second
                }
                .collectLatest { (track, source) -> loadSearchedArtwork(track, source) }
        }

        scope.launch {
            media.snapshot
                .map { it.nextTrack }
                .distinctUntilChanged { old, new -> old?.cacheKey == new?.cacheKey }
                .collect { next -> lyrics.prefetchNext(next) }
        }

        // Keyed on the settings as well as the track: turning "use extras from Spotify"
        // off has to drop the artist image and tempo now rather than at the next track, and
        // pasting a cookie has to take effect on what is playing rather than being invisible
        // until the song changes.
        scope.launch {
            combine(
                media.snapshot.map { it.track?.spotifyTrackId },
                settings.settings.map { it.useSpotifyExtras to it.spDcCookie },
            ) { trackId, (enabled, cookie) -> Triple(trackId, enabled, cookie) }
                .distinctUntilChanged()
                // collectLatest, not collect: a track change must abandon the previous
                // track's downloads instead of queueing behind them, or the tempo of the
                // song that just ended gets applied to the one that just started.
                .collectLatest { (trackId, _, _) -> loadExtras(trackId) }
        }
    }

    private val _searchedArtwork = MutableStateFlow<Bitmap?>(null)

    /**
     * A larger cover found by searching, when the player's own is small and a source is on.
     *
     * Separate from [extras] because it has nothing to do with Spotify and must keep working
     * for someone who has pasted no tokens at all.
     */
    val searchedArtwork: StateFlow<Bitmap?> = _searchedArtwork.asStateFlow()

    private suspend fun loadSearchedArtwork(track: TrackInfo?, source: ArtworkSource) {
        _searchedArtwork.value = null
        if (track == null || track.isEmpty || source == ArtworkSource.PLAYER) return
        // A token beats a title search, so do not spend a request competing with one. This is
        // also why the search is a separate flow: it must not wait on Spotify to find out.
        if (spotifyExtras.isAvailable || appleArtwork.isAvailable) return
        // And for the server, which may be holding Spotify's own artwork from a day when
        // somebody had a token.
        if (settings.current.cacheServerExtrasActive) return
        // Only worth a request when what the player gave us is not good enough.
        if (!artworkSearch.wouldImproveOn(media.snapshot.value.artwork)) return

        val found = runCatching { artworkSearch.find(track, source) }.getOrNull() ?: return
        // The track may have changed while that was in flight.
        if (media.snapshot.value.track?.cacheKey != track.cacheKey) return
        _searchedArtwork.value = found
    }

    /**
     * The artist image, the full-size cover and the tempo.
     *
     * Spotify first, whenever a token is there: it identifies the track by id rather than by
     * name, so it cannot be matching the wrong song, and it is the only one of these that has
     * the tempo. Apple second — it can only match on title and artist, but its token lasts
     * months rather than an hour, so it is what still works tomorrow.
     */
    private suspend fun loadExtras(trackId: String?) {
        _extras.value = NowPlayingExtras()
        val settingsNow = settings.current
        if (!settingsNow.useSpotifyExtras) return
        val track = media.snapshot.value.track

        if (trackId != null) {
            // Spotify's endpoints report an outage by throwing rather than returning null, so
            // a 503 here would otherwise take down the whole collector.
            val details = runCatching { spotifyExtras.extrasFor(trackId) }.getOrNull()
            if (details != null) {
                // The token expires within the hour; this does not.
                details.isrc?.let { isrc ->
                    media.snapshot.value.track?.let { lyrics.noteIsrc(it, isrc) }
                }
                // Publish the tempo straight away; the images arrive when they arrive.
                _extras.value = NowPlayingExtras(trackId = trackId, tempo = details.tempo)

                val cover = details.coverUrl?.let { url ->
                    runCatching { spotifyExtras.image(url) }.getOrNull()
                }
                val artist = details.artistImageUrl?.let { url ->
                    runCatching { spotifyExtras.image(url) }.getOrNull()
                }

                // Guard against a track change while the images were downloading.
                if (media.snapshot.value.track?.spotifyTrackId != trackId) return
                _extras.value = NowPlayingExtras(
                    trackId = trackId,
                    artistImage = artist,
                    cover = cover,
                    tempo = details.tempo,
                )
                if (cover != null || artist != null) return
            }
        }

        if (track == null) return

        // Nothing from Spotify — no token, no match, or an expired token. Apple next: it can
        // only match on name, but its token lasts months rather than an hour.
        if (appleArtwork.isAvailable) {
            val images = runCatching { appleArtwork.imagesFor(track) }.getOrNull()
            if (images != null) {
                val cover = images.coverUrl?.let { url ->
                    runCatching { appleArtwork.image(url, APPLE_IMAGE_SIZE) }.getOrNull()
                }
                val artist = images.artistImageUrl?.let { url ->
                    runCatching { appleArtwork.image(url, APPLE_IMAGE_SIZE) }.getOrNull()
                }
                if (cover != null || artist != null) {
                    if (media.snapshot.value.track?.cacheKey != track.cacheKey) return
                    _extras.value = _extras.value.copy(artistImage = artist, cover = cover)
                    return
                }
            }
        }

        // No token at all. Whatever the server found for itself, back when it had one.
        if (!settingsNow.cacheServerExtrasActive) return
        val cached = runCatching { cacheServerExtras.fetch(track) }.getOrNull() ?: return
        // The server has been collecting these from its own tokens. This is how a phone with none
        // still gets an exact match.
        cached.isrc?.let { lyrics.noteIsrc(track, it) }
        val cover = cached.coverUrl?.let { url ->
            runCatching { cacheServerExtras.image(url) }.getOrNull()
        }
        val artist = cached.artistImageUrl?.let { url ->
            runCatching { cacheServerExtras.image(url) }.getOrNull()
        }
        if (cover == null && artist == null && cached.tempo == null) return
        if (media.snapshot.value.track?.cacheKey != track.cacheKey) return
        _extras.value = NowPlayingExtras(
            trackId = trackId,
            artistImage = artist,
            cover = cover,
            tempo = cached.tempo,
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
