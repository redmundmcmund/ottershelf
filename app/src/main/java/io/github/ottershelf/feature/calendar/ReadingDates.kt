package io.github.ottershelf.feature.calendar

import androidx.compose.runtime.Immutable
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.Patch
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.feature.history.HistoryCard
import io.github.ottershelf.feature.history.HistoryMath
import java.time.LocalDate

// Start and end dates for a book, from the Calendar (a day, or the toolbar's +) and from History
// (a reading's "Edit dates"). Pure rules, tested in ReadingDatesTest; the sheet is
// ReadingDatesSheet.kt, the flow ReadingDatesViewModel.
//
// It goes through the book page's path (TrackingRepository.createPastRead / updateAttempt:
// `POST books/:bookId/reading-attempts` {startedOn?, endedOn?, outcome} and
// `PATCH books/:bookId/reading-attempts/:attemptId` {startedOn?, endedOn?, outcome?}; plain
// `YYYY-MM-DD` dates, outcome completed / skimmed / abandoned). What the server does with them
// (reading-attempt.service.ts):
// - Both routes check only that the start isn't after the end; the phone keeps both dates up to
//   today in the user's zone, as the book page does (the status route refuses future dates by its own clock).
// - After either, the book's status is rebuilt from its readings (`rebuildProjection`): an open
//   reading makes it reading / re-reading (a finished one before it) / on hold (if it was); else a
//   book on unread or want to read STAYS there; else the newest reading by id says read, skimmed or
//   abandoned, and its dates become the status's. [ReadingDates.statusAfter] is that rule.
// - A book marked read by hand (`PATCH books/:id/status {status: read}`, no dates) gets a reading
//   `{startedOn: null, endedOn: <the day it was marked>, outcome: completed, origin: manual}`, no
//   sessions. One marked read before readings existed was carried over by the server's backfill
//   with the status's dates, often none (`endedOn: null`), and re-reading without a finished
//   reading adds a placeholder `{null, null, completed, origin: migration}`. So "no dates" is a
//   reading without dates, or a hand-made finish without a start: the sheet offers to fix it
//   ([ReadingDates.defaultTarget]) rather than adding a second one.
// - A reading added to a book on unread would leave it unread (and out of History and the
//   calendar), though the user has just said the user read it, so the sheet then sets the status to the
//   reading's outcome ([DatesPlan.thenStatus]: `PATCH books/:id/status {status}`, no dates, which
//   keeps the reading just added as it is and projects its dates).
// - A book on want to read stays there, as the server, the book page's past read and the web's
//   reading log leave it (the user may mean to read it again): the sheet says so, since History and the
//   calendar don't list its readings until its status changes ([ReadingDates.staysWanted]).

/** Which reading the dates go to: a new one, or one the book already has. */
@Immutable
sealed interface DatesTarget {
    /** Add a reading (a reread when the book has a finished one). */
    data object New : DatesTarget

    /** Fix the dates of this reading. */
    data class Existing(val attemptId: Long) : DatesTarget
}

/**
 * The sheet's fields. [outcome] is completed, skimmed or abandoned; null only for an open reading
 * kept open ("Still reading": the end date is left alone). [ended] is kept while hidden.
 */
@Immutable
data class DatesForm(
    val started: LocalDate? = null,
    val ended: LocalDate,
    val outcome: String? = AttemptOutcome.COMPLETED,
)

/** A field of [DatesForm] the user set themselves (it stays as set when the reading the dates go to changes). */
enum class DatesField { START, END, OUTCOME }

/** What's wrong with the dates (Save waits until it's fixed). */
enum class DatesProblem { START_IN_FUTURE, END_IN_FUTURE, START_AFTER_END }

/** The write the sheet sends. */
sealed interface DatesSave {
    /** `POST books/:bookId/reading-attempts` (TrackingRepository.createPastRead). */
    data class Create(val startedOn: LocalDate?, val endedOn: LocalDate, val outcome: String) : DatesSave

    /** `PATCH books/:bookId/reading-attempts/:attemptId` (TrackingRepository.updateAttempt). */
    data class Update(
        val attemptId: Long,
        val startedOn: Patch<LocalDate>,
        val endedOn: Patch<LocalDate>,
        val outcome: Patch<String>,
    ) : DatesSave
}

/**
 * [save], then (when not null) [thenStatus] with `PATCH books/:id/status` and no dates;
 * [statusAfter] is the book's status once both are done, as the server will work it out.
 */
data class DatesPlan(val save: DatesSave, val thenStatus: String?, val statusAfter: String)

object ReadingDates {

    fun date(value: String?): LocalDate? = HistoryMath.date(value)

    /**
     * Where the dates ending on [day] go by default, for a book with [attempts] (the sheet asks
     * again whenever the user moves the end date, until the user picks a reading themselves):
     * 1. the reading the user is in now, if it started by then (or has no start): the user finished it that
     *    day (adding one instead would make the book a reread still going);
     * 2. else a closed reading without an end date (marked read before readings had dates, or the
     *    server's placeholder for a reading before its records): it gets the dates;
     * 3. else a finish marked by hand (no start, no sessions) on or after [day]: it was marked
     *    late, and [day] is when the user finished;
     * 4. else a new reading (a reread of a book the user has finished).
     */
    fun defaultTarget(attempts: List<ReadingAttempt>, day: LocalDate): DatesTarget {
        attempts.firstOrNull { it.outcome == null && date(it.startedOn)?.isAfter(day) != true }
            ?.let { return DatesTarget.Existing(it.id) }
        attempts.filter { it.outcome != null && date(it.endedOn) == null }.maxByOrNull { it.id }
            ?.let { return DatesTarget.Existing(it.id) }
        attempts.filter { it.outcome != null && date(it.startedOn) == null && it.totalSessions == 0 && date(it.endedOn)?.isBefore(day) == false }
            .maxByOrNull { it.id }
            ?.let { return DatesTarget.Existing(it.id) }
        return DatesTarget.New
    }

    /**
     * The fields for [target]. [day]: the day the user came from (the calendar), which the end date
     * takes; null when editing a reading (History), which keeps its own dates (an undated one
     * gets [today]). An open reading edited from History stays open unless the user says otherwise.
     */
    fun initialForm(target: DatesTarget, attempts: List<ReadingAttempt>, day: LocalDate?, today: LocalDate): DatesForm {
        val attempt = (target as? DatesTarget.Existing)?.let { t -> attempts.firstOrNull { it.id == t.attemptId } }
        if (attempt == null) return DatesForm(started = null, ended = minOf(day ?: today, today), outcome = AttemptOutcome.COMPLETED)
        val end = day ?: date(attempt.endedOn)?.takeIf { attempt.outcome != null } ?: today
        return DatesForm(
            started = date(attempt.startedOn),
            ended = minOf(end, today),
            outcome = attempt.outcome ?: if (day != null) AttemptOutcome.COMPLETED else null,
        )
    }

    /**
     * [form] once the dates go to [target] instead, or once [target]'s reading is fetched again:
     * the fields the user set ([edited]) stay as the user set them, the others are [target]'s
     * ([initialForm]); an outcome [target] doesn't offer is its own. A reading no longer among
     * [attempts] leaves [form] as it is (it can't be saved).
     */
    fun refill(
        target: DatesTarget,
        attempts: List<ReadingAttempt>,
        day: LocalDate?,
        today: LocalDate,
        form: DatesForm,
        edited: Set<DatesField>,
    ): DatesForm {
        if (target is DatesTarget.Existing && attempts.none { it.id == target.attemptId }) return form
        val base = initialForm(target, attempts, day, today)
        return DatesForm(
            started = if (DatesField.START in edited) form.started else base.started,
            ended = if (DatesField.END in edited) form.ended else base.ended,
            outcome = if (DatesField.OUTCOME in edited && form.outcome in outcomes(target, attempts)) form.outcome else base.outcome,
        )
    }

    /**
     * The outcomes offered: Finished and Gave up; Skimmed too for a reading that was skimmed (so
     * editing its dates doesn't change it); and "Still reading" (null) for the open reading.
     */
    fun outcomes(target: DatesTarget, attempts: List<ReadingAttempt>): List<String?> {
        val attempt = (target as? DatesTarget.Existing)?.let { t -> attempts.firstOrNull { it.id == t.attemptId } }
        return buildList {
            add(AttemptOutcome.COMPLETED)
            add(AttemptOutcome.ABANDONED)
            if (attempt?.outcome == AttemptOutcome.SKIMMED) add(AttemptOutcome.SKIMMED)
            if (attempt != null && attempt.outcome == null) add(null)
        }
    }

    /** The date rules: nothing after [today] (the user's zone), and the start not after the end. */
    fun problem(form: DatesForm, today: LocalDate): DatesProblem? {
        val start = form.started
        val end = form.ended.takeIf { form.outcome != null }
        return when {
            start != null && start.isAfter(today) -> DatesProblem.START_IN_FUTURE
            end != null && end.isAfter(today) -> DatesProblem.END_IN_FUTURE
            start != null && end != null && start.isAfter(end) -> DatesProblem.START_AFTER_END
            else -> null
        }
    }

    /**
     * What Save sends for [form] on [target], given the book's [attempts] and the user's [status] now
     * (null: none). A fixed reading gets both dates and its outcome, as the web's form sends them
     * (a start the user cleared is cleared); one kept open gets only its start.
     */
    fun plan(target: DatesTarget, form: DatesForm, attempts: List<ReadingAttempt>, status: String?): DatesPlan {
        val existing = (target as? DatesTarget.Existing)?.let { t -> attempts.firstOrNull { it.id == t.attemptId } }
        val save: DatesSave = if (existing == null) {
            DatesSave.Create(form.started, form.ended, form.outcome ?: AttemptOutcome.COMPLETED)
        } else {
            val outcome = form.outcome
            DatesSave.Update(
                attemptId = existing.id,
                startedOn = form.started?.let { Patch.Set(it) } ?: Patch.Clear,
                endedOn = if (outcome == null) Patch.Keep else Patch.Set(form.ended),
                outcome = if (outcome == null) Patch.Keep else Patch.Set(outcome),
            )
        }
        val after = applied(save, attempts)
        val unread = status == ReadStatus.UNREAD.value
        val then = if (save is DatesSave.Create && unread && after.none { it.outcome == null }) statusFor(save.outcome) else null
        return DatesPlan(save, then, then ?: statusAfter(status, after))
    }

    /**
     * The book is on want to read and stays there after [plan] (the server keeps it, and so does
     * the sheet): History and the calendar won't list the reading, so the sheet says so.
     */
    fun staysWanted(status: String?, plan: DatesPlan): Boolean =
        status == ReadStatus.WANT_TO_READ.value && plan.statusAfter == ReadStatus.WANT_TO_READ.value

    /** The book's readings once [save] is in (a new one gets the next id, as the server's are ascending). */
    fun applied(save: DatesSave, attempts: List<ReadingAttempt>): List<ReadingAttempt> = when (save) {
        is DatesSave.Create -> attempts + ReadingAttempt(
            id = (attempts.maxOfOrNull { it.id } ?: 0) + 1,
            bookId = attempts.firstOrNull()?.bookId ?: 0,
            startedOn = save.startedOn?.toString(),
            endedOn = save.endedOn.toString(),
            outcome = save.outcome,
            origin = "manual",
        )
        is DatesSave.Update -> attempts.map { a ->
            if (a.id != save.attemptId) a
            else a.copy(
                startedOn = save.startedOn.applyTo(a.startedOn),
                endedOn = save.endedOn.applyTo(a.endedOn),
                outcome = when (val o = save.outcome) {
                    Patch.Keep -> a.outcome
                    Patch.Clear -> null
                    is Patch.Set -> o.value
                },
            )
        }
    }

    private fun Patch<LocalDate>.applyTo(value: String?): String? = when (this) {
        Patch.Keep -> value
        Patch.Clear -> null
        is Patch.Set -> this.value.toString()
    }

    /**
     * The status the server works out from a book's readings after a reading is added, changed or
     * deleted (reading-attempt.service.ts `rebuildProjection`), from [current] (null: no status).
     */
    fun statusAfter(current: String?, attempts: List<ReadingAttempt>): String {
        val active = attempts.firstOrNull { it.outcome == null }
        val latest = active ?: attempts.maxByOrNull { it.id }
        return when {
            active != null -> when {
                current == ReadStatus.ON_HOLD.value -> ReadStatus.ON_HOLD.value
                attempts.any { it.outcome == AttemptOutcome.COMPLETED } -> ReadStatus.REREADING.value
                else -> ReadStatus.READING.value
            }
            current == ReadStatus.WANT_TO_READ.value || current == ReadStatus.UNREAD.value -> current
            latest?.outcome == AttemptOutcome.COMPLETED -> ReadStatus.READ.value
            latest?.outcome == AttemptOutcome.SKIMMED -> ReadStatus.SKIMMED.value
            latest?.outcome == AttemptOutcome.ABANDONED -> ReadStatus.ABANDONED.value
            else -> ReadStatus.UNREAD.value
        }
    }

    /** The status that says a reading ended this way. */
    fun statusFor(outcome: String): String = when (outcome) {
        AttemptOutcome.SKIMMED -> ReadStatus.SKIMMED.value
        AttemptOutcome.ABANDONED -> ReadStatus.ABANDONED.value
        else -> ReadStatus.READ.value
    }

    /** The status to mention under the dates, when saving changes it (null when it stays). */
    fun statusChange(status: String?, plan: DatesPlan): String? =
        plan.statusAfter.takeIf { it != (status ?: ReadStatus.UNREAD.value) }

    /** The book as History keeps it, from `GET books/:id` after the save. */
    fun cardOf(book: BookDetail): HistoryCard = HistoryCard(
        id = book.id,
        title = book.title,
        authors = book.authors.map { it.name },
        hasCover = book.coverSource != null,
        addedAt = book.addedAt,
        updatedAt = book.updatedAt,
        rating = book.rating?.toDouble(),
        readStatus = book.readStatus,
    )

    /** The readings as the sheet lists them: the one going on first, then newest first (by end, else start, else id). */
    fun ordered(attempts: List<ReadingAttempt>): List<ReadingAttempt> =
        attempts.sortedWith(
            compareBy<ReadingAttempt> { it.outcome != null }
                .thenByDescending { date(it.endedOn) ?: date(it.startedOn) ?: LocalDate.MIN }
                .thenByDescending { it.id },
        )
}
