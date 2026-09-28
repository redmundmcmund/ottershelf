package io.github.ottershelf.feature.comics

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.ui.components.KeepScreenOnWhileReading
import io.github.ottershelf.R
import io.github.ottershelf.feature.reader.PositionChoiceDialog
import io.github.ottershelf.feature.timer.ReaderTimerNotice
import io.github.ottershelf.ui.components.ImmersiveSystemBars
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.ReaderRouter
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel

/**
 * The comics reader (CBZ, CBR, CB7): Comics(bookId, fileId, title), opened through
 * ui.nav.ReaderRouter. Full screen and immersive like the EPUB reader: the pages under the reader's
 * bars, which a tap in the middle of the page toggles (the system bars go and come with them);
 * settings in a bottom sheet. Leaving the screen saves the page and ends the reading session
 * (ComicsViewModel); "Read next" at the end replaces this reader with the next comic's, so Back
 * returns to where the user came from.
 */
@Composable
fun ComicsScreen(route: Route.Comics, navigator: AppNavigator) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = appViewModel { ComicsViewModel(it, route.bookId, route.fileId, route.title, appContext) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    ImmersiveMode(barsVisible = state.chromeVisible || settingsOpen)
    KeepScreenOnWhileReading(state.currentPage, state.chromeVisible)

    val snackbars = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is ComicsMessage.FreshStart ->
                    snackbars.showSnackbar(resources.getString(R.string.comics_fresh_start, message.statusLabel))
            }
        }
    }

    val untitled = stringResource(R.string.comics_next_untitled)
    val openNext: () -> Unit = {
        state.nextBook?.let { next ->
            ReaderRouter.route(next.bookId, next.fileId, next.format, next.title ?: untitled)?.let(navigator::replace)
        }
    }
    ComicsContent(
        state = state,
        actions = ComicsActions(
            onBack = { navigator.back() },
            onSettings = { settingsOpen = true },
            onRetry = viewModel::retry,
            onToggleChrome = viewModel::toggleChrome,
            onPagesShown = viewModel::onPagesShown,
            onActivity = viewModel::onActivity,
            onPageSize = viewModel::onPageSize,
            onSeek = viewModel::seek,
            onJumpHandled = viewModel::jumpHandled,
            onPastEnd = { if (viewModel.shouldAutoAdvance()) openNext() },
            onOpenNext = openNext,
            onStripPlaced = viewModel::stripPlaced,
        ),
        imageLoader = viewModel.imageLoader,
        snackbars = snackbars,
    )

    if (settingsOpen) {
        ComicsSettingsSheet(
            settings = state.settings,
            customized = state.customized,
            actions = ComicsSettingsActions(
                onChange = viewModel::updateSettings,
                onReset = viewModel::resetSettings,
                onUseAsDefault = viewModel::useAsDefault,
            ),
            onDismiss = { settingsOpen = false },
        )
    }
    state.choice?.let { PositionChoiceDialog(it, onChoose = viewModel::choose) }
    // A reading timer on for this book would count the same time twice (feature.timer).
    ReaderTimerNotice(route.bookId)
}

/**
 * Hides the status and navigation bars while [barsVisible] is false (a swipe from an edge shows
 * them for a moment), and shows them again when the reader closes, unless another reader replaced
 * it (ui.components.ImmersiveSystemBars, as the EPUB reader).
 */
@Composable
private fun ImmersiveMode(barsVisible: Boolean) = ImmersiveSystemBars(barsVisible)
