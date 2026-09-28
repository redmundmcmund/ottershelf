package io.github.ottershelf.testing

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.theme.ThemePrefs
import io.github.ottershelf.ui.theme.OttershelfTheme

/** A typical phone in portrait: 1080x2392 px at ~420 dpi is about 411x891 dp. */
const val PHONE_WIDTH_DP = 411
const val PHONE_HEIGHT_DP = 891

/** Relative to the module directory (app/), where Gradle runs unit tests. */
const val SCREENSHOT_DIR = "build/outputs/roborazzi"

/**
 * Renders [content] in [OttershelfTheme] (with [prefs]: the web defaults unless given) on the page
 * background at phone size, once in light and once in dark theme (system night mode, so
 * `isSystemInDarkTheme()` follows), and writes
 * `app/build/outputs/roborazzi/<name>_light.png` and `<name>_dark.png`. Use `<feature>/<screen>`
 * as [name] so each feature's images sit in their own folder.
 *
 * Only records when Roborazzi is asked to (`recordRoborazziDebug`, see ARCHITECTURE.md, "Screenshot tests"); a plain
 * `testDebugUnitTest` skips the capture. The test class needs
 * `@RunWith(AndroidJUnit4::class)`, `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and
 * `@Config(qualifiers = "xxhdpi")` (see ui/screenshots/ShellScreenshotTest.kt).
 */
@OptIn(ExperimentalRoborazziApi::class)
fun captureLightAndDark(
    name: String,
    widthDp: Int = PHONE_WIDTH_DP,
    heightDp: Int = PHONE_HEIGHT_DP,
    prefs: ThemePrefs = ThemePrefs(),
    content: @Composable () -> Unit,
) {
    for (dark in listOf(false, true)) {
        captureRoboImage(
            filePath = "$SCREENSHOT_DIR/${name}_${if (dark) "dark" else "light"}.png",
            roborazziComposeOptions = RoborazziComposeOptions {
                size(widthDp = widthDp, heightDp = heightDp)
                uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            },
        ) {
            OttershelfTheme(prefs = prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }
}

/** Landscape phone: [captureLightAndDark] with the sides swapped. */
fun captureLandscape(name: String, content: @Composable () -> Unit) =
    captureLightAndDark("${name}_land", widthDp = PHONE_HEIGHT_DP, heightDp = PHONE_WIDTH_DP, content = content)
