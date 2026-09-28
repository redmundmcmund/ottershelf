package io.github.ottershelf.feature.comics

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.readerprefs.CbxReaderSettings
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The comics reader's chrome over a (fake) page and its settings sheet, dark theme only. Record with
 * `./gradlew recordRoborazziDebug --tests "*ComicsScreenshotTest"`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class ComicsScreenshotTest {

    private val pages = object : ComicSource {
        override val pageCount = 180
        override fun model(index: Int): Any = fakeCover(index)
    }

    /** The last page with the bars up and the series' next comic offered. */
    @Test
    fun reader() = captureDark("comics/reader") {
        WithFakeCovers {
            ComicsContent(
                ComicsUiState(
                    title = "Little Nemo, Volume 1",
                    loading = false,
                    chromeVisible = true,
                    source = pages,
                    pageCount = 180,
                    currentPage = 179,
                    lastShownPage = 179,
                    nextBook = SeriesNextBook(bookId = 12, fileId = 30, format = "cbz", title = "Little Nemo, Volume 2", seriesIndex = "2"),
                    nextCover = fakeCover(3),
                ),
            )
        }
    }

    @Test
    fun settings() = captureDark("comics/settings", heightDp = 1180) {
        WithFakeCovers {
            Box(Modifier.fillMaxSize().background(OttershelfTheme.colors.card)) {
                ComicsSettingsContent(
                    settings = CbxReaderSettings(viewMode = "two-page", direction = "rtl", spreadGap = 8),
                    customized = true,
                    actions = ComicsSettingsActions(),
                )
            }
        }
    }
}

@OptIn(ExperimentalRoborazziApi::class)
private fun captureDark(name: String, heightDp: Int = 891, content: @Composable () -> Unit) {
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
