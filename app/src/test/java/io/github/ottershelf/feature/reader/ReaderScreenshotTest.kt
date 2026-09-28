package io.github.ottershelf.feature.reader

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reader with its bars showing over a stand-in page (the WebView doesn't render under
 * Robolectric), and the reading settings sheet's content, in light and dark.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class ReaderScreenshotTest {

    private val openState = ReaderUiState(
        title = "A Study in Scarlet",
        loading = false,
        chromeVisible = true,
        chapter = "Chapter 1: Mr. Sherlock Holmes",
        fraction = 0.27f,
        page = 41,
        pages = 152,
        toc = listOf(TocEntry("Chapter 1", "c1.xhtml")),
        timeLeft = TimeLeft(chapterSeconds = 740, bookSeconds = 4 * 3600 + 10 * 60, fromPace = true),
    )

    @Test
    fun chrome() = captureLightAndDark("reader/reader_chrome") {
        WithFakeCovers {
            ReaderContent(state = openState, bookmarked = true) { modifier -> FakePage(modifier) }
        }
    }

    @Test
    fun chromeWithTimeLeftInTheChapter() = captureLightAndDark("reader/reader_chrome_time") {
        WithFakeCovers {
            ReaderContent(state = openState.copy(prefs = ReaderPrefs(footerDisplayMode = FooterMode.CHAPTER.id))) { modifier -> FakePage(modifier) }
        }
    }

    @Test
    fun scrolledAtAChaptersEnd() = captureLightAndDark("reader/reader_scrolled_end") {
        WithFakeCovers {
            ReaderContent(
                state = openState.copy(chromeVisible = false, chapterEnd = true, prefs = ReaderPrefs(theme = "sepia", flow = ReaderPrefs.FLOW_SCROLLED)),
            ) { modifier -> FakePage(modifier, ReaderPrefs(theme = "sepia"), end = true) }
        }
    }

    @Test
    fun settings() = captureLightAndDark("reader/reader_settings", heightDp = 1180) {
        SettingsSheet(ReaderPrefs(theme = "sepia", letterSpacing = 0.02, textIndent = 1.5))
    }

    @Test
    fun settingsScrolledWithCustomColours() = captureLightAndDark("reader/reader_settings_custom", heightDp = 1180) {
        // Too little contrast: the warning.
        SettingsSheet(ReaderPrefs(theme = "custom", customBg = "#d9e4ec", customFg = "#8a8f98", flow = ReaderPrefs.FLOW_SCROLLED, maxInlineSize = 1160, paragraphSpacing = 0.6))
    }

    @Test
    fun settingsForAFixedLayoutBook() = captureLightAndDark("reader/reader_settings_fixed") {
        SettingsSheet(ReaderPrefs(fixedLayoutSpread = ReaderPrefs.SPREAD_NONE), fixedLayout = true)
    }

    @Test
    fun colourPicker() = captureLightAndDark("reader/reader_colour_picker") {
        // The dialog's body in a card as the dialog shows it (Robolectric doesn't capture dialog windows).
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(color = OttershelfTheme.colors.popover, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text("Background colour", style = MaterialTheme.typography.headlineSmall, color = OttershelfTheme.colors.foreground)
                    Spacer(Modifier.height(16.dp))
                    var value by remember { mutableStateOf("#f4ecd8") }
                    PageColorPicker(ColorTarget.BACKGROUND, value, other = Color(0xFF2F2A24), onChange = { value = it })
                }
            }
        }
    }

    @Composable
    private fun SettingsSheet(prefs: ReaderPrefs, fixedLayout: Boolean = false) {
        WithFakeCovers {
            Surface(color = OttershelfTheme.colors.card, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(top = 24.dp)) {
                    var p by remember { mutableStateOf(prefs) }
                    ReaderSettingsContent(prefs = p, onChange = { p = it }, fixedLayout = fixedLayout, pageWidthDp = PHONE_WIDTH_DP)
                }
            }
        }
    }

    @Composable
    private fun FakePage(modifier: Modifier, prefs: ReaderPrefs = ReaderPrefs(), end: Boolean = false) {
        val page = pageColors(prefs)
        Column(modifier.background(page.background).padding(horizontal = 26.dp, vertical = 28.dp)) {
            if (!end) {
                Text(
                    "1. Mr. Sherlock Holmes",
                    style = MaterialTheme.typography.titleLarge,
                    color = page.foreground,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }
            repeat(if (end) 7 else 6) {
                Text(
                    LOREM,
                    fontSize = 18.sp,
                    lineHeight = 27.sp,
                    color = page.foreground,
                    textAlign = TextAlign.Justify,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    private companion object {
        const val LOREM = "Under such circumstances, I naturally gravitated to London, that great cesspool into which all " +
            "the loungers and idlers of the Empire are irresistibly drained."
    }
}

@OptIn(ExperimentalRoborazziApi::class)
internal fun captureDark(name: String, content: @Composable () -> Unit) {
    captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}
