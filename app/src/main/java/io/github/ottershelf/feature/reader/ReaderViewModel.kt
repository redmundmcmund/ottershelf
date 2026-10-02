package io.github.ottershelf.feature.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.download.KeepOnOpen
import io.github.ottershelf.core.model.FileProgress
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.model.SaveProgress
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.sync.ProgressStore
import io.github.ottershelf.core.sync.ProgressStore.Position
import io.github.ottershelf.core.sync.ReadingVisit
import io.github.ottershelf.core.tracking.SessionQuery
import io.github.ottershelf.core.util.IsoTime
import io.github.ottershelf.feature.book.trustedPace
import io.github.ottershelf.feature.notes.NoteChanges
import io.github.ottershelf.feature.quotes.QuotePosition
import io.github.ottershelf.feature.reader.annotations.Annotation
import io.github.ottershelf.feature.reader.annotations.AnnotationDraft
import io.github.ottershelf.feature.reader.annotations.AnnotationTapPayload
import io.github.ottershelf.feature.reader.annotations.ApiNotesRemote
import io.github.ottershelf.feature.reader.annotations.Cfi
import io.github.ottershelf.feature.reader.annotations.NoteDialogState
import io.github.ottershelf.feature.reader.annotations.NotesSnapshot
import io.github.ottershelf.feature.reader.annotations.NotesSync
import io.github.ottershelf.feature.reader.annotations.ReaderNotes
import io.github.ottershelf.feature.reader.annotations.ReaderNotesScheduler
import io.github.ottershelf.feature.reader.annotations.ReaderNotesStore
import io.github.ottershelf.feature.reader.annotations.ReaderNotesUiState
import io.github.ottershelf.feature.reader.annotations.SearchPayload
import io.github.ottershelf.feature.reader.annotations.SearchState
import io.github.ottershelf.feature.reader.annotations.SelectionPayload
import io.github.ottershelf.feature.reader.annotations.SelectionPopupState
import io.github.ottershelf.feature.reader.annotations.displayHex
import io.github.ottershelf.feature.reader.annotations.shareText
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** What the ViewModel needs from the page on screen (the screen's WebView). Main thread. */
internal interface ReaderPage {
    /** Runs [code] in the page. */
    fun js(code: String)

    /** Runs [code] and hands back its result, JSON-encoded (WebView.evaluateJavascript). */
    fun evaluate(code: String, onResult: (String?) -> Unit)
}

/**
 * The reader's logic, ported from the Nexus ReaderActivity with its rules unchanged: where to open
 * (the server's position, this device's offline one, or the user's pick when both moved), the
 * debounced position save (2 s after the last page turn, kept on the device first and pushed with
 * the conflict checks of [ProgressStore]), reading sessions (ended after 5 min idle or on leaving,
 * queued and synced), and the fresh start after a book was marked Read/Unread by hand. Nothing is
 * saved or sent until the user moves (a turn, swipe, scroll or jump) since opening ([ReadingVisit]).
 *
 * What changed for the phone: the WebView belongs to the screen and may be thrown away and made
 * again (the ViewModel survives it and reopens at the newest location); the page pushes its full
 * location after every settle and when it's hidden, so leaving never waits for the WebView: the
 * final save runs from [onCleared], sending in the app scope.
 *
 * Main thread only, apart from [requests] (WebView IO threads) and [Bridge] (the JavaBridge thread,
 * which posts here).
 */
class ReaderViewModel(
    private val container: AppContainer,
    private val bookId: Long,
    private val fileId: Long,
    title: String,
    appContext: Context,
    /** Open at this CFI (a highlight, from Highlights or Notes) instead of the reading position. */
    private val jumpCfi: String? = null,
    /** The file's format, lower case (null: EPUB). KEPUB, MOBI, AZW3, AZW and FB2 are read as one whole file. */
    private val format: String? = null,
) : ViewModel() {

    private val progress = container.progress

    /** The WebView's version when it is too old for the reader (an error then says to update it). */
    private val oldWebView: String? = WebViewVersion.outdated(appContext)
    private val api = container.api
    private val appScope = container.appScope
    private val prefsStore = ReaderPrefsStore(container.settings)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(ReaderUiState(title = title))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _messages = Channel<ReaderMessage>(Channel.BUFFERED)
    val messages: Flow<ReaderMessage> = _messages.receiveAsFlow()

    private val wholeFile = ReaderRequests.readsWholeFile(format)
    private val localCopy: Deferred<LocalBook?>
    internal val requests: ReaderRequests
    private val prefsLoaded: Deferred<ReaderPrefs>

    private var page: ReaderPage? = null
    private var appColors: ReaderAppColors? = null
    private var closed = false

    // Opening: decided once per screen (the server is asked once); a new page reopens at [latest].
    // Decided without the server and nothing read since, Retry decides again (see [retry]).
    private var decided = false
    private var deciding = false
    private var decidedWithServer = false
    private var start: Position? = null

    /** The system may take the renderer back once (see [onPageGone]) until the book opens again. */
    private var autoReopenAllowed = true

    // Progress: debounced like the web reader (2 s after the last page turn), flushed on pause/exit.
    private var latest: Relocate? = null
    private var unsaved = false
    private val saveRunnable = Runnable { saveProgress() }

    // Reading sessions, as useReadingSession does (end after 5 min idle or when leaving), sent only
    // once the user has moved since opening (a turn, swipe or scroll: [Relocate.turned], or a jump the user
    // picks). Until then nothing is saved either: opening a book and leaving without turning a page
    // isn't reading, yet a session on the file route would set the book to Reading on the server.
    private val visit = ReadingVisit(clock = System::currentTimeMillis, send = ::sendSession)

    // Which relocates are the user's reading: not the ones foliate makes by itself (a section settling, a
    // chapter's CSS animating its layout), or a book left open there would be read forever.
    private val signs = ReadingSigns(visit)

    // A fresh start saves nothing until the user actually turns a page: merely opening a Read book
    // shouldn't turn it into Re-reading on the server. Only the user's own moves end it ([Relocate.turned],
    // or a jump the user picks): foliate relocates by itself while a section settles, and the fraction
    // then changes with the page count, so a changed fraction isn't a page turn.
    private var freshStart = false

    // Whether the server is known to still have this device's baseline, so a position can be sent
    // without checking first. Cleared when a send fails, since anything could happen on the server
    // while this device can't reach it.
    private var verified = false
    private var pushing = false
    private var pushAgain = false

    /** A position dialog is showing (sends keep reporting the conflict until it's answered). */
    private var choosing = false
    private var pendingChoice: CompletableDeferred<Boolean>? = null

    init {
        val downloads = container.downloads
        val book = bookId
        val file = fileId
        val whole = wholeFile
        // Opening the copy reads its zip index and info (an EPUB) or its folder: off the main thread.
        localCopy = appScope.async(Dispatchers.IO) {
            runCatching {
                if (whole) downloads.openOffline(book, file)?.takeIf { ReaderRequests.readsWholeFile(it.format) }?.let { LocalBook.Whole(it) }
                else downloads.openEpub(book, file)?.let { LocalBook.Epub(it) }
            }.getOrNull()
        }
        requests = ReaderRequests(bookId, fileId, localCopy, api, wholeFile = whole)
        progress.opened(fileId)
        prefsLoaded = viewModelScope.async { prefsStore.prefs.first() }
        viewModelScope.launch {
            prefsStore.prefs.collect { p -> _state.update { it.copy(prefs = p) } }
        }
    }

    // --- time left (the footer's time modes) -------------------------------------------------

    private val paceStore = ReaderPaceStore(container.settings, container.session.accountKey())

    /** The user's pace in this book (percent per hour), once known; else foliate's estimate is shown. */
    private var percentPerHour: Double? = null
    private var lastTime: Relocate? = null

    init {
        loadPace()
    }

    /** The pace kept on the device at once, then the server's (the book's reading sessions). */
    private fun loadPace() {
        val tracking = container.tracking
        viewModelScope.launch {
            runCatching { paceStore.get(bookId) }.getOrNull()?.let(::setPace)
            val stats = runCatching { tracking.sessions(bookId, SessionQuery(pageSize = 1)).stats }.getOrNull() ?: return@launch
            val pace = trustedPace(stats, null)?.percentPerHour
            setPace(pace)
            runCatching { paceStore.put(bookId, pace) }
        }
    }

    private fun setPace(pace: Double?) {
        percentPerHour = pace
        lastTime?.let(::showTimeLeft)
    }

    private fun showTimeLeft(r: Relocate) {
        lastTime = r
        val left = ReadingTime.timeLeft(r.fraction, r.timeSection, r.timeTotal, r.timeBook, percentPerHour)
        if (left != _state.value.timeLeft) _state.update { it.copy(timeLeft = left) }
    }

    /** A tap on the footer: the next of page, time left in the chapter, time left in the book. */
    fun cycleFooter() {
        val prefs = _state.value.prefs
        updatePrefs(prefs.copy(footerDisplayMode = FooterMode.of(prefs.footerDisplayMode).next().id))
    }

    // --- highlights, notes, bookmarks and search (feature.reader.annotations) -------------------

    private val _notesUi = MutableStateFlow(ReaderNotesUiState())
    val notesUi: StateFlow<ReaderNotesUiState> = _notesUi.asStateFlow()

    /** The book is open in the page on screen (a new page reopens it and gets the list again). */
    private var bookShown = false
    private var drawnList: String? = null
    private var currentCfi: String? = null
    private var authors: List<String> = emptyList()
    private var searchCounter = 0

    /**
     * The book's details (`GET books/:id`), asked once per open for both the authors (a book that
     * isn't downloaded) and the read status [openBook] needs.
     */
    private val detail = SharedRequest(viewModelScope) { runCatching { api.book(bookId) }.getOrNull() }

    private val notes: ReaderNotes = run {
        val account = container.session.accountKey()
        val store = ReaderNotesStore.get(appContext)
        val context = appContext.applicationContext
        ReaderNotes(
            bookId = bookId,
            store = store,
            sync = NotesSync(store, ApiNotesRemote(api), account, container.stillSignedInAs(account)),
            account = account,
            online = container.online,
            scope = appScope,
            uiScope = viewModelScope,
            schedule = { ReaderNotesScheduler.enqueue(context) },
            onChanged = { highlights ->
                container.readingChanges.changed()
                if (highlights) NoteChanges.changed(bookId)
            },
        )
    }

    init {
        notes.start()
        viewModelScope.launch { notes.state.collect { onNotes(it) } }
        viewModelScope.launch { notes.rejected.collect { _messages.trySend(ReaderMessage.NoteRejected(it.message)) } }
        val downloads = container.downloads
        viewModelScope.launch {
            val local = kotlinx.coroutines.withContext(Dispatchers.IO) { downloads.get(bookId)?.authors.orEmpty() }
            authors = local.ifEmpty { detail.get().await()?.authors?.map { it.name }.orEmpty() }
        }
    }

    private fun onNotes(snapshot: NotesSnapshot) {
        _notesUi.update { ui ->
            ui.copy(
                // Typed or photographed quotes (feature.quotes) have no place in the book: not listed or drawn here.
                annotations = snapshot.annotations.filterNot { QuotePosition.isQuote(it.cfi) },
                bookmarks = snapshot.bookmarks,
                loaded = snapshot.loaded,
                bookmarked = snapshot.bookmarks.any { Cfi.contains(currentCfi, it.cfi) },
                // An open popup follows its highlight (a colour change, or deleted elsewhere).
                popup = ui.popup?.let { p ->
                    val id = p.annotationId ?: return@let p
                    val a = follow(snapshot.annotations, id, p.cfi) ?: return@let if (p.fromTap) null else p.copy(annotationId = null)
                    p.copy(annotationId = a.id, color = displayHex(a.color), style = a.style, note = a.note)
                },
                // So do an open note dialog and delete confirmation: a local id they hold may have
                // reached the server meanwhile, and a write naming it would be dropped.
                noteDialog = ui.noteDialog?.let { d ->
                    val id = d.annotationId ?: return@let d
                    follow(snapshot.annotations, id, d.cfi)?.let { d.copy(annotationId = it.id) } ?: d
                },
                confirmDelete = ui.confirmDelete?.let { c -> follow(snapshot.annotations, c.id, c.cfi) ?: c },
            )
        }
        pushAnnotations()
    }

    private fun annotationById(list: List<Annotation>, id: Long): Annotation? {
        val resolved = notes.resolve(id)
        return list.firstOrNull { it.id == resolved || it.id == id }
    }

    /** [id]'s highlight in [list]; by its place when the id is no longer known (a local one sent since). */
    private fun follow(list: List<Annotation>, id: Long, cfi: String?): Annotation? =
        annotationById(list, id) ?: cfi?.let { c -> list.firstOrNull { it.cfi == c } }

    /** Hands the highlights to the page (only when the list changed, or a new page opened the book). */
    private fun pushAnnotations(force: Boolean = false) {
        if (!bookShown) return
        val list = kotlinx.serialization.json.buildJsonArray {
            for (a in _notesUi.value.annotations) {
                val cfi = a.cfi ?: continue
                add(buildJsonObject {
                    put("cfi", cfi)
                    put("color", displayHex(a.color))
                    put("style", a.style)
                })
            }
        }.toString()
        if (!force && list == drawnList) return
        drawnList = list
        js("readerSetAnnotations($list)")
    }

    private fun onBookShown() {
        bookShown = true
        pushAnnotations(force = true)
        // The new page has no search outlines; the results list stays, and searching again redraws them.
        _notesUi.update { it.copy(popup = null) }
    }

    private fun onSelection(s: SelectionPayload) {
        if (s.text.isBlank()) return
        val ui = _notesUi.value
        val existing = ui.annotations.firstOrNull { Cfi.sameRange(it.cfi, s.cfi) }
        _notesUi.update {
            it.copy(
                popup = SelectionPopupState(
                    text = existing?.text ?: s.text,
                    cfi = s.cfi,
                    chapter = s.chapter ?: _state.value.chapter.ifBlank { null },
                    rect = s.rect?.toRect(),
                    annotationId = existing?.id,
                    color = existing?.let { a -> displayHex(a.color) } ?: ui.lastColor,
                    style = existing?.style ?: ui.lastStyle,
                    note = existing?.note,
                ),
            )
        }
        setChromeVisible(false)
    }

    private fun onAnnotationTap(t: AnnotationTapPayload) {
        val a = _notesUi.value.annotations.firstOrNull { it.cfi == t.cfi } ?: return
        _notesUi.update {
            it.copy(
                popup = SelectionPopupState(
                    text = a.text, cfi = a.cfi, chapter = a.chapterTitle, rect = t.rect?.toRect(),
                    annotationId = a.id, color = displayHex(a.color), style = a.style, note = a.note, fromTap = true,
                ),
            )
        }
        setChromeVisible(false)
    }

    /** The popup's colour: a new highlight in [color] (with the chosen style), or the highlight recoloured. */
    fun highlight(color: String) {
        val p = _notesUi.value.popup ?: return
        val id = p.annotationId
        if (id != null) {
            notes.updateAnnotation(id, color = color)
        } else {
            val cfi = p.cfi ?: return
            notes.createAnnotation(AnnotationDraft(cfi, p.text, color, p.style, chapterTitle = p.chapter, bookFileId = fileId))
        }
        _notesUi.update { it.copy(lastColor = color, lastStyle = p.style) }
        dismissPopup()
    }

    /** The popup's style: chosen for a new highlight, applied at once to an existing one. */
    fun setHighlightStyle(style: String) {
        val p = _notesUi.value.popup ?: return
        p.annotationId?.let { notes.updateAnnotation(it, style = style) }
        _notesUi.update { it.copy(popup = p.copy(style = style), lastStyle = style) }
    }

    fun openNote() {
        val p = _notesUi.value.popup ?: return
        _notesUi.update {
            it.copy(popup = null, noteDialog = NoteDialogState(p.text, p.note.orEmpty(), p.annotationId, p.cfi, p.chapter))
        }
        js("readerClearSelection()")
    }

    fun saveNote(text: String) {
        val d = _notesUi.value.noteDialog ?: return
        val ui = _notesUi.value
        if (d.annotationId != null) {
            notes.updateAnnotation(d.annotationId, note = text.trim())
        } else if (d.cfi != null) {
            notes.createAnnotation(
                AnnotationDraft(d.cfi, d.quote, ui.lastColor, ui.lastStyle, note = text.trim().ifEmpty { null }, chapterTitle = d.chapter, bookFileId = fileId),
            )
        }
        _notesUi.update { it.copy(noteDialog = null) }
    }

    fun dismissNote() = _notesUi.update { it.copy(noteDialog = null) }

    /** The shared text for the popup's quote (the quote, the book, its authors). */
    fun shareQuote(): String? {
        val p = _notesUi.value.popup ?: return null
        return shareText(p.text, _state.value.title, authors)
    }

    fun askDelete() {
        val p = _notesUi.value.popup ?: return
        val a = p.annotationId?.let { annotationById(_notesUi.value.annotations, it) } ?: return
        _notesUi.update { it.copy(popup = null, confirmDelete = a) }
        js("readerClearSelection()")
    }

    fun confirmDelete() {
        val a = _notesUi.value.confirmDelete ?: return
        notes.deleteAnnotation(a.id)
        _notesUi.update { it.copy(confirmDelete = null) }
    }

    fun cancelDelete() = _notesUi.update { it.copy(confirmDelete = null) }

    fun dismissPopup() {
        _notesUi.update { it.copy(popup = null) }
        js("readerClearSelection()")
    }

    /** Adds a bookmark for the page on screen, or removes the ones on it. */
    fun toggleBookmark() {
        val cfi = currentCfi ?: return
        val here = _notesUi.value.bookmarks.filter { Cfi.contains(cfi, it.cfi) }
        if (here.isNotEmpty()) {
            here.forEach { notes.deleteBookmark(it.id) }
        } else {
            val s = _state.value
            val title = s.chapter.trim().ifEmpty { "${(s.fraction * 100).roundToInt()}%" }
            notes.addBookmark(cfi, title)
        }
    }

    fun deleteBookmark(id: Long) = notes.deleteBookmark(id)

    /** Jumps to a highlight, bookmark or search match. */
    fun goToCfi(cfi: String) {
        freshStart = false
        visit.markMoved()
        signs.expect()
        js("readerGoTo(${ApiJson.encodeToString(String.serializer(), cfi)})")
        setChromeVisible(false)
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.length < MIN_SEARCH) return clearSearch()
        val id = ++searchCounter
        _notesUi.update { it.copy(search = SearchState(query = q, searching = true, id = id)) }
        js("readerSearch(${ApiJson.encodeToString(String.serializer(), q)}, $id)")
    }

    fun clearSearch() {
        searchCounter++
        _notesUi.update { it.copy(search = SearchState()) }
        js("readerClearSearch()")
    }

    private fun onSearchResults(r: SearchPayload) {
        _notesUi.update { ui ->
            if (r.id != ui.search.id) return@update ui
            ui.copy(
                search = ui.search.copy(
                    hits = ui.search.hits + r.items,
                    progress = r.progress.toFloat().coerceIn(0f, 1f),
                    searching = !r.done,
                    done = r.done,
                    error = r.error,
                ),
            )
        }
    }

    // --- the page ----------------------------------------------------------------------------

    /** [page] is the one on screen from now on. Returns its `window.Android`, a [Bridge] of its own. */
    internal fun attach(page: ReaderPage): Bridge {
        this.page = page
        bookShown = false
        return Bridge(page)
    }

    internal fun detach(page: ReaderPage) {
        if (this.page === page) this.page = null
    }

    /** The app theme's page colours changed (or are first known): an App-themed book follows (a custom one's links too). */
    fun setAppColors(colors: ReaderAppColors) {
        if (colors == appColors) return
        val before = ReaderStyles.pageSettings(_state.value.prefs, appColors)
        appColors = colors
        val after = ReaderStyles.pageSettings(_state.value.prefs, appColors)
        if (after != before) js("readerSettings(${settingsJson(_state.value.prefs)})")
    }

    fun updatePrefs(next: ReaderPrefs) {
        val previous = _state.value.prefs
        if (next == previous) return
        _state.update { it.copy(prefs = next) }
        viewModelScope.launch { prefsStore.save(next) }
        // Only what changes the page is sent (the footer's mode doesn't).
        if (ReaderStyles.pageSettings(next, appColors) != ReaderStyles.pageSettings(previous, appColors)) {
            js("readerSettings(${settingsJson(next)})")
        }
    }

    fun toggleChrome() = setChromeVisible(!_state.value.chromeVisible)

    fun setChromeVisible(visible: Boolean) {
        if (_state.value.chromeVisible != visible) _state.update { it.copy(chromeVisible = visible) }
        // The chapter label may lag behind a quick run of page turns; refresh it when shown.
        if (visible) requestLocation { r -> r?.let { showFullLocation(it) } }
    }

    fun seek(fraction: Float) {
        freshStart = false
        visit.markMoved()
        signs.expect()
        js("readerGoToFraction(${fraction.coerceIn(0f, 1f)})")
    }

    fun goTo(href: String) {
        freshStart = false
        visit.markMoved()
        signs.expect()
        js("readerGoTo(${ApiJson.encodeToString(String.serializer(), href)})")
        setChromeVisible(false)
    }

    fun next() = js("readerNext()")

    fun previous() = js("readerPrev()")

    /**
     * After an error: a new WebView loads the page again, which reopens the book. If where to open
     * was decided without the server (it couldn't be reached) and nothing has been read since, the
     * server is asked again: it may well have a newer position by now, and opening at the old one
     * would save over it (or ask about two positions for reading that never happened).
     */
    fun retry() {
        if (decidesAgainOnRetry(decided, decidedWithServer, readSince = latest != null, _state.value.error)) {
            decided = false
            start = null
            detail.reset() // the read status is asked again too, as the first open did
        }
        _state.update { it.copy(error = null, loading = true, pageGeneration = it.pageGeneration + 1) }
    }

    /** The user answered the two-positions prompt. */
    fun choose(keepMine: Boolean) {
        pendingChoice?.complete(keepMine)
    }

    // The page's functions exist only once reader.js (a module, so deferred) has run; a call made
    // before that, e.g. the theme's colours arriving while the page loads, is dropped rather than
    // thrown. readerOpen passes the settings itself, so nothing is lost.
    private fun js(code: String) {
        page?.js("if (window.readerSettings) { $code }")
    }

    // --- bridge (reader.js -> app) -------------------------------------------------------------

    /**
     * `window.Android` in reader.js, one for each page ([attach]). Called on the JavaBridge thread;
     * everything moves to main.
     */
    internal inner class Bridge(page: ReaderPage) {
        private val ready = PageReady(page, onScreen = { this@ReaderViewModel.page }, post = { post(it) }, open = { onPageReady() })

        /** The page is ready for the book ([PageReady]: once per page, and only from the page on screen). */
        @JavascriptInterface
        fun onReady() = ready.onReady()

        @JavascriptInterface
        fun onOpened(payload: String) = post {
            val opened = decode(Opened.serializer(), payload) ?: return@post
            autoReopenAllowed = true
            _state.update { s ->
                s.copy(
                    loading = false,
                    error = null,
                    toc = opened.toc,
                    title = s.title.ifEmpty { opened.title.orEmpty() },
                    fixedLayout = opened.fixedLayout,
                )
            }
            onBookShown()
        }

        /** A text selection settled (the app shows its popup; the WebView's own menu is suppressed). */
        @JavascriptInterface
        fun onSelection(payload: String) = post {
            decode(SelectionPayload.serializer(), payload)?.let { this@ReaderViewModel.onSelection(it) }
        }

        /** The selection changed or went away: a popup over it hides until it settles again. */
        @JavascriptInterface
        fun onSelectionCleared() = post {
            if (_notesUi.value.popup?.fromTap == false) _notesUi.update { it.copy(popup = null) }
        }

        @JavascriptInterface
        fun onAnnotationTap(payload: String) = post {
            decode(AnnotationTapPayload.serializer(), payload)?.let { this@ReaderViewModel.onAnnotationTap(it) }
        }

        @JavascriptInterface
        fun onSearchResults(payload: String) = post {
            decode(SearchPayload.serializer(), payload)?.let { this@ReaderViewModel.onSearchResults(it) }
        }

        /** Every relocate, cheap fields only (fraction/page), several times per section change. */
        @JavascriptInterface
        fun onRelocate(payload: String) = post {
            decode(Relocate.serializer(), payload)?.let { onRelocated(it) }
        }

        /** Full location (CFI, Kobo/KOReader positions, chapter), sent once the page settles. */
        @JavascriptInterface
        fun onLocation(payload: String) = post {
            decode(Relocate.serializer(), payload)?.let { onFullLocation(it) }
        }

        @JavascriptInterface
        fun onToggleUi() = post { toggleChrome() }

        @JavascriptInterface
        fun onError(message: String) = post {
            val error = when {
                requests.local == null && !container.online.value -> ReaderError.Offline
                oldWebView != null -> ReaderError.OldWebView(oldWebView)
                else -> ReaderError.Failed(message)
            }
            _state.update { it.copy(loading = false, error = error, chromeVisible = true) }
        }

        private fun post(block: () -> Unit) {
            main.post { if (!closed) block() }
        }

        private fun <T> decode(de: DeserializationStrategy<T>, payload: String): T? =
            runCatching { ApiJson.decodeFromString(de, payload) }.getOrNull()
    }

    /**
     * The WebView's renderer is gone, and that page with it. When the system took it back to free
     * memory ([crashed] false, usually in the background), a new page is made straight away (it's
     * created once the screen is back, and reopens at [latest]); once only until the book opens
     * again, so memory pressure while reading can't loop. A crash shows Retry.
     */
    internal fun onPageGone(crashed: Boolean) {
        page = null
        if (!crashed && autoReopenAllowed) {
            autoReopenAllowed = false
            retry()
            return
        }
        _state.update { it.copy(loading = false, error = ReaderError.Stopped, chromeVisible = true) }
    }

    private fun onPageReady() {
        when {
            !decided && !deciding -> openBook()
            decided -> openAt(latest?.let { Position(it.cfi, it.fraction * 100) } ?: start)
            // Still deciding: openBook opens on this page when it's done.
        }
    }

    // --- opening -------------------------------------------------------------------------------

    /**
     * Opens at the newest position: the server's, unless this device has read further without a
     * connection (sent now if the server hasn't moved since), or the one saved here if the server
     * can't be reached. If both moved, the user picks.
     */
    private fun openBook() {
        deciding = true
        viewModelScope.launch {
            val store = progress
            val record = store.get(fileId)
            prefsLoaded.await()
            // A downloaded book doesn't need the server to open: don't keep the user waiting for it.
            val local = requests.awaitLocal()
            if (local == null) keepOnOpen()
            val wait = if (local != null) SERVER_WAIT_LOCAL_MS else SERVER_WAIT_MS
            val serverProgress = async { withTimeoutOrNull(wait) { runCatching { api.fileProgress(fileId) }.getOrNull() } }
            val status = async { withTimeoutOrNull(wait) { detail.get().await()?.readStatus } }
            val server = serverProgress.await()
            val pending = record?.pendingPosition
            freshStart = false
            val position: Position? = if (server != null) {
                when (val decision = store.decideOnOpen(fileId, bookId, server, record)) {
                    is ProgressStore.Opening.Start -> {
                        verified = decision.inStep
                        if (decision.send) pushPending()
                        if (decision.atServer) freshStart = startsFromBeginning(status.await(), server)
                        if (freshStart) null else decision.position
                    }
                    is ProgressStore.Opening.Ask ->
                        choosePosition(decision.mine, decision.mineAt, decision.server, decision.serverReadAt, online = true)
                }
            } else {
                verified = false
                val conflict = record?.conflict
                if (pending != null && conflict != null) {
                    choosePosition(pending, record.pendingAt, conflict, record.conflictReadAt, online = false)
                } else record?.bestKnown
            }
            // Opened at a highlight: go there and, as after a fresh start, save nothing until the user
            // turns a page, so looking at a highlight doesn't move the user's reading position.
            val jump = jumpCfi?.let { Position(it, position?.percentage ?: 0.0) }
            if (jump != null) freshStart = true
            start = jump ?: position
            decided = true
            decidedWithServer = server != null
            deciding = false
            openAt(start)
            if (freshStart && jump == null) {
                val label = ReadStatus.of(status.await()?.status)?.label ?: ReadStatus.READ.label
                _messages.trySend(ReaderMessage.FreshStart(label))
            }
        }
    }

    /**
     * Opened from the server: keep a copy on the device, so the next open needs no connection
     * (KeepOnOpen, Settings > Offline). Quietly and in the background; this session keeps reading
     * from the server. Not when the book already has a copy (another of its files, or an older one:
     * the book page's Download replaces it) or one is on its way.
     */
    private fun keepOnOpen() {
        val downloads = container.downloads
        appScope.launch(Dispatchers.IO) {
            if (!KeepOnOpen.enabled(container.settings).first()) return@launch
            if (downloads.isDownloading(bookId) || downloads.get(bookId) != null) return@launch
            val book = detail.get().await() ?: return@launch
            val file = book.files.firstOrNull { it.id == fileId } ?: return@launch
            if (KeepOnOpen.applies(file.format)) downloads.start(book, file, quiet = true)
        }
    }

    /** Opens the book in the page at [position]; without a page yet, [onPageReady] does it. */
    private fun openAt(position: Position?) {
        val page = page ?: return
        val args = buildJsonObject {
            put("bookId", bookId)
            put("fileId", fileId)
            // reader.js reads a book in any other format than EPUB whole, with foliate's makeBook.
            put("format", if (wholeFile) JsonPrimitive(BookFormats.normalize(format)) else JsonNull)
            put("cfi", position?.cfi?.let { JsonPrimitive(it) } ?: JsonNull)
            put("percentage", position?.percentage ?: 0.0)
            put("settings", settingsJson(_state.value.prefs))
        }
        signs.expect()
        page.js("readerOpen($args)")
    }

    /** [prefs] as reader.js applies them ([ReaderStyles]), with the app's page colours for the App theme. */
    private fun settingsJson(prefs: ReaderPrefs): JsonObject =
        ApiJson.encodeToJsonElement(PageSettings.serializer(), ReaderStyles.pageSettings(prefs, appColors)).jsonObject

    /**
     * This device read on without a connection while something else (another device, the web app)
     * moved the position on the server. Neither is obviously right, so ask; nothing is sent until then.
     * Returns the position kept.
     */
    private suspend fun choosePosition(
        mine: Position, mineAt: Long, server: Position, serverReadAt: String?, online: Boolean,
    ): Position {
        val answer = CompletableDeferred<Boolean>()
        pendingChoice = answer
        choosing = true
        _state.update { it.copy(choice = PositionChoice(mine, mineAt, server, serverReadAt)) }
        try {
            return if (answer.await()) {
                progress.resolveKeepLocal(fileId, bookId, server)
                if (online) {
                    verified = true
                    pushPending()
                }
                mine
            } else {
                progress.resolveKeepServer(fileId, bookId, server)
                verified = online
                server
            }
        } finally {
            choosing = false
            pendingChoice = null
            _state.update { it.copy(choice = null) }
        }
    }

    /**
     * BookOrbit keeps status and position separate, so marking a book Read or Unread leaves the old
     * position saved. Start from the beginning if the user set one of those by hand after they last
     * read it. Deliberately not done by resetting progress on the server: a big backwards jump makes
     * the server infer a re-read (Read -> Re-reading, Unread -> Reading), and "reset reading state"
     * deletes the reading history. "auto" statuses are left alone so a book that flipped to Read at
     * the finish threshold still reopens at the last page.
     */
    private fun startsFromBeginning(status: ReadStatusInfo?, saved: FileProgress?): Boolean {
        if (status?.source != "manual") return false
        if (status.status != ReadStatus.READ.value && status.status != ReadStatus.UNREAD.value) return false
        if ((saved?.percentage ?: 0.0) <= 0.0 && saved?.cfi == null) return false // nothing to reset
        val statusAt = IsoTime.parse(status.updatedAt) ?: return false
        val readAt = IsoTime.parse(saved?.textUpdatedAt ?: saved?.updatedAt) ?: return true
        return statusAt > readAt
    }

    // --- position ------------------------------------------------------------------------------

    private fun onRelocated(r: Relocate) {
        if (r.turned) freshStart = false
        // A full location for this position arrives shortly; until then nothing new to save.
        main.removeCallbacks(saveRunnable)
        // The user's reading keeps the session going and the screen on; foliate relocating by itself doesn't.
        if (signs.onRelocate(r)) _state.update { it.copy(activity = it.activity + 1) }
        showPosition(r)
        showTimeLeft(r)
        if (r.chapterEnd != _state.value.chapterEnd || r.bookEnd != _state.value.bookEnd) {
            _state.update { it.copy(chapterEnd = r.chapterEnd, bookEnd = r.bookEnd) }
        }
        // A tapped highlight's popup stays where the highlight was: it closes once the text moved.
        if (r.turned && _notesUi.value.popup?.fromTap == true) _notesUi.update { it.copy(popup = null) }
    }

    private fun onFullLocation(r: Relocate) {
        showFullLocation(r)
        // Not moved since opening (or a fresh start not turned yet): where it opened stays as saved.
        if (freshStart || !visit.moved) return
        // The same place pushed again (the page was hidden, or relaid out): nothing new to save.
        val same = latest != null && r.cfi == latest?.cfi
        if (!same) {
            latest = r
            unsaved = true
        }
        main.removeCallbacks(saveRunnable)
        if (unsaved) main.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun showFullLocation(r: Relocate) {
        _state.update { it.copy(chapter = r.tocLabel.orEmpty(), tocHref = r.tocHref) }
        showPosition(r)
        if (r.cfi != null && r.cfi != currentCfi) {
            currentCfi = r.cfi
            val bookmarked = _notesUi.value.bookmarks.any { Cfi.contains(r.cfi, it.cfi) }
            if (bookmarked != _notesUi.value.bookmarked) _notesUi.update { it.copy(bookmarked = bookmarked) }
        }
    }

    private fun showPosition(r: Relocate) {
        _state.update { it.copy(fraction = r.fraction.toFloat().coerceIn(0f, 1f), page = r.page, pages = r.pages) }
    }

    /** Asks the page for the current full location (computing the lazy fields now). */
    private fun requestLocation(onResult: (Relocate?) -> Unit) {
        val page = page ?: return onResult(null)
        page.evaluate("readerLocation()") { result ->
            val r = runCatching {
                // evaluateJavascript hands back the JSON encoding of the returned string.
                val text = ApiJson.decodeFromString(String.serializer(), result ?: "null")
                ApiJson.decodeFromString(Relocate.serializer(), text)
            }.getOrNull()
            onResult(r)
        }
    }

    private fun saveProgress() {
        val r = latest ?: return
        if (!unsaved) return
        unsaved = false
        val body = SaveProgress(
            cfi = r.cfi,
            percentage = (r.fraction * 100).coerceIn(0.0, 100.0),
            koboLocationSource = r.source,
            koboLocationType = r.koboLocationType?.takeUnless { it is JsonNull },
            koboLocationValue = r.koboLocationValue?.takeUnless { it is JsonNull },
            koboContentSourceProgressPercent = r.contentSourceProgressPercent?.coerceIn(0.0, 100.0),
            koreaderProgress = r.koreaderProgress?.takeUnless { it is JsonNull },
        )
        val record = progress.get(fileId)
        // Where the server already is (just opened there, or jumped to its position): nothing to send.
        if (record?.pending == null && record?.baseline?.sameAs(Position(body.cfi, body.percentage)) == true) return
        // Kept on the device first, so it survives no connection, a failed send or the app closing.
        // Online, pushPending sends it now (a failed send schedules the flush); offline, the flush.
        // The connection is read once for both: were it read twice and lost in between, neither
        // the save nor the push would leave the position to the flush.
        val connected = container.online.value
        progress.savePending(fileId, bookId, body, sendingNow = connected)
        pushPending(connected)
    }

    /**
     * Sends the stored position; overlapping calls collapse into one follow-up send. [connected]:
     * the connection as the caller found it.
     */
    private fun pushPending(connected: Boolean = container.online.value) {
        if (!connected) {
            verified = false
            return
        }
        if (pushing) {
            pushAgain = true
            return
        }
        pushing = true
        appScope.launch {
            try {
                do {
                    pushAgain = false
                    val result = progress.push(fileId, checked = !verified)
                    when (result) {
                        ProgressStore.Push.SENT -> verified = true
                        ProgressStore.Push.FAILED, ProgressStore.Push.REJECTED -> verified = false
                        ProgressStore.Push.CONFLICT -> if (!closed) resolveConflictWhileReading()
                        ProgressStore.Push.NOTHING -> {}
                    }
                    // NOTHING: a sync sent it meanwhile; a save made since still goes (no flush covers it).
                } while (pushAgain && (result == ProgressStore.Push.SENT || result == ProgressStore.Push.NOTHING))
            } finally {
                pushing = false
            }
        }
    }

    /** The connection came back mid-book and the server had moved meanwhile. */
    private fun resolveConflictWhileReading() {
        if (choosing) return
        val record = progress.get(fileId) ?: return
        val mine = record.pendingPosition ?: return
        val server = record.conflict ?: return
        viewModelScope.launch {
            val chosen = choosePosition(mine, record.pendingAt, server, record.conflictReadAt, online = true)
            if (chosen === server) {
                val target = server.cfi
                if (target != null) js("readerGoTo(${ApiJson.encodeToString(String.serializer(), target)})")
                else js("readerGoToFraction(${server.percentage / 100})")
            }
        }
    }

    // --- sessions ------------------------------------------------------------------------------

    /** A session of this visit ([ReadingVisit]: only once the user has moved since opening). */
    private fun sendSession(body: ReadingSession) {
        // Queued on the device and sent by the next sync, which checks the position first: the server
        // de-duplicates sessions by id, so retrying is safe.
        progress.enqueueSession(fileId, bookId, body, sendingNow = container.online.value)
        container.sync.sync(force = true, baselines = false)
    }

    // --- leaving -------------------------------------------------------------------------------

    /**
     * The screen was paused (the app left the screen, the phone locked): save now and end the
     * session, as the Nexus reader did in onPause. The page is still alive, so ask it for the
     * newest location, falling back to what we have if it doesn't answer promptly.
     */
    fun onPause() {
        if (closed) return
        main.removeCallbacks(saveRunnable)
        if (freshStart || !visit.moved) {
            // Not moved since opening (or opened from the beginning and never turned a page): keep
            // the saved position as it was; the session waits for a move (ReadingVisit).
            visit.end()
            return
        }
        var answered = false
        val fallback = Runnable { if (!answered) saveProgress() }
        main.postDelayed(fallback, 1_000)
        requestLocation { r ->
            answered = true
            main.removeCallbacks(fallback)
            if (r?.cfi != null && r.cfi != latest?.cfi) {
                latest = r
                unsaved = true
            }
            saveProgress()
        }
        visit.end()
    }

    /**
     * The reader was closed. The newest full location is already here (the page pushes one after
     * every settle and when hidden), so it's saved and sent now, in the app scope, with no need to
     * keep the WebView around.
     */
    override fun onCleared() {
        closed = true
        page = null
        main.removeCallbacks(saveRunnable)
        pendingChoice?.cancel()
        if (!freshStart && visit.moved) saveProgress()
        visit.close() // a session held for want of a move is dropped here
        // The WebView may still be finishing a request from the copy on an IO thread.
        val copy = localCopy
        appScope.launch {
            delay(CLOSE_DELAY_MS)
            runCatching { copy.await()?.close() }
        }
        progress.closed(fileId)
    }

    private companion object {
        const val SAVE_DELAY_MS = 2_000L
        const val CLOSE_DELAY_MS = 3_000L
        /** How long to wait for the server's position before opening a downloaded book without it. */
        const val SERVER_WAIT_LOCAL_MS = 4_000L
        const val SERVER_WAIT_MS = 20_000L
        /** foliate's search needs two characters at least (the web's MIN_SEARCH_QUERY_LENGTH). */
        const val MIN_SEARCH = 2
    }
}

/**
 * Whether Retry asks the server where to open again: where to open was [decided] without the
 * server, and nothing has been read since (then the checked push covers it). Not after the renderer
 * died ([ReaderError.Stopped]): the book had opened then.
 */
internal fun decidesAgainOnRetry(decided: Boolean, withServer: Boolean, readSince: Boolean, error: ReaderError?): Boolean =
    decided && !withServer && !readSince && error !is ReaderError.Stopped

/**
 * A page's `onReady` ([ReaderViewModel.Bridge]): heard once, and only while [page] is the one on
 * screen, then the book opens in it. reader.js is one of the page's own scripts, so a book can load
 * it again inside a chapter, and that copy said it was ready too: the book then opened again,
 * unseen, under the one the user was reading (reader.js keeps such a copy quiet as well). An old page's
 * late onReady no longer opens the book in the new one either.
 */
internal class PageReady(
    private val page: ReaderPage,
    /** The page on screen now (main thread). */
    private val onScreen: () -> ReaderPage?,
    /** Runs a block on the main thread. */
    private val post: (() -> Unit) -> Unit,
    private val open: () -> Unit,
) {
    private val heard = AtomicBoolean(false)

    /** On the JavaBridge thread. */
    fun onReady() {
        if (heard.compareAndSet(false, true)) post { if (onScreen() === page) open() }
    }
}

/**
 * One request shared by everyone who asks for it: [get] starts [fetch] in [scope], or hands back
 * the one under way or done. A failure (null) isn't kept, so the next [get] asks again; [reset]
 * forgets an answer too. Main thread only.
 */
internal class SharedRequest<T : Any>(private val scope: CoroutineScope, private val fetch: suspend () -> T?) {
    private var request: Deferred<T?>? = null

    fun get(): Deferred<T?> {
        request?.let { return it }
        lateinit var started: Deferred<T?>
        // Lazy, so [request] names it before it can finish (Main.immediate runs it at once).
        started = scope.async(start = CoroutineStart.LAZY) {
            fetch().also { if (it == null && request === started) request = null }
        }
        request = started
        started.start()
        return started
    }

    fun reset() {
        request = null
    }
}
