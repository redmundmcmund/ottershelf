package io.github.ottershelf.feature.history

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.ReadingAttempt
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// The reading history: every reading (BookOrbit's reading attempts) of every book the user has a
// reading status for. The server has no list of attempts across books, so the books come from
// `POST books/query` (status reading, re-reading, on hold, read, skimmed or abandoned) and each
// one's readings from `GET books/:id/reading-attempts` (see HistoryViewModel).

/** A `books/query` item, only the fields the history shows (BookCard in packages/types/src/book.ts). */
@Serializable
data class HistoryCard(
    val id: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val hasCover: Boolean = false,
    val addedAt: String? = null,
    val updatedAt: String? = null,
    /** The user's own rating (user_book_ratings), 1..5. */
    val rating: Double? = null,
    val readStatus: ReadStatusInfo? = null,
) {
    /**
     * Changes whenever the book's readings may have: every change to a reading (a status set, a
     * session starting or finishing one, a past read added, edited or deleted) rewrites the book's
     * status row with a new `updatedAt`, so readings cached under the same fingerprint are current.
     */
    val fingerprint: String
        get() = listOf(readStatus?.status, readStatus?.startedAt, readStatus?.finishedAt, readStatus?.updatedAt).joinToString("|")
}

@Serializable
data class HistoryBooksPage(
    val items: List<HistoryCard> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val size: Int = 0,
)

/** One book's readings as last fetched, under the [fingerprint] its card had then. */
@Serializable
data class CachedBook(val card: HistoryCard, val fingerprint: String, val attempts: List<ReadingAttempt>)

/**
 * A book whose readings were just saved on this device (the dates sheet, feature.calendar), as
 * fetched again after the save: the card from `GET books/:id` and every reading.
 */
data class SavedReading(val card: HistoryCard, val attempts: List<ReadingAttempt>)

/** The device copy (`cacheDir/history/<account>.json`). */
@Serializable
data class HistoryCacheFile(val books: List<CachedBook> = emptyList())

/** How a reading went (or is going). */
enum class HistoryOutcome { READING, ON_HOLD, FINISHED, SKIMMED, GAVE_UP }

/** The chips over the list. Finished takes skimmed readings too, Reading the ones on hold. */
enum class HistoryFilter {
    ALL, FINISHED, READING, GAVE_UP;

    fun matches(outcome: HistoryOutcome): Boolean = when (this) {
        ALL -> true
        FINISHED -> outcome == HistoryOutcome.FINISHED || outcome == HistoryOutcome.SKIMMED
        READING -> outcome == HistoryOutcome.READING || outcome == HistoryOutcome.ON_HOLD
        GAVE_UP -> outcome == HistoryOutcome.GAVE_UP
    }
}

/** One reading of one book: a row of the history. */
@Immutable
data class HistoryEntry(
    val bookId: Long,
    val attemptId: Long,
    val title: String?,
    val authors: List<String>,
    val hasCover: Boolean,
    /** The card's `updatedAt` (else `addedAt`): the cover thumbnail's version. */
    val coverVersion: String?,
    val started: LocalDate?,
    /** The day it was finished, skimmed or given up; null while open. */
    val ended: LocalDate?,
    val outcome: HistoryOutcome,
    /** 1 for a first reading, 2 for the reading after one finished (a reread), and so on. */
    val readingNumber: Int,
    /** Days it took, both ends counted (1 when started and finished the same day); for an open reading, Day N so far. */
    val days: Int?,
    /** The user's rating, 1..5. */
    val rating: Int?,
) {
    val isOpen: Boolean get() = outcome == HistoryOutcome.READING || outcome == HistoryOutcome.ON_HOLD
    val isReread: Boolean get() = readingNumber >= 2
}

/** A month's readings inside a year, newest first. */
@Immutable
data class HistoryMonth(val month: YearMonth, val entries: List<HistoryEntry>)

/** A card of the list: the readings going on now, a year, or the ones without dates. */
@Immutable
sealed interface HistoryGroup {
    val key: String

    data class Now(val entries: List<HistoryEntry>) : HistoryGroup {
        override val key get() = "now"
    }

    data class Year(
        val year: Int,
        val months: List<HistoryMonth>,
        /** Books finished that year (every finished reading counts, rereads too). */
        val finished: Int,
        /** Days those finished readings took, together. */
        val days: Int,
        val gaveUp: Int,
    ) : HistoryGroup {
        override val key get() = "year-$year"
    }

    data class Undated(val entries: List<HistoryEntry>) : HistoryGroup {
        override val key get() = "undated"
    }
}

/** The history's rules, pure (HistoryMathTest). */
object HistoryMath {

    /** The statuses whose books have readings: unread and want to read say the user hasn't read it (see [HistoryViewModel]). */
    val STATUSES = listOf(
        ReadStatus.READING.value,
        ReadStatus.REREADING.value,
        ReadStatus.ON_HOLD.value,
        ReadStatus.READ.value,
        ReadStatus.SKIMMED.value,
        ReadStatus.ABANDONED.value,
    )

    fun date(value: String?): LocalDate? {
        if (value.isNullOrBlank() || value.length < 10) return null
        return runCatching { LocalDate.parse(value.substring(0, 10)) }.getOrNull()
    }

    /**
     * Every reading of [card] as rows. Readings are numbered in the order they happened (undated
     * first: the server's placeholder for a reading before its records, then by start or end, then
     * by id); a reading's number is one more than the readings finished before it, so "#2" is a
     * reread. An open reading of a book on hold is [HistoryOutcome.ON_HOLD].
     */
    fun entries(card: HistoryCard, attempts: List<ReadingAttempt>, today: LocalDate): List<HistoryEntry> {
        val ordered = attempts.sortedWith(compareBy<ReadingAttempt>({ date(it.startedOn) ?: date(it.endedOn) }, { it.id }))
        var completed = 0
        val onHold = card.readStatus?.status == ReadStatus.ON_HOLD.value
        return ordered.map { a ->
            val started = date(a.startedOn)
            val ended = date(a.endedOn)
            val outcome = when (a.outcome) {
                null -> if (onHold) HistoryOutcome.ON_HOLD else HistoryOutcome.READING
                AttemptOutcome.COMPLETED -> HistoryOutcome.FINISHED
                AttemptOutcome.SKIMMED -> HistoryOutcome.SKIMMED
                else -> HistoryOutcome.GAVE_UP
            }
            val entry = HistoryEntry(
                bookId = card.id,
                attemptId = a.id,
                title = card.title,
                authors = card.authors,
                hasCover = card.hasCover,
                coverVersion = card.updatedAt ?: card.addedAt,
                started = started,
                ended = if (a.outcome == null) null else ended,
                outcome = outcome,
                readingNumber = completed + 1,
                days = days(started, if (a.outcome == null) today else ended),
                rating = card.rating?.roundToInt()?.takeIf { it in 1..5 },
            )
            if (a.outcome == AttemptOutcome.COMPLETED) completed++
            entry
        }
    }

    /** Both ends counted; null without both, or when they're the wrong way round. */
    fun days(start: LocalDate?, end: LocalDate?): Int? {
        if (start == null || end == null) return null
        return (ChronoUnit.DAYS.between(start, end) + 1).toInt().takeIf { it >= 1 }
    }

    /**
     * The cards, newest first: what the user is reading now (latest start first), then each year by the
     * day its readings ended, months newest first, then the closed readings without an end date.
     * [filter] picks the rows; a year's summary counts all of its readings. Groups left empty by
     * the filter are left out.
     */
    fun groups(entries: List<HistoryEntry>, filter: HistoryFilter): List<HistoryGroup> {
        val newestFirst = compareByDescending<HistoryEntry> { it.ended ?: it.started }.thenByDescending { it.started }.thenByDescending { it.attemptId }
        val open = entries.filter { it.isOpen }
        val closed = entries.filter { !it.isOpen }
        val dated = closed.filter { it.ended != null }
        val undated = closed.filter { it.ended == null }
        return buildList {
            open.filter { filter.matches(it.outcome) }
                .sortedWith(compareByDescending<HistoryEntry> { it.started }.thenByDescending { it.attemptId })
                .takeIf { it.isNotEmpty() }?.let { add(HistoryGroup.Now(it)) }
            dated.groupBy { it.ended!!.year }.toSortedMap(reverseOrder()).forEach { (year, all) ->
                val finished = all.filter { it.outcome == HistoryOutcome.FINISHED }
                val months = all.filter { filter.matches(it.outcome) }
                    .groupBy { YearMonth.from(it.ended!!) }
                    .toSortedMap(reverseOrder())
                    .map { (month, rows) -> HistoryMonth(month, rows.sortedWith(newestFirst)) }
                if (months.isNotEmpty()) {
                    add(
                        HistoryGroup.Year(
                            year = year,
                            months = months,
                            finished = finished.size,
                            days = finished.sumOf { it.days ?: 0 },
                            gaveUp = all.count { it.outcome == HistoryOutcome.GAVE_UP },
                        ),
                    )
                }
            }
            undated.filter { filter.matches(it.outcome) }.sortedWith(newestFirst)
                .takeIf { it.isNotEmpty() }?.let { add(HistoryGroup.Undated(it)) }
        }
    }

    /** Which cached books' readings to fetch again: new books, and books whose status row changed. */
    fun toFetch(cards: List<HistoryCard>, cached: Map<Long, CachedBook>, force: Boolean): List<HistoryCard> =
        if (force) cards else cards.filter { cached[it.id]?.fingerprint != it.fingerprint }
}
