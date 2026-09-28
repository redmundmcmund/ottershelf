package io.github.ottershelf.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.FileProgress
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.model.SaveProgress
import io.github.ottershelf.core.sync.ProgressStore.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The page-based readers' progress (comics, PDF): page math, and PageReaderProgress running the EPUB
 * reader's rules through the real ProgressStore against a fake server. Robolectric only provides the
 * SharedPreferences the store persists to.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PageReaderProgressTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeServer()
    private var online = true
    private var now = 1_800_000_000_000L
    private var syncs = 0

    @Before
    fun setUp() {
        context.getSharedPreferences("progress", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun TestScope.store() = ProgressStore(context, { ACCOUNT }, server, io = StandardTestDispatcher(testScheduler))

    private fun TestScope.tracker(
        store: ProgressStore,
        scope: CoroutineScope = backgroundScope,
        connected: () -> Boolean = { online },
    ) = PageReaderProgress(
        store = store,
        bookId = BOOK,
        fileId = FILE,
        remote = server,
        online = connected,
        uiScope = scope,
        sendScope = scope,
        onSessionQueued = { syncs++ },
        clock = { now },
    )

    // --- page math ---------------------------------------------------------------------------

    @Test
    fun pagesAreSavedAsTheWebSavesThem() {
        assertEquals(30.0, PageProgress.percentage(3, 10), 1e-9)
        assertEquals(100.0, PageProgress.percentage(10, 10), 1e-9) // the last page finishes the book
        assertEquals(0.0, PageProgress.percentage(1, 0), 1e-9)
        assertEquals(SaveProgress(cfi = null, percentage = 30.0, pageNumber = 3), PageProgress.body(3, 10))
        assertEquals(10, PageProgress.position(12, 10).pageNumber) // clamped
    }

    @Test
    fun opensAtTheSavedPageOrOneEstimatedFromThePercentage() {
        assertEquals(7, PageProgress.startPage(Position(null, 70.0, 7), 10))
        assertEquals(10, PageProgress.startPage(Position(null, 70.0, 40), 10)) // the book got shorter
        assertEquals(5, PageProgress.startPage(Position(null, 50.0), 10)) // KOReader, or a percentage only
        assertEquals(1, PageProgress.startPage(Position(null, 0.0), 10))
        assertEquals(1, PageProgress.startPage(null, 10))
    }

    @Test
    fun pageNumbersOnlyCountWhenBothSidesHaveOne() {
        assertTrue(Position(null, 30.0, 3).sameAs(Position(null, 30.0, 3)))
        assertFalse(Position(null, 30.0, 3).sameAs(Position(null, 30.0, 4)))
        assertTrue(Position(null, 30.0, 3).sameAs(Position(null, 30.0))) // a percentage-only client
        // EPUB positions are compared exactly as before: no page numbers on either side.
        assertTrue(Position("a", 10.0).sameAs(Position("a", 10.004)))
        assertFalse(Position("a", 10.0).sameAs(Position("b", 10.0)))
    }

    // --- opening and saving --------------------------------------------------------------------

    @Test
    fun opensAtTheServersPageAndSavesOnlyAPageTurn() = runTest {
        server.position = FileProgress(pageNumber = 5, percentage = 50.0)
        val store = store()
        val tracker = tracker(store)

        val opening = tracker.open(hasLocalCopy = false)
        assertEquals(5, opening.startPage(10))
        assertNull(opening.freshStart)
        assertTrue(opening.withServer)

        tracker.onPage(5, 10) // the page it opened on: already the server's
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(server.saved.isEmpty())

        tracker.onPage(6, 10)
        advanceTimeBy(1_000)
        tracker.onPage(7, 10) // debounced: only the last one goes
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(SaveProgress(cfi = null, percentage = 70.0, pageNumber = 7)), server.saved)
        assertNull(store.get(FILE)!!.pending)
    }

    @Test
    fun onlineSavesAreSentWithoutTheFlushWhichOnlyCoversWhatCantGo() = runTest {
        var queued = 0
        server.position = FileProgress(pageNumber = 5, percentage = 50.0)
        val store = ProgressStore(context, { ACCOUNT }, server, onQueued = { queued++ }, io = StandardTestDispatcher(testScheduler))
        val tracker = tracker(store)
        tracker.open(hasLocalCopy = false)
        tracker.onPage(5, 10)

        tracker.onPage(6, 10)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, server.saved.size)
        assertEquals(0, queued) // sent at once: no WorkManager job

        server.unreachable = true // online, but the send fails
        tracker.onPage(7, 10)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, queued)

        online = false
        tracker.onPage(8, 10)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(2, queued)
        assertEquals(8, store.get(FILE)!!.pending!!.pageNumber)
    }

    @Test
    fun aConnectionLostJustAsASaveGoesStillLeavesItToTheFlush() = runTest {
        var queued = 0
        server.position = FileProgress(pageNumber = 5, percentage = 50.0)
        val store = ProgressStore(context, { ACCOUNT }, server, onQueued = { queued++ }, io = StandardTestDispatcher(testScheduler))
        var dropAfterNextLook = false
        val tracker = tracker(store, connected = {
            val was = online
            if (dropAfterNextLook) {
                dropAfterNextLook = false
                online = false
                server.unreachable = true
            }
            was
        })
        tracker.open(hasLocalCopy = false)
        tracker.onPage(5, 10)

        dropAfterNextLook = true // the save finds a connection that is gone a moment later
        tracker.onPage(6, 10)
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(1, queued) // the send went, failed, and left it to the flush
        assertEquals(6, store.get(FILE)!!.pending!!.pageNumber)
    }

    @Test
    fun aPageSavedJustAfterASyncSentThePreviousOneStillGoes() = runTest {
        var queued = 0
        server.position = FileProgress(pageNumber = 5, percentage = 50.0)
        // Sends run straight away on the caller's thread, so the test can act between two of them.
        val store = ProgressStore(context, { ACCOUNT }, server, onQueued = { queued++ }, io = UnconfinedTestDispatcher(testScheduler))
        val tracker = tracker(store)
        tracker.open(hasLocalCopy = false)
        tracker.onPage(5, 10)
        val answer = CompletableDeferred<Unit>()
        server.saveAnswer = answer

        // Page 6 is saved, and before the reader's send runs, a sync takes it and waits for the answer.
        tracker.onPage(6, 10)
        tracker.onPause()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { store.syncAll(emptyList()) }
        runCurrent() // the reader's send waits for the sync
        answer.complete(Unit) // the sync sends page 6; the reader's send then finds nothing left
        // Page 7, saved before the reader's send has told the reader so.
        tracker.onPage(7, 10)
        tracker.onPause()
        runCurrent()

        assertEquals(listOf(6, 7), server.saved.map { it.pageNumber })
        assertNull(store.get(FILE)!!.pending)
        assertEquals(0, queued)
    }

    @Test
    fun offlineReadingIsKeptAndSentWhenNextOpenedOnline() = runTest {
        server.position = FileProgress(pageNumber = 2, percentage = 20.0)
        val store = store()
        store.setBaseline(FILE, BOOK, Position(null, 20.0, 2))
        online = false
        server.unreachable = true
        val first = tracker(store)
        assertEquals(2, first.open(hasLocalCopy = true).startPage(10)) // this device's record
        first.onPage(3, 10)
        first.onPage(4, 10)
        first.close()
        runCurrent()
        assertTrue(server.saved.isEmpty())
        assertEquals(4, store.get(FILE)!!.pending!!.pageNumber)

        // Back online, and nothing else moved it: carry on from here and send it.
        online = true
        server.unreachable = false
        val second = tracker(store)
        assertEquals(4, second.open(hasLocalCopy = true).startPage(10))
        runCurrent() // (advanceUntilIdle stops once only background work is left)
        assertEquals(listOf(SaveProgress(cfi = null, percentage = 40.0, pageNumber = 4)), server.saved)
    }

    @Test
    fun bothMovedAsksAndKeepingTheServersDropsThisDevices() = runTest {
        val store = store()
        store.setBaseline(FILE, BOOK, Position(null, 20.0, 2))
        store.savePending(FILE, BOOK, PageProgress.body(4, 10)) // read offline here
        server.position = FileProgress(pageNumber = 8, percentage = 80.0, lastReadAt = "2026-09-24T10:00:00.000Z") // and on the web
        val tracker = tracker(store)

        val opening = async { tracker.open(hasLocalCopy = false) }
        runCurrent()
        val choice = tracker.choice.value
        assertNotNull(choice)
        assertEquals(4, choice!!.mine.pageNumber)
        assertEquals(8, choice.server.pageNumber)
        tracker.choose(keepMine = false)
        assertEquals(8, opening.await().startPage(10))
        assertNull(tracker.choice.value)
        assertNull(store.get(FILE)!!.pending)
        assertTrue(server.saved.isEmpty())
    }

    @Test
    fun aBookMarkedReadByHandStartsOverAndSavesNothingUntilAPageTurn() = runTest {
        server.position = FileProgress(pageNumber = 9, percentage = 90.0, updatedAt = "2026-09-01T10:00:00.000Z")
        server.status = ReadStatusInfo(status = "read", source = "manual", updatedAt = "2026-09-02T10:00:00.000Z")
        val store = store()
        val tracker = tracker(store)

        val opening = tracker.open(hasLocalCopy = false)
        assertEquals(ReadStatus.READ, opening.freshStart)
        assertEquals(1, opening.startPage(10))
        tracker.onPage(1, 10)
        tracker.onPause()
        runCurrent()
        assertTrue(server.saved.isEmpty()) // merely opening it doesn't make it Re-reading

        tracker.onPage(2, 10)
        tracker.close()
        runCurrent()
        assertEquals(listOf(SaveProgress(cfi = null, percentage = 20.0, pageNumber = 2)), server.saved)
    }

    @Test
    fun aHighlightsPageOpensThereWithoutMovingThePosition() = runTest {
        server.position = FileProgress(pageNumber = 5, percentage = 50.0)
        val store = store()
        val tracker = tracker(store)
        val opening = tracker.open(hasLocalCopy = false, jumpPage = 40)
        assertEquals(40, opening.startPage(100))
        tracker.onPage(40, 100)
        tracker.close()
        runCurrent()
        assertTrue(server.saved.isEmpty())
    }

    @Test
    fun aConflictFoundMidBookIsAskedAndTheServersPageJumpedTo() = runTest {
        server.position = FileProgress(pageNumber = 2, percentage = 20.0)
        val store = store()
        val tracker = tracker(store)
        tracker.open(hasLocalCopy = false)
        tracker.onPage(2, 10) // the page it opened at
        // The connection dropped; meanwhile the web moved it.
        online = false
        tracker.onPage(3, 10)
        advanceTimeBy(3_000)
        runCurrent()
        server.position = FileProgress(pageNumber = 9, percentage = 90.0)
        online = true
        tracker.onPage(4, 10)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(9, tracker.choice.value!!.server.pageNumber)
        val jump = async { tracker.jumps.first() }
        tracker.choose(keepMine = false)
        runCurrent()
        assertEquals(9, jump.await().pageNumber)
        assertTrue(server.saved.isEmpty())
    }

    // --- sessions ----------------------------------------------------------------------------

    @Test
    fun aReadingSessionIsQueuedWithItsPagesAndSynced() = runTest {
        server.position = FileProgress(pageNumber = 1, percentage = 10.0)
        val store = store()
        val tracker = tracker(store)
        tracker.open(hasLocalCopy = false)
        tracker.onPage(1, 10)
        now += 60_000
        tracker.onPage(3, 10)
        now += 60_000
        tracker.close()
        runCurrent()
        assertEquals(1, syncs)

        store.syncAll(emptyList())
        val session = server.sessions.single()
        assertEquals(120, session.durationSeconds)
        assertEquals(20.0, session.progressDelta!!, 1e-9)
        assertEquals(30.0, session.endProgress!!, 1e-9)
    }

    @Test
    fun anIdleGapEndsTheSessionAtTheLastActivity() = runTest {
        val store = store()
        val tracker = tracker(store)
        tracker.open(hasLocalCopy = false)
        tracker.onPage(1, 10)
        now += 30_000
        tracker.onActivity() // zooming: still reading
        now += 10 * 60_000 // then put down
        tracker.onPage(2, 10) // a new session starts here
        now += 5_000
        tracker.close() // under 10 s: dropped
        runCurrent()
        store.syncAll(emptyList())
        assertEquals(listOf(30), server.sessions.map { it.durationSeconds })
    }

    @Test
    fun openingAndLeavingWithoutTurningAPageSendsNoSessionAndSavesNothing() = runTest {
        server.position = FileProgress(pageNumber = 5, percentage = 50.0)
        val store = store()
        val tracker = tracker(store)
        tracker.open(hasLocalCopy = false)
        tracker.onPage(5, 10)
        now += 15_000
        tracker.onActivity() // the bars shown, a page relaid out: not a move
        now += 5_000
        tracker.onPause()
        tracker.close()
        runCurrent()
        store.syncAll(emptyList())
        assertEquals(0, syncs)
        assertTrue(server.sessions.isEmpty())
        assertTrue(server.saved.isEmpty())
        assertFalse(tracker.readSinceOpen)
    }

    @Test
    fun aFirstBookPageShownIsNotSavedUntilTheUserTurns() = runTest {
        val store = store()
        val tracker = tracker(store)
        tracker.open(hasLocalCopy = false) // never read: opens at page 1
        tracker.onPage(1, 10)
        advanceTimeBy(3_000)
        runCurrent()
        tracker.onPause()
        runCurrent()
        assertTrue(server.saved.isEmpty()) // page 1 of an unread book would read as started
        assertNull(store.get(FILE)?.pending)
    }

    @Test
    fun zoomingIntoThePageCountsAsReading() = runTest {
        server.position = FileProgress(pageNumber = 5, percentage = 50.0)
        val store = store()
        val tracker = tracker(store)
        tracker.open(hasLocalCopy = false)
        tracker.onPage(5, 10)
        now += 30_000
        tracker.onGesture() // a pinch on a long page
        now += 30_000
        tracker.close()
        runCurrent()
        store.syncAll(emptyList())
        assertEquals(listOf(60), server.sessions.map { it.durationSeconds })
        assertTrue(server.saved.isEmpty()) // still the server's page
    }

    @Test
    fun theTimeBeforeThePauseCountsOnceTheUserTurnsAfterComingBack() = runTest {
        server.position = FileProgress(pageNumber = 5, percentage = 50.0)
        val store = store()
        val tracker = tracker(store)
        tracker.open(hasLocalCopy = false)
        tracker.onPage(5, 10)
        now += 90_000
        tracker.onPause() // the phone locked on the first page: held, not sent
        runCurrent()
        assertEquals(0, syncs)
        now += 10 * 60_000
        tracker.onPage(5, 10) // back
        now += 30_000
        tracker.onPage(6, 10) // a turn: the held session goes out
        now += 30_000
        tracker.close()
        runCurrent()
        store.syncAll(emptyList())
        assertEquals(listOf(90, 60), server.sessions.map { it.durationSeconds })
        assertEquals(listOf(SaveProgress(cfi = null, percentage = 60.0, pageNumber = 6)), server.saved)
    }

    private class FakeServer : ProgressRemote, PageReaderProgress.Remote {
        var position = FileProgress(percentage = 0.0)
        var status: ReadStatusInfo? = null
        var unreachable = false
        /** While set, each position sent waits for it before it's taken. */
        var saveAnswer: CompletableDeferred<Unit>? = null
        val saved = ArrayList<SaveProgress>()
        val sessions = ArrayList<ReadingSession>()

        override suspend fun fileProgress(fileId: Long): FileProgress {
            if (unreachable) throw IOException("offline")
            return position
        }

        override suspend fun readStatus(bookId: Long): ReadStatusInfo? {
            if (unreachable) throw IOException("offline")
            return status
        }

        override suspend fun saveProgress(fileId: Long, body: SaveProgress) {
            saveAnswer?.await()
            if (unreachable) throw IOException("offline")
            saved += body
            position = FileProgress(cfi = body.cfi, pageNumber = body.pageNumber, percentage = body.percentage)
        }

        override suspend fun saveSession(fileId: Long, body: ReadingSession) {
            if (unreachable) throw IOException("offline")
            sessions += body
        }
    }

    private companion object {
        const val ACCOUNT = "books.example.net_reader"
        const val FILE = 21L
        const val BOOK = 2L
    }
}
