package io.github.ottershelf.feature.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What the page views draw from: page bitmaps (whole pages at a size, or a part of a zoomed page)
 * and the links found on pages already drawn. [PdfEngine] behind a [PageBitmapCache] in the app;
 * made-up pages in the screenshot tests.
 */
interface PdfPageSource {
    /** A whole page rendered at [width] px wide, if it is in the cache. */
    fun cached(index: Int, width: Int): ImageBitmap?

    /** Page [index] (0-based) rendered to [width] x [height] px; null if it can't be. */
    suspend fun render(index: Int, width: Int, height: Int): ImageBitmap?

    /** The part [region] of page [index] drawn at [fullWidth] x [fullHeight] px (a zoomed page's visible part). */
    suspend fun renderRegion(index: Int, fullWidth: Int, fullHeight: Int, region: IntRect): ImageBitmap?

    /** The link at ([x], [y]) in page points, for a page already rendered. */
    fun linkAt(index: Int, x: Float, y: Float): PdfLink?
}

/** The document needs a password, or the one given didn't open it. */
class PdfPasswordException : Exception("Password required")

/**
 * One open PDF: the platform [PdfRenderer] (pdfium). It isn't thread-safe and only one page may be
 * open at a time, so every call runs on this document's own single-threaded dispatcher, in order;
 * a call whose caller has gone (a page scrolled away) never starts. API 35's text, search and link
 * APIs back the search and the tappable links.
 */
class PdfEngine private constructor(
    private val renderer: PdfRenderer,
    private val thread: CoroutineDispatcher,
) {
    val pageCount: Int = renderer.pageCount

    private val links = ConcurrentHashMap<Int, List<PdfLink>>()

    @Volatile
    private var closed = false

    /** Page [index]'s size in points. */
    suspend fun pageSize(index: Int): Pair<Float, Float>? = onThread {
        renderer.openPage(index).use { it.width.toFloat() to it.height.toFloat() }
    }

    /** The sizes of pages [from] until [to]. */
    suspend fun pageSizes(from: Int, to: Int): List<Pair<Float, Float>> = onThread {
        (from until to.coerceAtMost(pageCount)).map { i -> renderer.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }
    } ?: emptyList()

    /** The whole page at [width] x [height] px, on white (pages are transparent where nothing is drawn). */
    suspend fun render(index: Int, width: Int, height: Int): Bitmap? = onThread {
        renderer.openPage(index).use { page ->
            if (!links.containsKey(index)) links[index] = readLinks(page)
            val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply { setScale(bitmap.width / page.width.toFloat(), bitmap.height / page.height.toFloat()) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /** [region] (px of the page drawn at [fullWidth] x [fullHeight]) as its own bitmap, for sharp zoomed text. */
    suspend fun renderRegion(index: Int, fullWidth: Int, fullHeight: Int, region: IntRect): Bitmap? = onThread {
        if (region.width <= 0 || region.height <= 0) return@onThread null
        renderer.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply {
                setScale(fullWidth / page.width.toFloat(), fullHeight / page.height.toFloat())
                postTranslate(-region.left.toFloat(), -region.top.toFloat())
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /** The matches for [query] on page [index] with a line of context each (pdfium's search: case-insensitive). */
    suspend fun search(index: Int, query: String): List<PdfHit> = onThread {
        renderer.openPage(index).use { page ->
            val matches = page.searchText(query)
            if (matches.isEmpty()) return@use emptyList()
            val text = runCatching { page.textContents.joinToString("") { it.text } }.getOrDefault("")
            matches.map { match ->
                val snippet = PdfMath.snippet(text, match.textStartIndex, query.length)
                PdfHit(
                    page = index,
                    rects = match.bounds.map { PdfRect(it.left, it.top, it.right, it.bottom) },
                    snippet = snippet.text,
                    matchStart = snippet.matchStart,
                    matchLength = snippet.matchLength,
                )
            }
        }
    } ?: emptyList()

    /** The links of a page already rendered (null: not read yet). */
    fun links(index: Int): List<PdfLink>? = links[index]

    private fun readLinks(page: PdfRenderer.Page): List<PdfLink> = runCatching {
        val web = page.linkContents.map { link ->
            PdfLink(link.bounds.map { PdfRect(it.left, it.top, it.right, it.bottom) }, uri = link.uri.toString(), page = null)
        }
        val inside = page.gotoLinks.map { link ->
            PdfLink(link.bounds.map { PdfRect(it.left, it.top, it.right, it.bottom) }, uri = null, page = link.destination.pageNumber)
        }
        inside + web
    }.getOrDefault(emptyList())

    /** Closes the document once the calls already queued have run. */
    suspend fun close() = withContext(thread + NonCancellable) {
        if (closed) return@withContext
        closed = true
        runCatching { renderer.close() }
    }

    private suspend fun <T> onThread(block: () -> T): T? = withContext(thread) {
        if (closed) null
        else try {
            block()
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: RuntimeException) {
            // A page pdfium can't parse, or the document closing under a queued call: that page stays blank.
            null
        }
    }

    companion object {
        /**
         * Opens [file] (with [password] for an encrypted one); throws [PdfPasswordException] when
         * that's what's missing. If the caller is cancelled (the reader closed) while the renderer
         * is being made, withContext drops the finished one: it is closed here instead of leaking
         * its native document and file descriptor.
         */
        suspend fun open(file: File, password: String?): PdfEngine {
            var made: PdfEngine? = null
            try {
                withContext(Dispatchers.IO) { made = create(file, password) }
            } catch (e: CancellationException) {
                made?.let { runCatching { it.renderer.close() } }
                throw e
            }
            return checkNotNull(made)
        }

        private fun create(file: File, password: String?): PdfEngine {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = if (password == null) PdfRenderer(fd)
                else PdfRenderer(fd, LoadParams.Builder().setPassword(password).build())
                @Suppress("OPT_IN_USAGE")
                return PdfEngine(renderer, Dispatchers.IO.limitedParallelism(1))
            } catch (e: SecurityException) {
                runCatching { fd.close() }
                throw PdfPasswordException()
            } catch (e: Throwable) {
                runCatching { fd.close() }
                throw e
            }
        }
    }
}

/**
 * Whole-page bitmaps by page and width, bounded by their bytes (least recently used go first).
 * Thread-safe (LruCache is synchronized). Evicted bitmaps are left to the garbage collector: one
 * may still be on screen for a frame.
 */
class PageBitmapCache(maxBytes: Int) {
    private val cache = object : LruCache<Long, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: Long, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }

    fun get(index: Int, width: Int): ImageBitmap? = cache.get(key(index, width))
    fun put(index: Int, width: Int, bitmap: ImageBitmap) {
        cache.put(key(index, width), bitmap)
    }

    fun clear() = cache.evictAll()

    private fun key(index: Int, width: Int) = (index.toLong() shl 32) or (width.toLong() and 0xffffffffL)

    companion object {
        /** A quarter of the app's heap limit, between 32 and 160 MB. */
        fun defaultBytes(): Int = (Runtime.getRuntime().maxMemory() / 4).coerceIn(32L shl 20, 160L shl 20).toInt()
    }
}

/** [PdfPageSource] over an open [PdfEngine]. Whole pages are capped at [MAX_PAGE_PIXELS] (a very long page is drawn smaller). */
class EnginePageSource(private val engine: PdfEngine, private val cache: PageBitmapCache) : PdfPageSource {
    override fun cached(index: Int, width: Int): ImageBitmap? = cache.get(index, width)

    override suspend fun render(index: Int, width: Int, height: Int): ImageBitmap? {
        cache.get(index, width)?.let { return it }
        var w = width
        var h = height
        val pixels = w.toLong() * h
        if (pixels > MAX_PAGE_PIXELS) {
            val f = Math.sqrt(MAX_PAGE_PIXELS.toDouble() / pixels)
            w = (w * f).toInt()
            h = (h * f).toInt()
        }
        val bitmap = engine.render(index, w, h)?.asImageBitmap() ?: return null
        cache.put(index, width, bitmap)
        return bitmap
    }

    override suspend fun renderRegion(index: Int, fullWidth: Int, fullHeight: Int, region: IntRect): ImageBitmap? =
        engine.renderRegion(index, fullWidth, fullHeight, region)?.asImageBitmap()

    override fun linkAt(index: Int, x: Float, y: Float): PdfLink? =
        engine.links(index)?.firstOrNull { link -> link.bounds.any { it.contains(x, y) } }

    companion object {
        const val MAX_PAGE_PIXELS = 8_000_000L
    }
}
