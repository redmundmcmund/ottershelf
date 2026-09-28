package io.github.ottershelf.feature.library

import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.feature.book.BookDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The book grids' view: the column maths, the pinch's steps, and what the quick view shows. */
class LibraryViewTest {

    /** The phone in portrait: 411dp less the grid's 6dp each side. */
    private val portrait = 399f

    /** The phone in landscape: 891dp less a 24dp cutout and the grid's padding. */
    private val landscape = 855f

    // --- columns -------------------------------------------------------------------------------

    @Test
    fun `the phone offers two to six columns in portrait and more in landscape`() {
        assertEquals(2..6, GridColumns.range(portrait))
        assertEquals(5..13, GridColumns.range(landscape))
    }

    @Test
    fun `nothing chosen is the old grid of 120dp cells`() {
        assertEquals(3, GridColumns.count(portrait, null))
        assertEquals(7, GridColumns.count(landscape, null))
    }

    @Test
    fun `a chosen count comes back exactly on the same width`() {
        for (n in 2..6) assertEquals(n, GridColumns.count(portrait, GridColumns.cellFor(portrait, n)))
    }

    @Test
    fun `the cover size is kept when the phone turns`() {
        // Three across in portrait (133dp cells) is six or seven across in landscape.
        val cell = GridColumns.cellFor(portrait, 3)
        assertEquals(6, GridColumns.count(landscape, cell))
        // Six across in portrait is thirteen (the most) in landscape, and two across is five (the least).
        assertEquals(13, GridColumns.count(landscape, GridColumns.cellFor(portrait, 6)))
        assertEquals(5, GridColumns.count(landscape, GridColumns.cellFor(portrait, 2)))
    }

    @Test
    fun `a stored size outside the range is held to it`() {
        assertEquals(6, GridColumns.count(portrait, 10f))
        assertEquals(2, GridColumns.count(portrait, 900f))
    }

    @Test
    fun `steps stay within the range`() {
        assertEquals(4, GridColumns.step(portrait, 3, 1))
        assertEquals(2, GridColumns.step(portrait, 3, -1))
        assertEquals(6, GridColumns.step(portrait, 6, 1))
        assertEquals(2, GridColumns.step(portrait, 2, -1))
    }

    @Test
    fun `a width not yet measured gives one column`() {
        assertEquals(1..1, GridColumns.range(0f))
        assertEquals(1, GridColumns.count(0f, 120f))
    }

    @Test
    fun `the list is one column on the phone and two in landscape`() {
        assertEquals(1, GridColumns.listColumns(portrait))
        assertEquals(2, GridColumns.listColumns(landscape))
    }

    // --- the pinch -----------------------------------------------------------------------------

    @Test
    fun `spreading the fingers steps to fewer columns once the covers have grown most of the way`() {
        val steps = PinchSteps()
        steps.start()
        // Three to two columns makes covers 1.5x; the step comes at 1.5^0.75 (about 1.36).
        assertEquals(0, steps.onZoom(1.2f, 3, 2..6))
        assertEquals(-1, steps.onZoom(1.15f, 3, 2..6))
        // What's left is measured against the new layout: drawn the size it was, so no jump.
        assertEquals(1.2f * 1.15f / 1.5f, steps.zoom, 1e-4f)
    }

    @Test
    fun `pinching the fingers together steps to more columns`() {
        val steps = PinchSteps()
        steps.start()
        // Three to four columns makes covers 0.75x; the step comes at 0.75^0.75 (about 0.81).
        assertEquals(0, steps.onZoom(0.89f, 3, 2..6))
        assertEquals(1, steps.onZoom(0.89f, 3, 2..6))
        assertEquals(0.89f * 0.89f / 0.75f, steps.zoom, 1e-4f)
    }

    @Test
    fun `a step doesn't step straight back`() {
        val steps = PinchSteps()
        steps.start()
        assertEquals(-1, steps.onZoom(1.4f, 3, 2..6))
        // Now at two columns: a small move back doesn't return to three.
        assertEquals(0, steps.onZoom(0.95f, 2, 2..6))
    }

    @Test
    fun `one long pinch can go through several steps`() {
        val steps = PinchSteps()
        steps.start()
        var columns = 2
        repeat(40) { columns += steps.onZoom(0.95f, columns, 2..6) }
        assertEquals(6, columns)
    }

    @Test
    fun `at the ends the grid only gives a little`() {
        val steps = PinchSteps()
        steps.start()
        assertEquals(0, steps.onZoom(3f, 2, 2..6))
        assertEquals(PinchSteps.EDGE_GIVE, steps.zoom, 1e-4f)
        // And turning back responds at once (the zoom wasn't left far out).
        steps.onZoom(0.9f, 2, 2..6)
        assertTrue(steps.zoom < 1f)

        steps.start()
        assertEquals(0, steps.onZoom(0.2f, 6, 2..6))
        assertEquals(1f / PinchSteps.EDGE_GIVE, steps.zoom, 1e-4f)
    }

    @Test
    fun `lifting the fingers past half way takes the step`() {
        val steps = PinchSteps()
        steps.start()
        steps.onZoom(1.2f, 3, 2..6)
        assertEquals(-1, steps.onEnd(3, 2..6))
        assertEquals(1f, steps.zoom, 0f)

        steps.start()
        steps.onZoom(1.05f, 3, 2..6)
        assertEquals(0, steps.onEnd(3, 2..6))

        steps.start()
        steps.onZoom(0.88f, 3, 2..6)
        assertEquals(1, steps.onEnd(3, 2..6))
    }

    @Test
    fun `a pinch during the spring back carries on from the size drawn`() {
        val steps = PinchSteps()
        // Two columns drawn at 0.87 (the spring back after three to two): it goes on from there.
        steps.start(0.87f)
        assertEquals(0.87f, steps.zoom, 0f)
        assertEquals(0, steps.onZoom(1.02f, 2, 2..6))
        assertEquals(0.87f * 1.02f, steps.zoom, 1e-4f)

        // Six columns still drawn at 1.12 (past half way back to five): fingers lifted without
        // moving take no step, and spreading them on takes it.
        steps.start(1.12f)
        assertEquals(0, steps.onEnd(6, 2..6))
        steps.start(1.12f)
        assertEquals(0, steps.onZoom(1.02f, 6, 2..6))
        assertEquals(-1, steps.onEnd(6, 2..6))
    }

    @Test
    fun `a pinch that begins past the give at an end isn't pulled in at once`() {
        val steps = PinchSteps()
        // Six of at most six, drawn at 0.9 (below the 1/1.06 the grid gives): turning back follows the fingers...
        steps.start(0.9f)
        steps.onZoom(1.01f, 6, 2..6)
        assertEquals(0.909f, steps.zoom, 1e-4f)
        // ...and going on out doesn't go further.
        steps.onZoom(0.98f, 6, 2..6)
        assertEquals(0.909f, steps.zoom, 1e-4f)
    }

    @Test
    fun `a nonsense zoom is ignored`() {
        val steps = PinchSteps()
        steps.start()
        assertEquals(0, steps.onZoom(Float.NaN, 3, 2..6))
        assertEquals(0, steps.onZoom(0f, 3, 2..6))
        assertEquals(1f, steps.zoom, 0f)
    }

    // --- keeping the book under the fingers ----------------------------------------------------

    @Test
    fun `a cell grows with its cover and keeps its lines`() {
        // A 133px cell with 6px padding: a 121px cover (181.5 tall) and 40px of text.
        val height = 12f + 181.5f + 40f
        assertEquals(12f + (199.5f - 12f) * 1.5f + 40f, cellHeightAt(199.5f, 133f, height, 6f), 1e-3f)
    }

    @Test
    fun `the book keeps the same point under the fingers`() {
        // A third of the way down the book, under a finger at 600px: a 300px book starts at 500.
        assertEquals(500f, anchoredTop(600f, 1f / 3f, 300f), 1e-3f)
        assertEquals(600f, anchoredTop(600f, -1f, 300f), 1e-3f)
    }

    // --- stored views --------------------------------------------------------------------------

    @Test
    fun `a stored view is read back, an unknown one is the grid`() {
        val view = ListView(ViewMode.LIST, 99.75f)
        assertEquals(view, ListView.restore(view.stored()))
        assertEquals(ListView(), ListView.restore(null))
        assertEquals(ListView(cellDp = 80f), ListView.restore(StoredListView(mode = "mosaic", cellDp = 80f)))
        assertEquals(ListView(ViewMode.LIST), ListView.restore(StoredListView(mode = "list", cellDp = -3f)))
        assertEquals(ListView(), ListView.restore(StoredListView(cellDp = Float.NaN)))
    }

    // --- the list's rows -----------------------------------------------------------------------

    @Test
    fun `a row names each format once, the primary file's first`() {
        val files = listOf(
            BookFile(1, "pdf"),
            BookFile(2, "EPUB", role = "primary"),
            BookFile(3, "epub"),
            BookFile(4, null),
            BookFile(5, "cbz"),
            BookFile(6, "mobi"),
        )
        assertEquals(listOf("epub", "pdf", "cbz"), rowFormats(files))
        assertEquals(emptyList<String>(), rowFormats(emptyList()))
    }

    // --- the quick view ------------------------------------------------------------------------

    private val card = BookCard(
        id = 7,
        title = "The Sign of the Four",
        authors = listOf("Arthur Conan Doyle"),
        seriesName = "Sherlock Holmes",
        seriesIndex = "2",
        readingProgress = 42.0,
        readStatus = ReadStatusInfo("reading"),
        rating = 4,
    )

    private fun quick(card: BookCard = this.card, status: String? = card.readStatus?.status, book: BookDetail? = null) =
        QuickViewUiState(card, cover = null, page = BookDetailUiState(bookId = card.id, preview = card, status = status, book = book))

    @Test
    fun `the quick view continues a book part read`() {
        assertTrue(quick().continues)
        assertFalse(quick(card.copy(readingProgress = 0.0)).continues)
        assertNull(quick(card.copy(readingProgress = 0.0)).progress)
        assertFalse(quick(card.copy(readingProgress = null)).continues)
        assertFalse(quick(card.copy(readingProgress = 100.0)).continues)
        // Marked read with a position short of the end: Read, not Continue.
        assertFalse(quick(status = "read").continues)
    }

    @Test
    fun `the quick view shows the card until the book is in, then the book`() {
        val first = quick()
        assertTrue(first.loading)
        assertEquals("The Sign of the Four", first.title)
        assertEquals(4, first.rating)
        assertEquals(ReadStatus.READING, first.status)

        val book = BookDetail(
            id = 7,
            title = "The Sign of the Four (Sherlock Holmes 2)",
            authors = listOf(AuthorRef(1, "Arthur Conan Doyle")),
            seriesName = "Sherlock Holmes",
            seriesIndex = "2",
            rating = 5,
            description = "<p>Morstan</p>",
        )
        val loaded = quick(book = book)
        assertFalse(loaded.loading)
        assertEquals("The Sign of the Four (Sherlock Holmes 2)", loaded.title)
        assertEquals(5, loaded.rating)
        assertEquals("<p>Morstan</p>", loaded.description)
        assertEquals(listOf("Arthur Conan Doyle"), loaded.authors)
    }

    @Test
    fun `a book with no status is unread`() {
        assertEquals(ReadStatus.UNREAD, quick(status = null).status)
        assertFalse(quick(status = null).copy(loadFailed = true).loading)
    }
}
