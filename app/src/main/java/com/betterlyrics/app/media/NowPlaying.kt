package com.betterlyrics.app.media

import android.graphics.Bitmap
import android.os.SystemClock

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** `spotify:track:…` style id when the player exposes one; used for exact lookups. */
    val mediaId: String? = null,
    val artworkUri: String? = null,
    val packageName: String = "",
) {
    val spotifyTrackId: String?
        get() = mediaId?.let { id ->
            when {
                id.startsWith("spotify:track:") -> id.removePrefix("spotify:track:")
                    .takeIf { it.length == 22 }
                else -> null
            }
        }

    /** Stable key for the lyrics cache. Deliberately excludes artwork and app. */
    val cacheKey: String
        get() = spotifyTrackId?.let { "sp:$it" }
            ?: buildString {
                append(title.lowercase().trim())
                append('|')
                append(artist.lowercase().trim())
                append('|')
                // Bucket the duration so a one-second reporting difference between
                // players doesn't split the cache entry in two.
                append(durationMs / 2000)
            }

    val isEmpty: Boolean get() = title.isBlank() && artist.isBlank()

    /**
     * Whether this track — read from a player's queue rather than from what is playing —
     * can be looked up now and found again later under the same [cacheKey].
     *
     * A queue entry publishes far less than the metadata of a playing track: usually a
     * title and an artist, and no duration. `title|artist|0` is not the key the same track
     * gets once it starts and its real duration is known, so prefetching it would spend
     * the request and never be read back. A Spotify id or a duration makes the two keys
     * agree; without either, the honest answer is not to bother.
     */
    val isPrefetchable: Boolean
        get() = title.isNotBlank() && (spotifyTrackId != null || durationMs > 0)
}

data class PlaybackPosition(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    /** [SystemClock.elapsedRealtime] when [positionMs] was reported. */
    val atElapsedRealtime: Long = 0L,
    val speed: Float = 1f,
    val durationMs: Long = 0L,
) {
    /**
     * Where the playhead is *now*. Media sessions only publish a position when
     * something changes, so between updates we extrapolate from the wall clock —
     * exactly what every "synced lyrics" implementation has to do.
     */
    fun currentMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0L)
        val drift = ((nowElapsed - atElapsedRealtime) * speed).toLong()
        val projected = positionMs + drift
        return if (durationMs > 0) projected.coerceIn(0L, durationMs) else projected.coerceAtLeast(0L)
    }

    companion object {
        val IDLE = PlaybackPosition()
    }
}

/**
 * Which transport operations the player actually supports.
 *
 * A session that publishes a playback state has not thereby promised to accept every
 * command: a live stream cannot be sought or rewound, and a restricted session may only
 * offer play and pause. It says which in `PlaybackState.actions`, and a control that is
 * shown but silently ignored is worse than one that is visibly unavailable.
 */
data class Transport(
    val playPause: Boolean = false,
    val skipNext: Boolean = false,
    val skipPrevious: Boolean = false,
    val seek: Boolean = false,
) {
    val any: Boolean get() = playPause || skipNext || skipPrevious || seek
}

data class PlayerSnapshot(
    val track: TrackInfo? = null,
    val playback: PlaybackPosition = PlaybackPosition.IDLE,
    val artwork: Bitmap? = null,
    val sourcePackage: String? = null,
    val sourceLabel: String? = null,
    val transport: Transport = Transport(),
    /**
     * The track queued after this one, when the player publishes a queue at all.
     *
     * Only used to warm the lyrics cache ahead of time. Most players publish nothing
     * here — Spotify among them — so anything depending on it must treat null as the
     * normal case.
     */
    val nextTrack: TrackInfo? = null,
) {
    val hasTrack: Boolean get() = track != null && !track.isEmpty

    /** True when the player accepts at least one command, so a control row is worth showing. */
    val canControl: Boolean get() = transport.any
}
