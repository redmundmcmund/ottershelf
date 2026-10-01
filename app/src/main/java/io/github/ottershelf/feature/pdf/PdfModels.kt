package io.github.ottershelf.feature.pdf

import io.github.ottershelf.core.readerprefs.PdfReaderSettings
import io.github.ottershelf.core.readerprefs.ReaderSettingsSpecs
import io.github.ottershelf.feature.reader.PositionChoice
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How pages follow each other: one long vertical strip (the default), or one page at a time. */
enum class PdfScroll { Continuous, Paged }

/** What a page fills at zoom 1: the viewport's width, or the whole page inside the viewport. */
enum class PdfFit { Width, Page }

/** What the reader applies: the synced settings (scroll, fit) plus the device-only night mode. */
data class PdfView(
    val scroll: PdfScroll = PdfScroll.Continuous,
    val fit: PdfFit = PdfFit.Page,
    /** Page colours inverted (dark paper, light ink). Kept on this device only: the web has no such key. */
    val night: Boolean = false,
)

/**
 * The web's PDF reader settings (`PdfReaderSettings`, synced through core.readerprefs) as this
 * reader applies them. The phone shows one page across, so `spread` is kept but not applied; the
 * web's `horizontal` strip is shown as single pages; `automatic` and `custom` zoom open as fit
 * page (custom scale isn't applied). Our built-in default is the continuous vertical strip
 * ([Spec]); the web's own default (`page`) still applies when the account or the book sets it.
 */
object PdfSettings {
    /** The web's PDF spec with a vertical strip as the phone's built-in default. */
    val Spec = ReaderSettingsSpecs.Pdf.withDefaults(PdfReaderSettings(scrollMode = SCROLL_VERTICAL))

    const val SCROLL_VERTICAL = "vertical"
    const val SCROLL_PAGE = "page"
    const val ZOOM_FIT_WIDTH = "fit-width"
    const val ZOOM_FIT_PAGE = "fit-page"

    fun view(settings: PdfReaderSettings, night: Boolean) = PdfView(
        scroll = if (settings.scrollMode == SCROLL_VERTICAL) PdfScroll.Continuous else PdfScroll.Paged,
        fit = if (settings.zoomMode == ZOOM_FIT_WIDTH) PdfFit.Width else PdfFit.Page,
        night = night,
    )

    fun withScroll(settings: PdfReaderSettings, scroll: PdfScroll) =
        settings.copy(scrollMode = if (scroll == PdfScroll.Continuous) SCROLL_VERTICAL else SCROLL_PAGE)

    fun withFit(settings: PdfReaderSettings, fit: PdfFit) =
        settings.copy(zoomMode = if (fit == PdfFit.Width) ZOOM_FIT_WIDTH else ZOOM_FIT_PAGE)
}

/** A rectangle in page points (1/72 inch), origin at the page's top left, as PdfRenderer reports them. */
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** A link on a page: [uri] (a web or mail link) or [page] (0-based, a jump inside the document). */
data class PdfLink(val bounds: List<PdfRect>, val uri: String?, val page: Int?)

/** One search match: [page] 0-based, its [rects] on the page, and a line of context with the match in it. */
data class PdfHit(
    val page: Int,
    val rects: List<PdfRect>,
    val snippet: String,
    val matchStart: Int,
    val matchLength: Int,
) {
    val top: Float get() = rects.minOfOrNull { it.top } ?: 0f
}

/** In-document search: the query, the matches found so far, and how far it got. */
data class PdfSearch(
    val query: String = "",
    val hits: List<PdfHit> = emptyList(),
    val running: Boolean = false,
    /** Pages searched so far (for "Searching… page 45 of 300"). */
    val searched: Int = 0,
    /** The match shown (index into [hits]), or -1. */
    val selected: Int = -1,
    /** Stopped at [PdfViewModel.MAX_HITS]. */
    val capped: Boolean = false,
) {
    val active: Boolean get() = query.isNotEmpty()
}

/** Every page's size in points; until a page has been measured it has the first page's size. */
class PdfPageSizes(private val values: FloatArray) {
    val count: Int get() = values.size / 2
    fun width(index: Int): Float = values.getOrElse(index * 2) { 612f }
    fun height(index: Int): Float = values.getOrElse(index * 2 + 1) { 792f }

    /** A copy with pages from [from] set to [sizes] (width, height pairs); this one if nothing changed. */
    fun with(from: Int, sizes: List<Pair<Float, Float>>): PdfPageSizes {
        var copy: FloatArray? = null
        sizes.forEachIndexed { i, (w, h) ->
            val at = (from + i) * 2
            if (at + 1 >= values.size) return@forEachIndexed
            if (values[at] != w || values[at + 1] != h) {
                val c = copy ?: values.copyOf().also { copy = it }
                c[at] = w
                c[at + 1] = h
            }
        }
        return copy?.let(::PdfPageSizes) ?: this
    }

    companion object {
        val Empty = PdfPageSizes(FloatArray(0))
        fun uniform(count: Int, width: Float, height: Float) =
            PdfPageSizes(FloatArray(count * 2) { if (it % 2 == 0) width else height })
    }
}

/** Where the reader is in opening the document. */
sealed interface PdfPhase {
    /** Fetching the file; [progress] 0..1, or null when the size isn't known yet. */
    data class Loading(val progress: Float? = null) : PdfPhase
    data object Opening : PdfPhase
    /** The document is encrypted; [wrong] after a password that didn't open it. */
    data class Password(val wrong: Boolean) : PdfPhase
    /**
     * [offline]: no connection and no copy on the phone; [locked]: a protected PDF this phone's
     * renderer can't open; else [message] (null: no detail).
     */
    data class Failed(val offline: Boolean, val message: String? = null, val locked: Boolean = false) : PdfPhase
    data object Ready : PdfPhase
}

data class PdfUiState(
    val title: String = "",
    val phase: PdfPhase = PdfPhase.Loading(),
    val pageCount: Int = 0,
    val sizes: PdfPageSizes = PdfPageSizes.Empty,
    /** The page on screen, 1-based. */
    val page: Int = 1,
    val view: PdfView = PdfView(),
    /** This book has settings of its own (Reset / Use for all PDFs). */
    val customized: Boolean = false,
    val chromeVisible: Boolean = true,
    /** The document's outline (bookmarks), empty when it has none. */
    val outline: List<OutlineEntry> = emptyList(),
    val search: PdfSearch = PdfSearch(),
    /** The phone can search this PDF's text (not on Android 12 to 14 without the PDF module's update). */
    val searchable: Boolean = true,
    /** Two reading positions: the prompt is showing. */
    val choice: PositionChoice? = null,
    /** Draws the pages (null until the document is open). */
    val source: PdfPageSource? = null,
) {
    val ready: Boolean get() = phase == PdfPhase.Ready && source != null

    /** The outline entry the page on screen belongs to (the last one starting on or before it), or -1. */
    val outlineIndex: Int get() = PdfMath.outlineIndex(outline, page - 1)
}

/** A place to go: [page] 0-based; [y] how far down the page (0..1) should come into view, null for its top. */
data class PdfJump(val page: Int, val y: Float? = null, val animate: Boolean = false)

sealed interface PdfMessage {
    data class FreshStart(val statusLabel: String) : PdfMessage
}

/** A line of text around a search match. */
data class Snippet(val text: String, val matchStart: Int, val matchLength: Int)

/** The reader's arithmetic, kept pure for tests. */
object PdfMath {

    /** A page's size in px at zoom 1: [fit] into a [viewWidth] x [viewHeight] px viewport. */
    fun fitSize(pageWidth: Float, pageHeight: Float, viewWidth: Float, viewHeight: Float, fit: PdfFit): Pair<Int, Int> {
        if (pageWidth <= 0f || pageHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return 1 to 1
        val scale = when (fit) {
            PdfFit.Width -> viewWidth / pageWidth
            PdfFit.Page -> min(viewWidth / pageWidth, viewHeight / pageHeight)
        }
        return max(1, (pageWidth * scale).roundToInt()) to max(1, (pageHeight * scale).roundToInt())
    }

    /**
     * The page on screen in the continuous strip: the one with the most of it in the viewport, the
     * earlier of two that show as much (pdf.js' rule); the first while the strip is at its top and
     * the last once it can't scroll further, so a short first or last page still counts. The
     * opening and every jump put a page at the top, so pages shorter than half the screen (landscape
     * pages, slides) must still count that one, not the next. [items]: (index, offset, size) of the
     * laid-out pages.
     */
    fun currentIndex(items: List<Triple<Int, Int, Int>>, viewportStart: Int, viewportEnd: Int, atTop: Boolean, atEnd: Boolean): Int? {
        if (items.isEmpty()) return null
        if (atEnd && !atTop) return items.last().first
        if (atTop) return items.first().first
        var best = items.first().first
        var bestPixels = Int.MIN_VALUE
        for ((index, offset, size) in items) {
            val visible = min(viewportEnd, offset + size) - max(viewportStart, offset)
            if (visible > bestPixels) {
                best = index
                bestPixels = visible
            }
        }
        return best
    }

    /**
     * The outline entry [pageIndex] (0-based) belongs to: the one starting latest on or before it
     * (of several on that page, the last listed, so a section wins over its chapter), or -1.
     */
    fun outlineIndex(outline: List<OutlineEntry>, pageIndex: Int): Int {
        var found = -1
        var foundPage = -1
        outline.forEachIndexed { i, entry ->
            if (entry.page in 0..pageIndex && entry.page >= foundPage) {
                found = i
                foundPage = entry.page
            }
        }
        return found
    }

    /** Context around the match at [start] (length [length]) in a page's [text], whitespace collapsed. */
    fun snippet(text: String, start: Int, length: Int, before: Int = 40, after: Int = 80): Snippet {
        if (text.isEmpty() || start < 0 || start >= text.length) return Snippet("", 0, 0)
        val end = (start + length).coerceAtMost(text.length)
        var from = (start - before).coerceAtLeast(0)
        var to = (end + after).coerceAtMost(text.length)
        // Start and stop on word boundaries where there is one nearby.
        if (from > 0) text.indexOf(' ', from).takeIf { it in from until start }?.let { from = it + 1 }
        if (to < text.length) text.lastIndexOf(' ', to).takeIf { it > end }?.let { to = it }
        val head = collapse(text.substring(from, start))
        val match = collapse(text.substring(start, end))
        val tail = collapse(text.substring(end, to))
        val prefix = (if (from > 0) "…" else "") + head.trimStart()
        val suffix = tail.trimEnd() + if (to < text.length) "…" else ""
        return Snippet(prefix + match + suffix, prefix.length, match.length)
    }

    private val SPACES = Regex("\\s+")
    private fun collapse(s: String) = s.replace(SPACES, " ")
}
