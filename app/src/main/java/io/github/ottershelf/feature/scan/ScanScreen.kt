package io.github.ottershelf.feature.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import io.github.ottershelf.core.session.AuthState
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel

/**
 * Scan a book (Route.Scan): pushed from the toolbar's scan action (Dashboard, book lists), or from
 * the Currently Reading card to pick the book for the reading timer ([Route.Scan.forTimer]). What
 * it finds replaces it (the book page, the timer, or Book requests), so Back returns to where the user
 * was; opened to pick a book ([Route.Scan.pick], the Calendar's "Add a book"), it hands the book
 * back ([ScanPicks]) and closes. CAMERA is asked for here, after the rationale; refused, the ISBN
 * can still be typed.
 */
@Composable
fun ScanScreen(route: Route.Scan, navigator: AppNavigator) {
    val context = LocalContext.current
    val viewModel = appViewModel { c ->
        ScanViewModel(
            remote = ApiScanRemote(c.api),
            forTimer = route.forTimer,
            canRequest = { c.auth.user.value?.can(AuthState.BOOK_REQUEST_ACCESS) == true },
            saved = createSavedStateHandle(),
            remember = { card -> BookPreview.put(c.session.accountKey(), card.toBookCard()) },
            pick = route.pick,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectWhileResumed(viewModel.navigation) { next ->
        when (next) {
            // Back to the screen that opened the scanner, which takes the book when it shows again.
            is ScanNav.Picked -> {
                ScanPicks.hand(next.book)
                navigator.back()
            }
            is ScanNav.OpenBook -> navigator.replace(Route.BookDetail(next.bookId))
            is ScanNav.OpenTimer -> navigator.replace(Route.Timer(next.bookId))
            is ScanNav.Request -> navigator.replace(Route.RequestBook(next.isbn, next.title, next.author))
            ScanNav.OpenRequests -> navigator.replace(Route.Requests)
        }
    }
    // Off the back stack (Back pressed) the screen still shows while it fades out: the camera's
    // frames are closed unread then.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    var access by rememberSaveable { mutableStateOf(if (cameraGranted(context)) CameraAccess.Granted else CameraAccess.Ask) }
    // Back from Settings (or the permission revoked meanwhile).
    LifecycleResumeEffect(Unit) {
        val granted = cameraGranted(context)
        if (granted) access = CameraAccess.Granted else if (access == CameraAccess.Granted) access = CameraAccess.Ask
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = if (granted) CameraAccess.Granted else CameraAccess.Denied
    }
    var torch by rememberSaveable { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf<String?>(null) }
    val camera = CameraUi(access, torchAvailable, torch, cameraFailed)

    // Light status bar icons over the camera image, whatever the theme.
    val view = LocalView.current
    DisposableEffect(camera.live) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        if (camera.live) controller?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) controller.isAppearanceLightStatusBars = before }
    }

    BackHandler(enabled = state.manual != null || state.result != null) {
        if (state.manual != null) viewModel.closeManual() else viewModel.scanAgain()
    }

    val haptics = LocalHapticFeedback.current
    ScanContent(
        state = state,
        camera = camera,
        actions = remember(viewModel, navigator) {
            ScanActions(
                onClose = {
                    viewModel.leave()
                    navigator.back()
                },
                onTorch = { torch = !torch },
                onAllowCamera = { permission.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { openAppSettings(context) },
                onType = viewModel::openManual,
                onManualText = viewModel::setManual,
                onFind = viewModel::submitManual,
                onCloseManual = viewModel::closeManual,
                onPick = viewModel::pick,
                onRequest = viewModel::request,
                onScanAgain = viewModel::scanAgain,
                onRetry = viewModel::retry,
            )
        },
        viewfinder = { modifier ->
            BarcodeCamera(
                analysing = state.analysing && resumed,
                torch = torch,
                onTorchAvailable = { torchAvailable = it },
                onIsbn = { isbn ->
                    val onTop = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (onTop && viewModel.detected(isbn)) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                },
                onFailed = { cameraFailed = it.orEmpty() },
                modifier = modifier.fillMaxSize(),
            )
        },
    )
}

/**
 * Acts on [events] only while this screen is resumed. Popped off the back stack (Back, or the
 * scanner's X), NavDisplay holds the leaving entry's lifecycle at CREATED while it fades out, so a
 * lookup that answers then doesn't replace the screen the user went back to with a book. One that
 * answers while the app is in the background is acted on when the user comes back.
 */
@Composable
internal fun <T> CollectWhileResumed(events: Flow<T>, onEvent: (T) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(onEvent)
    LaunchedEffect(events, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { events.collect { latest(it) } }
    }
}

private fun cameraGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
