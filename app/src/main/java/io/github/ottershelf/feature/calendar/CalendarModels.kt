package io.github.ottershelf.feature.calendar

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.tracking.ActivityCalendar
import io.github.ottershelf.core.tracking.ActivityDay
import io.github.ottershelf.core.tracking.ActivityDayDetail
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.feature.history.CachedBook
import io.github.ottershelf.feature.history.HistoryMath
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

// The Book Calendar's data: what a month shows (cached on the device per month), and the pure
// rules for building it from `activity-calendar/:year` and `activity-days/:day`. Tested in
// CalendarModelsTest.

/** A book read on a day: what its cover needs, and how long it was read that day. */
@Serializable
data class CalendarBook(
    val bookId: Long,
    val title: String? = null,
    val hasCover: Boolean = false,
    /** The server's metadata time: the thumbnail's version. */
    val coverVersion: String? = null,
    /** Reading time on that day (the day's part of sessions over midnight). */
    val seconds: Long = 0,
)

/** One day with reading (the server's local day, `YYYY-MM-DD`). */
@Serializable
data class CalendarDay(
    val day: String,
    val totalSeconds: Long = 0,
    val sessionsCount: Int = 0,
    /** The books read, most read first. Empty when the day's detail couldn't be loaded yet. */
    val books: List<CalendarBook> = emptyList(),
)

/** A month of the calendar as cached on the device (`YYYY-MM`): only the days with reading. */
@Serializable
data class CalendarMonth(
    val month: String,
    val days: List<CalendarDay> = emptyList(),
    val availableYears: List<Int> = emptyList(),
    val fetchedAt: Long = 0,
) {
    fun day(date: LocalDate): CalendarDay? = days.firstOrNull { it.day == date.toString() }
}

/** The line under the month: days read, total time, books. */
data class MonthSummary(val daysRead: Int, val totalSeconds: Long, val books: Int)

/**
 * A book of the month's list: on how many days it was read and for how long, and the days this
 * month a reading of it started, was finished (or skimmed) or given up (the latest of each end,
 * the earliest start).
 */
data class MonthBook(
    val book: CalendarBook,
    val days: Int,
    val seconds: Long,
    val started: LocalDate? = null,
    val finished: LocalDate? = null,
    val gaveUp: LocalDate? = null,
)

/** What a mark on a day says: a reading of the book started, was finished (or skimmed), or given up. */
enum class MarkKind { FINISHED, GAVE_UP, STARTED }

/**
 * A book's reading started or ended on a day: from its readings (BookOrbit's reading attempts,
 * History's data), so a reading given dates by hand shows on its days without any session.
 */
@Immutable
data class CalendarMark(val book: CalendarBook, val kind: MarkKind)

/** A day's covers, front first, and the mark on the front one (finished or given up). */
data class DayStack(val books: List<CalendarBook>, val badge: MarkKind?)

/** The yearly goal's pace, as the server words it (activity-overview `goal.status`). */
enum class GoalPace { AHEAD, ON_PACE, BEHIND }

object CalendarMath {

    /** A day's detail as the calendar shows it: its books, most read first. */
    fun dayOf(detail: ActivityDayDetail): CalendarDay {
        val byBook = LinkedHashMap<Long, CalendarBook>()
        for (s in detail.sessions) {
            val seen = byBook[s.bookId]
            byBook[s.bookId] = seen?.copy(seconds = seen.seconds + s.durationOnDaySeconds)
                ?: CalendarBook(s.bookId, s.bookTitle, s.hasCover, s.coverVersion, s.durationOnDaySeconds)
        }
        return CalendarDay(
            day = detail.day,
            totalSeconds = detail.totals?.totalSeconds ?: byBook.values.sumOf { it.seconds },
            sessionsCount = detail.totals?.sessionsCount ?: detail.sessions.size,
            books = byBook.values.sortedByDescending { it.seconds },
        )
    }

    /** The days of [month] with reading, from its year's calendar. */
    fun activeDays(calendar: ActivityCalendar, month: YearMonth): List<ActivityDay> {
        val prefix = "$month-"
        return calendar.days.filter { it.sessionsCount > 0 && it.day.startsWith(prefix) }
    }

    /**
     * The days whose detail must be fetched: new since [cached], changed (time or session count),
     * never loaded (no books), and today (it can still change).
     */
    fun daysToFetch(active: List<ActivityDay>, cached: CalendarMonth?, today: LocalDate): List<String> {
        val old = cached?.days.orEmpty().associateBy { it.day }
        val todayKey = today.toString()
        return active.filter { d ->
            val c = old[d.day]
            c == null || c.books.isEmpty() || c.totalSeconds != d.totalSeconds || c.sessionsCount != d.sessionsCount || d.day == todayKey
        }.map { it.day }
    }

    /**
     * The month after a refresh: every active day, from [fetched], else as [cached] had it, else
     * with its totals and no books yet. A day whose detail failed is fetched on the next refresh:
     * a new one has no books, and a changed one keeps its old totals (its old books under the new
     * totals would look up to date and never be fetched again).
     */
    fun merge(
        month: YearMonth,
        active: List<ActivityDay>,
        cached: CalendarMonth?,
        fetched: Map<String, CalendarDay>,
        availableYears: List<Int>,
        now: Long,
    ): CalendarMonth {
        val old = cached?.days.orEmpty().associateBy { it.day }
        val days = active.map { d -> fetched[d.day] ?: old[d.day] ?: CalendarDay(d.day, d.totalSeconds, d.sessionsCount) }
        return CalendarMonth(month.toString(), days, availableYears, now)
    }

    fun summary(month: CalendarMonth?): MonthSummary {
        val days = month?.days.orEmpty()
        return MonthSummary(
            daysRead = days.count { it.sessionsCount > 0 },
            totalSeconds = days.sumOf { it.totalSeconds },
            books = days.flatMap { it.books }.map { it.bookId }.distinct().size,
        )
    }

    /**
     * The month's books, most read first (the title and cover of the latest day they appear on),
     * then the ones only marked this month ([marks]: started, finished or given up without a
     * session in it), latest first.
     */
    fun books(
        month: CalendarMonth?,
        marks: Map<String, List<CalendarMark>> = emptyMap(),
        of: YearMonth? = month?.let { YearMonth.parse(it.month) },
    ): List<MonthBook> {
        val byBook = LinkedHashMap<Long, MonthBook>()
        for (day in month?.days.orEmpty().sortedByDescending { it.day }) {
            for (b in day.books) {
                val seen = byBook[b.bookId]
                byBook[b.bookId] = seen?.copy(days = seen.days + 1, seconds = seen.seconds + b.seconds) ?: MonthBook(b, 1, b.seconds)
            }
        }
        if (of != null) {
            val prefix = "$of-"
            for ((day, list) in marks.entries.sortedByDescending { it.key }) {
                if (!day.startsWith(prefix)) continue
                val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: continue
                for (m in list) {
                    val seen = byBook[m.book.bookId] ?: MonthBook(m.book, 0, 0)
                    byBook[m.book.bookId] = when (m.kind) {
                        MarkKind.STARTED -> seen.copy(started = date)
                        MarkKind.FINISHED -> seen.copy(finished = seen.finished ?: date)
                        MarkKind.GAVE_UP -> seen.copy(gaveUp = seen.gaveUp ?: date)
                    }
                }
            }
        }
        return byBook.values.sortedWith(
            compareByDescending<MonthBook> { it.seconds }
                .thenByDescending { listOfNotNull(it.finished, it.gaveUp, it.started).maxOrNull() },
        )
    }

    /**
     * Every day's marks, from each book's readings: the day a reading started, and the day it was
     * finished, skimmed (both [MarkKind.FINISHED]) or given up. One mark per book and day, the end
     * over the start; finished first, then given up, then started.
     */
    fun marksOf(books: Collection<CachedBook>): Map<String, List<CalendarMark>> {
        val byDay = HashMap<String, LinkedHashMap<Long, CalendarMark>>()
        fun add(day: LocalDate?, mark: CalendarMark) {
            if (day == null) return
            val marks = byDay.getOrPut(day.toString()) { LinkedHashMap() }
            val seen = marks[mark.book.bookId]
            if (seen == null || mark.kind.ordinal < seen.kind.ordinal) marks[mark.book.bookId] = mark
        }
        for (b in books) {
            val card = b.card
            val cover = CalendarBook(card.id, card.title, card.hasCover, card.updatedAt ?: card.addedAt)
            for (a in b.attempts) {
                add(HistoryMath.date(a.startedOn), CalendarMark(cover, MarkKind.STARTED))
                val outcome = a.outcome ?: continue
                val kind = if (outcome == AttemptOutcome.ABANDONED) MarkKind.GAVE_UP else MarkKind.FINISHED
                add(HistoryMath.date(a.endedOn), CalendarMark(cover, kind))
            }
        }
        return byDay.mapValues { (_, marks) ->
            marks.values.sortedWith(compareBy<CalendarMark> { it.kind.ordinal }.thenBy { it.book.title.orEmpty() })
        }
    }

    /**
     * A day's covers: the books finished or given up that day first (the front one carries the
     * mark), then the books read that day, most read first, then the books started that day
     * without reading.
     */
    fun stack(day: CalendarDay?, marks: List<CalendarMark>): DayStack {
        val ended = marks.filter { it.kind != MarkKind.STARTED }
        val read = day?.books.orEmpty()
        val seen = HashSet<Long>()
        val books = buildList {
            ended.forEach { if (seen.add(it.book.bookId)) add(read.firstOrNull { r -> r.bookId == it.book.bookId } ?: it.book) }
            read.forEach { if (seen.add(it.bookId)) add(it) }
            marks.filter { it.kind == MarkKind.STARTED }.forEach { if (seen.add(it.book.bookId)) add(it.book) }
        }
        return DayStack(books, ended.firstOrNull()?.kind)
    }

    /** The weeks of [month], Monday first; each has 7 dates, null outside the month. */
    fun weeks(month: YearMonth): List<List<LocalDate?>> {
        val lead = month.atDay(1).dayOfWeek.value - 1
        val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        return cells.chunked(7).map { it + List(7 - it.size) { null } }
    }

    /** The server's rule (at +/- half a book against a straight line from 1 January). */
    fun goalPace(completed: Int, goal: Int, today: LocalDate): GoalPace {
        val delta = completed - goal.toDouble() * today.dayOfYear / today.lengthOfYear()
        return when {
            delta >= 0.5 -> GoalPace.AHEAD
            delta <= -0.5 -> GoalPace.BEHIND
            else -> GoalPace.ON_PACE
        }
    }

    /** Books finished by the end of the year at the current rate, as the server rounds it. */
    fun projected(completed: Int, today: LocalDate): Double =
        (completed.toDouble() / today.dayOfYear * today.lengthOfYear() * 10).roundToInt() / 10.0

    /** How many books the goal wants by [today] (whole books, rounded down). */
    fun expectedByNow(goal: Int, today: LocalDate): Int = (goal.toDouble() * today.dayOfYear / today.lengthOfYear()).toInt()
}

/** The zone the server splits days in: users.settings.timezone, else UTC (as the server falls back). */
internal fun serverZone(user: AuthUser?): ZoneId =
    user?.settings?.timezone?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
