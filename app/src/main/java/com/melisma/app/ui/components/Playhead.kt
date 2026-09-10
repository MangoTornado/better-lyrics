package com.melisma.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.melisma.app.media.PlaybackPosition
import kotlinx.coroutines.delay

/**
 * Where the song has got to, for a progress bar.
 *
 * A progress bar does not need 60 fps; five updates a second is imperceptibly smooth and costs
 * nothing. Two things keep it honest about the "costs nothing":
 *
 * - It only runs while a window of ours is on screen. A composition outlives the window it was
 *   drawn in, so an unguarded ticker keeps going for as long as the process does — and this app is
 *   deliberately hard to kill.
 * - A paused song reports the same position every time, and writing an unchanged value to a
 *   snapshot state is not a change, so nothing recomposes and nothing redraws. That is why this
 *   does not need to watch `isPlaying` for itself.
 *
 * Returned as a mutable state because a scrub has to be able to move it: releasing the thumb sets
 * the position it asked for, so the bar stays where the finger left it rather than snapping back
 * for the fifth of a second before the player echoes the seek.
 */
@Composable
fun rememberPlayheadMs(playback: PlaybackPosition): MutableState<Long> {
    val positionMs = remember(playback) { mutableStateOf(playback.currentMs()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playback, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                positionMs.value = playback.currentMs()
                delay(PLAYHEAD_INTERVAL_MS)
            }
        }
    }
    return positionMs
}

private const val PLAYHEAD_INTERVAL_MS = 200L
