package io.github.ottershelf.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.FileProgress
import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.model.SaveProgress
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.sync.ProgressStore.Opening
import io.github.ottershelf.core.sync.ProgressStore.Position
import io.github.ottershelf.core.sync.ProgressStore.Push
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * ProgressStore's conflict and queueing rules against a fake server. Robolectric only provides the
 * SharedPreferences the store persists to.
 */
@RunWith(AndroidJUnit4::class)
class ProgressStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeServer()
    private var sentReports = 0
    private var queuedReports = 0
    private lateinit var store: ProgressStore

    @Before
    fun setUp() {
        context.getSharedPreferences("progress", Context.MODE_PRIVATE).edit().clear().commit()
        store = ProgressStore(context, { ACCOUNT }, server, onSent = { sentReports++ }, onQueued = { queuedReports++ })
    }

    // --- pushing -----------------------------------------------------------------------------

    @Test
    fun checkedPushSendsWhenTheServerStillHasTheBaseline() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        server.position = progress("a", 10.0)
        store.savePending(FILE, BOOK, save("b", 20.0))

        assertEquals(Push.SENT, store.push(FILE, checked = true))

        assertEquals(listOf(save("b", 20.0)), server.savedPositions)
        val record = store.get(FILE)!!
        assertNull(record.pending)
        assertTrue(record.baseline!!.sameAs(Position("b", 20.0)))
        assertEquals(1, sentReports)
    }

    @Test
    fun checkedPushRecordsAConflictInsteadOfOverwritingAnotherDevice() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        server.position = progress("c", 50.0, lastReadAt = "2026-09-24T10:00:00.000Z") // read elsewhere
        store.savePending(FILE, BOOK, save("b", 20.0))

        assertEquals(Push.CONFLICT, store.push(FILE, checked = true))

        assertTrue(server.savedPositions.isEmpty())
        val record = store.get(FILE)!!
        assertTrue(record.conflict!!.sameAs(Position("c", 50.0)))
        assertEquals("2026-09-24T10:00:00.000Z", record.conflictReadAt)
        assertEquals(save("b", 20.0), record.pending) // kept until the user picks one
        assertEquals(Push.CONFLICT, store.push(FILE, checked = false)) // and nothing goes meanwhile
    }

    @Test
    fun anUnansweredSendThatArrivedIsNotTakenForAConflict() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        server.position = progress("a", 10.0)
        store.savePending(FILE, BOOK, save("b", 20.0))
        server.saveFailure = IOException("timeout")

        assertEquals(Push.FAILED, store.push(FILE, checked = true))
        assertEquals(listOf(Position("b", 20.0)), store.get(FILE)!!.attempted)

        // It got there after all, and the reader moved on meanwhile.
        server.position = progress("b", 20.0)
        store.savePending(FILE, BOOK, save("c", 30.0))

        assertEquals(Push.SENT, store.push(FILE, checked = true))
        assertEquals(listOf(save("c", 30.0)), server.savedPositions)
        assertNull(store.get(FILE)!!.conflict)
    }

    @Test
    fun theServerAlreadyHavingThePendingPositionCountsAsSent() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.savePending(FILE, BOOK, save("b", 20.0))
        server.position = progress("b", 20.0) // sent before, the answer was lost

        assertEquals(Push.SENT, store.push(FILE, checked = true))

        assertTrue(server.savedPositions.isEmpty())
        assertNull(store.get(FILE)!!.pending)
    }

    @Test
    fun aFileTheServerRejectsForGoodDropsItsPendingPosition() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.savePending(FILE, BOOK, save("b", 20.0))
        server.saveFailure = ApiException(404, "Not found")

        assertEquals(Push.REJECTED, store.push(FILE, checked = false))

        assertNull(store.get(FILE)!!.pending)
    }

    @Test
    fun aServerErrorAfterPostingIsTreatedAsUnanswered() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.savePending(FILE, BOOK, save("b", 20.0))
        server.saveFailure = ApiException(502, "Bad gateway")

        assertEquals(Push.FAILED, store.push(FILE, checked = false))

        val record = store.get(FILE)!!
        assertEquals(save("b", 20.0), record.pending)
        assertEquals(listOf(Position("b", 20.0)), record.attempted)
    }

    @Test
    fun keepingThisDevicesPositionSendsItOverTheServers() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        server.position = progress("c", 50.0)
        store.savePending(FILE, BOOK, save("b", 20.0))
        assertEquals(Push.CONFLICT, store.push(FILE, checked = true))

        store.resolveKeepLocal(FILE, BOOK, Position("c", 50.0))

        assertEquals(Push.SENT, store.push(FILE, checked = true))
        assertEquals(listOf(save("b", 20.0)), server.savedPositions)
    }

    @Test
    fun keepingTheServersPositionDropsThisDevicesAndItsSessionEnds() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.savePending(FILE, BOOK, save("b", 20.0))
        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0))

        store.resolveKeepServer(FILE, BOOK, Position("c", 50.0))
        store.syncAll(emptyList())

        assertNull(store.get(FILE)!!.pending)
        assertTrue(server.savedPositions.isEmpty())
        assertEquals(1, server.savedSessions.size)
        assertNull(server.savedSessions.single().endProgress) // counts for time, can't move the status
    }

    // --- opening -----------------------------------------------------------------------------

    @Test
    fun openingWithNothingPendingStartsAtTheServerPosition() {
        val opening = store.decideOnOpen(FILE, BOOK, progress("s", 40.0), before = null)

        assertEquals(Opening.Start(Position("s", 40.0), send = false, atServer = true), opening)
        assertTrue(store.get(FILE)!!.baseline!!.sameAs(Position("s", 40.0)))
    }

    @Test
    fun openingCarriesOnFromHereWhenNothingElseMovedIt() {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.savePending(FILE, BOOK, save("b", 20.0))
        val before = store.get(FILE)

        val opening = store.decideOnOpen(FILE, BOOK, progress("a", 10.0), before)

        assertEquals(Opening.Start(Position("b", 20.0), send = true, atServer = false), opening)
    }

    @Test
    fun openingAsksWhenBothMoved() {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.savePending(FILE, BOOK, save("b", 20.0))
        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0))
        val before = store.get(FILE)

        val opening = store.decideOnOpen(FILE, BOOK, progress("c", 50.0, lastReadAt = "2026-09-24T10:00:00.000Z"), before)

        opening as Opening.Ask
        assertTrue(opening.mine.sameAs(Position("b", 20.0)))
        assertTrue(opening.server.sameAs(Position("c", 50.0)))
        assertEquals("2026-09-24T10:00:00.000Z", opening.serverReadAt)
        assertTrue(store.get(FILE)!!.conflict!!.sameAs(Position("c", 50.0)))
        assertTrue(store.hasUnsent()) // the session still goes, without its end point
    }

    @Test
    fun openingConcludesNothingFromAnAnswerOlderThanTheRecord() {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        val before = store.get(FILE)
        store.savePending(FILE, BOOK, save("b", 20.0)) // a sync or the last reader wrote meanwhile

        val opening = store.decideOnOpen(FILE, BOOK, progress("c", 50.0), before)

        assertEquals(Opening.Start(Position("b", 20.0), send = false, atServer = false, inStep = false), opening)
        assertNull(store.get(FILE)!!.conflict)
    }

    // --- sessions and syncAll ----------------------------------------------------------------

    @Test
    fun syncAllSendsSessionsBeforePositionsAndReportsOnce() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.setBaseline(OTHER_FILE, OTHER_BOOK, Position("x", 1.0))
        server.positions[OTHER_FILE] = progress("x", 1.0)
        server.position = progress("a", 10.0)
        store.savePending(FILE, BOOK, save("b", 20.0))
        store.savePending(OTHER_FILE, OTHER_BOOK, save("y", 2.0))
        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0))

        store.syncAll(emptyList())

        assertEquals("session s1", server.log.first())
        assertEquals(3, server.log.size)
        assertEquals(1, sentReports)
        assertFalse(store.hasUnsent())
    }

    @Test
    fun sessionsWaitOutNetworkFailuresButAreDroppedWhenRejected() = runTest {
        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0))
        server.sessionFailure = IOException("offline")

        store.syncAll(emptyList())
        assertTrue(store.hasUnsent())

        server.sessionFailure = ApiException(400, "Bad request")
        store.syncAll(emptyList())
        assertFalse(store.hasUnsent())
        assertTrue(server.savedSessions.isEmpty())
    }

    @Test
    fun syncAllRefreshesBaselinesOfDownloadedBooksNotOpenOrPending() = runTest {
        server.position = progress("s", 60.0)
        store.opened(OTHER_FILE)

        store.syncAll(listOf(BOOK to FILE, OTHER_BOOK to OTHER_FILE))

        assertTrue(store.get(FILE)!!.baseline!!.sameAs(Position("s", 60.0)))
        assertNull(store.get(OTHER_FILE)) // open in a reader: left alone
    }

    @Test
    fun syncAllSaysWhetherItGotThroughTheDownloads() = runTest {
        // The server failing on files (one pending, one downloaded): skipped, and the pass goes on.
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.savePending(FILE, BOOK, save("b", 20.0), sendingNow = true)
        server.progressFailure = ApiException(503, "Service unavailable")
        assertTrue(store.syncAll(listOf(OTHER_BOOK to OTHER_FILE)))

        // The connection gone: stopped before the downloads were all asked.
        server.progressFailure = IOException("offline")
        assertFalse(store.syncAll(listOf(OTHER_BOOK to OTHER_FILE)))

        // A session that can't go stops it before them too.
        server.progressFailure = null
        server.position = progress("a", 10.0)
        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0), sendingNow = true)
        server.sessionFailure = IOException("offline")
        assertFalse(store.syncAll(listOf(OTHER_BOOK to OTHER_FILE)))
    }

    @Test
    fun conflictsAreNotUnsentButTheirSessionsAre() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        server.position = progress("c", 50.0)
        store.savePending(FILE, BOOK, save("b", 20.0))
        store.push(FILE, checked = true)
        assertFalse(store.hasUnsent())

        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0))
        assertTrue(store.hasUnsent())

        store.syncAll(emptyList())
        assertTrue(server.savedPositions.isEmpty())
        assertNull(server.savedSessions.single().endProgress)
    }

    @Test
    fun queueingReportsSoTheFlushCanBeScheduled() {
        store.savePending(FILE, BOOK, save("b", 20.0))
        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0))
        assertEquals(2, queuedReports)
    }

    // --- the flush as the failures' safety net ---------------------------------------------------

    @Test
    fun whatTheReaderSendsAtOnceSchedulesNoFlush() {
        store.savePending(FILE, BOOK, save("b", 20.0), sendingNow = true)
        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0), sendingNow = true)
        assertEquals(0, queuedReports)
    }

    @Test
    fun aSendThatWorkedSchedulesNoFlush() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        server.position = progress("a", 10.0)
        store.savePending(FILE, BOOK, save("b", 20.0), sendingNow = true)

        assertEquals(Push.SENT, store.push(FILE, checked = true))
        assertEquals(0, queuedReports)
    }

    @Test
    fun aFailedSendSchedulesTheFlush() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.savePending(FILE, BOOK, save("b", 20.0), sendingNow = true)
        server.saveFailure = IOException("timeout")

        assertEquals(Push.FAILED, store.push(FILE, checked = false))
        assertEquals(1, queuedReports)
    }

    @Test
    fun aRefusalThatDropsThePositionSchedulesNoFlush() = runTest {
        store.savePending(FILE, BOOK, save("b", 20.0), sendingNow = true)
        server.saveFailure = ApiException(404, "Not found")

        assertEquals(Push.REJECTED, store.push(FILE, checked = false))
        assertEquals(0, queuedReports) // nothing left to send
    }

    @Test
    fun aFailedSendAfterSignOutSchedulesNothing() = runTest {
        var signedIn = true
        val checked = ProgressStore(
            context, { ACCOUNT }, server, onQueued = { queuedReports++ },
            stillSignedInAs = { { signedIn } },
        )
        checked.savePending(FILE, BOOK, save("b", 20.0), sendingNow = true)
        server.saveFailure = IOException("timeout")
        signedIn = false // signed out while the position was on its way

        assertEquals(Push.FAILED, checked.push(FILE, checked = false))
        assertEquals(0, queuedReports)
    }

    @Test
    fun aSyncThatStopsWithSomethingLeftSchedulesTheFlush() = runTest {
        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0), sendingNow = true)
        server.sessionFailure = IOException("offline")

        store.syncAll(emptyList())

        assertTrue(store.hasUnsent())
        assertEquals(1, queuedReports)
    }

    @Test
    fun aSyncThatSkipsAFileTheServerFailedOnSchedulesTheFlush() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        store.savePending(FILE, BOOK, save("b", 20.0), sendingNow = true)
        server.progressFailure = ApiException(503, "Service unavailable")

        store.syncAll(emptyList())

        assertTrue(store.hasUnsent())
        assertEquals(1, queuedReports)
    }

    @Test
    fun aSyncThatSentEverythingSchedulesNoFlush() = runTest {
        store.setBaseline(FILE, BOOK, Position("a", 10.0))
        server.position = progress("a", 10.0)
        store.savePending(FILE, BOOK, save("b", 20.0), sendingNow = true)
        store.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0), sendingNow = true)

        store.syncAll(listOf(OTHER_BOOK to OTHER_FILE))

        assertFalse(store.hasUnsent())
        assertEquals(0, queuedReports)
    }

    @Test
    fun aSyncStoppedBySignOutSchedulesNothing() = runTest {
        var signIns = 1
        val checked = ProgressStore(context, { ACCOUNT }, server, onQueued = { queuedReports++ }, stillSignedInAs = { _ ->
            val started = signIns
            val lasts: () -> Boolean = { signIns == started }
            lasts
        })
        checked.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0), sendingNow = true)
        checked.enqueueSession(FILE, BOOK, session("s2", endProgress = 30.0), sendingNow = true)
        server.afterSession = { signIns++ } // signed out while s1 was on its way

        checked.syncAll(emptyList())

        assertTrue(checked.hasUnsent()) // s2 waits for this account's next sign-in
        assertEquals(0, queuedReports) // sign-out cancelled the jobs: none scheduled again
    }

    @Test
    fun recordsArePerAccount() {
        store.savePending(FILE, BOOK, save("b", 20.0))
        val other = ProgressStore(context, { "someone_else" }, server)
        assertNull(other.get(FILE))
        assertFalse(other.hasUnsent())
    }

    @Test
    fun aPassSendsNoMoreSessionsOnceAnotherAccountSignedIn() = runTest {
        var account = ACCOUNT
        val switching = ProgressStore(context, { account }, server)
        switching.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0))
        switching.enqueueSession(FILE, BOOK, session("s2", endProgress = 30.0))
        // Signed out and in as someone else while s1 was on its way.
        server.afterSession = { account = "books.example.net_someone_else" }

        switching.syncAll(emptyList())
        assertEquals(listOf("s1"), server.savedSessions.map { it.sessionId })

        // s2 waits for its own account's next sign-in (s1 may go again: sessions are retry-safe).
        server.afterSession = null
        account = ACCOUNT
        assertTrue(switching.hasUnsent())
        switching.syncAll(emptyList())
        assertEquals("s2", server.savedSessions.last().sessionId)
        assertFalse(switching.hasUnsent())
    }

    @Test
    fun aPassAsksWhetherItsSignInLastsNotOnlyTheAccountKey() = runTest {
        var signIns = 1
        // Like AppContainer.stillSignedInAs: the sign-in the pass started under, not just the key.
        val checked = ProgressStore(context, { ACCOUNT }, server, stillSignedInAs = { account ->
            val started = signIns
            val lasts: () -> Boolean = { signIns == started && account == ACCOUNT }
            lasts
        })
        checked.enqueueSession(FILE, BOOK, session("s1", endProgress = 20.0))
        checked.enqueueSession(FILE, BOOK, session("s2", endProgress = 30.0))
        // Signed out and in again (another user whose key is the same) while s1 was on its way.
        server.afterSession = { signIns++ }

        checked.syncAll(emptyList())

        assertEquals(listOf("s1"), server.savedSessions.map { it.sessionId })
        assertTrue(checked.hasUnsent())
    }

    // --- helpers -----------------------------------------------------------------------------

    private class FakeServer : ProgressRemote {
        /** Position of [FILE] (and of any file without its own entry in [positions]). */
        var position = FileProgress(cfi = null, percentage = 0.0)
        val positions = HashMap<Long, FileProgress>()
        var saveFailure: Exception? = null
        var sessionFailure: Exception? = null
        /** Thrown by every position read while set. */
        var progressFailure: Exception? = null
        /** Runs once a session has reached the server, before its answer returns. */
        var afterSession: (() -> Unit)? = null
        val savedPositions = ArrayList<SaveProgress>()
        val savedSessions = ArrayList<ReadingSession>()
        val log = ArrayList<String>()

        override suspend fun fileProgress(fileId: Long): FileProgress {
            progressFailure?.let { throw it }
            return positions[fileId] ?: position
        }

        override suspend fun saveProgress(fileId: Long, body: SaveProgress) {
            saveFailure?.let { saveFailure = null; throw it }
            savedPositions += body
            log += "position $fileId"
            val stored = FileProgress(body.cfi, body.percentage)
            if (fileId in positions) positions[fileId] = stored else position = stored
        }

        override suspend fun saveSession(fileId: Long, body: ReadingSession) {
            sessionFailure?.let { throw it }
            savedSessions += body
            log += "session ${body.sessionId}"
            afterSession?.invoke()
        }
    }

    private companion object {
        const val ACCOUNT = "books.example.net_reader"
        const val FILE = 11L
        const val BOOK = 1L
        const val OTHER_FILE = 22L
        const val OTHER_BOOK = 2L

        fun save(cfi: String, percentage: Double) = SaveProgress(cfi = cfi, percentage = percentage)

        fun progress(cfi: String, percentage: Double, lastReadAt: String? = null) =
            FileProgress(cfi = cfi, percentage = percentage, lastReadAt = lastReadAt)

        fun session(id: String, endProgress: Double?) = ReadingSession(
            sessionId = id,
            startedAt = "2026-09-24T09:00:00.000Z",
            endedAt = "2026-09-24T09:30:00.000Z",
            durationSeconds = 1800,
            progressDelta = 10.0,
            endProgress = endProgress,
        )
    }
}
