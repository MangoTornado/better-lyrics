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

data class PlayerSnapshot(
    val track: TrackInfo? = null,
    val playback: PlaybackPosition = PlaybackPosition.IDLE,
    val artwork: Bitmap? = null,
    val sourcePackage: String? = null,
    val sourceLabel: String? = null,
    val canControl: Boolean = false,
) {
    val hasTrack: Boolean get() = track != null && !track.isEmpty
}
