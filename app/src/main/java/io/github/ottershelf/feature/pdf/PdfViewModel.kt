package io.github.ottershelf.feature.pdf

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.readerprefs.PdfReaderSettings
import io.github.ottershelf.core.readerprefs.ReaderSettingsStore
import io.github.ottershelf.core.sync.PageProgress
import io.github.ottershelf.core.sync.PageReaderProgress
import io.github.ottershelf.feature.reader.PositionChoice
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The PDF reader's state: the file (downloaded copy, cache, or streamed from the server), the open
 * document, where to start and what to save (core.sync.PageReaderProgress: the EPUB reader's open,
 * save, conflict and session rules), the settings (core.readerprefs, synced as the web's
 * `reader/preferences/:fileId`; night mode on the device), search and the outline.
 */
class PdfViewModel(
    private val container: AppContainer,
    private val bookId: Long,
    private val fileId: Long,
    title: String,
    /** Open at this page (1-based; a highlight's) and save nothing until a page is turned. */
    private val jumpPage: Int?,
    context: Context,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val progress: PageReaderProgress = container.pageReaderProgress(bookId, fileId, viewModelScope)
    private val settings: ReaderSettingsStore<PdfReaderSettings> = container.readerSettings(PdfSettings.Spec, fileId)
    private val nightKey = booleanPreferencesKey("pdf.night.${container.session.accountKey()}")
    private val files = PdfFiles(
        root = File(context.cacheDir, "${PdfFiles.CACHE_DIR}/${container.session.accountKey()}"),
        client = { downloadClient },
        serveUrl = { container.api.serverUrl("/api/v1/books/files/$it/serve") },
        offline = { book, file -> container.downloads.openOffline(book, file) },
    )
    private val downloadClient by lazy { container.api.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }
    private val cache = PageBitmapCache(PageBitmapCache.defaultBytes())
    private val trimInBackground = TrimInBackground(cache::clear).also { appContext.registerComponentCallbacks(it) }

    private var engine: PdfEngine? = null
    private var file: File? = null
    /** [file] is this reader's cached copy: one that won't open is deleted, so Retry fetches it again. */
    private var fileCached = false
    private var opening: Deferred<PageReaderProgress.Opening>? = null
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var ready = false

    private val _state = MutableStateFlow(PdfUiState(title = title))
    val state: StateFlow<PdfUiState> = _state.asStateFlow()

    private val _jumps = Channel<PdfJump>(Channel.CONFLATED)
    /** Where the page views should go (outline, slider, search, a conflict settled for the server). */
    val jumps: Flow<PdfJump> = _jumps.receiveAsFlow()

    private val _messages = Channel<PdfMessage>(Channel.BUFFERED)
    val messages: Flow<PdfMessage> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { settings.load() }
        viewModelScope.launch {
            val night = container.settings.data.map { it[nightKey] ?: false }.catch { emit(false) }
            combine(settings.effective, settings.customized, night) { s, customized, n -> Triple(s, customized, n) }
                .collect { (s, customized, n) -> _state.update { it.copy(view = PdfSettings.view(s, n), customized = customized) } }
        }
        viewModelScope.launch {
            progress.choice.collect { c ->
                _state.update { it.copy(choice = c?.let { PositionChoice(it.mine, it.mineAt, it.server, it.serverReadAt) }) }
            }
        }
        viewModelScope.launch {
            progress.jumps.collect { position ->
                val count = _state.value.pageCount
                if (count > 0) _jumps.trySend(PdfJump(PageProgress.startPage(position, count) - 1))
            }
        }
        load()
    }

    /** Gets the file and opens it; also Retry after a failure. */
    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(phase = PdfPhase.Loading(), chromeVisible = true) }
            val onPhone = withContext(Dispatchers.IO) { runCatching { files.onPhone(bookId, fileId) }.getOrDefault(false) }
            // Offline with no copy on the phone: nothing can open, so nothing is decided (or asked) yet.
            if (!onPhone && !container.online.value) {
                _state.update { it.copy(phase = PdfPhase.Failed(offline = true)) }
                return@launch
            }
            if (decidesAgain()) {
                // Where to start is decided while the file downloads (it may wait for the two-positions prompt).
                opening = viewModelScope.async { progress.open(hasLocalCopy = onPhone, jumpPage = jumpPage) }
            }
            val source = try {
                files.obtain(bookId, fileId, online = container.online.value) { p ->
                    _state.update { if (it.phase is PdfPhase.Loading) it.copy(phase = PdfPhase.Loading(p)) else it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfFiles.OfflineException) {
                _state.update { it.copy(phase = PdfPhase.Failed(offline = true)) }
                return@launch
            } catch (e: Exception) {
                _state.update { it.copy(phase = PdfPhase.Failed(offline = false, message = e.message)) }
                return@launch
            }
            file = source.file
            fileCached = source.cached
            openDocument(password = null)
        }
    }

    /**
     * Whether [load] decides where to start (again): nothing decided yet, the decision failed, or it
     * was made without the server's answer and nothing has been read since. On Retry once the server
     * is back, opening at the old place would save over its newer page, or ask about two positions
     * for reading that never happened (the EPUB reader's decidesAgainOnRetry). A decision still under
     * way is kept.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun decidesAgain(): Boolean {
        val previous = opening ?: return true
        if (!previous.isCompleted) return false
        val decided = runCatching { previous.getCompleted() }.getOrNull() ?: return true
        return !decided.withServer && !progress.readSinceOpen
    }

    /** The password prompt's answer (kept only in memory, for this opening). */
    fun submitPassword(password: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { openDocument(password) }
    }

    private suspend fun openDocument(password: String?) {
        val f = file ?: return
        _state.update { it.copy(phase = PdfPhase.Opening) }
        val doc = try {
            PdfEngine.open(f, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfPasswordException) {
            _state.update { it.copy(phase = PdfPhase.Password(wrong = password != null)) }
            return
        } catch (e: Exception) {
            if (fileCached) withContext(Dispatchers.IO) { f.delete() }
            _state.update { it.copy(phase = PdfPhase.Failed(offline = false, message = e.message)) }
            return
        }
        // A document opened before (a Retry after one that showed nothing) is closed, not dropped.
        engine?.let { old -> container.appScope.launch { old.close() } }
        engine = doc
        val count = doc.pageCount
        val first = if (count > 0) doc.pageSize(0) else null
        if (first == null) {
            // Nothing to show: closed now, and a cached copy goes, so Retry fetches the file again.
            engine = null
            doc.close()
            if (fileCached) withContext(Dispatchers.IO) { f.delete() }
            _state.update { it.copy(phase = PdfPhase.Failed(offline = false, message = null)) }
            return
        }
        val decided = opening?.await() ?: progress.open(hasLocalCopy = true, jumpPage = jumpPage)
        val start = decided.startPage(count)
        ready = true
        _state.update {
            it.copy(
                phase = PdfPhase.Ready,
                pageCount = count,
                sizes = PdfPageSizes.uniform(count, first.first, first.second),
                page = start,
                chromeVisible = false,
                source = EnginePageSource(doc, cache),
            )
        }
        decided.freshStart?.let { _messages.trySend(PdfMessage.FreshStart(it.label)) }
        viewModelScope.launch { measurePages(doc, count) }
        viewModelScope.launch {
            val outline = withContext(Dispatchers.IO) { PdfOutline.read(f) }.filter { it.page in 0 until count }
            _state.update { it.copy(outline = outline) }
        }
    }

    /**
     * Every page's size (the views start with the first page's for all), published a batch at a
     * time. They are read [SIZE_CHUNK] pages per call on the renderer's one thread, so a page render
     * asked for meanwhile (the first page, a fling, a jump) waits for a few page opens at most.
     */
    private suspend fun measurePages(doc: PdfEngine, count: Int) {
        var from = 1
        while (from < count) {
            val to = (from + SIZE_BATCH).coerceAtMost(count)
            val sizes = ArrayList<Pair<Float, Float>>(to - from)
            var at = from
            while (at < to) {
                val chunk = doc.pageSizes(at, (at + SIZE_CHUNK).coerceAtMost(to))
                if (chunk.isEmpty()) break
                sizes += chunk
                at += chunk.size
            }
            if (sizes.isNotEmpty()) _state.update { it.copy(sizes = it.sizes.with(from, sizes)) }
            if (at < to) return
            from = to
            yield()
        }
    }

    // --- reading ------------------------------------------------------------------------------

    /** The page on screen (1-based) changed. */
    fun onPageShown(page: Int) {
        if (!ready) return
        val count = _state.value.pageCount
        _state.update { it.copy(page = page) }
        progress.onPage(page, count)
    }

    /** A gesture on the pages ended; [moved]: the user panned, zoomed or scrolled (a plain tap isn't reading). */
    fun onActivity(moved: Boolean) {
        if (!ready) return
        if (moved) progress.onGesture() else progress.onActivity()
    }

    /** The screen stopped: save now, end the session. */
    fun onPause() = progress.onPause()

    /** Go to [page] (1-based). */
    fun goToPage(page: Int, animate: Boolean = false) {
        val count = _state.value.pageCount
        if (count <= 0) return
        _jumps.trySend(PdfJump((page - 1).coerceIn(0, count - 1), animate = animate))
    }

    fun next() = goToPage(_state.value.page + 1, animate = true)
    fun previous() = goToPage(_state.value.page - 1, animate = true)

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun choose(keepMine: Boolean) = progress.choose(keepMine)

    // --- search -------------------------------------------------------------------------------

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        val doc = engine
        if (q.isEmpty() || doc == null) {
            clearSearch()
            return
        }
        _state.update { it.copy(search = PdfSearch(query = q, running = true)) }
        searchJob = viewModelScope.launch {
            val hits = ArrayList<PdfHit>()
            val count = doc.pageCount
            var capped = false
            for (i in 0 until count) {
                val found = doc.search(i, q)
                if (found.isNotEmpty()) hits += found
                if (hits.size >= MAX_HITS) {
                    capped = true
                    hits.subList(MAX_HITS, hits.size).clear()
                }
                if (found.isNotEmpty() || i % 10 == 9 || capped) {
                    val snapshot = hits.toList()
                    _state.update { it.copy(search = it.search.copy(hits = snapshot, searched = i + 1, capped = capped)) }
                }
                if (capped) break
            }
            _state.update { it.copy(search = it.search.copy(hits = hits.toList(), running = false, searched = count, capped = capped)) }
        }
    }

    /** Shows match [index] (its page, the match about a third down the screen). */
    fun selectHit(index: Int) {
        val s = _state.value
        val hit = s.search.hits.getOrNull(index) ?: return
        _state.update { it.copy(search = it.search.copy(selected = index)) }
        val height = s.sizes.height(hit.page).takeIf { it > 0f } ?: 1f
        _jumps.trySend(PdfJump(hit.page, y = (hit.top / height).coerceIn(0f, 1f)))
    }

    fun nextHit() {
        val s = _state.value.search
        if (s.hits.isNotEmpty()) selectHit((s.selected + 1).mod(s.hits.size))
    }

    fun previousHit() {
        val s = _state.value.search
        if (s.hits.isNotEmpty()) selectHit((if (s.selected < 0) s.hits.size - 1 else s.selected - 1).mod(s.hits.size))
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = PdfSearch()) }
    }

    // --- settings -----------------------------------------------------------------------------

    fun setScroll(scroll: PdfScroll) = settings.updateBook { PdfSettings.withScroll(it, scroll) }

    fun setFit(fit: PdfFit) = settings.updateBook { PdfSettings.withFit(it, fit) }

    fun setNight(on: Boolean) {
        container.appScope.launch { runCatching { container.settings.edit { it[nightKey] = on } } }
    }

    /** This book's settings become the default for every PDF (`reader/defaults/pdf`), and the book follows it. */
    fun useForAllPdfs() {
        val current = settings.effective.value
        settings.updateDefault { current }
        settings.resetBook()
    }

    fun resetBookSettings() = settings.resetBook()

    override fun onCleared() {
        progress.close()
        searchJob?.cancel()
        val doc = engine
        engine = null
        if (doc != null) container.appScope.launch { doc.close() }
        appContext.unregisterComponentCallbacks(trimInBackground)
        cache.clear()
    }

    companion object {
        const val MAX_HITS = 500
        private const val SIZE_BATCH = 48
        private const val SIZE_CHUNK = 4
    }
}

/**
 * Runs [clear] once the app is in the background (`TRIM_MEMORY_BACKGROUND` or more, where Coil's
 * loaders clear theirs): the reader's page bitmaps (up to a quarter of the heap limit) would make a
 * cached process a likelier victim of the low-memory killer, and then coming back is a cold start.
 * Not at `TRIM_MEMORY_UI_HIDDEN`, which comes with every screen lock mid-read. The pages on screen
 * keep their bitmaps (rememberPageBitmap's state); pages further away render again when reached.
 */
internal class TrimInBackground(private val clear: () -> Unit) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clear()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Superseded by onTrimMemory")
    override fun onLowMemory() = Unit
}
