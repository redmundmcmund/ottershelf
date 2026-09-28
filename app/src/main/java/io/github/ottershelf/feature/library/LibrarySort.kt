package io.github.ottershelf.feature.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.model.ReadStatus

/**
 * How a book grid is sorted and filtered: the toolbar's sort sheet, remembered per list on the
 * device ([LibrarySortPrefs]). Pure, so the rules are unit-tested (`LibrarySortTest`).
 *
 * The server does the work wherever it can (grids page 60 at a time, so sorting on the phone would
 * only sort what happens to be loaded):
 * - All books, a library, a smart scope, a collection: the books query (`POST .../books/query`,
 *   packages/types query.ts) takes every [SortField.Query] field and the read status filter.
 * - An author's or a series' books come from their own routes (`authors/:id/books`,
 *   `series/:id/books`), which take only a few sorts and no filter. Their other choices load the
 *   whole list (it's one author's or one series' books) and sort or filter it on the phone, which
 *   is correct because nothing is missing (see [BookListLoader]).
 */
enum class SortField(val id: String, val naturalDescending: Boolean = false) {
    TITLE("title"),
    AUTHOR("author"),
    SERIES("series"),
    /** In series order (a series' own page). */
    SERIES_ORDER("seriesIndex"),
    READ_STATUS("readStatus"),
    ADDED("addedAt", naturalDescending = true),
    LAST_READ("lastReadAt", naturalDescending = true),
    PUBLISHED("publishedDate", naturalDescending = true),
    /** An author's page: the author route sorts by year only. */
    PUBLISHED_YEAR("publishedYear", naturalDescending = true),
    ;

    companion object {
        fun of(id: String?): SortField? = entries.firstOrNull { it.id == id }
    }
}

/** Which kind of list a sort belongs to: what it offers, its default, and where it's remembered. */
enum class ListKind(val fields: List<SortField>, val default: ListSort) {
    /** All books, a library, scope or collection: the books query. */
    QUERY(
        listOf(SortField.TITLE, SortField.AUTHOR, SortField.SERIES, SortField.READ_STATUS, SortField.ADDED, SortField.LAST_READ, SortField.PUBLISHED),
        ListSort(SortField.TITLE),
    ),
    AUTHOR(
        listOf(SortField.TITLE, SortField.READ_STATUS, SortField.ADDED, SortField.PUBLISHED_YEAR),
        ListSort(SortField.TITLE),
    ),
    SERIES(
        listOf(SortField.SERIES_ORDER, SortField.TITLE, SortField.READ_STATUS, SortField.ADDED),
        ListSort(SortField.SERIES_ORDER),
    ),
    ;

    companion object {
        fun of(source: BookSource): ListKind = when (source) {
            is BookSource.ByAuthor -> AUTHOR
            is BookSource.InSeries -> SERIES
            else -> QUERY
        }

        /**
         * Where a list's choice is kept: each drawer list (All books, each library, scope and
         * collection) has its own; every author's page shares one, and so does every series' page.
         */
        fun prefsKey(source: BookSource): String = when (of(source)) {
            AUTHOR -> "author"
            SERIES -> "series"
            QUERY -> source.key
        }
    }
}

/**
 * A list's sort and filters.
 *
 * @property hideRead leaves out books marked Read.
 * @property hideUnread leaves out books marked Unread, and books with no status (the server reads
 *   a missing status as unread, and so does its filter).
 * @property readingOnly only books being read or re-read.
 */
data class ListSort(
    val field: SortField = SortField.TITLE,
    val descending: Boolean = false,
    val hideRead: Boolean = false,
    val hideUnread: Boolean = false,
    val readingOnly: Boolean = false,
) {
    val filtered: Boolean get() = hideRead || hideUnread || readingOnly

    /** Picking a field starts in its natural direction (dates newest first, names A to Z). */
    fun withField(next: SortField): ListSort = if (next == field) this else copy(field = next, descending = next.naturalDescending)

    fun withoutFilters(): ListSort = copy(hideRead = false, hideUnread = false, readingOnly = false)

    /** The statuses a book may have to be shown, in [ReadStatus] order. */
    val shownStatuses: List<ReadStatus>
        get() = ReadStatus.entries.filter { status ->
            when {
                readingOnly && status !in READING -> false
                hideRead && status == ReadStatus.READ -> false
                hideUnread && status == ReadStatus.UNREAD -> false
                else -> true
            }
        }

    /** Whether a book with [status] (the server's id; null for none, which is unread) is shown. */
    fun shows(status: String?): Boolean = statusOf(status) in shownStatuses

    /** The read status filter for the books query, or null when every status shows. */
    fun statusRule(): QueryRule? = when {
        !filtered -> null
        readingOnly -> QueryRule.includesAny("readStatus", shownStatuses.map { it.value })
        else -> QueryRule.excludesAll("readStatus", hiddenStatuses().map { it.value })
    }

    private fun hiddenStatuses(): List<ReadStatus> = ReadStatus.entries - shownStatuses.toSet()

    /** A stored copy (the field by its server id, so a later app can add fields). */
    fun stored() = StoredListSort(field.id, descending, hideRead, hideUnread, readingOnly)

    companion object {
        val READING = setOf(ReadStatus.READING, ReadStatus.REREADING)

        /** A missing or unknown status is unread, as on the server. */
        fun statusOf(value: String?): ReadStatus = ReadStatus.of(value) ?: ReadStatus.UNREAD

        /** A stored sort, if it still fits [kind] (a field this kind doesn't offer falls back to its default). */
        fun restore(stored: StoredListSort?, kind: ListKind): ListSort {
            val field = SortField.of(stored?.field)?.takeIf { it in kind.fields } ?: return kind.default
            return ListSort(field, stored!!.descending, stored.hideRead, stored.hideUnread, stored.readingOnly)
        }
    }
}

@Serializable
data class StoredListSort(
    val field: String,
    val descending: Boolean = false,
    val hideRead: Boolean = false,
    val hideUnread: Boolean = false,
    val readingOnly: Boolean = false,
)

// --- read status order ---------------------------------------------------------------------------

/**
 * "Read status" in a sensible order: what's being read first, then what's set aside or waiting,
 * then the untouched, then the finished, then the dropped. Descending is the reverse.
 *
 * The server's `readStatus` sort orders by the status id's spelling (abandoned, on_hold, read,
 * reading, ...), which means nothing to a reader. So the list is fetched as consecutive segments,
 * each a books query filtered to some statuses, one after the other. Within a segment the server's
 * own `readStatus` sort still separates its statuses, because each segment's statuses are in
 * alphabetical order already (reading < rereading, on_hold < want_to_read, read < skimmed); then
 * titles A to Z.
 */
internal val STATUS_SEGMENTS: List<List<ReadStatus>> = listOf(
    listOf(ReadStatus.READING, ReadStatus.REREADING),
    listOf(ReadStatus.ON_HOLD, ReadStatus.WANT_TO_READ),
    listOf(ReadStatus.UNREAD),
    listOf(ReadStatus.READ, ReadStatus.SKIMMED),
    listOf(ReadStatus.ABANDONED),
)

/** A status's place in the read status order (the phone-side sort of an author's or series' books). */
internal fun statusRank(status: ReadStatus): Int = STATUS_SEGMENTS.flatten().indexOf(status)

// --- the books query -----------------------------------------------------------------------------

/** One books query of a list: a sorted, filtered stretch. A read status sort has several in a row. */
data class QuerySegment(val sort: List<QuerySort>, val filter: QueryGroup?)

/** The books query of [sort] as one or more segments to page through in turn (see [STATUS_SEGMENTS]). */
fun querySegments(sort: ListSort): List<QuerySegment> {
    val dir = if (sort.descending) "desc" else "asc"
    val byTitle = QuerySort("title", "asc")
    if (sort.field == SortField.READ_STATUS) {
        val shown = sort.shownStatuses.toSet()
        val segments = if (sort.descending) STATUS_SEGMENTS.reversed() else STATUS_SEGMENTS
        return segments.mapNotNull { segment ->
            val statuses = segment.filter { it in shown }
            if (statuses.isEmpty()) null
            else QuerySegment(
                sort = listOf(QuerySort("readStatus", dir), byTitle),
                filter = QueryGroup(rules = listOf(QueryRule.includesAny("readStatus", statuses.map { it.value }))),
            )
        }
    }
    val order = when (sort.field) {
        SortField.TITLE -> listOf(QuerySort("title", dir))
        // Books without a series last either way (the server puts nulls last), each series in order.
        SortField.SERIES -> listOf(QuerySort("series", dir), QuerySort("seriesIndex", "asc"), byTitle)
        else -> listOf(QuerySort(sort.field.id, dir), byTitle)
    }
    return listOf(QuerySegment(order, sort.statusRule()?.let { QueryGroup(rules = listOf(it)) }))
}

/** A route's own sort for an author's or a series' books (`sort=` / `order=`), or null if it has none for [field]. */
internal fun routeSort(kind: ListKind, field: SortField): String? = when (kind) {
    ListKind.AUTHOR -> field.id.takeIf { field in setOf(SortField.TITLE, SortField.ADDED, SortField.PUBLISHED_YEAR) }
    ListKind.SERIES -> field.id.takeIf { field in setOf(SortField.SERIES_ORDER, SortField.TITLE, SortField.ADDED) }
    ListKind.QUERY -> null
}

// --- request bodies (packages/types query.ts, the server's bookQuerySchema) -----------------------

/** A rule; [value] is a list of status ids here. */
@Serializable
data class QueryRule(
    val type: String = "rule",
    val field: String,
    val operator: String,
    val value: JsonElement? = null,
) {
    companion object {
        fun includesAny(field: String, values: List<String>) = QueryRule(field = field, operator = "includesAny", value = JsonArray(values.map(::JsonPrimitive)))

        fun excludesAll(field: String, values: List<String>) = QueryRule(field = field, operator = "excludesAll", value = JsonArray(values.map(::JsonPrimitive)))
    }
}

@Serializable
data class QueryGroup(val type: String = "group", val join: String = "AND", val rules: List<QueryRule>)

@Serializable
data class QuerySort(val field: String, val dir: String)

@Serializable
data class QueryPage(val page: Int, val size: Int)

/** Exactly the fields of the server's bookQuerySchema this list sends; null ones are left out. */
@Serializable
data class ListQueryBody(
    val sort: List<QuerySort>,
    val pagination: QueryPage,
    val filter: QueryGroup? = null,
    val q: String? = null,
)
