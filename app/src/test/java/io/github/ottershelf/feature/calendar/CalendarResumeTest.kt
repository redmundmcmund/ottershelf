package io.github.ottershelf.feature.calendar

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
import io.github.ottershelf.core.tracking.ActivityCalendar
import io.github.ottershelf.core.tracking.ActivityDay
import io.github.ottershelf.core.tracking.ActivityDayDetail
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.feature.history.HistoryBooksPage
import io.github.ottershelf.feature.history.HistoryCache
import io.github.ottershelf.feature.history.HistoryCard
import io.github.ottershelf.feature.history.HistoryLoader
import io.github.ottershelf.feature.history.HistoryRemote
import io.github.ottershelf.feature.history.ReadingLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

/**
 * When the Calendar loads again. Every return used to clear the year's calendar and load the month
 * and the marks (which page through every book with a status) once the last load was 30 s old.
 * Now a tracking write reloads at once (once, though it moves two signals), and a return only after
 * something was sent, on a new day, after a failure, or ten minutes on; the year's calendar is kept
 * five minutes while the user moves between months.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarResumeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val version = MutableStateFlow(0)
    private val changes = MutableStateFlow(0)
    private var today = LocalDate.of(2026, 9, 27)
    private var calendarCalls = 0
    private var dayCalls = 0
    private var calendarFails = false
    private val history = Remote()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Remote : HistoryRemote {
        var bookCalls = 0
        override suspend fun books(page: Int): HistoryBooksPage {
            bookCalls++
            val cards = listOf(HistoryCard(1, "Frankenstein", readStatus = ReadStatusInfo("read")))
            return HistoryBooksPage(cards, cards.size, page, HistoryRemote.PAGE_SIZE)
        }

        override suspend fun attempts(bookId: Long): List<ReadingAttempt> = emptyList()
    }

    private fun newViewModel() = CalendarViewModel(
        activityCalendar = { year ->
            calendarCalls++
            if (calendarFails) throw IOException("offline")
            // Today has reading: its day is asked for on every load of this month.
            ActivityCalendar(year, availableYears = listOf(2025, 2026), days = listOf(ActivityDay(today.toString(), totalSeconds = 600, sessionsCount = 1)))
        },
        activityDay = { day ->
            dayCalls++
            ActivityDayDetail(day.toString())
        },
        trackingVersion = version,
        readingChanges = changes,
        cache = CalendarCache(File(folder.root, "calendar/reader"), io = dispatcher),
        log = ReadingLog(HistoryLoader(history), HistoryCache(File(folder.root, "history/reader.json"), io = dispatcher)),
        todayNow = { today },
        coverOf = { null },
        clock = { scheduler.currentTime + START },
    )

    /** Opened (the first load), then shown: what the screen does. */
    private fun opened(): CalendarViewModel {
        val vm = newViewModel()
        scheduler.advanceUntilIdle()
        vm.onResume()
        scheduler.advanceUntilIdle()
        assertLoads(1, 1)
        return vm
    }

    /** The user opens a book and comes back [afterMs] later. */
    private fun CalendarViewModel.away(afterMs: Long, meanwhile: () -> Unit = {}) {
        scheduler.advanceTimeBy(afterMs / 2)
        meanwhile()
        scheduler.advanceTimeBy(afterMs - afterMs / 2)
        onResume()
        scheduler.advanceUntilIdle()
    }

    /** [months] loads of the month (today's day asked each time) and [marks] of the marks' book pages. */
    private fun assertLoads(months: Int, marks: Int) {
        assertEquals("activity-days", months, dayCalls)
        assertEquals("books/query", marks, history.bookCalls)
    }

    @Test
    fun aReturnTwoMinutesLaterWithNothingChangedAsksNothing() = runTest(dispatcher) {
        val vm = opened()
        vm.away(2 * 60_000)
        vm.away(5 * 60_000)
        assertLoads(1, 1)
        assertEquals(1, calendarCalls)
    }

    @Test
    fun aTrackingWriteReloadsAtOnceAndOnlyOnce() = runTest(dispatcher) {
        val vm = opened()
        // A session logged on a book page over the Calendar: both signals move.
        version.value++
        changes.value++
        advanceUntilIdle()
        assertLoads(2, 2)
        assertEquals(2, calendarCalls)
        // The return that follows asks nothing more: that load had it.
        vm.away(60_000)
        assertLoads(2, 2)
    }

    @Test
    fun somethingTheReaderSentWhileAwayReloadsOnTheReturn() = runTest(dispatcher) {
        val vm = opened()
        vm.away(2 * 60_000) { changes.value++ }
        assertLoads(2, 2)
        // A session sent can be on any day: the year's calendar is asked again too.
        assertEquals(2, calendarCalls)
        vm.away(60_000)
        assertLoads(2, 2)
    }

    @Test
    fun aNewDayReloadsOnTheReturn() = runTest(dispatcher) {
        val vm = opened()
        vm.away(2 * 60_000) { today = today.plusDays(1) }
        assertLoads(2, 2)
        assertEquals(today, vm.state.value.today)
    }

    @Test
    fun tenMinutesLaterReloadsOnTheReturn() = runTest(dispatcher) {
        val vm = opened()
        vm.away(9 * 60_000)
        assertLoads(1, 1)
        vm.away(2 * 60_000)
        assertLoads(2, 2)
        // Kept five minutes: this one is older, so it was asked again.
        assertEquals(2, calendarCalls)
    }

    @Test
    fun aFailedLoadIsTriedAgainOnTheReturn() = runTest(dispatcher) {
        val vm = opened()
        calendarFails = true
        vm.refresh()
        advanceUntilIdle()
        assertEquals(true, vm.state.value.failed)
        calendarFails = false
        vm.away(30_000)
        assertEquals(false, vm.state.value.failed)
        assertLoads(2, 3)
        vm.away(30_000)
        assertLoads(2, 3)
    }

    @Test
    fun theYearsCalendarIsKeptFiveMinutesBetweenMonths() = runTest(dispatcher) {
        val vm = opened()
        advanceTimeBy(4 * 60_000)
        vm.previous()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 8), vm.state.value.month)
        assertEquals(1, calendarCalls)
        advanceTimeBy(2 * 60_000)
        vm.next()
        advanceUntilIdle()
        assertEquals(2, calendarCalls)
    }

    @Test
    fun pullToRefreshAlwaysReloads() = runTest(dispatcher) {
        val vm = opened()
        vm.refresh()
        advanceUntilIdle()
        assertLoads(2, 2)
        assertEquals(2, calendarCalls)
    }

    private companion object {
        const val START = 1_790_000_000_000L
    }
}
