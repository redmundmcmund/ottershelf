package io.github.ottershelf.feature.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.tracking.ReadingAttempt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * When History loads again: every return used to re-page every book with a status (200 full cards a
 * request) once the last load was 30 s old. Now only a change here, a new day, a failed load or ten
 * minutes do; a return from a book page with nothing changed asks nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryResumeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val version = MutableStateFlow(0)
    private val changes = MutableStateFlow(0)
    private var today = LocalDate.of(2026, 9, 27)
    private val remote = Remote()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Remote : HistoryRemote {
        var bookCalls = 0
        var fail = false
        override suspend fun books(page: Int): HistoryBooksPage {
            bookCalls++
            if (fail) throw IOException("offline")
            val cards = listOf(HistoryCard(1, "Frankenstein", readStatus = ReadStatusInfo("read")))
            return HistoryBooksPage(cards, cards.size, page, HistoryRemote.PAGE_SIZE)
        }

        override suspend fun attempts(bookId: Long): List<ReadingAttempt> = emptyList()
    }

    private fun newViewModel() = HistoryViewModel(
        remote = remote,
        cache = HistoryCache(File(folder.root, "history/reader.json"), io = dispatcher),
        account = "reader",
        trackingVersion = version,
        readingChanges = changes,
        todayNow = { today },
        thumbnail = { _, _ -> null },
        clock = { scheduler.currentTime + START },
    )

    /** Opened (the first load), then shown: what the screen does. */
    private fun opened(): HistoryViewModel {
        val vm = newViewModel()
        scheduler.advanceUntilIdle()
        vm.onResume()
        scheduler.advanceUntilIdle()
        assertEquals(1, remote.bookCalls)
        return vm
    }

    /** The user opens a book and comes back [afterMs] later. */
    private fun HistoryViewModel.away(afterMs: Long, meanwhile: () -> Unit = {}) {
        onPause()
        scheduler.advanceTimeBy(afterMs / 2)
        meanwhile()
        scheduler.advanceTimeBy(afterMs - afterMs / 2)
        onResume()
        scheduler.advanceUntilIdle()
    }

    @Test
    fun aReturnTwoMinutesLaterWithNothingChangedAsksNothing() = runTest(dispatcher) {
        val vm = opened()
        vm.away(2 * 60_000)
        vm.away(5 * 60_000)
        assertEquals(1, remote.bookCalls)
    }

    @Test
    fun aTrackingWriteWhileAwayLoadsOnTheReturn() = runTest(dispatcher) {
        val vm = opened()
        // A session logged on the book page: the tracker's version and ReadingChanges move together.
        vm.away(2 * 60_000) {
            version.value++
            changes.value++
        }
        assertEquals(2, remote.bookCalls)
        // Once: back again, nothing new.
        vm.away(60_000)
        assertEquals(2, remote.bookCalls)
    }

    @Test
    fun somethingTheReaderSentWhileAwayLoadsOnTheReturn() = runTest(dispatcher) {
        val vm = opened()
        vm.away(2 * 60_000) { changes.value++ }
        assertEquals(2, remote.bookCalls)
    }

    @Test
    fun aWriteWhileOnScreenLoadsOnceForBothSignals() = runTest(dispatcher) {
        val vm = opened()
        // The dates sheet saved from History itself.
        version.value++
        changes.value++
        advanceTimeBy(100)
        assertEquals(1, remote.bookCalls)
        advanceUntilIdle()
        assertEquals(2, remote.bookCalls)
        vm.away(60_000)
        assertEquals(2, remote.bookCalls)
    }

    @Test
    fun aNewDayLoadsOnTheReturn() = runTest(dispatcher) {
        val vm = opened()
        vm.away(2 * 60_000) { today = today.plusDays(1) }
        assertEquals(2, remote.bookCalls)
    }

    @Test
    fun tenMinutesLaterLoadsOnTheReturn() = runTest(dispatcher) {
        val vm = opened()
        vm.away(9 * 60_000)
        assertEquals(1, remote.bookCalls)
        vm.away(2 * 60_000)
        assertEquals(2, remote.bookCalls)
    }

    @Test
    fun aFailedLoadIsTriedAgainOnTheReturn() = runTest(dispatcher) {
        val vm = opened()
        remote.fail = true
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, remote.bookCalls)
        remote.fail = false
        vm.away(30_000)
        assertEquals(3, remote.bookCalls)
        vm.away(30_000)
        assertEquals(3, remote.bookCalls)
    }

    @Test
    fun pullToRefreshAlwaysLoads() = runTest(dispatcher) {
        val vm = opened()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, remote.bookCalls)
    }

    private companion object {
        /** The wall clock at the test's start (0 would read as "never loaded"). */
        const val START = 1_790_000_000_000L
    }
}
