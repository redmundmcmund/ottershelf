package io.github.ottershelf.feature.bookmory

import io.github.ottershelf.core.tracking.AttemptOutcome
import java.time.LocalDate

/**
 * A reading the import wants a book to have. [outcome] null is a reading still going (set with the
 * status route as reading from [startedOn], since `POST reading-attempts` always closes one).
 */
data class PlannedReading(
    val startedOn: LocalDate?,
    val endedOn: LocalDate?,
    val outcome: String?,
    /**
     * The export's start date of a row's first closed reading (imported with its dates, or without
     * them as a backlog reading): it is there when the book has a reading starting that day, or one
     * with no dates and the same outcome.
     */
    val exportedStart: LocalDate? = null,
    /** Only for a book with no readings at all: a row with a read status but no reading log. */
    val onlyIfNone: Boolean = false,
) {
    val isOpen: Boolean get() = outcome == null
}

/** What the import wants for one row's book, whatever book that turns out to be. */
data class RowPlan(
    val row: BookmoryRow,
    /** Oldest first. */
    val readings: List<PlannedReading>,
    /** Want to read, for a book with no status and no readings. */
    val wantToRead: Boolean,
    /** 1..5, for a book this user hasn't rated. */
    val rating: Int?,
)

/** A book's tracking data on the server, as the import reads it before writing. */
data class BookState(
    val status: String?,
    val rating: Int?,
    val readings: List<ServerReading>,
)

data class ServerReading(
    val id: Long,
    val startedOn: LocalDate?,
    val endedOn: LocalDate?,
    val outcome: String?,
) {
    val isOpen: Boolean get() = outcome == null
}

/** One write the import makes for a book. */
sealed interface ImportAction {
    /** `POST books/:id/reading-attempts` (a closed reading). */
    data class AddReading(val reading: PlannedReading) : ImportAction

    /** `PATCH books/:id/status {status: reading, startedAt}`: opens a reading from that day. */
    data class StartReading(val startedOn: LocalDate?) : ImportAction

    /** `PATCH books/:id/status {status: want_to_read}`. */
    data object WantToRead : ImportAction

    /**
     * `PATCH books/:id/status {status}` with no dates, for a book held on unread or want to read,
     * which the readings alone don't move (reading-attempt.service.ts rebuildProjection). [status]
     * is the outcome of the book's latest reading, so the server adds none.
     */
    data class SetStatus(val status: String) : ImportAction

    /** `POST books/bulk-set-rating`. */
    data class Rate(val rating: Int) : ImportAction
}

/** The writes one row needs, and how many of its readings the book already has. */
data class RowDiff(
    val actions: List<ImportAction>,
    /** Readings the book has already, as they are in the export. */
    val readingsPresent: Int,
    /**
     * Readings left alone because the book has one for them already, with other dates: an open
     * reading for an open log that started on another day, or a reading with the same end and
     * outcome for a closed log that started on another day (BookOrbit's own sessions started it).
     */
    val readingsLeftAlone: Int = 0,
    /** A rating the account may not set on this book. */
    val ratingsBlocked: Int = 0,
) {
    val readingsToAdd: Int get() = actions.count { it is ImportAction.AddReading || it is ImportAction.StartReading }
    val statusChanges: Int get() = actions.count { it is ImportAction.WantToRead || it is ImportAction.SetStatus }
    val ratings: Int get() = actions.count { it is ImportAction.Rate }
}

/**
 * Turns Bookmory rows into what BookOrbit should hold, and compares that with a book's state on the
 * server. Pure. The rules:
 * - each reading log is one reading, oldest first; the last one is given up when the row says
 *   "Gave up", stays open when the row says "Reading" and it has no end date; every other one is
 *   completed;
 * - a row with no log: "To read" (or on the wishlist) is want to read, "Reading" an open reading
 *   with no start, read or gave up a reading with no dates; all only for a book with no readings and
 *   no status;
 * - the rating (1..5) only for a book this user hasn't rated;
 * - nothing that is there already is added, and nothing on the server is changed or removed: a
 *   reading counts as there when one starts on the same day (with no start: the same end and
 *   outcome); it is left alone when the book has one for it with other dates: an open reading for
 *   an open log, one with the same end and outcome for a closed log ([Presence.SIMILAR]).
 */
object ImportPlanner {

    /** Statuses the import may set over: the book has nothing of the user's yet. */
    private val EMPTY_STATUSES = setOf(null, "unread")

    /** First-log start dates shared by at least [min] rows (a backlog logged when Bookmory was installed), with their counts. */
    fun backlogDates(rows: List<BookmoryRow>, min: Int = BACKLOG_MIN): Map<LocalDate, Int> =
        rows.mapNotNull { it.logs.firstOrNull()?.startedOn }
            .groupingBy { it }.eachCount()
            .filterValues { it >= min }
            .toSortedMap()

    fun plan(row: BookmoryRow, undatedBacklog: Set<LocalDate> = emptySet()): RowPlan {
        val logs = row.logs
        val readings = logs.mapIndexed { i, log ->
            val last = i == logs.lastIndex
            val outcome = when {
                last && row.status == BookmoryStatus.GAVE_UP -> AttemptOutcome.ABANDONED
                last && row.status == BookmoryStatus.READING && log.endedOn == null -> null
                else -> AttemptOutcome.COMPLETED
            }
            // The first reading keeps its exported start either way, so it is found again whether it
            // went in with its dates or without (the backlog choice changed between two imports).
            val first = i == 0 && outcome != null && log.startedOn != null
            when {
                first && log.startedOn in undatedBacklog -> PlannedReading(null, null, outcome, exportedStart = log.startedOn)
                first -> PlannedReading(log.startedOn, log.endedOn, outcome, exportedStart = log.startedOn)
                else -> PlannedReading(log.startedOn, log.endedOn, outcome)
            }
        }.ifEmpty {
            when (row.status) {
                BookmoryStatus.READ -> listOf(PlannedReading(null, null, AttemptOutcome.COMPLETED, onlyIfNone = true))
                BookmoryStatus.GAVE_UP -> listOf(PlannedReading(null, null, AttemptOutcome.ABANDONED, onlyIfNone = true))
                BookmoryStatus.READING -> listOf(PlannedReading(null, null, null, onlyIfNone = true))
                else -> emptyList()
            }
        }
        val wantToRead = row.status == BookmoryStatus.TO_READ || (row.wishlist && logs.isEmpty() && row.status == BookmoryStatus.UNKNOWN)
        return RowPlan(row, readings, wantToRead, row.rating)
    }

    /** How [reading] stands against a book's readings: not there, there as exported, or there with other dates. */
    enum class Presence { NEW, SAME, SIMILAR }

    /** Whether [reading] is on the server already among [existing] (as exported, or with other dates). */
    fun isPresent(reading: PlannedReading, existing: List<ServerReading>): Boolean = presence(reading, existing) != Presence.NEW

    fun presence(reading: PlannedReading, existing: List<ServerReading>): Presence {
        fun sameIf(found: Boolean) = if (found) Presence.SAME else null
        // The book has a reading that ended the same day with the same outcome.
        val similar = if (!reading.isOpen && reading.endedOn != null &&
            existing.any { it.endedOn == reading.endedOn && it.outcome == reading.outcome }
        ) Presence.SIMILAR else Presence.NEW
        return when {
            reading.onlyIfNone -> if (existing.isNotEmpty()) Presence.SAME else Presence.NEW
            reading.isOpen -> sameIf(reading.startedOn != null && existing.any { it.startedOn == reading.startedOn })
                ?: if (existing.any { it.isOpen }) Presence.SIMILAR else Presence.NEW
            reading.exportedStart != null -> sameIf(
                existing.any {
                    it.startedOn == reading.exportedStart ||
                        (it.startedOn == null && it.endedOn == null && it.outcome == reading.outcome)
                },
            ) ?: similar
            reading.startedOn != null -> sameIf(existing.any { it.startedOn == reading.startedOn }) ?: similar
            else -> sameIf(existing.any { it.startedOn == null && it.endedOn == reading.endedOn && it.outcome == reading.outcome }) ?: similar
        }
    }

    /**
     * The writes [plan] needs on a book in [state]; with [mayRate] false a rating it would set is
     * counted in [RowDiff.ratingsBlocked] instead.
     */
    fun diff(plan: RowPlan, state: BookState, mayRate: Boolean = true): RowDiff {
        val actions = mutableListOf<ImportAction>()
        var present = 0
        var leftAlone = 0
        val readings = state.readings.toMutableList()
        var nextId = (readings.maxOfOrNull { it.id } ?: 0) + 1
        val hadNothing = state.readings.isEmpty() && state.status in EMPTY_STATUSES
        for (reading in plan.readings) {
            when (presence(reading, readings)) {
                Presence.SAME -> { present++; continue }
                Presence.SIMILAR -> { leftAlone++; continue }
                Presence.NEW -> Unit
            }
            actions += if (reading.isOpen) ImportAction.StartReading(reading.startedOn) else ImportAction.AddReading(reading)
            readings += ServerReading(nextId++, reading.startedOn, reading.endedOn, reading.outcome)
        }
        if (plan.wantToRead) {
            if (hadNothing) actions += ImportAction.WantToRead
        } else {
            statusAfter(plan, state.status, readings)?.let { actions += ImportAction.SetStatus(it) }
        }
        val rate = plan.rating != null && state.rating == null
        if (rate && mayRate) actions += ImportAction.Rate(plan.rating!!)
        return RowDiff(actions, present, leftAlone, ratingsBlocked = if (rate && !mayRate) 1 else 0)
    }

    /**
     * The status to set once [readings] are on the book, or null when the server sets it itself:
     * only a book held on unread or want to read (which readings alone don't move), for a row the
     * user has read or given up, and never past an open reading.
     */
    fun statusAfter(plan: RowPlan, status: String?, readings: List<ServerReading>): String? {
        if (status != "unread" && status != "want_to_read") return null
        if (plan.row.status !in setOf(BookmoryStatus.READ, BookmoryStatus.GAVE_UP, BookmoryStatus.READING)) return null
        if (readings.any { it.isOpen }) return null
        return when (latest(readings)?.outcome) {
            AttemptOutcome.COMPLETED -> "read"
            AttemptOutcome.SKIMMED -> "skimmed"
            AttemptOutcome.ABANDONED -> "abandoned"
            else -> null
        }
    }

    /** The reading the server counts as latest: by end (else start) date, newest first, dates before none, then by id. */
    fun latest(readings: List<ServerReading>): ServerReading? =
        readings.maxWithOrNull(compareBy<ServerReading>({ (it.endedOn ?: it.startedOn) != null }, { it.endedOn ?: it.startedOn }, { it.id }))

    const val BACKLOG_MIN = 5
}
