package com.betterlyrics.app.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier

/**
 * Scroll a title that does not fit — while the song is playing, and not otherwise.
 *
 * A marquee is the one thing on this screen that animates on its own account, so left running it
 * decides the whole app's idle behaviour: a long title would keep the frame pipeline awake at the
 * display's refresh rate for as long as the lyrics are open, with the music paused, the lyric
 * animation settled and the background held still. One line of text is cheap to draw; never being
 * allowed to stop drawing is not.
 *
 * Zero iterations does not animate, which leaves a long title clipped exactly as it would be
 * anywhere else in the app.
 */
fun Modifier.titleMarquee(playing: Boolean): Modifier =
    basicMarquee(iterations = if (playing) Int.MAX_VALUE else 0)
