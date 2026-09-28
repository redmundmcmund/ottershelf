package io.github.ottershelf.feature.seriesnext

import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.download.DownloadedBook
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatusInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesNextLoaderTest {

    private fun card(id: Long, index: String?, status: String? = null, files: List<BookFile> = listOf(BookFile(id * 10, "epub", "primary"))) =
        BookCard(id, "Book $id", authors = listOf("L. Frank Baum"), seriesName = "The Oz Books", seriesIndex = index, readStatus = status?.let { ReadStatusInfo(it) }, hasCover = true, files = files)

    private fun detail(id: Long, index: String?, seriesId: Long? = 9, status: String? = "read", name: String? = "The Oz Books") =
        BookDetail(id, title = "Book $id", seriesId = seriesId, seriesName = name, seriesIndex = index, readStatus = status?.let { ReadStatusInfo(it) })

    /** A server holding [books] in series order, paging as `series/:id/books` does. */
    private class FakeServer(val books: List<BookCard>, val details: Map<Long, BookDetail> = emptyMap(), val seriesName: String? = "The Oz Books") : SeriesNextRemote {
        val pages = mutableListOf<Pair<Int, Int>>()
        val bookCalls = mutableListOf<Long>()

        override suspend fun book(bookId: Long): BookDetail {
            bookCalls += bookId
            return details.getValue(bookId)
        }

        override suspend fun seriesBooks(seriesId: Long, page: Int, size: Int): SeriesBooksPage {
            pages += page to size
            return SeriesBooksPage(books.drop(page * size).take(size), books.size, SeriesInfo(seriesId, seriesName, books.size))
        }

        override fun cover(card: BookCard): Any? = "cover:${card.id}"
    }

    @Test
    fun `the next book with its cover, file and series name`() = runTest {
        val server = FakeServer(listOf(card(1, "1", "read"), card(2, "2", "read"), card(3, "3"), card(4, "4")))
        val state = SeriesNextLoader(server).after(detail(2, "2")) as SeriesNextState.Next
        assertEquals(3L, state.book.bookId)
        assertEquals("3", state.book.seriesIndex)
        assertEquals("The Oz Books", state.book.seriesName)
        assertEquals("cover:3", state.book.cover)
        assertEquals(30L, state.book.file?.id)
        assertEquals(listOf(0 to SeriesNextLoader.PAGE_SIZE), server.pages)
    }

    @Test
    fun `not in a series asks nothing`() = runTest {
        val server = FakeServer(emptyList())
        assertEquals(SeriesNextState.Hidden, SeriesNextLoader(server).after(detail(2, null, seriesId = null)))
        assertTrue(server.pages.isEmpty())
    }

    @Test
    fun `a long series is read page by page`() = runTest {
        val books = (1L..250L).map { card(it, "$it", if (it < 240) "read" else null) }
        val server = FakeServer(books)
        val state = SeriesNextLoader(server).after(detail(239, "239")) as SeriesNextState.Next
        assertEquals(240L, state.book.bookId)
        assertEquals(listOf(0, 1, 2), server.pages.map { it.first })
    }

    @Test
    fun `a book seen on two pages counts once`() = runTest {
        // Something moved while paging: page 1 starts with page 0's last book again.
        val books = (1L..100L).map { card(it, "$it", "read") }
        val server = object : SeriesNextRemote {
            override suspend fun book(bookId: Long) = error("unused")
            override suspend fun seriesBooks(seriesId: Long, page: Int, size: Int) = when (page) {
                0 -> SeriesBooksPage(books, 101)
                else -> SeriesBooksPage(listOf(books.last(), card(101, "101", "read")), 101)
            }
            override fun cover(card: BookCard): Any? = null
        }
        assertEquals(SeriesNextState.AllRead(101, "The Oz Books"), SeriesNextLoader(server).after(detail(50, "50")))
    }

    @Test
    fun `the user's statuses are the ones just fetched`() = runTest {
        // Volume 3 finished on another device since a page on this phone last showed it unread.
        val server = FakeServer(listOf(card(2, "2", "read"), card(3, "3", "read"), card(4, "4")))
        val state = SeriesNextLoader(server).after(detail(2, "2")) as SeriesNextState.Next
        assertEquals(4L, state.book.bookId)
    }

    @Test
    fun `after the timer the status the server just returned counts`() = runTest {
        // A timed session reached the end: the server set the book to read before it answered.
        val server = FakeServer(listOf(card(2, "2", "read"), card(3, "3")), details = mapOf(2L to detail(2, "2", status = "read")))
        assertEquals(3L, (SeriesNextLoader(server).after(2, onlyWhenRead = true) as SeriesNextState.Next).book.bookId)
    }

    @Test
    fun `a book whose files are missing isn't read`() = runTest {
        // Volume 3 has two copies: the one missing from the server's disk loses to the present one...
        val missing = card(3, "3", files = listOf(BookFile(31, "epub", "primary"))).copy(status = "missing")
        val present = card(5, "3", files = listOf(BookFile(51, "epub", "primary"))).copy(status = "present")
        val two = SeriesNextLoader(FakeServer(listOf(card(2, "2"), missing, present))).after(detail(2, "2")) as SeriesNextState.Next
        assertEquals(5L, two.book.bookId)
        assertEquals(51L, two.book.file?.id)
        // ...and as the only copy it is offered with Details only.
        val only = SeriesNextLoader(FakeServer(listOf(card(2, "2"), missing))).after(detail(2, "2")) as SeriesNextState.Next
        assertEquals(3L, only.book.bookId)
        assertNull(only.book.file)
    }

    @Test
    fun `a comic's kept CBZ is what Read opens, as on the book page`() = runTest {
        val files = listOf(BookFile(31, "cbr", "primary"), BookFile(32, "cbz", "alternate"))
        val server = FakeServer(listOf(card(2, "2"), card(3, "3", files = files)))
        val kept = SeriesNextLoader(server, downloaded = { if (it == 3L) DownloadedBook(3, 32, format = "cbz") else null })
        assertEquals(32L, (kept.after(detail(2, "2")) as SeriesNextState.Next).book.file?.id)
        // Nothing kept: the CBR.
        assertEquals(31L, (SeriesNextLoader(server).after(detail(2, "2")) as SeriesNextState.Next).book.file?.id)
        // A kept copy of a file the book no longer has on the server isn't used.
        val gone = SeriesNextLoader(server, downloaded = { DownloadedBook(3, 99, format = "cbz") })
        assertEquals(31L, (gone.after(detail(2, "2")) as SeriesNextState.Next).book.file?.id)
        // Nor for a format that can be kept itself (it would be the one downloaded).
        val epub = FakeServer(listOf(card(2, "2"), card(3, "3", files = listOf(BookFile(31, "epub", "primary"), BookFile(32, "cbz", "alternate")))))
        val other = SeriesNextLoader(epub, downloaded = { DownloadedBook(3, 32, format = "cbz") })
        assertEquals(31L, (other.after(detail(2, "2")) as SeriesNextState.Next).book.file?.id)
    }

    @Test
    fun `no file a reader here opens leaves Details only`() = runTest {
        val server = FakeServer(listOf(card(2, "2"), card(3, "3", files = listOf(BookFile(31, "m4b", "primary")))))
        val state = SeriesNextLoader(server).after(detail(2, "2")) as SeriesNextState.Next
        assertEquals(3L, state.book.bookId)
        assertNull(state.book.file)
    }

    @Test
    fun `the primary file is the one Read opens`() = runTest {
        val files = listOf(BookFile(31, "pdf", "alternate"), BookFile(32, "epub", "primary"))
        val server = FakeServer(listOf(card(2, "2"), card(3, "3", files = files)))
        val state = SeriesNextLoader(server).after(detail(2, "2")) as SeriesNextState.Next
        assertEquals(32L, state.book.file?.id)
    }

    @Test
    fun `the whole series read`() = runTest {
        val server = FakeServer((1L..6L).map { card(it, "$it", "read") })
        assertEquals(SeriesNextState.AllRead(6, "The Oz Books"), SeriesNextLoader(server).after(detail(6, "6")))
    }

    @Test
    fun `the series' own name when the book lacks one`() = runTest {
        val server = FakeServer((1L..3L).map { card(it, "$it", "read") }, seriesName = "Sherlock Holmes")
        assertEquals(SeriesNextState.AllRead(3, "Sherlock Holmes"), SeriesNextLoader(server).after(detail(3, "3", name = null)))
    }

    @Test
    fun `nothing after it`() = runTest {
        val server = FakeServer(listOf(card(1, "1"), card(2, "2")))
        assertEquals(SeriesNextState.Hidden, SeriesNextLoader(server).after(detail(2, "2")))
    }

    @Test
    fun `after the timer only once the book is read`() = runTest {
        val books = listOf(card(2, "2"), card(3, "3"))
        val reading = FakeServer(books, details = mapOf(2L to detail(2, "2", status = "reading")))
        assertEquals(SeriesNextState.Hidden, SeriesNextLoader(reading).after(2, onlyWhenRead = true))
        assertTrue(reading.pages.isEmpty())

        val read = FakeServer(books, details = mapOf(2L to detail(2, "2", status = "read")))
        assertEquals(3L, (SeriesNextLoader(read).after(2, onlyWhenRead = true) as SeriesNextState.Next).book.bookId)
    }

    @Test
    fun `the reader asks whatever the status`() = runTest {
        val server = FakeServer(listOf(card(2, "2"), card(3, "3")), details = mapOf(2L to detail(2, "2", status = "reading")))
        assertEquals(3L, (SeriesNextLoader(server).after(2, onlyWhenRead = false) as SeriesNextState.Next).book.bookId)
        assertEquals(listOf(2L), server.bookCalls)
    }
}
