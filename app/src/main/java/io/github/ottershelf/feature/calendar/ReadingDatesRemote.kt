package io.github.ottershelf.feature.calendar

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.core.tracking.Patch
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.core.tracking.TrackingRepository
import io.github.ottershelf.feature.achievements.model.QueryGroup
import io.github.ottershelf.feature.achievements.model.QueryPage
import io.github.ottershelf.feature.achievements.model.QueryRule
import io.github.ottershelf.feature.achievements.model.QuerySort
import java.time.LocalDate

/** A book as the picker and the dates sheet show it. [status]: the user's read status id (null: none). */
@Immutable
data class PickerBook(
    val id: Long,
    val title: String?,
    val authors: List<String> = emptyList(),
    val cover: Any? = null,
    /** Lower case, primary first. */
    val formats: List<String> = emptyList(),
    val status: String? = null,
)

/** A `books/query` item: the BookCard fields the picker shows (packages/types book.ts). */
@Serializable
data class PickerCard(
    val id: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val hasCover: Boolean = false,
    val addedAt: String? = null,
    val updatedAt: String? = null,
    val readStatus: ReadStatusInfo? = null,
    val files: List<BookFile> = emptyList(),
) {
    /** Its files' formats, primary first, each once. */
    val formats: List<String>
        get() = files.sortedByDescending { it.role == "primary" }.mapNotNull { it.format?.lowercase() }.distinct()
}

@Serializable
data class PickerPage(val items: List<PickerCard> = emptyList(), val total: Int = 0)

/**
 * The picker's `POST books/query` body: exactly the bookQuerySchema fields it uses (the server's
 * zod schema; null ones are left out by ApiJson).
 */
@Serializable
data class PickerQuery(
    val filter: QueryGroup? = null,
    val q: String? = null,
    val sort: List<QuerySort>,
    val pagination: QueryPage,
)

object PickerQueries {
    /** The results a search shows (the best first). */
    const val PAGE_SIZE = 30

    /** The server's limit on `q`. */
    private const val MAX_Q = 200

    /** Before the user types: what the user is reading now, most recently read first (the user's likeliest finish). */
    fun readingNow() = PickerQuery(
        filter = QueryGroup(
            rules = listOf(
                QueryRule.includesAny("readStatus", listOf(ReadStatus.READING.value, ReadStatus.REREADING.value, ReadStatus.ON_HOLD.value)),
            ),
        ),
        sort = listOf(QuerySort("lastReadAt", "desc")),
        pagination = QueryPage(0, PAGE_SIZE),
    )

    /** The library's quick search (`q`: title, author, series), best match first. */
    fun search(text: String) = PickerQuery(
        q = text.trim().take(MAX_Q),
        sort = listOf(QuerySort("relevance", "desc")),
        pagination = QueryPage(0, PAGE_SIZE),
    )
}

/**
 * What the picker and the dates sheet read and write, behind an interface for the tests. Writes
 * go through the tracker ([TrackingRepository]: the book page's past-read path, which announces
 * each change to the calendars, History, the Dashboard and an open book page).
 */
interface ReadingDatesRemote {
    suspend fun readingNow(): List<PickerBook>
    suspend fun search(text: String): List<PickerBook>
    suspend fun book(bookId: Long): BookDetail
    suspend fun attempts(bookId: Long): List<ReadingAttempt>
    suspend fun create(bookId: Long, startedOn: LocalDate?, endedOn: LocalDate, outcome: String): ReadingAttempt
    suspend fun update(bookId: Long, attemptId: Long, startedOn: Patch<LocalDate>, endedOn: Patch<LocalDate>, outcome: Patch<String>): ReadingAttempt
    suspend fun setStatus(bookId: Long, status: String)

    /**
     * The user's status for [bookId] is now [status] (the server rebuilt it after a reading changed): the
     * library's grids, Downloads and the picker show it over what they loaded
     * (`ReadingChanges.statusOverrides`), as they do after the tracker's status writes.
     */
    fun statusChanged(bookId: Long, status: String)
}

/**
 * [ReadingDatesRemote] over the app's [Api] (the picker's `books/query`, `GET books/:id`) and the
 * tracker ([tracking]: readings, and every write). [query], [bookOf] and [attemptsOf] are the
 * reads, replaceable in tests; [overrideStatus] is `ReadingChanges.overrideStatus`; [of] builds
 * the real one.
 */
class ApiReadingDatesRemote(
    private val tracking: TrackingRepository,
    private val query: suspend (PickerQuery) -> List<PickerBook>,
    private val bookOf: suspend (Long) -> BookDetail,
    private val overrideStatus: (Long, String) -> Unit,
    private val attemptsOf: suspend (Long) -> List<ReadingAttempt> = { tracking.attempts(it).items },
) : ReadingDatesRemote {

    override suspend fun readingNow(): List<PickerBook> = query(PickerQueries.readingNow())

    override suspend fun search(text: String): List<PickerBook> = query(PickerQueries.search(text))

    override suspend fun book(bookId: Long): BookDetail = bookOf(bookId)

    override suspend fun attempts(bookId: Long): List<ReadingAttempt> = attemptsOf(bookId)

    override suspend fun create(bookId: Long, startedOn: LocalDate?, endedOn: LocalDate, outcome: String): ReadingAttempt =
        tracking.createPastRead(bookId, startedOn, endedOn, outcome)

    override suspend fun update(bookId: Long, attemptId: Long, startedOn: Patch<LocalDate>, endedOn: Patch<LocalDate>, outcome: Patch<String>): ReadingAttempt =
        tracking.updateAttempt(bookId, attemptId, startedOn, endedOn, outcome)

    override suspend fun setStatus(bookId: Long, status: String) {
        tracking.setStatus(bookId, status)
    }

    override fun statusChanged(bookId: Long, status: String) = overrideStatus(bookId, status)

    companion object {
        /** The real one. [readingChanges]: the user's statuses as this device last saw them, shown over the server's. */
        fun of(api: Api, tracking: TrackingRepository, readingChanges: ReadingChanges) = ApiReadingDatesRemote(
            tracking = tracking,
            query = { body ->
                api.send("POST", "books/query", ApiJson.encodeToJsonElement(PickerQuery.serializer(), body)) {
                    ApiJson.decodeFromString(PickerPage.serializer(), it)
                }.items.map { card ->
                    PickerBook(
                        id = card.id,
                        title = card.title,
                        authors = card.authors,
                        cover = if (card.hasCover) runCatching { api.thumbnailUrl(card.id, card.updatedAt ?: card.addedAt) }.getOrNull() else null,
                        formats = card.formats,
                        status = readingChanges.statusOf(card.id, card.readStatus?.status),
                    )
                }
            },
            bookOf = api::book,
            overrideStatus = readingChanges::overrideStatus,
        )
    }
}
