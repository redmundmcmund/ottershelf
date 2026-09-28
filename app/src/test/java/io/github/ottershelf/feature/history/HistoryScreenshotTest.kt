package io.github.ottershelf.feature.history

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
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The reading history, dark: history/list_dark.png (and history/empty_dark.png). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class HistoryScreenshotTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun book(id: Long, title: String, author: String, status: String, rating: Double? = null, vararg readings: Triple<String?, String?, String?>): List<HistoryEntry> =
        HistoryMath.entries(
            HistoryCard(id, title, listOf(author), hasCover = id != 4L, updatedAt = "2026-01-01T00:00:00Z", rating = rating, readStatus = ReadStatusInfo(status = status)),
            readings.mapIndexed { i, (start, end, outcome) -> ReadingAttempt(id = id * 10 + i, bookId = id, startedOn = start, endedOn = end, outcome = outcome) },
            today,
        )

    private val entries: List<HistoryEntry> = book(1, "The Lost World", "Arthur Conan Doyle", "rereading", 5.0,
        Triple("2019-04-02", "2019-04-20", "completed"), Triple("2026-09-12", null, null)) +
        book(2, "Frankenstein", "Mary Shelley", "read", 4.0, Triple("2026-09-03", "2026-09-21", "completed")) +
        book(3, "Dracula", "Bram Stoker", "abandoned", null, Triple("2026-08-01", "2026-08-12", "abandoned")) +
        book(4, "A Paper Book With a Rather Long Title That Wraps", "Someone", "read", 3.0, Triple("2026-08-14", "2026-08-30", "completed")) +
        book(5, "Treasure Island", "R. L. Stevenson", "read", null, Triple("2025-12-28", "2026-01-05", "completed")) +
        book(6, "Middlemarch", "George Eliot", "read", 4.0, Triple("2025-06-01", "2025-07-15", "completed"))

    private fun state(list: List<HistoryEntry>) = HistoryUiState(today = today, entries = list, groups = HistoryMath.groups(list, HistoryFilter.ALL))

    @Test
    fun list() = captureDark("history/list") { Frame(state(entries)) }

    @Test
    fun empty() = captureDark("history/empty") { Frame(state(emptyList())) }

    /** Each row's "Edit dates" (a pencil at its end), and "Add dates" on the readings without any, light and dark. */
    @Test
    fun rowActions() = captureLightAndDark("history/row_actions", heightDp = 1500) {
        val list = book(1, "The Lost World", "Arthur Conan Doyle", "rereading", 5.0,
            Triple("2019-04-02", "2019-04-20", "completed"), Triple("2026-09-12", null, null)) +
            book(2, "Frankenstein", "Mary Shelley", "read", 4.0, Triple("2026-09-03", "2026-09-21", "completed")) +
            book(3, "Dracula", "Bram Stoker", "abandoned", null, Triple("2026-08-01", "2026-08-12", "abandoned")) +
            book(7, "Middlemarch", "George Eliot", "read", null, Triple(null, null, "completed")) +
            book(8, "Far from the Madding Crowd", "Thomas Hardy", "read", 4.0, Triple("2024-02-01", null, "completed"))
        WithFakeCovers { Frame(state(list), onEditDates = {}) }
    }

    @Composable
    private fun Frame(state: HistoryUiState, onEditDates: ((HistoryEntry) -> Unit)? = null) {
        RootFrame(
            title = "History",
            search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
            onOpenDrawer = {},
            onOpenSearch = {},
            onSubmitSearch = {},
            onCloseSearch = {},
        ) { padding ->
            HistoryContent(state = state, coverOf = { if (it.hasCover) fakeCover(it.bookId.toInt()) else null }, onEditDates = onEditDates, contentPadding = padding)
        }
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
