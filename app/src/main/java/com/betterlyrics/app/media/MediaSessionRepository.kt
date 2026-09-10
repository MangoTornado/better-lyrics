package com.betterlyrics.app.media

import android.content.ComponentName
import android.content.Context
import com.betterlyrics.app.settings.SettingsStore
import android.content.pm.PackageManager
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches every media session on the device and publishes whichever one is
 * actually playing music, together with a playhead we can extrapolate from.
 *
 * This is what makes the app standalone: no Spotify login, no Web API, no account.
 * Spotify (or YouTube Music, or Apple Music, or a local player) publishes a
 * `MediaSession`; once the user grants notification access we can read it.
 */
class MediaSessionRepository(
    private val context: Context,
    /**
     * Consulted for which players to follow, and told about the ones it has seen.
     *
     * The alternative was filtering further downstream, but a session that is going to be ignored
     * should not be watched at all — an ignored player's notifications would otherwise still drive
     * every republish on the device.
     */
    private val settings: SettingsStore,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val listenerComponent =
        ComponentName(context, MediaNotificationListener::class.java)

    private val sessionManager: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    /** Controllers we have callbacks attached to, keyed by session token identity. */
    private val watched = LinkedHashMap<MediaController, ControllerWatcher>()

    /** Last time each controller reported that it was playing. Breaks ties. */
    private val lastPlayingAt = HashMap<String, Long>()

    /** Covers fetched from a URI, keyed by it. Small: a handful of recent tracks. */
    private val artworkCache = LinkedHashMap<String, Bitmap>()

    /** URIs already tried, so a failure is not retried on every metadata change. */
    private val artworkAttempted = HashSet<String>()

    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var started = false
    private var selected: MediaController? = null
    private var bindAttempts = 0

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            rebind(controllers.orEmpty())
        }

    fun isPermissionGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun notificationAccessIntent() =
        android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun start() {
        val granted = isPermissionGranted()
        _permissionGranted.value = granted
        if (!granted || started || sessionManager == null) return

        started = true
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                listenerComponent,
                handler,
            )
            rebind(sessionManager.getActiveSessions(listenerComponent))
            bindAttempts = 0
        } catch (e: SecurityException) {
            // The toggle is on but the system has not bound our listener service yet.
            // This is the normal path immediately after the user grants access: coming
            // back to the app is not enough on its own, so keep asking for a few seconds
            // rather than leaving the screen stuck on "nothing playing".
            Log.w(TAG, "Not allowed to read sessions yet: ${e.message}")
            started = false
            scheduleBindRetry()
        }
    }

    private fun scheduleBindRetry() {
        if (bindAttempts >= MAX_BIND_ATTEMPTS) return
        val delay = BIND_RETRY_BASE_MS shl bindAttempts
        bindAttempts++
        handler.postDelayed({ if (!started) start() }, delay)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        watched.values.forEach { it.detach() }
        watched.clear()
        selected = null
    }

    /** Call from `onResume`: picks up a permission that was granted while we were away. */
    fun refresh() {
        val granted = isPermissionGranted()
        _permissionGranted.value = granted
        if (!granted) {
            stop()
            _snapshot.value = PlayerSnapshot()
            return
        }
        if (!started) {
            // Coming back from the settings screen: allow a fresh round of retries.
            bindAttempts = 0
            start()
        } else {
            runCatching { rebind(sessionManager?.getActiveSessions(listenerComponent).orEmpty()) }
        }
    }

    private fun rebind(controllers: List<MediaController>) {
        val incoming = controllers.filter { it.packageName != context.packageName }

        // Every player that turns up gets remembered, including the ones being ignored — a list you
        // cannot see is a list you cannot change your mind about.
        for (controller in incoming) {
            settings.notePlayerSeen(controller.packageName, appLabel(controller.packageName))
        }

        // Drop watchers for sessions that went away.
        val gone = watched.keys.filter { existing -> incoming.none { it.sameSession(existing) } }
        gone.forEach { watched.remove(it)?.detach() }

        // Attach to sessions we have not seen yet.
        for (controller in incoming) {
            if (watched.keys.none { it.sameSession(controller) }) {
                val watcher = ControllerWatcher(controller)
                watched[controller] = watcher
                watcher.attach()
            }
        }

        publish()
    }

    private fun MediaController.sameSession(other: MediaController): Boolean =
        sessionToken == other.sessionToken

    /**
     * Re-runs the choice of session, for when the settings changed rather than the sessions did.
     *
     * Without this, turning a player off left whatever it was playing on screen until something else
     * happened — which, if it was the only player, could be a long time.
     */
    fun republish() {
        publish()
    }

    /**
     * Decide which session the lyrics should follow, then publish it.
     *
     * Preference order: something that is playing right now, then whatever played
     * most recently, then anything with usable metadata at all. That keeps the
     * screen on the paused track you were listening to instead of jumping to a
     * silent video session that happens to exist.
     */
    private fun publish() {
        // Read per publish rather than held: switching a player off in Settings should take effect on
        // the next thing that happens, without wiring an observer through to here.
        val ignored = settings.current.ignoredPlayers
        val candidates = watched.keys.filter {
            it.hasUsableMetadata() && it.packageName !in ignored
        }
        if (candidates.isEmpty()) {
            selected = null
            _snapshot.value = PlayerSnapshot()
            return
        }

        val playing = candidates.filter { it.isPlayingNow() }
        val winner = when {
            playing.isNotEmpty() -> playing.maxByOrNull { lastPlayingAt[it.packageName] ?: 0L }
            else -> candidates.maxByOrNull { lastPlayingAt[it.packageName] ?: 0L }
        } ?: candidates.first()

        selected = winner
        _snapshot.value = winner.toSnapshot()
    }

    private fun MediaController.hasUsableMetadata(): Boolean {
        val md = metadata ?: return false
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
        return !title.isNullOrBlank()
    }

    private fun MediaController.isPlayingNow(): Boolean =
        playbackState?.state == PlaybackState.STATE_PLAYING

    /**
     * The queue entry after the one playing.
     *
     * Every step here can fail on a real player, and each failure is a null rather than a
     * guess: a queue may be absent (Spotify publishes none), stale, or published without
     * saying which item is active. Prefetching the wrong song would write a wrong answer
     * into the cache under its key, which is worse than not prefetching at all.
     */
    private fun MediaController.nextInQueue(): TrackInfo? {
        // Reading the queue is a call across to the player for a list that can be a whole album,
        // and this runs on every metadata and state change of every session on the device. Nobody
        // asked for it unless the prefetch is on, so nobody pays for it either.
        if (!settings.current.prefetchNextTrack) return null

        val items = runCatching { queue }.getOrNull()?.takeIf { it.size > 1 } ?: return null
        val activeId = playbackState?.activeQueueItemId ?: return null
        if (activeId == MediaSession.QueueItem.UNKNOWN_ID.toLong()) return null

        val index = items.indexOfFirst { it.queueId == activeId }
        if (index < 0) return null
        val next = items.getOrNull(index + 1)?.description ?: return null

        val title = next.title?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return null
        return TrackInfo(
            title = title,
            // A queue entry has a subtitle, not an artist field; players put the artist
            // there. Album and duration are simply not published, and the lyrics lookup
            // treats both as unknown rather than as blank.
            artist = next.subtitle?.toString()?.trim().orEmpty(),
            album = "",
            durationMs = next.extras
                ?.getLong(MediaMetadata.METADATA_KEY_DURATION, 0L)
                ?.coerceAtLeast(0L) ?: 0L,
            mediaId = next.mediaId,
            artworkUri = next.iconUri?.toString(),
            packageName = packageName,
        )
    }

    private fun MediaController.toSnapshot(): PlayerSnapshot {
        val md = metadata
        val state = playbackState
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val track = TrackInfo(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim(),
            artist = (
                md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ).orEmpty().trim(),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim(),
            durationMs = duration.coerceAtLeast(0L),
            mediaId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            artworkUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            packageName = packageName,
        )

        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val playback = PlaybackPosition(
            isPlaying = isPlaying,
            positionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
            atElapsedRealtime = state?.lastPositionUpdateTime?.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0.05f } ?: 1f,
            durationMs = duration.coerceAtLeast(0L),
        )

        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            // Some players publish only a URI. Reading it needs I/O, so it cannot happen
            // here on the main thread — the snapshot goes out without it and the bitmap
            // arrives in a later one.
            ?: artworkCache[track.artworkUri].also { if (it == null) loadArtwork(track) }

        return PlayerSnapshot(
            track = track,
            playback = playback,
            artwork = art,
            nextTrack = nextInQueue(),
            sourcePackage = packageName,
            sourceLabel = appLabel(packageName),
            transport = state.transport(),
        )
    }

    /**
     * Fetch artwork that was published as a URI rather than a bitmap.
     *
     * One attempt per URI, cached, and the result is published by re-running [publish] so
     * it reaches the UI through the same path as everything else. `content://` covers most
     * players; `http(s)://` happens too, and both are read the same way through the
     * resolver's stream.
     */
    private fun loadArtwork(track: TrackInfo) {
        val uri = track.artworkUri?.takeIf { it.isNotBlank() } ?: return
        if (!artworkAttempted.add(uri)) return

        artworkScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val parsed = android.net.Uri.parse(uri)
                    when (parsed.scheme) {
                        "http", "https" ->
                            java.net.URL(uri).openStream().use { ArtworkDecoding.decode(it) }

                        else -> context.contentResolver.openInputStream(parsed)
                            ?.use { ArtworkDecoding.decode(it) }
                    }
                }.getOrNull()
            } ?: return@launch

            // Bounded: a long listening session must not accumulate covers.
            if (artworkCache.size >= ARTWORK_CACHE_SIZE) {
                artworkCache.keys.firstOrNull()?.let(artworkCache::remove)
            }
            artworkCache[uri] = bitmap
            publish()
        }
    }

    /**
     * What this session says it will accept.
     *
     * `ACTION_PLAY_PAUSE` is the combined flag some players use instead of naming both, so
     * either spelling counts.
     */
    private fun PlaybackState?.transport(): Transport {
        val actions = this?.actions ?: return Transport()
        fun has(flag: Long) = actions and flag != 0L
        return Transport(
            playPause = has(PlaybackState.ACTION_PLAY) ||
                has(PlaybackState.ACTION_PAUSE) ||
                has(PlaybackState.ACTION_PLAY_PAUSE),
            skipNext = has(PlaybackState.ACTION_SKIP_TO_NEXT),
            skipPrevious = has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            seek = has(PlaybackState.ACTION_SEEK_TO),
        )
    }

    /**
     * The player's display name.
     *
     * Android 11 hides other packages from us unless the manifest declares an interest
     * in them, and even with the `<queries>` block a player that exposes no media
     * service stays invisible — so fall back to something readable rather than showing
     * `com.example.someplayer` in the UI.
     */
    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { prettifyPackageName(pkg) }

    private fun prettifyPackageName(pkg: String): String =
        pkg.substringAfterLast('.')
            .replace('_', ' ')
            .split(' ')
            .joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercaseChar() }
            }
            .ifBlank { pkg }

    // ---- transport controls -------------------------------------------------

    fun togglePlayPause() {
        val controller = selected ?: return
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    fun skipNext() = selected?.transportControls?.skipToNext()

    fun skipPrevious() = selected?.transportControls?.skipToPrevious()

    fun seekTo(positionMs: Long) {
        val controller = selected ?: return
        controller.transportControls.seekTo(positionMs.coerceAtLeast(0L))
        // Optimistically move our own playhead so the lyrics jump immediately
        // instead of waiting for the player to echo the seek back.
        _snapshot.value = _snapshot.value.let { current ->
            current.copy(
                playback = current.playback.copy(
                    positionMs = positionMs,
                    atElapsedRealtime = SystemClock.elapsedRealtime(),
                ),
            )
        }
    }

    fun openSourceApp() {
        val pkg = _snapshot.value.sourcePackage ?: return
        val intent = context.packageManager.getLaunchIntentForPackageCompat(pkg) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun PackageManager.getLaunchIntentForPackageCompat(pkg: String) =
        runCatching { getLaunchIntentForPackage(pkg) }.getOrNull()

    // ---- per-controller callbacks ------------------------------------------

    private inner class ControllerWatcher(private val controller: MediaController) {

        /**
         * Whether anything this session says can change what is on screen.
         *
         * An ignored player is watched but not published — the sessions it is competing with are
         * chosen from the ones that are not ignored, so republishing on its behalf can only ever
         * produce the snapshot that is already showing. That matters because the usual reason to
         * ignore a player is that it talks constantly: a video app that reports a new position
         * every second would otherwise drive a full re-read of every session on the device, once a
         * second, for as long as it is open.
         */
        private fun ignored(): Boolean =
            controller.packageName in settings.current.ignoredPlayers

        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (!ignored()) publish()
            }

            // The queue usually arrives after the metadata does, and changes whenever the
            // user reorders it, so the next track is only known by listening for it.
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
                if (!ignored()) publish()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    // Recorded even for an ignored player: it decides the tie-break, and un-ignoring
                    // one should not make it look like it has never played.
                    lastPlayingAt[controller.packageName] = SystemClock.elapsedRealtime()
                }
                if (!ignored()) publish()
            }

            override fun onSessionDestroyed() {
                watched.remove(controller)?.detach()
                publish()
            }
        }

        fun attach() {
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                lastPlayingAt[controller.packageName] = SystemClock.elapsedRealtime()
            }
            controller.registerCallback(callback, handler)
        }

        fun detach() {
            runCatching { controller.unregisterCallback(callback) }
        }
    }

    private companion object {
        const val TAG = "MediaSessionRepo"

        /** Roughly 0.4 s, 0.8 s, 1.6 s, 3.2 s, 6.4 s — long enough to cover the bind. */
        const val MAX_BIND_ATTEMPTS = 5
        const val BIND_RETRY_BASE_MS = 400L
        /**
         * Decoded covers, so the count is a memory budget rather than a convenience. Three: the
         * track on screen, the one before it, and one in flight.
         */
        const val ARTWORK_CACHE_SIZE = 3
    }
}
