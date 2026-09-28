package io.github.ottershelf.feature.reader.annotations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import io.github.ottershelf.core.network.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** When a reader's note writes schedule ReaderNotesWorker: only when the in-app send can't finish. */
class ReaderNotesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val online = MutableStateFlow(true)
    private var scheduled = 0

    /** The app scope stand-in: [settle] waits for everything launched in it. */
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.Default)

    private fun notes(remote: NotesRemote): ReaderNotes {
        val store = ReaderNotesStore(tmp.root)
        return ReaderNotes(
            bookId = BOOK,
            store = store,
            sync = NotesSync(store, remote, ACCOUNT),
            account = ACCOUNT,
            online = online,
            scope = appScope,
            uiScope = appScope,
            schedule = { synchronized(this) { scheduled++ } },
            onChanged = {},
        )
    }

    private fun settle() = runBlocking { appJob.children.toList().forEach { it.join() } }

    @Test
    fun anOnlineWriteThatGoesSchedulesNothing() {
        val remote = FakeRemote()
        val notes = notes(remote)

        notes.addBookmark(CFI, "Chapter 1")
        settle()

        assertEquals(listOf(CFI), remote.bookmarks.map { it.cfi })
        assertEquals(0, scheduled)
        assertTrue(notes.state.value.bookmarks.single().id > 0) // the server's id, shown
    }

    @Test
    fun anOfflineWriteSchedulesOnce() {
        online.value = false
        val remote = FakeRemote()
        val notes = notes(remote)

        notes.addBookmark(CFI, "Chapter 1")
        settle()

        assertTrue(remote.bookmarks.isEmpty())
        assertEquals(1, scheduled)
        assertTrue(notes.state.value.bookmarks.single().local) // shown at once, waiting
    }

    @Test
    fun aSendThatLeavesWritesQueuedSchedules() {
        val remote = FakeRemote().apply { failure = ApiException(503, "Service unavailable") }
        val notes = notes(remote)

        notes.addBookmark(CFI, "Chapter 1")
        settle()

        assertTrue(remote.bookmarks.isEmpty())
        assertEquals(1, scheduled)
    }

    @Test
    fun aDroppedConnectionMidSendSchedules() {
        val remote = FakeRemote().apply { failure = IOException("connection reset") }
        val notes = notes(remote)

        notes.addBookmark(CFI, "Chapter 1")
        settle()

        assertEquals(1, scheduled)
    }

    @Test
    fun aSendCutShortStillLeavesTheWorkerScheduled() {
        val remote = FakeRemote().apply { failure = CancellationException("cancelled") }
        val notes = notes(remote)

        notes.addBookmark(CFI, "Chapter 1")
        settle()

        assertEquals(1, scheduled)
    }

    /** The bookmark routes; [failure] is thrown by every request while set. */
    private class FakeRemote : NotesRemote {
        val bookmarks = mutableListOf<Bookmark>()
        var failure: Exception? = null
        private var nextId = 100L

        private fun net() { failure?.let { throw it } }

        override suspend fun annotations(bookId: Long): List<Annotation> { net(); return emptyList() }
        override suspend fun createAnnotation(bookId: Long, draft: AnnotationDraft): Annotation = throw UnsupportedOperationException()
        override suspend fun updateAnnotation(bookId: Long, id: Long, note: String?, color: String?, style: String?): Annotation =
            throw UnsupportedOperationException()
        override suspend fun deleteAnnotation(bookId: Long, id: Long) = throw UnsupportedOperationException()
        override suspend fun bookmarks(bookId: Long): List<Bookmark> { net(); return bookmarks.toList() }
        override suspend fun createBookmark(bookId: Long, cfi: String, title: String): Bookmark {
            net()
            return Bookmark(nextId++, bookId, cfi, title).also { bookmarks += it }
        }
        override suspend fun deleteBookmark(bookId: Long, id: Long) = throw UnsupportedOperationException()
    }

    private companion object {
        const val ACCOUNT = "books.example_me"
        const val BOOK = 7L
        const val CFI = "epubcfi(/6/14!/4/2,/1:0)"
    }
}
