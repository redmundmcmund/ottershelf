package io.github.ottershelf.feature.stats

import io.github.ottershelf.feature.stats.model.ArchetypePoint
import io.github.ottershelf.feature.stats.model.DailyReadingStat
import io.github.ottershelf.feature.stats.model.DecadeCount
import io.github.ottershelf.feature.stats.model.FavoriteDayStat
import io.github.ottershelf.feature.stats.model.GoalTrajectory
import io.github.ottershelf.feature.stats.model.GoalTrajectoryPoint
import io.github.ottershelf.feature.stats.model.MonthCount
import io.github.ottershelf.feature.stats.model.Overview
import io.github.ottershelf.feature.stats.model.OverviewGoal
import io.github.ottershelf.feature.stats.model.OverviewGoalPoint
import io.github.ottershelf.feature.stats.model.Snapshot
import io.github.ottershelf.feature.stats.model.ProgressFunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class StatsMathTest {

    private val today = LocalDate.of(2026, 9, 25) // a Friday

    @Test
    fun dailySeriesSplitsCurrentAndPrevious() {
        val rows = listOf(
            DailyReadingStat("2026-09-25", 600.0),
            DailyReadingStat("2026-08-27", 1200.0), // first day of the 30
            DailyReadingStat("2026-08-26", 300.0), // last day of the previous 30
            DailyReadingStat("2026-07-01", 999.0), // outside both
        )
        val s = StatsMath.dailySeries(rows, today, 30)
        assertEquals(1, s.bucketDays)
        assertEquals(30, s.buckets.size)
        assertEquals(LocalDate.of(2026, 8, 27), s.buckets.first().start)
        assertEquals(1800.0, s.totalSeconds, 0.0)
        assertEquals(300.0, s.previousTotalSeconds, 0.0)
        assertEquals(300.0, s.buckets.last().previousSeconds, 0.0) // 25 Sep - 30 days = 26 Aug
        assertEquals(2, s.activeDays)
    }

    @Test
    fun longPeriodsGoWeeklyWithoutAPartialFirstColumn() {
        // 365 days = 52 weeks and one day: that day counts in the total but gets no column.
        val rows = listOf(DailyReadingStat(today.minusDays(364).toString(), 600.0), DailyReadingStat(today.toString(), 60.0))
        val s = StatsMath.dailySeries(rows, today, 365)
        assertEquals(7, s.bucketDays)
        assertEquals(52, s.buckets.size)
        assertEquals(today, s.buckets.last().end)
        assertEquals(today.minusDays(363), s.buckets.first().start)
        assertEquals(today.minusDays(357), s.buckets.first().end)
        assertEquals(660.0, s.totalSeconds, 0.0)
        assertEquals(2, s.activeDays)
        // This year on 25 Sep: 268 days, so 1-2 January are left out of the columns.
        val year = StatsMath.dailySeries(emptyList(), today, StatsPeriod.YEAR.days(today))
        assertEquals(LocalDate.of(2026, 1, 3), year.buckets.first().start)
        // Whole weeks keep every column.
        assertEquals(LocalDate.of(2026, 6, 27), StatsMath.dailySeries(emptyList(), today, 91).buckets.first().start)
    }

    @Test
    fun goalTrajectoryCoversTwelveWholeMonths() {
        // 1 October 2025 to 25 September 2026: the server starts at October, not a partial September.
        assertEquals(360, StatsMath.trajectoryDays(today))
        assertEquals(365, StatsMath.trajectoryDays(LocalDate.of(2026, 12, 31)))
        assertEquals(335, StatsMath.trajectoryDays(LocalDate.of(2026, 1, 1)))
        assertEquals(366, StatsMath.trajectoryDays(LocalDate.of(2024, 12, 31)))

        // A 13th month (clocks either side of UTC midnight): the last 12, counted from their start.
        val points = (1..13).map { i -> GoalTrajectoryPoint(2025 + (8 + i) / 12, (8 + i) % 12 + 1, actualCumulative = i.toDouble(), targetCumulative = i.toDouble()) }
        val t = StatsMath.lastTwelveMonths(GoalTrajectory(12, points))
        assertEquals(12, t.points.size)
        assertEquals(1.0, t.points.first().actualCumulative, 0.0)
        assertEquals(12.0, t.points.last().actualCumulative, 0.0)
        assertEquals(12.0, t.points.last().targetCumulative, 1e-9)
        val twelve = GoalTrajectory(12, points.take(12))
        assertEquals(twelve, StatsMath.lastTwelveMonths(twelve))
    }

    @Test
    fun weekdaysComeFromLocalDays() {
        val rows = listOf(
            DailyReadingStat("2026-09-25", 600.0, eventsCount = 2.0), // Friday
            DailyReadingStat("2026-09-20", 300.0, eventsCount = 1.0), // Sunday
            DailyReadingStat("2026-09-11", 999.0, eventsCount = 5.0), // Friday, before the 14 days
        )
        val days = StatsMath.favoriteDays(rows, 14, today)
        assertEquals(7, days.size)
        assertEquals(600.0, days[5].readingSeconds, 0.0)
        assertEquals(2.0, days[5].eventsCount, 0.0)
        assertEquals(300.0, days[0].readingSeconds, 0.0)
        assertEquals(0.0, days.sumOf { it.readingSeconds } - 900.0, 0.0)
    }

    @Test
    fun archetypesMoveIntoTheUsersZone() {
        val moved = StatsMath.localArchetypes(
            listOf(ArchetypePoint(hour = 20.5, durationMinutes = 30.0, dayOfWeek = 1), ArchetypePoint(hour = 23.5, dayOfWeek = 6), ArchetypePoint(hour = 0.25, dayOfWeek = 0)),
            offsetHours = 1.0,
        )
        assertEquals(21.5, moved[0].hour, 1e-9)
        assertEquals(1, moved[0].dayOfWeek)
        assertEquals(30.0, moved[0].durationMinutes, 0.0)
        assertEquals(0.5, moved[1].hour, 1e-9) // Saturday 23:30 UTC is Sunday 00:30
        assertEquals(0, moved[1].dayOfWeek)
        val behind = StatsMath.localArchetypes(listOf(ArchetypePoint(hour = 0.25, dayOfWeek = 0)), offsetHours = -5.0)
        assertEquals(19.25, behind[0].hour, 1e-9) // Sunday 00:15 UTC is Saturday 19:15
        assertEquals(6, behind[0].dayOfWeek)
    }

    @Test
    fun decadesAreZeroFilledWithOutliersFolded() {
        val columns = StatsMath.decadeColumns(listOf(DecadeCount(1920, 5.0), DecadeCount(1850, 2.0), DecadeCount(1940, 1.0)))
        assertEquals((1850..1940 step 10).toList(), columns.map { it.decade })
        assertEquals(listOf(2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 5.0, 0.0, 1.0), columns.map { it.count })
        val outliers = StatsMath.decadeColumns(listOf(DecadeCount(0, 1.0), DecadeCount(1060, 1.0), DecadeCount(1990, 3.0), DecadeCount(2020, 4.0)))
        assertEquals(DecadeColumn(1870, 2.0, earlier = true), outliers.first())
        assertEquals(listOf(1990, 2000, 2010, 2020), outliers.drop(1).map { it.decade })
        assertEquals(emptyList<DecadeColumn>(), StatsMath.decadeColumns(emptyList()))
    }

    @Test
    fun deltaAndLevels() {
        val base = StatsMath.MIN_TIME_BASE_SECONDS
        assertEquals(Change.Percent(50), StatsMath.change(1500.0, 1000.0, base))
        assertEquals(Change.Percent(-25), StatsMath.change(750.0, 1000.0, base))
        assertEquals(Change.Percent(300), StatsMath.change(4000.0, 1000.0, base))
        assertEquals(Change.NoBase, StatsMath.change(10.0, 0.0, base))
        // The phone check: 11 min against 18 s was "+3717%".
        assertEquals(Change.From(18.0, up = true), StatsMath.change(11 * 60.0 + 7, 18.0, base))
        assertEquals(Change.From(240.0, up = false), StatsMath.change(60.0, 240.0, base))
        // A large base still says "up from" past +300 %.
        assertEquals(Change.From(1000.0, up = true), StatsMath.change(5000.0, 1000.0, base))
        assertEquals(listOf(0, 1, 2, 3, 4, 4), listOf(0.0, 60.0, 16 * 60.0, 60 * 60.0, 61 * 60.0, 5 * 3600.0).map { StatsMath.heatLevel(it) })
    }

    /** The phone check: 14 books marked read in September, the overview (from sessions) saying 0. */
    @Test
    fun finishedBooksComeFromTheCompletionTimeline() {
        val overview = Overview(
            snapshot = Snapshot(completedBooksYtd = 0),
            goal = OverviewGoal(year = 2026, goalBooks = null, completedBooks = 0, projectedBooks = 0.0, points = (1..12).map { OverviewGoalPoint(it, 0.0) }),
        )
        val months = listOf(MonthCount(2025, 12, 3.0), MonthCount(2026, 1, 1.0), MonthCount(2026, 9, 14.0))
        val fixed = StatsMath.withFinishedBooks(overview, months, today)
        assertEquals(15, fixed.snapshot.completedBooksYtd) // 2026 only: January and September
        val goal = fixed.goal!!
        assertEquals(15, goal.completedBooks)
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 15.0, 15.0, 15.0, 15.0), goal.points.map { it.actualCumulative })
        assertNull(goal.points.first().targetCumulative)
        assertNull(goal.status)
        // The server's projection: 15 over 268 days of 365.
        assertEquals(20.4, goal.projectedBooks, 0.0)

        // With a goal: the target line and the status, by the server's formulas.
        val withGoal = StatsMath.withFinishedBooks(overview.copy(goal = overview.goal!!.copy(goalBooks = 24)), months, today).goal!!
        assertEquals(24.0, withGoal.points.last().targetCumulative!!, 0.0)
        assertEquals(2.0, withGoal.points.first().targetCumulative!!, 0.0)
        assertEquals("behind", withGoal.status) // 15 against 17.6 by 25 September
        assertEquals("ahead", StatsMath.withFinishedBooks(overview.copy(goal = overview.goal!!.copy(goalBooks = 12)), months, today).goal!!.status)
        assertEquals("on_pace", StatsMath.withFinishedBooks(overview.copy(goal = overview.goal!!.copy(goalBooks = 20)), months, today).goal!!.status)

        // The timeline request failed: the overview's own figures stay.
        assertEquals(overview, StatsMath.withFinishedBooks(overview, null, today))
    }

    @Test
    fun heatmapCoversTheWholeYear() {
        val days = StatsMath.heatmapYear(listOf(DailyReadingStat("2026-03-02", 900.0, eventsCount = 2.0)), 2026, today)
        assertEquals(365, days.size)
        assertEquals(900.0, days[31 + 28 + 1].seconds, 0.0)
        assertEquals(2, days[31 + 28 + 1].sessions)
        assertEquals(false, days[today.dayOfYear - 1].future)
        assertEquals(true, days[today.dayOfYear].future)
    }

    @Test
    fun weekdaysAreMondayFirstAverages() {
        // 14 days ending Friday 25 Sep: every weekday occurs twice.
        val rows = listOf(FavoriteDayStat(0, 2 * 1800.0), FavoriteDayStat(1, 1200.0))
        val avg = StatsMath.weekdayAverages(rows, 14, today)
        assertEquals(10.0, avg[0], 0.001) // Monday: 1200 s over 2 days = 10 min
        assertEquals(30.0, avg[6], 0.001) // Sunday: 3600 s over 2 days = 30 min
        assertEquals(0, StatsMath.mondayFirst(1))
        assertEquals(6, StatsMath.mondayFirst(0))
    }

    @Test
    fun monthsAreZeroFilled() {
        val months = StatsMath.completionMonths(listOf(MonthCount(2026, 7, 0.0), MonthCount(2026, 8, 2.0)), today)
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9)), months.map { YearMonth.of(it.year, it.month) })
        assertEquals(listOf(2.0, 0.0), months.map { it.count })
        assertEquals(emptyList<MonthCount>(), StatsMath.completionMonths(listOf(MonthCount(2026, 1, 0.0)), today))
    }

    @Test
    fun ticksAreClean() {
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0), StatsMath.niceTicks(55.0, 3))
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), StatsMath.niceTicks(2.2, 3, integer = true))
        assertEquals(listOf(0.0, 1.0), StatsMath.niceTicks(0.0))
    }

    @Test
    fun funnelAndOther() {
        val stages = StatsMath.funnel(ProgressFunnel(10.0, 8.0, 6.0, 5.0, 4.0))
        assertEquals(listOf(10, 8, 6, 5, 4), stages.map { it.count })
        assertEquals(0.4f, stages.last().ofStarted, 0.001f)
        val items = (1..10).map { RankedItem("g$it", it.toDouble()) }
        val top = StatsMath.topWithOther(items, 3, "Other")
        assertEquals(listOf("g10", "g9", "g8", "Other"), top.map { it.label })
        assertEquals((1..7).sum().toDouble(), top.last().value, 0.0)
        assertEquals(listOf("g4", "g3", "g2", "g1"), StatsMath.topWithOther(items.take(4), 3, "Other").map { it.label })
    }

    @Test
    fun theServersOtherRowJoinsOurs() {
        // Over ten languages the server sends its own "Other": never a ranked accent bar beside ours.
        val items = listOf(RankedItem("English", 50.0), RankedItem("French", 20.0), RankedItem("Other", 30.0, other = true), RankedItem("German", 10.0))
        val all = StatsMath.topWithOther(items, 6, "Other")
        assertEquals(listOf("English", "French", "German", "Other"), all.map { it.label })
        assertEquals(listOf(false, false, false, true), all.map { it.other })
        assertEquals(30.0, all.last().value, 0.0)
        val two = StatsMath.topWithOther(items, 2, "Other")
        assertEquals(listOf("English", "French", "Other"), two.map { it.label })
        assertEquals(40.0, two.last().value, 0.0)
    }

    @Test
    fun text() {
        assertEquals("21:30", StatsMath.clock(21.5))
        assertEquals("00:00", StatsMath.clock(24.0))
        assertEquals("512 B", StatsMath.bytes(512.0))
        assertEquals("200 GB", StatsMath.bytes(200.0 * 1024 * 1024 * 1024))
    }
}
