package io.github.ottershelf.feature.achievements

import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.tracking.ActivityDay
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.core.tracking.TimelineSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RewindMathTest {

    private fun days(vararg pairs: Pair<String, Long>) = pairs.map { LocalDate.parse(it.first) to it.second }

    @Test
    fun longestStreakAndBestDay() {
        val d = days(
            "2026-01-01" to 600, "2026-01-02" to 0, "2026-01-03" to 300, "2026-01-04" to 900,
            "2026-01-05" to 1200, "2026-01-06" to 0, "2026-02-10" to 5000,
        )
        val streak = RewindMath.longestStreak(d)!!
        assertEquals(3, streak.days)
        assertEquals(LocalDate.parse("2026-01-03"), streak.start)
        assertEquals(LocalDate.parse("2026-01-05"), streak.end)
        assertEquals(LocalDate.parse("2026-02-10"), RewindMath.bestDay(d)!!.first)
        assertNull(RewindMath.longestStreak(days("2026-03-01" to 0)))
    }

    @Test
    fun monthlySecondsFillTwelveMonths() {
        val m = RewindMath.monthlySeconds(days("2026-01-01" to 600, "2026-01-20" to 400, "2026-12-31" to 60))
        assertEquals(12, m.size)
        assertEquals(1000.0, m[0], 0.0)
        assertEquals(60.0, m[11], 0.0)
    }

    @Test
    fun topAuthorsCountBooks() {
        val books = listOf(
            BookCard(1, authors = listOf("Conan Doyle")), BookCard(2, authors = listOf("Dickens", "Collins")),
            BookCard(3, authors = listOf("Dickens")), BookCard(4, authors = listOf("Conan Doyle")), BookCard(5, authors = listOf("Dickens")),
        )
        val top = RewindMath.topAuthors(books)
        assertEquals(listOf("Dickens", "Conan Doyle", "Collins"), top.map { it.label })
        assertEquals(3.0, top[0].value, 0.0)
    }

    @Test
    fun peakHoursSplitAtTheHourInTheAccountZone() {
        val zone = ZoneId.of("Europe/Dublin")
        val sessions = listOf(
            // 21:30 to 22:30 Dublin summer time (UTC+1): 30 min at 21, 30 min at 22.
            TimelineSession(1, 5, startedAt = "2026-07-01T20:30:00Z", endedAt = "2026-07-01T21:30:00Z", durationSeconds = 3600),
            TimelineSession(1, 5, startedAt = "2026-07-01T20:30:00Z", endedAt = "2026-07-01T21:30:00Z", durationSeconds = 3600), // listed twice
            TimelineSession(2, 5, startedAt = "2025-12-31T23:30:00Z", endedAt = "2026-01-01T00:00:00Z", durationSeconds = 1800), // last year
        )
        val hours = RewindMath.peakHours(sessions, zone, 2026)
        assertEquals(1800.0, hours[21], 0.0)
        assertEquals(1800.0, hours[22], 0.0)
        assertEquals(3600.0, hours.sum(), 0.0)
        assertEquals(21, RewindMath.peakHour(hours))
    }

    @Test
    fun weeksCoverTheYearUpToToday() {
        val weeks = RewindMath.weeksOf(2026, LocalDate.of(2026, 1, 20))
        // 1 January 2026 is a Thursday: ISO week 1 of 2026.
        assertEquals(listOf(2026 to 1, 2026 to 2, 2026 to 3, 2026 to 4), weeks)
        val full = RewindMath.weeksOf(2021, LocalDate.of(2026, 9, 25))
        assertEquals(2020 to 53, full.first()) // 1 January 2021 is in 2020's week 53
        assertTrue(full.size in 52..54)
    }

    @Test
    fun finishedAttemptAndDaysTaken() {
        val attempts = listOf(
            ReadingAttempt(1, 9, startedOn = "2025-03-01", endedOn = "2025-03-20", outcome = "completed"),
            ReadingAttempt(2, 9, startedOn = "2026-05-01", endedOn = "2026-05-04", outcome = "completed", totalSeconds = 7200),
            ReadingAttempt(3, 9, startedOn = "2026-06-01", endedOn = null, outcome = null),
        )
        val a = RewindMath.finishedAttempt(attempts, 2026)!!
        assertEquals(2L, a.id)
        assertEquals(4, RewindMath.daysTaken(a))
    }

    @Test
    fun defaultYearIsLastYearInJanuary() {
        assertEquals(2025, RewindMath.defaultYear(LocalDate.of(2026, 1, 15)))
        assertEquals(2026, RewindMath.defaultYear(LocalDate.of(2026, 9, 25)))
    }

    @Test
    fun anEmptyLastYearCanStillMoveOnToThisYear() {
        // January, and the user's first session is this year: last year answers 400 (no years at all).
        assertEquals(listOf(2025, 2026), RewindMath.years(emptyList(), shown = 2025, thisYear = 2026, known = emptyList()))
        // Years learned from an earlier load are kept.
        assertEquals(listOf(2023, 2024, 2025, 2026), RewindMath.years(emptyList(), 2025, 2026, known = listOf(2023, 2024, 2026)))
        assertEquals(listOf(2024, 2025, 2026), RewindMath.years(listOf(2024, 2025, 2026), 2026, 2026, emptyList()))
    }

    @Test
    fun timeIncludesListening() {
        val days = RewindMath.daysOf(
            listOf(ActivityDay("2026-03-01", totalSeconds = 1_800, readingSeconds = 600, listeningSeconds = 1_200), ActivityDay("2025-12-31", totalSeconds = 60)),
            2026,
        )
        assertEquals(listOf(LocalDate.parse("2026-03-01") to 1_800L), days)
    }

    @Test
    fun coverCellsCountEveryFinish() {
        assertEquals(4 to 0, RewindMath.coverCells(count = 4, listed = 4))
        assertEquals(4 to 1, RewindMath.coverCells(count = 5, listed = 4)) // a reread's finish not found as a book
        assertEquals(9 to 3, RewindMath.coverCells(count = 12, listed = 11))
        assertEquals(10 to 0, RewindMath.coverCells(count = 10, listed = 10))
        assertEquals(0 to 3, RewindMath.coverCells(count = 3, listed = 0))
        assertEquals(9 to 3, RewindMath.coverCells(count = 11, listed = 12)) // more books than the timeline: all of them
    }
}
