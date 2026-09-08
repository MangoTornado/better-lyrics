package com.betterlyrics.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterlyrics.app.media.PlaybackPosition
import com.betterlyrics.app.media.Transport
import com.betterlyrics.app.media.TrackInfo
import kotlinx.coroutines.delay

/**
 * The album-art half of Cinema view: big cover, track details, scrubber, transport, and
 * an optional volume band.
 *
 * Spicy Lyrics calls the not-fullscreen version of this its "NowBar" and lets you flip it
 * to the other side of the lyrics; that flip is [MediaPanelSide] in Settings, and the
 * button for it sits in the view controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPanel(
    track: TrackInfo,
    playback: PlaybackPosition,
    artwork: Bitmap?,
    accent: Color,
    transport: Transport,
    showVolume: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var positionMs by remember { mutableStateOf(playback.currentMs()) }
    LaunchedEffect(playback) {
        while (true) {
            positionMs = playback.currentMs()
            delay(200)
        }
    }

    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    val duration = playback.durationMs.coerceAtLeast(1L)
    val fraction = if (scrubFraction >= 0f) {
        scrubFraction
    } else {
        (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    }

    BoxWithConstraints(
        modifier
            // The panel shares the screen with the lyrics; it must never bleed into them,
            // however little room it is given.
            .clipToBounds()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        val panelHeight = maxHeight
        // On a short panel, drop the least important rows rather than overflowing.
        val roomForAlbum = panelHeight > 300.dp
        val roomForTransport = panelHeight > 210.dp
        val roomForOpen = panelHeight > 360.dp

        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The cover takes the space the text and controls leave over, so the panel
            // works the same whether it is the top of a phone or the side of a tablet.
            BoxWithConstraints(
                Modifier.weight(1f, fill = false).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                Box(
                    Modifier
                        .size(side)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.07f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (artwork != null) {
                        Image(
                            bitmap = artwork.asImageBitmap(),
                            contentDescription = "Album artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            AppIcons.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(side * 0.3f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = track.title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(
                text = track.artist.ifBlank { "Unknown artist" },
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (roomForAlbum && track.album.isNotBlank()) {
                Text(
                    text = track.album,
                    color = Color.White.copy(alpha = 0.42f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ScrubBar(
                fraction = fraction,
                accent = accent,
                enabled = transport.seek,
                onScrub = { scrubFraction = it },
                onScrubFinished = {
                    if (scrubFraction >= 0f) {
                        val target = (scrubFraction * duration).toLong()
                        onSeek(target)
                        positionMs = target
                    }
                    scrubFraction = -1f
                },
                leftLabel = formatTime((fraction * duration).toLong()),
                rightLabel = formatTime(playback.durationMs),
            )

            // Per action rather than all-or-nothing: see NowBar for why.
            if (transport.any && roomForTransport) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(onClick = onPrevious, enabled = transport.skipPrevious) {
                        Icon(
                            AppIcons.SkipPrevious,
                            contentDescription = "Previous track",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = onTogglePlay,
                        enabled = transport.playPause,
                        modifier = Modifier.size(54.dp),
                    ) {
                        Icon(
                            if (playback.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                            contentDescription = if (playback.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onNext, enabled = transport.skipNext) {
                        Icon(
                            AppIcons.SkipNext,
                            contentDescription = "Next track",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }

            if (showVolume) {
                VolumeRow(volume = volume, accent = accent, onVolumeChange = onVolumeChange)
            }

            if (roomForOpen) {
                Text(
                    text = "Open player",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenSource)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScrubBar(
    fraction: Float,
    accent: Color,
    /** False for a player that publishes progress but does not accept seeks. */
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    leftLabel: String,
    rightLabel: String,
) {
    Slider(
        value = fraction,
        enabled = enabled,
        onValueChange = onScrub,
        onValueChangeFinished = onScrubFinished,
        thumb = {
            Box(
                Modifier
                    .size(11.dp)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(leftLabel, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        Text(rightLabel, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

/**
 * Volume band, matching the one Spicy Lyrics puts on the artwork in Fullscreen, Cinema
 * View and Popup Lyrics. Drives the device's music stream, not the player's own mixer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeRow(volume: Float, accent: Color, onVolumeChange: (Float) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (volume <= 0.001f) AppIcons.VolumeMute else AppIcons.VolumeUp,
            contentDescription = "Volume",
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            thumb = {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            },
            track = { state ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(state.value.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent.copy(alpha = 0.85f)),
                    )
                }
            },
            modifier = Modifier
                .weight(1f)
                .height(22.dp),
        )
    }
}

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
