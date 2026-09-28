package io.github.ottershelf.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.R
import io.github.ottershelf.core.download.DownloadEvent
import io.github.ottershelf.core.session.AuthState
import io.github.ottershelf.core.theme.ThemeSyncEvent
import io.github.ottershelf.core.tracking.TrackingEvent
import io.github.ottershelf.feature.achievements.AchievementCelebrationHost
import io.github.ottershelf.ui.theme.PatternBackground

/**
 * The whole UI. Signed out: the Login route alone. Signed in: [AppScaffold], keyed by account, so
 * every screen, back stack and ViewModel starts fresh after signing in again (as someone else, or
 * after the server rejected the refresh token).
 */
@Composable
fun AppRoot(container: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        val status by container.auth.status.collectAsStateWithLifecycle()
        when (val signedIn = status) {
            AuthState.Status.SignedOut -> key(AuthState.Status.SignedOut) {
                ViewModelScope { RouteContent(Route.Login, AppNavigator.None, chrome = null) }
            }
            is AuthState.Status.SignedIn -> key(signedIn.accountKey) {
                ViewModelScope { AppScaffold(container) }
            }
        }
    }
}

/** ViewModels created inside [content] are cleared when it leaves the composition. */
@Composable
private fun ViewModelScope(content: @Composable () -> Unit) {
    val owner = rememberViewModelStoreOwner()
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

/**
 * The signed-in shell, laid out as the Nexus MainActivity: a modal navigation drawer holding every
 * list (swipe or the toolbar's toggle; the scrim or Back closes it) over Navigation 3's NavDisplay.
 * The bottom entry is the picked list under its toolbar; book pages, the reader, requests and
 * settings are pushed on top (predictive back included). The drawer only opens over a root list.
 * The app starts on the Dashboard, or on Downloaded when the device is offline.
 */
@Composable
private fun AppScaffold(container: AppContainer) {
    val navigator = rememberAppNavigatorState(start = if (container.online.value) Route.Home else Route.Downloads())
    val shell = appViewModel { ShellViewModel(it) }
    val drawer by shell.state.collectAsStateWithLifecycle()
    LaunchedEffect(drawer.failed) { navigator.drawerLoadFailed(drawer.failed) }
    // Leaving Book requests (or a scanned book's request, Route.RequestBook): its open count in
    // the drawer may have changed.
    val requestsOpen = navigator.backStack.any { it == Route.Requests || it is Route.RequestBook }
    var requestsWereOpen by remember { mutableStateOf(requestsOpen) }
    LaunchedEffect(requestsOpen) {
        if (requestsWereOpen && !requestsOpen) shell.refreshRequestCount()
        requestsWereOpen = requestsOpen
    }
    // A timer notification was tapped: open its screen over whatever is showing. The result of a
    // timer whose screen is on top replaces that screen, so Done goes back to where the user was.
    val pendingRoute by PendingRoute.route.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { route ->
            val top = navigator.current
            if (route is Route.TimerResult && top is Route.Timer && top.bookId == route.bookId) navigator.replace(route) else navigator.navigate(route)
            PendingRoute.consume(route)
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    val snackbars = remember { SnackbarHostState() }
    ShellSnackbars(shell.downloadEvents, shell::downloadShown, shell.themeEvents, shell.trackingEvents, snackbars)

    val chrome = remember(navigator, drawerState) {
        RootChrome(
            navigator = navigator,
            openDrawer = { scope.launch { drawerState.open() } },
            drawerOpen = { drawerState.isOpen },
        )
    }
    val entries = navigator.rememberEntries { route -> RouteContent(route, navigator, chrome) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only over a root list; a drawer already open may still be swiped shut.
        gesturesEnabled = navigator.current.chrome == Chrome.Root || drawerState.isOpen,
        scrimColor = DrawerScrimColor,
        drawerContent = {
            AppDrawerSheet(
                state = drawer,
                selectedKey = navigator.root.sourceKey,
                drawerState = drawerState,
                onPick = { item, label ->
                    closeDrawer()
                    navigator.pick(item.route(label))
                },
                onAction = { action ->
                    when (action) {
                        DrawerAction.Requests -> {
                            closeDrawer()
                            navigator.navigate(Route.Requests)
                        }
                        DrawerAction.Retry -> shell.load()
                    }
                },
                onSettings = {
                    closeDrawer()
                    navigator.navigate(Route.Settings)
                },
                onSignOut = shell::signOut,
            )
        },
    ) {
        PatternBackground(Modifier.fillMaxSize()) {
            NavDisplay(
                entries = entries,
                onBack = { navigator.back() },
                modifier = Modifier.fillMaxSize(),
            )
            SnackbarHost(
                snackbars,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
            )
            // Achievements just earned (claimed once across devices), as a toast at the top.
            AchievementCelebrationHost(
                current = navigator.current,
                onOpenAchievements = { navigator.navigate(Route.Achievements) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * Download results while the app is visible (in the background they become notifications), and an
 * appearance change the account didn't take. Each result is confirmed with [onDownloadShown] once
 * its snackbar has been shown; one cut off by leaving the app becomes a notification instead.
 */
@Composable
private fun ShellSnackbars(
    downloads: SharedFlow<DownloadEvent>,
    onDownloadShown: (DownloadEvent) -> Unit,
    theme: SharedFlow<ThemeSyncEvent>,
    tracking: SharedFlow<TrackingEvent>,
    snackbars: SnackbarHostState,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resources = LocalResources.current
    val shown by rememberUpdatedState(onDownloadShown)
    LaunchedEffect(downloads, theme, tracking, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                downloads.collect { event ->
                    val title = event.title?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.core_download_untitled)
                    val text = when (event) {
                        is DownloadEvent.Completed -> resources.getString(R.string.core_download_done, title)
                        is DownloadEvent.Failed -> resources.getString(R.string.core_download_failed, title, event.message)
                    }
                    snackbars.showSnackbar(text)
                    shown(event)
                }
            }
            launch {
                theme.collect { event ->
                    when (event) {
                        ThemeSyncEvent.SaveFailed -> snackbars.showSnackbar(resources.getString(R.string.components_theme_save_failed))
                    }
                }
            }
            launch {
                // A reading session saved offline that the server refused when it was sent at last.
                tracking.collect { event ->
                    when (event) {
                        is TrackingEvent.SessionRejected ->
                            snackbars.showSnackbar(resources.getString(R.string.nav_session_rejected, event.message), withDismissAction = true)
                    }
                }
            }
        }
    }
}
