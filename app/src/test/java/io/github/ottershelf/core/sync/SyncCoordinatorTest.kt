package io.github.ottershelf.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.FileProgress
import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.model.SaveProgress
import io.github.ottershelf.core.network.Connectivity
import io.github.ottershelf.core.sync.ProgressStore.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** SyncCoordinator's in-process sync against a fake server, online (Robolectric's default network). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SyncCoordinatorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The fake SystemClock.elapsedRealtime. */
    private var now = 0L
    private var online = true
    private var signedIn = true

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        context.getSharedPreferences("progress", Context.MODE_PRIVATE).edit().clear().commit()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    private fun TestScope.store(server: ProgressRemote) =
        ProgressStore(context, { ACCOUNT }, server, io = StandardTestDispatcher(testScheduler))

    private fun TestScope.coordinator(
        progress: ProgressStore,
        downloads: List<Pair<Long, Long>> = emptyList(),
        intervalMs: Long = SyncCoordinator.BASELINE_INTERVAL_MS,
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = SyncCoordinator(
        signedIn = { signedIn },
        progress = progress,
        connectivity = Connectivity(context),
        scheduler = SyncScheduler(context),
        scope = backgroundScope,
        downloadedFiles = { downloads },
        elapsedRealtime = { now },
        isOnline = { online },
        baselineIntervalMs = intervalMs,
        io = io,
    )

    // --- when the downloaded books' positions are fetched ------------------------------------

    @Test
    fun aResumeInANewProcessFetchesTheDownloadedBooksPositions() = runTest {
        val server = CountingServer()
        val coordinator = coordinator(store(server), DOWNLOADS)

        coordinator.sync() // MainActivity.onResume
        runCurrent()

        assertEquals(DOWNLOADED_FILES, server.progressAsked.sorted())
    }

    @Test
    fun aResumeWithinTheIntervalSendsWhatWaitsWithoutAskingForEveryDownload() = runTest {
        val server = CountingServer()
        val progress = store(server)
        val coordinator = coordinator(progress, DOWNLOADS, intervalMs = 15 * 60_000L)
        coordinator.sync()
        runCurrent()
        server.progressAsked.clear()

        // Read a little (the send failed), left, and came back a minute later.
        progress.setBaseline(FILE, BOOK, Position("a", 10.0))
        progress.savePending(FILE, BOOK, SaveProgress(cfi = "b", percentage = 20.0))
        now += 60_000
        coordinator.sync()
        runCurrent()

        assertEquals(listOf(SaveProgress(cfi = "b", percentage = 20.0)), server.saved)
        assertEquals(listOf(FILE), server.progressAsked) // the check before sending; no download asked
    }

    @Test
    fun aResumeWithinTheIntervalWithNothingWaitingAsksNothing() = runTest {
        val server = CountingServer()
        val coordinator = coordinator(store(server), DOWNLOADS)
        coordinator.sync()
        runCurrent()
        server.progressAsked.clear()

        now += 10_000
        coordinator.sync()
        runCurrent()

        assertTrue(server.progressAsked.isEmpty())
    }

    @Test
    fun aResumeOnceTheIntervalHasPassedFetchesThemAgain() = runTest {
        val server = CountingServer()
        val coordinator = coordinator(store(server), DOWNLOADS)
        coordinator.sync()
        runCurrent()
        server.progressAsked.clear()

        now += SyncCoordinator.BASELINE_INTERVAL_MS
        coordinator.sync()
        runCurrent()

        assertEquals(DOWNLOADED_FILES, server.progressAsked.sorted())
    }

    @Test
    fun theConnectionComingBackWithinTheIntervalSendsWhatWaitsWithoutTheDownloads() = runTest {
        val server = CountingServer()
        val progress = store(server)
        val coordinator = coordinator(progress, DOWNLOADS)
        coordinator.sync()
        runCurrent()
        server.progressAsked.clear()

        progress.enqueueSession(FILE, BOOK, session("s1"))
        now += 5_000
        coordinator.sync(force = true) // what the connection's return runs with the app on screen
        runCurrent()

        assertEquals(listOf("s1"), server.sessions)
        assertTrue(server.progressAsked.isEmpty())
    }

    @Test
    fun signingInAlwaysFetchesThem() = runTest {
        val server = CountingServer()
        val coordinator = coordinator(store(server), DOWNLOADS)
        coordinator.sync()
        runCurrent()
        server.progressAsked.clear()

        now += 1_000
        coordinator.onSignedIn()
        runCurrent()

        assertEquals(DOWNLOADED_FILES, server.progressAsked.sorted())
    }

    @Test
    fun aPassCutShortDoesntCountAsAFetch() = runTest {
        val server = CountingServer()
        val coordinator = coordinator(store(server), DOWNLOADS)
        server.unreachable = true // the connection drops as the resume's pass starts
        coordinator.sync()
        runCurrent()
        server.unreachable = false
        server.progressAsked.clear()

        now += 5_000
        coordinator.sync(force = true) // its return, with the app on screen
        runCurrent()

        assertEquals(DOWNLOADED_FILES, server.progressAsked.sorted())
    }

    @Test
    fun theReadersQuickSyncsDontCountAsAFetch() = runTest {
        val server = CountingServer()
        val coordinator = coordinator(store(server), DOWNLOADS)

        coordinator.sync(force = true, baselines = false) // a reading session ended
        runCurrent()
        assertTrue(server.progressAsked.isEmpty())

        coordinator.sync()
        runCurrent()
        assertEquals(DOWNLOADED_FILES, server.progressAsked.sorted())
    }

    // --- the WorkManager flush -----------------------------------------------------------------

    @Test
    fun offlineWithSomethingWaitingTheFlushIsScheduled() = runTest {
        val progress = store(CountingServer())
        val coordinator = coordinator(progress)
        progress.enqueueSession(FILE, BOOK, session("s1"), sendingNow = true) // the reader saw a connection
        online = false // gone by the time the sync runs

        coordinator.sync(force = true, baselines = false)
        runCurrent()

        assertTrue(flushScheduled())
    }

    @Test
    fun offlineWithNothingWaitingNothingIsScheduled() = runTest {
        val coordinator = coordinator(store(CountingServer()))
        online = false

        coordinator.sync()
        runCurrent()

        assertFalse(flushScheduled())
    }

    @Test
    fun theOfflineFlushIsEnqueuedOffTheMainThread() = runTest {
        val progress = store(CountingServer())
        val io = HeldDispatcher()
        val coordinator = coordinator(progress, io = io)
        progress.enqueueSession(FILE, BOOK, session("s1"), sendingNow = true)
        online = false

        coordinator.sync() // a resume, offline
        runCurrent()
        assertFalse(flushScheduled())

        io.runAll() // the check and the enqueue, with nothing more from the main thread
        assertTrue(flushScheduled())
    }

    @Test
    fun aPassAskedForDuringASyncThatCantRunOfflineIsLeftToTheFlush() = runTest {
        val server = SlowServer()
        val progress = store(server)
        progress.enqueueSession(FILE, BOOK, session("s1"), sendingNow = true)
        val coordinator = coordinator(progress)
        coordinator.sync(force = true, baselines = false)
        runCurrent()
        assertEquals(listOf("s1"), server.sessions) // on its way

        // Another reading session ends meanwhile (the reader still saw a connection)...
        progress.enqueueSession(FILE, BOOK, session("s2"), sendingNow = true)
        coordinator.sync(force = true, baselines = false)
        runCurrent()
        // ...and the connection goes before the pass under way ends.
        online = false
        server.answer.complete(Unit)
        runCurrent()

        assertEquals(listOf("s1"), server.sessions)
        assertTrue(flushScheduled())
    }

    @Test
    fun aSyncThatSendsEverythingSchedulesNoFlush() = runTest {
        val server = CountingServer()
        val progress = store(server)
        val coordinator = coordinator(progress)
        progress.enqueueSession(FILE, BOOK, session("s1"), sendingNow = true)

        coordinator.sync(force = true, baselines = false)
        runCurrent()

        assertEquals(listOf("s1"), server.sessions)
        assertFalse(flushScheduled())
    }

    private fun flushScheduled(): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.FLUSH_WORK).get().any { !it.state.isFinished }

    // --- app start -------------------------------------------------------------------------------

    @Test
    fun startSchedulesThePeriodicSyncOffTheMainThreadWhenSignedIn() = runTest {
        coordinator(store(CountingServer())).start()
        assertFalse(periodicScheduled()) // not on the caller's thread (Application.onCreate)

        runCurrent()
        assertTrue(periodicScheduled())
    }

    @Test
    fun startSchedulesNothingWhenSignedOut() = runTest {
        signedIn = false
        coordinator(store(CountingServer())).start()
        runCurrent()
        assertFalse(periodicScheduled())
    }

    private fun periodicScheduled(): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK).get().any { !it.state.isFinished }

    // --- signing out ---------------------------------------------------------------------------

    @Test
    fun signingOutStopsTheSyncUnderWay() = runTest {
        val server = SlowServer()
        val progress = store(server)
        progress.enqueueSession(FILE, BOOK, session("s1"))
        progress.enqueueSession(FILE, BOOK, session("s2"))
        val coordinator = coordinator(progress)

        coordinator.sync(force = true, baselines = false)
        runCurrent()
        assertEquals(listOf("s1"), server.sessions) // on its way

        // Signed out (or the refresh token rejected) while s1 waits for its answer. (runCurrent, not
        // advanceUntilIdle: that one leaves work in backgroundScope alone.)
        coordinator.onSignedOut()
        runCurrent()
        server.answer.complete(Unit)
        runCurrent()

        // s2 never went; both stay queued for this account's next sign-in (sessions are retry-safe).
        assertEquals(listOf("s1"), server.sessions)
        assertTrue(progress.hasUnsent())
    }

    /** Answers at once, counting what is asked and sent. */
    private class CountingServer : ProgressRemote {
        val progressAsked = ArrayList<Long>()
        val saved = ArrayList<SaveProgress>()
        val sessions = ArrayList<String>()
        private val positions = HashMap<Long, FileProgress>()
        /** Position reads fail as with no connection (counted all the same). */
        var unreachable = false

        override suspend fun fileProgress(fileId: Long): FileProgress {
            progressAsked += fileId
            if (unreachable) throw IOException("offline")
            return positions[fileId] ?: if (fileId == FILE) FileProgress(cfi = "a", percentage = 10.0) else FileProgress(percentage = 0.0)
        }

        override suspend fun saveProgress(fileId: Long, body: SaveProgress) {
            saved += body
            positions[fileId] = FileProgress(cfi = body.cfi, percentage = body.percentage)
        }

        override suspend fun saveSession(fileId: Long, body: ReadingSession) {
            sessions += body.sessionId
        }
    }

    /** Sessions reach it at once; each answer waits for [answer]. */
    private class SlowServer : ProgressRemote {
        val answer = CompletableDeferred<Unit>()
        val sessions = ArrayList<String>()

        override suspend fun fileProgress(fileId: Long) = FileProgress(cfi = null, percentage = 0.0)

        override suspend fun saveProgress(fileId: Long, body: SaveProgress) = Unit

        override suspend fun saveSession(fileId: Long, body: ReadingSession) {
            sessions += body.sessionId
            answer.await()
        }
    }

    /** An IO dispatcher that holds what is sent to it until [runAll], apart from the test's own. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks += block
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private companion object {
        const val ACCOUNT = "books.example.net_reader"
        const val FILE = 11L
        const val BOOK = 1L

        /** (book id, file id) of two downloaded books. */
        val DOWNLOADS = listOf(2L to 22L, 3L to 33L)
        val DOWNLOADED_FILES = listOf(22L, 33L)

        fun session(id: String) = ReadingSession(
            sessionId = id,
            startedAt = "2026-09-24T09:00:00.000Z",
            endedAt = "2026-09-24T09:30:00.000Z",
            durationSeconds = 1800,
            progressDelta = 10.0,
            endProgress = 40.0,
        )
    }
}
