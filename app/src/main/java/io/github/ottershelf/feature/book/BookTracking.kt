package io.github.ottershelf.feature.book

import androidx.annotation.StringRes
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.BookSession
import io.github.ottershelf.core.tracking.BookSessionStats
import io.github.ottershelf.core.tracking.CelebrationClaim
import io.github.ottershelf.core.tracking.PageMath
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.core.util.IsoTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The tracking part of a book's page (Bookmory's current-book card, the book timeline, rating and
 * review): what [BookTracker] loaded. Everything shown is derived with [progressOf] and
 * [timelineOf], so the screenshot tests build it by hand.
 *
 * @property sessions the sessions loaded so far, newest first ([sessionsTotal] on the server).
 * @property stats the sessions' stats (page 1's; they cover every session).
 * @property attempts the book's readings, as the server lists them.
 * @property fileProgress the ebook's own position (0..100), if any.
 * @property rating / [note] what shows (optimistic while a write is on its way).
 * @property timerHere a reading timer is running for this book.
 * @property celebration the finish flow's current step, or null.
 */
data class BookTrackingUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val sessions: List<BookSession> = emptyList(),
    val sessionsTotal: Int = 0,
    val sessionsPage: Int = 0,
    val loadingMore: Boolean = false,
    val stats: BookSessionStats = BookSessionStats(),
    val attempts: List<ReadingAttempt> = emptyList(),
    val fileProgress: Double? = null,
    val settings: AppSettings = AppSettings(),
    val canRate: Boolean = false,
    /** The book's rating field is metadata-locked: the server would skip a new rating. */
    val ratingLocked: Boolean = false,
    val rating: Int? = null,
    val note: String? = null,
    val busy: Boolean = false,
    val timerHere: Boolean = false,
    val celebration: CelebrationStep? = null,
    /** The last page of sessions came back short: there are no more (even if the total moved). */
    val endReached: Boolean = false,
    /** The zone the server splits days in (users.settings.timezone, else UTC): Day N and the log's days. */
    val zone: ZoneId = ZoneOffset.UTC,
) {
    val allSessionsLoaded: Boolean get() = endReached || sessions.size >= sessionsTotal
}

/** The finish flow, one full-screen step at a time. */
sealed interface CelebrationStep {
    /** Confetti, the cover and the message; [days] the reading took, if its start is known. */
    data class Done(val days: Int?) : CelebrationStep

    /** Rate it (with the permission) and write the private review. */
    data object Rate : CelebrationStep

    /** A server achievement just earned. */
    data class Achievement(val claim: CelebrationClaim) : CelebrationStep
}

/** A one-off message for the page's snackbar: [text] with an optional [arg]. */
data class TrackingMessage(@param:StringRes val text: Int, val arg: String? = null)

/** What the progress card shows. */
data class ProgressSummary(
    /** Being read now: reading, rereading or on hold. */
    val active: Boolean,
    val finished: Boolean,
    /** "Day N" of the current reading, when active and its start is known. */
    val dayNumber: Int?,
    val startedOn: LocalDate?,
    val finishedOn: LocalDate?,
    /** The page total of the user's edition (else the server's), and whether it is the user's own number. */
    val total: Int?,
    val totalIsOwn: Boolean,
    val serverPageCount: Int?,
    val currentPage: Int?,
    /** 0..100, what the bar shows (the hand-set page's when that wins). */
    val percent: Double?,
    /** A hand-set page is showing (no session since). */
    val pageSetByHand: Boolean,
    val readingNumber: Int,
    /** The reading speed, once there is enough reading behind it ([trustedPace]); else null. */
    val pace: PageMath.Pace?,
    /** Reading time left to the end at [pace], while being read; null without a trusted pace. */
    val timeLeftSeconds: Long?,
)

/**
 * The least reading a speed and a time left are quoted from: [MIN_PACE_SECONDS] (30 minutes) of
 * the reading time that gained progress, across at least [MIN_PACE_SESSIONS] (3) sessions. Below
 * that the ratio is noise: seven short sessions totalling nine minutes came out at 350%/h and "14
 * min left". (The web waits for 10 minutes; a phone's many short sessions need more.)
 */
const val MIN_PACE_SECONDS = 30 * 60L
const val MIN_PACE_SESSIONS = 3

/** [PageMath.pace] from [stats], or null until the reading passes the floor above. */
fun trustedPace(stats: BookSessionStats, total: Int?): PageMath.Pace? {
    if (stats.paceDurationSeconds < MIN_PACE_SECONDS || stats.totalSessions < MIN_PACE_SESSIONS) return null
    return PageMath.pace(stats, total)
}

private val ACTIVE = setOf(ReadStatus.READING.value, ReadStatus.REREADING.value, ReadStatus.ON_HOLD.value)

/** Works out the progress card for [book] with [status] (the page's, optimistic) on [today]. */
fun progressOf(book: BookDetail, status: String?, tracking: BookTrackingUiState, today: LocalDate): ProgressSummary {
    val settings = tracking.settings
    val active = status in ACTIVE
    val finished = status == ReadStatus.READ.value
    val total = PageMath.pageTotal(book, settings)
    val override = settings.pageOverrides[book.id]
    val lastEnd = tracking.sessions.firstOrNull()?.let(PageMath::endMs)
    val percent = PageMath.currentPercent(tracking.stats.latestEndProgress, tracking.fileProgress)
    val byHand = override != null && (lastEnd == null || override.at >= lastEnd)
    val page = PageMath.currentPage(total, percent, override, lastEnd)
    val shownPercent = if (byHand && total != null && page != null) PageMath.pageToPercent(page, total) else percent
    val pace = trustedPace(tracking.stats, total)
    val startedAt = book.readStatus?.startedAt
    return ProgressSummary(
        active = active,
        finished = finished,
        dayNumber = if (active) PageMath.dayNumber(startedAt, today) else null,
        startedOn = PageMath.dateOf(startedAt),
        finishedOn = if (finished) PageMath.dateOf(book.readStatus?.finishedAt) else null,
        total = total,
        totalIsOwn = settings.pageTotals[book.id]?.let { it > 0 } == true,
        serverPageCount = book.pageCount?.takeIf { it > 0 },
        currentPage = page,
        percent = if (finished) 100.0 else shownPercent,
        pageSetByHand = byHand,
        readingNumber = PageMath.readingNumber(tracking.attempts, active),
        pace = pace,
        timeLeftSeconds = if (active) PageMath.timeLeftSeconds(shownPercent, pace) else null,
    )
}

/** One line of the reading log. */
sealed interface TimelineItem {
    val date: LocalDate
    val key: String

    data class Session(
        val session: BookSession,
        override val date: LocalDate,
        val start: LocalDateTime,
        /** Pages gained (from progressDelta and the page total); null when unknown. */
        val pages: Int?,
        /** The page it ended on; null when unknown. */
        val endPage: Int?,
    ) : TimelineItem {
        override val key get() = "s${session.id}"
    }

    data class Started(val attempt: ReadingAttempt, override val date: LocalDate) : TimelineItem {
        override val key get() = "a${attempt.id}s"
    }

    /** A reading that ended: [outcome] completed, skimmed or abandoned. */
    data class Ended(val attempt: ReadingAttempt, override val date: LocalDate, val outcome: String) : TimelineItem {
        override val key get() = "a${attempt.id}e"
    }
}

/**
 * The reading log: sessions and readings (their start and end) merged by date, newest first. On a
 * day, a reading's end sits above that day's sessions and its start below them. While older
 * sessions are still to load, readings older than the oldest loaded session wait for them. Days
 * (and times) are in [zone], the server's by default, so a session sits on the same day as in the
 * Calendar and the Day screen.
 */
fun timelineOf(tracking: BookTrackingUiState, total: Int?, zone: ZoneId = tracking.zone): List<TimelineItem> {
    val sessions = tracking.sessions.mapNotNull { s ->
        val ms = IsoTime.parse(s.startedAt) ?: return@mapNotNull null
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        TimelineItem.Session(
            session = s,
            date = start.toLocalDate(),
            start = start,
            pages = PageMath.pagesOf(s.progressDelta, total),
            endPage = if (total != null && total > 0) s.endProgress?.let { PageMath.percentToPage(it, total) } else null,
        )
    }
    val oldest = sessions.minOfOrNull { it.start }
    val complete = tracking.allSessionsLoaded
    val events = buildList {
        for (a in tracking.attempts) {
            PageMath.dateOf(a.startedOn)?.let { add(TimelineItem.Started(a, it)) }
            val outcome = a.outcome
            if (outcome != null) PageMath.dateOf(a.endedOn)?.let { add(TimelineItem.Ended(a, it, outcome)) }
        }
    }.filter { e ->
        if (complete) return@filter true
        val cut = oldest?.toLocalDate() ?: return@filter false
        when (e) {
            is TimelineItem.Ended -> !e.date.isBefore(cut)
            else -> e.date.isAfter(cut)
        }
    }
    // Sort key: the day, then start < sessions < end, then the time of day.
    fun rank(item: TimelineItem) = when (item) {
        is TimelineItem.Started -> 0
        is TimelineItem.Session -> 1
        is TimelineItem.Ended -> 2
    }
    return (sessions + events).sortedWith(
        compareByDescending<TimelineItem> { it.date }
            .thenByDescending { rank(it) }
            .thenByDescending { (it as? TimelineItem.Session)?.start },
    )
}

/** How a reading ended, as the log and the past-read dialog name it. */
@StringRes
fun outcomeLabel(outcome: String): Int = when (outcome) {
    AttemptOutcome.COMPLETED -> R.string.book_log_finished
    AttemptOutcome.SKIMMED -> R.string.book_log_skimmed
    else -> R.string.book_log_abandoned
}
