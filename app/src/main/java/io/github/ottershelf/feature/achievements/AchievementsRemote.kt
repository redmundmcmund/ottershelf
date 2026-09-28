package io.github.ottershelf.feature.achievements

import kotlinx.serialization.builtins.ListSerializer
import io.github.ottershelf.core.model.BooksPage
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.feature.achievements.model.AchievementCatalogue
import io.github.ottershelf.feature.achievements.model.FilteredBookQuery
import io.github.ottershelf.feature.achievements.model.QueryGroup
import io.github.ottershelf.feature.achievements.model.QueryPage
import io.github.ottershelf.feature.achievements.model.QueryRule
import io.github.ottershelf.feature.achievements.model.QuerySort
import io.github.ottershelf.feature.stats.model.GenreReadingTime
import io.github.ottershelf.feature.stats.model.MonthCount
import io.github.ottershelf.feature.stats.model.PeakHourStat
import kotlinx.serialization.json.JsonElement

/**
 * The achievements screen's and the year in review's own reads (the tracker's reads, such as the
 * activity calendar and the celebration claim, stay in core.tracking). GETs, plus the book query,
 * which is a POST that changes nothing.
 */
interface AchievementsRemote {
    /** `GET achievements`: the whole catalogue with the user's progress. */
    suspend fun catalogue(): AchievementCatalogue

    /**
     * Books whose current reading finished in [year] (the user's timezone), oldest finish first; at most
     * 200. The status holds only the latest (or open) reading, so a book finished that year and then
     * reread, or finished again later, isn't here: see [finishCandidates].
     */
    suspend fun finishedIn(year: Int): BooksPage

    /**
     * Books that may hold a reading finished in [year] that their status no longer shows: finished
     * after it, or reading, re-reading, on hold, abandoned or skimmed now. [page] of 200.
     */
    suspend fun finishCandidates(year: Int, page: Int): BooksPage

    /** `user-statistics/completion-timeline?days=`: finished books per month over the window. */
    suspend fun completionTimeline(days: Int): List<MonthCount>

    /** `user-statistics/peak-hours?days=`: reading time per hour of day over the window. */
    suspend fun peakHours(days: Int): List<PeakHourStat>

    /** `user-statistics/genre-reading-time?days=`. */
    suspend fun genreReadingTime(days: Int): List<GenreReadingTime>
}

class ApiAchievementsRemote(private val api: Api) : AchievementsRemote {

    override suspend fun catalogue(): AchievementCatalogue =
        api.send("GET", "achievements", null) { ApiJson.decodeFromString(AchievementCatalogue.serializer(), it) }

    override suspend fun finishedIn(year: Int): BooksPage = query(finishedInQuery(year))

    override suspend fun finishCandidates(year: Int, page: Int): BooksPage = query(finishCandidatesQuery(year, page))

    private suspend fun query(body: FilteredBookQuery): BooksPage {
        val json: JsonElement = ApiJson.encodeToJsonElement(FilteredBookQuery.serializer(), body)
        return api.send("POST", "books/query", json) { ApiJson.decodeFromString(BooksPage.serializer(), it) }
    }

    companion object {
        const val PAGE_SIZE = 200

        fun finishedInQuery(year: Int) = FilteredBookQuery(
            filter = QueryGroup(rules = listOf(QueryRule.between("finishedAt", "$year-01-01", "$year-12-31"))),
            sort = listOf(QuerySort("finishedAt", "asc")),
            pagination = QueryPage(0, PAGE_SIZE),
        )

        fun finishCandidatesQuery(year: Int, page: Int) = FilteredBookQuery(
            filter = QueryGroup(
                join = "OR",
                rules = listOf(
                    QueryRule.after("finishedAt", "$year-12-31"),
                    QueryRule.includesAny("readStatus", listOf("reading", "rereading", "on_hold", "abandoned", "skimmed")),
                ),
            ),
            sort = emptyList(), // title, then id: a stable order to page through
            pagination = QueryPage(page, PAGE_SIZE),
        )
    }

    override suspend fun completionTimeline(days: Int): List<MonthCount> =
        api.send("GET", "user-statistics/completion-timeline?days=$days", null) { ApiJson.decodeFromString(ListSerializer(MonthCount.serializer()), it) }

    override suspend fun peakHours(days: Int): List<PeakHourStat> =
        api.send("GET", "user-statistics/peak-hours?days=$days", null) { ApiJson.decodeFromString(ListSerializer(PeakHourStat.serializer()), it) }

    override suspend fun genreReadingTime(days: Int): List<GenreReadingTime> =
        api.send("GET", "user-statistics/genre-reading-time?days=$days", null) { ApiJson.decodeFromString(ListSerializer(GenreReadingTime.serializer()), it) }
}
