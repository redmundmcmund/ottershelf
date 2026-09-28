package io.github.ottershelf.feature.achievements

import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.tracking.ActivityDay
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.core.tracking.TimelineSession
import io.github.ottershelf.feature.stats.RankedItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

/** A run of consecutive reading days. */
data class Streak(val days: Int, val start: LocalDate, val end: LocalDate)

/** The year in review's arithmetic (pure: RewindMathTest). */
object RewindMath {

    /**
     * The year's days with their time: reading and listening together (`totalSeconds`), as the
     * Calendar counts it and as the sessions and peak hours (which include listening) do.
     */
    fun daysOf(days: List<ActivityDay>, year: Int): List<Pair<LocalDate, Long>> = days.mapNotNull { d ->
        runCatching { LocalDate.parse(d.day) }.getOrNull()?.takeIf { it.year == year }?.let { it to d.totalSeconds }
    }.sortedBy { it.first }

    /**
     * The years the review can move between: the server's (it names only years with reading, and
     * answers 400 for an empty one before them), the one shown, this year (so an empty January
     * review of last year can still move on) and any learned from an earlier load.
     */
    fun years(server: List<Int>, shown: Int, thisYear: Int, known: List<Int>): List<Int> =
        (server + shown + thisYear + known).filter { it <= thisYear || it == shown }.distinct().sorted()

    /**
     * How many covers the books card has room for (ten, or nine and a "+N" cell), from the
     * [count] finished (the completion timeline's) and the [listed] books that could be found.
     */
    fun coverCells(count: Int, listed: Int): Pair<Int, Int> {
        val total = maxOf(count, listed)
        val shown = minOf(listed, if (total > 10) 9 else 10)
        return shown to (total - shown)
    }

    /** Reading seconds per month, January first (12 values). */
    fun monthlySeconds(days: List<Pair<LocalDate, Long>>): List<Double> {
        val out = DoubleArray(12)
        days.forEach { (date, s) -> out[date.monthValue - 1] += s.toDouble() }
        return out.toList()
    }

    /** The longest run of days with reading (the latest of equal runs), or null with none. */
    fun longestStreak(days: List<Pair<LocalDate, Long>>): Streak? {
        var best: Streak? = null
        var start: LocalDate? = null
        var prev: LocalDate? = null
        for ((date, s) in days.filter { it.second > 0 }) {
            start = if (prev != null && ChronoUnit.DAYS.between(prev, date) == 1L) start else date
            prev = date
            val length = ChronoUnit.DAYS.between(start, date).toInt() + 1
            if (best == null || length >= best.days) best = Streak(length, start!!, date)
        }
        return best
    }

    fun bestDay(days: List<Pair<LocalDate, Long>>): Pair<LocalDate, Long>? = days.filter { it.second > 0 }.maxByOrNull { it.second }

    /** Authors by books finished (the most first, then by name), [top] at most. */
    fun topAuthors(books: List<BookCard>, top: Int = 5): List<RankedItem> =
        books.flatMap { b -> b.authors.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(top)
            .map { RankedItem(it.key, it.value.toDouble()) }

    /** The ISO weeks (week-based year, week) whose days touch [year], for session-timeline. */
    fun weeksOf(year: Int, today: LocalDate): List<Pair<Int, Int>> {
        val first = LocalDate.of(year, 1, 1)
        val last = minOf(LocalDate.of(year, 12, 31), today)
        if (last.isBefore(first)) return emptyList()
        val out = LinkedHashSet<Pair<Int, Int>>()
        var d = first
        while (!d.isAfter(last)) {
            out += d.get(IsoFields.WEEK_BASED_YEAR) to d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            d = d.plusDays(7)
        }
        out += last.get(IsoFields.WEEK_BASED_YEAR) to last.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return out.toList()
    }

    /**
     * Reading seconds per local hour of day (24 values) from [sessions] that started in [year]
     * (in [zone]); a session running past the hour is split at each hour, and each session counts
     * once even if two weeks list it.
     */
    fun peakHours(sessions: List<TimelineSession>, zone: ZoneId, year: Int): List<Double> {
        val out = DoubleArray(24)
        sessions.distinctBy { it.sessionId }.forEach { s ->
            val start = runCatching { Instant.parse(s.startedAt).atZone(zone) }.getOrNull() ?: return@forEach
            if (start.year != year || s.durationSeconds <= 0) return@forEach
            var left = s.durationSeconds.toLong()
            var at = start
            while (left > 0) {
                val toNextHour = 3600L - (at.minute * 60L + at.second)
                val chunk = minOf(left, toNextHour)
                out[at.hour] += chunk.toDouble()
                left -= chunk
                at = at.plusSeconds(chunk)
            }
        }
        return out.toList()
    }

    /** The hour with the most reading, or null. */
    fun peakHour(hours: List<Double>): Int? = hours.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index

    /**
     * The reading of [book] that finished in [year]: its completed attempt ended that year (the
     * latest), so the days it took (first to last day, inclusive) and its reading time.
     */
    fun finishedAttempt(attempts: List<ReadingAttempt>, year: Int): ReadingAttempt? =
        attempts.filter { it.outcome == AttemptOutcome.COMPLETED && it.endedOn?.take(4) == year.toString() }.maxByOrNull { it.endedOn.orEmpty() }

    fun daysTaken(attempt: ReadingAttempt): Int? {
        val start = attempt.startedOn?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null
        val end = attempt.endedOn?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null
        return (ChronoUnit.DAYS.between(start, end).toInt() + 1).takeIf { it >= 1 }
    }

    /** Finished books per month of [year] from the completion timeline (12 values). */
    fun completionsByMonth(rows: List<io.github.ottershelf.feature.stats.model.MonthCount>, year: Int): List<Double> {
        val out = DoubleArray(12)
        rows.filter { it.year == year && it.month in 1..12 }.forEach { out[it.month - 1] += it.count }
        return out.toList()
    }

    /** The year the review opens on: last year during January, else this year. */
    fun defaultYear(today: LocalDate): Int = if (today.monthValue == 1) today.year - 1 else today.year
}
