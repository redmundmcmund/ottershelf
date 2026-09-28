package io.github.ottershelf.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns a function that asks for POST_NOTIFICATIONS if it isn't granted yet (a no-op otherwise).
 * Call it when the user starts something that notifies, such as a download: downloads work
 * without it, but their progress notification (with Cancel) and results then stay hidden.
 * [onResult] hears the user's answer (true: granted), e.g. to post a notification that was skipped.
 */
@Composable
fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> currentOnResult(granted) }
    return remember(context, launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
