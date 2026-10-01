package io.github.ottershelf.feature.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.graphics.pdf.content.PdfPageGotoLinkContent
import android.graphics.pdf.content.PdfPageLinkContent
import android.graphics.pdf.models.PageMatchBounds
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
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

/**
 * The document needs a password, or the one given didn't open it. [canUnlock] false: this phone's
 * renderer can't open protected PDFs at all (asking for the password would be no use).
 */
class PdfPasswordException(val canUnlock: Boolean = true) : Exception("Password required")

/**
 * One open PDF, drawn by pdfium through the platform's renderer. It isn't thread-safe and only one
 * page may be open at a time, so every call runs on this document's own single-threaded
 * dispatcher, in order; a call whose caller has gone (a page scrolled away) never starts.
 *
 * Which renderer depends on the phone ([Document.open]): Android 15's PdfRenderer; on Android 12
 * to 14, the same features as PdfRendererPreV when the phone's PDF module has them (Google Play
 * system updates, S extension 13); otherwise the original PdfRenderer, which only draws pages:
 * no search ([searchable] false), no links, and protected PDFs don't open.
 */
class PdfEngine private constructor(
    private val document: Document,
    private val thread: CoroutineDispatcher,
) {
    val pageCount: Int = document.pageCount

    /** Search (and the page text it needs) works on this phone. */
    val searchable: Boolean = document.hasText

    private val links = ConcurrentHashMap<Int, List<PdfLink>>()

    @Volatile
    private var closed = false

    /** Page [index]'s size in points. */
    suspend fun pageSize(index: Int): Pair<Float, Float>? = onThread {
        document.openPage(index).use { it.width.toFloat() to it.height.toFloat() }
    }

    /** The sizes of pages [from] until [to]. */
    suspend fun pageSizes(from: Int, to: Int): List<Pair<Float, Float>> = onThread {
        (from until to.coerceAtMost(pageCount)).map { i -> document.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }
    } ?: emptyList()

    /** The whole page at [width] x [height] px, on white (pages are transparent where nothing is drawn). */
    suspend fun render(index: Int, width: Int, height: Int): Bitmap? = onThread {
        document.openPage(index).use { page ->
            if (!links.containsKey(index)) links[index] = runCatching { page.links() }.getOrDefault(emptyList())
            val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, Matrix().apply { setScale(bitmap.width / page.width.toFloat(), bitmap.height / page.height.toFloat()) })
            bitmap
        }
    }

    /** [region] (px of the page drawn at [fullWidth] x [fullHeight]) as its own bitmap, for sharp zoomed text. */
    suspend fun renderRegion(index: Int, fullWidth: Int, fullHeight: Int, region: IntRect): Bitmap? = onThread {
        if (region.width <= 0 || region.height <= 0) return@onThread null
        document.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply {
                setScale(fullWidth / page.width.toFloat(), fullHeight / page.height.toFloat())
                postTranslate(-region.left.toFloat(), -region.top.toFloat())
            }
            page.render(bitmap, matrix)
            bitmap
        }
    }

    /** The matches for [query] on page [index] with a line of context each (pdfium's search: case-insensitive). */
    suspend fun search(index: Int, query: String): List<PdfHit> = onThread {
        if (!searchable) return@onThread emptyList()
        document.openPage(index).use { page ->
            val matches = page.search(query)
            if (matches.isEmpty()) return@use emptyList()
            val text = runCatching { page.text() }.getOrDefault("")
            matches.map { match ->
                val snippet = PdfMath.snippet(text, match.start, query.length)
                PdfHit(
                    page = index,
                    rects = match.rects,
                    snippet = snippet.text,
                    matchStart = snippet.matchStart,
                    matchLength = snippet.matchLength,
                )
            }
        }
    } ?: emptyList()

    /** The links of a page already rendered (null: not read yet). */
    fun links(index: Int): List<PdfLink>? = links[index]

    /** Closes the document once the calls already queued have run. */
    suspend fun close() = withContext(thread + NonCancellable) {
        if (closed) return@withContext
        closed = true
        runCatching { document.close() }
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
                made?.let { runCatching { it.document.close() } }
                throw e
            }
            return checkNotNull(made)
        }

        private fun create(file: File, password: String?): PdfEngine {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                @Suppress("OPT_IN_USAGE")
                return PdfEngine(Document.open(fd, password), Dispatchers.IO.limitedParallelism(1))
            } catch (e: PdfPasswordException) {
                runCatching { fd.close() }
                throw e
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

/** One match of a search: where it starts in the page's text, and its boxes in page points. */
private class TextMatch(val start: Int, val rects: List<PdfRect>)

/** An open document, whichever renderer draws it. Closing it closes its file descriptor. */
private interface Document : AutoCloseable {
    val pageCount: Int

    /** Text, search and links are there ([Page.search], [Page.text] and [Page.links] work). */
    val hasText: Boolean

    fun openPage(index: Int): Page

    companion object {
        /** The best renderer this phone has (see [PdfEngine]). */
        fun open(fd: ParcelFileDescriptor, password: String?): Document = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> PlatformDocument.open(fd, password)
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13 -> ModuleDocument.open(fd, password)
            else -> BasicDocument.open(fd, password)
        }
    }
}

/** One open page. Only one may be open at a time. */
private interface Page : AutoCloseable {
    val width: Int
    val height: Int

    /** Draws the page into [bitmap], placed by [matrix] (page points to bitmap pixels). */
    fun render(bitmap: Bitmap, matrix: Matrix)

    fun search(query: String): List<TextMatch>

    fun text(): String

    /** The page's links: those inside the document first, then web links. */
    fun links(): List<PdfLink>
}

/** Android 15 and later: the platform's PdfRenderer, with text, search, links and passwords. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private class PlatformDocument(private val renderer: PdfRenderer) : Document {
    override val pageCount: Int get() = renderer.pageCount
    override val hasText = true

    override fun openPage(index: Int): Page {
        val page = renderer.openPage(index)
        return object : Page {
            override val width get() = page.width
            override val height get() = page.height
            override fun render(bitmap: Bitmap, matrix: Matrix) = page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            override fun search(query: String) = page.searchText(query).map { TextMatch(it.textStartIndex, it.bounds.map(::pdfRect)) }
            override fun text() = page.textContents.joinToString("") { it.text }
            override fun links() = page.gotoLinks.map { PdfLink(it.bounds.map(::pdfRect), uri = null, page = it.destination.pageNumber) } +
                page.linkContents.map { PdfLink(it.bounds.map(::pdfRect), uri = it.uri.toString(), page = null) }
            override fun close() = page.close()
        }
    }

    override fun close() = renderer.close()

    companion object {
        fun open(fd: ParcelFileDescriptor, password: String?) = PlatformDocument(
            if (password == null) PdfRenderer(fd) else PdfRenderer(fd, LoadParams.Builder().setPassword(password).build()),
        )
    }
}

/**
 * Android 12 to 14 with an up-to-date PDF module (Google Play system updates, S extension 13):
 * PdfRendererPreV, the same features as Android 15's renderer.
 */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private class ModuleDocument(private val renderer: PdfRendererPreV) : Document {
    override val pageCount: Int get() = renderer.pageCount
    override val hasText = true

    override fun openPage(index: Int): Page {
        val page = renderer.openPage(index)
        return object : Page {
            override val width get() = page.width
            override val height get() = page.height
            override fun render(bitmap: Bitmap, matrix: Matrix) = page.render(bitmap, null, matrix, DISPLAY)
            override fun search(query: String) = page.searchText(query).map { it.toMatch() }
            override fun text() = page.textContents.joinToString("") { it.text }
            override fun links() = page.gotoLinks.map { it.toLink() } + page.linkContents.map { it.toLink() }
            override fun close() = page.close()
        }
    }

    override fun close() = renderer.close()

    companion object {
        private val DISPLAY by lazy { RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build() }

        fun open(fd: ParcelFileDescriptor, password: String?) = ModuleDocument(
            if (password == null) PdfRendererPreV(fd) else PdfRendererPreV(fd, LoadParams.Builder().setPassword(password).build()),
        )
    }
}

/**
 * Android 12 to 14 without the PDF module's update: the original PdfRenderer, which only draws
 * pages. It can't open protected PDFs ([PdfPasswordException] with canUnlock false).
 */
private class BasicDocument(private val renderer: PdfRenderer) : Document {
    override val pageCount: Int get() = renderer.pageCount
    override val hasText = false

    override fun openPage(index: Int): Page {
        val page = renderer.openPage(index)
        return object : Page {
            override val width get() = page.width
            override val height get() = page.height
            override fun render(bitmap: Bitmap, matrix: Matrix) = page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            override fun search(query: String) = emptyList<TextMatch>()
            override fun text() = ""
            override fun links() = emptyList<PdfLink>()
            override fun close() = page.close()
        }
    }

    override fun close() = renderer.close()

    companion object {
        fun open(fd: ParcelFileDescriptor, password: String?): BasicDocument = try {
            BasicDocument(PdfRenderer(fd))
        } catch (e: SecurityException) {
            throw PdfPasswordException(canUnlock = false)
        }
    }
}

// The model classes below come with both newer renderers (Android 15, or S extension 13). Android
// 15's PlatformDocument converts them itself: lint doesn't count API 35 as having the extension.

private fun pdfRect(r: RectF) = PdfRect(r.left, r.top, r.right, r.bottom)

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private fun PageMatchBounds.toMatch() = TextMatch(textStartIndex, bounds.map(::pdfRect))

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private fun PdfPageGotoLinkContent.toLink() =
    PdfLink(bounds.map(::pdfRect), uri = null, page = destination.pageNumber)

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private fun PdfPageLinkContent.toLink() =
    PdfLink(bounds.map(::pdfRect), uri = uri.toString(), page = null)

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
