package com.betterlyrics.fakeplayer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Publishes a media session that behaves like a real player, so Better Lyrics has
 * something to follow on a device with no music app installed.
 *
 * The track defaults to one whose lyrics exist in every database, and can be pointed at
 * a Japanese title (to exercise romanization) or a made-up one (to exercise the
 * not-found path) without rebuilding.
 */
class FakePlayerActivity : Activity() {

    private class Track(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
    )

    private val tracks = listOf(
        Track("Bohemian Rhapsody", "Queen", "A Night at the Opera", 354_000),
        Track("Lemon", "米津玄師", "Lemon", 256_000),
        Track("Ditto", "NewJeans", "OMG", 185_000),
        Track("Nonexistent Song Title Xyzzy", "No Such Artist", "Nowhere", 123_000),
    )

    private lateinit var session: MediaSession
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var trackIndex = 0
    private var playing = true
    private var positionMs = 0L
    private var positionSetAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            publishState()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        session = MediaSession(this, "FakePlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    playing = true
                    publishState()
                }

                override fun onPause() {
                    playing = false
                    publishState()
                }

                override fun onSeekTo(pos: Long) {
                    positionMs = pos
                    positionSetAt = SystemClock.elapsedRealtime()
                    publishState()
                }

                override fun onSkipToNext() = selectTrack(trackIndex + 1)

                override fun onSkipToPrevious() = selectTrack(trackIndex - 1)
            })
            isActive = true
        }

        setContentView(buildUi())
        publishQueue()
        selectTrack(0)
        handler.post(tick)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#101014"))
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, 36)
        }
        root.addView(status)

        tracks.forEachIndexed { index, track ->
            root.addView(
                Button(this).apply {
                    text = "${track.title} — ${track.artist}"
                    setOnClickListener { selectTrack(index) }
                },
            )
        }

        root.addView(
            Button(this).apply {
                text = "Play / pause"
                setOnClickListener {
                    playing = !playing
                    publishState()
                }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Restart from 0:00"
                setOnClickListener {
                    positionMs = 0
                    positionSetAt = SystemClock.elapsedRealtime()
                    publishState()
                }
            },
        )
        return root
    }

    /**
     * Publish the track list as a queue.
     *
     * Real players mostly do not — Spotify publishes nothing here — so without this there
     * is no way to exercise the app's prefetch of the next track at all. The durations go
     * in the extras because a queue entry has nowhere else to put one, and the app needs
     * one to look a track up under the key it will have when it starts playing.
     */
    private fun publishQueue() {
        session.setQueueTitle("Fake queue")
        session.setQueue(
            tracks.mapIndexed { index, track ->
                MediaSession.QueueItem(
                    MediaDescription.Builder()
                        .setMediaId("fake:${track.title}")
                        .setTitle(track.title)
                        .setSubtitle(track.artist)
                        .setExtras(
                            Bundle().apply {
                                putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                            },
                        )
                        .build(),
                    index.toLong(),
                )
            },
        )
    }

    private fun selectTrack(index: Int) {
        trackIndex = ((index % tracks.size) + tracks.size) % tracks.size
        val track = tracks[trackIndex]
        positionMs = 0
        positionSetAt = SystemClock.elapsedRealtime()

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "fake:${track.title}")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artworkFor(trackIndex))
                .build(),
        )
        publishState()
    }

    private fun publishState() {
        val track = tracks[trackIndex]
        val now = SystemClock.elapsedRealtime()
        if (playing) {
            positionMs = (positionMs + (now - positionSetAt)).coerceAtMost(track.durationMs)
        }
        positionSetAt = now

        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs,
                    if (playing) 1f else 0f,
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                // Which queue entry is playing. Without it a queue says nothing about
                // what comes next.
                .setActiveQueueItemId(trackIndex.toLong())
                .build(),
        )

        status.text = buildString {
            append(track.title).append('\n').append(track.artist).append("\n\n")
            append(if (playing) "playing" else "paused")
            append("  ")
            append(format(positionMs)).append(" / ").append(format(track.durationMs))
        }
    }

    private fun format(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** A distinct colour field per track, so the animated background has something to do. */
    private fun artworkFor(index: Int): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hues = listOf(
            listOf(Color.rgb(212, 62, 92), Color.rgb(70, 60, 190)),
            listOf(Color.rgb(232, 190, 60), Color.rgb(40, 130, 120)),
            listOf(Color.rgb(90, 200, 170), Color.rgb(180, 70, 200)),
            listOf(Color.rgb(120, 120, 130), Color.rgb(50, 50, 60)),
        )[index % 4]

        canvas.drawColor(hues[1])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            size * 0.35f,
            size * 0.35f,
            size * 0.6f,
            hues[0],
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(size * 0.35f, size * 0.35f, size * 0.6f, paint)
        return bitmap
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        session.isActive = false
        session.release()
        super.onDestroy()
    }
}
