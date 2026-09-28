package io.github.ottershelf.feature.history

import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.core.tracking.TrackingRepository
import io.github.ottershelf.feature.achievements.model.FilteredBookQuery
import io.github.ottershelf.feature.achievements.model.QueryGroup
import io.github.ottershelf.feature.achievements.model.QueryPage
import io.github.ottershelf.feature.achievements.model.QueryRule

/** The history's two reads, behind an interface for the tests. Both throw the Api's exceptions. */
interface HistoryRemote {
    /** [page] (0-based) of the books the user has a reading status for ([HistoryMath.STATUSES]), [PAGE_SIZE] a page. */
    suspend fun books(page: Int): HistoryBooksPage

    /** Every reading of the book (the first 100, newest first: far more than any book has). */
    suspend fun attempts(bookId: Long): List<ReadingAttempt>

    companion object {
        /** The book query's largest page. */
        const val PAGE_SIZE = 200
    }
}

class ApiHistoryRemote(private val api: Api, private val tracking: TrackingRepository) : HistoryRemote {

    override suspend fun books(page: Int): HistoryBooksPage {
        val body = FilteredBookQuery(
            filter = QueryGroup(rules = listOf(QueryRule.includesAny("readStatus", HistoryMath.STATUSES))),
            sort = emptyList(), // title, then id: a stable order to page through
            pagination = QueryPage(page, HistoryRemote.PAGE_SIZE),
        )
        return api.send("POST", "books/query", ApiJson.encodeToJsonElement(FilteredBookQuery.serializer(), body)) {
            ApiJson.decodeFromString(HistoryBooksPage.serializer(), it)
        }
    }

    override suspend fun attempts(bookId: Long): List<ReadingAttempt> = tracking.attempts(bookId).items
}
