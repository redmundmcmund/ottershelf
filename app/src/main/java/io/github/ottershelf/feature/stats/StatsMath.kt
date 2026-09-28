package io.github.ottershelf.feature.stats

import io.github.ottershelf.feature.stats.model.ArchetypePoint
import io.github.ottershelf.feature.stats.model.DailyReadingStat
import io.github.ottershelf.feature.stats.model.DecadeCount
import io.github.ottershelf.feature.stats.model.FavoriteDayStat
import io.github.ottershelf.feature.stats.model.GoalTrajectory
import io.github.ottershelf.feature.stats.model.GoalTrajectoryPoint
import io.github.ottershelf.feature.stats.model.MonthCount
import io.github.ottershelf.feature.stats.model.Overview
import io.github.ottershelf.feature.stats.model.OverviewGoalPoint
import io.github.ottershelf.feature.stats.model.PacePoint
import io.github.ottershelf.feature.stats.model.ProgressFunnel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** The period row's choices: they scope the charts that take a window (the web's are fixed at 365). */
enum class StatsPeriod(private val fixedDays: Int?) {
    D30(30), D90(90), D365(365), YEAR(null);

    /** Days in the window ending [today]; This year = 1 January to today. */
    fun days(today: LocalDate): Int = fixedDays ?: today.dayOfYear
}

/** One bar of the daily reading chart: a day, or seven days for the long periods. */
data class TimeBucket(val start: LocalDate, val end: LocalDate, val seconds: Double, val previousSeconds: Double)

/**
 * The daily reading chart's data: [buckets] oldest first, each with the previous period's twin. The
 * totals and [activeDays] cover all [days]; a weekly chart leaves out a partial oldest week's column.
 */
data class DailySeries(
    val days: Int,
    val bucketDays: Int,
    val buckets: List<TimeBucket>,
    val totalSeconds: Double,
    val previousTotalSeconds: Double,
    val activeDays: Int,
)

/** A change against an earlier period (see [StatsMath.change]). */
sealed interface Change {
    /** Nothing in the earlier period. */
    data object NoBase : Change
    data class Percent(val percent: Int) : Change
    /** The earlier figure was too small for a meaningful percentage: say what it was. */
    data class From(val previous: Double, val up: Boolean) : Change
}

/**
 * The goal trajectory chart: finished books, cumulative, over the last 12 months. [goalBooks] is
 * the user's yearly goal, or null without one: the server always sends a target line (it needs a goal
 * to answer), and then it is left out.
 */
data class Trajectory(val points: List<GoalTrajectoryPoint>, val goalBooks: Int?)

/** One day of the heatmap year. */
data class HeatDay(val date: LocalDate, val seconds: Double, val sessions: Int, val future: Boolean)

/** One stage of the progress funnel. */
data class FunnelStage(val count: Int, val ofStarted: Float)

/** A column of the decades chart: [decade], or every decade before it when [earlier]. */
data class DecadeColumn(val decade: Int, val count: Double, val earlier: Boolean = false)

/** A ranked bar (genres, authors, formats): [value] in the chart's unit. */
data class RankedItem(val label: String, val value: Double, val key: String = label, val other: Boolean = false)

object StatsMath {

    /** Charts with a window take its days; the daily chart asks for twice as many (the comparison). */
    fun dailySeries(rows: List<DailyReadingStat>, today: LocalDate, days: Int): DailySeries {
        val byDay = HashMap<LocalDate, Double>(rows.size * 2)
        rows.forEach { row -> runCatching { LocalDate.parse(row.day.take(10)) }.getOrNull()?.let { byDay[it] = (byDay[it] ?: 0.0) + row.readingSeconds } }
        val bucketDays = if (days <= 90) 1 else 7
        val start = today.minusDays(days - 1L)
        val count = ceil(days / bucketDays.toDouble()).toInt()
        fun sum(from: LocalDate, to: LocalDate): Double {
            var total = 0.0
            var d = from
            while (!d.isAfter(to)) { total += byDay[d] ?: 0.0; d = d.plusDays(1) }
            return total
        }
        val buckets = (count - 1 downTo 0).map { k ->
            val end = today.minusDays(k.toLong() * bucketDays)
            val from = maxOf(start, end.minusDays(bucketDays - 1L))
            TimeBucket(from, end, sum(from, end), sum(from.minusDays(days.toLong()), end.minusDays(days.toLong())))
        }
        // A column of one to six days on the weekly scale would read as a slump: its days still
        // count in the totals, but it isn't drawn.
        val partial = buckets.size > 1 && buckets.first().start.isAfter(buckets.first().end.minusDays(bucketDays - 1L))
        var active = 0
        var d = start
        while (!d.isAfter(today)) { if ((byDay[d] ?: 0.0) > 0) active++; d = d.plusDays(1) }
        return DailySeries(
            days = days,
            bucketDays = bucketDays,
            buckets = if (partial) buckets.drop(1) else buckets,
            totalSeconds = sum(start, today),
            previousTotalSeconds = sum(start.minusDays(days.toLong()), today.minusDays(days.toLong())),
            activeDays = active,
        )
    }

    /**
     * [current] against [previous] as the user reads it: nothing before ([Change.NoBase]); a percentage
     * when the earlier figure is at least [minBase] and the change at most [maxPercent]; else the
     * earlier figure itself ("up from 18 s"), since +3717 % on 18 seconds says nothing.
     */
    fun change(current: Double, previous: Double, minBase: Double, maxPercent: Int = MAX_PERCENT): Change {
        if (previous <= 0.0) return Change.NoBase
        val percent = ((current - previous) / previous * 100).roundToInt()
        return if (previous < minBase || percent > maxPercent) Change.From(previous, up = current >= previous) else Change.Percent(percent)
    }

    /** Reading time: a percentage needs at least five minutes before. */
    const val MIN_TIME_BASE_SECONDS = 300.0

    /** Past +300 % the earlier figure says more than the percentage. */
    const val MAX_PERCENT = 300

    /** The finish rate's comparison needs as many books started before as the funnel itself (the web's 10). */
    const val MIN_FUNNEL_STARTED = 10.0

    /**
     * [o] with the year's finished books from `completion-timeline` [months] (completed readings by
     * the month they ended): the Books this year box and the goal card (count, points, projection,
     * status, by the server's formulas) then count what the charts and the dashboard's reading goal
     * count. Some servers build the overview's figures from sessions that reached the end, which
     * misses every book marked read by hand; newer ones count these same readings. Without
     * [months] (that request failed) the overview's own figures stay.
     */
    fun withFinishedBooks(o: Overview, months: List<MonthCount>?, today: LocalDate): Overview {
        if (months == null) return o
        val year = o.goal?.year ?: today.year
        val perMonth = DoubleArray(13)
        months.forEach { if (it.year == year && it.month in 1..12) perMonth[it.month] += it.count }
        val completed = perMonth.sum().roundToInt()
        val goal = o.goal?.let { g ->
            val target = g.goalBooks?.takeIf { it > 0 }
            var running = 0.0
            val points = (1..12).map { m ->
                running += perMonth[m]
                OverviewGoalPoint(m, running, target?.let { (it * m / 12.0 * 100).roundToInt() / 100.0 })
            }
            val daysInYear = LocalDate.of(year, 1, 1).lengthOfYear()
            val dayOfYear = when {
                today.year == year -> today.dayOfYear
                today.year > year -> daysInYear
                else -> 0
            }
            val projected = if (dayOfYear > 0) (completed.toDouble() / dayOfYear * daysInYear * 10).roundToInt() / 10.0 else 0.0
            val status = target?.let {
                val delta = completed - it.toDouble() * dayOfYear / daysInYear
                when {
                    delta >= 0.5 -> "ahead"
                    delta <= -0.5 -> "behind"
                    else -> "on_pace"
                }
            }
            g.copy(goalBooks = target, completedBooks = completed, projectedBooks = projected, status = status, points = points)
        }
        val snapshot = if (year == today.year) o.snapshot.copy(completedBooksYtd = completed) else o.snapshot
        return o.copy(snapshot = snapshot, goal = goal)
    }

    /**
     * goal-trajectory's `days`: the 12 whole months ending this one. The server counts its months
     * in UTC from `days` ago, so [todayUtc] is today in UTC; 365 days would start a 13th, partial month.
     */
    fun trajectoryDays(todayUtc: LocalDate): Int =
        (ChronoUnit.DAYS.between(todayUtc.withDayOfMonth(1).minusMonths(11), todayUtc) + 1).toInt()

    /**
     * The last 12 months of [t], both lines counted from the first of them (a clock a moment off the
     * server's around UTC midnight can still bring a 13th month), so the goal line ends at the goal.
     */
    fun lastTwelveMonths(t: GoalTrajectory): GoalTrajectory {
        if (t.points.size <= 12) return t
        val base = t.points[t.points.size - 13].actualCumulative
        val perMonth = t.goalBooks / 12.0
        return t.copy(
            points = t.points.takeLast(12).mapIndexed { i, p ->
                p.copy(actualCumulative = p.actualCumulative - base, targetCumulative = perMonth * (i + 1))
            },
        )
    }

    /** Every day of [year], zero-filled (daily-reading lists only the days with reading). */
    fun heatmapYear(rows: List<DailyReadingStat>, year: Int, today: LocalDate): List<HeatDay> {
        val byDay = rows.associateBy { it.day.take(10) }
        val first = LocalDate.of(year, 1, 1)
        return (0 until first.lengthOfYear()).map { i ->
            val date = first.plusDays(i.toLong())
            val row = byDay[date.toString()]
            HeatDay(date, row?.readingSeconds ?: 0.0, row?.eventsCount?.roundToInt() ?: 0, date.isAfter(today))
        }
    }

    /** The web heatmap's fixed bins (minutes a day): 0, 1-15, 16-30, 31-60, over 60. */
    fun heatLevel(seconds: Double): Int {
        val minutes = seconds / 60.0
        return when {
            minutes <= 0.0 -> 0
            minutes <= 15 -> 1
            minutes <= 30 -> 2
            minutes <= 60 -> 3
            else -> 4
        }
    }

    /** Sunday-first (0 = Sunday, the server's) to Monday-first, as the app's calendar. */
    fun mondayFirst(sundayIndex: Int): Int = (sundayIndex + 6) % 7

    /** The weekday of a Monday-first index. */
    fun weekday(mondayIndex: Int): DayOfWeek = DayOfWeek.of(mondayIndex + 1)

    /**
     * daily-reading rows (the user's local days) as weekday totals (0 = Sunday, as favorite-days), over
     * the [days] ending [today]. favorite-days itself takes the weekday in UTC, which puts reading
     * after local midnight on the wrong day.
     */
    fun favoriteDays(rows: List<DailyReadingStat>, days: Int, today: LocalDate): List<FavoriteDayStat> {
        val from = today.minusDays(days - 1L)
        val seconds = DoubleArray(7)
        val events = DoubleArray(7)
        rows.forEach { row ->
            val date = runCatching { LocalDate.parse(row.day.take(10)) }.getOrNull() ?: return@forEach
            if (date.isBefore(from) || date.isAfter(today)) return@forEach
            val i = date.dayOfWeek.value % 7
            seconds[i] += row.readingSeconds
            events[i] += row.eventsCount
        }
        return (0 until 7).map { FavoriteDayStat(it, seconds[it], events[it]) }
    }

    /**
     * session-archetypes in the user's zone: the server reads the hour and weekday in UTC, so each point
     * moves by [offsetHours] (the zone's current offset; approximate across a DST change), rolling
     * the weekday over midnight.
     */
    fun localArchetypes(points: List<ArchetypePoint>, offsetHours: Double): List<ArchetypePoint> = points.map { p ->
        val hour = p.hour + offsetHours
        val dayShift = floor(hour / 24).toInt()
        p.copy(hour = hour - dayShift * 24, dayOfWeek = Math.floorMod(p.dayOfWeek + dayShift, 7))
    }

    /**
     * favorite-days as average minutes a day, Monday first: each weekday's total over how many
     * times it occurs in the [days] ending [today] (the web's FavoriteReadingDaysChart).
     */
    fun weekdayAverages(rows: List<FavoriteDayStat>, days: Int, today: LocalDate): List<Double> {
        val occurrences = IntArray(7)
        var d = today.minusDays(days - 1L)
        while (!d.isAfter(today)) { occurrences[d.dayOfWeek.value - 1]++; d = d.plusDays(1) }
        val totals = DoubleArray(7)
        rows.forEach { if (it.dayOfWeek in 0..6) totals[mondayFirst(it.dayOfWeek)] += it.readingSeconds }
        return (0 until 7).map { totals[it] / 60.0 / max(1, occurrences[it]) }
    }

    /** Months from [from] to [to], zero-filled. */
    fun months(rows: List<MonthCount>, from: YearMonth, to: YearMonth): List<MonthCount> {
        val byMonth = rows.groupBy { YearMonth.of(it.year, it.month.coerceIn(1, 12)) }.mapValues { (_, v) -> v.sumOf { it.count } }
        val out = ArrayList<MonthCount>()
        var m = from
        while (!m.isAfter(to)) { out += MonthCount(m.year, m.monthValue, byMonth[m] ?: 0.0); m = m.plusMonths(1) }
        return out
    }

    /** The completion timeline from the first month with a finished book (all zeros: empty). */
    fun completionMonths(rows: List<MonthCount>, today: LocalDate): List<MonthCount> {
        val first = rows.filter { it.count > 0 }.minOfOrNull { YearMonth.of(it.year, it.month) } ?: return emptyList()
        return months(rows, first, YearMonth.from(today))
    }

    /** Clean axis ticks from 0 covering [max]: 0, 15, 30, 45... (at most [count] + 1). */
    fun niceTicks(max: Double, count: Int = 4, integer: Boolean = false): List<Double> {
        if (max <= 0.0) return listOf(0.0, 1.0)
        val raw = max / count
        val magnitude = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }.let { if (integer) max(1.0, ceil(it)) else it }
        val top = ceil(max / step) * step
        return generateSequence(0.0) { it + step }.takeWhile { it <= top + step / 1000 }.toList()
    }

    /** Clean time ticks for minutes: 15-minute, half-hour, hour, then multi-hour steps. */
    fun timeTicks(maxMinutes: Double, count: Int = 3): List<Double> {
        if (maxMinutes <= 0.0) return listOf(0.0, 15.0)
        val raw = maxMinutes / count
        val steps = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 60.0, 120.0, 180.0, 240.0, 360.0, 480.0, 720.0, 1440.0, 2880.0, 7200.0, 14400.0)
        val step = steps.firstOrNull { it >= raw } ?: (ceil(raw / 14400.0) * 14400.0)
        val top = ceil(maxMinutes / step) * step
        return generateSequence(0.0) { it + step }.takeWhile { it <= top + step / 1000 }.toList()
    }

    fun funnel(f: ProgressFunnel): List<FunnelStage> {
        val counts = listOf(f.started, f.reached25, f.reached50, f.reached75, f.completed).map { it.roundToInt() }
        val started = counts.first()
        return counts.map { FunnelStage(it, if (started > 0) it.toFloat() / started else 0f) }
    }

    /** Finished / started, 0..1, or null with nothing started. */
    fun finishRate(f: ProgressFunnel?): Float? = f?.takeIf { it.started > 0 }?.let { (it.completed / it.started).toFloat() }

    /**
     * The first [top] items and the rest folded into one "Other" item (never a ninth hue). Items
     * already marked [RankedItem.other] (the server's own "Other" row) go into it, never ranked.
     */
    fun topWithOther(items: List<RankedItem>, top: Int, otherLabel: String): List<RankedItem> {
        val (folded, ranked) = items.filter { it.value > 0 }.partition { it.other }
        val sorted = ranked.sortedByDescending { it.value }
        val alreadyOther = folded.sumOf { it.value }
        val keep = if (alreadyOther <= 0 && sorted.size <= top + 1) sorted.size else minOf(top, sorted.size)
        val rest = sorted.drop(keep).sumOf { it.value } + alreadyOther
        return sorted.take(keep) + if (rest > 0) listOf(RankedItem(otherLabel, rest, key = "__other", other = true)) else emptyList()
    }

    /**
     * The decades chart's columns: every decade from the first to the last with books, zero-filled
     * so the gaps show; decades more than [span] years before the last (metadata outliers such as
     * year 1) fold into one leading column ([DecadeColumn.earlier]).
     */
    fun decadeColumns(rows: List<DecadeCount>, span: Int = 150): List<DecadeColumn> {
        if (rows.isEmpty()) return emptyList()
        val byDecade = rows.groupBy { Math.floorDiv(it.decade, 10) * 10 }.mapValues { (_, v) -> v.sumOf { it.count } }
        val last = byDecade.keys.max()
        val cutoff = Math.floorDiv(last - span, 10) * 10
        val earlier = byDecade.filterKeys { it < cutoff }.values.sum()
        val first = byDecade.keys.filter { it >= cutoff }.min()
        val columns = (first..last step 10).map { DecadeColumn(it, byDecade[it] ?: 0.0) }
        return if (earlier > 0) listOf(DecadeColumn(cutoff, earlier, earlier = true)) + columns else columns
    }

    /** The pace scatter's points: sessions up to four hours with 0-100 % progress (the web's filter). */
    fun pacePoints(points: List<PacePoint>): List<PacePoint> =
        points.filter { it.durationSeconds > 0 && it.durationSeconds <= 14_400 && it.progressDelta > 0 && it.progressDelta <= 100 }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    /** 1536 -> "1.5 KB" (binary units, as the web). */
    fun bytes(value: Double): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = value
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0 || v >= 100) "${v.roundToInt()} ${units[i]}" else String.format(Locale.getDefault(), "%.1f %s", v, units[i])
    }

    /** 1234 -> "1,234"; 12345 -> "12.3K" past 10 000. */
    fun compact(value: Double): String = when {
        value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000)
        value >= 10_000 -> String.format(Locale.getDefault(), "%.1fK", value / 1_000)
        else -> String.format(Locale.getDefault(), "%,d", value.roundToInt())
    }

    /** 21.5 -> "21:30". */
    fun clock(hour: Double): String {
        val minutes = ((hour * 60).roundToInt() % 1440 + 1440) % 1440
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    }
}
