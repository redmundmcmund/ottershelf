package io.github.ottershelf.feature.stats

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import io.github.ottershelf.core.model.Library
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.feature.stats.model.ArchetypePoint
import io.github.ottershelf.feature.stats.model.CompletionLatency
import io.github.ottershelf.feature.stats.model.DailyReadingStat
import io.github.ottershelf.feature.stats.model.DecadeCount
import io.github.ottershelf.feature.stats.model.FormatCount
import io.github.ottershelf.feature.stats.model.FormatStorage
import io.github.ottershelf.feature.stats.model.GenreCount
import io.github.ottershelf.feature.stats.model.GenreReadingTime
import io.github.ottershelf.feature.stats.model.GoalTrajectory
import io.github.ottershelf.feature.stats.model.LanguageCount
import io.github.ottershelf.feature.stats.model.LargestBook
import io.github.ottershelf.feature.stats.model.LibrarySummary
import io.github.ottershelf.feature.stats.model.MonthCount
import io.github.ottershelf.feature.stats.model.NameCount
import io.github.ottershelf.feature.stats.model.Overview
import io.github.ottershelf.feature.stats.model.PacePoint
import io.github.ottershelf.feature.stats.model.PeakHourStat
import io.github.ottershelf.feature.stats.model.ProgressFunnelComparison
import io.github.ottershelf.feature.stats.model.StatisticsResult

/**
 * The Statistics screen's reads: the server's `user-statistics/...` (the user's own reading) and
 * `statistics/...` (the libraries the user can see; no admin permission needed). Every call takes the
 * library filter ([libraryId] null = all libraries). GETs only; the server caches each answer for
 * five minutes.
 *
 * The heatmap and the weekday chart use `daily-reading` (the user's local days, no upper bound) rather
 * than `reading-heatmap` (which stops at today in UTC) and `favorite-days` (weekdays in UTC).
 */
interface StatsRemote {
    suspend fun libraries(): List<Library>
    suspend fun overview(libraryId: Long?): Overview
    suspend fun dailyReading(days: Int, libraryId: Long?): List<DailyReadingStat>
    suspend fun peakHours(days: Int, libraryId: Long?): List<PeakHourStat>
    suspend fun completionTimeline(days: Int, libraryId: Long?): List<MonthCount>
    /** [days] from today in UTC; see [StatsMath.trajectoryDays]. */
    suspend fun goalTrajectory(goalBooks: Int, days: Int, libraryId: Long?): GoalTrajectory
    suspend fun progressFunnel(days: Int, libraryId: Long?): ProgressFunnelComparison
    suspend fun completionLatency(days: Int, libraryId: Long?): CompletionLatency
    suspend fun genreReadingTime(days: Int, libraryId: Long?): List<GenreReadingTime>
    suspend fun readingPace(days: Int, libraryId: Long?): List<PacePoint>
    suspend fun sessionArchetypes(days: Int, libraryId: Long?): List<ArchetypePoint>

    suspend fun librarySummary(libraryId: Long?): LibrarySummary
    suspend fun formats(libraryId: Long?): List<FormatCount>
    suspend fun storage(libraryId: Long?): List<FormatStorage>
    suspend fun booksAdded(libraryId: Long?): List<MonthCount>
    suspend fun topAuthors(libraryId: Long?): List<NameCount>
    suspend fun topSeries(libraryId: Long?): List<NameCount>
    suspend fun genres(libraryId: Long?): List<GenreCount>
    suspend fun languages(libraryId: Long?): List<LanguageCount>
    suspend fun decades(libraryId: Long?): List<DecadeCount>
    suspend fun largestBooks(libraryId: Long?): List<LargestBook>
}

/** [StatsRemote] over the app's authenticated [Api] (its `send`, as core.tracking does). */
class ApiStatsRemote(private val api: Api) : StatsRemote {

    private suspend fun <T> get(path: String, de: DeserializationStrategy<T>): T =
        api.send("GET", path, null) { ApiJson.decodeFromString(de, it) }

    private suspend fun <T> items(path: String, de: kotlinx.serialization.KSerializer<T>): List<T> =
        get(path, StatisticsResult.serializer(de)).items

    /** `?days=..&libraryIds=..` from the given pairs, skipping nulls. */
    private fun query(libraryId: Long?, vararg params: Pair<String, Any?>): String {
        val parts = params.mapNotNull { (k, v) -> v?.let { "$k=$it" } } + listOfNotNull(libraryId?.let { "libraryIds=$it" })
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }

    private fun user(path: String, libraryId: Long?, vararg params: Pair<String, Any?>) = "user-statistics/$path${query(libraryId, *params)}"
    private fun lib(path: String, libraryId: Long?, vararg params: Pair<String, Any?>) = "statistics/$path${query(libraryId, *params)}"

    override suspend fun libraries(): List<Library> = api.libraries()

    override suspend fun overview(libraryId: Long?): Overview = get(user("activity-overview", libraryId), Overview.serializer())

    override suspend fun dailyReading(days: Int, libraryId: Long?) =
        get(user("daily-reading", libraryId, "days" to days), ListSerializer(DailyReadingStat.serializer()))

    override suspend fun peakHours(days: Int, libraryId: Long?) =
        get(user("peak-hours", libraryId, "days" to days), ListSerializer(PeakHourStat.serializer()))

    override suspend fun completionTimeline(days: Int, libraryId: Long?) =
        get(user("completion-timeline", libraryId, "days" to days), ListSerializer(MonthCount.serializer()))

    override suspend fun goalTrajectory(goalBooks: Int, days: Int, libraryId: Long?) =
        get(user("goal-trajectory", libraryId, "days" to days.coerceIn(1, 3650), "goalBooks" to goalBooks.coerceIn(1, 240)), GoalTrajectory.serializer())

    override suspend fun progressFunnel(days: Int, libraryId: Long?) =
        get(user("progress-funnel", libraryId, "days" to days, "comparePrevious" to true), ProgressFunnelComparison.serializer())

    override suspend fun completionLatency(days: Int, libraryId: Long?) =
        get(user("completion-latency", libraryId, "days" to days), CompletionLatency.serializer())

    override suspend fun genreReadingTime(days: Int, libraryId: Long?) =
        get(user("genre-reading-time", libraryId, "days" to days), ListSerializer(GenreReadingTime.serializer()))

    override suspend fun readingPace(days: Int, libraryId: Long?) =
        get(user("reading-pace", libraryId, "days" to days), ListSerializer(PacePoint.serializer()))

    override suspend fun sessionArchetypes(days: Int, libraryId: Long?) =
        get(user("session-archetypes", libraryId, "days" to days), ListSerializer(ArchetypePoint.serializer()))

    override suspend fun librarySummary(libraryId: Long?) = get(lib("summary", libraryId), LibrarySummary.serializer())
    override suspend fun formats(libraryId: Long?) = items(lib("format-distribution", libraryId), FormatCount.serializer())
    override suspend fun storage(libraryId: Long?) = items(lib("storage-by-format", libraryId), FormatStorage.serializer())
    override suspend fun booksAdded(libraryId: Long?) =
        items(lib("books-added-over-time", libraryId, "granularity" to "monthly", "range" to "last-5-years"), MonthCount.serializer())
    override suspend fun topAuthors(libraryId: Long?) = items(lib("top-authors", libraryId), NameCount.serializer())
    override suspend fun topSeries(libraryId: Long?) = items(lib("top-series", libraryId), NameCount.serializer())
    override suspend fun genres(libraryId: Long?) = items(lib("genre-distribution", libraryId), GenreCount.serializer())
    override suspend fun languages(libraryId: Long?) = items(lib("language-distribution", libraryId), LanguageCount.serializer())
    override suspend fun decades(libraryId: Long?) = items(lib("publication-decade", libraryId), DecadeCount.serializer())
    override suspend fun largestBooks(libraryId: Long?) = items(lib("largest-books", libraryId), LargestBook.serializer())
}
