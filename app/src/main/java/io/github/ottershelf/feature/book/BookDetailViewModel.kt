package io.github.ottershelf.feature.book

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.download.DownloadedBook
import io.github.ottershelf.core.download.Downloads
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.feature.bookedit.canEditDetails
import io.github.ottershelf.ui.components.coverModel

/**
 * A book's page (Nexus BookDetailActivity).
 *
 * @property preview the grid card it was opened from: title, authors and thumbnail until [book] comes.
 * @property hint the title and authors it was opened with from elsewhere (no grid card), until [book] comes.
 * @property loaded the server answered (not only the copy kept with a download).
 * @property offlineCopy the server couldn't be reached: this is the downloaded copy's page.
 * @property error the page couldn't be loaded at all (no server, no downloaded copy).
 * @property status the read status shown (optimistic while [statusBusy]).
 * @property thumb the grid thumbnail, drawn under [cover] so the full cover fades in over it.
 * @property readFile the file Read opens (core.format.BookFormats.pickFile: the primary openable
 *   file, else the library's format priority; any format a reader here opens).
 * @property offlineFile the file a download keeps (BookFormats.pickOfflineFile: the same pick among
 *   the files that can be kept offline, so never a CBR or CB7).
 * @property downloaded the copy on this device.
 * @property download a download of this book queued or running.
 * @property downloadAllowed the account holds BookOrbit's "Download books" permission.
 * @property downloadIsCurrent the downloaded copy is still the server's [offlineFile].
 * @property canEdit the account may edit the book's details (feature.bookedit: the toolbar's pencil).
 */
data class BookDetailUiState(
    val bookId: Long,
    val preview: BookCard? = null,
    val hint: TitleHint? = null,
    val book: BookDetail? = null,
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val offlineCopy: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    val statusBusy: Boolean = false,
    val thumb: Any? = null,
    val cover: Any? = null,
    val readFile: BookFile? = null,
    val offlineFile: BookFile? = null,
    val downloaded: DownloadedBook? = null,
    val download: Downloads.Progress? = null,
    val downloadAllowed: Boolean = true,
    val downloadIsCurrent: Boolean = true,
    val canEdit: Boolean = false,
) {
    val title: String? get() = book?.title ?: preview?.title ?: hint?.title

    /** Read shows with a file a reader here opens on the server, or a copy on the device. */
    val canRead: Boolean get() = readFile != null || downloaded != null

    /** The format Read names on its button ("Read PDF"); null for an EPUB, which is plain "Read". */
    val readLabelFormat: String?
        get() = (keptComic?.format ?: readFile?.format ?: downloaded?.format)?.lowercase()?.takeUnless { it == "epub" }?.uppercase()

    /**
     * The downloaded copy Read opens online too, instead of [readFile]: the CBZ kept for a CBR or CB7
     * comic (BookFormats.readsKeptCopyInstead), while it is current and still on the server, so the user's
     * place stays in one file. A copy waiting for an Update doesn't: the server's newer file wins
     * online, as for every format.
     */
    private val keptComic: DownloadedBook?
        get() {
            val copy = downloaded ?: return null
            val file = readFile ?: return null
            if (!downloadIsCurrent || copy.fileId == file.id || !BookFormats.readsKeptCopyInstead(file.format, copy.format)) return null
            return copy.takeIf { book?.files?.any { it.id == copy.fileId } == true }
        }

    /** What the download button shows. */
    val downloadButton: DownloadButton
        get() {
            val progress = download
            // With a copy on the device the button stays, even if the server no longer has the
            // file: it's the only way to remove it.
            if (book == null || (downloaded == null && (offlineFile == null || (!downloadAllowed && progress == null)))) {
                return DownloadButton.Hidden
            }
            return when {
                progress != null -> DownloadButton.Downloading(
                    percent = if (progress.total > 0) (progress.done * 100 / progress.total).toInt() else null,
                    fraction = if (progress.total > 0) progress.done.toFloat() / progress.total else null,
                    waiting = progress.waiting,
                )
                downloaded != null && (offlineFile == null || downloadIsCurrent) -> DownloadButton.Downloaded
                downloaded != null -> DownloadButton.Update
                else -> DownloadButton.Download
            }
        }

    /**
     * The file Read opens and its format: the server's [readFile], except without a connection (or
     * the server's answer), where the downloaded copy is what can be read, even if the server has a
     * newer file, and for a comic whose kept CBZ stands in for its CBR ([keptComic]). Turn it into a
     * reader screen with ui.nav.ReaderRouter.
     */
    fun readTarget(online: Boolean): ReadTarget? {
        val copy = downloaded
        if (copy != null && (!loaded || !online)) return ReadTarget(copy.fileId, copy.format)
        keptComic?.let { return ReadTarget(it.fileId, it.format) }
        readFile?.let { return ReadTarget(it.id, it.format) }
        return copy?.let { ReadTarget(it.fileId, it.format) }
    }

    /** What tapping the download button leads to. */
    val downloadAction: DownloadAction?
        get() = when {
            download != null -> DownloadAction.ConfirmStop
            downloaded == null -> if (offlineFile != null) DownloadAction.Start else null
            offlineFile != null && !downloadIsCurrent -> DownloadAction.ConfirmUpdate
            else -> DownloadAction.ConfirmRemove
        }
}

/** A file to open in a reader: [format] picks which (ui.nav.ReaderRouter). */
data class ReadTarget(val fileId: Long, val format: String?)

sealed interface DownloadButton {
    data object Hidden : DownloadButton
    /** [percent] and [fraction] are null while the size is unknown; [waiting]: queued. */
    data class Downloading(val percent: Int?, val fraction: Float?, val waiting: Boolean) : DownloadButton
    data object Downloaded : DownloadButton
    /** The server has a different file now (replaced, or metadata written into it). */
    data object Update : DownloadButton
    data object Download : DownloadButton
}

enum class DownloadAction { Start, ConfirmStop, ConfirmUpdate, ConfirmRemove }

/** One-off messages for the screen's snackbar. */
sealed interface BookDetailMessage {
    data class StatusFailed(val error: String) : BookDetailMessage
}

/**
 * A book's page, ported from the Nexus BookDetailActivity: the copy kept with a download first
 * (and with no connection), then the server's; the read status set through the app's scope; and
 * the download's state from [Downloads].
 */
class BookDetailViewModel(private val container: AppContainer, private val bookId: Long) : ViewModel() {

    private val api = container.api
    private val downloads = container.downloads
    private val account = container.session.accountKey()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<BookDetailUiState> = _state.asStateFlow()

    private val _messages = Channel<BookDetailMessage>(Channel.BUFFERED)
    val messages: Flow<BookDetailMessage> = _messages.receiveAsFlow()

    /** Bumped by each [show], so a slower earlier one doesn't overwrite a newer one's cover. */
    private var showGeneration = 0
    private var loadJob: Job? = null

    /** The tracking part of the page (progress, reading log, rating and review, the finish flow). */
    val tracker = BookTracker(container, bookId, viewModelScope, book = { _state.value.book }, status = { _state.value.status })

    /**
     * When the page asks the server again beyond the first load and the tracking writes' reloads.
     * The screen tells it whether the page shows (BookDetailScreen's FollowShowing).
     */
    internal val freshness = PageFreshness(viewModelScope, clock = SystemClock::elapsedRealtime, refresh = ::refresh)

    init {
        load()
        // What this phone sent (the reader's and the timer's positions and sessions, a status set):
        // at once while the page shows, else when it shows again. The reader's last push lands after
        // the user is back on the page, so a check only on showing would miss it. A tracking write
        // (finished, a session logged, rating, review) changes the book: asked again at once. The
        // tracker reloads itself on the same signal, so that is a full reload.
        freshness.follow(container.readingChanges.changes, container.tracking.bookVersion(bookId), onWrite = ::reloadBook)
        viewModelScope.launch {
            // Progress ticks only redraw; the saved copy is re-read when downloads change.
            downloads.active.map { it[bookId] }.distinctUntilChanged().collect { progress ->
                _state.update { it.copy(download = progress) }
            }
        }
        viewModelScope.launch {
            downloads.version.collect {
                val copy = withContext(Dispatchers.IO) { downloads.get(bookId) }
                _state.update { it.copy(downloaded = copy).withCurrency() }
            }
        }
        viewModelScope.launch {
            // Its details edited on this phone (feature.bookedit): the saved book, its new cover too.
            container.readingChanges.editedBooks.map { it[bookId]?.book }.distinctUntilChanged().drop(1).filterNotNull().collect { edited ->
                _state.update { it.copy(thumb = null, preview = null, hint = null) }
                show(edited, fromServer = true)
            }
        }
    }

    private fun initialState(): BookDetailUiState {
        val preview = BookPreview.get(account, bookId)
        return BookDetailUiState(
            bookId = bookId,
            preview = preview,
            hint = if (preview == null) BookPreview.hint(account, bookId) else null,
            status = preview?.let { container.readingChanges.statusOf(bookId, it.readStatus?.status) },
            thumb = preview?.let { runCatching { api.coverModel(it) }.getOrNull() },
            // Downloading needs BookOrbit's "Download books" permission (superusers have it).
            downloadAllowed = container.auth.user.value?.can(DOWNLOAD_PERMISSION) ?: true,
            canEdit = canEditDetails(container.auth.user.value),
        )
    }

    /** Loads the page: a downloaded copy's first (and with no connection), then the server's. */
    fun load() {
        if (loadJob?.isActive == true) return
        freshness.reloaded()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { downloads.detail(bookId) }
            if (cached != null) show(cached, fromServer = false)
            try {
                val book = api.book(bookId)
                _state.update { it.copy(loaded = true, offlineCopy = false) }
                show(book, fromServer = true)
                saveDetail(book)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (cached == null && it.book == null) it.copy(error = e.message.orEmpty()) else it.copy(offlineCopy = true)
                }
            } finally {
                _state.update { it.copy(loading = false) }
                freshness.loadFinished()
            }
        }
    }

    /** Asks for the book, its position, sessions and readings again, once loaded; false if it couldn't. */
    private fun refresh(): Boolean {
        if (!_state.value.loaded || loadJob?.isActive == true) return false
        freshness.reloaded()
        tracker.refresh()
        reloadBook()
        return true
    }

    private fun reloadBook() {
        viewModelScope.launch {
            val book = runCatching { api.book(bookId) }.getOrNull() ?: return@launch
            _state.update { it.copy(book = book) }
            showStatus(book.readStatus?.status, fromServer = true)
            tracker.onBook(book)
            saveDetail(book)
        }
    }

    /** [fromServer]: fresh from the server, rather than the copy kept with a download. */
    private suspend fun show(book: BookDetail, fromServer: Boolean) {
        val generation = ++showGeneration
        showStatus(book.readStatus?.status, fromServer)
        if (fromServer) tracker.onBook(book)
        _state.update {
            it.copy(
                book = book,
                // The web's pick: the primary openable file, else the library's format priority.
                readFile = BookFormats.pickFile(book),
                offlineFile = BookFormats.pickOfflineFile(book),
                thumb = it.thumb ?: book.coverSource?.let { runCatching { api.thumbnailUrl(book.id, book.updatedAt) }.getOrNull() },
            ).withCurrency()
        }
        // The download (if any) and its cover are on disk: looked up off the main thread.
        val (copy, localCover) = withContext(Dispatchers.IO) { downloads.get(book.id) to downloads.cover(book.id) }
        if (generation != showGeneration) return // a newer show() took over
        // The server's cover when online (it may have changed); the one kept with a download otherwise.
        val cover: Any? = when {
            fromServer && book.coverSource != null -> runCatching { api.coverUrl(book.id, book.updatedAt) }.getOrNull()
            localCover != null -> localCover
            book.coverSource != null -> runCatching { api.coverUrl(book.id, book.updatedAt) }.getOrNull()
            else -> null
        }
        _state.update { it.copy(downloaded = copy, cover = cover).withCurrency() }
    }

    /** [fromServer]: only the server's word goes into the library grids' overrides, not an offline copy's. */
    private fun showStatus(value: String?, fromServer: Boolean) {
        if (value != null && fromServer) container.readingChanges.overrideStatus(bookId, value)
        _state.update { it.copy(status = value) }
    }

    /**
     * Sets the read status: shown at once, reverted if the server refuses. Sent from the app's
     * scope: it already looks done, so leaving the page mustn't cancel it, and the grids, the
     * dashboard and a downloaded copy hear of it anyway.
     */
    fun setStatus(chosen: ReadStatus) {
        val current = _state.value
        if (current.statusBusy || chosen.value == current.status) return
        val previous = current.status
        showStatus(chosen.value, fromServer = false) // optimistic; reverted if the server refuses
        _state.update { it.copy(statusBusy = true) }
        val container = container
        val id = bookId
        val request = container.appScope.async {
            container.api.setStatus(id, chosen.value).also { info ->
                container.readingChanges.overrideStatus(id, info.status ?: chosen.value)
                // Keep a downloaded copy's page in step, for offline.
                withContext(Dispatchers.IO) {
                    container.downloads.detail(id)?.let { container.downloads.saveDetail(it.copy(readStatus = info)) }
                }
                // The dashboard (e.g. marked Read: off Currently Reading), calendars and goals, and
                // this page's tracker (a new status opens or closes a reading) through bookVersion.
                container.tracking.changedElsewhere(id)
            }
        }
        viewModelScope.launch {
            try {
                val info = request.await()
                showStatus(info.status ?: chosen.value, fromServer = true)
                _state.update { it.copy(book = it.book?.copy(readStatus = info)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatus(previous, fromServer = false)
                _messages.trySend(BookDetailMessage.StatusFailed(e.message.orEmpty()))
            } finally {
                _state.update { it.copy(statusBusy = false) }
            }
        }
    }

    /** The file Read opens (see [BookDetailUiState.readTarget]). */
    fun readTarget(): ReadTarget? = _state.value.readTarget(container.online.value)

    fun startDownload() {
        val s = _state.value
        val book = s.book ?: return
        val file = s.offlineFile ?: return
        downloads.start(book, file)
    }

    fun cancelDownload() = downloads.cancel(bookId)

    fun removeDownload() {
        val downloads = downloads
        val id = bookId
        container.appScope.launch(Dispatchers.IO) { downloads.delete(id) }
    }

    /** Keeps a downloaded copy's page in step (a file write: off the main thread, and not cut short). */
    private fun saveDetail(book: BookDetail) {
        val downloads = downloads
        container.appScope.launch(Dispatchers.IO) { downloads.saveDetail(book) }
    }

    private fun BookDetailUiState.withCurrency(): BookDetailUiState {
        val saved = downloaded
        val file = offlineFile
        return copy(downloadIsCurrent = saved == null || file == null || downloads.isCurrent(saved, file))
    }

    private companion object {
        const val DOWNLOAD_PERMISSION = "library_download"
    }
}

/**
 * When the book page asks the server again ([refresh]: `books/:id`, the file's position, the
 * sessions and the readings, four requests), besides its first load ([reloaded]) and the reloads a
 * tracking write of this book makes ([reloadedByWrite]):
 * - something this phone sent ([changed]: ReadingChanges, moved by the reader's and the timer's
 *   positions and sessions, a status set, a note synced) while the page shows: [SETTLE_MS] later,
 *   so what comes in a burst (a position and its session, the reader's last push landing just after
 *   the user is back) makes one refresh; while it doesn't show, when it shows again;
 * - showing again ([resumed]) after the app was stopped while it showed ([stopped]), or once the last
 *   refresh is [MAX_AGE_MS] old.
 * Back from the author's books, the series, highlights or edit with nothing sent, nothing is asked
 * (every return used to ask again). A change after a load or refresh started is asked for again (its
 * answers may be from before the change), except within [SAME_EVENT_MS] after a tracking write's
 * reload: the write moves this book's version and ReadingChanges together, in either order.
 */
internal class PageFreshness(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** Asks again; false if it couldn't (not loaded, or the first load still running): it stays due. */
    private val refresh: () -> Boolean,
) {
    private var showing = false
    private var stoppedSinceReload = false
    private var reloadedAt = clock()
    /** The last reload was a tracking write's ([reloadedByWrite]). */
    private var reloadedByWrite = false
    private var changedAt: Long? = null
    private var pending: Job? = null

    /**
     * Follows what this phone sends: [changes] (ReadingChanges) and [writes] (this book's tracking
     * version, which moves with a write of this book and reloads the page through [onWrite]). Their
     * values when it starts are where the page starts.
     */
    fun follow(changes: Flow<*>, writes: Flow<*>, onWrite: () -> Unit) {
        scope.launch { changes.drop(1).collect { changed() } }
        scope.launch {
            writes.drop(1).collect {
                reloadedByWrite()
                onWrite()
            }
        }
    }

    /** A load or reload started (the first load, a retry, or [refresh]). */
    fun reloaded() {
        reloadedAt = clock()
        stoppedSinceReload = false
        reloadedByWrite = false
    }

    /** A tracking write of this book reloads the page: the ReadingChanges move it brings is its own. */
    fun reloadedByWrite() {
        reloaded()
        reloadedByWrite = true
    }

    /** The first load (or a retry) ended: something sent while it ran is asked for now. */
    fun loadFinished() {
        if (showing && due()) refreshSoon()
    }

    fun changed() {
        changedAt = clock()
        if (showing) refreshSoon()
    }

    /**
     * The page shows (the screen's resume effect: opened, back from a screen on top, the app
     * resumed). It asks again only when [due]: something was sent meanwhile (the reader can set the
     * status to Reading, or Read at the end), the app was stopped, or the page is old.
     */
    fun resumed() {
        showing = true
        if (due()) refreshSoon()
    }

    /** The page no longer shows (a screen on top, the app paused, the entry left composition). */
    fun paused() {
        showing = false
        // Still due when it shows again.
        pending?.cancel()
    }

    /** The app stopped while this page showed (not merely a screen pushed over it). */
    fun stopped() {
        stoppedSinceReload = true
    }

    private fun due(): Boolean {
        // A write's own ReadingChanges move can come just after its reload started.
        val grace = if (reloadedByWrite) SAME_EVENT_MS else 0L
        val sent = changedAt?.let { it > reloadedAt + grace } == true
        return sent || stoppedSinceReload || clock() - reloadedAt >= MAX_AGE_MS
    }

    private fun refreshSoon() {
        pending?.cancel()
        pending = scope.launch {
            delay(SETTLE_MS)
            if (showing && due()) refresh()
        }
    }

    companion object {
        const val SETTLE_MS = 1_000L
        const val MAX_AGE_MS = 5 * 60_000L
        const val SAME_EVENT_MS = 250L
    }
}
