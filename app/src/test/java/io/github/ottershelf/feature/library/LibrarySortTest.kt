package io.github.ottershelf.feature.library

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.network.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySortTest {

    private fun QueryRule.values() = (value as JsonArray).map { it.jsonPrimitive.content }

    @Test
    fun defaultsAndWhereEachListIsKept() {
        assertEquals(ListSort(SortField.TITLE), ListKind.QUERY.default)
        assertEquals(ListSort(SortField.SERIES_ORDER), ListKind.SERIES.default)
        assertEquals("all", ListKind.prefsKey(BookSource.All("")))
        assertEquals("library:3", ListKind.prefsKey(BookSource.InLibrary(3, "")))
        assertEquals("scope:7", ListKind.prefsKey(BookSource.InScope(7, "")))
        assertEquals("collection:2", ListKind.prefsKey(BookSource.InCollection(2, "")))
        // Every author's page shares one choice, and so does every series' page.
        assertEquals("author", ListKind.prefsKey(BookSource.ByAuthor(5, "")))
        assertEquals("series", ListKind.prefsKey(BookSource.InSeries(9, "")))
    }

    @Test
    fun pickingAFieldStartsInItsNaturalDirection() {
        val sort = ListSort(SortField.TITLE)
        assertTrue(sort.withField(SortField.ADDED).descending)
        assertTrue(sort.withField(SortField.LAST_READ).descending)
        assertFalse(sort.withField(SortField.AUTHOR).descending)
        // The same field keeps the chosen direction.
        val titleDesc = sort.copy(descending = true)
        assertEquals(titleDesc, titleDesc.withField(SortField.TITLE))
        // Filters survive a field change.
        assertTrue(sort.copy(hideRead = true).withField(SortField.AUTHOR).hideRead)
    }

    @Test
    fun hideReadAndHideUnreadAreTheStatusesOfThoseNames() {
        val hideRead = ListSort(hideRead = true)
        assertFalse(hideRead.shows("read"))
        assertTrue(hideRead.shows("skimmed"))
        assertTrue(hideRead.shows(null))
        val hideUnread = ListSort(hideUnread = true)
        // No status is unread, as on the server; a book set back to Unread by hand too.
        assertFalse(hideUnread.shows(null))
        assertFalse(hideUnread.shows("unread"))
        assertTrue(hideUnread.shows("want_to_read"))
        assertTrue(hideUnread.shows("read"))
        val both = ListSort(hideRead = true, hideUnread = true)
        assertEquals(
            listOf(ReadStatus.WANT_TO_READ, ReadStatus.READING, ReadStatus.ON_HOLD, ReadStatus.REREADING, ReadStatus.SKIMMED, ReadStatus.ABANDONED),
            both.shownStatuses,
        )
        val readingOnly = ListSort(readingOnly = true)
        assertEquals(listOf(ReadStatus.READING, ReadStatus.REREADING), readingOnly.shownStatuses)
    }

    @Test
    fun theStatusRuleIsTheServersReadStatusFilter() {
        assertNull(ListSort().statusRule())
        val hide = ListSort(hideRead = true, hideUnread = true).statusRule()!!
        assertEquals("readStatus", hide.field)
        assertEquals("excludesAll", hide.operator)
        // 'unread' in excludesAll also leaves out books without a status row (the server's rule).
        assertEquals(listOf("unread", "read"), hide.values())
        val reading = ListSort(readingOnly = true, hideRead = true).statusRule()!!
        assertEquals("includesAny", reading.operator)
        assertEquals(listOf("reading", "rereading"), reading.values())
    }

    @Test
    fun oneSegmentWithATitleTieBreak() {
        val segments = querySegments(ListSort(SortField.AUTHOR, descending = true, hideRead = true))
        assertEquals(1, segments.size)
        assertEquals(listOf(QuerySort("author", "desc"), QuerySort("title", "asc")), segments[0].sort)
        assertEquals("excludesAll", segments[0].filter!!.rules.single().operator)
        assertEquals(listOf(QuerySort("title", "asc")), querySegments(ListSort()).single().sort)
        assertNull(querySegments(ListSort()).single().filter)
        assertEquals(
            listOf(QuerySort("series", "asc"), QuerySort("seriesIndex", "asc"), QuerySort("title", "asc")),
            querySegments(ListSort(SortField.SERIES)).single().sort,
        )
    }

    @Test
    fun readStatusIsSegmentsInReadingOrder() {
        val segments = querySegments(ListSort(SortField.READ_STATUS))
        assertEquals(
            listOf(listOf("reading", "rereading"), listOf("on_hold", "want_to_read"), listOf("unread"), listOf("read", "skimmed"), listOf("abandoned")),
            segments.map { it.filter!!.rules.single().values() },
        )
        segments.forEach { assertEquals(listOf(QuerySort("readStatus", "asc"), QuerySort("title", "asc")), it.sort) }
        // Within a segment the server's alphabetical status sort already gives the wanted order.
        segments.forEach { segment -> segment.filter!!.rules.single().values().let { assertEquals(it.sorted(), it) } }

        val reversed = querySegments(ListSort(SortField.READ_STATUS, descending = true))
        assertEquals(listOf("abandoned"), reversed.first().filter!!.rules.single().values())
        assertEquals(QuerySort("readStatus", "desc"), reversed.first().sort.first())

        // Hidden statuses drop out of their segments, and empty segments aren't asked for.
        val filtered = querySegments(ListSort(SortField.READ_STATUS, hideRead = true, hideUnread = true))
        assertEquals(
            listOf(listOf("reading", "rereading"), listOf("on_hold", "want_to_read"), listOf("skimmed"), listOf("abandoned")),
            filtered.map { it.filter!!.rules.single().values() },
        )
        assertEquals(1, querySegments(ListSort(SortField.READ_STATUS, readingOnly = true)).size)
    }

    @Test
    fun theBodyHasOnlyTheServersFields() {
        val segment = querySegments(ListSort(SortField.TITLE, hideRead = true)).single()
        val json = ApiJson.encodeToString(ListQueryBody.serializer(), ListQueryBody(segment.sort, QueryPage(0, 60), segment.filter, q = null))
        assertEquals(
            """{"sort":[{"field":"title","dir":"asc"}],"pagination":{"page":0,"size":60},""" +
                """"filter":{"type":"group","join":"AND","rules":[{"type":"rule","field":"readStatus","operator":"excludesAll","value":["read"]}]}}""",
            json,
        )
        val plain = ApiJson.encodeToString(ListQueryBody.serializer(), ListQueryBody(listOf(QuerySort("title", "asc")), QueryPage(1, 60), q = "dracula"))
        assertEquals("""{"sort":[{"field":"title","dir":"asc"}],"pagination":{"page":1,"size":60},"q":"dracula"}""", plain)
    }

    @Test
    fun aStoredSortComesBackOnlyIfTheListStillOffersIt() {
        val sort = ListSort(SortField.LAST_READ, descending = true, hideUnread = true)
        assertEquals(sort, ListSort.restore(sort.stored(), ListKind.QUERY))
        // Last read isn't offered on an author's page: the default, filters and all.
        assertEquals(ListKind.AUTHOR.default, ListSort.restore(sort.stored(), ListKind.AUTHOR))
        assertEquals(ListKind.QUERY.default, ListSort.restore(StoredListSort("somethingNew"), ListKind.QUERY))
        assertEquals(ListKind.SERIES.default, ListSort.restore(null, ListKind.SERIES))
    }

    @Test
    fun aStatusChangedSinceTheLoadHidesTheBookAtOnce() {
        fun book(id: Long, status: String?) = BookCard(id, "Book $id", readStatus = status?.let { ReadStatusInfo(it) })
        val state = PagedState(items = listOf(book(1, "reading"), book(2, null), book(3, "reading")), total = 10)
        val overrides = mapOf(3L to "read")
        val shown = state.shownBy(ListSort(hideRead = true)) { overrides[it.id] ?: it.readStatus?.status }
        assertEquals(listOf(1L, 2L), shown.items.map { it.id })
        // The total drops with it, so the grid still knows how many are left to page in.
        assertEquals(9, shown.total)
        assertEquals(state, state.shownBy(ListSort(hideUnread = true)) { if (it.id == 2L) "reading" else it.readStatus?.status })
    }
}
