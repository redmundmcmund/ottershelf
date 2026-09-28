package io.github.ottershelf.feature.library

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.model.BooksPage
import io.github.ottershelf.core.model.ReadStatusInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookListLoaderTest {

    private fun book(id: Long, status: String?, title: String = "Book $id") =
        BookCard(id, title, readStatus = status?.let { ReadStatusInfo(it) })

    /**
     * A server holding [books]: the books query filters by readStatus (includesAny / excludesAll,
     * a missing status counting as unread) and pages; the author and series routes page in the
     * order given. Every call is recorded.
     */
    private class FakeServer(val books: List<BookCard>) : BookListRemote {
        val queries = mutableListOf<Pair<String, ListQueryBody>>()
        val routeCalls = mutableListOf<String>()

        override suspend fun query(path: String, body: ListQueryBody): BooksPage {
            queries += path to body
            val rule = body.filter?.rules?.singleOrNull()
            val values = (rule?.value as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val matching = books.filter { book ->
                val status = book.readStatus?.status ?: "unread"
                when (rule?.operator) {
                    null -> true
                    "includesAny" -> status in values
                    "excludesAll" -> status !in values
                    else -> error("unexpected ${rule.operator}")
                }
            }
            val from = body.pagination.page * body.pagination.size
            val items = matching.drop(from).take(body.pagination.size)
            return BooksPage(items, matching.size, body.pagination.page, body.pagination.size)
        }

        override suspend fun routeBooks(path: String, page: Int, size: Int, sort: String, order: String): BooksPage {
            routeCalls += "$path page=$page size=$size sort=$sort order=$order"
            val items = books.drop(page * size).take(size)
            return BooksPage(items, books.size, page, size)
        }
    }

    /** Every page, as the pager asks for them, until the total is reached (or a page comes back empty). */
    private suspend fun BookListLoader.all(size: Int): Pair<List<BookCard>, List<Int>> {
        val items = mutableListOf<BookCard>()
        val totals = mutableListOf<Int>()
        var page = 0
        while (true) {
            val (list, total) = load(page++, size)
            items += list
            totals += total
            if (list.isEmpty() || items.size >= total || page > 50) break
        }
        return items to totals
    }

    @Test
    fun oneSegmentIsTheServersPages() = runTest {
        val server = FakeServer((1L..5L).map { book(it, if (it % 2 == 0L) "read" else null) })
        val loader = BookListLoader(server, BookSource.InLibrary(3, "Fiction"), ListSort(SortField.AUTHOR, hideRead = true), query = " dracula ")
        val (items, totals) = loader.all(size = 2)
        assertEquals(listOf(1L, 3L, 5L), items.map { it.id })
        assertEquals(listOf(3, 3), totals)
        val (path, body) = server.queries.first()
        assertEquals("libraries/3/books", path)
        assertEquals("dracula", body.q)
        assertEquals(listOf(QuerySort("author", "asc"), QuerySort("title", "asc")), body.sort)
        assertEquals(2, server.queries.size)
    }

    @Test
    fun readStatusRunsTheSegmentsInTurnWithAnExactTotal() = runTest {
        val server = FakeServer(
            listOf(
                book(1, "read"), book(2, null), book(3, "reading"), book(4, "want_to_read"), book(5, "abandoned"),
                book(6, "unread"), book(7, "reading"), book(8, null), book(9, "rereading"),
            ),
        )
        val loader = BookListLoader(server, BookSource.All("All"), ListSort(SortField.READ_STATUS), query = null)
        val (items, totals) = loader.all(size = 2)
        // Reading and re-reading, then want to read, then unread (a missing status and a hand-set
        // Unread alike), then read, then abandoned. On hold and skimmed have none.
        assertEquals(listOf(3L, 7L, 9L, 4L, 2L, 6L, 8L, 1L, 5L), items.map { it.id })
        // The total is known from the first page (every segment counted at once).
        assertTrue(totals.all { it == 9 })
        // A page never spans two segments: reading 2 + 1, then want to read 1, unread 2 + 1, read 1, abandoned 1.
        val pages = server.queries.drop(5).map { it.second.filter!!.rules.single().let { r -> (r.value as JsonArray).first().jsonPrimitive.content } to it.second.pagination.page }
        assertEquals(listOf("reading" to 1, "on_hold" to 0, "unread" to 0, "unread" to 1, "read" to 0, "abandoned" to 0), pages)
    }

    @Test
    fun emptySegmentsAreSkippedWithoutARequest() = runTest {
        val server = FakeServer(listOf(book(1, "read"), book(2, "read"), book(3, null)))
        val loader = BookListLoader(server, BookSource.InScope(7, "Scope"), ListSort(SortField.READ_STATUS), query = null)
        val (items, totals) = loader.all(size = 60)
        assertEquals(listOf(3L, 1L, 2L), items.map { it.id })
        assertEquals(3, totals.first())
        // Page 0: five counts in parallel (reading, on hold, unread, read, abandoned); the first is
        // empty, so the unread segment is fetched for page 0, then the read segment for page 1.
        assertTrue(server.queries.all { it.first == "smart-scopes/7/books/query" })
        assertEquals(5 + 2, server.queries.size)
    }

    @Test
    fun anEmptyListIsEmpty() = runTest {
        val server = FakeServer(listOf(book(1, "read")))
        val loader = BookListLoader(server, BookSource.All("All"), ListSort(SortField.READ_STATUS, readingOnly = true), query = null)
        assertEquals(emptyList<BookCard>() to 0, loader.load(0, 60))
    }

    @Test
    fun anAuthorsRouteSortsWhenItCan() = runTest {
        val server = FakeServer((1L..3L).map { book(it, null) })
        val loader = BookListLoader(server, BookSource.ByAuthor(5, "Conan Doyle"), ListSort(SortField.ADDED, descending = true), query = null)
        val (items, _) = loader.all(size = 60)
        assertEquals(3, items.size)
        assertEquals(listOf("authors/5/books page=0 size=60 sort=addedAt order=desc"), server.routeCalls)
    }

    @Test
    fun aFilteredSeriesIsLoadedWholeThenFiltered() = runTest {
        val books = (1L..250L).map { book(it, if (it % 10 == 0L) "read" else null) }
        val server = FakeServer(books)
        val loader = BookListLoader(server, BookSource.InSeries(9, "Barsetshire"), ListSort(SortField.SERIES_ORDER, hideRead = true), query = null)
        val (items, total) = loader.load(0, 60)
        assertEquals(225, items.size)
        assertEquals(225, total)
        assertTrue(items.none { it.readStatus?.status == "read" })
        assertEquals(
            listOf(
                "series/9/books page=0 size=100 sort=seriesIndex order=asc",
                "series/9/books page=1 size=100 sort=seriesIndex order=asc",
                "series/9/books page=2 size=100 sort=seriesIndex order=asc",
            ),
            server.routeCalls,
        )
        // Nothing comes after the whole list.
        assertEquals(emptyList<BookCard>() to 225, loader.load(1, 60))
    }

    @Test
    fun readStatusOnAnAuthorsPageKeepsTitlesInOrderWithinAStatus() = runTest {
        val server = FakeServer(
            listOf(book(1, "read", "A"), book(2, null, "B"), book(3, "reading", "C"), book(4, "read", "D"), book(5, "reading", "E")),
        )
        val loader = BookListLoader(server, BookSource.ByAuthor(5, "Conan Doyle"), ListSort(SortField.READ_STATUS), query = null)
        assertEquals(listOf(3L, 5L, 2L, 1L, 4L), loader.load(0, 60).first.map { it.id })
        assertEquals(listOf("authors/5/books page=0 size=100 sort=title order=asc"), server.routeCalls)
        val reversed = BookListLoader(server, BookSource.ByAuthor(5, "Conan Doyle"), ListSort(SortField.READ_STATUS, descending = true), query = null)
        assertEquals(listOf(1L, 4L, 2L, 3L, 5L), reversed.load(0, 60).first.map { it.id })
    }
}
