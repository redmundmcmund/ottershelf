package io.github.ottershelf.feature.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.CurrentlyReadingBook
import io.github.ottershelf.core.model.Library
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.ReadingStreak
import io.github.ottershelf.core.model.SmartScope
import io.github.ottershelf.feature.home.model.CustomiseDraft
import io.github.ottershelf.feature.home.model.CustomiseTab
import io.github.ottershelf.feature.home.model.DEFAULT_SHELVES
import io.github.ottershelf.feature.home.model.DEFAULT_WIDGETS
import io.github.ottershelf.feature.home.model.DashboardLayout
import io.github.ottershelf.feature.home.model.DiversityScoreData
import io.github.ottershelf.feature.home.model.HighlightData
import io.github.ottershelf.feature.home.model.LibraryOverviewData
import io.github.ottershelf.feature.home.model.LongWaitData
import io.github.ottershelf.feature.home.model.MonthlyChallengeData
import io.github.ottershelf.feature.home.model.NeglectedGem
import io.github.ottershelf.feature.home.model.NeglectedGemsData
import io.github.ottershelf.feature.home.model.ReadingDnaData
import io.github.ottershelf.feature.home.model.ReadingGoalData
import io.github.ottershelf.feature.home.model.ReadingRhythmData
import io.github.ottershelf.feature.home.model.RhythmDay
import io.github.ottershelf.feature.home.model.ShelfConfig
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.feature.home.model.YearProjectionData
import io.github.ottershelf.feature.home.model.reorderWidgets
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Dashboard in dark theme, in the shell's toolbar: every widget and the shelves, portrait
 * (tall) and landscape; and the Customise sheet. home/dashboard_dark.png, home/dashboard_land_dark.png,
 * home/customise_dark.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class HomeScreenshotTest {

    @Test
    fun dashboard() = captureDark("home/dashboard_dark", PHONE_WIDTH_DP, 3860) { Dashboard() }

    @Test
    fun landscape() = captureDark("home/dashboard_land_dark", PHONE_HEIGHT_DP, 2380) { Dashboard() }

    /** Widgets and shelves in the user's order; Currently Reading with one book shrinks to it. */
    @Test
    fun arranged() = captureDark("home/dashboard_arranged_dark", PHONE_WIDTH_DP, 2150) { Dashboard(arrangedState) }

    /** Arranging: the cards as their titles with handles, Done in the toolbar. */
    @Test
    fun arranging() = captureDark("home/arrange_dark", PHONE_WIDTH_DP, PHONE_HEIGHT_DP) { Dashboard(arrangedState.copy(editing = true)) }

    @Test
    fun customise() = captureDark("home/customise_dark", PHONE_WIDTH_DP, 1500) {
        WithFakeCovers {
            Surface(Modifier.fillMaxSize(), color = OttershelfTheme.colors.card) {
                CustomiseContent(
                    state = CustomiseState(
                        draft = CustomiseDraft(
                            widgets = DEFAULT_WIDGETS,
                            shelves = DEFAULT_SHELVES.take(3) + ShelfConfig("8", "smart-scope", label = "Cosy mysteries", rows = 2, limit = 10, smartScopeId = 4),
                            libraryIds = listOf(1),
                            libraryScopeOpen = true,
                        ),
                        libraries = listOf(Library(1, name = "Books"), Library(2, name = "Comics")),
                        smartScopes = listOf(SmartScope(4, "Cosy mysteries")),
                    ),
                    onEdit = {},
                    onSave = {},
                    onCancel = {},
                    modifier = Modifier.fillMaxSize().padding(top = 16.dp),
                )
            }
        }
    }

    @Test
    fun customiseWidgets() = captureDark("home/customise_widgets_dark", PHONE_WIDTH_DP, PHONE_HEIGHT_DP) {
        WithFakeCovers {
            Surface(Modifier.fillMaxSize(), color = OttershelfTheme.colors.card) {
                CustomiseContent(
                    state = CustomiseState(
                        draft = CustomiseDraft(widgets = DEFAULT_WIDGETS, shelves = DEFAULT_SHELVES, libraryIds = null, tab = CustomiseTab.WIDGETS),
                        libraries = listOf(Library(1, name = "Books")),
                    ),
                    onEdit = {},
                    onSave = {},
                    onCancel = {},
                    modifier = Modifier.fillMaxSize().padding(top = 16.dp),
                )
            }
        }
    }

    @Composable
    private fun Dashboard(state: HomeUiState = sampleState) {
        WithFakeCovers {
            RootFrame(
                title = "Dashboard",
                search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
                onOpenDrawer = {},
                onOpenSearch = {},
                onSubmitSearch = {},
                onCloseSearch = {},
            ) {
                TopBarActions {
                    if (state.editing) {
                        TextButton(onClick = {}) { Text("Done", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = OttershelfTheme.colors.primary) }
                    } else {
                        IconButton(onClick = {}) { LucideIcon("Settings2", contentDescription = null, tint = LocalContentColor.current, size = 22.dp) }
                    }
                }
                HomeContent(state, it, widgetCover = { id, has -> if (has) fakeCover(id.toInt()) else null }, rhythmDay = 11)
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDark(name: String, width: Int, height: Int, content: @Composable () -> Unit) = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/$name.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = width, heightDp = height)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    private val shelfBooks = (1..8).map { i ->
        ShelfItem(
            BookCard(
                id = 100L + i,
                title = "Shelf book $i",
                authors = listOf("Author $i"),
                seriesIndex = if (i % 3 == 0) "$i" else null,
                readingProgress = if (i == 2) 40.0 else null,
                readStatus = if (i == 2) ReadStatusInfo("reading") else if (i == 4) ReadStatusInfo("read") else null,
                hasCover = i != 5,
                files = listOf(BookFile(id = i.toLong(), format = if (i == 3) "pdf" else "epub", role = "primary")),
            ),
            if (i != 5) fakeCover(i + 2) else null,
        )
    }

    private val shelves = listOf(
        ShelfConfig("2", "recently-added"),
        ShelfConfig("3", "random", rows = 2),
        ShelfConfig("1", "continue-reading"),
        ShelfConfig("6", "want-to-read"),
    )

    private val sampleState = HomeUiState(
        layout = DashboardLayout(DEFAULT_WIDGETS.map { it.copy(enabled = true) }, null),
        reading = listOf(
            ReadingRow(CurrentlyReadingBook(1, "The Lost World", listOf("Arthur Conan Doyle"), 62.0, true, 11, "epub"), fakeCover(1)),
            ReadingRow(CurrentlyReadingBook(2, "Frankenstein", listOf("Mary Shelley"), 18.0, true, 12, "epub"), fakeCover(2)),
            ReadingRow(CurrentlyReadingBook(3, "A Scanned Manual", listOf("Anon"), 5.0, false, 13, "pdf"), null),
        ),
        streak = ReadingStreak(currentStreak = 4, longestStreak = 12, lastSevenDays = listOf(false, true, false, true, true, true, true)),
        widgetData = mapOf(
            WidgetType.READING_GOAL to ReadingGoalData(goalBooks = 24, completedBooks = 17, year = 2026),
            WidgetType.READING_DNA to ReadingDnaData(
                archetype = "Steady Habit Reader", lengthScore = 62.0, varietyScore = 48.0, rhythmScore = 81.0, timeScore = 30.0, speedScore = 55.0,
                lengthLabel = "Chunky reads", varietyLabel = "Balanced", rhythmLabel = "Daily ritual", timeLabel = "Night owl", speedLabel = "Steady", booksAnalyzed = 42,
            ),
            WidgetType.MONTHLY_CHALLENGE to MonthlyChallengeData("new-author", "New Voices", "Read 2 books by authors you haven't read before", 1.0, 2.0),
            WidgetType.HIGHLIGHT_OF_THE_DAY to HighlightData(
                text = "Life, although it may only be an accumulation of anguish, is dear to me, and I will defend it.",
                bookTitle = "Frankenstein", bookId = 2, hasCover = true, chapterTitle = "Chapter 10",
            ),
            WidgetType.NEGLECTED_GEMS to NeglectedGemsData(listOf(NeglectedGem(21, "Far from the Madding Crowd", true, 5.0, 412, "Literary"), NeglectedGem(22, "Emma", false, 4.0, 90))),
            WidgetType.READING_RHYTHM to ReadingRhythmData(
                days = listOf(0, 1200, 3600, 0, 900, 2400, 5400, 0, 0, 1800, 2700, 4200, 600, 3000).mapIndexed { i, s -> RhythmDay("2026-09-%02d".format(12 + i), s.toDouble()) },
                consistencyPercent = 71.0, avgSecondsPerDay = 1843.0, activeDays = 10, totalDays = 14,
            ),
            WidgetType.DIVERSITY_SCORE to DiversityScoreData(64.0, "Explorer", 70.0, 82.0, 40.0, 25.0, 38),
            WidgetType.LIBRARY_OVERVIEW to LibraryOverviewData(1284, 612, 143, 48.3 * 1024 * 1024 * 1024, 57),
            WidgetType.YEAR_PROJECTION to YearProjectionData(23.0, 7420.0, 186.5, 17, 97, "up"),
            WidgetType.LONG_WAIT to LongWaitData(31, "Middlemarch", true, "2021-03-02", 1668, 880, "Classics", 5, "epub"),
        ),
        shelfConfigs = shelves,
        shelfBooks = mapOf(
            shelves[0].key to shelfBooks,
            shelves[1].key to shelfBooks.reversed(),
            shelves[2].key to shelfBooks.take(2),
            shelves[3].key to emptyList(),
        ),
    )

    /**
     * The user's own order: Currently Reading (one book) first, then Recently Added, the streak and the
     * goal side by side, Continue Reading, then the rest; most widgets off.
     */
    private val arrangedState = sampleState.copy(
        layout = DashboardLayout(
            reorderWidgets(
                DEFAULT_WIDGETS.map { it.copy(enabled = it.type in setOf(WidgetType.CURRENTLY_READING, WidgetType.READING_STREAK, WidgetType.READING_GOAL, WidgetType.YEAR_PROJECTION)) },
                listOf(WidgetType.CURRENTLY_READING, WidgetType.READING_STREAK, WidgetType.READING_GOAL),
            ),
            null,
        ),
        reading = sampleState.reading!!.take(1),
        order = listOf("w:currently-reading", "s:2", "w:reading-streak", "w:reading-goal", "s:1"),
    )
}
