package io.github.ottershelf.feature.login

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sign-in page, dark, with the https-only error showing. Writes login/login_screen_dark.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class LoginScreenshotTest {

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun loginScreen() = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/login/login_screen_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                WithFakeCovers {
                    LoginContent(
                        LoginUiState(server = "http://books.example.net", username = "reader", error = LoginError.Insecure),
                    )
                }
            }
        }
    }

    /** The first sign-in on a phone: every field blank (no server is built in). */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun firstSignIn() = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/login/login_first_light.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                WithFakeCovers { LoginContent(LoginUiState()) }
            }
        }
    }
}
