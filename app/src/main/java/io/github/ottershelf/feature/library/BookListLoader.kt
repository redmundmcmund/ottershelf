package io.github.ottershelf.feature.library

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.model.BooksPage
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson

/** The server calls a book grid makes (a fake in `BookListLoaderTest`). */
interface BookListRemote {
    /** `POST <path>` with a books query: `books/query`, `libraries/:id/books`, `smart-scopes/:id/books/query`, `collections/:id/books/query`. */
    suspend fun query(path: String, body: ListQueryBody): BooksPage

    /** `GET authors/:id/books` or `series/:id/books` with the route's own `sort` and `order` (at most 100 a page). */
    suspend fun routeBooks(path: String, page: Int, size: Int, sort: String, order: String): BooksPage
}

class ApiBookListRemote(private val api: Api) : BookListRemote {
    override suspend fun query(path: String, body: ListQueryBody): BooksPage {
        val json = ApiJson.encodeToJsonElement(ListQueryBody.serializer(), body)
        return api.send("POST", path, json) { ApiJson.decodeFromString(BooksPage.serializer(), it) }
    }

    override suspend fun routeBooks(path: String, page: Int, size: Int, sort: String, order: String): BooksPage =
        api.send("GET", "$path?page=$page&size=$size&sort=$sort&order=$order", null) { ApiJson.decodeFromString(BooksPage.serializer(), it) }
}

/**
 * Loads one list's pages for [Pager] under a [ListSort]. Pages are asked for in order (0, 1, 2...;
 * a failed one again), and page 0 starts over, which is what the pager does.
 *
 * - **Books query, one segment** (every sort but read status): the server's pages as they come.
 * - **Books query, read status** ([querySegments]): the segments one after the other. Page 0 also
 *   asks every other segment for its count (one book each, in parallel), so the total is exact from
 *   the start and empty segments are skipped without a request. A page never spans two segments:
 *   the last page of one can be short.
 * - **An author's or a series' books**: the route's own sort when it has one and nothing is
 *   filtered; otherwise the whole list (100 a request, at most [WHOLE_LIST_LIMIT]) is fetched on
 *   page 0 in the route's order, filtered, and for read status put in the status order (stable, so
 *   titles or series order stay in order within a status).
 */
class BookListLoader(
    private val remote: BookListRemote,
    private val source: BookSource,
    private val sort: ListSort,
    private val query: String?,
) {
    private val kind = ListKind.of(source)
    private val segments = if (kind == ListKind.QUERY) querySegments(sort) else emptyList()

    /** Where each page of this listing starts: (segment, that segment's page). Page 0 is always (0, 0). */
    private var starts: List<Pair<Int, Int>> = listOf(0 to 0)
    private var totals: List<Int> = emptyList()

    /** How many an author's or series' list loaded whole came to. */
    private var wholeTotal: Int? = null

    suspend fun load(page: Int, size: Int): Pair<List<BookCard>, Int> = when (kind) {
        ListKind.QUERY -> loadQuery(page, size)
        else -> loadRoute(page, size)
    }

    // --- books query -------------------------------------------------------------------------

    private suspend fun loadQuery(page: Int, size: Int): Pair<List<BookCard>, Int> {
        if (segments.isEmpty()) return emptyList<BookCard>() to 0
        val path = queryPath(source)
        if (page == 0) {
            // The first segment's first page, and every other segment's count, together.
            val results = coroutineScope {
                segments.mapIndexed { index, segment ->
                    async { remote.query(path, body(segment, 0, if (index == 0) size else 1)) }
                }.awaitAll()
            }
            val counts = results.map { it.total }
            val first = results[0]
            if (first.items.isNotEmpty() || segments.size == 1) {
                totals = counts
                starts = listOf(0 to 0, next(0, 0, size, counts))
                return first.items to counts.sum()
            }
            // The first segment is empty: carry on from the next one that isn't.
            totals = counts
            starts = listOf(next(0, 0, size, counts))
            return fetchFrom(0, starts[0], size, path)
        }
        val start = starts.getOrNull(page) ?: return emptyList<BookCard>() to totals.sum()
        return fetchFrom(page, start, size, path)
    }

    /** Page [page] of the listing, starting at segment page [start]; empty segments are skipped. */
    private suspend fun fetchFrom(page: Int, start: Pair<Int, Int>, size: Int, path: String): Pair<List<BookCard>, Int> {
        var (segment, segmentPage) = start
        while (segment < segments.size) {
            if (totals.getOrElse(segment) { 1 } <= 0) {
                segment++; segmentPage = 0; continue
            }
            val result = remote.query(path, body(segments[segment], segmentPage, size))
            val counts = totals.toMutableList().also { if (segment < it.size) it[segment] = result.total }
            if (result.items.isEmpty()) {
                // The segment shrank since it was counted: on to the next.
                if (segment < counts.size) counts[segment] = segmentPage * size
                totals = counts
                segment++; segmentPage = 0; continue
            }
            totals = counts
            starts = starts.take(page) + listOf(segment to segmentPage, next(segment, segmentPage, size, counts))
            return result.items to counts.sum()
        }
        return emptyList<BookCard>() to totals.sum()
    }

    /** Where the page after (segment, segmentPage) starts. */
    private fun next(segment: Int, segmentPage: Int, size: Int, counts: List<Int>): Pair<Int, Int> =
        if ((segmentPage + 1) * size < counts.getOrElse(segment) { 0 }) segment to segmentPage + 1 else segment + 1 to 0

    private fun body(segment: QuerySegment, page: Int, size: Int) = ListQueryBody(
        sort = segment.sort,
        pagination = QueryPage(page, size),
        filter = segment.filter,
        q = query?.trim()?.takeIf { it.isNotEmpty() },
    )

    // --- an author's or a series' books ----------------------------------------------------------

    private suspend fun loadRoute(page: Int, size: Int): Pair<List<BookCard>, Int> {
        val path = routePath(source)
        val direct = routeSort(kind, sort.field)
        val order = if (sort.descending) "desc" else "asc"
        if (direct != null && !sort.filtered) {
            return remote.routeBooks(path, page, size, direct, order).let { it.items to it.total }
        }
        // Loaded whole on the first page; nothing comes after it.
        if (page > 0) return emptyList<BookCard>() to (wholeTotal ?: 0)
        val (baseSort, baseOrder) = if (direct != null) direct to order else routeSort(kind, kind.default.field)!! to "asc"
        val all = ArrayList<BookCard>()
        var next = 0
        while (all.size < WHOLE_LIST_LIMIT) {
            val result = remote.routeBooks(path, next, ROUTE_PAGE, baseSort, baseOrder)
            all += result.items
            if (result.items.isEmpty() || all.size >= result.total) break
            next++
        }
        val shown = all.distinctBy { it.id }.filter { sort.shows(it.readStatus?.status) }
        val ordered = if (sort.field != SortField.READ_STATUS) shown else {
            val rank = { book: BookCard -> statusRank(ListSort.statusOf(book.readStatus?.status)) }
            if (sort.descending) shown.sortedByDescending(rank) else shown.sortedBy(rank)
        }
        wholeTotal = ordered.size
        return ordered to ordered.size
    }

    companion object {
        /** The author and series routes take at most 100 a page. */
        const val ROUTE_PAGE = 100

        /** An author's or series' list loaded whole for a sort or filter the route can't do. */
        const val WHOLE_LIST_LIMIT = 2000

        fun queryPath(source: BookSource): String = when (source) {
            is BookSource.InLibrary -> "libraries/${source.id}/books"
            is BookSource.InScope -> "smart-scopes/${source.id}/books/query"
            is BookSource.InCollection -> "collections/${source.id}/books/query"
            else -> "books/query"
        }

        fun routePath(source: BookSource): String = when (source) {
            is BookSource.ByAuthor -> "authors/${source.id}/books"
            is BookSource.InSeries -> "series/${source.id}/books"
            else -> throw IllegalArgumentException("${source.key} has no route of its own")
        }
    }
}
