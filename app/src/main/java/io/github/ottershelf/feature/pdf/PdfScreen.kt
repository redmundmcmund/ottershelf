package io.github.ottershelf.feature.pdf

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.ottershelf.ui.components.KeepScreenOnWhileReading
import io.github.ottershelf.R
import io.github.ottershelf.feature.reader.PositionChoiceDialog
import io.github.ottershelf.feature.timer.ReaderTimerNotice
import io.github.ottershelf.ui.components.ImmersiveSystemBars
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel

private enum class PdfSheet { Contents, Search, Settings }

/**
 * The PDF reader: Pdf(bookId, fileId, title, page?), opened through ui.nav.ReaderRouter. The pages
 * (platform PdfRenderer, see [PdfEngine]) fill the screen in a continuous strip or one at a time,
 * with the EPUB reader's chrome over them, hidden while reading and toggled by a tap. Immersive:
 * the system bars hide with the reader's bars. Contents (outline or thumbnails), search and
 * settings are bottom sheets; an encrypted PDF asks for its password.
 */
@Composable
fun PdfScreen(route: Route.Pdf, navigator: AppNavigator) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = appViewModel { PdfViewModel(it, route.bookId, route.fileId, route.title, route.page, appContext) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPause() }

    var sheet by rememberSaveable { mutableStateOf<PdfSheet?>(null) }
    PdfImmersiveMode(barsVisible = state.chromeVisible || sheet != null || !state.ready)
    KeepScreenOnWhileReading(state.page, state.chromeVisible)

    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is PdfMessage.FreshStart -> snackbars.showSnackbar(resources.getString(R.string.pdf_fresh_start, message.statusLabel))
            }
        }
    }
    val linkFailed = stringResource(R.string.pdf_link_failed)

    PdfContent(
        state = state,
        actions = PdfActions(
            onBack = { navigator.back() },
            onContents = { sheet = PdfSheet.Contents },
            onSearch = { sheet = PdfSheet.Search },
            onSettings = { sheet = PdfSheet.Settings },
            onSeek = { viewModel.goToPage(it) },
            onRetry = viewModel::retry,
            onNext = viewModel::next,
            onPrevious = viewModel::previous,
            onNextHit = viewModel::nextHit,
            onPreviousHit = viewModel::previousHit,
            onClearSearch = viewModel::clearSearch,
            viewer = PdfViewerEvents(
                onPage = { viewModel.onPageShown(it + 1) },
                onTap = viewModel::toggleChrome,
                onLink = { link ->
                    val page = link.page
                    val uri = link.uri
                    if (page != null) viewModel.goToPage(page + 1)
                    else if (uri != null && !openLink(context, uri)) scope.launch { snackbars.showSnackbar(linkFailed) }
                },
                onActivity = viewModel::onActivity,
            ),
        ),
        jumps = viewModel.jumps,
        snackbars = snackbars,
    )

    when (sheet) {
        PdfSheet.Contents -> PdfContentsSheet(
            state = state,
            onPage = { page ->
                sheet = null
                viewModel.goToPage(page)
            },
            onDismiss = { sheet = null },
        )
        PdfSheet.Search -> PdfSearchSheet(
            search = state.search,
            pageCount = state.pageCount,
            onSearch = viewModel::search,
            onPick = { index ->
                sheet = null
                viewModel.selectHit(index)
            },
            onDismiss = { sheet = null },
        )
        PdfSheet.Settings -> PdfSettingsSheet(
            view = state.view,
            customized = state.customized,
            actions = PdfSettingsActions(
                onScroll = viewModel::setScroll,
                onFit = viewModel::setFit,
                onNight = viewModel::setNight,
                onReset = viewModel::resetBookSettings,
                onUseForAll = viewModel::useForAllPdfs,
            ),
            onDismiss = { sheet = null },
        )
        null -> {}
    }
    (state.phase as? PdfPhase.Password)?.let { phase ->
        PdfPasswordDialog(wrong = phase.wrong, onOpen = viewModel::submitPassword, onCancel = { navigator.back() })
    }
    state.choice?.let { PositionChoiceDialog(it, onChoose = viewModel::choose) }
    // A reading timer on for this book would count the same time twice (feature.timer).
    ReaderTimerNotice(route.bookId)
}

/** Opens a web or mail link from the document in another app; false if it isn't one or nothing can. */
private fun openLink(context: Context, uri: String): Boolean {
    val parsed = Uri.parse(uri)
    if (parsed.scheme?.lowercase() !in setOf("http", "https", "mailto")) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, parsed))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * Hides the status and navigation bars while [barsVisible] is false (a swipe from an edge shows
 * them for a moment), and shows them again when the reader closes, unless another reader replaced
 * it (ui.components.ImmersiveSystemBars, as the EPUB reader).
 */
@Composable
private fun PdfImmersiveMode(barsVisible: Boolean) = ImmersiveSystemBars(barsVisible)
