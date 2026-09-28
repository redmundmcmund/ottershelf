package io.github.ottershelf.core.tracking

import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.settings.PageOverride
import io.github.ottershelf.core.util.IsoTime
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Pages, days and pace for the tracker. The server keeps progress as a percentage only, so pages
 * are worked out from the page total of the user's edition ([AppSettings.pageTotal]: the user's own number, else
 * `BookDetail.pageCount`). Pure; tested in PageMathTest.
 */
object PageMath {

    /** The page total for [book]: that of the user's edition if set, else the server's page count; null if neither. */
    fun pageTotal(book: BookDetail, settings: AppSettings): Int? = settings.pageTotal(book.id, book.pageCount)

    /** The page [percent] (0..100) of [total] lands on, rounded (so a page converted to percent comes back the same). */
    fun percentToPage(percent: Double, total: Int): Int =
        if (total <= 0) 0 else (percent.coerceIn(0.0, 100.0) / 100.0 * total).roundToInt().coerceIn(0, total)

    /** [page] of [total] as a percentage (0..100), rounded to 2 decimals as it is sent. */
    fun pageToPercent(page: Int, total: Int): Double =
        if (total <= 0) 0.0 else ((page.coerceIn(0, total).toDouble() / total * 100.0) * 100.0).roundToLong() / 100.0

    /**
     * The best known progress (0..100): the highest of the sessions' latest endProgress
     * (`BookSessionStats.latestEndProgress`, the only progress a paper reading leaves) and the
     * ebook's own position (`BookCard.readingProgress` / file progress). Null if none is known.
     */
    fun currentPercent(latestEndProgress: Double?, fileProgress: Double? = null): Double? =
        listOfNotNull(latestEndProgress, fileProgress).maxOrNull()?.coerceIn(0.0, 100.0)

    /** A known position (0..100) and when it was recorded (epoch ms; null when unknown). */
    data class Position(val percent: Double, val atMs: Long?)

    /**
     * Where a new timed session starts, for its progress gained: the most recent of [positions] (a
     * paper session's end, the ebook's own position), not the higher one as [currentPercent] shows
     * it; the web does the same for display, but a start from an older, further position would
     * send a large negative progressDelta. Without any time known, the higher one.
     *
     * [readingSinceMs]: the start of a reread's first day. Positions from before it belong to an
     * earlier reading and are ignored; with none after it, the reread starts at 0.
     */
    fun startPercent(positions: List<Position>, readingSinceMs: Long? = null): Double? {
        if (readingSinceMs != null) {
            val current = positions.filter { it.atMs != null && it.atMs >= readingSinceMs }
            return current.maxByOrNull { it.atMs ?: 0 }?.percent?.coerceIn(0.0, 100.0) ?: 0.0
        }
        val timed = positions.filter { it.atMs != null }
        val pick = timed.maxByOrNull { it.atMs ?: 0 } ?: positions.maxByOrNull { it.percent }
        return pick?.percent?.coerceIn(0.0, 100.0)
    }

    /**
     * The current page: a hand-set page ([override]) until a session ends after it was set
     * ([lastSessionEndMs]), else [percent] converted with [total]. Null without a total or any
     * known position.
     */
    fun currentPage(total: Int?, percent: Double?, override: PageOverride?, lastSessionEndMs: Long?): Int? {
        if (override != null && (lastSessionEndMs == null || override.at >= lastSessionEndMs)) {
            return if (total != null && total > 0) override.page.coerceIn(0, total) else override.page.coerceAtLeast(0)
        }
        if (total == null || total <= 0 || percent == null) return null
        return percentToPage(percent, total)
    }

    /**
     * A status date as a calendar date: `YYYY-MM-DD`, or an ISO timestamp whose date part is taken
     * as it is (the server sends UTC midnight of the date; shifting it to local time would move it
     * a day back west of UTC).
     */
    fun dateOf(value: String?): LocalDate? {
        if (value.isNullOrBlank() || value.length < 10) return null
        return try {
            LocalDate.parse(value.substring(0, 10))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** "Day N" of the current reading: 1 on the day it started. Null without a start date. */
    fun dayNumber(startedAt: String?, today: LocalDate): Int? {
        val start = dateOf(startedAt) ?: return null
        return (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceAtLeast(1)
    }

    /**
     * Which reading this is (1 for the first): the completed readings, plus the open one if the
     * book is being read now ([reading]: status reading, rereading or on hold). At least 1.
     */
    fun readingNumber(attempts: List<ReadingAttempt>, reading: Boolean): Int {
        val completed = attempts.count { it.outcome == AttemptOutcome.COMPLETED }
        return (completed + if (reading) 1 else 0).coerceAtLeast(1)
    }

    /** Reading speed, from the sessions that moved forward. */
    data class Pace(val percentPerHour: Double, val pagesPerHour: Double?)

    /**
     * The pace from `BookSessionStats` (progress gained over the reading time of the sessions that
     * gained it), in pages too when [total] is known. Null before any such session.
     */
    fun pace(stats: BookSessionStats, total: Int?): Pace? {
        if (stats.paceDurationSeconds <= 0 || stats.paceProgressDelta <= 0.0) return null
        val perHour = stats.paceProgressDelta / (stats.paceDurationSeconds / 3600.0)
        return Pace(perHour, total?.takeIf { it > 0 }?.let { perHour / 100.0 * it })
    }

    /** Reading time left to 100% from [percent] at [pace], in seconds; null without a pace. */
    fun timeLeftSeconds(percent: Double?, pace: Pace?): Long? {
        if (pace == null || pace.percentPerHour <= 0.0) return null
        val left = (100.0 - (percent ?: 0.0)).coerceAtLeast(0.0)
        return (left / pace.percentPerHour * 3600.0).roundToLong()
    }

    /** Pages read in a session from its progress delta (percentage points); null without a total. */
    fun pagesOf(progressDelta: Double?, total: Int?): Int? {
        if (progressDelta == null || total == null || total <= 0) return null
        return (progressDelta / 100.0 * total).roundToInt()
    }

    /** When [session] ended, in epoch milliseconds (for [currentPage]). */
    fun endMs(session: BookSession): Long? = IsoTime.parse(session.endedAt)

    /**
     * The file a timed session is recorded against: the primary file, else the first. Null for a
     * book with no file (its sessions then go through the manual route).
     */
    fun trackingFile(files: List<BookFile>): BookFile? = files.firstOrNull { it.role == "primary" } ?: files.firstOrNull()
}
