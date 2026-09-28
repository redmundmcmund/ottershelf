package io.github.ottershelf.feature.comics

import android.content.Context
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size as CoilSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import io.github.ottershelf.R
import io.github.ottershelf.core.readerprefs.CbxReaderSettings
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Page turns asked for from outside the pages (volume keys): the pages composable fills these in
 * with whatever turns a page in the current mode.
 */
@Stable
class PageTurner {
    var next: () -> Unit = {}
    var previous: () -> Unit = {}
}

/** The fit modes' ids (`CbxReaderSettings.fitMode`). */
internal object Fit {
    const val PAGE = "fit-page"
    const val WIDTH = "fit-width"
    const val HEIGHT = "fit-height"
    const val ACTUAL = "actual"
}

/** How much a page can be zoomed, and where a double tap zooms to. */
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val STRIP_MAX_ZOOM = 3f

/** A page's shape before its size is known (a typical comic page, width / height). */
private const val DEFAULT_RATIO = 0.66f

/** Coil's default cap on a decoded bitmap's side (maxBitmapSize), which a webtoon strip passes. */
private const val COIL_MAX_BITMAP_SIDE = 4096f

/** The most a tall strip page is decoded at: 48 MB, well under the 100 MB a canvas may draw. */
private const val MAX_STRIP_PAGE_PIXELS = 12_000_000.0

/**
 * The tallest a strip page is laid out, px: Compose can't measure anything 262,143 px tall next to a
 * column under [WIDE_STRIP_COLUMN_PX], or 65,535 px next to a wider one (a zoomed landscape strip),
 * and a merged webtoon chapter at the column's width passes that: the layout threw and the reader
 * crashed. Both keep room to spare; the wide one holds for any column up to 32,766 px.
 */
internal const val MAX_STRIP_PAGE_HEIGHT_PX = 250_000f
internal const val MAX_WIDE_STRIP_PAGE_HEIGHT_PX = 60_000f
internal const val WIDE_STRIP_COLUMN_PX = 8_191f

/** Taps in the outer quarter on either side turn the page, the web's click zones. */
private const val TURN_ZONE = 0.25f

/** How far a swipe past the last page must pull to turn past it (the web's 50 px). */
private val PAST_END_SWIPE = 48.dp

/**
 * The pages: a pager of single pages or spreads (paged mode), or a vertical list (infinite and
 * long-strip). [imageLoader] null draws with the app's loader (screenshot tests).
 */
@Composable
internal fun ComicPages(
    state: ComicsUiState,
    actions: ComicsActions,
    imageLoader: ImageLoader?,
    turner: PageTurner,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val loader = imageLoader ?: coil3.SingletonImageLoader.get(LocalPlatformContext.current)
    if (state.paged) PagedPages(state, actions, loader, turner, landscape, modifier)
    else StripPages(state, actions, loader, turner, modifier)
}

/** Which side a tap at [x] (0..1 across) means: the outer quarters turn, the middle toggles the bars. */
internal fun tapZone(x: Float, rtl: Boolean, turner: PageTurner, onMiddle: () -> Unit) {
    when {
        x < TURN_ZONE -> if (rtl) turner.next() else turner.previous()
        x > 1 - TURN_ZONE -> if (rtl) turner.previous() else turner.next()
        else -> onMiddle()
    }
}

/** Whether spreads show: two-page view in paged mode, in landscape (or everywhere when forced). */
internal fun twoPageEffective(settings: CbxReaderSettings, landscape: Boolean): Boolean =
    settings.viewMode == "two-page" && settings.scrollMode == ComicsUiState.SCROLL_PAGED && (landscape || settings.forceTwoPage)

// --- paged -------------------------------------------------------------------------------------

@Composable
private fun PagedPages(
    state: ComicsUiState,
    actions: ComicsActions,
    loader: ImageLoader,
    turner: PageTurner,
    landscape: Boolean,
    modifier: Modifier,
) {
    val settings = state.settings
    val twoPage = twoPageEffective(settings, landscape)
    // Keyed on which pages are wide, not on their ratios: every decode reports a slightly different
    // ratio, and a new layout mid-turn would fight the turn.
    val wide = remember(twoPage, state.ratios) { if (twoPage) SpreadLayout.widePages(state.ratios) else emptySet() }
    val layout = remember(state.pageCount, twoPage, state.rtl, settings.spreadAlignment, settings.widePageSingletonMode, wide) {
        SpreadLayout.create(
            pageCount = state.pageCount,
            twoPage = twoPage,
            rtl = state.rtl,
            shifted = settings.spreadAlignment == "shifted",
            widePagesInSpreads = settings.widePageSingletonMode == "disable",
            wide = wide,
        )
    }
    val pager = rememberPagerState(initialPage = layout.spreadIndexForPage(state.currentPage)) { layout.spreads.size }
    val scope = rememberCoroutineScope()
    val latestActions by rememberUpdatedState(actions)
    val currentLayout by rememberUpdatedState(layout)
    // The first page last reported on screen: where the user is, in whatever layout. Written as it is
    // reported, so it is never a recomposition behind.
    val shown = remember { ShownPage(state.currentPage) }
    // The layout the pager has been brought to the user's page in; pages are reported only then, so a new
    // layout's spread at the pager's old index is never taken for the page on screen.
    var anchored by remember { mutableStateOf<SpreadLayout?>(null) }

    // A new layout (rotation, settings, a wide page found) keeps the page the user is on. A turn under way
    // is left to finish: the pager keeps its spreads by key, and re-anchoring now would undo it.
    LaunchedEffect(layout) {
        if (!pager.isScrollInProgress) {
            val target = layout.spreadIndexForPage(shown.page)
            if (pager.currentPage != target) {
                try {
                    pager.scrollToPage(target)
                } catch (e: CancellationException) {
                    // The user's own drag took over the pager (a higher priority): where it ends is the user's page.
                    ensureActive()
                }
            }
        }
        snapshotFlow { pager.isScrollInProgress }.first { !it }
        anchored = layout
    }
    // Every page that settles on screen.
    LaunchedEffect(pager) {
        snapshotFlow { if (anchored === currentLayout) currentLayout.spreads.getOrNull(pager.settledPage)?.pages else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { pages ->
                shown.page = pages.first()
                latestActions.onPagesShown(pages)
            }
    }
    LaunchedEffect(state.jump) {
        val jump = state.jump ?: return@LaunchedEffect
        pager.scrollToPage(layout.spreadIndexForPage(jump.page))
        latestActions.onJumpHandled(jump.id)
    }
    SideEffect {
        turner.next = {
            if (pager.currentPage < layout.spreads.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            else latestActions.onPastEnd()
        }
        turner.previous = {
            if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
        }
    }
    // A swipe on past the last page turns past the end too, as the web's does (auto-advance).
    val forward = if (state.rtl != (LocalLayoutDirection.current == LayoutDirection.Rtl)) 1f else -1f
    val threshold = with(LocalDensity.current) { PAST_END_SWIPE.toPx() }
    val pastEnd = remember(pager, forward, threshold) { PastEndSwipe(pager, forward, threshold) { latestActions.onPastEnd() } }

    HorizontalPager(
        state = pager,
        modifier = modifier.fillMaxSize().nestedScroll(pastEnd),
        reverseLayout = state.rtl,
        beyondViewportPageCount = 1,
        key = { index -> layout.spreads.getOrNull(index)?.pages?.joinToString(",") ?: "x$index" },
    ) { index ->
        val spread = layout.spreads.getOrNull(index) ?: return@HorizontalPager
        val onTap: (Float) -> Unit = { x -> tapZone(x, state.rtl, turner, latestActions.onToggleChrome) }
        val current = pager.settledPage == index
        if (spread.single) {
            val page = spread.anchorPage
            SinglePage(page, state.source?.model(page), settings, state.rtl, loader, current, onTap, actions)
        } else {
            SpreadPage(spread, state, loader, current, onTap, actions)
        }
    }
}

/** The first page last reported on screen (see PagedPages). */
private class ShownPage(var page: Int)

/**
 * A swipe on the last spread, onward: the pager can't take it, so the drag reaches this parent
 * unconsumed (before the overscroll effect). Released after a pull of at least [threshold] px while
 * still at the end, it is a turn past the end ([onPastEnd]), once per gesture. [forward]: the sign
 * of a forward drag's x (-1 when the next page comes from the right, 1 for right to left).
 */
private class PastEndSwipe(
    private val pager: PagerState,
    private val forward: Float,
    private val threshold: Float,
    private val onPastEnd: () -> Unit,
) : NestedScrollConnection {
    private var pulled = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && !pager.canScrollForward) pulled += available.x * forward
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val past = pulled >= threshold && !pager.canScrollForward
        pulled = 0f
        if (past) onPastEnd()
        return Velocity.Zero
    }
}

/** One page, zoomable (pinch, double tap, pan; large pages are decoded in tiles). */
@Composable
private fun SinglePage(
    page: Int,
    model: Any?,
    settings: CbxReaderSettings,
    rtl: Boolean,
    loader: ImageLoader,
    current: Boolean,
    onTap: (Float) -> Unit,
    actions: ComicsActions,
) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember(attempt) { mutableStateOf(false) }
    val request = remember(model, attempt) {
        ImageRequest.Builder(context)
            .data(model)
            .listener(
                onSuccess = { _, result -> actions.onPageSize(page, result.image.width, result.image.height) },
                onError = { _, _ -> failed = true },
            )
            .build()
    }
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maximum = ZoomLimit(MAX_ZOOM)))
    val image = rememberZoomableImageState(zoom)
    LaunchedEffect(current) { if (!current) zoom.resetZoom(animationSpec = snap()) }
    LaunchedEffect(zoom) {
        // The first known fraction is the page fitted as it loads; only later changes are the user's zooming.
        snapshotFlow { zoom.zoomFraction }.filterNotNull().distinctUntilChanged().drop(1).collect { actions.onActivity() }
    }
    var width by remember { mutableIntStateOf(1) }
    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width.coerceAtLeast(1) }) {
        if (LocalInspectionMode.current) {
            // Screenshot tests: Coil's preview handler draws the page (telephoto has none).
            AsyncImage(model = model, contentDescription = null, imageLoader = loader, modifier = Modifier.fillMaxSize(), contentScale = contentScale(settings.fitMode))
        } else {
            key(attempt) {
                ZoomableAsyncImage(
                    model = request,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    state = image,
                    imageLoader = loader,
                    alignment = pageAlignment(settings.fitMode, rtl),
                    contentScale = contentScale(settings.fitMode),
                    onClick = { offset -> onTap(offset.x / width) },
                    onDoubleClick = DoubleClickToZoomListener.cycle(DOUBLE_TAP_ZOOM),
                )
            }
            if (failed) {
                ErrorState(
                    onRetry = { attempt++ },
                    message = stringResource(R.string.comics_page_failed),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (!image.isImageDisplayed) {
                LoadingState(Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Two pages side by side (or one beside a blank half), zoomed together. */
@Composable
private fun SpreadPage(
    spread: Spread,
    state: ComicsUiState,
    loader: ImageLoader,
    current: Boolean,
    onTap: (Float) -> Unit,
    actions: ComicsActions,
) {
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maximum = ZoomLimit(MAX_ZOOM)))
    LaunchedEffect(current) { if (!current) zoom.resetZoom(animationSpec = snap()) }
    LaunchedEffect(zoom) {
        snapshotFlow { zoom.zoomFraction }.filterNotNull().distinctUntilChanged().drop(1).collect { actions.onActivity() }
    }
    var width by remember { mutableIntStateOf(1) }
    Row(
        Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .zoomable(
                state = zoom,
                onClick = { offset -> onTap(offset.x / width) },
                onDoubleClick = DoubleClickToZoomListener.cycle(DOUBLE_TAP_ZOOM),
            ),
        horizontalArrangement = Arrangement.spacedBy(state.settings.spreadGap.dp),
    ) {
        SpreadHalf(spread.left, state, loader, Alignment.CenterEnd, actions)
        SpreadHalf(spread.right, state, loader, Alignment.CenterStart, actions)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SpreadHalf(
    page: Int?,
    state: ComicsUiState,
    loader: ImageLoader,
    alignment: Alignment,
    actions: ComicsActions,
) {
    if (page == null) {
        Spacer(Modifier.weight(1f).fillMaxHeight())
        return
    }
    // A failed page offers Retry (a new attempt makes a new request), as a single page does.
    var attempt by remember(page) { mutableIntStateOf(0) }
    var loaded by remember(page, attempt) { mutableStateOf(false) }
    var failed by remember(page, attempt) { mutableStateOf(false) }
    Box(Modifier.weight(1f).fillMaxHeight()) {
        key(attempt) {
            AsyncImage(
                model = state.source?.model(page),
                contentDescription = null,
                imageLoader = loader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alignment = alignment,
                onSuccess = {
                    loaded = true
                    actions.onPageSize(page, it.result.image.width, it.result.image.height)
                },
                onError = { failed = true },
            )
        }
        PageLoadState(loaded, failed, onRetry = { attempt++ }, Modifier.align(Alignment.Center))
    }
}

/** A page still loading (a spinner), or failed (the message and Retry). */
@Composable
private fun PageLoadState(loaded: Boolean, failed: Boolean, onRetry: () -> Unit, modifier: Modifier) {
    if (loaded || LocalInspectionMode.current) return
    if (failed) {
        ErrorState(onRetry = onRetry, message = stringResource(R.string.comics_page_failed), modifier = modifier, compact = true)
    } else {
        LoadingState(modifier)
    }
}

private fun contentScale(fit: String): ContentScale = when (fit) {
    Fit.WIDTH -> ContentScale.FillWidth
    Fit.HEIGHT -> ContentScale.FillHeight
    Fit.ACTUAL -> ContentScale.None
    else -> ContentScale.Fit
}

/** Where a page larger than the screen starts: its top, and its first side in reading order. */
private fun pageAlignment(fit: String, rtl: Boolean): Alignment = when (fit) {
    Fit.WIDTH -> Alignment.TopCenter
    Fit.HEIGHT -> if (rtl) Alignment.CenterEnd else Alignment.CenterStart
    Fit.ACTUAL -> if (rtl) Alignment.TopEnd else Alignment.TopStart
    else -> Alignment.Center
}

// --- infinite and long strip -------------------------------------------------------------------

/**
 * Pages one under another: with gaps (infinite) or none (long strip, for webtoons). Pinch or double
 * tap widens the column (up to 3x) and it scrolls sideways too; a tap on either side scrolls most
 * of a screen, the middle toggles the bars.
 */
@Composable
private fun StripPages(
    state: ComicsUiState,
    actions: ComicsActions,
    loader: ImageLoader,
    turner: PageTurner,
    modifier: Modifier,
) {
    val strip = state.settings.scrollMode == ComicsUiState.SCROLL_STRIP
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage.coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val latestActions by rememberUpdatedState(actions)
    var zoom by remember { mutableFloatStateOf(1f) }
    val sideways = rememberScrollState()
    val density = LocalDensity.current

    LaunchedEffect(list, state.pageCount) {
        // Once per opening, go to the page it chose before reporting anything: a list restored after
        // the process was killed keeps its old place (rememberSaveable ignores the initial index),
        // which would then be saved over the server's newer page. A rotation keeps its place.
        if (!state.stripPlaced) {
            val start = state.currentPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
            if (list.firstVisibleItemIndex != start) list.scrollToItem(start)
            latestActions.onStripPlaced()
        }
        snapshotFlow { mostVisiblePage(list.layoutInfo, state.pageCount) }
            .distinctUntilChanged()
            .collect { page -> page?.let { latestActions.onPagesShown(listOf(it)) } }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.filter { it }.collect { latestActions.onActivity() }
    }
    LaunchedEffect(state.jump) {
        val jump = state.jump ?: return@LaunchedEffect
        list.scrollToItem(jump.page.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
        latestActions.onJumpHandled(jump.id)
    }

    /**
     * Zooms the column to [after], keeping the point at [focus] (in the viewport) where it is: the
     * sideways scroll moves with it, and so does the list, whose pages grow with the zoom while it
     * keeps the first page's offset in pixels.
     */
    fun zoomTo(after: Float, focus: Offset) {
        val before = zoom
        if (after == before) return
        zoom = after
        val grow = after / before
        val target = (sideways.value + focus.x) * grow - focus.x
        scope.launch { sideways.scrollTo(target.toInt().coerceAtLeast(0)) }
        list.dispatchRawDelta((list.firstVisibleItemScrollOffset + focus.y) * (grow - 1f))
        latestActions.onActivity()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val viewportWidth = maxWidth
        val step = with(density) { viewportHeight.toPx() } * 0.85f
        SideEffect {
            turner.next = {
                if (list.canScrollForward) scope.launch { list.animateScrollBy(step) } else latestActions.onPastEnd()
            }
            turner.previous = { scope.launch { list.animateScrollBy(-step) } }
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectPinch { factor, centroid -> zoomTo((zoom * factor).coerceIn(1f, STRIP_MAX_ZOOM), centroid) }
                }
                .pointerInput(state.rtl) {
                    detectTapGestures(
                        onTap = { offset -> tapZone(offset.x / size.width, state.rtl, turner, latestActions.onToggleChrome) },
                        onDoubleTap = { offset -> zoomTo(if (zoom > 1.01f) 1f else 2f, offset) },
                    )
                }
                .horizontalScroll(sideways, enabled = zoom > 1f),
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.width(viewportWidth * zoom).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = if (strip) 0.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (strip) 0.dp else 8.dp),
            ) {
                items(count = state.pageCount, key = { it }) { page ->
                    StripPage(page, state, loader, viewportWidth, zoom, viewportHeight * zoom, strip, actions)
                }
                state.nextBook?.takeIf { state.suggestNext }?.let { next ->
                    item(key = "next") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
                            NextIssueCard(next, cover = state.nextCover, autoAdvance = false, onOpen = actions.onOpenNext)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StripPage(
    page: Int,
    state: ComicsUiState,
    loader: ImageLoader,
    viewportWidth: Dp,
    zoom: Float,
    screenHeight: Dp,
    strip: Boolean,
    actions: ComicsActions,
) {
    val fit = state.settings.fitMode
    val wholePage = fit == Fit.PAGE || fit == Fit.HEIGHT
    val ratio = state.ratios[page]
    val context = LocalContext.current
    val density = LocalDensity.current
    val columnPx = with(density) { (viewportWidth * zoom).toPx() }
    val heightPx = stripPageHeight(columnPx, ratio ?: DEFAULT_RATIO)
    // Taller than a layout can be at the column's width: shown whole in the capped box, narrower.
    val capped = !wholePage && heightPx < columnPx / (ratio ?: DEFAULT_RATIO)
    val size = if (wholePage) {
        Modifier.fillMaxWidth().height(screenHeight)
    } else {
        Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() })
    }
    // A page much taller than wide (a webtoon), drawn the column's width: over Coil's usual cap on
    // a bitmap's side it would be decoded narrow and drawn blurred, so it gets a request of its own.
    val tall = !wholePage && ratio != null && with(density) { viewportWidth.toPx() } / ratio > COIL_MAX_BITMAP_SIDE
    var attempt by remember(page) { mutableIntStateOf(0) }
    var loaded by remember(page, attempt) { mutableStateOf(false) }
    var failed by remember(page, attempt) { mutableStateOf(false) }
    // What is on screen, shown while a page found to be tall loads again sharp.
    var shown by remember(page) { mutableStateOf<MemoryCache.Key?>(null) }
    val model = remember(state.source, page, tall) {
        val data = state.source?.model(page)
        if (tall && data != null) {
            tallPageRequest(context, data, with(density) { (viewportWidth * zoom).roundToPx() }, ratio, placeholder = shown)
        } else {
            data
        }
    }
    // The box keeps its size while loading or failed, so a retry doesn't shift the strip.
    Box(size.padding(horizontal = if (strip) 0.dp else 8.dp)) {
        key(attempt) {
            AsyncImage(
                model = model,
                contentDescription = null,
                imageLoader = loader,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (wholePage || capped) ContentScale.Fit else ContentScale.FillWidth,
                onSuccess = {
                    loaded = true
                    shown = it.result.memoryCacheKey
                    actions.onPageSize(page, it.result.image.width, it.result.image.height)
                },
                onError = { failed = true },
            )
        }
        PageLoadState(loaded, failed, onRetry = { attempt++ }, Modifier.align(Alignment.Center))
    }
}

/**
 * A tall page's request at [widthPx] wide (the column's, as the page is laid out): software (a
 * hardware bitmap that tall can pass the GPU's largest texture), not capped at Coil's 4096 px a side,
 * decoded no larger than the source and at most [MAX_STRIP_PAGE_PIXELS], well under the 100 MB a
 * canvas may draw. [placeholder]: the page as drawn so far, kept on screen until this one is in.
 */
private fun tallPageRequest(context: Context, data: Any, widthPx: Int, ratio: Float, placeholder: MemoryCache.Key?): ImageRequest {
    var width = widthPx.coerceAtLeast(1).toDouble()
    var height = width / ratio
    val pixels = width * height
    if (pixels > MAX_STRIP_PAGE_PIXELS) {
        val shrink = sqrt(MAX_STRIP_PAGE_PIXELS / pixels)
        width *= shrink
        height *= shrink
    }
    return ImageRequest.Builder(context)
        .data(data)
        .size(ceil(width).toInt().coerceAtLeast(1), ceil(height).toInt().coerceAtLeast(1))
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .maxBitmapSize(CoilSize.ORIGINAL)
        .allowHardware(false)
        .placeholderMemoryCacheKey(placeholder)
        .build()
}

/**
 * A strip page's height in px at [columnPx] wide, for a page [ratio] (width / height): at most
 * [MAX_STRIP_PAGE_HEIGHT_PX], or [MAX_WIDE_STRIP_PAGE_HEIGHT_PX] next to a column of
 * [WIDE_STRIP_COLUMN_PX] or more, so only a page Compose couldn't measure is capped.
 */
internal fun stripPageHeight(columnPx: Float, ratio: Float): Float =
    (columnPx / ratio).coerceAtMost(if (columnPx < WIDE_STRIP_COLUMN_PX) MAX_STRIP_PAGE_HEIGHT_PX else MAX_WIDE_STRIP_PAGE_HEIGHT_PX)

/** The page taking up most of the viewport (the one "on screen"), or null before layout. */
internal fun mostVisiblePage(info: LazyListLayoutInfo, pageCount: Int): Int? {
    val start = info.viewportStartOffset
    val end = info.viewportEndOffset
    var best: Int? = null
    var bestPixels = -1
    for (item in info.visibleItemsInfo) {
        if (item.index >= pageCount) continue
        val visible = minOf(item.offset + item.size, end) - maxOf(item.offset, start)
        if (visible > bestPixels) {
            bestPixels = visible
            best = item.index
        }
    }
    return best
}

/**
 * Two-finger zoom that leaves one-finger scrolling alone: it looks at every event first
 * ([PointerEventPass.Initial]) and consumes only moves made with two or more fingers down.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPinch(onZoom: (factor: Float, centroid: Offset) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val factor = event.calculateZoom()
                if (factor != 1f) {
                    onZoom(factor, event.calculateCentroid(useCurrent = true))
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
