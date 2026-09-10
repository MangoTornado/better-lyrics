package com.betterlyrics.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterlyrics.app.media.PlaybackPosition
import com.betterlyrics.app.media.Transport
import com.betterlyrics.app.media.TrackInfo

/**
 * The now-playing bar: cover, title, a scrubbable progress line, and transport.
 *
 * Playback is driven by whichever app owns the media session, so these are remote
 * controls — the seek here moves Spotify's playhead, and the lyrics follow because they
 * follow the session, not the button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowBar(
    track: TrackInfo,
    playback: PlaybackPosition,
    artwork: Bitmap?,
    accent: Color,
    transport: Transport,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var positionMs by rememberPlayheadMs(playback)

    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    val duration = playback.durationMs.coerceAtLeast(1L)
    val fraction = if (scrubFraction >= 0f) {
        scrubFraction
    } else {
        (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .pointerInput(Unit) { detectTapGestures { onOpenSource() } },
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    androidx.compose.foundation.Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(54.dp),
                    )
                } else {
                    Icon(
                        AppIcons.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.titleMarquee(playback.isPlaying),
                )
                Text(
                    text = track.artist.ifBlank { "Unknown artist" },
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Each button asks about its own action: a live stream offers play and pause
            // but not skip, and a control that is shown and ignored is worse than one that
            // is visibly unavailable.
            if (transport.any) {
                IconButton(onClick = onPrevious, enabled = transport.skipPrevious) {
                    Icon(
                        AppIcons.SkipPrevious,
                        contentDescription = "Previous track",
                        tint = Color.White.copy(alpha = 0.85f),
                    )
                }
                IconButton(onClick = onTogglePlay, enabled = transport.playPause) {
                    Icon(
                        if (playback.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(onClick = onNext, enabled = transport.skipNext) {
                    Icon(
                        AppIcons.SkipNext,
                        contentDescription = "Next track",
                        tint = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // A Slider rather than a hand-drawn bar with hand-rolled gestures: scrubbing has
        // to handle tap, drag, touch slop, cancellation and TalkBack, and the platform
        // control already does all of that. Only the thumb and track are replaced, to get
        // a thin media scrubber instead of the chunky default.
        Slider(
            value = fraction,
            // A player that does not accept seeks still shows progress; it just cannot be
            // dragged.
            enabled = transport.seek,
            onValueChange = { scrubFraction = it.coerceIn(0f, 1f) },
            onValueChangeFinished = {
                if (scrubFraction >= 0f) {
                    val target = (scrubFraction * duration).toLong()
                    onSeek(target)
                    positionMs = target
                }
                scrubFraction = -1f
            },
            thumb = {
                Box(
                    Modifier
                        .size(if (scrubFraction >= 0f) 14.dp else 9.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            },
            track = { state ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(state.value.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime((fraction * duration).toLong()),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
            Text(
                text = formatTime(playback.durationMs),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
        }
    }
}
