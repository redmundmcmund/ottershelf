package io.github.ottershelf.feature.notes

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
import io.github.ottershelf.core.settings.AppSettingsRepository
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookFacet
import io.github.ottershelf.feature.notes.model.HubOverview
import io.github.ottershelf.feature.notes.model.NoteFilter
import io.github.ottershelf.feature.quotes.QuotePhotos
import java.io.File

@Immutable
data class NotesUiState(
    val filter: NoteFilter = NoteFilter(),
    val items: List<Annotation> = emptyList(),
    /** Matching the filter (the server's count; the liked list's size when Liked is on). */
    val total: Int = 0,
    val withNotes: Int = 0,
    val books: Int = 0,
    /** The whole library's totals and colours (for the colour filter). */
    val overview: HubOverview? = null,
    val bookOptions: List<BookFacet> = emptyList(),
    val liked: Set<Long> = emptySet(),
    /** Quotes with a photo kept on this phone (`AppSettings.quotePhotos`). */
    val photos: Set<Long> = emptySet(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    /** The random note sheet: open while [randomOpen]; [random] is the note shown. */
    val randomOpen: Boolean = false,
    val random: Annotation? = null,
    val randomLoading: Boolean = false,
    val randomFailed: Boolean = false,
)

/**
 * Random highlights for a filter without repeats until every one was shown: the Notes feed's
 * shuffle and Memorize. The server's list is indexed at random (one-item pages); liked notes are
 * fetched once and picked from on the phone.
 */
class RandomNotes(private val repo: NotesRepository, private val filter: NoteFilter, private val liked: () -> Map<Long, Long>) {
    private var pool: List<Annotation>? = null
    private var total: Int? = null
    private val seen = mutableSetOf<Int>()
    private var last: Int? = null

    /** How many there are to pick from, once known. */
    val count: Int? get() = pool?.size ?: total

    suspend fun next(): Annotation? {
        if (filter.liked) {
            val all = pool ?: repo.likedNotes(liked(), filter).also { pool = it }
            val i = pick(all.size) ?: return null
            return all[i]
        }
        val n = total ?: readTotal()
        val i = pick(n) ?: return null
        repo.remote.hub(filter, i + 1, 1).items.firstOrNull()?.let { return it }
        // Past the end: highlights were deleted since the total was read. Read it again, pick again.
        seen.clear()
        last = null
        val j = pick(readTotal()) ?: return null
        return repo.remote.hub(filter, j + 1, 1).items.firstOrNull()
    }

    private suspend fun readTotal(): Int = repo.remote.hub(filter, 1, 1).total.also { total = it }

    private fun pick(n: Int): Int? {
        val i = NotesLogic.randomIndex(n, seen, last) ?: return null
        if (seen.count { it < n } >= n) seen.clear()
        seen += i
        last = i
        return i
    }
}

/**
 * The Notes feed (root, the drawer's Tracking > Notes): every highlight across the library, newest
 * first, 30 a page, with the shell's search and the book, colour, has-note and liked filters; a
 * random note; likes kept in the app settings key.
 */
class NotesViewModel(container: AppContainer, query: String?, private val appContext: Context) : ViewModel() {

    private val api = container.api
    private val settings: AppSettingsRepository = container.appSettings
    private val account = container.session.accountKey()
    private val appScope = container.appScope
    private val repo = NotesRepository(ApiNotesRemote(api), settings, account)
    private val remote = repo.remote

    private val _state = MutableStateFlow(NotesUiState(filter = NoteFilter(query = query?.takeIf { it.isNotBlank() })))
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    private val _messages = Channel<NotesMessage>(Channel.BUFFERED)
    val messages: Flow<NotesMessage> = _messages.receiveAsFlow()

    private var nextPage = 1
    private var loadJob: Job? = null
    private var randomJob: Job? = null
    private var random: RandomNotes? = null

    init {
        load(pull = false)
        viewModelScope.launch {
            settings.settings.collect { s ->
                val liked = s.notes.liked.keys
                val photos = QuotePhotos.onThisPhone(appContext, account, s.quotePhotos)
                _state.update { st ->
                    // Unliked while the Liked filter shows: it goes.
                    if (st.filter.liked) st.copy(liked = liked, photos = photos, items = st.items.filter { it.id in liked }, total = st.items.count { it.id in liked })
                    else st.copy(liked = liked, photos = photos)
                }
            }
        }
        viewModelScope.launch {
            runCatching { remote.overview() }.getOrNull()?.let { o -> _state.update { it.copy(overview = o) } }
        }
        viewModelScope.launch {
            runCatching { remote.books(BOOK_OPTIONS) }.getOrNull()?.let { b -> _state.update { it.copy(bookOptions = b) } }
        }
        viewModelScope.launch {
            // A change that can touch what's shown: any book, the filtered one, or one holding a liked
            // highlight while Liked is on (the reader syncs one highlight at a time while Notes waits under it).
            NoteChanges.changes.collect { c ->
                val filter = _state.value.filter
                val book = c.bookId
                val shown = book == null || when {
                    filter.bookId != null -> book == filter.bookId
                    filter.liked -> book in settings.settings.value.notes.liked.values
                    else -> true
                }
                if (shown) reloadLoaded()
            }
        }
    }

    fun cover(bookId: Long): Any? = runCatching { api.unversionedThumbnailUrl(bookId) }.getOrNull()

    fun refresh() = load(pull = true)

    fun retry() = load(pull = false)

    private fun setFilter(filter: NoteFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter, items = emptyList(), endReached = false) }
        random = null
        load(pull = false)
    }

    fun setBook(book: BookFacet?) = setFilter(_state.value.filter.copy(bookId = book?.bookId, bookTitle = book?.bookTitle))

    fun setColor(hex: String?) = setFilter(_state.value.filter.copy(color = hex))

    fun toggleHasNote() = setFilter(_state.value.filter.let { it.copy(hasNote = !it.hasNote) })

    fun toggleLikedFilter() = setFilter(_state.value.filter.let { it.copy(liked = !it.liked) })

    /** [note]'s kept photo, if it is still on this phone. */
    fun photo(note: Annotation): File? = QuotePhotos.of(appContext, account, settings, note.id)

    /** Deletes the quote [id]'s kept photo from this phone (asked first). */
    fun removePhoto(id: Long) {
        appScope.launch { QuotePhotos.remove(appContext, account, settings, id) }
    }

    fun toggleLike(note: Annotation) {
        if (!repo.toggleLike(note)) _messages.trySend(NotesMessage.LikesFull)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.endReached || s.error != null || s.filter.liked) return
        loadPage(first = false, pull = false)
    }

    private fun load(pull: Boolean) = loadPage(first = true, pull = pull)

    private fun loadPage(first: Boolean, pull: Boolean) {
        if (first) loadJob?.cancel() else if (loadJob?.isActive == true) return
        val filter = _state.value.filter
        loadJob = viewModelScope.launch {
            _state.update {
                when {
                    pull -> it.copy(refreshing = true)
                    first -> it.copy(loading = true, error = null)
                    else -> it.copy(loadingMore = true)
                }
            }
            try {
                if (filter.liked) {
                    // Kept for the process (LikedNotesCache): a chip or search here filters on the
                    // phone; a pull fetches the liked books again.
                    val notes = repo.likedNotes(settings.settings.value.notes.liked, filter, fresh = pull)
                    _state.update {
                        it.copy(
                            loading = false, refreshing = false, loadingMore = false, error = null,
                            items = notes, total = notes.size, withNotes = notes.count { n -> n.hasNote },
                            books = notes.map { n -> n.bookId }.distinct().size, endReached = true,
                        )
                    }
                } else {
                    val page = if (first) 1 else nextPage
                    val result = remote.hub(filter, page, PAGE_SIZE)
                    nextPage = page + 1
                    _state.update { s ->
                        val items = if (first) result.items else (s.items + result.items).distinctBy { it.id }
                        s.copy(
                            loading = false, refreshing = false, loadingMore = false, error = null,
                            items = items, total = result.total, withNotes = result.stats.withNotes, books = result.stats.books,
                            endReached = result.items.isEmpty() || items.size >= result.total,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                _state.update { it.copy(loading = false, refreshing = false, loadingMore = false, error = if (first || it.items.isEmpty()) detail else null) }
            }
        }
    }

    /**
     * What's shown again, quietly (a change made elsewhere): the pages loaded so far, so the list
     * keeps its length and the user's place, rather than going back to the first page. Liked: the list again
     * (the changed book is fetched again, the others come from the cache).
     */
    private fun reloadLoaded() {
        val filter = _state.value.filter
        if (filter.liked) return load(pull = false)
        val pages = (nextPage - 1).coerceAtLeast(1)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val items = mutableListOf<Annotation>()
                var page = 1
                var last = remote.hub(filter, page, PAGE_SIZE)
                items += last.items
                while (page < pages && last.items.isNotEmpty() && items.size < last.total) {
                    page++
                    last = remote.hub(filter, page, PAGE_SIZE)
                    items += last.items
                }
                nextPage = page + 1
                val merged = items.distinctBy { it.id }
                _state.update { s ->
                    s.copy(
                        loading = false, refreshing = false, loadingMore = false, error = null,
                        items = merged, total = last.total, withNotes = last.stats.withNotes, books = last.stats.books,
                        endReached = last.items.isEmpty() || merged.size >= last.total,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                _state.update { it.copy(loading = false, refreshing = false, loadingMore = false, error = if (it.items.isEmpty()) detail else null) }
            }
        }
    }

    /** Opens the random note sheet with a note picked at random from what the filters show. */
    fun shuffle() {
        _state.update { it.copy(randomOpen = true) }
        randomJob?.cancel()
        randomJob = viewModelScope.launch {
            _state.update { it.copy(randomLoading = true, randomFailed = false) }
            try {
                val source = random ?: RandomNotes(repo, _state.value.filter) { settings.settings.value.notes.liked }.also { random = it }
                val note = source.next()
                _state.update { it.copy(random = note, randomLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(randomLoading = false, randomFailed = true) }
            }
        }
    }

    fun closeRandom() {
        randomJob?.cancel()
        _state.update { it.copy(randomOpen = false, random = null, randomLoading = false, randomFailed = false) }
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val BOOK_OPTIONS = 50
    }
}
