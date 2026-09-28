package io.github.ottershelf.feature.timer

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.model.CurrentlyReadingBook
import io.github.ottershelf.core.model.ReadingStreak
import io.github.ottershelf.core.settings.ProgressUnit
import io.github.ottershelf.core.settings.TimerMode
import io.github.ottershelf.core.settings.TimerPrefs
import io.github.ottershelf.core.tracking.ActiveTimer
import io.github.ottershelf.core.tracking.FinishedTimer
import io.github.ottershelf.feature.home.HomeContent
import io.github.ottershelf.feature.home.HomeUiState
import io.github.ottershelf.feature.home.ReadingRow
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

/** The timer screens, dark: timer/{setup,running,save,result,dashboard}_dark.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class TimerScreenshotTest {

    private val now = 1_790_000_000_000L
    private val book = TimerBook(
        bookId = 1, title = "The Lost World", authors = "Arthur Conan Doyle", cover = fakeCover(1),
        fileId = 11, pageTotal = 464, serverPageCount = 300, percent = 28.0, page = 130,
    )
    private val running = ActiveTimer(
        sessionId = "s1", bookId = 1, fileId = 11, title = book.title!!,
        startedAtMs = now - 754_000, accumulatedMs = 0, runningSinceMs = now - 754_000, startProgress = 28.02,
    )

    @Test
    fun setup() = captureDark("timer/setup") {
        TimerContent(TimerUiState(bookId = 1, loaded = true, book = book, prefs = TimerPrefs(mode = TimerMode.COUNTDOWN, countdownMinutes = 30)), now)
    }

    @Test
    fun runningTimer() = captureDark("timer/running") {
        TimerContent(TimerUiState(bookId = 1, loaded = true, book = book, active = running), now)
    }

    @Test
    fun setupLandscape() = captureDark("timer/setup_land", landscape = true) {
        TimerContent(TimerUiState(bookId = 1, loaded = true, book = book, prefs = TimerPrefs(mode = TimerMode.COUNTDOWN, countdownMinutes = 30)), now)
    }

    @Test
    fun runningLandscape() = captureDark("timer/running_land", landscape = true) {
        TimerContent(TimerUiState(bookId = 1, loaded = true, book = book, active = running), now)
    }

    @Test
    fun save() = captureDark("timer/save") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SaveSessionForm(
                form = SaveForm(activeMs = 754_000, title = book.title, startPage = 130, total = 464, startPercent = 28.02, unit = ProgressUnit.PAGE),
                saving = false,
                error = null,
                onSave = { _, _ -> },
                onCancel = {},
                onDiscard = {},
                modifier = Modifier
                    .background(OttershelfTheme.colors.card, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .padding(20.dp),
            )
        }
    }

    @Test
    fun result() = captureDark("timer/result") {
        TimerResultContent(
            TimerResultUiState(
                loaded = true,
                summary = SessionSummary(
                    bookId = 1, sessionId = "s1", title = book.title, authors = book.authors, activeSeconds = 32 * 60,
                    outcome = SaveOutcome.Sent, total = 464, startPage = 130, endPage = 152, startPercent = 28.02, endPercent = 32.76,
                    bookPagesPerHour = 38.5, todaySeconds = 41 * 60, dailyGoalMinutes = 30,
                ),
            ),
        )
    }

    @Test
    fun dashboard() = captureDark("timer/dashboard") {
        RootFrame(
            title = "Dashboard",
            search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
            onOpenDrawer = {},
            onOpenSearch = {},
            onSubmitSearch = {},
            onCloseSearch = {},
        ) { padding ->
            HomeContent(
                HomeUiState(
                    reading = listOf(
                        ReadingRow(CurrentlyReadingBook(1, "The Lost World", listOf("Arthur Conan Doyle"), 62.0, true, 11, "epub"), fakeCover(1)),
                        ReadingRow(CurrentlyReadingBook(2, "Frankenstein", listOf("Mary Shelley"), 18.0, true, 12, "epub"), fakeCover(2)),
                        ReadingRow(CurrentlyReadingBook(3, "A Paper Book", listOf("Anon"), 5.0, false, 13, "pdf"), null),
                    ),
                    streak = ReadingStreak(currentStreak = 4, longestStreak = 12, lastSevenDays = listOf(false, true, false, true, true, true, true)),
                ),
                padding,
                timerBookId = 1,
                timerBar = {
                    // A running timer, and another book's stopped session still to be saved.
                    val unsaved = FinishedTimer("s0", 2, 12, "Frankenstein", now - 90_000_000, now - 88_000_000, 25 * 60_000L)
                    TimerBars(running, unsaved, now = { now }, onTimer = {}, onUnsaved = {})
                },
            )
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDark(name: String, landscape: Boolean = false, content: @Composable () -> Unit) = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            if (landscape) size(widthDp = PHONE_HEIGHT_DP, heightDp = PHONE_WIDTH_DP) else size(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize().fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                WithFakeCovers(content)
            }
        }
    }
}
