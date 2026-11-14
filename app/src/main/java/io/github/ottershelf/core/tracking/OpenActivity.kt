package io.github.ottershelf.core.tracking

import io.github.ottershelf.core.model.AuthUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The activity views (a calendar year, a day, the overview) built from routes every client may call.
 *
 * BookOrbit 3.7 reserved `user-statistics/activity-overview`, `activity-calendar/:year` and
 * `activity-days/:day` for its own iPhone and Android apps: App Attest, a 403 `APP_ATTEST_REQUIRED`
 * for any other app, deliberately not configurable. Those routes were made for its apps; the web
 * never used them. The same numbers come from what the web reads: `daily-reading` (the per-day table
 * those routes total, in users.settings.timezone, written with each session), `session-timeline` (the
 * sessions themselves), `completion-timeline` and the dashboard's reading-streak widget. The models
 * stay the ones the screens already read; only where the data comes from changed. Pure, so the sums
 * are tested.
 */
@Serializable
data class DailyReadingRow(val day: String, val readingSeconds: Double = 0.0, val eventsCount: Double = 0.0)

/** A month of `user-statistics/completion-timeline`: books finished in it. */
@Serializable
data class CompletionMonth(val year: Int, val month: Int, val count: Double = 0.0)

object OpenActivity {

    /** daily-reading's longest window (UserDailyReadingQueryDto: 1..3650 days). */
    const val MAX_DAYS = 3650

    /** The server's overview windows: a year for the rhythm, 84 days for the daily chart. */
    const val YEAR_DAYS = 365
    const val DAILY_DAYS = 84

    /** The zone the server splits reading days in: users.settings.timezone, else UTC (as it does). */
    fun zone(user: AuthUser?): ZoneId =
        user?.settings?.timezone?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

    /** dashboardConfig.readingGoal (books a year), as the server reads it; null when unset. */
    fun goalBooks(user: AuthUser?): Int? =
        (user?.settings?.dashboardConfig?.get("readingGoal") as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() && it > 0 }?.roundToInt()

    /** One day from its daily-reading row (null: nothing read). A day with reading has a session at least. */
    fun point(day: String, row: DailyReadingRow?): ActivityDay {
        val seconds = row?.readingSeconds?.roundToLong()?.coerceAtLeast(0) ?: 0
        val sessions = row?.eventsCount?.roundToInt()?.coerceAtLeast(0) ?: 0
        return ActivityDay(day, totalSeconds = seconds, readingSeconds = seconds, sessionsCount = if (seconds > 0) max(sessions, 1) else sessions)
    }

    /** Years with reading in [rows], and [today]'s, oldest first (as activity-calendar lists them). */
    fun availableYears(rows: List<DailyReadingRow>, today: LocalDate): List<Int> =
        (rows.filter { it.readingSeconds > 0 }.mapNotNull { it.day.take(4).toIntOrNull() } + today.year).distinct().sorted()

    /** Every day of [year], zero-filled; no days for a year before the first reading or after [today] (the server's 400). */
    fun calendar(year: Int, rows: List<DailyReadingRow>, today: LocalDate): ActivityCalendar {
        val years = availableYears(rows, today)
        if (year < years.first() || year > today.year) return ActivityCalendar(year = year, availableYears = years)
        return ActivityCalendar(year, years, days(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), rows))
    }

    /** Each day from [from] to [to], zero-filled from [rows]. */
    fun days(from: LocalDate, to: LocalDate, rows: List<DailyReadingRow>): List<ActivityDay> {
        val byDay = rows.associateBy { it.day }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map { point(it.toString(), byDay[it.toString()]) }.toList()
    }

    private fun sum(days: List<ActivityDay>): ActivityDuration {
        val total = days.sumOf { it.totalSeconds }
        return ActivityDuration(totalSeconds = total, readingSeconds = total)
    }

    /**
     * The overview's snapshot: [today], the 7 days to it and the 7 before (as the server sums them),
     * the streaks from the dashboard's reading-streak widget (null: unknown, left at 0) and the books
     * finished this year.
     */
    fun snapshot(rows: List<DailyReadingRow>, today: LocalDate, currentStreak: Int?, longestStreak: Int?, completedYtd: Int): ActivitySnapshot {
        val last14 = days(today.minusDays(13), today, rows)
        return ActivitySnapshot(
            today = sum(last14.takeLast(1)),
            lastSevenDays = sum(last14.takeLast(7)),
            previousSevenDays = sum(last14.take(7)),
            currentStreak = currentStreak ?: 0,
            longestStreak = longestStreak ?: 0,
            completedBooksYtd = completedYtd,
        )
    }

    /** Books finished in each month of [year] (index 1..12). */
    fun finishedPerMonth(months: List<CompletionMonth>, year: Int): IntArray {
        val perMonth = IntArray(13)
        months.forEach { if (it.year == year && it.month in 1..12) perMonth[it.month] += it.count.roundToInt() }
        return perMonth
    }

    /** daily-reading's days reaching back to [from], today included (1..[MAX_DAYS]). */
    fun daysBack(from: LocalDate, today: LocalDate): Int =
        (ChronoUnit.DAYS.between(from, today) + 1).coerceIn(1, MAX_DAYS.toLong()).toInt()

    /** The year's goal as the server's overview works it out (ahead or behind at half a book). */
    fun goal(year: Int, goalBooks: Int?, perMonth: IntArray, today: LocalDate): ActivityGoal {
        var running = 0
        val points = (1..12).map { m ->
            running += perMonth[m]
            GoalPoint(m, running.toDouble(), goalBooks?.let { (it * m / 12.0 * 100).roundToInt() / 100.0 })
        }
        val completed = perMonth.sum()
        val daysInYear = LocalDate.of(year, 1, 1).lengthOfYear()
        val dayOfYear = when {
            today.year == year -> today.dayOfYear
            today.year > year -> daysInYear
            else -> 0
        }
        val projected = if (dayOfYear > 0) (completed.toDouble() / dayOfYear * daysInYear * 10).roundToInt() / 10.0 else 0.0
        val status = goalBooks?.let {
            val delta = completed - it.toDouble() * dayOfYear / daysInYear
            if (delta >= 0.5) "ahead" else if (delta <= -0.5) "behind" else "on_pace"
        }
        return ActivityGoal(year, goalBooks, completed, projected, status, points)
    }

    /** The ISO weeks (in UTC, as session-timeline counts them) that [day] in [zone] reaches into. */
    fun weeksOf(day: LocalDate, zone: ZoneId): List<Pair<Int, Int>> {
        val start = day.atStartOfDay(zone).toInstant()
        val last = day.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)
        return listOf(start, last).map { instant ->
            val utc = instant.atZone(ZoneOffset.UTC).toLocalDate()
            utc.get(IsoFields.WEEK_BASED_YEAR) to utc.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        }.distinct()
    }

    /**
     * [day]'s sessions from session-timeline weeks: each one's share of the day in proportion to its
     * clock time on it (the server's splitReadingSessionByDay), sorted by start. The timeline has no
     * cover version or progress per session: covers use the book's current thumbnail, and the session's
     * percentage is left out.
     */
    fun day(day: LocalDate, zone: ZoneId, sessions: List<TimelineSession>): ActivityDayDetail {
        val from = day.atStartOfDay(zone).toInstant()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant()
        val onDay = sessions.distinctBy { it.sessionId }.mapNotNull { s ->
            val start = instant(s.startedAt) ?: return@mapNotNull null
            val end = instant(s.endedAt)?.takeIf { it.isAfter(start) } ?: start
            val seconds = secondsOnDay(start, end, s.durationSeconds.toLong(), from, to)
            if (seconds == null) return@mapNotNull null
            DaySession(
                id = s.sessionId,
                bookId = s.bookId,
                bookTitle = s.bookTitle,
                hasCover = true,
                format = s.bookFormat,
                mediaBucket = "reading",
                sourceBucket = s.bookSource,
                startedAt = s.startedAt,
                endedAt = s.endedAt,
                durationOnDaySeconds = seconds,
            )
        }.sortedBy { it.startedAt }
        val total = onDay.sumOf { it.durationOnDaySeconds }
        val totals = ActivityDay(day.toString(), totalSeconds = total, readingSeconds = total, sessionsCount = onDay.size)
        return ActivityDayDetail(day.toString(), zone.id, totals, onDay)
    }

    /** A session's seconds on the day [from]..[to] (null: not on it); its whole duration if it has no length on the clock. */
    internal fun secondsOnDay(start: Instant, end: Instant, duration: Long, from: Instant, to: Instant): Long? {
        if (duration <= 0) return null
        if (!end.isAfter(start)) return if (!start.isBefore(from) && start.isBefore(to)) duration else null
        val overlapStart = maxOf(start, from)
        val overlapEnd = minOf(end, to)
        if (!overlapEnd.isAfter(overlapStart)) return null
        val wall = ChronoUnit.MILLIS.between(start, end).toDouble()
        return (duration * ChronoUnit.MILLIS.between(overlapStart, overlapEnd) / wall).roundToLong()
    }

    private fun instant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
}
