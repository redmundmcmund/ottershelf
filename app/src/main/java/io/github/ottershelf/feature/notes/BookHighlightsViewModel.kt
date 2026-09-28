package io.github.ottershelf.feature.notes

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.quotes.QuotePhotos
import io.github.ottershelf.feature.quotes.QuotePosition
import io.github.ottershelf.feature.notes.model.BookAnnotationStats
import io.github.ottershelf.ui.nav.ReaderRouter
import io.github.ottershelf.ui.nav.Route
import java.io.File

/** A one-off for a notes screen to show or do. */
sealed interface NotesMessage {
    /** Something the server refused or the network lost; [detail] is its message. */
    data class Failed(val detail: String) : NotesMessage

    /** The book's highlights as Markdown, to hand to the share sheet. */
    data class Export(val markdown: String, val fileName: String) : NotesMessage

    /** A like refused: there are as many as the settings key keeps (unlike some first). */
    data object LikesFull : NotesMessage
}

@Immutable
data class BookHighlightsUiState(
    val bookId: Long,
    val title: String,
    val author: String? = null,
    val cover: Any? = null,
    /**
     * The book's files a reader here opens (id -> format, lower case), best format first: a
     * highlight opens in its jump file when that is one of them (ui.nav.ReaderRouter.annotation).
     */
    val readerFiles: Map<Long, String> = emptyMap(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val items: List<Annotation> = emptyList(),
    val stats: BookAnnotationStats = BookAnnotationStats(),
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val liked: Set<Long> = emptySet(),
    /** Quotes with a photo kept on this phone (`AppSettings.quotePhotos`). */
    val photos: Set<Long> = emptySet(),
    val saving: Set<Long> = emptySet(),
    /** Notes whose save failed: id -> the text the user wrote, for the card's editor to show again. */
    val drafts: Map<Long, String> = emptyMap(),
    val exporting: Boolean = false,
) {
    /**
     * The reader screen [note] opens in (its CFI in the foliate reader, its page in the PDF reader),
     * or null when no reader here can show it (a quote has no place in the book).
     */
    fun readerRoute(note: Annotation): Route? {
        if (QuotePosition.isQuote(note.cfi)) return null
        val jump = note.jumpFileId
        val files = if (jump != null) listOfNotNull(readerFiles[jump]?.let { jump to it }) else readerFiles.toList()
        return files.firstNotNullOfOrNull { (id, format) -> ReaderRouter.annotation(bookId, id, format, title, note.cfi, note.pageno) }
    }
}

/** [files] a reader here opens, id -> format, in the web's default format order (EPUB first). */
internal fun readerFilesOf(files: List<BookFile>): Map<Long, String> =
    files.filter { BookFormats.isOpenable(it.format) }
        .sortedBy { f -> BookFormats.DEFAULT_PRIORITY.indexOf(BookFormats.normalize(f.format)).let { if (it < 0) Int.MAX_VALUE else it } }
        .associate { it.id to BookFormats.normalize(it.format)!! }

/**
 * One book's highlights (Highlights screen): by position through the book, 100 a page, grouped by
 * chapter on screen. Notes, colours and styles are edited here (PATCH), highlights deleted (moved to
 * the web's trash), liked (the app settings key), and the whole book exported as Markdown.
 */
class BookHighlightsViewModel(
    container: AppContainer,
    private val bookId: Long,
    title: String,
    private val appContext: Context,
) : ViewModel() {

    private val api = container.api
    private val appScope = container.appScope
    private val account = container.session.accountKey()
    private val repo = NotesRepository(ApiNotesRemote(api), container.appSettings, account)
    private val remote = repo.remote
    private val appSettings = container.appSettings

    private val _state = MutableStateFlow(BookHighlightsUiState(bookId = bookId, title = title))
    val state: StateFlow<BookHighlightsUiState> = _state.asStateFlow()

    private val _messages = Channel<NotesMessage>(Channel.BUFFERED)
    val messages: Flow<NotesMessage> = _messages.receiveAsFlow()

    private var nextPage = 1
    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            runCatching { api.book(bookId) }.getOrNull()?.let { book ->
                _state.update { s ->
                    s.copy(
                        title = book.title ?: s.title,
                        author = book.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                        cover = book.coverSource?.let { runCatching { api.thumbnailUrl(book.id, book.updatedAt) }.getOrNull() },
                        readerFiles = readerFilesOf(book.files),
                    )
                }
            }
        }
        viewModelScope.launch {
            container.appSettings.settings.collect { s ->
                val photos = QuotePhotos.onThisPhone(appContext, account, s.quotePhotos)
                _state.update { it.copy(liked = s.notes.liked.keys, photos = photos) }
            }
        }
        viewModelScope.launch {
            // This book's highlights changed elsewhere (the reader, a quote); not this screen's own edits.
            NoteChanges.changes.collect { c ->
                if (c.origin !== this@BookHighlightsViewModel && (c.bookId == null || c.bookId == bookId)) reloadLoaded()
            }
        }
    }

    /** The first page again (also Retry). */
    fun load() = loadPage(first = true, pull = false)

    /** Pull to refresh. */
    fun refresh() = loadPage(first = true, pull = true)

    /** The next page, when the list nears its end. */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.endReached || s.error != null) return
        loadPage(first = false, pull = false)
    }

    private fun loadPage(first: Boolean, pull: Boolean) {
        if (first) loadJob?.cancel() else if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update {
                when {
                    pull -> it.copy(refreshing = true)
                    first -> it.copy(loading = it.items.isEmpty(), error = null)
                    else -> it.copy(loadingMore = true)
                }
            }
            val page = if (first) 1 else nextPage
            try {
                val result = remote.bookAnnotations(bookId, page, PAGE_SIZE, newestFirst = false)
                nextPage = page + 1
                _state.update { s ->
                    val items = if (first) result.items else (s.items + result.items).distinctBy { it.id }
                    s.copy(
                        loading = false, refreshing = false, loadingMore = false, error = null,
                        items = items,
                        stats = result.stats,
                        endReached = result.items.isEmpty() || items.size >= result.total,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                _state.update { it.copy(loading = false, refreshing = false, loadingMore = false, error = if (it.items.isEmpty()) detail else null) }
                if (_state.value.items.isNotEmpty()) _messages.trySend(NotesMessage.Failed(detail))
            }
        }
    }

    /**
     * The pages loaded so far again, quietly (a change made elsewhere): the list keeps its length,
     * so the user's place in it and an open card stay, rather than going back to the first page.
     */
    private fun reloadLoaded() {
        val pages = (nextPage - 1).coerceAtLeast(1)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val items = mutableListOf<Annotation>()
                var page = 1
                var last = remote.bookAnnotations(bookId, page, PAGE_SIZE, newestFirst = false)
                items += last.items
                while (page < pages && last.items.isNotEmpty() && items.size < last.total) {
                    page++
                    last = remote.bookAnnotations(bookId, page, PAGE_SIZE, newestFirst = false)
                    items += last.items
                }
                nextPage = page + 1
                val merged = items.distinctBy { it.id }
                _state.update { s ->
                    s.copy(
                        loading = false, refreshing = false, loadingMore = false, error = null,
                        items = merged,
                        stats = last.stats,
                        endReached = last.items.isEmpty() || merged.size >= last.total,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // What's shown stays; a first load this replaced reports its failure.
                val detail = e.message ?: e.javaClass.simpleName
                _state.update { it.copy(loading = false, refreshing = false, loadingMore = false, error = if (it.items.isEmpty()) detail else null) }
            }
        }
    }

    fun saveNote(note: Annotation, text: String?) {
        val clean = text?.trim()?.takeIf { it.isNotEmpty() }
        _state.update { it.copy(drafts = it.drafts - note.id) }
        if (clean == note.note?.trim()?.takeIf { it.isNotEmpty() }) return
        edit(note, note.copy(note = clean), NoteEdits.note(clean))
    }

    fun setColor(note: Annotation, hex: String) = edit(note, note.copy(color = hex), NoteEdits.color(hex))

    fun setStyle(note: Annotation, style: String) = edit(note, note.copy(style = style), NoteEdits.style(style))

    /** The card showed the failed note's text again in its editor. */
    fun draftShown(note: Annotation) = _state.update { it.copy(drafts = it.drafts - note.id) }

    /**
     * Shows [changed] at once; the server's answer then sets the fields [body] changes, a failure
     * puts back [note]'s (other fields as they are by then). One edit of a highlight at a time. The
     * request runs in the app scope, so leaving the screen doesn't cancel it (as BookTracker does).
     */
    private fun edit(note: Annotation, changed: Annotation, body: JsonObject) {
        if (note.id in _state.value.saving) return
        val fields = body.keys
        change(note.id) { NotesLogic.withFields(it, changed, fields) }
        _state.update { it.copy(saving = it.saving + note.id) }
        val request = appScope.async {
            remote.update(bookId, note.id, body).also { NoteChanges.changed(bookId, origin = this@BookHighlightsViewModel) }
        }
        viewModelScope.launch {
            try {
                val saved = request.await()
                change(note.id) { NotesLogic.withFields(it, saved, fields) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                change(note.id) { NotesLogic.withFields(it, note, fields) }
                // The user's text isn't lost: the card opens its editor with it again.
                if ("note" in fields) _state.update { it.copy(drafts = it.drafts + (note.id to changed.note.orEmpty())) }
                _messages.trySend(NotesMessage.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                _state.update { it.copy(saving = it.saving - note.id) }
            }
        }
    }

    /** [transform] applied to the highlight [id] as it is now (if still listed), the stats in step. */
    private fun change(id: Long, transform: (Annotation) -> Annotation) {
        _state.update { s ->
            val current = s.items.firstOrNull { it.id == id } ?: return@update s
            val next = transform(current)
            s.copy(items = s.items.map { if (it.id == id) next else it }, stats = NotesLogic.statsAfter(s.stats, current, next))
        }
    }

    /**
     * Moves [note] to the web's trash (the caller confirmed), in the app scope. A failure puts back
     * only this highlight: other deletes and pages loaded meanwhile stay as they are.
     */
    fun delete(note: Annotation) {
        val items = _state.value.items
        val index = items.indexOfFirst { it.id == note.id }
        if (index < 0) return
        val removed = items[index]
        val afterId = items.getOrNull(index - 1)?.id
        _state.update { s -> s.copy(items = s.items.filterNot { it.id == note.id }, stats = NotesLogic.statsAfter(s.stats, removed, null)) }
        val request = appScope.async {
            remote.delete(bookId, note.id)
            NoteChanges.changed(bookId, origin = this@BookHighlightsViewModel)
        }
        viewModelScope.launch {
            try {
                request.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    if (s.items.any { it.id == removed.id }) s
                    else s.copy(items = NotesLogic.reinsert(s.items, removed, afterId, index), stats = NotesLogic.statsRestored(s.stats, removed))
                }
                _messages.trySend(NotesMessage.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun toggleLike(note: Annotation) {
        if (!repo.toggleLike(note)) _messages.trySend(NotesMessage.LikesFull)
    }

    /** [note]'s kept photo, if it is still on this phone. */
    fun photo(note: Annotation): File? = QuotePhotos.of(appContext, account, appSettings, note.id)

    /** Deletes the quote [id]'s kept photo from this phone (asked first). */
    fun removePhoto(id: Long) {
        appScope.launch { QuotePhotos.remove(appContext, account, appSettings, id) }
    }

    /** The book's highlights as Markdown (the server's export), for the share sheet. */
    fun export() {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                val markdown = remote.exportMarkdown(bookId)
                _messages.trySend(NotesMessage.Export(markdown, NotesLogic.exportFileName(_state.value.title)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(NotesMessage.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = NotesRepository.PAGE_MAX
    }
}
