package io.github.ottershelf.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.model.AuthorSummary
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.model.SeriesSummary
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.ui.components.coverModel

/**
 * What a paged grid shows (Nexus BookGridFragment / PagedGridFragment state).
 *
 * @property total the server's count, -1 until the first page arrives.
 * @property loading a page request is in flight.
 * @property refreshing the user pulled to refresh (what's shown stays until the new first page comes).
 * @property error why the first load failed; only set while there is nothing to show.
 */
data class PagedState<T>(
    val items: List<T> = emptyList(),
    val total: Int = -1,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    val endReached: Boolean get() = total >= 0 && items.size >= total

    /** Loaded, and nothing there. */
    val empty: Boolean get() = !loading && error == null && total >= 0 && items.isEmpty()

    /** The refresh indicator: pulled, or the first page still coming (the Nexus SwipeRefresh spinner). */
    val showRefresh: Boolean get() = refreshing || (loading && items.isEmpty() && error == null)
}

/**
 * The Nexus paging, shared by the book grid, Authors and Series: pages of [pageSize] loaded as the
 * grid nears its end, one request at a time; a refresh keeps what's on screen until the new first
 * page arrives, so pulling without a connection doesn't wipe the grid. Items already listed are
 * skipped when a later page repeats them (the list can shift between pages), so grid keys stay unique.
 */
class Pager<T : Any>(
    private val scope: CoroutineScope,
    private val pageSize: Int,
    private val keyOf: (T) -> Any,
    private val load: suspend (page: Int, size: Int) -> Pair<List<T>, Int>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()

    private val _refreshFailures = Channel<String>(Channel.BUFFERED)

    /** A refresh failed while something was showing (the Nexus "Couldn't refresh" toast): the error text. */
    val refreshFailures: Flow<String> = _refreshFailures.receiveAsFlow()

    private var nextPage = 0
    private var job: Job? = null

    /** The first page, unless something is already loaded or loading. */
    fun start() {
        if (_state.value.items.isEmpty() && job == null) loadPage(0, replace = false)
    }

    /** The next page, unless one is loading or everything is listed. */
    fun loadMore() = loadPage(nextPage, replace = false)

    /** Pull to refresh: starts over from the first page. */
    fun refresh() {
        job?.cancel()
        job = null
        _state.update { it.copy(refreshing = true) }
        loadPage(0, replace = true)
    }

    /** After a failed first load. */
    fun retry() = loadPage(0, replace = true)

    /** A different list (another sort or filter): clears what's shown and loads the first page. */
    fun restart() {
        job?.cancel()
        job = null
        nextPage = 0
        _state.value = PagedState()
        loadPage(0, replace = true)
    }

    /** Every item shown, through [change] (a book edited on this phone), without asking the server. */
    fun update(change: (T) -> T) = _state.update { it.copy(items = it.items.map(change)) }

    private fun loadPage(page: Int, replace: Boolean) {
        if (job?.isActive == true) return
        if (!replace && _state.value.endReached) return
        _state.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            try {
                var current = page
                var repeatPages = 0
                while (true) {
                    val (list, count) = load(current, pageSize)
                    nextPage = current + 1
                    var added = 0
                    _state.update { state ->
                        val items = if (replace) {
                            list.distinctBy(keyOf)
                        } else {
                            val seen = state.items.mapTo(HashSet(), keyOf)
                            state.items + list.filter { seen.add(keyOf(it)) }
                        }
                        added = items.size - state.items.size
                        // An empty page before the count is reached (the list shrank): stop asking.
                        val total = if (!replace && list.isEmpty()) items.size else count
                        state.copy(items = items, total = total, loading = false, refreshing = false, error = null)
                    }
                    // A page of nothing but repeats (the list shifted): the grid's end didn't move,
                    // so nothing would ask again. Ask for the next one now (a few at most).
                    if (replace || list.isEmpty() || added > 0 || _state.value.endReached || ++repeatPages > MAX_REPEAT_PAGES) break
                    current = nextPage
                    _state.update { it.copy(loading = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val showing = _state.value.items.isNotEmpty()
                _state.update {
                    it.copy(loading = false, refreshing = false, error = if (showing) null else e.message.orEmpty())
                }
                if (showing && replace) _refreshFailures.trySend(e.message.orEmpty())
            }
        }
    }
}

/** A page of books, authors or series, as every grid asks for them (Nexus PAGE_SIZE). */
internal const val PAGE_SIZE = 60

/** How many pages of nothing but repeats [Pager] skips over in a row before waiting for a scroll. */
private const val MAX_REPEAT_PAGES = 3

/**
 * One source's books (Nexus BookGridFragment): `all`, a library, scope or collection from the
 * drawer, or an author's or series' books (the pushed BooksActivity), optionally searched; sorted
 * and filtered as this list was last left on the device ([LibrarySortPrefs]; a search inside a
 * list keeps its sort and filters).
 */
class BookListViewModel(
    private val container: AppContainer,
    sourceKey: String,
    title: String,
    val query: String?,
) : ViewModel() {
    val source: BookSource = BookSource.fromKey(sourceKey, title)
    val kind: ListKind = ListKind.of(source)
    private val prefsKey = ListKind.prefsKey(source)
    private val prefs = LibrarySortPrefs(container.settings) { container.session.accountKey() }
    private val viewPrefs = LibraryViewPrefs(container.settings) { container.session.accountKey() }
    private val remote = ApiBookListRemote(container.api)

    private val _sort = MutableStateFlow<ListSort?>(null)

    /** The list's sort and filters; null for the moment it takes to read them from the device. */
    val sort: StateFlow<ListSort?> = _sort.asStateFlow()

    private val _view = MutableStateFlow<ListView?>(null)

    /** Grid or list and the cover size, kept like the sort ([LibraryViewPrefs]); null while being read. */
    val view: StateFlow<ListView?> = _view.asStateFlow()

    private val _resets = Channel<Unit>(Channel.CONFLATED)

    /** The sort changed: the grid goes back to the top. */
    val resets: Flow<Unit> = _resets.receiveAsFlow()

    private var loader: BookListLoader? = null

    /** Books edited on this phone (feature.bookedit) show as edited in the cards loaded before the edit. */
    private val edits = BookListEdits(container.readingChanges, source)

    val pager = Pager<BookCard>(viewModelScope, PAGE_SIZE, BookCard::id) { page, size ->
        edits.page { checkNotNull(loader).load(page, size) }
    }

    /** Statuses set on book pages since these pages loaded. */
    val statusOverrides: StateFlow<Map<Long, String>> = container.readingChanges.statusOverrides

    init {
        // From before the first page is asked for, so no edit falls between the two.
        viewModelScope.launch { edits.follow(pager) }
        viewModelScope.launch {
            // Both before the first page, so it shows in the user's view straight away.
            _view.value = viewPrefs.load(prefsKey)
            val stored = prefs.load(prefsKey, kind)
            loader = BookListLoader(remote, source, stored, query)
            _sort.value = stored
            pager.start()
        }
    }

    /** From the view menu or a pinch: shown at once and kept on the device (nothing reloads). */
    fun setView(next: ListView) {
        if (_view.value == next) return
        _view.value = next
        container.appScope.launch { viewPrefs.save(prefsKey, next) }
    }

    /** From the sheet or the chip row's Clear: kept on the device, and the list loads again. */
    fun setSort(next: ListSort) {
        val current = _sort.value ?: return
        if (next == current) return
        _sort.value = next
        loader = BookListLoader(remote, source, next, query)
        _resets.trySend(Unit)
        pager.restart()
        container.appScope.launch { prefs.save(prefsKey, next, kind) }
    }

    fun cover(book: BookCard): Any? = runCatching { container.api.coverModel(book) }.getOrNull()

    /** Before opening a book: its page shows the card's title, authors and cover at once. */
    fun opening(book: BookCard) = BookPreview.put(container.session.accountKey(), book)
}

/** Every author (Nexus AuthorsFragment), by name or, searched, by best match. */
class AuthorsViewModel(private val container: AppContainer, val query: String?) : ViewModel() {
    val pager = Pager<AuthorSummary>(viewModelScope, PAGE_SIZE, AuthorSummary::id) { page, size ->
        container.api.authors(page, size, query).let { it.items to it.total }
    }

    init {
        pager.start()
    }

    /** The portrait's URL (versioned by the server), or null for initials. */
    fun portrait(author: AuthorSummary): Any? =
        author.imageUrl?.let { runCatching { container.api.serverUrl(it) }.getOrNull() }
}

/** Every series (Nexus SeriesFragment), each with its first covers fanned out. */
class SeriesViewModel(private val container: AppContainer, val query: String?) : ViewModel() {
    val pager = Pager<SeriesSummary>(viewModelScope, PAGE_SIZE, SeriesSummary::id) { page, size ->
        container.api.series(page, size, query).let { it.items to it.total }
    }

    init {
        pager.start()
    }

    /** A series cover by book id: no version to go by (the card caches it for a day). */
    fun cover(bookId: Long): String? = runCatching { container.api.unversionedThumbnailUrl(bookId) }.getOrNull()
}
