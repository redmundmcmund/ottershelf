package io.github.ottershelf.feature.pdf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The document's paper (pages are drawn on white) and what night mode turns it into. The page's colours, not the theme's. */
internal val PaperColor = Color.White
internal val NightPaperColor = Color(0xFF1E1E1E)

/** Night mode: colours inverted and softened (white paper to #1E1E1E, black ink to #E1E1E1). */
internal val NightFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.765f, 0f, 0f, 0f, 225f,
            0f, -0.765f, 0f, 0f, 225f,
            0f, 0f, -0.765f, 0f, 225f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

internal const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
/** How long a zoomed view must stay still before its visible part is drawn again at full sharpness. */
private const val DETAIL_DELAY_MS = 160L
private val PAGE_GAP = 8.dp

/** Search matches to draw: by page, the selected one stronger. */
class PdfMarks(val hits: Map<Int, List<PdfHit>>, val selected: PdfHit?, val color: Color, val selectedColor: Color) {
    companion object {
        val None = PdfMarks(emptyMap(), null, Color.Transparent, Color.Transparent)
    }
}

/** A zoomed page's visible part, drawn at the screen's resolution: [region] in px of the page at [zoom]. */
internal class PageDetail(val bitmap: ImageBitmap, val region: IntRect, val zoom: Float)

/** What the page views tell the reader. */
class PdfViewerEvents(
    /** The page on screen changed (0-based). */
    val onPage: (Int) -> Unit = {},
    /** A tap on the page that isn't a link (or, in single pages, the middle): show or hide the bars. */
    val onTap: () -> Unit = {},
    val onLink: (PdfLink) -> Unit = {},
    /** A gesture ended: the reading session isn't idle; [moved] (panned, zoomed, scrolled) is a move the user made. */
    val onActivity: (moved: Boolean) -> Unit = {},
)

// --- continuous strip ----------------------------------------------------------------------------

private class StripZoom {
    var zoom by mutableFloatStateOf(1f)
    /** Horizontal offset of the zoomed strip, 0..width * (zoom - 1) px. */
    var panX by mutableFloatStateOf(0f)
}

/**
 * The pages one under the other (the default). The strip is a LazyColumn laid out at zoom 1 and
 * drawn scaled (pinch, double tap), so zooming doesn't relayout the pages; its viewport is the
 * screen's height divided by the zoom, so the pages composed are exactly the ones in view. Every
 * gesture is handled here (drag in any direction, fling, pinch, double tap, tap), since panning a
 * zoomed page is two-dimensional. When the view rests zoomed in, the visible part of each page is
 * rendered again at screen resolution over the page's bitmap.
 */
@Composable
internal fun ContinuousPages(
    source: PdfPageSource,
    sizes: PdfPageSizes,
    fit: PdfFit,
    night: Boolean,
    initialPage: Int,
    jumps: Flow<PdfJump>,
    marks: PdfMarks,
    events: PdfViewerEvents,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val gap = with(LocalDensity.current) { PAGE_GAP.roundToPx() }
        val count = sizes.count
        val pageSize: (Int) -> IntSize = remember(sizes, fit, vw, vh) {
            { i -> PdfMath.fitSize(sizes.width(i), sizes.height(i), vw, vh, fit).let { (w, h) -> stripPageSize(w, h, vw) } }
        }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceIn(0, (count - 1).coerceAtLeast(0)))
        val zoom = remember { StripZoom() }
        val scope = rememberCoroutineScope()
        val decay = rememberSplineBasedDecay<Float>()
        val details = remember { mutableStateMapOf<Int, PageDetail>() }
        var motion by remember { mutableStateOf<Job?>(null) }
        val currentEvents by rememberUpdatedState(events)
        val currentPageSize by rememberUpdatedState(pageSize)
        val currentSizes by rememberUpdatedState(sizes)

        fun pan(d: Offset) {
            zoom.panX = (zoom.panX - d.x).coerceIn(0f, vw * (zoom.zoom - 1f))
            if (d.y != 0f) list.dispatchRawDelta(-d.y / zoom.zoom)
        }

        fun zoomBy(factor: Float, c: Offset) {
            val z = zoom.zoom
            val nz = (z * factor).coerceIn(1f, MAX_ZOOM)
            if (nz == z) return
            zoom.panX = ((zoom.panX + c.x) * (nz / z) - c.x).coerceIn(0f, vw * (nz - 1f))
            // The point under the fingers stays there: it was c.y / z below the top, and will be c.y / nz.
            list.dispatchRawDelta(c.y / z - c.y / nz)
            zoom.zoom = nz
        }

        fun fling(v: Velocity) {
            motion = scope.launch {
                launch {
                    list.scroll {
                        var last = 0f
                        AnimationState(0f, -v.y / zoom.zoom).animateDecay(decay) {
                            val delta = value - last
                            last = value
                            if (abs(delta - scrollBy(delta)) > 0.5f) cancelAnimation()
                        }
                    }
                }
                if (zoom.zoom > 1f) launch { flingAxis(zoom.panX, -v.x, decay) { value -> val max = vw * (zoom.zoom - 1f); zoom.panX = value.coerceIn(0f, max); value in 0f..max } }
            }
        }

        fun tap(p: Offset) {
            val z = zoom.zoom
            val lx = (p.x + zoom.panX) / z
            val ly = p.y / z
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { ly >= it.offset && ly < it.offset + it.size }
            if (item != null) {
                val size = currentPageSize(item.index)
                val left = (vw - size.width) / 2f
                val top = item.offset + gap / 2f
                val x = (lx - left) / size.width * currentSizes.width(item.index)
                val y = (ly - top) / size.height * currentSizes.height(item.index)
                source.linkAt(item.index, x, y)?.let {
                    currentEvents.onLink(it)
                    return
                }
            }
            currentEvents.onTap()
        }

        LaunchedEffect(list) {
            snapshotFlow {
                val info = list.layoutInfo
                PdfMath.currentIndex(
                    info.visibleItemsInfo.map { Triple(it.index, it.offset, it.size) },
                    info.viewportStartOffset,
                    info.viewportEndOffset,
                    atTop = !list.canScrollBackward,
                    atEnd = !list.canScrollForward,
                )
            }.filterNotNull().distinctUntilChanged().collect { currentEvents.onPage(it) }
        }
        LaunchedEffect(jumps) {
            jumps.collect { jump ->
                motion?.cancel()
                val page = jump.page.coerceIn(0, (count - 1).coerceAtLeast(0))
                val offset = jump.y?.let { y -> (gap / 2f + y * currentPageSize(page).height - vh / zoom.zoom / 3f).toInt().coerceAtLeast(0) } ?: 0
                if (jump.animate) list.animateScrollToItem(page, offset) else list.scrollToItem(page, offset)
            }
        }
        LaunchedEffect(source, pageSize) {
            snapshotFlow { listOf(zoom.zoom, zoom.panX, list.firstVisibleItemIndex.toFloat(), list.firstVisibleItemScrollOffset.toFloat()) }
                .collectLatest { (z, panX) ->
                    if (z < 1.02f) {
                        details.clear()
                        return@collectLatest
                    }
                    delay(DETAIL_DELAY_MS)
                    val wanted = HashMap<Int, PageDetail>()
                    for (item in list.layoutInfo.visibleItemsInfo) {
                        val size = pageSize(item.index)
                        val left = (vw - size.width) / 2f
                        val top = item.offset + gap / 2f
                        val x0 = max(panX / z, left)
                        val x1 = min((panX + vw) / z, left + size.width)
                        val y0 = max(0f, top)
                        val y1 = min(vh / z, top + size.height)
                        if (x1 <= x0 || y1 <= y0) continue
                        val full = IntSize((size.width * z).roundToInt(), (size.height * z).roundToInt())
                        val region = IntRect(floor((x0 - left) * z).toInt(), floor((y0 - top) * z).toInt(), ceil((x1 - left) * z).toInt(), ceil((y1 - top) * z).toInt())
                            .intersect(IntRect(0, 0, full.width, full.height))
                        val bitmap = source.renderRegion(item.index, full.width, full.height, region) ?: continue
                        wanted[item.index] = PageDetail(bitmap, region, z)
                    }
                    details.keys.retainAll(wanted.keys)
                    details.putAll(wanted)
                }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(source, vw, vh) {
                    readerGestures {
                        GestureHandlers(
                            claimDrag = { true },
                            onDown = { motion?.cancel() },
                            onPan = ::pan,
                            onZoom = ::zoomBy,
                            onFling = ::fling,
                            onTap = ::tap,
                            onDoubleTap = { c ->
                                motion?.cancel()
                                val target = if (zoom.zoom > 1.2f) 1f else DOUBLE_TAP_ZOOM
                                motion = scope.launch { Animatable(zoom.zoom).animateTo(target, tween(220)) { zoomBy(value / zoom.zoom, c) } }
                            },
                            onEnd = { moved -> currentEvents.onActivity(moved) },
                        )
                    }
                },
        ) {
            LazyColumn(state = list, userScrollEnabled = false, modifier = Modifier.fillMaxSize().zoomedStrip(zoom)) {
                items(count, key = { it }) { index ->
                    val size = pageSize(index)
                    val bitmap = rememberPageBitmap(source, index, size)
                    val detail = details[index]
                    val hits = marks.hits[index]
                    val scale = size.width / sizes.width(index)
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .layout { measurable, constraints ->
                                val p = measurable.measure(Constraints.fixed(constraints.maxWidth, size.height + gap))
                                layout(p.width, p.height) { p.place(0, 0) }
                            }
                            .drawBehind {
                                translate(left = (this.size.width - size.width) / 2f, top = gap / 2f) {
                                    drawPage(bitmap, size, night, detail, hits, marks, scale)
                                }
                            },
                    )
                }
            }
        }
    }
}

/**
 * The tallest a page is laid out in the strip, px, with room left for its gap: Compose can't measure
 * anything 262,143 px tall next to a screen under [WIDE_STRIP_PX] wide (a phone), or 65,535 px next
 * to a wider one, and a page of an extreme shape at fit width passes that (a 3 x 14400 pt MediaBox):
 * the layout threw and the reader crashed.
 */
internal const val MAX_STRIP_PAGE_HEIGHT = 250_000
internal const val MAX_WIDE_STRIP_PAGE_HEIGHT = 60_000
internal const val WIDE_STRIP_PX = 8_191f

/**
 * A page's [width] x [height] in the strip on a screen [viewportWidth] wide: as it is, unless it is
 * taller than [MAX_STRIP_PAGE_HEIGHT] ([MAX_WIDE_STRIP_PAGE_HEIGHT] on a screen of [WIDE_STRIP_PX] or
 * more); then scaled down to that, keeping its shape.
 */
internal fun stripPageSize(width: Int, height: Int, viewportWidth: Float): IntSize {
    val max = if (viewportWidth < WIDE_STRIP_PX) MAX_STRIP_PAGE_HEIGHT else MAX_WIDE_STRIP_PAGE_HEIGHT
    if (height <= max) return IntSize(width, height)
    val scale = max.toFloat() / height
    return IntSize((width * scale).roundToInt().coerceAtLeast(1), max)
}

/** Lays the strip out [StripZoom.zoom] times shorter than the screen and draws it scaled up from the top left. */
private fun Modifier.zoomedStrip(state: StripZoom) = layout { measurable, constraints ->
    val z = state.zoom
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed(w, (h / z).roundToInt().coerceAtLeast(1)))
    layout(w, h) {
        placeable.placeWithLayer(0, 0) {
            scaleX = state.zoom
            scaleY = state.zoom
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = -state.panX
        }
    }
}

// --- single pages -------------------------------------------------------------------------------

private class PageZoom {
    var zoom by mutableFloatStateOf(1f)
    /** The zoomed page's top left in the viewport, px. */
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
}

/**
 * One page at a time, swiped sideways (the pager). Each page zooms and pans on its own; while it is
 * zoomed in the pager stays put, so panning never turns the page. Taps on the outer quarters turn
 * the page, as in the EPUB reader; a page taller than the screen (fit width) scrolls vertically.
 */
@Composable
internal fun PagedPages(
    source: PdfPageSource,
    sizes: PdfPageSizes,
    fit: PdfFit,
    night: Boolean,
    initialPage: Int,
    jumps: Flow<PdfJump>,
    marks: PdfMarks,
    events: PdfViewerEvents,
    modifier: Modifier = Modifier,
) {
    val count = sizes.count
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, (count - 1).coerceAtLeast(0))) { count }
    var zoomedIn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentEvents by rememberUpdatedState(events)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect {
            // A jump (slider, contents) can leave a zoomed page behind: the new page starts unzoomed.
            zoomedIn = false
            currentEvents.onPage(it)
        }
    }
    LaunchedEffect(jumps) {
        jumps.collect { jump ->
            val page = jump.page.coerceIn(0, (count - 1).coerceAtLeast(0))
            if (jump.animate) pager.animateScrollToPage(page) else pager.scrollToPage(page)
        }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomedIn,
            beyondViewportPageCount = 1,
            pageSpacing = PAGE_GAP,
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val base = remember(sizes, fit, vw, vh, index) {
                PdfMath.fitSize(sizes.width(index), sizes.height(index), vw, vh, fit).let { (w, h) -> IntSize(w, h) }
            }
            ZoomablePage(
                index = index,
                base = base,
                points = Size(sizes.width(index), sizes.height(index)),
                viewport = Size(vw, vh),
                source = source,
                night = night,
                marks = marks,
                active = index == pager.settledPage,
                onZoomed = { if (index == pager.currentPage) zoomedIn = it },
                onTap = { x ->
                    when {
                        x < vw * 0.25f && index > 0 -> scope.launch { pager.animateScrollToPage(index - 1) }
                        x > vw * 0.75f && index < count - 1 -> scope.launch { pager.animateScrollToPage(index + 1) }
                        else -> currentEvents.onTap()
                    }
                },
                onLink = { currentEvents.onLink(it) },
                onActivity = { moved -> currentEvents.onActivity(moved) },
            )
        }
    }
}

@Composable
private fun ZoomablePage(
    index: Int,
    base: IntSize,
    points: Size,
    viewport: Size,
    source: PdfPageSource,
    night: Boolean,
    marks: PdfMarks,
    active: Boolean,
    onZoomed: (Boolean) -> Unit,
    onTap: (Float) -> Unit,
    onLink: (PdfLink) -> Unit,
    onActivity: (moved: Boolean) -> Unit,
) {
    val state = remember { PageZoom() }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    var motion by remember { mutableStateOf<Job?>(null) }
    var detail by remember { mutableStateOf<PageDetail?>(null) }
    val bitmap = rememberPageBitmap(source, index, base)
    val vw = viewport.width
    val vh = viewport.height

    fun clamp() {
        val cw = base.width * state.zoom
        val ch = base.height * state.zoom
        state.x = if (cw <= vw) (vw - cw) / 2f else state.x.coerceIn(vw - cw, 0f)
        state.y = if (ch <= vh) (vh - ch) / 2f else state.y.coerceIn(vh - ch, 0f)
    }

    fun zoomBy(factor: Float, c: Offset) {
        val z = state.zoom
        val nz = (z * factor).coerceIn(1f, MAX_ZOOM)
        if (nz == z) return
        state.x = c.x - (c.x - state.x) * (nz / z)
        state.y = c.y - (c.y - state.y) * (nz / z)
        state.zoom = nz
        clamp()
        onZoomed(nz > 1.01f)
    }

    LaunchedEffect(base, viewport, active) {
        if (!active && state.zoom != 1f) {
            state.zoom = 1f
            state.y = 0f
            detail = null
        }
        clamp()
    }
    LaunchedEffect(source, base, viewport) {
        snapshotFlow { Triple(state.zoom, state.x, state.y) }.collectLatest { (z, x, y) ->
            if (z < 1.02f) {
                detail = null
                return@collectLatest
            }
            delay(DETAIL_DELAY_MS)
            val cw = base.width * z
            val ch = base.height * z
            val x0 = max(0f, -x)
            val y0 = max(0f, -y)
            val x1 = min(cw, vw - x)
            val y1 = min(ch, vh - y)
            if (x1 <= x0 || y1 <= y0) return@collectLatest
            val full = IntSize(cw.roundToInt(), ch.roundToInt())
            val region = IntRect(floor(x0).toInt(), floor(y0).toInt(), ceil(x1).toInt(), ceil(y1).toInt())
                .intersect(IntRect(0, 0, full.width, full.height))
            source.renderRegion(index, full.width, full.height, region)?.let { detail = PageDetail(it, region, z) }
        }
    }

    Spacer(
        Modifier
            .fillMaxSize()
            .pointerInput(source, base, viewport) {
                readerGestures {
                    GestureHandlers(
                        claimDrag = { total ->
                            state.zoom > 1.01f || (base.height * state.zoom > vh + 1f && abs(total.y) > abs(total.x))
                        },
                        onDown = { motion?.cancel() },
                        onPan = { d ->
                            state.x += d.x
                            state.y += d.y
                            clamp()
                        },
                        onZoom = ::zoomBy,
                        onFling = { v ->
                            motion = scope.launch {
                                launch { flingAxis(state.x, v.x, decay) { value -> state.x = value; val before = state.x; clamp(); before == state.x } }
                                launch { flingAxis(state.y, v.y, decay) { value -> state.y = value; val before = state.y; clamp(); before == state.y } }
                            }
                        },
                        onTap = { p ->
                            val s = base.width * state.zoom / points.width
                            val px = (p.x - state.x) / s
                            val py = (p.y - state.y) / s
                            val link = if (px in 0f..points.width && py in 0f..points.height) source.linkAt(index, px, py) else null
                            if (link != null) onLink(link) else onTap(p.x)
                        },
                        onDoubleTap = { c ->
                            motion?.cancel()
                            val target = if (state.zoom > 1.2f) 1f else DOUBLE_TAP_ZOOM
                            motion = scope.launch { Animatable(state.zoom).animateTo(target, tween(220)) { zoomBy(value / state.zoom, c) } }
                        },
                        onEnd = onActivity,
                    )
                }
            }
            .drawBehind {
                translate(state.x, state.y) {
                    scale(state.zoom, state.zoom, pivot = Offset.Zero) {
                        drawPage(bitmap, base, night, detail, marks.hits[index], marks, base.width / points.width)
                    }
                }
            },
    )
}

// --- shared pieces -----------------------------------------------------------------------------

/** Page [index] at [size] px: the cached bitmap at once, else rendered; the previous size's until then. */
@Composable
internal fun rememberPageBitmap(source: PdfPageSource, index: Int, size: IntSize): ImageBitmap? {
    var state by remember(source, index) { mutableStateOf(source.cached(index, size.width)?.let { size.width to it }) }
    LaunchedEffect(source, index, size) {
        if (state?.first == size.width) return@LaunchedEffect
        val bitmap = source.cached(index, size.width) ?: source.render(index, size.width, size.height)
        if (bitmap != null) state = size.width to bitmap
    }
    return state?.second
}

/** A page at [size] (px, its top left at the origin): its bitmap, the sharp zoomed part over it, and search matches. */
internal fun DrawScope.drawPage(
    bitmap: ImageBitmap?,
    size: IntSize,
    night: Boolean,
    detail: PageDetail?,
    hits: List<PdfHit>?,
    marks: PdfMarks,
    pxPerPoint: Float,
) {
    val filter = if (night) NightFilter else null
    if (bitmap != null) {
        drawImage(
            bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset.Zero,
            dstSize = size,
            colorFilter = filter,
            filterQuality = FilterQuality.Low,
        )
    } else {
        drawRect(if (night) NightPaperColor else PaperColor, size = size.toSize())
    }
    if (detail != null) {
        withTransform({
            translate(detail.region.left / detail.zoom, detail.region.top / detail.zoom)
            scale(1f / detail.zoom, 1f / detail.zoom, pivot = Offset.Zero)
        }) {
            drawImage(detail.bitmap, colorFilter = filter)
        }
    }
    hits?.forEach { hit ->
        val color = if (hit == marks.selected) marks.selectedColor else marks.color
        hit.rects.forEach { r ->
            drawRect(color, topLeft = Offset(r.left * pxPerPoint, r.top * pxPerPoint), size = Size((r.right - r.left) * pxPerPoint, (r.bottom - r.top) * pxPerPoint))
        }
    }
}

/** Flings one axis from [start] at [velocity]; [apply] sets the value and says whether it stayed in bounds (false stops). */
private suspend fun flingAxis(start: Float, velocity: Float, decay: DecayAnimationSpec<Float>, apply: (Float) -> Boolean) {
    if (abs(velocity) < 1f) return
    AnimationState(start, velocity).animateDecay(decay) {
        if (!apply(value)) cancelAnimation()
    }
}

internal class GestureHandlers(
    /** A single-finger drag passed the touch slop with [total] movement: true to pan, false to leave it (to the pager). */
    val claimDrag: (total: Offset) -> Boolean,
    val onDown: () -> Unit,
    val onPan: (Offset) -> Unit,
    val onZoom: (factor: Float, centroid: Offset) -> Unit,
    val onFling: (Velocity) -> Unit,
    val onTap: (Offset) -> Unit,
    val onDoubleTap: (Offset) -> Unit,
    /** The gesture is over; [moved]: it panned, zoomed or double-tapped (not a plain tap). */
    val onEnd: (moved: Boolean) -> Unit,
)

private const val UNDECIDED = 0
private const val PANNING = 1
private const val DECLINED = 2
private const val ZOOMING = 3

/**
 * Drag (any direction), fling, pinch (with pan), tap and double tap in one gesture loop. A tap
 * waits out the double-tap timeout before it counts. Single-finger drags this handler declines
 * aren't consumed, so a pager around it can take them.
 */
internal suspend fun PointerInputScope.readerGestures(handlers: () -> GestureHandlers) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val h = handlers()
        h.onDown()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var mode = UNDECIDED
        var total = Offset.Zero
        var multi = false
        var upTime = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                upTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: upTime
                break
            }
            if (pressed.size >= 2 && mode != DECLINED) {
                multi = true
                mode = ZOOMING
                val zoom = event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = true)
                val pan = event.calculatePan()
                if (zoom != 1f && centroid != Offset.Unspecified) h.onZoom(zoom, centroid)
                if (pan != Offset.Zero) h.onPan(pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                tracker.resetTracking()
            } else if (pressed.size == 1) {
                val change = pressed[0]
                val delta = change.positionChange()
                if (mode == UNDECIDED) {
                    total += delta
                    if (total.getDistance() > viewConfiguration.touchSlop) mode = if (h.claimDrag(total)) PANNING else DECLINED
                }
                if (mode == PANNING || mode == ZOOMING) {
                    if (delta != Offset.Zero) h.onPan(delta)
                    change.consume()
                }
                tracker.addPosition(change.uptimeMillis, change.position)
            }
        }
        var moved = mode == PANNING || mode == ZOOMING
        if (mode == PANNING) {
            h.onFling(tracker.calculateVelocity())
        } else if (mode == UNDECIDED && !multi && upTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
            if (second == null) {
                h.onTap(down.position)
            } else if (waitForUpOrCancellation() != null) {
                h.onDoubleTap(second.position)
                moved = true
            }
        }
        h.onEnd(moved)
    }
}
