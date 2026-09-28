package io.github.ottershelf.feature.comics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.readerprefs.CbxReaderSettings
import io.github.ottershelf.core.readerprefs.ReaderSettingsSpecs
import io.github.ottershelf.core.sync.PageProgress
import io.github.ottershelf.feature.reader.PositionChoice

/** `GET cbz/files/:fileId/pages`. */
@Serializable
internal data class ComicPageCount(val pageCount: Int = 0)

/** The next book of the series this reader opens (`SeriesNextBook`). */
@Serializable
data class SeriesNextBook(
    val bookId: Long,
    val fileId: Long,
    val format: String,
    val title: String? = null,
    val seriesIndex: String? = null,
)

/** `GET series/:seriesId/books/:bookId/next?formatGroup=cbx` */
@Serializable
internal data class SeriesNextBookResponse(val next: SeriesNextBook? = null)

/** Why the comic isn't showing. */
sealed interface ComicsError {
    /** No connection, and this comic isn't downloaded. */
    data object Offline : ComicsError

    /** The server has no pages for it. */
    data object Empty : ComicsError

    /** The server's or the network's message. */
    data class Failed(val message: String?) : ComicsError
}

/** A page to show because of something other than the reader's own paging (a settled conflict). */
data class ComicsJump(val page: Int, val id: Int)

/** One-off messages for a snackbar. */
sealed interface ComicsMessage {
    /** Marked Read/Unread by hand since it was last read: starting from the first page. */
    data class FreshStart(val statusLabel: String) : ComicsMessage
}

data class ComicsUiState(
    val title: String,
    val loading: Boolean = true,
    val error: ComicsError? = null,
    /** The top and bottom bars (a tap in the middle of the page toggles them). */
    val chromeVisible: Boolean = false,
    val source: ComicSource? = null,
    val pageCount: Int = 0,
    /** The first page on screen (0-based), and the last (a spread shows two). */
    val currentPage: Int = 0,
    val lastShownPage: Int = 0,
    /** Width / height of the pages whose size is known (for the wide-page rule of spreads). */
    val ratios: Map<Int, Float> = emptyMap(),
    val settings: CbxReaderSettings = CbxReaderSettings(),
    /** This comic has settings of its own (Reset offered). */
    val customized: Boolean = false,
    /** Reading the downloaded copy. */
    val offline: Boolean = false,
    val nextBook: SeriesNextBook? = null,
    /** The next book's cover (a thumbnail URL). */
    val nextCover: Any? = null,
    /**
     * The user's Settings switch "Suggest the next in a series" (AppSettings.nextInSeries). Off, the next
     * issue's card shows only while auto-advance is on, since a turn past the last page then opens it.
     */
    val suggestNext: Boolean = true,
    val choice: PositionChoice? = null,
    val jump: ComicsJump? = null,
    /**
     * The strip has been brought to the page this opening chose. Not saved: after the process was
     * killed the restored list holds the old place, and this new opening may have chosen another.
     */
    val stripPlaced: Boolean = false,
) {
    val ready: Boolean get() = !loading && error == null && pageCount > 0
    val paged: Boolean get() = settings.scrollMode == SCROLL_PAGED
    val rtl: Boolean get() = settings.direction == DIRECTION_RTL
    val atLastPage: Boolean get() = pageCount > 0 && lastShownPage >= pageCount - 1

    companion object {
        const val SCROLL_PAGED = "paginated"
        const val SCROLL_INFINITE = "infinite"
        const val SCROLL_STRIP = "long-strip"
        const val DIRECTION_RTL = "rtl"
    }
}

/**
 * The comics reader (the web's CbzReaderView): the pages of a CBZ, CBR or CB7 from the server as
 * images, or of the downloaded CBZ. Position and sessions go through core.sync.PageReaderProgress
 * (the EPUB reader's rules: open at the saved page, save 2 s after a page turn, sessions), settings
 * through core.readerprefs (on the device, and the account's in reader sync mode), and at the end the
 * series' next comic (`series/:id/books/:bookId/next?formatGroup=cbx`).
 */
class ComicsViewModel(
    private val container: AppContainer,
    private val bookId: Long,
    private val fileId: Long,
    title: String,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ComicsUiState(title, suggestNext = container.appSettings.settings.value.nextInSeries))
    val state: StateFlow<ComicsUiState> = _state.asStateFlow()

    private val _messages = Channel<ComicsMessage>(Channel.BUFFERED)
    val messages: Flow<ComicsMessage> = _messages.receiveAsFlow()

    /** Pages: the account's client, their own disk cache, the local CBZ fetcher. */
    val imageLoader: ImageLoader = ComicImages.loader(appContext) { container.api.client }

    private val progress = container.pageReaderProgress(bookId, fileId, viewModelScope)
    private val settings = container.readerSettings(ReaderSettingsSpecs.Cbx, fileId)

    private var shownOnce = false
    private var lastPageReachedAt = 0L
    private var jumpIds = 0
    private val prefetched = HashSet<Int>()
    private val prefetches = ArrayList<Disposable>()
    /** The pages on screen haven't loaded yet: prefetching waits for them ([prefetchAfterShown]). */
    private var prefetchWaiting = false
    private var nextBookLoaded = false

    init {
        viewModelScope.launch { settings.effective.collect { s -> _state.update { it.copy(settings = s) } } }
        viewModelScope.launch { container.appSettings.settings.collect { s -> _state.update { it.copy(suggestNext = s.nextInSeries) } } }
        viewModelScope.launch { settings.customized.collect { c -> _state.update { it.copy(customized = c) } } }
        viewModelScope.launch { settings.load() }
        viewModelScope.launch {
            progress.choice.collect { c ->
                _state.update { it.copy(choice = c?.let { PositionChoice(c.mine, c.mineAt, c.server, c.serverReadAt) }) }
            }
        }
        viewModelScope.launch {
            progress.jumps.collect { position ->
                val count = _state.value.pageCount
                if (count > 0) jumpTo(PageProgress.startPage(position, count) - 1)
            }
        }
        open()
    }

    private fun open() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) {
                container.downloads.openOffline(bookId, fileId)
                    ?.takeIf { it.format == "cbz" }
                    ?.let { copy -> runCatching { ComicSource.Local(copy.file, LocalCbz.pages(copy.file)) }.getOrNull() }
                    ?.takeIf { it.pageCount > 0 }
            }
            if (local == null && !container.online.value) return@launch fail(ComicsError.Offline)
            // Where to open is decided while the page count loads.
            val opening = if (progress.readSinceOpen) null else async { progress.open(hasLocalCopy = local != null) }
            val source: ComicSource = local ?: try {
                val count = container.api.send("GET", "cbz/files/$fileId/pages", null) {
                    ApiJson.decodeFromString(ComicPageCount.serializer(), it).pageCount
                }
                if (count <= 0) {
                    opening?.cancel()
                    return@launch fail(ComicsError.Empty)
                }
                ComicSource.Server(container.api, fileId, count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                opening?.cancel()
                return@launch fail(ComicsError.Failed(e.message))
            }
            val decided = opening?.await()
            val start = decided?.startPage(source.pageCount)?.minus(1) ?: _state.value.currentPage.coerceIn(0, source.pageCount - 1)
            _state.update {
                it.copy(
                    loading = false,
                    source = source,
                    pageCount = source.pageCount,
                    currentPage = start,
                    lastShownPage = start,
                    offline = local != null,
                )
            }
            decided?.freshStart?.let { _messages.trySend(ComicsMessage.FreshStart(it.label)) }
            prefetchAfterShown()
            loadNextBook()
        }
    }

    private fun fail(error: ComicsError) {
        _state.update { it.copy(loading = false, error = error, chromeVisible = true) }
    }

    fun retry() {
        if (_state.value.loading) return
        open()
    }

    /** The series' next comic, for the card at the end (nothing when offline or not in a series). */
    private fun loadNextBook() {
        if (nextBookLoaded || !container.online.value) return
        nextBookLoaded = true
        viewModelScope.launch {
            val next = runCatching {
                val seriesId = container.api.book(bookId).seriesId ?: return@runCatching null
                container.api.send("GET", "series/$seriesId/books/$bookId/next?formatGroup=cbx", null) {
                    ApiJson.decodeFromString(SeriesNextBookResponse.serializer(), it).next
                }
            }.getOrNull()
            if (next == null) nextBookLoaded = false
            _state.update { it.copy(nextBook = next, nextCover = next?.let { n -> runCatching { container.api.unversionedThumbnailUrl(n.bookId) }.getOrNull() }) }
        }
    }

    // --- reading -------------------------------------------------------------------------------

    /** These pages (0-based, reading order) are on screen now. */
    fun onPagesShown(pages: List<Int>) {
        val count = _state.value.pageCount
        if (count <= 0 || pages.isEmpty()) return
        val first = pages.first().coerceIn(0, count - 1)
        val last = pages.last().coerceIn(0, count - 1)
        _state.update { it.copy(currentPage = first, lastShownPage = last) }
        shownOnce = true
        // A spread reports its last page, as the web does.
        progress.onPage(last + 1, count)
        lastPageReachedAt = if (last >= count - 1) lastPageReachedAt.takeIf { it > 0 } ?: System.currentTimeMillis() else 0L
        prefetchAfterShown()
    }

    /** The user zoomed or scrolled within a page: a move (the visit counts as reading), and the session isn't idle. */
    fun onActivity() {
        if (shownOnce) progress.onGesture()
    }

    /**
     * A turn past the last page: open the next comic when auto-advance is on (paged mode only, and
     * only half a second after the last page came up, so a quick double turn doesn't skip a book).
     */
    fun shouldAutoAdvance(): Boolean {
        val s = _state.value
        return s.settings.autoAdvance && s.paged && s.nextBook != null && s.atLastPage &&
            lastPageReachedAt > 0 && System.currentTimeMillis() - lastPageReachedAt >= AUTO_ADVANCE_ARM_MS
    }

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun hideChrome() = _state.update { it.copy(chromeVisible = false) }

    fun onPageSize(page: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val ratio = width.toFloat() / height
        val s = _state.value
        if (s.ratios[page] != ratio) _state.update { it.copy(ratios = it.ratios + (page to ratio)) }
        // A page on screen came in: now the ones around it.
        if (prefetchWaiting && page in s.currentPage..s.lastShownPage) {
            prefetchWaiting = false
            prefetch()
        }
    }

    /** The slider: go to [page] (0-based). */
    fun seek(page: Int) {
        val count = _state.value.pageCount
        if (count > 0) jumpTo(page.coerceIn(0, count - 1))
    }

    private fun jumpTo(page: Int) {
        _state.update { it.copy(jump = ComicsJump(page, ++jumpIds)) }
    }

    fun jumpHandled(id: Int) = _state.update { if (it.jump?.id == id) it.copy(jump = null) else it }

    fun stripPlaced() = _state.update { it.copy(stripPlaced = true) }

    fun choose(keepMine: Boolean) = progress.choose(keepMine)

    fun onPause() {
        progress.onPause()
    }

    fun onResume() {
        // A new session starts with the page the user comes back to.
        val s = _state.value
        if (shownOnce && s.pageCount > 0) progress.onPage(s.lastShownPage + 1, s.pageCount)
    }

    // --- settings ------------------------------------------------------------------------------

    fun updateSettings(change: (CbxReaderSettings) -> CbxReaderSettings) = settings.updateBook(change)

    fun resetSettings() = settings.resetBook()

    /** These settings for every comic without its own (the account's cbx default); this one follows it. */
    fun useAsDefault() {
        val current = _state.value.settings
        settings.updateDefault { current }
        settings.resetBook()
    }

    // --- pages ---------------------------------------------------------------------------------

    /**
     * [prefetch] once the pages on screen are in: at once when they were loaded before (their size is
     * known), else when one of them reports its size. Until then they have the connection to
     * themselves (the loader's few requests per host would otherwise go to pages the user may never reach).
     */
    private fun prefetchAfterShown() {
        val s = _state.value
        if ((s.currentPage..s.lastShownPage).all { it in s.ratios }) {
            prefetchWaiting = false
            prefetch()
        } else {
            prefetchWaiting = true
        }
    }

    /**
     * Warms the cache for the pages around the ones on screen (and learns their sizes, which the
     * spreads need), decoded small: the reader decodes them again at full size. The pages ahead in
     * reading order go first; the ones on screen load themselves.
     */
    private fun prefetch() {
        val s = _state.value
        val source = s.source ?: return
        val ahead = (s.lastShownPage + 1)..(s.lastShownPage + PREFETCH_AHEAD)
        val behind = (s.currentPage - 1) downTo (s.currentPage - PREFETCH_BEHIND)
        for (i in ahead + behind) {
            if (i !in 0 until source.pageCount || !prefetched.add(i)) continue
            val model = source.model(i) ?: continue
            val request = ImageRequest.Builder(appContext)
                .data(model)
                .size(PREFETCH_SIZE_PX)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .listener(
                    onSuccess = { _, result -> onPageSize(i, result.image.width, result.image.height) },
                    onError = { _, _ -> prefetched.remove(i) },
                )
                .build()
            prefetches += imageLoader.enqueue(request)
        }
        if (prefetches.size > 64) prefetches.removeAll { it.isDisposed }
    }

    override fun onCleared() {
        progress.close()
        prefetches.forEach { it.dispose() }
        // The loader is process-wide: its memory cache would keep this comic's pages (tens of MB)
        // until the app goes to the background. Only this comic's: after Read next the next one's stay.
        _state.value.source?.cacheKeyPrefix?.let { ComicImages.forget(imageLoader.memoryCache, it) }
    }

    companion object {
        const val AUTO_ADVANCE_ARM_MS = 500L
        private const val PREFETCH_BEHIND = 2
        private const val PREFETCH_AHEAD = 5
        private const val PREFETCH_SIZE_PX = 320
    }
}
