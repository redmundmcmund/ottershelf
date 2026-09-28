package io.github.ottershelf.feature.seriesnext

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import io.github.ottershelf.core.download.DownloadedBook
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.ui.components.coverModel

/** What the next-in-series card, row or line shows. */
sealed interface SeriesNextState {
    /** Nothing: not a series book, not finished, nothing after it, offline, or still looking. */
    data object Hidden : SeriesNextState

    data class Next(val book: NextBook) : SeriesNextState

    /** The user has read all [count] of [seriesName]'s volumes in the library. */
    data class AllRead(val count: Int, val seriesName: String?) : SeriesNextState
}

/**
 * The book to offer: [card] as the series list has it (title, authors, number in this series, the user's
 * status), [seriesName], its [cover] (a thumbnail URL, or null for the generated one) and [file], the
 * file Read opens (a comic's kept CBZ rather than its CBR, as elsewhere; null when no reader here
 * opens any, or its files are missing from the server: only Details then).
 */
data class NextBook(
    val card: BookCard,
    val seriesName: String?,
    val cover: Any?,
    val file: BookFile?,
) {
    val bookId: Long get() = card.id
    val title: String? get() = card.title?.takeIf { it.isNotBlank() }
    val seriesIndex: String? get() = card.seriesIndex?.takeIf { it.isNotBlank() }
}

/** `GET series/:id/books` (BooksPage with the series' `seriesInfo`), the fields read here. */
@Serializable
data class SeriesBooksPage(
    val items: List<BookCard> = emptyList(),
    val total: Int = 0,
    val seriesInfo: SeriesInfo? = null,
)

@Serializable
data class SeriesInfo(val id: Long? = null, val name: String? = null, val bookCount: Int = 0)

/** The server calls the next-book lookup makes (a fake in the tests). */
interface SeriesNextRemote {
    suspend fun book(bookId: Long): BookDetail

    /** One page of the series' books in series order (the server's `seriesIndex` sort). */
    suspend fun seriesBooks(seriesId: Long, page: Int, size: Int): SeriesBooksPage

    /** The cover model for [card] (a versioned thumbnail URL), or null without one. */
    fun cover(card: BookCard): Any?
}

class ApiSeriesNextRemote(private val api: Api) : SeriesNextRemote {
    override suspend fun book(bookId: Long): BookDetail = api.book(bookId)

    override suspend fun seriesBooks(seriesId: Long, page: Int, size: Int): SeriesBooksPage =
        api.send("GET", "series/$seriesId/books?page=$page&size=$size&sort=seriesIndex&order=asc", null) {
            ApiJson.decodeFromString(SeriesBooksPage.serializer(), it)
        }

    override fun cover(card: BookCard): Any? = runCatching { api.coverModel(card) }.getOrNull()
}

/**
 * Works out what follows a book in its series ([SeriesNext.pick]) from the whole series as the
 * library holds it: `GET series/:id/books` in series order, 100 a page (the route's most), at most
 * [MAX_PAGES] pages. The user's statuses are the ones just fetched: fresher than anything this session
 * remembers (ReadingChanges' overrides are only what a screen last saw, and a timed session or
 * another device changes a status without touching them). [downloaded] gives the copy of a book
 * kept on this phone (Downloads.get, read on [io]). Throws the Api's exceptions.
 */
class SeriesNextLoader(
    private val remote: SeriesNextRemote,
    private val downloaded: (bookId: Long) -> DownloadedBook? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** After [book], which the user has finished or read to the end. */
    suspend fun after(book: BookDetail): SeriesNextState {
        val seriesId = book.seriesId ?: return SeriesNextState.Hidden
        val (cards, info) = seriesBooks(seriesId)
        val members = cards.map { card ->
            SeriesMember(
                bookId = card.id,
                index = card.seriesIndex,
                status = card.readStatus?.status,
                readable = serverFile(card) != null,
            )
        }
        val seriesName = book.seriesName?.takeIf { it.isNotBlank() } ?: info?.name?.takeIf { it.isNotBlank() }
        return when (val pick = SeriesNext.pick(book.id, book.seriesIndex, members)) {
            is SeriesPick.Next -> {
                val card = cards.first { it.id == pick.bookId }
                SeriesNextState.Next(NextBook(card, seriesName, remote.cover(card), serverFile(card)?.let { readFile(card, it) }))
            }
            is SeriesPick.AllRead -> SeriesNextState.AllRead(pick.count, seriesName)
            SeriesPick.Nothing -> SeriesNextState.Hidden
        }
    }

    /**
     * After the book [bookId], looked up first. [onlyWhenRead]: only once the user's status says the user has
     * read it (the timer's result: a session that reached the end sets it on the server, before it
     * answers, so the page fetched here already says so).
     */
    suspend fun after(bookId: Long, onlyWhenRead: Boolean): SeriesNextState {
        val book = remote.book(bookId)
        if (onlyWhenRead && !SeriesNext.isDone(book.readStatus?.status)) return SeriesNextState.Hidden
        return after(book)
    }

    /**
     * The file a reader here opens ([BookFormats.pickFile]), or null: none opens, or the book's
     * files are missing from the server's disk (the server's own next-book query leaves such books
     * out; one is still offered, with Details only, when it's the volume's only copy).
     */
    private fun serverFile(card: BookCard): BookFile? =
        if (card.status == null || card.status == PRESENT) BookFormats.pickFile(card.files) else null

    /**
     * What Read opens for [file]: the CBZ kept on this phone instead of a CBR or CB7 comic
     * (BookFormats.readsKeptCopyInstead), as the book page's Read and the Dashboard's play buttons
     * do, so the user's place stays in one file; only while that file is still the book's on the server.
     */
    private suspend fun readFile(card: BookCard, file: BookFile): BookFile {
        if (BookFormats.canKeepOffline(file.format)) return file
        val copy = withContext(io) { runCatching { downloaded(card.id) }.getOrNull() } ?: return file
        if (copy.fileId == file.id || !BookFormats.readsKeptCopyInstead(file.format, copy.format)) return file
        return card.files.firstOrNull { it.id == copy.fileId } ?: file
    }

    /** Every book of the series (in its order), and the series' own details from the first page. */
    private suspend fun seriesBooks(seriesId: Long): Pair<List<BookCard>, SeriesInfo?> {
        val books = mutableListOf<BookCard>()
        val seen = HashSet<Long>()
        var info: SeriesInfo? = null
        for (page in 0 until MAX_PAGES) {
            val result = remote.seriesBooks(seriesId, page, PAGE_SIZE)
            if (page == 0) info = result.seriesInfo
            // A book that moved between pages while paging shows up twice: keep the first.
            result.items.filterTo(books) { seen.add(it.id) }
            if (result.items.size < PAGE_SIZE || books.size >= result.total) break
        }
        return books to info
    }

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 20
        private const val PRESENT = "present"
    }
}
