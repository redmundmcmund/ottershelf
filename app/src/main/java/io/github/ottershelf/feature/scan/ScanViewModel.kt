package io.github.ottershelf.feature.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.model.WorkKey

/** A library book the scan found, as its rows show it. */
data class ScanBook(
    val id: Long,
    val title: String?,
    val authors: List<String> = emptyList(),
    /** Lower case, primary first. */
    val formats: List<String> = emptyList(),
    val year: Int? = null,
    /** The user's read status id (null: none). */
    val status: String? = null,
    val cover: Any? = null,
)

/** What a scanned (or typed) ISBN led to. While one shows, the camera doesn't read. */
sealed interface ScanResult {
    val isbn: Isbn

    /** The library is being searched. */
    data class Looking(override val isbn: Isbn) : ScanResult

    /** Exactly one copy: its page (or its timer) is opening. */
    data class Opening(override val isbn: Isbn, val book: ScanBook) : ScanResult

    /** Several copies (formats, editions, libraries): the user picks one. */
    data class Choose(override val isbn: Isbn, val books: List<ScanBook>) : ScanResult

    /**
     * No book in the user's libraries has this ISBN. [candidate]: what the metadata providers call it
     * (streamed in, [lookingUp] until every provider answered); [editions]: library books with the
     * same title and author, likely another edition; [canRequest]: the user's account may request books.
     */
    data class Missing(
        override val isbn: Isbn,
        val lookingUp: Boolean = true,
        val candidate: MetadataCandidate? = null,
        val lookupFailed: Boolean = false,
        val editions: List<ScanBook> = emptyList(),
        val canRequest: Boolean = false,
    ) : ScanResult

    /** The library couldn't be searched (offline, the server failed). */
    data class Failed(override val isbn: Isbn, val message: String?) : ScanResult
}

/** The typed-ISBN panel. [submitted]: the user pressed Find, so an incomplete number is flagged too. */
data class ManualEntry(val text: String = "", val submitted: Boolean = false) {
    val input: IsbnInput get() = Isbn.parse(text)

    val isbn: Isbn? get() = (input as? IsbnInput.Valid)?.isbn

    /** What's wrong, if it's worth saying now. */
    val problem: IsbnInput?
        get() = input.takeIf { it !is IsbnInput.Valid && it !is IsbnInput.Empty && (submitted || it.showWhileTyping) }

    /** Nine characters in: offer the X an ISBN-10 may end with (the number keyboard has none). */
    val offersX: Boolean
        get() = text.count { it in '0'..'9' } == 9 && text.none { it == 'x' || it == 'X' }
}

data class ScanUiState(
    /** Opened to pick the book for the reading timer. */
    val forTimer: Boolean = false,
    /** Opened to pick a book for the screen that opened it (handed back, [ScanNav.Picked]). */
    val pick: Boolean = false,
    val manual: ManualEntry? = null,
    val result: ScanResult? = null,
) {
    /** The camera reads barcodes only while nothing covers it. */
    val analysing: Boolean get() = manual == null && result == null
}

/** Where the scan goes next (the screen navigates). */
sealed interface ScanNav {
    data class OpenBook(val bookId: Long) : ScanNav
    data class OpenTimer(val bookId: Long) : ScanNav

    /** Pick mode: [book] goes back to the screen that opened the scanner, which closes. */
    data class Picked(val book: ScanBook) : ScanNav

    /** Book requests, searching the providers for this ISBN, prefilled with what they call it. */
    data class Request(val isbn: String, val title: String?, val author: String?) : ScanNav

    /** Book requests, to search by title (no provider knows the ISBN). */
    data object OpenRequests : ScanNav
}

/**
 * The ISBN scanner (feature.scan): a barcode read by the camera or an ISBN typed in is looked up
 * in the user's libraries (`books/query` with the `isbn` rule, [isbnQuery]); one copy opens (its page, or
 * its reading timer when [forTimer]), several are offered to choose from, none shows what the
 * metadata providers know, other editions in the library, and a way to request it.
 *
 * [canRequest]: whether the user's account may request books (`book_request_access`, as the drawer
 * decides). [remember]: keeps a found card for the book page's preview. The ISBN on show and a
 * half-typed one are kept in [saved], so a process death looks it up again.
 */
class ScanViewModel(
    private val remote: ScanRemote,
    forTimer: Boolean,
    private val canRequest: () -> Boolean,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val remember: (ScanCard) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Pick mode ([ScanUiState.pick]): what it finds is handed back rather than opened. */
    pick: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState(forTimer = forTimer, pick = pick))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _navigation = Channel<ScanNav>(Channel.BUFFERED)
    val navigation: Flow<ScanNav> = _navigation.receiveAsFlow()

    private val cards = HashMap<Long, ScanCard>()
    private var job: Job? = null

    /** The ISBN the user just dismissed, ignored for a moment so the book still in view doesn't reopen it. */
    private var dismissed: Isbn? = null
    private var dismissedAt = 0L

    /** The user closed the scanner ([leave]): nothing it finds goes anywhere now. */
    private var leaving = false

    init {
        saved.get<String>(KEY_MANUAL)?.let { text -> _state.update { it.copy(manual = ManualEntry(text)) } }
        saved.get<String>(KEY_ISBN)?.let(Isbn::of13)?.let(::lookUp)
    }

    /**
     * The camera read [isbn]. Returns whether it was taken (the screen then ticks): not while a
     * result or the typing panel shows, nor the one just dismissed for [RESCAN_MS], nor once the user
     * closed the scanner.
     */
    fun detected(isbn: Isbn): Boolean {
        if (leaving || !_state.value.analysing) return false
        if (isbn == dismissed && clock() - dismissedAt < RESCAN_MS) return false
        lookUp(isbn)
        return true
    }

    private fun lookUp(isbn: Isbn) {
        job?.cancel()
        saved[KEY_ISBN] = isbn.isbn13
        saved.remove<String>(KEY_MANUAL)
        _state.update { it.copy(manual = null, result = ScanResult.Looking(isbn)) }
        job = viewModelScope.launch {
            val found = try {
                remote.byIsbn(isbn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(result = ScanResult.Failed(isbn, e.message)) }
                return@launch
            }
            found.forEach { cards[it.id] = it }
            when (found.size) {
                0 -> missing(isbn)
                1 -> {
                    val book = book(found.single())
                    _state.update { it.copy(result = ScanResult.Opening(isbn, book)) }
                    open(book)
                }
                else -> _state.update { it.copy(result = ScanResult.Choose(isbn, found.map(::book))) }
            }
        }
    }

    /** Nothing has this ISBN: ask the providers what it is, and look for its title in the library. */
    private suspend fun missing(isbn: Isbn) {
        _state.update { it.copy(result = ScanResult.Missing(isbn, canRequest = canRequest())) }
        val candidates = ArrayList<MetadataCandidate>()
        try {
            coroutineScope {
                var searched: String? = null
                var editions: Job? = null
                remote.lookUp(isbn).collect { candidate ->
                    candidates += candidate
                    val best = bestCandidate(candidates, isbn)
                    updateMissing(isbn) { it.copy(candidate = best) }
                    val title = best?.shownTitle ?: return@collect
                    val key = WorkKey.token(mainTitle(title))
                    if (key.isNotEmpty() && key != searched) {
                        searched = key
                        editions?.cancel()
                        editions = launch { findEditions(isbn, title, best.authors.orEmpty()) }
                    }
                }
                updateMissing(isbn) { it.copy(lookingUp = false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateMissing(isbn) { it.copy(lookingUp = false, lookupFailed = it.candidate == null) }
        }
    }

    private suspend fun findEditions(isbn: Isbn, title: String, authors: List<String>) {
        val books = try {
            remote.byTitle(title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // only a hint: without it the sheet still says what matters
        }
        val same = books.filter { sameWork(title, authors, it) }.take(MAX_EDITIONS)
        same.forEach { cards[it.id] = it }
        updateMissing(isbn) { it.copy(editions = same.map(::book)) }
    }

    private fun updateMissing(isbn: Isbn, change: (ScanResult.Missing) -> ScanResult.Missing) =
        _state.update { s ->
            val m = s.result as? ScanResult.Missing
            if (m == null || m.isbn != isbn) s else s.copy(result = change(m))
        }

    private fun book(card: ScanCard) = ScanBook(
        id = card.id,
        title = card.title,
        authors = card.authors,
        formats = card.formats,
        year = card.publishedYear,
        status = card.readStatus?.status,
        cover = remote.cover(card),
    )

    /** The user picked [book] (a copy or another edition). */
    fun pick(book: ScanBook) = open(book)

    private fun open(book: ScanBook) {
        if (leaving) return
        cards[book.id]?.let(remember)
        _navigation.trySend(
            when {
                _state.value.pick -> ScanNav.Picked(book)
                _state.value.forTimer -> ScanNav.OpenTimer(book.id)
                else -> ScanNav.OpenBook(book.id)
            },
        )
    }

    /** "Request it": Book requests, prefilled with what the providers call it. */
    fun request() {
        if (leaving) return
        val missing = _state.value.result as? ScanResult.Missing ?: return
        val candidate = missing.candidate
        _navigation.trySend(
            if (candidate == null) ScanNav.OpenRequests
            else ScanNav.Request(missing.isbn.isbn13, candidate.shownTitle, candidate.authors?.firstOrNull()),
        )
    }

    fun retry() {
        (_state.value.result as? ScanResult.Failed)?.let { lookUp(it.isbn) }
    }

    /** Back to the camera. */
    fun scanAgain() {
        job?.cancel()
        _state.value.result?.let {
            dismissed = it.isbn
            dismissedAt = clock()
        }
        saved.remove<String>(KEY_ISBN)
        _state.update { it.copy(result = null) }
    }

    /**
     * The user is closing the scanner (its X). The screen stays up through the navigation's crossfade,
     * the camera still reading frames: a barcode read then, or a lookup still out, must not open a
     * book over the screen the user went back to. Nothing more is looked up or opened.
     */
    fun leave() {
        leaving = true
        job?.cancel()
    }

    // --- typing the ISBN ---------------------------------------------------------------------

    fun openManual() {
        job?.cancel()
        saved.remove<String>(KEY_ISBN)
        val text = saved.get<String>(KEY_MANUAL).orEmpty()
        _state.update { it.copy(result = null, manual = ManualEntry(text)) }
    }

    fun setManual(text: String) {
        val kept = text.take(MAX_TYPED)
        saved[KEY_MANUAL] = kept
        _state.update { s -> s.copy(manual = ManualEntry(kept)) }
    }

    fun submitManual() {
        val entry = _state.value.manual ?: return
        val isbn = entry.isbn
        if (isbn != null) lookUp(isbn) else _state.update { it.copy(manual = entry.copy(submitted = true)) }
    }

    fun closeManual() {
        saved.remove<String>(KEY_MANUAL)
        _state.update { it.copy(manual = null) }
    }

    companion object {
        /** How long a dismissed ISBN is ignored when the camera reads it again. */
        const val RESCAN_MS = 3_000L
        private const val MAX_EDITIONS = 5
        /** "ISBN-13: 978-0-306-40615-7" and some slack. */
        private const val MAX_TYPED = 32
        private const val KEY_ISBN = "isbn"
        private const val KEY_MANUAL = "manual"
    }
}
