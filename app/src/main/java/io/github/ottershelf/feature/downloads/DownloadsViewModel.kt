package io.github.ottershelf.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.download.DownloadedBook
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.feature.library.LibraryViewPrefs
import io.github.ottershelf.feature.library.ListView
import java.util.concurrent.ConcurrentHashMap

/** A book on this device, as the grid shows it. [cover] is the thumbnail kept with it (a File); [format] the kept file's (it picks the reader). */
data class DownloadedItem(val card: BookCard, val fileId: Long, val cover: Any?, val sizeBytes: Long, val format: String = "epub")

/** A download queued or running for a book that isn't on the device yet. */
data class ActiveDownload(
    val bookId: Long,
    val title: String?,
    val authors: List<String>,
    /** The server's thumbnail URL. */
    val cover: Any?,
    val done: Long,
    /** -1 while unknown. */
    val total: Long,
    /** Queued: waiting for a connection, or behind other work. */
    val waiting: Boolean,
) {
    /** 0..1, or null while the size isn't known. */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

data class DownloadsUiState(
    val loading: Boolean = true,
    /** Every book on this device, by title (Downloads.list). */
    val books: List<DownloadedItem> = emptyList(),
    /** Downloads of books not on the device yet, shown first. */
    val active: List<ActiveDownload> = emptyList(),
    /** Read statuses changed on book pages this session (ReadingChanges), by book id. */
    val statusOverrides: Map<Long, String> = emptyMap(),
    /** The toolbar's search, if any. */
    val query: String? = null,
    /** Pulled to refresh: the list is being read again. */
    val refreshing: Boolean = false,
    /** Grid or list and the cover size (feature.library's view, kept for Downloaded on the device). */
    val view: ListView = ListView(),
) {
    val isEmpty: Boolean get() = !loading && books.isEmpty() && active.isEmpty()
}

/**
 * The drawer's Downloaded (the Nexus BookGridFragment on the Downloaded source): every book on this
 * device, filtered by the search, with its local reading position and status; plus downloads still
 * on their way, with WorkManager's progress. Works with no connection at all: everything comes
 * from the device. Reloads whenever a download completes or is removed, and on every return
 * ([refresh]), as the Nexus grid did.
 */
class DownloadsViewModel(private val container: AppContainer, private val query: String?) : ViewModel() {

    private val downloads = container.downloads
    private val local = MutableStateFlow<List<DownloadedItem>?>(null)
    private val queuedBooks = ConcurrentHashMap<Long, BookDetail>()
    private var loadJob: Job? = null

    private val activeItems: Flow<List<ActiveDownload>> = combine(downloads.active, local) { active, books ->
        val onDevice = books.orEmpty().mapTo(HashSet()) { it.card.id }
        active.filterKeys { it !in onDevice }.map { (bookId, progress) ->
            val book = queuedBooks[bookId] ?: downloads.queuedBook(bookId)?.also { queuedBooks[bookId] = it }
            ActiveDownload(
                bookId = bookId,
                title = book?.title,
                authors = book?.authors?.map { it.name }.orEmpty(),
                cover = container.api.thumbnailUrl(bookId, book?.updatedAt),
                done = progress.done,
                total = progress.total,
                waiting = progress.waiting,
            )
        }.filter { matches(it.title, it.authors) }
    }.flowOn(Dispatchers.IO)

    private val refreshing = MutableStateFlow(false)

    private val viewPrefs = LibraryViewPrefs(container.settings) { container.session.accountKey() }
    private val view = MutableStateFlow<ListView?>(null)

    val state: StateFlow<DownloadsUiState> =
        combine(local, activeItems, container.readingChanges.statusOverrides, refreshing, view) { books, active, overrides, pulled, shown ->
            DownloadsUiState(
                // The user's view is read first, so the books never show in the other one for a moment.
                loading = books == null || shown == null,
                books = books.orEmpty(),
                active = active,
                statusOverrides = overrides,
                query = query,
                refreshing = pulled,
                view = shown ?: ListView(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState(query = query))

    init {
        viewModelScope.launch { view.value = viewPrefs.load(LibraryViewPrefs.DOWNLOADED) }
        // Downloads finish, get removed and move on while this list is showing (or away).
        viewModelScope.launch { downloads.version.collect { load() } }
    }

    /** From the view menu or a pinch: shown at once and kept on the device. */
    fun setView(next: ListView) {
        if (view.value == next) return
        view.value = next
        container.appScope.launch { viewPrefs.save(LibraryViewPrefs.DOWNLOADED, next) }
    }

    /** Reads the list again (positions and statuses change while a book is open on top). */
    fun refresh() = load()

    /** Pulled to refresh: reads the list again with the spinner showing, long enough to be seen. */
    fun pullToRefresh() {
        if (refreshing.value) return
        refreshing.value = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val shown = launch { delay(MIN_REFRESH_MS) } // reading the device takes milliseconds
                local.value = withContext(Dispatchers.IO) { readDevice() }
                shown.join()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun cancel(bookId: Long) = downloads.cancel(bookId)

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            local.value = withContext(Dispatchers.IO) { readDevice() }
        }
    }

    private fun readDevice(): List<DownloadedItem> = downloads.list()
        .filter { matches(it.title, it.authors) }
        .map { d ->
            val detail = downloads.detail(d.bookId)
            val thumbnail = downloads.thumbnail(d.bookId)
            DownloadedItem(
                card = downloadedCard(d, detail, hasThumbnail = thumbnail != null, readingProgress = container.progress.percentage(d.fileId)),
                fileId = d.fileId,
                cover = thumbnail,
                sizeBytes = d.sizeBytes,
                format = d.format,
            )
        }

    private fun matches(title: String?, authors: List<String>): Boolean {
        val q = query?.trim()?.lowercase().orEmpty()
        return q.isEmpty() || (title.orEmpty() + " " + authors.joinToString(" ")).lowercase().contains(q)
    }

    private companion object {
        const val MIN_REFRESH_MS = 400L
    }
}

/**
 * A book on this device as the grid's card, from its download [d] and the page kept with it
 * ([detail]). The card also goes to the book page (the quick view's Book page), which asks for the
 * server's thumbnail by the card's dates: they are the page's, so the URL carries a real version
 * (not a made-up one, which the server would mark as final). Without them no server thumbnail is
 * claimed (the page finds its own once it loads); the grid shows the thumbnail kept on the device.
 */
internal fun downloadedCard(d: DownloadedBook, detail: BookDetail?, hasThumbnail: Boolean, readingProgress: Double?): BookCard {
    val dated = detail?.updatedAt != null || detail?.addedAt != null
    return BookCard(
        id = d.bookId,
        title = d.title,
        authors = d.authors,
        seriesName = detail?.seriesName,
        seriesIndex = detail?.seriesIndex,
        readingProgress = readingProgress,
        readStatus = detail?.readStatus ?: ReadStatusInfo(),
        hasCover = hasThumbnail && dated,
        addedAt = detail?.addedAt,
        updatedAt = detail?.updatedAt,
        files = detail?.files.orEmpty(),
        rating = detail?.rating,
    )
}
