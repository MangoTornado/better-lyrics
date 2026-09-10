package com.melisma.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A column of rows that can be dragged into a different order by a handle.
 *
 * Written by hand rather than taken from a library, and not built on `LazyColumn`, because the
 * one list in this app that wants reordering is eight rows inside a settings sheet that is
 * already one long scrolling column. What that costs is the two things `LazyColumn` would have
 * given for free, so both are done here explicitly:
 *
 * - **Rows are measured, not assumed.** A source with a two-line description is half again the
 *   height of one with a short name, so a drag cannot be turned into an index by dividing by a
 *   row height. Each row reports its own, and a swap happens when the drag has passed half of
 *   whichever row it is actually passing.
 * - **The sheet scrolls itself** when a lifted row nears either edge, since the list is easily
 *   taller than the sheet and a reorder you cannot finish in one gesture is barely better than
 *   the arrows it replaced.
 *
 * The drag begins on a long press on the handle only. Anywhere else, and a downward drag is
 * what it looks like — the sheet scrolling — which is why the handle is a separate target
 * rather than the whole row.
 *
 * @param items the rows, in the order to show them.
 * @param keyOf a stable identity per item. Node identity follows it, so the row under the
 *   finger stays under the finger across a swap.
 * @param scroll the scroll state of the container this sits inside, for the edge scrolling.
 * @param viewportInWindow that container's visible bounds, in window coordinates.
 * @param onReordered the new order, once the finger lifts. Not called mid-drag: the rows have to
 *   move the instant a neighbour is passed, and a value that makes the round trip out to
 *   storage and back through a flow arrives a frame late, which reads as a stutter.
 * @param row draws one item. `raised` is true for the row being dragged; `handle` must be
 *   attached to whatever the user is meant to grab.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    scroll: ScrollState,
    viewportInWindow: () -> Rect,
    onReordered: (List<T>) -> Unit,
    row: @Composable (item: T, raised: Boolean, handle: Modifier) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edgeZone = with(density) { EDGE_ZONE.toPx() }
    val edgeStep = with(density) { EDGE_STEP.toPx() }
    val overshoot = with(density) { OVERSHOOT.toPx() }

    // Everything the gesture reads has to be read through a state object. The pointer block is
    // started once per row and keeps running across recompositions, so anything captured by
    // value is whatever it was when the finger went down — the same stale-lambda trap that once
    // froze the lyrics view.
    val latest by rememberUpdatedState(items)
    val viewport by rememberUpdatedState(viewportInWindow)
    val commit by rememberUpdatedState(onReordered)

    // Measured heights and slot positions in pixels, keyed by item rather than by position:
    // position is the thing a drag changes, so an index-keyed measurement would go stale exactly
    // when it is needed. Plain maps rather than snapshot state — they are written from layout and
    // read from a gesture, and making them observable would recompose the list on every scroll.
    val heights = remember { mutableMapOf<Any, Int>() }
    val tops = remember { mutableMapOf<Any, Float>() }

    /** The order under the finger. Null when no drag is in progress or settling. */
    var draft by remember { mutableStateOf<List<T>?>(null) }
    var lifted by remember { mutableStateOf<Any?>(null) }
    var settling by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }

    val shown = draft ?: items

    // Let go of the draft once the caller's list agrees with it, or once it is answering a
    // question that is no longer being asked — a row appearing or disappearing (the cache
    // server, when developer options are switched off) would otherwise leave the draft
    // describing a list that no longer exists.
    LaunchedEffect(items) {
        val held = draft ?: return@LaunchedEffect
        val same = held.map(keyOf) == items.map(keyOf)
        val comparable = held.map(keyOf).toSet() == items.map(keyOf).toSet()
        if (same || !comparable) draft = null
    }

    /**
     * Move the lifted row past every neighbour the drag has taken it more than halfway across.
     *
     * A loop rather than a single step, because one frame of a fast drag can cover several rows,
     * and because the edge scrolling calls this with no new finger movement at all.
     */
    fun advance() {
        val id = lifted ?: return
        var list = draft ?: latest
        var index = list.indexOfFirst { keyOf(it) == id }
        if (index < 0) return
        var moved = false
        while (true) {
            val ahead = offset > 0f
            val neighbour = list.getOrNull(if (ahead) index + 1 else index - 1) ?: break
            val height = heights[keyOf(neighbour)]?.toFloat() ?: break
            if (ahead && offset <= height / 2f) break
            if (!ahead && offset >= -height / 2f) break
            val to = if (ahead) index + 1 else index - 1
            list = list.swapped(index, to)
            offset -= if (ahead) height else -height
            index = to
            moved = true
        }
        if (moved) {
            draft = list
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        // A row cannot be dragged out of its own list: at either end it stops just past its
        // slot. That also ends the edge scrolling, which would otherwise carry on scrolling the
        // sheet under a finger held against the bottom with nothing left to pass.
        if (index == 0) offset = offset.coerceAtLeast(-overshoot)
        if (index == list.lastIndex) offset = offset.coerceAtMost(overshoot)
    }

    /** Reorder by one place without a drag, for the accessibility actions. */
    fun step(id: Any, by: Int) {
        val list = draft ?: latest
        val from = list.indexOfFirst { keyOf(it) == id }
        val to = from + by
        if (from < 0 || to !in list.indices) return
        commit(list.swapped(from, to))
    }

    // Scroll the sheet while a lifted row is held near either edge, and keep the row under the
    // finger while it happens: the slot moves with the content, so the visual offset has to
    // absorb however much actually scrolled — which is nothing at all at either end of the
    // sheet, so this cannot run away.
    LaunchedEffect(lifted) {
        val id = lifted ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val top = tops[id] ?: continue
            val height = heights[id] ?: continue
            val bounds = viewport()
            if (bounds.height <= 0f) continue
            val above = (top + offset) - (bounds.top + edgeZone)
            val below = (top + offset + height) - (bounds.bottom - edgeZone)
            val depth = when {
                above < 0f -> above
                below > 0f -> below
                else -> 0f
            }
            if (depth == 0f) continue
            offset += scroll.scrollBy((depth / edgeZone).coerceIn(-1f, 1f) * edgeStep)
            advance()
        }
    }

    // Slide home whatever distance is left over when the finger lifts — at most half a row,
    // since anything more than that has already become a swap.
    LaunchedEffect(settling) {
        if (settling == null) return@LaunchedEffect
        animate(
            initialValue = offset,
            targetValue = 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        ) { value, _ ->
            // Guarded because a new grab during the settle has already zeroed this, and an
            // in-flight animation frame would otherwise put the old distance back.
            if (lifted == null) offset = value
        }
        settling = null
    }

    Column(Modifier.fillMaxWidth()) {
        shown.forEach { item ->
            val id = keyOf(item)
            key(id) {
                val raised = id == lifted || id == settling
                Box(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (raised) 1f else 0f)
                        // On the outer box, so it reports where the row's slot is rather than
                        // where the drag has moved it to.
                        .onGloballyPositioned { tops[id] = it.positionInWindow().y }
                        .onSizeChanged { heights[id] = it.height },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // Read in the layer, so following a finger costs a redraw of
                                // one row rather than a recomposition of the list.
                                if (raised) {
                                    translationY = offset
                                    scaleX = LIFT_SCALE
                                    scaleY = LIFT_SCALE
                                }
                            }
                            .then(
                                if (raised) {
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.White.copy(alpha = 0.07f))
                                } else {
                                    Modifier
                                }
                            ),
                    ) {
                        row(
                            item,
                            raised,
                            Modifier
                                .semantics(mergeDescendants = true) {
                                    customActions = listOf(
                                        CustomAccessibilityAction("Move up") {
                                            step(id, -1); true
                                        },
                                        CustomAccessibilityAction("Move down") {
                                            step(id, 1); true
                                        },
                                    )
                                }
                                .pointerInput(id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            lifted = id
                                            settling = null
                                            offset = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragEnd = {
                                            draft?.let(commit)
                                            settling = lifted
                                            lifted = null
                                        },
                                        onDragCancel = {
                                            draft?.let(commit)
                                            settling = lifted
                                            lifted = null
                                        },
                                        onDrag = { _, amount ->
                                            offset += amount.y
                                            advance()
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

private fun <T> List<T>.swapped(a: Int, b: Int): List<T> {
    if (a !in indices || b !in indices) return this
    return toMutableList().also {
        val held = it[a]
        it[a] = it[b]
        it[b] = held
    }
}

/** How near an edge a lifted row has to be before the container starts scrolling. */
private val EDGE_ZONE = 88.dp

/** How far it scrolls per frame at the very edge — about a screenful a second. */
private val EDGE_STEP = 12.dp

/** How far past the first or last slot a row can be dragged. */
private val OVERSHOOT = 20.dp

private const val LIFT_SCALE = 1.015f
