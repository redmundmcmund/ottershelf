package io.github.ottershelf.feature.calendar

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The calendar screens, dark: calendar/{month,day,goals}_dark.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class CalendarScreenshotTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun book(id: Long, title: String, seconds: Long, cover: Boolean = true) = CalendarBook(id, title, cover, null, seconds)

    private val lostWorld = book(1, "The Lost World", 2400)
    private val frankenstein = book(2, "Frankenstein", 1500)
    private val paper = book(3, "A Paper Book", 900, cover = false)
    private val dracula = book(4, "Dracula", 600)
    private val treasureIsland = book(5, "Treasure Island", 300)

    private val september = CalendarMonth(
        month = "2026-09",
        days = buildList {
            listOf(1, 2, 3, 4, 7, 8, 9).forEach { add(CalendarDay("2026-09-%02d".format(it), 2400, 1, listOf(lostWorld))) }
            add(CalendarDay("2026-09-10", 3900, 2, listOf(lostWorld, frankenstein)))
            add(CalendarDay("2026-09-12", 1500, 1, listOf(frankenstein)))
            add(CalendarDay("2026-09-13", 4800, 3, listOf(frankenstein, paper, dracula)))
            listOf(15, 16, 17, 19).forEach { add(CalendarDay("2026-09-$it", 1500, 1, listOf(frankenstein))) }
            add(CalendarDay("2026-09-20", 5700, 5, listOf(frankenstein, dracula, treasureIsland, paper, lostWorld)))
            add(CalendarDay("2026-09-22", 900, 1, listOf(paper)))
            add(CalendarDay("2026-09-23", 600, 1, emptyList()))
            add(CalendarDay("2026-09-24", 2100, 2, listOf(dracula, paper)))
            add(CalendarDay("2026-09-25", 1500, 1, listOf(dracula)))
        },
        availableYears = listOf(2025, 2026),
    )

    @Test
    fun month() = captureDark("calendar/month") {
        RootFrame(
            title = "Calendar",
            search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
            onOpenDrawer = {},
            onOpenSearch = {},
            onSubmitSearch = {},
            onCloseSearch = {},
        ) { padding ->
            CalendarContent(
                state = CalendarUiState(month = YearMonth.of(2026, 9), today = today, data = september, firstMonth = YearMonth.of(2025, 1)),
                coverOf = { if (it.hasCover) fakeCover(it.bookId.toInt()) else null },
                contentPadding = padding,
            )
        }
    }

    @Test
    fun day() = captureDark("calendar/day") {
        val zone = ZoneId.of("Europe/Dublin")
        fun at(h: Int, m: Int) = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        DayContent(
            DayUiState(
                date = LocalDate.of(2026, 9, 24),
                loading = false,
                totalSeconds = 2100,
                zone = zone,
                goalMinutes = 30,
                sessions = listOf(
                    DaySessionRow(1, 4, "Dracula", fakeCover(4), at(7, 40), at(7, 58), 1080, 4.2, pages = 26),
                    DaySessionRow(2, 3, "A Paper Book", null, at(13, 5), at(13, 20), 900, 3.5),
                    DaySessionRow(3, 4, "Dracula", fakeCover(4), at(22, 30), at(22, 32), 120, null),
                ),
            ),
        )
    }

    @Test
    fun goals() = captureDark("calendar/goals") {
        ReadingGoalsContent(
            GoalsUiState(
                today = today,
                loading = false,
                yearlyGoal = 24,
                completed = 15,
                projected = 20.4,
                dailyGoal = 30,
                todaySeconds = 18 * 60,
                currentStreak = 6,
                longestStreak = 21,
                lastSevenSeconds = 4 * 3600 + 20 * 60,
                previousSevenSeconds = 3 * 3600 + 5 * 60,
            ),
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDark(name: String, content: @Composable () -> Unit) = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
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
