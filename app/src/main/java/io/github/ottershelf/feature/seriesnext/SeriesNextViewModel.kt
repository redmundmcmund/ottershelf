package io.github.ottershelf.feature.seriesnext

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.ui.nav.ReaderRouter
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel

/**
 * The next book in [bookId]'s series, for the screen it sits in (the book page and its finish
 * celebration, the timer's result, the reader's last page). It only reads: opening the next book is
 * the user's to do, and nothing here changes a status.
 */
class SeriesNextViewModel(private val container: AppContainer, private val bookId: Long) : ViewModel() {

    private val loader = SeriesNextLoader(ApiSeriesNextRemote(container.api), downloaded = container.downloads::get)
    private val account = runCatching { container.session.accountKey() }.getOrNull()

    /** The user's Settings switch (AppSettings.nextInSeries): off, nothing shows anywhere and nothing is asked. */
    private val enabled: StateFlow<Boolean> = container.appSettings.settings
        .map { it.nextInSeries }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.appSettings.settings.value.nextInSeries)

    private val _state = MutableStateFlow<SeriesNextState>(SeriesNextState.Hidden)
    val state: StateFlow<SeriesNextState> = combine(_state, enabled) { state, on -> if (on) state else SeriesNextState.Hidden }
        .stateIn(viewModelScope, SharingStarted.Eagerly, if (enabled.value) _state.value else SeriesNextState.Hidden)

    /** What was asked for last (so the same question isn't asked again) and how to ask it. */
    private var askedFor: Any? = null
    private var ask: (suspend () -> SeriesNextState)? = null
    private var job: Job? = null
    private var answeredAt = 0L

    init {
        // Switched back on while this screen is up: ask what the screen last wanted, from nothing
        // shown (not the answer from before it was switched off, which may be out of date by now).
        viewModelScope.launch {
            enabled.drop(1).collect { on ->
                if (on) {
                    ask?.let { load(it, keepOnFailure = false) }
                } else {
                    job?.cancel()
                    _state.value = SeriesNextState.Hidden
                }
            }
        }
    }

    /** The book page: [book] as it shows; [finished]: the user has finished it (read, or the finish flow is on). */
    fun after(book: BookDetail?, finished: Boolean) {
        if (book == null || !finished || book.seriesId == null) {
            clear()
            return
        }
        start(listOf(book.id, book.seriesId, book.seriesIndex, book.seriesName)) { loader.after(book) }
    }

    /**
     * The reader and the timer's result, which don't hold the book's page: it is looked up here.
     * [onlyWhenRead]: only once the user's status says the user has read it.
     */
    fun afterThisBook(onlyWhenRead: Boolean) {
        start("book:$bookId:$onlyWhenRead") { loader.after(bookId, onlyWhenRead) }
    }

    /** Showing again (the user may have read the next one meanwhile): asks again, at most every few seconds. */
    fun refresh() {
        val run = ask ?: return
        if (job?.isActive == true || SystemClock.elapsedRealtime() - answeredAt < REFRESH_GAP_MS) return
        load(run, keepOnFailure = true)
    }

    /** The next book's reader (ui.nav.ReaderRouter), or null when no reader here opens any of its files. */
    fun readRoute(): Route? {
        val next = (_state.value as? SeriesNextState.Next)?.book ?: return null
        val file = next.file ?: return null
        return ReaderRouter.route(next.bookId, file, next.title.orEmpty())
    }

    /** The next book's page (its card left for the page to show at once). */
    fun detailsRoute(): Route? {
        val next = (_state.value as? SeriesNextState.Next)?.book ?: return null
        account?.let { runCatching { BookPreview.put(it, next.card) } }
        return Route.BookDetail(next.bookId)
    }

    private fun clear() {
        job?.cancel()
        askedFor = null
        ask = null
        _state.value = SeriesNextState.Hidden
    }

    private fun start(key: Any, run: suspend () -> SeriesNextState) {
        if (key == askedFor) return
        job?.cancel()
        askedFor = key
        ask = run
        _state.value = SeriesNextState.Hidden
        load(run, keepOnFailure = false)
    }

    /** [keepOnFailure]: a refresh that fails (or finds no connection) leaves what shows. */
    private fun load(run: suspend () -> SeriesNextState, keepOnFailure: Boolean) {
        if (!enabled.value) return
        if (!container.online.value) {
            if (!keepOnFailure) _state.value = SeriesNextState.Hidden
            return
        }
        job = viewModelScope.launch {
            val result = try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (keepOnFailure) return@launch
                SeriesNextState.Hidden
            }
            answeredAt = SystemClock.elapsedRealtime()
            _state.value = result
        }
    }

    private companion object {
        const val REFRESH_GAP_MS = 5_000L
    }
}

/** The next book for a screen: what to show, and the routes its Read and Details open. */
@Stable
class SeriesNextUi(
    val state: SeriesNextState,
    /** The next book's reader, or null (nothing a reader here opens). */
    val readRoute: () -> Route?,
    /** The next book's page, or null when there's none. */
    val detailsRoute: () -> Route?,
)

@Composable
private fun rememberSeriesNextViewModel(bookId: Long): SeriesNextViewModel =
    appViewModel(key = "series-next:$bookId") { SeriesNextViewModel(it, bookId) }

@Composable
private fun SeriesNextViewModel.ui(): SeriesNextUi {
    val state by state.collectAsStateWithLifecycle()
    return remember(this, state) { SeriesNextUi(state, this::readRoute, this::detailsRoute) }
}

/**
 * The book page (and its finish celebration): the next book after [book] once the user has finished it:
 * [status] (the page's) says read or skimmed, or the finish flow is on ([finishing]). Looked up
 * again when the page shows again.
 */
@Composable
fun rememberSeriesNext(bookId: Long, book: BookDetail?, status: String?, finishing: Boolean): SeriesNextUi {
    val viewModel = rememberSeriesNextViewModel(bookId)
    val finished = finishing || SeriesNext.isDone(status)
    LaunchedEffect(viewModel, book, finished) { viewModel.after(book, finished) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    return viewModel.ui()
}

/**
 * The timer's result: the next book once [saved] (the session reached the server) and the user's status
 * says the user has read [bookId], as a session reaching the end sets it.
 */
@Composable
fun rememberSeriesNextAfterSession(bookId: Long, saved: Boolean): SeriesNextUi {
    val viewModel = rememberSeriesNextViewModel(bookId)
    LaunchedEffect(viewModel, saved) { if (saved) viewModel.afterThisBook(onlyWhenRead = true) }
    return viewModel.ui()
}

/** The reader: the next book, looked up once the user is near the end of [bookId] ([near]). */
@Composable
internal fun rememberSeriesNextNearEnd(bookId: Long, near: Boolean): SeriesNextUi {
    val viewModel = rememberSeriesNextViewModel(bookId)
    LaunchedEffect(viewModel, near) { if (near) viewModel.afterThisBook(onlyWhenRead = false) }
    // Back in the app: a connection that was missing may be there now.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    return viewModel.ui()
}
