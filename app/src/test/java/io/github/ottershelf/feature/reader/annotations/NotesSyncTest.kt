package io.github.ottershelf.feature.reader.annotations

import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.network.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class NotesSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val account = "books.example_me"
    private val book = 7L
    private val otherBook = 8L
    private val cfi = "epubcfi(/6/14!/4/2,/1:0,/1:20)"

    private class FakeRemote : NotesRemote {
        val annotations = mutableListOf<Annotation>()
        val bookmarks = mutableListOf<Bookmark>()
        var nextId = 100L
        var creates = 0
        var offline = false
        /** The next create reaches the server but its answer is lost. */
        var loseNextAnswer = false
        var reject: ApiException? = null
        /** Runs once a create has reached the server, before its answer returns. */
        var afterCreate: (() -> Unit)? = null

        private fun net() { if (offline) throw IOException("offline") }

        override suspend fun annotations(bookId: Long): List<Annotation> { net(); return annotations.toList() }
        override suspend fun createAnnotation(bookId: Long, draft: AnnotationDraft): Annotation {
            net()
            reject?.let { throw it }
            creates++
            val a = Annotation(nextId++, bookId, draft.cfi, draft.text, draft.color, draft.style, draft.note, draft.chapterTitle)
            annotations += a
            afterCreate?.invoke()
            if (loseNextAnswer) { loseNextAnswer = false; throw IOException("connection reset") }
            return a
        }
        override suspend fun updateAnnotation(bookId: Long, id: Long, note: String?, color: String?, style: String?): Annotation {
            net()
            val i = annotations.indexOfFirst { it.id == id }
            if (i < 0) throw ApiException(404, "gone")
            val a = annotations[i]
            val u = a.copy(note = note?.ifBlank { null } ?: a.note, color = color ?: a.color, style = style ?: a.style)
            annotations[i] = u
            return u
        }
        override suspend fun deleteAnnotation(bookId: Long, id: Long) {
            net()
            if (!annotations.removeAll { it.id == id }) throw ApiException(404, "gone")
        }
        override suspend fun bookmarks(bookId: Long): List<Bookmark> { net(); return bookmarks.toList() }
        override suspend fun createBookmark(bookId: Long, cfi: String, title: String): Bookmark {
            net()
            bookmarks.firstOrNull { it.cfi == cfi }?.let { return it }
            return Bookmark(nextId++, bookId, cfi, title).also { bookmarks += it }
        }
        override suspend fun deleteBookmark(bookId: Long, id: Long) {
            net()
            if (!bookmarks.removeAll { it.id == id }) throw ApiException(404, "gone")
        }
    }

    private fun draft(text: String = "Hello there") = AnnotationDraft(cfi, text, DEFAULT_COLOR, STYLE_HIGHLIGHT, bookFileId = 3)

    private suspend fun ReaderNotesStore.enqueue(op: NoteOp) = update(account, book) { it.copy(queue = it.queue + op) }

    @Test
    fun offlineWritesShowAtOnceAndGoWhenOnline() = runTest {
        val store = ReaderNotesStore(tmp.root)
        val remote = FakeRemote().apply { offline = true }
        val sync = NotesSync(store, remote, account)
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft()))
        store.enqueue(NoteOp.UpdateAnnotation("b", -5, note = "mine"))
        store.enqueue(NoteOp.CreateBookmark("c", -6, cfi, "Chapter 1"))

        assertFalse(sync.flush(book))
        val shown = store.read(account, book).visible()
        assertEquals(listOf(-5L), shown.annotations.map { it.id })
        assertEquals("mine", shown.annotations.single().note)
        assertEquals(1, shown.bookmarks.size)

        remote.offline = false
        var changed = 0
        assertTrue(sync.flush(book, onChanged = { changed++ }))
        assertEquals(1, changed)
        assertEquals("mine", remote.annotations.single().note)
        assertEquals(1, remote.bookmarks.size)
        val after = store.read(account, book)
        assertTrue(after.queue.isEmpty())
        assertEquals(listOf(100L), after.visible().annotations.map { it.id })
    }

    @Test
    fun anAttemptedCreateIsLookedForBeforeItIsSentAgain() = runTest {
        val store = ReaderNotesStore(tmp.root)
        val remote = FakeRemote().apply { loseNextAnswer = true }
        val sync = NotesSync(store, remote, account)
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft()))

        assertFalse(sync.flush(book)) // reached the server, the answer was lost
        assertTrue(sync.flush(book))
        assertEquals(1, remote.creates)
        assertEquals(1, remote.annotations.size)
        assertEquals(listOf(remote.annotations.single().id), store.read(account, book).visible().annotations.map { it.id })
    }

    @Test
    fun aSameTextHighlightThisDeviceAlreadyHadIsNotTakenForTheLostOne() = runTest {
        val store = ReaderNotesStore(tmp.root)
        val remote = FakeRemote()
        val sync = NotesSync(store, remote, account)
        val old = Annotation(50, book, cfi, "Hello there")
        remote.annotations += old
        store.update(account, book) { it.copy(annotations = listOf(old)) }
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft(), attempted = true))

        assertTrue(sync.flush(book))
        assertEquals(1, remote.creates)
        assertEquals(2, remote.annotations.size)
    }

    @Test
    fun refreshMatchesALostCreateInsteadOfShowingItTwice() = runTest {
        val store = ReaderNotesStore(tmp.root)
        val remote = FakeRemote()
        val sync = NotesSync(store, remote, account)
        val arrived = Annotation(60, book, cfi, "Hello there")
        remote.annotations += arrived
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft(), attempted = true))
        remote.offline = true
        assertFalse(sync.flush(book))
        remote.offline = false

        val file = sync.refresh(book)
        assertEquals(0, remote.creates)
        assertEquals(listOf(60L), file.visible().annotations.map { it.id })
    }

    @Test
    fun aRefusedCreateIsDroppedWithWhatWaitedOnIt() = runTest {
        val store = ReaderNotesStore(tmp.root)
        val remote = FakeRemote().apply { reject = ApiException(400, "Provide exactly one of cfi or pdf") }
        val sync = NotesSync(store, remote, account)
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft()))
        store.enqueue(NoteOp.UpdateAnnotation("b", -5, color = "#4ADE80"))
        val refused = mutableListOf<String>()

        assertTrue(sync.flush(book, onRejected = { refused += it }))
        assertEquals(listOf("Provide exactly one of cfi or pdf"), refused)
        assertTrue(store.read(account, book).queue.isEmpty())
    }

    @Test
    fun aPassStopsOnceItsAccountSignedOut() = runTest {
        val store = ReaderNotesStore(tmp.root)
        var signedIn = true
        // Signed out (and maybe in as someone else) while the first write was on its way.
        val remote = FakeRemote().apply { afterCreate = { signedIn = false } }
        val sync = NotesSync(store, remote, account, stillSignedIn = { signedIn })
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft()))
        store.enqueue(NoteOp.CreateBookmark("b", -6, cfi, "Chapter 1"))

        assertFalse(sync.flush(book))
        assertEquals(1, remote.creates)
        assertTrue(remote.bookmarks.isEmpty())
        assertEquals(listOf("b"), store.read(account, book).queue.map { it.opId })

        // A refresh neither sends the rest nor keeps the lists another sign-in would fetch.
        remote.annotations += Annotation(900, book, cfi, "Someone else's")
        assertTrue(runCatching { sync.refresh(book) }.exceptionOrNull() is IOException)
        assertTrue(remote.bookmarks.isEmpty())
        assertEquals(listOf(100L), store.read(account, book).annotations.map { it.id })
    }

    @Test
    fun theWorkersRunSendsEveryBookAndAsksForARetryWhileAnyIsLeft() = runTest {
        val store = ReaderNotesStore(tmp.root)
        val remote = FakeRemote().apply { offline = true }
        val sync = NotesSync(store, remote, account)
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft()))
        store.update(account, otherBook) { it.copy(queue = listOf(NoteOp.CreateBookmark("b", -6, cfi, "Chapter 1"))) }

        assertFalse(sync.flushAll())

        remote.offline = false
        val changed = mutableListOf<Long>()
        assertTrue(sync.flushAll(onChanged = { bookId, _ -> changed += bookId }))
        assertEquals(setOf(book, otherBook), changed.toSet())
        assertTrue(store.booksWithQueue(account).isEmpty())
    }

    @Test
    fun theWorkersRunEndsWithoutARetryOnceItsAccountSignedOut() = runTest {
        val store = ReaderNotesStore(tmp.root)
        var signedIn = true
        val remote = FakeRemote().apply { afterCreate = { signedIn = false } }
        val sync = NotesSync(store, remote, account, stillSignedIn = { signedIn })
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft()))
        store.update(account, otherBook) { it.copy(queue = listOf(NoteOp.CreateAnnotation("b", -6, draft()))) }

        // Nothing to retry under whoever signs in next: the rest waits for this account's sign-in.
        assertTrue(sync.flushAll())
        assertEquals(1, remote.creates) // the first book's, whichever it was
        assertEquals(1, store.booksWithQueue(account).size)
    }

    @Test
    fun aServerErrorKeepsTheQueue() = runTest {
        val store = ReaderNotesStore(tmp.root)
        val remote = FakeRemote().apply { reject = ApiException(503, "busy") }
        val sync = NotesSync(store, remote, account)
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft()))

        assertFalse(sync.flush(book))
        assertEquals(1, store.read(account, book).queue.size)
    }

    @Test
    fun deletingSomethingAlreadyGoneCountsAsDone() = runTest {
        val store = ReaderNotesStore(tmp.root)
        val remote = FakeRemote()
        val sync = NotesSync(store, remote, account)
        store.update(account, book) { it.copy(annotations = listOf(Annotation(9, book, cfi, "x"))) }
        store.enqueue(NoteOp.DeleteAnnotation("a", 9))

        assertTrue(sync.flush(book))
        assertTrue(store.read(account, book).visible().annotations.isEmpty())
    }

    @Test
    fun aLocalIdStillResolvesAfterTheRefreshThatSentItsCreate() = runTest {
        val store = ReaderNotesStore(tmp.root)
        val remote = FakeRemote()
        val sync = NotesSync(store, remote, account)
        store.enqueue(NoteOp.CreateAnnotation("a", -5, draft()))

        // The reader-open refresh sends the create; the queue is empty after it.
        val file = sync.refresh(book)
        assertEquals(100L, file.ids[-5L])
        // A note dialog opened on the local highlight before that is saved now.
        store.enqueue(NoteOp.UpdateAnnotation("b", -5, note = "later"))
        assertTrue(sync.flush(book))
        assertEquals("later", remote.annotations.single().note)
    }

    @Test
    fun idsOfItemsGoneAreForgottenUnlessAWriteNamesThem() {
        val file = NotesFile(
            annotations = listOf(Annotation(100, book, cfi, "x")),
            bookmarks = listOf(Bookmark(102, book, cfi, "Chapter 1")),
            queue = listOf(NoteOp.DeleteAnnotation("a", -7)),
            ids = mapOf(-5L to 100L, -6L to 101L, -7L to 103L, -8L to 102L),
        )
        assertEquals(mapOf(-5L to 100L, -7L to 103L, -8L to 102L), file.withLiveIds().ids)
    }

    @Test
    fun booksWithQueueListsOnlyWaitingOnes() = runTest {
        val store = ReaderNotesStore(tmp.root)
        store.update(account, 1) { it.copy(annotations = listOf(Annotation(1, 1, cfi, "x"))) }
        store.update(account, 2) { it.copy(queue = listOf(NoteOp.DeleteAnnotation("a", 1))) }
        assertEquals(listOf(2L), store.booksWithQueue(account))
        assertNull(store.booksWithQueue("other").firstOrNull())
    }
}
