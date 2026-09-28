package io.github.ottershelf.feature.book

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.BookSession
import io.github.ottershelf.core.tracking.BookSessionStats
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The book page's tracking part (tall, to show everything) and the finish celebration, dark.
 * Record with `--tests "*BookTrackingScreenshotTest"`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class BookTrackingScreenshotTest {

    @Test
    fun tracking() = captureDarkTall("book/tracking", heightDp = 2300) {
        WithFakeCovers {
            BookDetailContent(
                state = trackingBookState,
                tint = Color(0xFF3D7EA6),
                tracking = sampleTracking,
                today = LocalDate.of(2026, 9, 25),
            )
        }
    }

    @Test
    fun celebration() = captureDarkTall("book/celebration", heightDp = PHONE_HEIGHT_DP) {
        WithFakeCovers {
            FinishCelebrationContent(
                step = CelebrationStep.Done(days = 12),
                title = sampleBook.title,
                cover = fakeCover(1),
                tracking = sampleTracking,
                actions = BookTrackingActions.None,
            )
        }
    }
}

@OptIn(ExperimentalRoborazziApi::class)
private fun captureDarkTall(name: String, heightDp: Int, content: @Composable () -> Unit) {
    captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = heightDp)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

private fun s(id: Long, start: String, minutes: Int, delta: Double?, end: Double?, source: String = "android") = BookSession(
    id = id,
    startedAt = start,
    endedAt = start,
    durationSeconds = minutes * 60,
    progressDelta = delta,
    endProgress = end,
    source = source,
)

private val trackingBook = sampleBook.copy(
    readStatus = ReadStatusInfo("rereading", startedAt = "2026-09-14T00:00:00.000Z"),
    rating = 4,
    personalNote = "Even better the second time. The chapters in Utah are still the best part.",
)

private val trackingBookState = BookDetailUiState(
    bookId = 1,
    book = trackingBook,
    loading = false,
    loaded = true,
    status = "rereading",
    cover = fakeCover(1),
    readFile = trackingBook.files.first(),
    offlineFile = trackingBook.files.first(),
)

internal val sampleTracking = BookTrackingUiState(
    loading = false,
    sessions = listOf(
        s(6, "2026-09-24T19:40:00Z", 52, 9.5, 62.0),
        s(5, "2026-09-24T07:15:00Z", 18, 3.0, 52.5, source = "manual"),
        s(4, "2026-09-21T20:05:00Z", 64, 12.0, 49.5),
        s(3, "2026-09-18T21:10:00Z", 41, 8.0, 37.5, source = "web"),
        s(2, "2026-09-15T20:30:00Z", 35, 7.5, 29.5),
    ),
    sessionsTotal = 5,
    sessionsPage = 1,
    stats = BookSessionStats(
        totalSessions = 5,
        totalSeconds = 210L * 60,
        latestEndProgress = 62.0,
        paceProgressDelta = 40.0,
        paceDurationSeconds = 210L * 60,
    ),
    attempts = listOf(
        ReadingAttempt(id = 2, bookId = 1, startedOn = "2026-09-14"),
        ReadingAttempt(id = 1, bookId = 1, startedOn = "2019-03-02", endedOn = "2019-03-20", outcome = AttemptOutcome.COMPLETED),
    ),
    settings = AppSettings(pageTotals = mapOf(1L to 212)),
    canRate = true,
    rating = 4,
    note = trackingBook.personalNote,
)
