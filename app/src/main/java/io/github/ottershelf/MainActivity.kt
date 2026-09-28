package io.github.ottershelf

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.ui.nav.AppRoot
import io.github.ottershelf.ui.nav.PendingRoute
import io.github.ottershelf.ui.theme.OttershelfTheme

/** The only activity: everything else is Compose, routed by ui.nav. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        // The splash stays up until the saved appearance is read, so the first frame has the
        // user's theme rather than the defaults (a DataStore read: milliseconds).
        splash.setKeepOnScreenCondition { !container.theme.loaded.value }
        // Opened from a timer notification (not again when the activity is recreated).
        if (savedInstanceState == null) PendingRoute.offer(PendingRoute.fromIntent(intent))
        setContent {
            val themePrefs by container.themePrefs.collectAsStateWithLifecycle()
            OttershelfTheme(prefs = themePrefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(container)
                }
            }
        }
    }

    /** A timer notification tapped while the app is open (the intent is SINGLE_TOP). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PendingRoute.offer(PendingRoute.fromIntent(intent))
    }

    override fun onResume() {
        super.onResume()
        // Sends what's queued (no request when nothing waits) and refreshes downloaded books'
        // positions at most every SyncCoordinator.BASELINE_INTERVAL_MS. Unlocking with the reader on
        // top, or coming back from the camera or a picker, is a resume too. Nothing when signed out;
        // offline, what waits is left to the WorkManager flush.
        appContainer.sync.sync()
    }
}
