package com.melisma.app.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.settings.FuriganaMode
import com.melisma.app.settings.Settings
import com.melisma.app.settings.TranslationSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** How long to keep drawing after the playhead stops, so the springs can come to rest. */
private const val SPRING_SETTLE_NANOS = 900_000_000L

/**
 * How often to look for something to do while the screen is still.
 *
 * 10 Hz: negligible next to a 60 Hz frame callback, and short enough that pressing play
 * elsewhere does not visibly lag — the position jump that follows is treated as a seek and
 * snapped, so the lyrics land rather than drifting into place late.
 */
private const val IDLE_POLL_MS = 100L

/**
 * The lyrics surface.
 *
 * Everything that changes 60 times a second lives in the draw phase: the composable
 * reads the frame clock, hands the raw canvas to [LyricsRenderer], and never
 * recomposes while a song plays. Recomposition only happens when the document, the
 * type size or a setting changes — which is also when the layout is rebuilt.
 */
@Composable
fun LyricsView(
    document: LyricsDocument,
    settings: Settings,
    /** Called in the draw phase; must be cheap and must not read snapshot state. */
    positionMsProvider: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Tighter type and spacing: the floating window, or split screen. */
    compact: Boolean = false,
    /** While set, tapping a line picks it out to copy instead of seeking to it. */
    selectionMode: Boolean = false,
    selectedIndices: Set<Int> = emptySet(),
    onSelectLine: (Int) -> Unit = {},
    onLongPressLine: (LyricLine) -> Unit = {},
    /** Bump to pull the scroll back to the line that is playing. */
    jumpToActiveSignal: Int = 0,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val tight = compact || settings.compactMode
        val sidePaddingPx = with(density) { (if (tight) 12.dp else 22.dp).toPx() }
        val contentWidthPx = (widthPx - sidePaddingPx * 2f).coerceAtLeast(1f)

        // Type scales with the width the way Spicy Lyrics' container-relative sizing
        // does — but a floating window is short as well as narrow, so height gets a say
        // too, or four lines of lyrics would not fit in it.
        val fontSizePx = remember(widthPx, heightPx, settings.fontScale, tight, density) {
            val minPx = with(density) { (if (tight) 11 else 21).sp.toPx() }
            val maxPx = with(density) { (if (tight) 26 else 40).sp.toPx() }
            val byWidth = widthPx * (if (tight) 0.075f else 0.082f)
            val byHeight = heightPx * (if (tight) 0.17f else 0.5f)
            minOf(byWidth, byHeight).coerceIn(minPx, maxPx) * settings.fontScale
        }

        val useRomanization = settings.showRomanization
        // Furigana glosses the original text, so it only makes sense while romanization
        // is off — and there is no room for it in the floating window.
        val furiganaHiragana = when {
            useRomanization || tight -> null
            settings.furigana == FuriganaMode.HIRAGANA -> true
            settings.furigana == FuriganaMode.KATAKANA -> false
            else -> null
        }
        // Either source ends up in the same place: a smaller row under the line. The
        // difference between them is who wrote the text, which is settled by now.
        val showTranslation = settings.translationSource != TranslationSource.OFF && !tight
        val showCredits = settings.showCredits && !tight

        val metrics = remember(fontSizePx, settings.simpleMode, showTranslation, settings.font, tight) {
            LyricsMetrics(
                fontSizePx = fontSizePx,
                simpleMode = settings.simpleMode,
                showSecondaryLine = showTranslation,
                fontFamily = settings.font.familyName,
                compact = tight,
            )
        }

        val layout = remember(
            document, metrics, contentWidthPx, useRomanization, showTranslation,
            settings.duetLinePadding, showCredits, furiganaHiragana,
        ) {
            LyricsLayoutBuilder.build(
                document = document,
                metrics = metrics,
                widthPx = contentWidthPx,
                useRomanization = useRomanization,
                showTranslation = showTranslation,
                duetPadding = settings.duetLinePadding,
                showCredits = showCredits,
                furiganaHiragana = furiganaHiragana,
            )
        }

        val renderer = remember { LyricsRenderer(layout) }
        SideEffect {
            renderer.layout = layout
            renderer.simpleMode = settings.simpleMode
            renderer.blurEnabled = settings.lineBlur
            renderer.minimalMode = settings.minimalMode
            renderer.animationStyle = settings.textAnimationStyle
            renderer.viewportHeight = heightPx
            renderer.contentLeftPx = sidePaddingPx
            renderer.selectedIndices = selectedIndices
        }

        LaunchedEffect(jumpToActiveSignal) {
            if (jumpToActiveSignal > 0) renderer.jumpToActive()
        }

        val frameNanos = remember { mutableLongStateOf(0L) }

        // The frame loop must read the *current* playhead, and `positionMsProvider` is a new lambda
        // every time playback changes — a new track, a play, a pause. Keyed only on the renderer,
        // the loop captured the first one and held it forever, so on a track change it was asking
        // the previous track where it had got to.
        //
        // That is invisible for a few seconds and then looks like a freeze: the stale lambda keeps
        // extrapolating, so frames are requested and the new lyrics animate correctly, until the
        // projection hits the old track's duration and `currentMs` clamps it. From then on the
        // position never changes, nothing looks like movement, and the loop stops asking for frames
        // — while the draw, which is recomposed and does have the fresh lambda, would have drawn the
        // right thing if only it had been called. Touching the screen woke it and it jumped.
        //
        // `rememberUpdatedState` rather than adding a key: restarting the loop on every play/pause
        // would also reset the idle timer, which is the thing the loop exists to get right.
        val currentPosition by rememberUpdatedState(positionMsProvider)

        LaunchedEffect(renderer) {
            var lastPosition = Long.MIN_VALUE
            var restingSince = 0L
            while (true) {
                var awake = false
                withFrameNanos { nanos ->
                    val position = currentPosition()
                    val moved = position != lastPosition
                    if (moved) {
                        lastPosition = position
                        restingSince = 0L
                    } else if (restingSince == 0L) {
                        restingSince = nanos
                    }

                    // Keep drawing while the playhead moves, for a moment afterwards so the
                    // springs can come to rest, and for as long as a gesture is still
                    // playing out. Once a paused song has settled, stop: there is nothing
                    // to redraw, and a lyrics screen left open should not hold the GPU at
                    // 60 fps to show a still image.
                    awake = moved ||
                        nanos - restingSince < SPRING_SETTLE_NANOS ||
                        renderer.isSettling
                    if (awake) frameNanos.longValue = nanos
                }

                // Deciding not to draw is not the same as not asking to. Looping straight
                // back into withFrameNanos leaves a Choreographer callback scheduled on
                // every single refresh, so a paused screen never actually idles — it just
                // idles invisibly. Poll instead, slowly enough to cost nothing and often
                // enough that resuming playback is not perceptibly late.
                if (!awake) delay(IDLE_POLL_MS)
            }
        }

        val resumeMs = settings.autoScrollResumeMs
        val tapToSeek = settings.tapLineToSeek
        val highlightEnabled = settings.lineTapHighlight

        Canvas(
            modifier = Modifier
                .matchParentSize()
                // Its own render node, so a per-frame lyric redraw does not drag the
                // background into being re-rasterised with it.
                .graphicsLayer()
                // The renderer deliberately draws above its own top edge (that is how the
                // first line can sit at the focus point), so without this the lyrics spill
                // over the controls and, in Cinema view, over the album art.
                .clipToBounds()
                // One gesture handler for the lot: hold to highlight (and copy), drag to
                // scroll, tap to seek or to pick a line. Splitting these across separate
                // detectors makes them fight over the same touch slop.
                .pointerInput(document, tapToSeek, selectionMode, highlightEnabled) {
                    val tracker = VelocityTracker()
                    val slop = viewConfiguration.touchSlop
                    val longPressMs = viewConfiguration.longPressTimeoutMillis

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val line = renderer.lineAtViewY(down.position.y)
                        if (highlightEnabled && line != null && !line.isInterlude) {
                            renderer.pressedIndex = line.index
                        }
                        tracker.resetTracking()
                        tracker.addPointerInputChange(down)

                        var dragging = false
                        var longPressed = false
                        val longPressDeadline = System.currentTimeMillis() + longPressMs

                        while (true) {
                            // A short poll rather than a plain await, so the hold can be
                            // recognised while the finger is still down and not moving.
                            val event = withTimeoutOrNull(24L) { awaitPointerEvent() }

                            if (event == null) {
                                if (!dragging && !longPressed &&
                                    System.currentTimeMillis() >= longPressDeadline
                                ) {
                                    longPressed = true
                                    renderer.pressedIndex = -1
                                    line?.takeIf { !it.isInterlude }
                                        ?.let { onLongPressLine(it.source) }
                                }
                                continue
                            }

                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPointerInputChange(change)
                            if (change.changedToUpIgnoreConsumed()) break

                            if (!dragging && abs(change.position.y - down.position.y) > slop) {
                                dragging = true
                                renderer.pressedIndex = -1
                                renderer.onDragStart()
                            }
                            if (dragging) {
                                renderer.onDrag(change.positionChange().y)
                                change.consume()
                            }
                        }

                        renderer.pressedIndex = -1
                        when {
                            dragging -> renderer.onDragEnd(tracker.calculateVelocity().y, resumeMs)
                            longPressed -> Unit
                            selectionMode -> line?.takeIf { !it.isInterlude }
                                ?.let { onSelectLine(it.index) }

                            else -> {
                                val seeking = tapToSeek && line != null &&
                                    !line.isInterlude && document.isSynced
                                if (seeking) {
                                    onSeek(line.startMs.toLong())
                                    // Following stopped when the finger went down. Give it
                                    // straight back and let the page glide to the tapped
                                    // line — jumpToActive would snap, and snap to where the
                                    // song still is, because the player has not been told
                                    // yet.
                                    renderer.followAfterSeek()
                                } else {
                                    // A tap that seeks nothing is a request to re-centre.
                                    renderer.jumpToActive()
                                }
                            }
                        }
                    }
                },
        ) {
            // Reading the frame clock here — not in composition — is what keeps this a
            // draw-only invalidation.
            val nanos = frameNanos.longValue
            val position = positionMsProvider().toInt()
            drawIntoCanvas { canvas ->
                renderer.draw(canvas.nativeCanvas, position, nanos)
            }
        }
    }
}
