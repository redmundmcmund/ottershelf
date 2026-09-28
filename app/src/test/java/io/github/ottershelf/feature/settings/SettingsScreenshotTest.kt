package io.github.ottershelf.feature.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.theme.ThemeBackground
import io.github.ottershelf.core.theme.ThemeMode
import io.github.ottershelf.core.theme.ThemePrefs
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.backgroundPattern
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings and Appearance (dark), and every background pattern in light and dark. Record with
 * `./gradlew recordRoborazziDebug --tests "*SettingsScreenshotTest"`; the PNGs land in
 * app/build/outputs/roborazzi/settings/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class SettingsScreenshotTest {

    private val darkPrefs = ThemePrefs(theme = ThemeMode.DARK)

    @Test
    fun settings() = captureDark("settings/settings", prefs = darkPrefs) {
        SettingsContent(
            SettingsUiState(username = "reader", name = "Elizabeth Bennet", server = "books.example.net", prefs = darkPrefs, version = "0.1.0", debugBuild = true),
        )
    }

    /** About: the badge, BookOrbit's required notices, the source and the components (tall, to show all). */
    @Test
    fun about() = captureLightAndDark("settings/about", heightDp = 1900) {
        AboutContent(version = "0.1.12", modified = "2026-09-28")
    }

    /** Landscape, tall enough for the accent grid: the web's four rows of 16 must fit the 640dp column. */
    @Test
    fun appearanceLandscape() = captureDark("settings/appearance_land", heightDp = 1100, widthDp = PHONE_HEIGHT_DP, prefs = darkPrefs) {
        AppearanceContent(AppearanceUiState(prefs = darkPrefs, sync = true))
    }

    @Test
    fun appearance() = captureDark("settings/appearance", heightDp = 1960, prefs = darkPrefs) {
        AppearanceContent(AppearanceUiState(prefs = darkPrefs, sync = true))
    }

    /** All 20 patterns on phone-shaped tiles, so the page-sized ones (vinyl, glow, mesh) read right. */
    @Test
    fun patterns() = captureLightAndDark("settings/patterns", heightDp = 1180) {
        val colors = OttershelfTheme.colors
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeBackground.entries.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { pattern ->
                        Column(Modifier.weight(1f)) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .background(colors.background)
                                    .backgroundPattern(pattern, colors)
                                    .border(1.dp, colors.border),
                            )
                            Text(pattern.label, fontSize = 11.sp, color = colors.mutedForeground, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDark(name: String, heightDp: Int = PHONE_HEIGHT_DP, prefs: ThemePrefs, widthDp: Int = PHONE_WIDTH_DP, content: @Composable () -> Unit) {
        captureRoboImage(
            filePath = "$SCREENSHOT_DIR/${name}_dark.png",
            roborazziComposeOptions = RoborazziComposeOptions {
                size(widthDp = widthDp, heightDp = heightDp)
                uiMode(Configuration.UI_MODE_NIGHT_YES)
            },
        ) {
            OttershelfTheme(prefs = prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WithFakeCovers(content)
                }
            }
        }
    }
}
