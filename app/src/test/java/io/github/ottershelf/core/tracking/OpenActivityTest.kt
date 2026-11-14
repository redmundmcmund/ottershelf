package io.github.ottershelf.core.tracking

import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.UserSettings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The activity views built from the open routes, summed as BookOrbit's reserved activity routes sum them. */
class OpenActivityTest {

    private val today = LocalDate.of(2026, 11, 14)
    private val rows = listOf(
        DailyReadingRow("2024-03-02", 600.0, 1.0),
        DailyReadingRow("2026-11-01", 1_200.0, 2.0),
        DailyReadingRow("2026-11-08", 300.0, 0.0), // reading with no event count still counts as a session
        DailyReadingRow("2026-11-13", 900.0, 1.0),
        DailyReadingRow("2026-11-14", 60.0, 1.0),
    )

    @Test
    fun aCalendarYearIsZeroFilledAndListsTheYearsWithReading() {
        val year = OpenActivity.calendar(2026, rows, today)
        assertEquals(listOf(2024, 2026), year.availableYears)
        assertEquals(365, year.days.size)
        assertEquals("2026-01-01", year.days.first().day)
        assertEquals(ActivityDay("2026-11-01", 1_200, 1_200, sessionsCount = 2), year.days.single { it.day == "2026-11-01" })
        assertEquals(1, year.days.single { it.day == "2026-11-08" }.sessionsCount)
        assertEquals(0, year.days.single { it.day == "2026-11-02" }.totalSeconds)
    }

    @Test
    fun yearsBeforeTheFirstReadingOrAfterTodayHaveNoDays() {
        assertTrue(OpenActivity.calendar(2023, rows, today).days.isEmpty())
        assertTrue(OpenActivity.calendar(2027, rows, today).days.isEmpty())
        assertEquals(366, OpenActivity.calendar(2024, rows, today).days.size)
        assertEquals(listOf(2026), OpenActivity.availableYears(emptyList(), today))
    }

    @Test
    fun theSnapshotSumsTodayAndTheTwoWeeks() {
        val s = OpenActivity.snapshot(rows, today, currentStreak = 2, longestStreak = 5, completedYtd = 7)
        assertEquals(60, s.today.totalSeconds)
        assertEquals(60 + 900 + 300, s.lastSevenDays.totalSeconds) // Nov 8..14
        assertEquals(1_200, s.previousSevenDays.totalSeconds) // Nov 1..7
        assertEquals(2, s.currentStreak)
        assertEquals(5, s.longestStreak)
        assertEquals(7, s.completedBooksYtd)
    }

    @Test
    fun theGoalIsWorkedOutAsTheServerDoes() {
        val months = listOf(CompletionMonth(2026, 1, 2.0), CompletionMonth(2026, 10, 3.0), CompletionMonth(2025, 12, 9.0))
        val perMonth = OpenActivity.finishedPerMonth(months, 2026)
        val goal = OpenActivity.goal(2026, 12, perMonth, today)
        assertEquals(5, goal.completedBooks)
        assertEquals(5.0, goal.points[10].actualCumulative, 0.0)
        assertEquals(11.0, goal.points[10].targetCumulative!!, 0.0)
        assertEquals(5.7, goal.projectedBooks, 0.0) // 5 by day 318 of 365
        assertEquals("behind", goal.status)
        assertNull(OpenActivity.goal(2026, null, perMonth, today).status)
    }

    @Test
    fun theZoneAndGoalComeFromTheUsersSettings() {
        val user = AuthUser(1, "reader", settings = UserSettings(timezone = "Europe/Dublin", dashboardConfig = buildJsonObject { put("readingGoal", JsonPrimitive(24)) }))
        assertEquals(ZoneId.of("Europe/Dublin"), OpenActivity.zone(user))
        assertEquals(24, OpenActivity.goalBooks(user))
        assertEquals(ZoneOffset.UTC, OpenActivity.zone(AuthUser(1, "reader", settings = UserSettings(timezone = "Not/AZone"))))
        assertNull(OpenActivity.goalBooks(null))
    }

    @Test
    fun aDayReachesIntoTheUtcWeeksItOverlaps() {
        // Monday 2026-11-09 in Los Angeles starts on Monday 08:00 UTC and ends Tuesday 08:00 UTC: one week.
        assertEquals(listOf(2026 to 46), OpenActivity.weeksOf(LocalDate.of(2026, 11, 9), ZoneId.of("America/Los_Angeles")))
        // Sunday 2026-11-08 there ends at Monday 08:00 UTC: the end of week 45 and the start of 46.
        assertEquals(listOf(2026 to 45, 2026 to 46), OpenActivity.weeksOf(LocalDate.of(2026, 11, 8), ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun aSessionOverMidnightIsSplitByItsClockTime() {
        val zone = ZoneOffset.UTC
        val sessions = listOf(
            TimelineSession(1, bookId = 7, bookTitle = "Mort", startedAt = "2026-11-13T23:30:00Z", endedAt = "2026-11-14T00:30:00Z", durationSeconds = 3_000),
            TimelineSession(2, bookId = 8, startedAt = "2026-11-14T08:00:00Z", endedAt = "2026-11-14T08:20:00Z", durationSeconds = 1_200),
            TimelineSession(3, bookId = 9, startedAt = "2026-11-15T08:00:00Z", endedAt = "2026-11-15T08:20:00Z", durationSeconds = 1_200),
            TimelineSession(2, bookId = 8, startedAt = "2026-11-14T08:00:00Z", endedAt = "2026-11-14T08:20:00Z", durationSeconds = 1_200), // the next week's copy
        )
        val day = OpenActivity.day(today, zone, sessions)
        assertEquals(listOf(1L, 2L), day.sessions.map { it.id })
        assertEquals(1_500, day.sessions[0].durationOnDaySeconds) // half its clock time is on the 14th
        assertEquals(2_700, day.totals!!.totalSeconds)
        assertEquals(2, day.totals!!.sessionsCount)
        assertNull(day.sessions[0].progressDelta)
    }

    @Test
    fun aSessionWithNoClockTimeCountsWhollyOnTheDayItStarted() {
        val start = Instant.parse("2026-11-14T10:00:00Z")
        val from = Instant.parse("2026-11-14T00:00:00Z")
        val to = Instant.parse("2026-11-15T00:00:00Z")
        assertEquals(600L, OpenActivity.secondsOnDay(start, start, 600, from, to))
        assertNull(OpenActivity.secondsOnDay(start, start, 0, from, to))
        assertNull(OpenActivity.secondsOnDay(to, to, 600, from, to))
    }
}
