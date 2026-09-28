package io.github.ottershelf.ui.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.CurrentlyReadingBook
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.ReadingStreak
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.BookSession
import io.github.ottershelf.core.tracking.BookSessionStats
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.feature.book.BookDetailContent
import io.github.ottershelf.feature.book.BookDetailUiState
import io.github.ottershelf.feature.book.BookTrackingUiState
import io.github.ottershelf.feature.calendar.CalendarBook
import io.github.ottershelf.feature.calendar.CalendarContent
import io.github.ottershelf.feature.calendar.CalendarDay
import io.github.ottershelf.feature.calendar.CalendarMonth
import io.github.ottershelf.feature.calendar.CalendarUiState
import io.github.ottershelf.feature.home.HomeContent
import io.github.ottershelf.feature.home.HomeUiState
import io.github.ottershelf.feature.home.ReadingRow
import io.github.ottershelf.feature.home.ShelfItem
import io.github.ottershelf.feature.home.model.DEFAULT_WIDGETS
import io.github.ottershelf.feature.home.model.DashboardLayout
import io.github.ottershelf.feature.home.model.HighlightData
import io.github.ottershelf.feature.home.model.ReadingGoalData
import io.github.ottershelf.feature.home.model.ReadingRhythmData
import io.github.ottershelf.feature.home.model.RhythmDay
import io.github.ottershelf.feature.home.model.ShelfConfig
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.feature.home.model.reorderWidgets
import io.github.ottershelf.feature.library.BookGridContent
import io.github.ottershelf.feature.library.GridColumns
import io.github.ottershelf.feature.library.GridMetrics
import io.github.ottershelf.feature.library.ListView
import io.github.ottershelf.feature.library.PagedState
import io.github.ottershelf.feature.library.SortButton
import io.github.ottershelf.feature.library.ViewMenuButton
import io.github.ottershelf.feature.notes.HighlightActions
import io.github.ottershelf.feature.notes.BookHighlightsContent
import io.github.ottershelf.feature.notes.BookHighlightsUiState
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookAnnotationStats
import io.github.ottershelf.feature.notes.model.ChapterStat
import io.github.ottershelf.feature.notes.model.ColorCount
import io.github.ottershelf.feature.stats.LibraryOption
import io.github.ottershelf.feature.stats.Load
import io.github.ottershelf.feature.stats.ReadingCharts
import io.github.ottershelf.feature.stats.StatsContent
import io.github.ottershelf.feature.stats.StatsMath
import io.github.ottershelf.feature.stats.StatsPeriod
import io.github.ottershelf.feature.stats.StatsUiState
import io.github.ottershelf.feature.stats.Trajectory
import io.github.ottershelf.feature.stats.WeekdayData
import io.github.ottershelf.feature.stats.model.ArchetypePoint
import io.github.ottershelf.feature.stats.model.CompletionLatency
import io.github.ottershelf.feature.stats.model.DailyActivity
import io.github.ottershelf.feature.stats.model.DailyReadingStat
import io.github.ottershelf.feature.stats.model.DayPoint
import io.github.ottershelf.feature.stats.model.Duration
import io.github.ottershelf.feature.stats.model.GenreReadingTime
import io.github.ottershelf.feature.stats.model.GoalTrajectoryPoint
import io.github.ottershelf.feature.stats.model.LatencyBucket
import io.github.ottershelf.feature.stats.model.MonthCount
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
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.RootTopBar
import io.github.ottershelf.ui.nav.SearchBarState
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Google Play phone screenshots: six screens at 360x640 dp, xxhdpi, so each PNG is exactly
 * 1080x1920; light theme, the default accent, a drawn status bar and gesture handle, and a library
 * of public-domain classics: the Dashboard, the library grid, a book page, reading statistics, the
 * reading calendar and a book's highlights and notes. Written to
 * app/build/outputs/roborazzi/store/1_dashboard.png .. 6_notes.png (24-bit copies of them are the
 * store listing's images). Record with `./gradlew recordRoborazziDebug --tests "*StoreScreenshotTest"`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class StoreScreenshotTest {

    @Test
    fun dashboard() = capture("1_dashboard") {
        Root("Dashboard") { padding ->
            TopBarActions {
                IconButton(onClick = {}) { LucideIcon("Settings2", contentDescription = null, tint = LocalContentColor.current, size = 22.dp) }
            }
            HomeContent(dashboard, padding, widgetCover = { id, has -> if (has) coverFor(id) else null }, rhythmDay = 13)
        }
    }

    @Test
    fun library() = capture("2_library") {
        val metrics = remember { GridMetrics() }
        val view = ListView(cellDp = GridColumns.cellFor(GRID_WIDTH, 3))
        Scaffold(
            topBar = {
                RootTopBar(
                    title = "All books",
                    search = SearchBarState(mutableStateOf(false), mutableStateOf("")),
                    onOpenDrawer = {},
                    onOpenSearch = {},
                    onSubmitSearch = {},
                    onCloseSearch = {},
                    actions = {
                        ViewMenuButton(view, metrics, onChange = {})
                        SortButton(active = false, onClick = {})
                    },
                )
            },
        ) { padding ->
            BookGridContent(
                state = PagedState(items = library, total = 1284),
                coverOf = { if (it.hasCover) coverFor(it.id) else null },
                contentPadding = PaddingValues(top = padding.calculateTopPadding()),
                view = view,
                metrics = metrics,
            )
        }
    }

    @Test
    fun bookPage() = capture("3_book") {
        BookDetailContent(
            state = BookDetailUiState(
                bookId = PRIDE,
                book = pride,
                loading = false,
                loaded = true,
                status = "reading",
                cover = coverFor(PRIDE),
                readFile = pride.files.first(),
                offlineFile = pride.files.first(),
            ),
            tint = Color(0xFF3D7EA6),
            tracking = prideTracking,
            today = TODAY,
        )
    }

    @Test
    fun statistics() = capture("4_statistics") {
        Root("Statistics") { padding ->
            StatsContent(
                StatsUiState(today = TODAY, period = StatsPeriod.D30, libraries = listOf(LibraryOption(1, "Books")), reading = reading),
                contentPadding = padding,
            )
        }
    }

    @Test
    fun calendar() = capture("5_calendar") {
        Root("Calendar") { padding ->
            CalendarContent(
                state = CalendarUiState(month = YearMonth.of(2026, 9), today = TODAY, data = september, firstMonth = YearMonth.of(2025, 1)),
                coverOf = { if (it.hasCover) coverFor(it.bookId) else null },
                contentPadding = padding,
            )
        }
    }

    /** A book's highlights and notes, by chapter. */
    @Test
    fun notes() = capture("6_notes") {
        BookHighlightsContent(
            state = BookHighlightsUiState(
                bookId = PRIDE, title = "Pride and Prejudice", author = "Jane Austen", readerFiles = mapOf(11L to "epub"),
                loading = false, items = notes, stats = prideHighlights, liked = setOf(3L), endReached = false,
            ),
            actions = HighlightActions(onOpenReader = {}, onLike = {}, onSaveNote = { _, _ -> }, onColor = { _, _ -> }, onStyle = { _, _ -> }),
        )
    }

    // --- frame -----------------------------------------------------------------------------------

    /** A root screen under the shell's toolbar (drawer toggle, title, Search). */
    @Composable
    private fun Root(title: String, content: @Composable (PaddingValues) -> Unit) {
        RootFrame(
            title = title,
            search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
            onOpenDrawer = {},
            onOpenSearch = {},
            onSubmitSearch = {},
            onCloseSearch = {},
            content = content,
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, content: @Composable () -> Unit) = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/store/$name.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = WIDTH_DP, heightDp = HEIGHT_DP)
            uiMode(Configuration.UI_MODE_NIGHT_NO)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                WithFakeCovers { Phone(content) }
            }
        }
    }

    /**
     * The screen as a phone shows it edge to edge: the status bar over the toolbar's colour (the
     * toolbar takes no inset here, so the bar sits above it), and the gesture handle over the content.
     */
    @Composable
    private fun Phone(content: @Composable () -> Unit) {
        val colors = OttershelfTheme.colors
        Column(Modifier.fillMaxSize()) {
            StatusBar(colors.shellSurface.compositeOver(colors.background), colors.foreground)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                content()
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .size(width = 108.dp, height = 4.dp)
                        .background(colors.foreground.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
                )
            }
        }
    }

    @Composable
    private fun StatusBar(background: Color, ink: Color) {
        Row(
            Modifier.fillMaxWidth().height(STATUS_BAR_DP.dp).background(background).padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("10:24", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Canvas(Modifier.size(16.dp)) {
                // Wi-Fi: a quarter circle, point down.
                val r = size.width * 0.72f
                drawArc(ink, startAngle = 225f, sweepAngle = 90f, useCenter = true, topLeft = Offset(size.width / 2 - r, size.height * 0.9f - r), size = Size(2 * r, 2 * r))
            }
            Canvas(Modifier.size(15.dp)) {
                // Signal: a right triangle.
                val path = Path().apply {
                    moveTo(size.width * 0.08f, size.height * 0.92f)
                    lineTo(size.width * 0.92f, size.height * 0.92f)
                    lineTo(size.width * 0.92f, size.height * 0.08f)
                    close()
                }
                drawPath(path, ink)
            }
            Canvas(Modifier.width(9.dp).height(16.dp)) {
                // Battery, nearly full.
                val nub = size.height * 0.12f
                drawRect(ink, topLeft = Offset(size.width * 0.3f, 0f), size = Size(size.width * 0.4f, nub))
                drawRoundRect(ink, topLeft = Offset(0f, nub), size = Size(size.width, size.height - nub), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }
    }

    // --- data ------------------------------------------------------------------------------------

    private companion object {
        const val WIDTH_DP = 360
        const val HEIGHT_DP = 640
        const val STATUS_BAR_DP = 28

        /** The grid's width: the screen less the grid's 6dp each side. */
        const val GRID_WIDTH = WIDTH_DP - 12f

        val TODAY: LocalDate = LocalDate.of(2026, 9, 25)

        const val PRIDE = 1L
        const val MOBY = 2L
        const val EXPECTATIONS = 3L
        const val FRANKENSTEIN = 5L
        const val HOLMES = 7L
        const val ALICE = 8L

        private val epub = listOf(BookFile(0, "epub", role = "primary"))

        /** Each book's made-up cover (a palette of FakeCovers), or null for the app's generated one with the title. */
        private val covers = mutableMapOf<Long, Int?>()

        fun coverFor(id: Long): Any? = covers[id]?.let(::fakeCover)

        private fun book(id: Long, title: String, author: String, cover: Int?, progress: Double? = null, status: String? = null, rating: Int? = null, series: String? = null, index: String? = null): BookCard {
            covers[id] = cover
            return BookCard(
                id = id,
                title = title,
                authors = listOf(author),
                seriesName = series,
                seriesIndex = index,
                readingProgress = progress,
                readStatus = status?.let { ReadStatusInfo(it) },
                hasCover = cover != null,
                files = epub,
                rating = rating,
            )
        }

        /** All books, newest first: the grid shows the first twelve. */
        val library = listOf(
            book(13, "Emma", "Jane Austen", 1, 100.0, "read", rating = 5),
            book(MOBY, "Moby-Dick", "Herman Melville", 2, 18.0, "reading"),
            book(6, "Dracula", "Bram Stoker", 4, status = "want_to_read"),
            book(4, "Jane Eyre", "Charlotte Brontë", null, 100.0, "read", rating = 5),
            book(FRANKENSTEIN, "Frankenstein", "Mary Shelley", 5, 100.0, "read", rating = 4),
            book(12, "Middlemarch", "George Eliot", 3, status = "want_to_read"),
            book(17, "Little Women", "Louisa May Alcott", 0),
            book(19, "Oliver Twist", "Charles Dickens", null, 100.0, "read"),
            book(20, "Persuasion", "Jane Austen", 1, 44.0, "on_hold"),
            book(21, "Kidnapped", "R. L. Stevenson", 3),
            book(22, "Walden", "H. D. Thoreau", null, status = "want_to_read"),
            book(23, "Cranford", "Elizabeth Gaskell", 5, 100.0, "read"),
            book(PRIDE, "Pride and Prejudice", "Jane Austen", 0, 62.0, "reading"),
            book(EXPECTATIONS, "Great Expectations", "Charles Dickens", 3, 35.0, "reading"),
            book(HOLMES, "The Adventures of Sherlock Holmes", "Arthur Conan Doyle", 1, 100.0, "read", series = "Sherlock Holmes", index = "3"),
            book(ALICE, "Alice's Adventures in Wonderland", "Lewis Carroll", 4, 100.0, "read"),
            book(9, "The Time Machine", "H. G. Wells", null),
            book(10, "Adventures of Huckleberry Finn", "Mark Twain", 2),
            book(11, "Wuthering Heights", "Emily Brontë", 5, 100.0, "read"),
            book(14, "A Tale of Two Cities", "Charles Dickens", 0),
            book(15, "The War of the Worlds", "H. G. Wells", null, 100.0, "read"),
            book(16, "Treasure Island", "R. L. Stevenson", 2),
            book(18, "The Picture of Dorian Gray", "Oscar Wilde", 4, 100.0, "read"),
        )

        private fun byId(id: Long) = library.first { it.id == id }

        private fun reading(id: Long, progress: Double) = byId(id).let { card ->
            ReadingRow(CurrentlyReadingBook(id, card.title, card.authors, progress, card.hasCover, id * 10, "epub"), coverFor(id))
        }

        private val shelves = listOf(
            ShelfConfig("1", "continue-reading"),
            ShelfConfig("2", "recently-added"),
        )

        val dashboard = HomeUiState(
            layout = DashboardLayout(
                reorderWidgets(
                    DEFAULT_WIDGETS.map { it.copy(enabled = it.type in setOf(WidgetType.CURRENTLY_READING, WidgetType.READING_STREAK, WidgetType.READING_GOAL, WidgetType.HIGHLIGHT_OF_THE_DAY, WidgetType.READING_RHYTHM)) },
                    listOf(WidgetType.CURRENTLY_READING, WidgetType.READING_STREAK, WidgetType.READING_GOAL, WidgetType.HIGHLIGHT_OF_THE_DAY, WidgetType.READING_RHYTHM),
                ),
                null,
            ),
            reading = listOf(reading(PRIDE, 62.0), reading(MOBY, 18.0)),
            streak = ReadingStreak(currentStreak = 12, longestStreak = 21, lastSevenDays = listOf(true, true, true, true, true, true, true)),
            widgetData = mapOf(
                WidgetType.READING_GOAL to ReadingGoalData(goalBooks = 24, completedBooks = 17, year = 2026),
                WidgetType.HIGHLIGHT_OF_THE_DAY to HighlightData(
                    text = "I declare after all there is no enjoyment like reading!",
                    bookTitle = "Pride and Prejudice", bookId = PRIDE, hasCover = true, chapterTitle = "Chapter 11",
                ),
                WidgetType.READING_RHYTHM to ReadingRhythmData(
                    days = listOf(1800, 2400, 3600, 900, 1500, 2700, 5400, 1200, 0, 2100, 3000, 4200, 1800, 2400).mapIndexed { i, s -> RhythmDay("2026-09-%02d".format(12 + i), s.toDouble()) },
                    consistencyPercent = 93.0, avgSecondsPerDay = 2357.0, activeDays = 13, totalDays = 14,
                ),
            ),
            shelfConfigs = shelves,
            shelfBooks = mapOf(
                shelves[0].key to listOf(PRIDE, MOBY, EXPECTATIONS, 20L).map { ShelfItem(byId(it), coverFor(it)) },
                shelves[1].key to library.take(8).map { ShelfItem(it, coverFor(it.id)) },
            ),
            order = listOf("w:currently-reading", "w:reading-streak", "s:2", "w:reading-goal", "w:highlight-of-the-day", "s:1", "w:reading-rhythm"),
        )

        val pride = BookDetail(
            id = PRIDE,
            libraryName = "Books",
            title = "Pride and Prejudice",
            description = "<p>Mr Bennet of Longbourn has five daughters and no son, so his estate will pass to a cousin. When the " +
                "wealthy Mr Bingley takes the house nearby, bringing his proud friend Mr Darcy, Mrs Bennet sees a husband " +
                "for each of her girls.</p><p>Elizabeth, the second and cleverest, takes an instant dislike to Darcy, " +
                "and a comedy of first impressions, pride and prejudice begins.</p>",
            authors = listOf(AuthorRef(1, "Jane Austen")),
            publisher = "T. Egerton",
            publishedYear = 1813,
            pageCount = 432,
            language = "en",
            coverSource = "embedded",
            files = listOf(BookFile(11, "epub", "primary", 1_243_200, "Pride and Prejudice.epub")),
            readStatus = ReadStatusInfo("reading", startedAt = "2026-09-14T00:00:00.000Z"),
        )

        private fun session(id: Long, start: String, minutes: Int, delta: Double, end: Double, source: String = "android") = BookSession(
            id = id, startedAt = start, endedAt = start, durationSeconds = minutes * 60, progressDelta = delta, endProgress = end, source = source,
        )

        val prideTracking = BookTrackingUiState(
            loading = false,
            sessions = listOf(
                session(5, "2026-09-24T19:40:00Z", 52, 9.5, 62.0),
                session(4, "2026-09-22T07:15:00Z", 28, 6.0, 52.5),
                session(3, "2026-09-19T20:05:00Z", 64, 14.0, 46.5),
                session(2, "2026-09-17T21:10:00Z", 41, 12.5, 32.5, source = "web"),
                session(1, "2026-09-14T20:30:00Z", 55, 20.0, 20.0),
            ),
            sessionsTotal = 5,
            sessionsPage = 1,
            stats = BookSessionStats(totalSessions = 5, totalSeconds = 240L * 60, latestEndProgress = 62.0, paceProgressDelta = 62.0, paceDurationSeconds = 240L * 60),
            attempts = listOf(
                ReadingAttempt(id = 2, bookId = PRIDE, startedOn = "2026-09-14"),
                ReadingAttempt(id = 1, bookId = PRIDE, startedOn = "2021-06-02", endedOn = "2021-06-29", outcome = AttemptOutcome.COMPLETED),
            ),
            settings = AppSettings(pageTotals = mapOf(PRIDE to 432)),
            canRate = true,
            rating = 5,
        )

        /** A believable reading pattern: evenings, more at weekends, a few days off. */
        private fun secondsOn(date: LocalDate): Double {
            val n = date.toEpochDay()
            if (n % 9 == 4L) return 0.0
            val weekend = date.dayOfWeek.value >= 6
            return (1200 + 900 * abs(sin(n * 0.7)) + if (weekend) 1500 else 0).toDouble()
        }

        private val readingDaily = (0 until 180).map { TODAY.minusDays(it.toLong()) }.map { DailyReadingStat(it.toString(), secondsOn(it), eventsCount = 1.0) }

        private val overview = Overview(
            timezone = "UTC",
            snapshot = Snapshot(
                today = Duration(readingSeconds = 1500.0),
                lastSevenDays = Duration(readingSeconds = 5.3 * 3600),
                previousSevenDays = Duration(readingSeconds = 4.1 * 3600),
                currentStreak = 12,
                longestStreak = 21,
                completedBooksYtd = 17,
            ),
            dailyActivity = DailyActivity((0 until 14).map { TODAY.minusDays(it.toLong()) }.map { DayPoint(it.toString(), readingSeconds = secondsOn(it)) }),
            goal = OverviewGoal(
                year = 2026, goalBooks = 24, completedBooks = 17, projectedBooks = 22.7, status = "on_track",
                points = (1..12).map { m -> OverviewGoalPoint(m, listOf(2, 4, 5, 7, 8, 10, 12, 14, 17, 17, 17, 17)[m - 1].toDouble(), 2.0 * m) },
            ),
            rhythm = Rhythm(
                sessionsCount = 240,
                weekdays = listOf(2400, 1500, 1300, 1400, 1250, 1600, 2800).mapIndexed { i, s -> RhythmWeekday(i, s.toDouble(), 30) },
                favoriteDayOfWeek = 6,
                peakHour = 21,
            ),
        )

        val reading = ReadingCharts(
            overview = Load.Ready(overview),
            daily = Load.Ready(StatsMath.dailySeries(readingDaily, TODAY, 30)),
            heatmap = Load.Ready(StatsMath.heatmapYear(readingDaily, 2026, TODAY)),
            peakHours = Load.Ready((0 until 24).map { h -> PeakHourStat(h, if (h in 7..8) 3000.0 else if (h in 19..23) 9000.0 - abs(21 - h) * 2000 else if (h in 12..13) 1500.0 else 0.0, 4.0) }),
            weekdays = Load.Ready(WeekdayData(listOf(25.0, 21.0, 22.0, 24.0, 20.0, 38.0, 45.0), List(7) { 40000.0 }, List(7) { 12 }, 84)),
            completions = Load.Ready(StatsMath.months((0 until 30).map { i -> YearMonth.from(TODAY).minusMonths(i.toLong()) }.map { MonthCount(it.year, it.monthValue, ((it.monthValue * 7 + it.year) % 4).toDouble()) }, YearMonth.of(2024, 4), YearMonth.from(TODAY))),
            trajectory = Load.Ready(Trajectory((0 until 12).map { i -> YearMonth.from(TODAY).minusMonths(11L - i) }.mapIndexed { i, m -> GoalTrajectoryPoint(m.year, m.monthValue, (i * 1.6 + 1).toInt().toDouble(), 2.0 * (i + 1)) }, 24)),
            genres = Load.Ready(listOf("Classics" to 62.0, "Gothic" to 41.0, "Adventure" to 30.0, "Mystery" to 18.0, "Poetry" to 4.0).map { (g, h) -> GenreReadingTime(g, h * 3600) }),
            pace = Load.Ready((0 until 80).map { i -> PacePoint(600.0 + (i * 137 % 5400), 0.5 + (i * 37 % 60) / 10.0) }),
            archetypes = Load.Ready((0 until 70).map { i -> ArchetypePoint(if (i % 4 == 0) 7.5 + i % 3 * 0.3 else 19.0 + (i * 13 % 50) / 10.0, 10.0 + i * 29 % 80, i % 7) }),
            funnel = Load.Ready(ProgressFunnelComparison(90, ProgressFunnel(18.0, 15.0, 13.0, 11.0, 9.0), ProgressFunnel(14.0, 11.0, 9.0, 7.0, 6.0))),
            latency = Load.Ready(CompletionLatency(22.0, 19.0, 34.0, 71.0, listOf(4, 7, 6, 3, 1, 1, 0).mapIndexed { i, n -> LatencyBucket("b$i", count = n.toDouble()) })),
        )

        private fun day(id: Long, seconds: Long) = byId(id).let { CalendarBook(id, it.title ?: "", it.hasCover, null, seconds) }

        val september = CalendarMonth(
            month = "2026-09",
            days = buildList {
                listOf(1, 2, 3, 4, 5).forEach { add(CalendarDay("2026-09-%02d".format(it), 2400, 1, listOf(day(FRANKENSTEIN, 2400)))) }
                add(CalendarDay("2026-09-06", 3900, 2, listOf(day(FRANKENSTEIN, 2400), day(ALICE, 1500))))
                listOf(7, 8, 10).forEach { add(CalendarDay("2026-09-%02d".format(it), 1800, 1, listOf(day(ALICE, 1800)))) }
                add(CalendarDay("2026-09-11", 2700, 1, listOf(day(HOLMES, 2700))))
                add(CalendarDay("2026-09-12", 4800, 2, listOf(day(HOLMES, 3000), day(ALICE, 1800))))
                add(CalendarDay("2026-09-13", 3600, 1, listOf(day(HOLMES, 3600))))
                add(CalendarDay("2026-09-14", 3300, 2, listOf(day(PRIDE, 1800), day(HOLMES, 1500))))
                listOf(15, 16, 18).forEach { add(CalendarDay("2026-09-$it", 2400, 1, listOf(day(PRIDE, 2400)))) }
                add(CalendarDay("2026-09-17", 3600, 2, listOf(day(PRIDE, 2400), day(EXPECTATIONS, 1200))))
                add(CalendarDay("2026-09-19", 5700, 3, listOf(day(PRIDE, 3000), day(MOBY, 1800), day(EXPECTATIONS, 900))))
                add(CalendarDay("2026-09-20", 4200, 2, listOf(day(MOBY, 2400), day(PRIDE, 1800))))
                listOf(21, 23).forEach { add(CalendarDay("2026-09-$it", 2100, 1, listOf(day(PRIDE, 2100)))) }
                add(CalendarDay("2026-09-22", 3300, 2, listOf(day(MOBY, 1800), day(PRIDE, 1500))))
                add(CalendarDay("2026-09-24", 3900, 2, listOf(day(PRIDE, 3100), day(EXPECTATIONS, 800))))
                add(CalendarDay("2026-09-25", 1500, 1, listOf(day(MOBY, 1500))))
            },
            availableYears = listOf(2025, 2026),
        )

        private fun highlight(id: Long, chapter: Int, text: String, color: String, note: String? = null, style: String = "highlight", created: String) = Annotation(
            id = id, bookId = PRIDE, cfi = "epubcfi(/6/${2 * chapter + 2}!/4/2,/1:0,/1:20)", jumpFileId = 11, jumpFileFormat = "epub",
            text = text, color = color, style = style, note = note, chapterTitle = "Chapter $chapter",
            createdAt = created, bookTitle = "Pride and Prejudice", author = "Jane Austen",
        )

        val notes = listOf(
            highlight(
                1, 1, "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
                "#FACC15", note = "The famous first line, and a joke.", created = "2026-09-14T20:40:00Z",
            ),
            highlight(2, 3, "She is tolerable; but not handsome enough to tempt me.", "#F472B6", created = "2026-09-15T21:05:00Z"),
            highlight(3, 5, "Vanity and pride are different things, though the words are often used synonymously.", "#38BDF8", style = "underline", created = "2026-09-17T21:30:00Z"),
            highlight(4, 11, "I declare after all there is no enjoyment like reading!", "#4ADE80", created = "2026-09-21T20:15:00Z"),
        )

        val prideHighlights = BookAnnotationStats(
            totalHighlights = 23,
            colorBreakdown = listOf(ColorCount("#FACC15", 11), ColorCount("#38BDF8", 6), ColorCount("#F472B6", 4), ColorCount("#4ADE80", 2)),
            chaptersWithHighlights = 14,
            highlightsWithNotes = 8,
            chapterBreakdown = listOf(ChapterStat("Chapter 1", 1), ChapterStat("Chapter 3", 1), ChapterStat("Chapter 5", 1), ChapterStat("Chapter 11", 1)),
        )
    }
}
