package io.github.ottershelf.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drag to reorder in a LazyColumn (the Dashboard's arrange mode, the Customise sheet's lists).
 *
 * - The dragged item follows the finger as a translation of its layer (no relayout, so it keeps
 *   up at 120 Hz); the others make room with `animateItem`.
 * - It changes places with a neighbour once its middle passes the neighbour's middle, so items of
 *   different heights don't flip back and forth. After a move it waits for the list to be measured
 *   again before looking for the next one (the owner's list may update a frame later).
 * - Held near the top or bottom edge, the list scrolls, faster the closer it is.
 * - A tick on pick-up, on each move and on the drop; after the drop the item springs into place.
 *
 * Keys are the LazyColumn's item keys; only items [canMove] accepts are passed or moved over.
 * [onMove] moves `from` to `to`'s place in the owner's list; [onDrop] ends a drag.
 */
@Stable
internal class ReorderState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val edgePx: Float,
    private val maxSpeedPx: Float,
    private val canMove: (Any) -> Boolean,
    private val onMove: (from: Any, to: Any) -> Unit,
    private val onDrop: (Any) -> Unit,
) {
    /** The item being dragged. */
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** The item springing back into place after a drop. */
    var settlingKey by mutableStateOf<Any?>(null)
        private set

    val isDragging: Boolean get() = draggingKey != null

    /** The finger's travel since the pick-up. */
    private var travel by mutableFloatStateOf(0f)
    /** The dragged item's offset in the list when it was picked up. */
    private var startOffset = 0
    private val settle = Animatable(0f)
    private var autoScroll: Job? = null
    private var pending: Pending? = null

    /** A move asked for, not yet seen in a measured layout. */
    private class Pending(val layout: LazyListLayoutInfo, val index: Int) {
        var frames = 0
    }

    private fun itemOf(key: Any?, info: LazyListLayoutInfo = listState.layoutInfo): LazyListItemInfo? =
        info.visibleItemsInfo.firstOrNull { it.key == key }

    /** How far the dragged item is drawn from its place in the list. Read while drawing. */
    val draggingOffset: Float
        get() = itemOf(draggingKey)?.let { startOffset + travel - it.offset } ?: 0f

    val settleOffset: Float get() = settle.value

    fun start(key: Any) {
        if (draggingKey != null || !canMove(key)) return
        val item = itemOf(key) ?: return
        startOffset = item.offset + if (settlingKey == key) settle.value.toInt() else 0
        travel = 0f
        pending = null
        draggingKey = key
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        autoScroll = scope.launch { scrollNearEdges() }
    }

    fun drag(dy: Float) {
        if (draggingKey == null) return
        travel += dy
        moveIfPast()
    }

    fun end() {
        val key = draggingKey ?: return
        val offset = draggingOffset
        autoScroll?.cancel()
        autoScroll = null
        pending = null
        draggingKey = null
        travel = 0f
        settlingKey = key
        scope.launch {
            settle.snapTo(offset)
            settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.5f))
            if (settlingKey == key) settlingKey = null
        }
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        onDrop(key)
    }

    /** Changes places with the neighbour whose middle the dragged item's middle has passed. */
    private fun moveIfPast() {
        val key = draggingKey ?: return
        val info = listState.layoutInfo
        pending?.let { p ->
            if (info === p.layout) return
            val index = itemOf(key, info)?.index
            // The owner's list may take a frame to arrive; don't move twice for one crossing.
            if (index != p.index && p.frames < 3) return
            pending = null
        }
        val items = info.visibleItemsInfo
        val position = items.indexOfFirst { it.key == key }
        if (position < 0) return
        val item = items[position]
        val middle = startOffset + travel + item.size / 2f
        val next = items.getOrNull(position + 1)?.takeIf { canMove(it.key) }
        val previous = items.getOrNull(position - 1)?.takeIf { canMove(it.key) }
        val target = when {
            next != null && middle > next.offset + next.size / 2f -> next
            previous != null && middle < previous.offset + previous.size / 2f -> previous
            else -> return
        }
        // Moving the first item on screen would scroll the list to follow it: keep the place.
        val first = listState.firstVisibleItemIndex
        if (item.index == first || target.index == first) {
            listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
        }
        onMove(key, target.key)
        pending = Pending(info, target.index)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    /** While dragging: scrolls when the item is held near an edge, and looks for moves after each frame. */
    private suspend fun scrollNearEdges() {
        var last = withFrameNanos { it }
        while (draggingKey != null) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            pending?.let { it.frames++ }
            val speed = edgeSpeed()
            if (speed != 0f) listState.scrollBy(speed * seconds)
            moveIfPast()
        }
    }

    private fun edgeSpeed(): Float {
        val item = itemOf(draggingKey) ?: return 0f
        val info = listState.layoutInfo
        val top = startOffset + travel
        val bottom = top + item.size
        val start = info.viewportStartOffset + info.beforeContentPadding
        val end = info.viewportEndOffset - info.afterContentPadding
        fun speed(depth: Float): Float {
            val f = (depth / edgePx).coerceIn(0f, 1f)
            return maxSpeedPx * f * f
        }
        return when {
            top < start + edgePx && listState.canScrollBackward -> -speed(start + edgePx - top)
            bottom > end - edgePx && listState.canScrollForward -> speed(bottom - (end - edgePx))
            else -> 0f
        }
    }
}

@Composable
internal fun rememberReorderState(
    listState: LazyListState,
    canMove: (Any) -> Boolean,
    onMove: (from: Any, to: Any) -> Unit,
    onDrop: (Any) -> Unit = {},
): ReorderState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val canMoveNow by rememberUpdatedState(canMove)
    val onMoveNow by rememberUpdatedState(onMove)
    val onDropNow by rememberUpdatedState(onDrop)
    return remember(listState, scope, haptics, density) {
        ReorderState(
            listState = listState,
            scope = scope,
            haptics = haptics,
            edgePx = with(density) { 72.dp.toPx() },
            maxSpeedPx = with(density) { 900.dp.toPx() },
            canMove = { canMoveNow(it) },
            onMove = { from, to -> onMoveNow(from, to) },
            onDrop = { onDropNow(it) },
        )
    }
}

/**
 * An item of a reorderable list: the dragged one is drawn above the others and follows the finger,
 * then springs into place; the others slide to their new places ([animate]: off for items that
 * aren't moving, e.g. while the list isn't being arranged).
 */
internal fun LazyItemScope.reorderable(state: ReorderState, key: Any, animate: Boolean = true): Modifier {
    val dragging = state.draggingKey == key
    val settling = state.settlingKey == key
    return when {
        dragging -> Modifier.zIndex(2f).graphicsLayer { translationY = state.draggingOffset }
        settling -> Modifier.zIndex(1f).graphicsLayer { translationY = state.settleOffset }
        animate -> Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
        else -> Modifier
    }
}

/** A drag handle: a drag on it moves [key] at once. */
internal fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
    ) { change, amount ->
        change.consume()
        state.drag(amount.y)
    }
}

/** The whole item: a long press picks [key] up, then it follows the finger. */
internal fun Modifier.longPressDrag(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.start(key) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
    ) { change, amount ->
        change.consume()
        state.drag(amount.y)
    }
}
