package io.github.ottershelf.feature.stats.model

import kotlinx.serialization.Serializable

/*
 * The server's user-statistics and statistics responses (packages/types/src/activity.ts,
 * user-statistics.ts, statistics.ts), only the fields the Statistics screen shows. Measurements are
 * Doubles: some come from SQL sums the server passes through as they are.
 */

// --- activity overview (GET user-statistics/activity-overview) ---------------------------------

@Serializable
data class Duration(val totalSeconds: Double = 0.0, val readingSeconds: Double = 0.0, val listeningSeconds: Double = 0.0)

@Serializable
data class Snapshot(
    val today: Duration = Duration(),
    val lastSevenDays: Duration = Duration(),
    val previousSevenDays: Duration = Duration(),
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val completedBooksYtd: Int = 0,
)

/** `ActivityDayPoint`: one local day in users.settings.timezone. */
@Serializable
data class DayPoint(
    val day: String,
    val totalSeconds: Double = 0.0,
    val readingSeconds: Double = 0.0,
    val sessionsCount: Int = 0,
)

@Serializable
data class DailyActivity(val days: List<DayPoint> = emptyList())

@Serializable
data class OverviewGoalPoint(val month: Int, val actualCumulative: Double = 0.0, val targetCumulative: Double? = null)

@Serializable
data class OverviewGoal(
    val year: Int,
    val goalBooks: Int? = null,
    val completedBooks: Int = 0,
    val projectedBooks: Double = 0.0,
    /** ahead, on_pace or behind; null without a goal. */
    val status: String? = null,
    val points: List<OverviewGoalPoint> = emptyList(),
)

/** dayOfWeek 0 = Sunday. */
@Serializable
data class RhythmWeekday(val dayOfWeek: Int, val averageReadingSeconds: Double = 0.0, val sessionsCount: Int = 0)

@Serializable
data class Rhythm(
    val windowDays: Int = 365,
    val sessionsCount: Int = 0,
    val weekdays: List<RhythmWeekday> = emptyList(),
    val favoriteDayOfWeek: Int? = null,
    val peakHour: Int? = null,
)

@Serializable
data class Overview(
    val timezone: String? = null,
    val snapshot: Snapshot = Snapshot(),
    val dailyActivity: DailyActivity = DailyActivity(),
    val goal: OverviewGoal? = null,
    val rhythm: Rhythm = Rhythm(),
)

// --- personal charts ---------------------------------------------------------------------------

/** daily-reading and reading-heatmap rows (daily-reading only lists days with reading). */
@Serializable
data class DailyReadingStat(val day: String, val readingSeconds: Double = 0.0, val progressDelta: Double = 0.0, val eventsCount: Double = 0.0)

@Serializable
data class PeakHourStat(val hour: Int, val readingSeconds: Double = 0.0, val eventsCount: Double = 0.0)

/** dayOfWeek 0 = Sunday. */
@Serializable
data class FavoriteDayStat(val dayOfWeek: Int, val readingSeconds: Double = 0.0, val eventsCount: Double = 0.0)

@Serializable
data class MonthCount(val year: Int, val month: Int, val count: Double = 0.0)

@Serializable
data class GoalTrajectoryPoint(val year: Int, val month: Int, val actualCumulative: Double = 0.0, val targetCumulative: Double = 0.0)

@Serializable
data class GoalTrajectory(val goalBooks: Int = 12, val points: List<GoalTrajectoryPoint> = emptyList())

@Serializable
data class ProgressFunnel(
    val started: Double = 0.0,
    val reached25: Double = 0.0,
    val reached50: Double = 0.0,
    val reached75: Double = 0.0,
    val completed: Double = 0.0,
)

@Serializable
data class ProgressFunnelComparison(val days: Int = 365, val current: ProgressFunnel = ProgressFunnel(), val previous: ProgressFunnel? = null)

@Serializable
data class LatencyBucket(val label: String, val minDays: Int = 0, val maxDays: Int? = null, val count: Double = 0.0)

@Serializable
data class CompletionLatency(
    val totalCompletions: Double = 0.0,
    val medianDays: Double? = null,
    val percentile75Days: Double? = null,
    val percentile90Days: Double? = null,
    val buckets: List<LatencyBucket> = emptyList(),
)

@Serializable
data class GenreReadingTime(val genre: String, val readingSeconds: Double = 0.0)

@Serializable
data class PacePoint(val durationSeconds: Double = 0.0, val progressDelta: Double = 0.0)

/** [hour] is fractional (21.5 = 21:30); dayOfWeek 0 = Sunday. */
@Serializable
data class ArchetypePoint(val hour: Double = 0.0, val durationMinutes: Double = 0.0, val dayOfWeek: Int = 0)

// --- library statistics (GET statistics/...) ---------------------------------------------------

@Serializable
data class StatisticsResult<T>(val items: List<T> = emptyList(), val unknownCount: Double = 0.0)

@Serializable
data class LibrarySummary(
    val totalBooks: Double = 0.0,
    val totalAuthors: Double = 0.0,
    val totalSeries: Double = 0.0,
    val totalPublishers: Double = 0.0,
    val totalStorageBytes: Double = 0.0,
    val totalGenres: Double = 0.0,
    val totalLanguages: Double = 0.0,
    val publicationYearMin: Int? = null,
    val publicationYearMax: Int? = null,
    val booksAddedThisYear: Double = 0.0,
)

@Serializable
data class FormatCount(val format: String, val count: Double = 0.0)

@Serializable
data class FormatStorage(val format: String, val sizeBytes: Double = 0.0)

@Serializable
data class NameCount(val name: String, val count: Double = 0.0)

@Serializable
data class GenreCount(val genre: String, val count: Double = 0.0)

@Serializable
data class LanguageCount(val language: String, val count: Double = 0.0)

@Serializable
data class DecadeCount(val decade: Int, val count: Double = 0.0)

@Serializable
data class LargestBook(val id: Long, val title: String, val sizeBytes: Double = 0.0, val format: String = "")
