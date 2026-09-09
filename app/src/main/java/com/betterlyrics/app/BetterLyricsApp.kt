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
import com.betterlyrics.app.lyrics.provider.SpotifyBrowserToken
import com.betterlyrics.app.lyrics.provider.SpotifyWebToken
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

    /**
     * Below this on the short edge, a cover is soft enough behind full-screen lyrics to be worth
     * replacing. Matches the threshold [ArtworkSearch] uses to decide the same thing.
     */
    private val ARTWORK_GOOD_ENOUGH_PX = 500

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
        // The only place with both a Context and the settings. Installed unconditionally; whether it
        // ever runs is decided per call by `spotifyBrowserTokenEnabled`.
        SpotifyWebToken.harvester = SpotifyBrowserToken(context)
        // A harvest has to outlive the lookup that noticed it was needed: a lookup gets twelve
        // seconds for every source together, and a browser loading the whole player needs longer
        // than that by itself.
        SpotifyWebToken.externalScope = scope

        // Get the token now rather than letting the first lookup discover it is missing. That lookup
        // could only report the fact and start a harvest for the *next* one, which is why the first
        // source test after a launch failed and a second one worked. Does nothing when a valid token
        // is already cached, or when the feature is off.
        SpotifyWebToken.warmUp(settings)

        // The whole app in one line: whatever the phone is playing decides what we look up.
        scope.launch {
            media.snapshot
                .map { it.track }
                .distinctUntilChanged { old, new -> old?.cacheKey == new?.cacheKey }
                .collect { track -> lyrics.setTrack(track) }
        }

        // Warm the cache for whatever is queued next, when the player says what that is.
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
            combine(media.snapshot, settings.settings) { snapshot, settings ->
                ExtrasInputs(
                    track = snapshot.track,
                    // The player's own artwork is an input, not just a fallback: a session that
                    // publishes a URI sends the track first with no bitmap and the bitmap after,
                    // and once that arrives a searched cover may no longer be an improvement.
                    playerArtworkSize = snapshot.artwork
                        ?.let { minOf(it.width, it.height) }
                        ?: 0,
                    enabled = settings.useSpotifyExtras,
                    artworkSource = settings.artworkSource,
                    // Every credential the chain consults. Keyed on the values, so pasting a token
                    // takes effect on the track that is playing rather than the one after it.
                    spotifyToken = settings.spotifyWebToken.orEmpty() + settings.spDcCookie.orEmpty(),
                    appleToken = settings.appleDeveloperToken.orEmpty(),
                    server = if (settings.cacheServerExtrasActive) {
                        settings.cacheServerUrl.orEmpty() + settings.cacheServerKey.orEmpty()
                    } else {
                        ""
                    },
                )
            }
                .distinctUntilChanged()
                // collectLatest, not collect: a track change must abandon the previous track's
                // downloads instead of queueing behind them, or the tempo of the song that just
                // ended gets applied to the one that just started.
                .collectLatest { inputs -> loadExtras(inputs) }
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

    /**
     * Everything the artwork chain reads.
     *
     * A data class rather than a tuple because the list kept growing and the bug it caused was
     * invisible: the flow de-duplicated on a Spotify track id, so for any player that publishes
     * none — most of them — every track looked identical to the last and the Apple and cache-server
     * steps never ran at all.
     */
    private data class ExtrasInputs(
        val track: TrackInfo?,
        val playerArtworkSize: Int,
        val enabled: Boolean,
        val artworkSource: ArtworkSource,
        val spotifyToken: String,
        val appleToken: String,
        val server: String,
    )

    private suspend fun loadExtras(inputs: ExtrasInputs) {
        _extras.value = NowPlayingExtras()
        _searchedArtwork.value = null
        if (!inputs.enabled) return

        val track = inputs.track?.takeIf { !it.isEmpty } ?: return
        val trackId = track.spotifyTrackId
        val settingsNow = settings.current

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

        // Nothing from Spotify — no token, no match, or an expired token. Apple next: it can
        // only match on name, but its token lasts months rather than an hour.
        if (appleArtwork.isAvailable) {
            val images = runCatching { appleArtwork.imagesFor(track) }.getOrNull()
            if (images != null) {
                // The developer token alone gets this, so it works without a subscription.
                images.isrc?.let { lyrics.noteIsrc(track, it) }
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

        // Whatever the server found for itself, back when it had a token.
        if (settingsNow.cacheServerExtrasActive) {
            val cached = runCatching { cacheServerExtras.fetch(track) }.getOrNull()
            if (cached != null) {
                // The server collects these from its own tokens. This is how a phone with none
                // still gets an exact match.
                cached.isrc?.let { lyrics.noteIsrc(track, it) }
                val cover = cached.coverUrl?.let { url ->
                    runCatching { cacheServerExtras.image(url) }.getOrNull()
                }
                val artist = cached.artistImageUrl?.let { url ->
                    runCatching { cacheServerExtras.image(url) }.getOrNull()
                }
                if (cover != null || artist != null || cached.tempo != null) {
                    if (media.snapshot.value.track?.cacheKey != track.cacheKey) return
                    _extras.value = NowPlayingExtras(
                        trackId = trackId,
                        artistImage = artist,
                        cover = cover,
                        tempo = cached.tempo,
                    )
                    if (cover != null) return
                }
            }
        }

        // Last: the keyless search, if a source is chosen for it.
        //
        // Reached whenever nothing above actually produced a cover — which is the fix for a real
        // failure, not a nicety. It used to stand down whenever a token merely *existed*, so an
        // expired Apple token or a cache server holding nothing for this track left the screen with
        // the player's thumbnail and no way to improve on it.
        searchForArtwork(track, inputs)
    }

    /**
     * A bigger cover from a keyless source, when nothing with a token could supply one.
     *
     * Judged against the artwork the player published *now*, from the same snapshot the flow keyed
     * on — not by re-reading it. A session that publishes a URI sends the bitmap in a later
     * emission, and this function runs again for that emission; deciding from a live read would let
     * the two disagree about which artwork it was comparing.
     */
    private suspend fun searchForArtwork(track: TrackInfo, inputs: ExtrasInputs) {
        if (inputs.artworkSource == ArtworkSource.PLAYER) return
        // Nothing above found one, so there is something to improve on by definition — but the
        // player's own may already be good enough.
        if (inputs.playerArtworkSize >= ARTWORK_GOOD_ENOUGH_PX) return

        val found = runCatching { artworkSearch.find(track, inputs.artworkSource) }.getOrNull()
            ?: return
        if (media.snapshot.value.track?.cacheKey != track.cacheKey) return
        // And only if it is actually an improvement on what the player gave us.
        if (minOf(found.width, found.height) <= inputs.playerArtworkSize) return
        _searchedArtwork.value = found
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
