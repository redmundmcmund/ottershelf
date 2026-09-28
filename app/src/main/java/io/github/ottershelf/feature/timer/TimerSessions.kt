package io.github.ottershelf.feature.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.settings.ProgressUnit
import io.github.ottershelf.core.tracking.FinishedTimer
import io.github.ottershelf.core.tracking.PageMath
import io.github.ottershelf.core.tracking.SessionQuery
import io.github.ottershelf.core.tracking.SessionSaveResult
import io.github.ottershelf.core.util.IsoTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Summaries just saved, by session id: the timer screen saves, then opens the result screen,
 * which takes its summary from here (and keeps it in its saved state).
 */
internal object TimerResults {
    private val saved = ConcurrentHashMap<String, SessionSummary>()

    fun put(summary: SessionSummary) {
        saved[summary.sessionId] = summary
    }

    fun take(sessionId: String): SessionSummary? = saved.remove(sessionId)
}

/** How saving a timer's session went. */
internal sealed interface SaveResult {
    data class Saved(val summary: SessionSummary) : SaveResult

    /** Under 10 s: nothing was kept. */
    data object TooShort : SaveResult

    /** This timer is already being saved (or was): nothing more to do here. */
    data object AlreadySaved : SaveResult
}

/**
 * The timer screens' data work, shared by the timer, its result screen and the reader's notice:
 * the book's pages, and saving a finished timer (TrackingRepository.logTimedSession, then
 * TimerEngine.clearFinished; the timer is only forgotten once its session is queued or sent).
 */
internal class TimerSessions(private val container: AppContainer) {

    /**
     * The book, its page total and where it stands: shown as the higher of the sessions' latest
     * end and the ebook's position (as the web shows it); a session started now begins at the more
     * recent of the two (PageMath.startPercent).
     */
    suspend fun loadBook(bookId: Long): TimerBook = coroutineScope {
        val detail = container.api.book(bookId)
        val file = PageMath.trackingFile(detail.files)
        val sessions = async { attempt { container.tracking.sessions(bookId, SessionQuery(pageSize = POSITION_LOOKBACK)) } }
        val progress = async { file?.let { f -> attempt { container.api.fileProgress(f.id) } } }
        val settings = container.appSettings.settings.value
        val total = PageMath.pageTotal(detail, settings)
        val list = sessions.await()
        val fileProgress = progress.await()
        val percent = PageMath.currentPercent(list?.stats?.latestEndProgress, fileProgress?.percentage)
        val lastEnd = list?.items?.firstOrNull()?.let(PageMath::endMs)
        val override = settings.pageOverrides[bookId]

        val positions = buildList {
            val ended = list?.items?.firstOrNull { it.endProgress != null }
            val latest = list?.stats?.latestEndProgress
            if (ended?.endProgress != null) {
                add(PageMath.Position(ended.endProgress, PageMath.endMs(ended)))
            } else if (latest != null) {
                add(PageMath.Position(latest, null)) // older than the sessions looked at
            }
            fileProgress?.percentage?.let { add(PageMath.Position(it, IsoTime.parse(fileProgress.lastReadAt ?: fileProgress.updatedAt))) }
        }
        // A reread (picking Re-reading opens a new reading dated today) starts afresh.
        val readingSince = detail.readStatus?.takeIf { it.status == ReadStatus.REREADING.value }
            ?.let { PageMath.dateOf(it.startedAt) }?.atStartOfDay(zone())?.toInstant()?.toEpochMilli()
        val startPercent = PageMath.startPercent(positions, readingSince)
        val sinceEnd = listOfNotNull(lastEnd, readingSince).maxOrNull()
        TimerBook(
            bookId = bookId,
            title = detail.title,
            authors = detail.authors.joinToString(", ") { it.name },
            cover = detail.coverSource?.let { runCatching { container.api.thumbnailUrl(detail.id, detail.updatedAt) }.getOrNull() },
            fileId = file?.id,
            pageTotal = total,
            serverPageCount = detail.pageCount?.takeIf { it > 0 },
            percent = percent,
            page = PageMath.currentPage(total, percent, override, lastEnd),
            unit = settings.progressUnits[bookId] ?: ProgressUnit.PAGE,
            startPercent = startPercent,
            startPage = PageMath.currentPage(total, startPercent, override, sinceEnd),
        )
    }

    /** The save form for [activeMs] of reading that started at [startProgress]. */
    fun form(book: TimerBook?, activeMs: Long, startProgress: Double?, title: String?): SaveForm {
        val total = book?.pageTotal
        val startPercent = startProgress ?: book?.startProgress
        val startPage = if (total != null && startPercent != null) PageMath.percentToPage(startPercent, total) else book?.startPage
        return SaveForm(activeMs, title ?: book?.title, startPage, total, startPercent, book?.unit ?: ProgressUnit.PAGE)
    }

    /**
     * Saves [finished] with where the user got to ([entry], in [unit]); remembers an edited page total
     * and the unit for the book. The timer is cleared once the session is queued or sent (or the
     * server refused it: sending it again wouldn't help).
     */
    suspend fun save(finished: FinishedTimer, book: TimerBook?, form: SaveForm, entry: ProgressEntry, unit: ProgressUnit): SaveResult {
        val bookId = finished.bookId
        if (finished.tooShort) {
            container.timer.clearFinished(finished.sessionId)
            return SaveResult.TooShort
        }
        // The timer screen and the result screen (opened from the notification) can both hold it.
        if (!Saving.claim(finished.sessionId)) return SaveResult.AlreadySaved
        return try {
            saveClaimed(finished, book, form, entry, unit)
        } catch (e: Throwable) {
            Saving.release(finished.sessionId) // not stored: the user can try again
            throw e
        }
    }

    private suspend fun saveClaimed(finished: FinishedTimer, book: TimerBook?, form: SaveForm, entry: ProgressEntry, unit: ProgressUnit): SaveResult {
        val bookId = finished.bookId
        rememberChoices(bookId, book, entry, unit)

        val total = (entry as? ProgressEntry.Pages)?.total ?: form.total
        val startPage = form.startPage
        val startPercent = when {
            entry is ProgressEntry.Pages && startPage != null -> PageMath.pageToPercent(startPage, entry.total)
            else -> form.startPercent
        }
        val endPercent = when (entry) {
            is ProgressEntry.Pages -> PageMath.pageToPercent(entry.page, entry.total)
            is ProgressEntry.Percent -> entry.percent
            ProgressEntry.None -> null
        }
        val endPage = when (entry) {
            is ProgressEntry.Pages -> entry.page
            is ProgressEntry.Percent -> total?.let { PageMath.percentToPage(entry.percent, it) }
            ProgressEntry.None -> null
        }
        val result = container.tracking.logTimedSession(
            bookId = bookId,
            fileId = fileOf(finished, book),
            sessionId = finished.sessionId,
            startedAtMs = finished.startedAtMs,
            endedAtMs = finished.endedAtMs,
            activeSeconds = finished.activeSeconds,
            startProgress = startPercent,
            endProgress = endPercent,
            spans = finished.spans,
            // Forgotten once it's stored on the device, before the send (which can take a while).
            stored = { container.timer.clearFinished(finished.sessionId) },
        )
        container.timer.clearFinished(finished.sessionId)
        val outcome = when (result) {
            SessionSaveResult.Sent -> SaveOutcome.Sent
            SessionSaveResult.Queued -> SaveOutcome.Queued
            is SessionSaveResult.Rejected -> SaveOutcome.Rejected
            SessionSaveResult.TooShort -> return SaveResult.TooShort
        }

        // The book's speed and today's time, from the server now that it has the session.
        val extras = if (outcome == SaveOutcome.Sent) {
            withTimeoutOrNull(EXTRAS_TIMEOUT_MS) {
                coroutineScope {
                    val stats = async { attempt { container.tracking.sessions(bookId, SessionQuery(pageSize = 1)).stats } }
                    val today = async { attempt { container.tracking.activityDay(today()).totals?.totalSeconds ?: 0L } }
                    stats.await() to today.await()
                }
            }
        } else {
            null
        }
        val pace = extras?.first?.let { PageMath.pace(it, total) }
        val summary = SessionSummary(
            bookId = bookId,
            sessionId = finished.sessionId,
            title = finished.title.ifBlank { null } ?: book?.title,
            authors = book?.authors.orEmpty(),
            activeSeconds = finished.activeSeconds,
            outcome = outcome,
            message = (result as? SessionSaveResult.Rejected)?.message,
            total = total,
            startPage = if (endPage != null) startPage else null,
            endPage = endPage,
            startPercent = startPercent,
            endPercent = endPercent,
            bookPagesPerHour = pace?.pagesPerHour,
            bookPercentPerHour = pace?.percentPerHour,
            todaySeconds = extras?.second,
            dailyGoalMinutes = container.appSettings.settings.value.dailyGoalMinutes,
        )
        return SaveResult.Saved(summary)
    }

    /**
     * Stops [bookId]'s timer at [atMs] (when the reader opened: it records its own session from
     * then on) and saves what it timed without a page. Nothing happens if the timer isn't this
     * book's.
     */
    suspend fun stopWithoutPage(bookId: Long, atMs: Long) {
        if (container.timer.state.value.active?.bookId != bookId) return
        val finished = container.timer.stop(atMs = atMs, announce = false) ?: return
        if (!finished.tooShort && Saving.claim(finished.sessionId)) {
            container.tracking.logTimedSession(
                bookId = bookId,
                fileId = fileOf(finished, null),
                sessionId = finished.sessionId,
                startedAtMs = finished.startedAtMs,
                endedAtMs = finished.endedAtMs,
                activeSeconds = finished.activeSeconds,
                startProgress = finished.startProgress,
                endProgress = null,
                spans = finished.spans,
                stored = { container.timer.clearFinished(finished.sessionId) },
            )
        }
        container.timer.clearFinished(finished.sessionId)
    }

    /**
     * The file the session is recorded against: the timer's, else (it started before its book
     * loaded) the book's as loaded here, else looked up now. Null: a book without files.
     */
    private suspend fun fileOf(finished: FinishedTimer, book: TimerBook?): Long? = when {
        finished.fileId != null -> finished.fileId
        book != null -> book.fileId
        else -> container.tracking.trackingFileId(finished.bookId)
    }

    /** An edited page total (dropped again when it equals the server's) and the unit the user used. */
    private fun rememberChoices(bookId: Long, book: TimerBook?, entry: ProgressEntry, unit: ProgressUnit) {
        val settings = container.appSettings.settings.value
        val newTotal = (entry as? ProgressEntry.Pages)?.total
        val totalChanged = newTotal != null && newTotal != settings.pageTotal(bookId, book?.serverPageCount)
        val unitChanged = unit != (settings.progressUnits[bookId] ?: ProgressUnit.PAGE)
        if (!totalChanged && !unitChanged) return
        container.appSettings.update { s ->
            var next = s
            if (totalChanged && newTotal != null) {
                next = next.copy(
                    pageTotals = if (newTotal == book?.serverPageCount) next.pageTotals - bookId else next.pageTotals + (bookId to newTotal),
                )
            }
            if (unitChanged) {
                next = next.copy(progressUnits = if (unit == ProgressUnit.PAGE) next.progressUnits - bookId else next.progressUnits + (bookId to unit))
            }
            next
        }
    }

    /** Today in the zone the server splits days in (users.settings.timezone, else UTC). */
    private fun today(): LocalDate = LocalDate.now(zone())

    private fun zone(): ZoneId = container.auth.user.value?.settings?.timezone
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

    private companion object {
        const val EXTRAS_TIMEOUT_MS = 6_000L
        /** Sessions looked at for the latest end position. */
        const val POSITION_LOOKBACK = 25
    }
}

/**
 * The stopped timers being (or already) saved in this process, by session id: a second save of the
 * same timer (from the other screen, or a double tap) does nothing.
 */
private object Saving {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun claim(sessionId: String): Boolean = ids.add(sessionId)

    fun release(sessionId: String) {
        ids.remove(sessionId)
    }
}

/** [block]'s result, or null if it failed; being cancelled still cancels. */
internal inline fun <T> attempt(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
