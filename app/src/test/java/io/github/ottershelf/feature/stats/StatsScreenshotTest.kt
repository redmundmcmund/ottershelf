package io.github.ottershelf.feature.stats

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.feature.stats.model.ArchetypePoint
import io.github.ottershelf.feature.stats.model.CompletionLatency
import io.github.ottershelf.feature.stats.model.DailyActivity
import io.github.ottershelf.feature.stats.model.DailyReadingStat
import io.github.ottershelf.feature.stats.model.DayPoint
import io.github.ottershelf.feature.stats.model.DecadeCount
import io.github.ottershelf.feature.stats.model.Duration
import io.github.ottershelf.feature.stats.model.FavoriteDayStat
import io.github.ottershelf.feature.stats.model.FormatCount
import io.github.ottershelf.feature.stats.model.FormatStorage
import io.github.ottershelf.feature.stats.model.GenreCount
import io.github.ottershelf.feature.stats.model.GenreReadingTime
import io.github.ottershelf.feature.stats.model.GoalTrajectoryPoint
import io.github.ottershelf.feature.stats.model.LanguageCount
import io.github.ottershelf.feature.stats.model.LargestBook
import io.github.ottershelf.feature.stats.model.LatencyBucket
import io.github.ottershelf.feature.stats.model.LibrarySummary
import io.github.ottershelf.feature.stats.model.MonthCount
import io.github.ottershelf.feature.stats.model.NameCount
import io.github.ottershelf.feature.stats.model.Overview
import io.github.ottershelf.feature.stats.model.OverviewGoal
import io.github.ottershelf.feature.stats.model.OverviewGoalPoint
import io.github.ottershelf.feature.stats.model.PacePoint
import io.github.ottershelf.feature.stats.model.PeakHourStat
import io.github.ottershelf.feature.stats.model.ProgressFunnel
import io.github.ottershelf.feature.stats.model.ProgressFunnelComparison
import io.github.ottershelf.feature.stats.model.Rhythm
import io.github.ottershelf.feature.stats.model.RhythmWeekday
import io.github.ottershelf.feature.stats.model.Snapshot
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.sin

/** The Statistics screen, dark, tall enough for every card: stats/{reading,library}_dark.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class StatsScreenshotTest {

    private val today = LocalDate.of(2026, 9, 25)

    /** A believable reading pattern: evenings, more at weekends, a few days off. */
    private fun secondsOn(date: LocalDate): Double {
        val n = date.toEpochDay()
        if (n % 5 == 3L || n % 11 == 0L) return 0.0
        val weekend = date.dayOfWeek.value >= 6
        return (1200 + 900 * abs(sin(n * 0.7)) + if (weekend) 1500 else 0).toDouble()
    }

    private val readingDaily = (0 until 180).map { today.minusDays(it.toLong()) }.map { DailyReadingStat(it.toString(), secondsOn(it), eventsCount = 1.0) }

    private val overview = Overview(
        timezone = "Europe/Dublin",
        snapshot = Snapshot(
            today = Duration(readingSeconds = 1500.0),
            lastSevenDays = Duration(readingSeconds = 5.3 * 3600),
            previousSevenDays = Duration(readingSeconds = 4.1 * 3600),
            currentStreak = 6,
            longestStreak = 21,
            completedBooksYtd = 15,
        ),
        dailyActivity = DailyActivity((0 until 14).map { today.minusDays(it.toLong()) }.map { DayPoint(it.toString(), readingSeconds = secondsOn(it)) }),
        goal = OverviewGoal(
            year = 2026, goalBooks = 24, completedBooks = 15, projectedBooks = 20.4, status = "behind",
            points = (1..12).map { m -> OverviewGoalPoint(m, listOf(1, 3, 4, 6, 7, 9, 10, 12, 15, 15, 15, 15)[m - 1].toDouble(), 2.0 * m) },
        ),
        rhythm = Rhythm(
            sessionsCount = 240,
            weekdays = listOf(2400, 1500, 1300, 1400, 1250, 1600, 2800).mapIndexed { i, s -> RhythmWeekday(i, s.toDouble(), 30) },
            favoriteDayOfWeek = 6,
            peakHour = 21,
        ),
    )

    private val reading = ReadingCharts(
        overview = Load.Ready(overview),
        daily = Load.Ready(StatsMath.dailySeries(readingDaily, today, 30)),
        heatmap = Load.Ready(StatsMath.heatmapYear(readingDaily, 2026, today)),
        peakHours = Load.Ready((0 until 24).map { h -> PeakHourStat(h, if (h in 7..8) 3000.0 else if (h in 19..23) 9000.0 - abs(21 - h) * 2000 else if (h in 12..13) 1500.0 else 0.0, 4.0) }),
        weekdays = Load.Ready(WeekdayData(listOf(25.0, 21.0, 22.0, 24.0, 20.0, 38.0, 45.0), List(7) { 40000.0 }, List(7) { 12 }, 84)),
        completions = Load.Ready(StatsMath.months((0 until 30).map { i -> YearMonth.from(today).minusMonths(i.toLong()) }.map { MonthCount(it.year, it.monthValue, ((it.monthValue * 7 + it.year) % 4).toDouble()) }, YearMonth.of(2024, 4), YearMonth.from(today))),
        trajectory = Load.Ready(Trajectory((0 until 12).map { i -> YearMonth.from(today).minusMonths(11L - i) }.mapIndexed { i, m -> GoalTrajectoryPoint(m.year, m.monthValue, (i * 1.6 + 1).toInt().toDouble(), 2.0 * (i + 1)) }, 24)),
        genres = Load.Ready(listOf("Fantasy" to 62.0, "Science fiction" to 41.0, "Literary fiction" to 30.0, "Mystery" to 18.0, "History" to 9.0, "Poetry" to 4.0).map { (g, h) -> GenreReadingTime(g, h * 3600) }),
        pace = Load.Ready((0 until 80).map { i -> PacePoint(600.0 + (i * 137 % 5400), 0.5 + (i * 37 % 60) / 10.0) }),
        archetypes = Load.Ready((0 until 70).map { i -> ArchetypePoint(if (i % 4 == 0) 7.5 + i % 3 * 0.3 else 19.0 + (i * 13 % 50) / 10.0, 10.0 + i * 29 % 80, i % 7) }),
        funnel = Load.Ready(ProgressFunnelComparison(90, ProgressFunnel(18.0, 15.0, 13.0, 11.0, 9.0), ProgressFunnel(14.0, 11.0, 9.0, 7.0, 6.0))),
        latency = Load.Ready(CompletionLatency(22.0, 19.0, 34.0, 71.0, listOf(4, 7, 6, 3, 1, 1, 0).mapIndexed { i, n -> LatencyBucket("b$i", count = n.toDouble()) })),
    )

    private val library = LibraryCharts(
        summary = Load.Ready(LibrarySummary(1284.0, 612.0, 143.0, 90.0, 18.4 * 1024 * 1024 * 1024, 57.0, 6.0, 1818, 2026, 96.0)),
        formats = Load.Ready(listOf(FormatCount("epub", 1102.0), FormatCount("pdf", 121.0), FormatCount("cbz", 38.0), FormatCount("mobi", 23.0))),
        storage = Load.Ready(listOf(FormatStorage("pdf", 9.1e9), FormatStorage("epub", 6.2e9), FormatStorage("cbz", 3.0e9))),
        added = Load.Ready(StatsMath.months((0 until 60).map { i -> YearMonth.from(today).minusMonths(i.toLong()) }.map { MonthCount(it.year, it.monthValue, (5 + (it.monthValue * 13 + it.year) % 30).toDouble()) }, YearMonth.from(today).minusMonths(59), YearMonth.from(today))),
        authors = Load.Ready(listOf("Arthur Conan Doyle" to 31, "Anthony Trollope" to 28, "Charles Dickens" to 22, "Edgar A. Poe" to 14, "Mary Shelley" to 3).map { NameCount(it.first, it.second.toDouble()) }),
        series = Load.Ready(listOf("Barsetshire" to 6, "Sherlock Holmes" to 9, "The Dupin Tales" to 3).map { NameCount(it.first, it.second.toDouble()) }),
        genres = Load.Ready(listOf("Fantasy" to 420, "Science fiction" to 300, "Mystery" to 180).map { GenreCount(it.first, it.second.toDouble()) }),
        languages = Load.Ready(listOf(LanguageCount("en", 1180.0), LanguageCount("ga", 60.0), LanguageCount("fr", 44.0))),
        decades = Load.Ready((1950..2020 step 10).map { DecadeCount(it, (it - 1940) * 3.0) }),
        largest = Load.Ready(listOf(LargestBook(1, "The Complete Works of Shakespeare", 412e6, "pdf"), LargestBook(2, "Little Nemo in Slumberland", 260e6, "cbz"))),
    )

    @Test
    fun reading() = captureDark("stats/reading", heightDp = 5200) {
        Frame { padding ->
            StatsContent(
                StatsUiState(today = today, period = StatsPeriod.D30, libraries = listOf(LibraryOption(1, "Books"), LibraryOption(2, "Comics")), reading = reading),
                contentPadding = padding,
            )
        }
    }

    /**
     * The phone check's account: a few days of reading (18 s the week before), 14 books marked read
     * in September, no yearly goal. The overview's own count (0, from sessions) is replaced by the
     * completion timeline's, as StatsViewModel does.
     */
    @Test
    fun readingSparse() = captureDark("stats/reading_sparse", heightDp = 3400) {
        val rows = listOf("2026-09-10" to 25.0, "2026-09-17" to 18.0, "2026-09-23" to 130.0, "2026-09-25" to 537.0)
            .map { (d, s) -> DailyReadingStat(d, s, eventsCount = 1.0) }
        val months = listOf(MonthCount(2026, 9, 14.0))
        val sparseOverview = Overview(
            timezone = "Europe/Dublin",
            snapshot = Snapshot(
                today = Duration(readingSeconds = 537.0),
                lastSevenDays = Duration(readingSeconds = 667.0),
                previousSevenDays = Duration(readingSeconds = 18.0),
                currentStreak = 1,
                longestStreak = 1,
                completedBooksYtd = 0,
            ),
            dailyActivity = DailyActivity(rows.map { DayPoint(it.day, readingSeconds = it.readingSeconds) }),
            goal = OverviewGoal(year = 2026, goalBooks = null, completedBooks = 0, projectedBooks = 0.0, points = (1..12).map { OverviewGoalPoint(it, 0.0) }),
        )
        val sparse = ReadingCharts(
            overview = Load.Ready(StatsMath.withFinishedBooks(sparseOverview, months, today)),
            daily = Load.Ready(StatsMath.dailySeries(rows, today, 90)),
            heatmap = Load.Ready(StatsMath.heatmapYear(rows, 2026, today)),
            peakHours = Load.Ready(emptyList()),
            weekdays = Load.Ready(WeekdayData(List(7) { 0.0 }, List(7) { 0.0 }, List(7) { 0 }, 4)),
            completions = Load.Ready(StatsMath.completionMonths(months, today)),
            trajectory = Load.Ready(
                Trajectory(
                    (0 until 12).map { i -> YearMonth.from(today).minusMonths(11L - i) }
                        .mapIndexed { i, m -> GoalTrajectoryPoint(m.year, m.monthValue, if (i == 11) 14.0 else 0.0, 1.0 * (i + 1)) },
                    goalBooks = null,
                ),
            ),
            genres = Load.Ready(listOf("Fantasy" to 660.0, "Science" to 39.0).map { (g, s) -> GenreReadingTime(g, s) }),
            pace = Load.Ready(emptyList()),
            archetypes = Load.Ready(emptyList()),
            funnel = Load.Ready(ProgressFunnelComparison(90, ProgressFunnel(3.0, 1.0, 0.0, 0.0, 0.0))),
            latency = Load.Ready(CompletionLatency()),
        )
        Frame { padding ->
            StatsContent(StatsUiState(today = today, period = StatsPeriod.D90, reading = sparse), contentPadding = padding)
        }
    }

    @Test
    fun library() = captureDark("stats/library", heightDp = 3000) {
        Frame { padding ->
            StatsContent(StatsUiState(today = today, tab = StatsTab.LIBRARY, library = library), contentPadding = padding)
        }
    }

    @Composable
    private fun Frame(content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
        RootFrame(
            title = "Statistics",
            search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
            onOpenDrawer = {},
            onOpenSearch = {},
            onSubmitSearch = {},
            onCloseSearch = {},
            content = content,
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDark(name: String, heightDp: Int, content: @Composable () -> Unit) = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = heightDp)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                WithFakeCovers(content)
            }
        }
    }
}
