package io.github.ottershelf.devicetest

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.feature.book.BookDetailUiState
import io.github.ottershelf.feature.library.BookGridContent
import io.github.ottershelf.feature.library.BookQuickViewContent
import io.github.ottershelf.feature.library.GridColumns
import io.github.ottershelf.feature.library.ListView
import io.github.ottershelf.feature.library.PagedState
import io.github.ottershelf.feature.library.QuickViewUiState
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * The library grid (the app's [BookGridContent]: the pinch frame, the lazy grid, the cover cells)
 * with 500 made-up books and covers, no server: a real two-finger pinch on the phone changes the
 * column count and keeps the book under the fingers; flings are timed frame by frame
 * (FrameMetrics); a long press opens the quick view (its actions are never tapped, and go nowhere).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, DelicateCoilApi::class)
class LibraryDeviceTest {

    private val books = (1L..500L).map { id ->
        BookCard(
            id = id,
            title = TITLES[(id % TITLES.size).toInt()] + if (id > TITLES.size) " ${id / TITLES.size + 1}" else "",
            authors = listOf(AUTHORS[(id % AUTHORS.size).toInt()]),
            seriesName = if (id % 4 == 0L) "The Orbit Cycle" else null,
            seriesIndex = if (id % 4 == 0L) (id / 4).toString() else null,
            readingProgress = when {
                id % 7 == 0L -> (id * 13 % 97).toDouble()
                id % 5 == 0L -> 100.0
                else -> null
            },
            readStatus = when {
                id % 7 == 0L -> ReadStatusInfo(status = "reading")
                id % 5 == 0L -> ReadStatusInfo(status = "read")
                else -> null
            },
            hasCover = id % 11 != 0L,
            files = listOf(BookFile(id * 10, if (id % 9 == 0L) "pdf" else "epub", role = "primary")),
        )
    }

    private val view = mutableStateOf(ListView())

    /** The books shown: all of them at once, unless a test pages them in ([loadMore]). */
    private val paged = mutableStateOf(PagedState(items = books, total = books.size))

    /** The grid asked for the next page (on the main thread). */
    private var loadMore: () -> Unit = {}
    private val quick = mutableStateOf<BookCard?>(null)
    private val opened = CopyOnWriteArrayList<Long>()
    private val actions = CopyOnWriteArrayList<String>()
    private val gridRef = AtomicReference<LazyGridState?>(null)
    private val origin = AtomicReference(Offset.Zero)
    private val frameHeight = AtomicLong(0)
    private val traceFocus = AtomicReference<Offset?>(null)
    private val trace = CopyOnWriteArrayList<String>()
    private val pointerTrace = CopyOnWriteArrayList<String>()

    @Before
    fun covers() {
        SingletonImageLoader.setUnsafe(DeviceCovers.loader(Device.context))
    }

    @After
    fun resetCovers() {
        SingletonImageLoader.reset()
    }

    private fun LockScreenHost.showGrid() = setContent {
        OttershelfTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(OttershelfTheme.colors.background)
                    .semantics { testTagsAsResourceId = true },
            ) {
                val grid = rememberLazyGridState()
                gridRef.set(grid)
                // Frame by frame while a pinch is traced: the columns, the book under the fingers and
                // where the same point of the previous frame's book is now.
                LaunchedEffect(grid) {
                    var previous: Long? = null
                    while (true) {
                        withFrameNanos { now ->
                            val focus = traceFocus.get()
                            if (focus == null) {
                                previous = null
                            } else {
                                val info = grid.layoutInfo
                                val under = itemAt(info, focus)
                                val before = previous?.let { key -> info.visibleItemsInfo.firstOrNull { it.key == key } }
                                trace += "${now / 1_000_000} ms, ${info.maxSpan} columns: under the fingers " +
                                    (under?.let { "book ${it.key} at ${"%.2f".format(fractionAt(info, it, focus))}" } ?: "none") +
                                    (previous?.let { key -> "; book $key " + (before?.let { "at ${"%.2f".format(fractionAt(info, it, focus))} of its height" } ?: "off screen") } ?: "")
                                previous = under?.key as Long?
                            }
                        }
                    }
                }
                val v by view
                Box(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            origin.set(it.positionOnScreen())
                            frameHeight.set(it.size.height.toLong())
                        }
                        // While a pinch is traced: every pointer event as the grid gets it (nothing consumed here).
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    if (traceFocus.get() != null) {
                                        pointerTrace += "${e.type} " + e.changes.joinToString { c -> "#${c.id.value} ${if (c.pressed) "down" else "up"}${if (c.previousPressed) "" else "(new)"} (${c.position.x.roundToInt()}, ${c.position.y.roundToInt()})" }
                                    }
                                }
                            }
                        },
                ) {
                    val shown by paged
                    BookGridContent(
                        state = shown,
                        coverOf = { if (it.hasCover) DeviceCover(it.id) else null },
                        onOpen = { opened += it.id },
                        onLoadMore = { loadMore() },
                        onQuickView = { quick.value = it },
                        gridState = grid,
                        view = v,
                        onViewChange = { view.value = it },
                        contentPadding = WindowInsets.systemBars.asPaddingValues(),
                        modifier = Modifier.testTag(GRID),
                    )
                }
                val card by quick
                card?.let { book ->
                    ModalBottomSheet(
                        onDismissRequest = { quick.value = null },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = OttershelfTheme.colors.card,
                    ) {
                        BookQuickViewContent(
                            state = quickState(book),
                            onRead = { actions += "read" },
                            onDetails = { actions += "details" },
                            onDownload = { actions += "download" },
                            onStatus = { actions += "status $it" },
                            onRetry = { actions += "retry" },
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                        )
                    }
                }
            }
        }
    }

    private val grid: LazyGridState get() = gridRef.get() ?: error("no grid")

    private fun columns(): Int = Device.onMain { grid.layoutInfo.maxSpan }

    // --- where the cells are on the screen ------------------------------------------------------
    // The grid sits below its frame's top padding (PagedGrid pads the frame, not the grid, so the
    // refresh indicator shows under the bars): its top is the frame's height less its viewport's.
    // Item offsets are from the grid's content start (after its own before padding); across, from
    // its start padding (the grid's 6dp).

    private fun gridTop(info: LazyGridLayoutInfo): Float = origin.get().y + frameHeight.get() - info.viewportSize.height

    private fun cellRect(info: LazyGridLayoutInfo, item: LazyGridItemInfo): android.graphics.Rect {
        val left = origin.get().x + item.offset.x + GRID_START_DP * Device.context.resources.displayMetrics.density
        val top = gridTop(info) + item.offset.y + info.beforeContentPadding
        return android.graphics.Rect(left.roundToInt(), top.roundToInt(), (left + item.size.width).roundToInt(), (top + item.size.height).roundToInt())
    }

    /** The cell under [point] (screen px). Called on the main thread. */
    private fun itemAt(info: LazyGridLayoutInfo, point: Offset): LazyGridItemInfo? =
        info.visibleItemsInfo.firstOrNull { cellRect(info, it).contains(point.x.roundToInt(), point.y.roundToInt()) }

    /** How far down [item]'s cell [point] is (0 its top, 1 its bottom). */
    private fun fractionAt(info: LazyGridLayoutInfo, item: LazyGridItemInfo, point: Offset): Float {
        val r = cellRect(info, item)
        return (point.y - r.top) / r.height()
    }

    /** The book whose cell holds [point] (screen px), and its cell. */
    private fun bookAt(point: Offset): Pair<Long, android.graphics.Rect>? = Device.onMain {
        val info = grid.layoutInfo
        itemAt(info, point)?.let { (it.key as Long) to cellRect(info, it) }
    }

    /** The cell of [book] on the screen now, or null when it isn't laid out. */
    private fun cellOf(book: Long): android.graphics.Rect? = Device.onMain {
        val info = grid.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.key == book }?.let { cellRect(info, it) }
    }

    /** The accessibility node's bounds of the cell titled [title] (what TalkBack and a finger see). */
    private fun nodeBounds(title: String): android.graphics.Rect? =
        Device.ui.findObject(By.textStartsWith(title))?.visibleBounds

    private fun gridObject(host: LockScreenHost): UiObject2 {
        host.requireOnTop()
        val obj = Device.ui.findObject(By.res(GRID)) ?: error("the grid isn't on screen")
        obj.setGestureMarginPercentage(0.18f)
        return obj
    }

    /**
     * One step of a pinch, frame by frame: the book under the fingers just before the column
     * count changed, and where the same point of it is just after (its fraction of its height
     * under the fingers). Parsed from [trace].
     */
    private data class Step(val columns: String, val book: String, val before: Float, val after: Float?)

    private fun steps(): List<Step> = trace.zipWithNext().mapNotNull { (a, b) ->
        val ca = a.substringAfter(", ").substringBefore(" columns")
        val cb = b.substringAfter(", ").substringBefore(" columns")
        if (ca == cb) return@mapNotNull null
        val book = a.substringAfter("under the fingers book ").substringBefore(" at")
        val before = a.substringAfter("under the fingers book $book at ").substringBefore(';').toFloatOrNull() ?: return@mapNotNull null
        val after = b.substringAfter("; book $book at ", "").substringBefore(" of").toFloatOrNull()
        Step("$ca -> $cb", book, before, after)
    }

    /** Pinches [obj] with the trace on; returns the steps it took. */
    private fun tracedPinch(obj: UiObject2, center: Offset, pinch: UiObject2.() -> Unit): List<Step> {
        trace.clear()
        pointerTrace.clear()
        traceFocus.set(center)
        obj.pinch()
        SystemClock.sleep(1_500)
        traceFocus.set(null)
        Device.log("pointer events: " + pointerTrace.take(4).joinToString(" | ") + " ... " + pointerTrace.takeLast(3).joinToString(" | "))
        Device.report("pinch pointers: first " + pointerTrace.take(3).joinToString(" | ") + "; in all ${pointerTrace.size} events")
        return steps()
    }

    @Test
    fun aRealPinchChangesTheColumnsAndKeepsTheBookUnderTheFingers() {
        LockScreenHost.launch().use { host ->
            host.showGrid()
            Device.waitUntil("the grid", 10_000) { gridRef.get() != null && columns() > 0 }
            SystemClock.sleep(1_000)
            Device.onMain { grid.requestScrollToItem(120) }
            SystemClock.sleep(800)
            val start = columns()
            val width = Device.onMain { Device.context.resources.configuration.screenWidthDp }
            Device.report("library: screen ${width}dp wide, $start columns, range ${GridColumns.range(width - 12f)}")
            Device.screenshot("library/grid_before")

            // Fingers together: smaller covers, more columns.
            var obj = gridObject(host)
            val center = obj.visibleCenter.let { Offset(it.x.toFloat(), it.y.toFloat()) }
            val (anchor, before) = bookAt(center) ?: error("no book under the centre")
            val title = books.first { it.id == anchor }.title!!
            val node = nodeBounds(title)
            Device.report("library: book $anchor ($title) under the fingers at $center: cell $before, its node $node")
            // The harness' cell must hold the title as the accessibility tree has it (a check on its coordinates).
            assertTrue("the harness' cell $before doesn't hold the title's node $node", node != null && before.contains(node))
            val closeSteps = tracedPinch(obj, center) { pinchClose(0.75f, 1_200) }
            val closed = columns()
            val after = cellOf(anchor)
            Device.report("library: pinch close $start -> $closed columns; book $anchor was at $before, now at $after; steps $closeSteps")
            Device.screenshot("library/grid_pinched_close")
            assertTrue("pinching the fingers together didn't add columns ($start -> $closed)", closed > start)
            assertNotNull("book $anchor, under the fingers, left the screen", after)
            assertEquals("the pinch opened a book", emptyList<Long>(), opened.toList())
            assertEquals("the pinch opened the quick view", null, quick.value)

            // Fingers apart: bigger covers, fewer columns.
            obj = gridObject(host)
            val center2 = obj.visibleCenter.let { Offset(it.x.toFloat(), it.y.toFloat()) }
            val (anchor2, _) = bookAt(center2) ?: error("no book under the centre")
            val openSteps = tracedPinch(obj, center2) { pinchOpen(0.75f, 1_200) }
            val spread = columns()
            val visible2 = cellOf(anchor2) != null
            Device.report("library: pinch open $closed -> $spread columns; book $anchor2 still on screen: $visible2; steps $openSteps")
            Device.screenshot("library/grid_pinched_open")
            assertTrue("spreading the fingers didn't remove columns ($closed -> $spread)", spread < closed)
            assertTrue("book $anchor2, under the fingers, left the screen", visible2)
            assertEquals(emptyList<Long>(), opened.toList())
            assertEquals("the pinch was saved as the view", GridColumns.count(width - 12f, view.value.cellDp), spread)
        }
    }

    /**
     * Every step of a pinch keeps the book under the fingers where it was under them (the same
     * point of it under their centre), slow or fast, one step or several, together or apart:
     * the rule of GridPinch.keepInPlace, measured frame by frame.
     */
    @Test
    fun everyPinchStepKeepsTheSamePointOfTheBookUnderTheFingers() {
        LockScreenHost.launch().use { host ->
            host.showGrid()
            Device.waitUntil("the grid", 10_000) { gridRef.get() != null && columns() > 0 }
            SystemClock.sleep(1_000)
            val misses = mutableListOf<String>()
            val cases = listOf("close" to (300 to 0.3f), "close" to (1_200 to 0.3f), "close" to (300 to 0.75f), "close" to (3_000 to 0.75f), "open" to (1_200 to 0.75f))
            for ((kind, pinch) in cases) {
                val (speed, percent) = pinch
                Device.onMain { view.value = if (kind == "open") ListView(cellDp = 70f) else ListView() }
                SystemClock.sleep(600)
                Device.onMain { grid.requestScrollToItem(120) }
                SystemClock.sleep(900)
                val start = columns()
                val obj = gridObject(host)
                val center = obj.visibleCenter.let { Offset(it.x.toFloat(), it.y.toFloat()) }
                val steps = tracedPinch(obj, center) { if (kind == "open") pinchOpen(percent, speed) else pinchClose(percent, speed) }
                Device.report(
                    "pinch $kind at $speed px/s over ${(percent * 100).roundToInt()}%: $start -> ${columns()} columns; steps (fraction of the book under the fingers before -> after): " +
                        steps.joinToString { "${it.columns} book ${it.book} ${"%.2f".format(it.before)} -> ${it.after?.let { a -> "%.2f".format(a) }}" },
                )
                for (s in steps) {
                    if (s.after == null || kotlin.math.abs(s.after - s.before) > 0.15f) {
                        misses += "$kind $speed px/s ${(percent * 100).roundToInt()}%, ${s.columns}: book ${s.book} ${"%.2f".format(s.before)} -> ${s.after?.let { "%.2f".format(it) } ?: "off screen"}"
                    }
                }
                if (steps.isEmpty()) misses += "$kind $speed px/s ${(percent * 100).roundToInt()}%: no step"
            }
            assertEquals("a pinch step moved the book under the fingers", emptyList<String>(), misses)
        }
    }

    /** How far four flings down got (the first visible item), the refresh rate, and every frame's timing. */
    private data class Flings(val scrolled: Int, val refresh: Float, val stats: FrameRecorder.Stats)

    /** Four flings down and two back up, timed frame by frame. */
    private fun timedFlings(host: LockScreenHost): Flings {
        val frames = FrameRecorder(host.activity.window)
        val refresh = Device.onMain { host.activity.display.refreshRate }
        Device.onMain { frames.start() }
        val obj = gridObject(host)
        val b = obj.visibleBounds
        val x = b.centerX()
        repeat(4) {
            host.requireOnTop()
            Device.ui.swipe(x, b.top + (b.height() * 0.75).toInt(), x, b.top + (b.height() * 0.3).toInt(), 12)
            SystemClock.sleep(900)
        }
        val scrolled = Device.onMain { grid.firstVisibleItemIndex }
        repeat(2) {
            host.requireOnTop()
            Device.ui.swipe(x, b.top + (b.height() * 0.3).toInt(), x, b.top + (b.height() * 0.75).toInt(), 12)
            SystemClock.sleep(900)
        }
        SystemClock.sleep(500)
        Device.onMain { frames.stop() }
        return Flings(scrolled, refresh, frames.stats(refresh))
    }

    @Test
    fun flingsAreTimedFrameByFrame() {
        LockScreenHost.launch().use { host ->
            host.showGrid()
            Device.waitUntil("the grid", 10_000) { gridRef.get() != null && columns() > 0 }
            SystemClock.sleep(1_500)
            val (scrolled, refresh, stats) = timedFlings(host)
            Device.report("library fling: ${"%.1f".format(refresh)} Hz, first visible after 4 flings $scrolled, $stats")
            Device.screenshot("library/grid_after_flings")
            assertTrue("the flings didn't scroll the grid ($scrolled)", scrolled > 30)
            assertTrue("no frames were measured", stats.frames > 30)
            assertEquals(emptyList<Long>(), opened.toList())
        }
    }

    /**
     * The same flings with the books arriving a page at a time as the grid nears its end, as the
     * app's Pager sends them: loading first, then the page a moment later (a quick server), so
     * pages arrive during the flings and every cell on screen sees both. Compare its janky frames
     * in report.txt with [flingsAreTimedFrameByFrame]'s.
     */
    @Test
    fun flingsWhilePagesArriveAreTimedFrameByFrame() {
        val main = Handler(Looper.getMainLooper())
        val pages = AtomicInteger(0)
        Device.onMain { paged.value = PagedState(items = books.take(PAGE), total = books.size) }
        loadMore = {
            val asked = paged.value
            if (!asked.loading && !asked.endReached) {
                paged.value = asked.copy(loading = true)
                main.postDelayed({
                    val now = paged.value
                    paged.value = now.copy(items = books.take(now.items.size + PAGE), loading = false)
                    pages.incrementAndGet()
                }, PAGE_DELAY_MS)
            }
        }
        LockScreenHost.launch().use { host ->
            host.showGrid()
            Device.waitUntil("the grid", 10_000) { gridRef.get() != null && columns() > 0 }
            SystemClock.sleep(1_500)
            val before = pages.get()
            val (scrolled, refresh, stats) = timedFlings(host)
            val arrived = pages.get() - before
            Device.report("library fling while paging: ${"%.1f".format(refresh)} Hz, first visible after 4 flings $scrolled, $arrived pages arrived during the flings, $stats")
            Device.screenshot("library/grid_after_paged_flings")
            assertTrue("the flings didn't scroll the grid ($scrolled)", scrolled > 30)
            assertTrue("no page arrived during the flings", arrived > 0)
            assertTrue("no frames were measured", stats.frames > 30)
            assertEquals(emptyList<Long>(), opened.toList())
        }
    }

    @Test
    fun aLongPressOpensTheQuickView() {
        LockScreenHost.launch().use { host ->
            host.showGrid()
            Device.waitUntil("the grid", 10_000) { gridRef.get() != null && columns() > 0 }
            SystemClock.sleep(1_500)
            val obj = gridObject(host)
            val c = obj.visibleCenter
            val (book, _) = bookAt(Offset(c.x.toFloat(), c.y.toFloat())) ?: error("no book under the centre")
            host.requireOnTop()
            Device.ui.swipe(c.x, c.y, c.x, c.y, 160) // held for about 0.8 s
            Device.waitUntil("the quick view", 5_000) { quick.value != null }
            assertEquals(book, quick.value?.id)
            SystemClock.sleep(1_500) // the sheet slides up
            Device.screenshot("library/quick_view")
            assertEquals("the long press also opened the book", emptyList<Long>(), opened.toList())
            assertEquals("a quick view action ran", emptyList<String>(), actions.toList())
            Device.onMain { quick.value = null }
            SystemClock.sleep(600)
        }
    }

    /** A quick view as the book page's state would fill it (made up: nothing is loaded). */
    private fun quickState(card: BookCard): QuickViewUiState {
        val file = card.files.first().copy(sizeBytes = 812_000)
        return QuickViewUiState(
            card = card,
            cover = if (card.hasCover) DeviceCover(card.id) else null,
            page = BookDetailUiState(
                bookId = card.id,
                preview = card,
                book = BookDetail(
                    id = card.id,
                    title = card.title,
                    authors = card.authors.mapIndexed { i, a -> AuthorRef(i.toLong() + 1, a) },
                    seriesName = card.seriesName,
                    seriesIndex = card.seriesIndex,
                    rating = 4,
                    description = "<p>A made-up book for the device tests: the station's crew keep their ledgers, watch the planet turn " +
                        "and wait for a signal that may never come. Nothing here is loaded from a server.</p>",
                    files = listOf(file),
                ),
                loading = false,
                loaded = true,
                status = card.readStatus?.status ?: "unread",
                readFile = file,
                offlineFile = file,
            ),
        )
    }

    /** Every frame the window draws while recording (FrameMetrics, on a thread of its own). */
    private class FrameRecorder(private val window: Window) {
        private val thread = HandlerThread("device-test-frames").apply { start() }
        private val frames = CopyOnWriteArrayList<LongArray>()
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += longArrayOf(metrics.getMetric(FrameMetrics.TOTAL_DURATION), metrics.getMetric(FrameMetrics.DEADLINE), metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME))
        }

        fun start() = window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))

        fun stop() {
            window.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
        }

        /**
         * [janky]: frames that missed their deadline (FrameMetrics.DEADLINE, the system's own
         * definition); [overTwoVsyncs]: frames longer than two refresh intervals (JankStats' default
         * heuristic); [overOneVsync]: longer than one.
         */
        data class Stats(
            val frames: Int, val janky: Int, val overTwoVsyncs: Int, val overOneVsync: Int,
            val p50: Double, val p90: Double, val p99: Double, val deadline: Double, val vsync: Double,
        ) {
            val jankyPercent: Double get() = percent(janky)
            private fun percent(n: Int) = if (frames == 0) 0.0 else n * 100.0 / frames
            override fun toString() = "frames $frames, janky (missed the deadline) $janky = ${"%.1f".format(jankyPercent)}%, " +
                "over 2 vsyncs (${"%.1f".format(vsync * 2)} ms, JankStats) $overTwoVsyncs = ${"%.1f".format(percent(overTwoVsyncs))}%, " +
                "over 1 vsync $overOneVsync = ${"%.1f".format(percent(overOneVsync))}%, " +
                "frame time p50 ${"%.1f".format(p50)} ms p90 ${"%.1f".format(p90)} ms p99 ${"%.1f".format(p99)} ms, median deadline ${"%.1f".format(deadline)} ms"
        }

        fun stats(refreshRate: Float): Stats {
            val list = frames.filter { it[2] == 0L }
            val totals = list.map { it[0] / 1e6 }.sorted()
            fun pct(p: Double) = if (totals.isEmpty()) 0.0 else totals[((totals.size - 1) * p).roundToInt()]
            val deadline = list.map { it[1] / 1e6 }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
            val vsync = 1000.0 / refreshRate
            return Stats(
                frames = list.size,
                janky = list.count { it[0] > it[1] },
                overTwoVsyncs = totals.count { it > vsync * 2 },
                overOneVsync = totals.count { it > vsync },
                p50 = pct(0.5), p90 = pct(0.9), p99 = pct(0.99),
                deadline = deadline,
                vsync = vsync,
            )
        }
    }

    private companion object {
        const val GRID = "library-grid"

        // A page of books, as the app's Pager asks for them, and how long one takes to arrive.
        const val PAGE = 60
        const val PAGE_DELAY_MS = 150L

        /** The grid pads its start with BOOK_GRID_SPACING. */
        const val GRID_START_DP = 6f
        val TITLES = listOf(
            "The Harbour", "Winter Quarters", "Lanterns", "The Long Road", "Salt and Copper", "The Ledger", "Night Watch",
            "A Signal", "The Crossing", "Homecoming", "Letters North", "The Bell Tower", "Silver Window", "Quiet Market",
        )
        val AUTHORS = listOf("Ada Lovelace", "Mary Shelley", "Arthur Conan Doyle", "Louisa May Alcott", "Alessandro Manzoni", "Selma Lagerlöf")
    }
}
