package io.github.ottershelf.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import io.github.ottershelf.core.model.BookDetail
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What this device changed on the server that already-loaded screens should reflect, without
 * refetching everything: the dashboard's freshness counter and read statuses set in this process.
 * Everything here is safe to call from any thread.
 */
class ReadingChanges {

    private val _changes = MutableStateFlow(0)
    /**
     * Goes up whenever this device changes something on the server that the dashboard shows (a
     * position or session sent, a read status set), so the dashboard knows to ask again.
     */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun changed() = _changes.update { it + 1 }

    private val clearedFor = AtomicInteger(0)
    /**
     * [changes] as of the last time the server's cached dashboard widgets were dropped
     * (`POST dashboard/refresh`), so a dashboard only asks for that again when something has
     * changed since.
     */
    val dashboardClearedFor: Int get() = clearedFor.get()

    /** The dashboard dropped the server's cache when [changes] was [at]. */
    fun dashboardCleared(at: Int) {
        clearedFor.accumulateAndGet(at, ::maxOf)
    }

    private val _statusOverrides = MutableStateFlow<Map<Long, String>>(emptyMap())
    /**
     * Read statuses changed in this session (book page), so an already-loaded library grid can
     * show them without refetching its pages. Keyed by book id; values are `ReadStatus.value`s.
     */
    val statusOverrides: StateFlow<Map<Long, String>> = _statusOverrides.asStateFlow()

    fun overrideStatus(bookId: Long, status: String) = _statusOverrides.update { it + (bookId to status) }

    /** The status to show for [bookId]: this session's change, else what the server sent. */
    fun statusOf(bookId: Long, fromServer: String?): String? = _statusOverrides.value[bookId] ?: fromServer

    private val lastEdit = AtomicLong(0)
    private val _editedBooks = MutableStateFlow<Map<Long, EditedBook>>(emptyMap())
    /**
     * Books whose details (title, authors, series, cover) were edited on this device in this session
     * (feature.bookedit), as the server had them right after the change, so already-loaded grids,
     * shelves and pages show the change without refetching ([edited], [withDetails], [newEdits]).
     * Keyed by id: the latest edit of each book.
     *
     * Only what was loaded before an edit shows it. Anything asked for since is the server's word,
     * which may be newer (someone else's change on the web), so a list takes [editMark] before it
     * asks and applies only what was [editedSince] then to the answer. The book's `updatedAt` can't
     * tell them apart: the server doesn't move it for a change of authors alone.
     */
    val editedBooks: StateFlow<Map<Long, EditedBook>> = _editedBooks.asStateFlow()

    /**
     * Where the edits stand (the latest [EditedBook.seq]): taken just before asking the server for
     * books, so that [editedSince] it gives the edits its answer may be from before.
     */
    val editMark: Long get() = lastEdit.get()

    /** The books edited after [mark] ([editMark]), by id. */
    fun editedSince(mark: Long): Map<Long, BookDetail> =
        _editedBooks.value.values.filter { it.seq > mark }.associate { it.book.id to it.book }

    /** [book] was edited here: [editedBooks], and the dashboard asks again ([changes]). */
    fun bookEdited(book: BookDetail) {
        val seq = lastEdit.incrementAndGet()
        _editedBooks.update { edits ->
            if ((edits[book.id]?.seq ?: 0) > seq) edits else edits + (book.id to EditedBook(book, seq))
        }
        changed()
    }

    /** On sign-out: another account's book ids mean other books. ([editMark] keeps counting.) */
    fun reset() {
        _statusOverrides.value = emptyMap()
        _editedBooks.value = emptyMap()
        clearedFor.set(0)
        changed()
    }
}
