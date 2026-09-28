package io.github.ottershelf.feature.calendar

import io.github.ottershelf.core.tracking.ActivityCalendar
import io.github.ottershelf.core.tracking.ActivityDay
import io.github.ottershelf.core.tracking.ActivityDayDetail
import io.github.ottershelf.core.tracking.DaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarModelsTest {

    private val sept = YearMonth.of(2026, 9)
    private val today = LocalDate.of(2026, 9, 25)

    private fun session(id: Long, bookId: Long, seconds: Long, title: String = "Book $bookId") =
        DaySession(id = id, bookId = bookId, bookTitle = title, hasCover = true, startedAt = "2026-09-03T10:00:00Z", endedAt = "2026-09-03T11:00:00Z", durationOnDaySeconds = seconds)

    @Test
    fun dayGroupsSessionsByBookMostReadFirst() {
        val detail = ActivityDayDetail(
            day = "2026-09-03",
            totals = ActivityDay("2026-09-03", totalSeconds = 4200, sessionsCount = 3),
            sessions = listOf(session(1, 10, 600), session(2, 20, 3000), session(3, 10, 600)),
        )
        val day = CalendarMath.dayOf(detail)
        assertEquals(listOf(20L, 10L), day.books.map { it.bookId })
        assertEquals(1200L, day.books[1].seconds)
        assertEquals(4200L, day.totalSeconds)
        assertEquals(3, day.sessionsCount)
    }

    @Test
    fun onlyNewChangedEmptyAndTodayAreFetched() {
        val active = listOf(
            ActivityDay("2026-09-01", totalSeconds = 600, sessionsCount = 1),
            ActivityDay("2026-09-02", totalSeconds = 900, sessionsCount = 2),
            ActivityDay("2026-09-03", totalSeconds = 300, sessionsCount = 1),
            ActivityDay("2026-09-04", totalSeconds = 300, sessionsCount = 1),
            ActivityDay("2026-09-25", totalSeconds = 300, sessionsCount = 1),
        )
        val book = CalendarBook(1, "A", true, null, 300)
        val cached = CalendarMonth(
            month = "2026-09",
            days = listOf(
                CalendarDay("2026-09-01", 600, 1, listOf(book)), // unchanged
                CalendarDay("2026-09-02", 600, 1, listOf(book)), // changed
                CalendarDay("2026-09-03", 300, 1, emptyList()), // never loaded
                CalendarDay("2026-09-25", 300, 1, listOf(book)), // today
            ),
        )
        assertEquals(listOf("2026-09-02", "2026-09-03", "2026-09-04", "2026-09-25"), CalendarMath.daysToFetch(active, cached, today))
        assertEquals(active.map { it.day }, CalendarMath.daysToFetch(active, null, today))
    }

    @Test
    fun mergeKeepsCachedDaysAndDropsDaysWithoutReading() {
        val book = CalendarBook(1, "A", true, null, 300)
        val cached = CalendarMonth("2026-09", listOf(CalendarDay("2026-09-01", 600, 1, listOf(book)), CalendarDay("2026-09-09", 60, 1, listOf(book))))
        val active = listOf(ActivityDay("2026-09-01", totalSeconds = 600, sessionsCount = 1), ActivityDay("2026-09-02", totalSeconds = 120, sessionsCount = 1))
        val merged = CalendarMath.merge(sept, active, cached, fetched = emptyMap(), availableYears = listOf(2025, 2026), now = 5)
        assertEquals(listOf("2026-09-01", "2026-09-02"), merged.days.map { it.day })
        assertEquals(listOf(book), merged.days[0].books)
        // Its detail failed: totals only, fetched next time.
        assertTrue(merged.days[1].books.isEmpty())
        assertEquals(120L, merged.days[1].totalSeconds)
    }

    @Test
    fun aChangedDayWhoseDetailFailedIsFetchedAgainNextTime() {
        val a = CalendarBook(1, "A", true, null, 1800)
        val cached = CalendarMonth("2026-09", listOf(CalendarDay("2026-09-03", 1800, 2, listOf(a))))
        // Book B was read on the 3rd elsewhere; its detail failed this time (another day loaded).
        val active = listOf(ActivityDay("2026-09-03", totalSeconds = 3600, sessionsCount = 3))
        val merged = CalendarMath.merge(sept, active, cached, fetched = emptyMap(), availableYears = listOf(2026), now = 5)
        assertEquals(listOf("2026-09-03"), CalendarMath.daysToFetch(active, merged, today))
        // Pulling to refresh (or the next load) then brings its books.
        val b = CalendarBook(2, "B", true, null, 1800)
        val fetched = mapOf("2026-09-03" to CalendarDay("2026-09-03", 3600, 3, listOf(a, b)))
        val fixed = CalendarMath.merge(sept, active, merged, fetched, listOf(2026), now = 6)
        assertEquals(listOf(1L, 2L), fixed.days.single().books.map { it.bookId })
        assertTrue(CalendarMath.daysToFetch(active, fixed, today).isEmpty())
    }

    @Test
    fun activeDaysAreThisMonthsWithSessions() {
        val calendar = ActivityCalendar(
            year = 2026,
            days = listOf(
                ActivityDay("2026-08-31", totalSeconds = 60, sessionsCount = 1),
                ActivityDay("2026-09-01", totalSeconds = 0, sessionsCount = 0),
                ActivityDay("2026-09-02", totalSeconds = 60, sessionsCount = 1),
            ),
        )
        assertEquals(listOf("2026-09-02"), CalendarMath.activeDays(calendar, sept).map { it.day })
    }

    @Test
    fun summaryAndBooks() {
        val a = CalendarBook(1, "A", true, null, 600)
        val b = CalendarBook(2, "B", true, null, 300)
        val month = CalendarMonth(
            "2026-09",
            listOf(
                CalendarDay("2026-09-01", 900, 2, listOf(a, b)),
                CalendarDay("2026-09-02", 600, 1, listOf(a)),
            ),
        )
        assertEquals(MonthSummary(daysRead = 2, totalSeconds = 1500, books = 2), CalendarMath.summary(month))
        val books = CalendarMath.books(month)
        assertEquals(listOf(1L, 2L), books.map { it.book.bookId })
        assertEquals(2, books[0].days)
        assertEquals(1200L, books[0].seconds)
        assertEquals(MonthSummary(0, 0, 0), CalendarMath.summary(null))
    }

    @Test
    fun weeksStartOnMonday() {
        // 1 September 2026 is a Tuesday.
        val weeks = CalendarMath.weeks(sept)
        assertEquals(5, weeks.size)
        assertNull(weeks[0][0])
        assertEquals(LocalDate.of(2026, 9, 1), weeks[0][1])
        assertEquals(LocalDate.of(2026, 9, 30), weeks[4][2])
        assertTrue(weeks.all { it.size == 7 })
        // February 2021 starts on a Monday and fills exactly four weeks.
        assertEquals(4, CalendarMath.weeks(YearMonth.of(2021, 2)).size)
    }

    @Test
    fun goalPaceFollowsTheServersRule() {
        // 25 September 2026 is day 268 of 365: 24 books a year expects 17.6 by now.
        assertEquals(GoalPace.BEHIND, CalendarMath.goalPace(17, 24, today))
        assertEquals(GoalPace.ON_PACE, CalendarMath.goalPace(18, 24, today))
        assertEquals(GoalPace.AHEAD, CalendarMath.goalPace(19, 24, today))
        assertEquals(17, CalendarMath.expectedByNow(24, today))
        assertEquals(9.5, CalendarMath.projected(7, today), 0.001)
    }
}
