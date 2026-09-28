package io.github.ottershelf.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.feature.book.BookDetailUiState
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.ui.nav.ReaderRouter
import io.github.ottershelf.ui.nav.Route

/**
 * The quick view of a long-pressed cover: what the card already knows at once, then the book as
 * the book page loads it.
 *
 * [page] is the book page's own state ([BookDetailUiState]), filled in as the page fills it, so Read
 * opens the same file in the same reader, and the download button says and does what the page's
 * does (its [BookDetailUiState.downloadButton] and [BookDetailUiState.downloadAction]).
 *
 * @property loadFailed the book couldn't be loaded (no connection and no downloaded copy): only the
 *   card's details, the book page and Retry are on offer.
 * @property statusError the server refused the last status change (it was put back).
 */
data class QuickViewUiState(
    val card: BookCard,
    val cover: Any?,
    val page: BookDetailUiState,
    val loadFailed: Boolean = false,
    val statusError: String? = null,
) {
    val bookId: Long get() = card.id
    val title: String? get() = page.book?.title ?: card.title
    val authors: List<String> get() = page.book?.authors?.map { it.name } ?: card.authors
    val seriesName: String? get() = page.book?.seriesName ?: card.seriesName
    val seriesIndex: String? get() = page.book?.seriesIndex ?: card.seriesIndex
    val status: ReadStatus get() = ReadStatus.of(page.status) ?: ReadStatus.UNREAD
    val rating: Int? get() = page.book?.rating ?: card.rating
    val description: String? get() = page.book?.description

    /** 0..100, or null when there is none to show. */
    val progress: Double? get() = card.readingProgress?.takeIf { it > 0.0 }

    /** Read says Continue for a book the user is part way through. */
    val continues: Boolean get() = (progress ?: 0.0) < 100.0 && progress != null && status != ReadStatus.READ

    /** Still waiting for the book (Read and the download wait for its files). */
    val loading: Boolean get() = page.book == null && !loadFailed
}

/**
 * One quick view at a time, per screen ([open] replaces what shows). Status and download work as
 * on the book page (BookDetailViewModel): the status is shown at once and put back if the server
 * refuses, sent from the app's scope so closing the sheet doesn't cancel it; the grids, the
 * Dashboard and a downloaded copy hear of it.
 */
class BookQuickViewModel(private val container: AppContainer) : ViewModel() {

    private val api = container.api
    private val downloads = container.downloads

    private val _state = MutableStateFlow<QuickViewUiState?>(null)
    val state: StateFlow<QuickViewUiState?> = _state.asStateFlow()

    private var opened: Job? = null

    fun open(card: BookCard, cover: Any?) {
        opened?.cancel()
        _state.value = QuickViewUiState(
            card = card,
            cover = cover,
            page = BookDetailUiState(
                bookId = card.id,
                preview = card,
                status = container.readingChanges.statusOf(card.id, card.readStatus?.status),
                downloadAllowed = container.auth.user.value?.can(DOWNLOAD_PERMISSION) ?: true,
            ),
        )
        val id = card.id
        opened = viewModelScope.launch {
            launch { load(id) }
            launch {
                downloads.active.map { it[id] }.distinctUntilChanged().collect { progress ->
                    updatePage(id) { it.copy(download = progress) }
                }
            }
            launch {
                downloads.version.collect {
                    val copy = withContext(Dispatchers.IO) { downloads.get(id) }
                    updatePage(id) { it.copy(downloaded = copy).withCurrency() }
                }
            }
        }
    }

    fun close() {
        opened?.cancel()
        opened = null
        _state.value = null
    }

    fun retry() {
        val current = _state.value ?: return
        if (!current.loadFailed) return
        update(current.bookId) { it.copy(loadFailed = false) }
        viewModelScope.launch { load(current.bookId) }
    }

    /** The downloaded copy's page first (and with no connection), then the server's, as the book page loads. */
    private suspend fun load(id: Long) {
        val cached = withContext(Dispatchers.IO) { downloads.detail(id) }
        if (cached != null) show(id, cached, fromServer = false)
        try {
            val book = api.book(id)
            show(id, book, fromServer = true)
            container.appScope.launch(Dispatchers.IO) { downloads.saveDetail(book) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (cached == null) update(id) { it.copy(loadFailed = it.page.book == null) }
        }
    }

    private fun show(id: Long, book: BookDetail, fromServer: Boolean) {
        val server = book.readStatus?.status
        // Only the server's word goes into the grids' overrides, not an offline copy's.
        if (fromServer && server != null && _state.value?.page?.statusBusy != true) container.readingChanges.overrideStatus(id, server)
        updatePage(id) { page ->
            page.copy(
                book = book,
                loaded = page.loaded || fromServer,
                status = if (page.statusBusy) page.status else if (fromServer) server ?: page.status else page.status ?: server,
                readFile = BookFormats.pickFile(book),
                offlineFile = BookFormats.pickOfflineFile(book),
            ).withCurrency()
        }
    }

    /** The reader Read opens (the book page's pick; the downloaded copy when offline), or null. */
    fun readRoute(): Route? {
        val s = _state.value ?: return null
        val target = s.page.readTarget(container.online.value) ?: return null
        return ReaderRouter.route(s.bookId, target.fileId, target.format, s.title.orEmpty())
    }

    /** Before opening the book page: it shows the card's title, authors and cover at once. */
    fun opening() {
        val s = _state.value ?: return
        BookPreview.put(container.session.accountKey(), s.card)
    }

    fun setStatus(chosen: ReadStatus) {
        val current = _state.value ?: return
        if (current.page.statusBusy || chosen.value == current.page.status) return
        val id = current.bookId
        val previous = current.page.status
        update(id) { it.copy(statusError = null, page = it.page.copy(status = chosen.value, statusBusy = true)) }
        val container = container
        val request = container.appScope.async {
            container.api.setStatus(id, chosen.value).also { info ->
                container.readingChanges.overrideStatus(id, info.status ?: chosen.value)
                withContext(Dispatchers.IO) {
                    container.downloads.detail(id)?.let { container.downloads.saveDetail(it.copy(readStatus = info)) }
                }
                container.tracking.changedElsewhere(id)
            }
        }
        viewModelScope.launch {
            try {
                val info = request.await()
                updatePage(id) { it.copy(status = info.status ?: chosen.value, book = it.book?.copy(readStatus = info)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(id) { it.copy(statusError = e.message.orEmpty(), page = it.page.copy(status = previous)) }
            } finally {
                updatePage(id) { it.copy(statusBusy = false) }
            }
        }
    }

    fun startDownload() {
        val page = _state.value?.page ?: return
        val book = page.book ?: return
        val file = page.offlineFile ?: return
        downloads.start(book, file)
    }

    fun cancelDownload() {
        val id = _state.value?.bookId ?: return
        downloads.cancel(id)
    }

    fun removeDownload() {
        val id = _state.value?.bookId ?: return
        val downloads = downloads
        container.appScope.launch(Dispatchers.IO) { downloads.delete(id) }
    }

    private fun update(id: Long, change: (QuickViewUiState) -> QuickViewUiState) =
        _state.update { s -> if (s != null && s.bookId == id) change(s) else s }

    private fun updatePage(id: Long, change: (BookDetailUiState) -> BookDetailUiState) = update(id) { it.copy(page = change(it.page)) }

    private fun BookDetailUiState.withCurrency(): BookDetailUiState {
        val saved = downloaded
        val file = offlineFile
        return copy(downloadIsCurrent = saved == null || file == null || downloads.isCurrent(saved, file))
    }

    private companion object {
        const val DOWNLOAD_PERMISSION = "library_download"
    }
}
