package io.github.ottershelf.feature.calendar

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.Patch
import io.github.ottershelf.core.tracking.ReadingAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Where a book's dates go (a reading it has, or a new one), the date rules, and what the server does to the user's status. */
class ReadingDatesTest {

    private val today = LocalDate.of(2026, 9, 26)
    private val day = LocalDate.of(2024, 9, 3)

    private fun attempt(
        id: Long,
        started: String? = null,
        ended: String? = null,
        outcome: String? = AttemptOutcome.COMPLETED,
        sessions: Int = 0,
        origin: String = "bookorbit",
    ) = ReadingAttempt(id = id, bookId = 7, startedOn = started, endedOn = ended, outcome = outcome, origin = origin, totalSessions = sessions)

    // --- the choice between fixing a reading and adding one -------------------------------------

    @Test
    fun aBookWithoutReadingsGetsANewOne() {
        assertEquals(DatesTarget.New, ReadingDates.defaultTarget(emptyList(), day))
    }

    @Test
    fun aBookMarkedReadByHandGetsItsDatesInsteadOfASecondReading() {
        // PATCH books/:id/status {status: read} without dates: {null, <the day it was marked>, completed, manual}, no sessions.
        val marked = attempt(3, ended = "2026-09-25", origin = "manual")
        assertEquals(DatesTarget.Existing(3), ReadingDates.defaultTarget(listOf(marked), day))
        // The calendar's day is the finish; the start stays unset until the user sets one.
        val form = ReadingDates.initialForm(DatesTarget.Existing(3), listOf(marked), day, today)
        assertEquals(DatesForm(started = null, ended = day, outcome = AttemptOutcome.COMPLETED), form)
    }

    @Test
    fun aReadingWithoutAnyDatesIsFixedFirst() {
        // The server's backfill of an old "read" status, or its placeholder before a reread.
        val undated = attempt(2, origin = "migration")
        val dated = attempt(5, started = "2025-01-02", ended = "2025-02-03", sessions = 12)
        assertEquals(DatesTarget.Existing(2), ReadingDates.defaultTarget(listOf(dated, undated), day))
        // Given up without a date counts too.
        assertEquals(DatesTarget.Existing(4), ReadingDates.defaultTarget(listOf(attempt(4, outcome = AttemptOutcome.ABANDONED)), day))
    }

    @Test
    fun theReadingGoingOnIsFinishedOnTheDayWhenItStartedByThen() {
        val open = attempt(9, started = "2024-08-20", outcome = null, sessions = 4)
        val placeholder = attempt(1, origin = "migration")
        assertEquals(DatesTarget.Existing(9), ReadingDates.defaultTarget(listOf(placeholder, open), day))
        val form = ReadingDates.initialForm(DatesTarget.Existing(9), listOf(placeholder, open), day, today)
        assertEquals(LocalDate.of(2024, 8, 20), form.started)
        assertEquals(day, form.ended)
        assertEquals(AttemptOutcome.COMPLETED, form.outcome)
        // One without a start date can be finished any day.
        assertEquals(DatesTarget.Existing(9), ReadingDates.defaultTarget(listOf(open.copy(startedOn = null)), day))
    }

    @Test
    fun aDayBeforeTheOpenReadingStartedIsAnEarlierReading() {
        val open = attempt(9, started = "2026-09-01", outcome = null)
        val placeholder = attempt(1, origin = "migration")
        // The placeholder of the reading before this reread gets the dates...
        assertEquals(DatesTarget.Existing(1), ReadingDates.defaultTarget(listOf(placeholder, open), day))
        // ...and without one, it's a reading of its own.
        assertEquals(DatesTarget.New, ReadingDates.defaultTarget(listOf(open), day))
    }

    @Test
    fun aDatedOrTrackedFinishMeansAnotherReading() {
        val dated = attempt(5, started = "2023-01-02", ended = "2023-02-03")
        assertEquals(DatesTarget.New, ReadingDates.defaultTarget(listOf(dated), day))
        // Finished by the reader (no start, but sessions behind it): a reread.
        assertEquals(DatesTarget.New, ReadingDates.defaultTarget(listOf(attempt(6, ended = "2025-05-05", sessions = 30)), day))
        // Marked by hand before this day: an earlier reading, so this one is new.
        assertEquals(DatesTarget.New, ReadingDates.defaultTarget(listOf(attempt(7, ended = "2023-05-05")), day))
    }

    @Test
    fun choosingAnotherReadingPutsItsDatesInTheFields() {
        val attempts = listOf(attempt(5, started = "2023-01-02", ended = "2023-02-03", outcome = AttemptOutcome.SKIMMED))
        val form = ReadingDates.initialForm(DatesTarget.Existing(5), attempts, day, today)
        assertEquals(DatesForm(LocalDate.of(2023, 1, 2), day, AttemptOutcome.SKIMMED), form)
        // A new reading: only the day.
        assertEquals(DatesForm(null, day, AttemptOutcome.COMPLETED), ReadingDates.initialForm(DatesTarget.New, attempts, day, today))
        // From the toolbar (today), and never after today.
        assertEquals(today, ReadingDates.initialForm(DatesTarget.New, attempts, today.plusDays(3), today).ended)
    }

    @Test
    fun editingFromHistoryKeepsTheReadingsOwnDates() {
        val finished = attempt(5, started = "2023-01-02", ended = "2023-02-03")
        assertEquals(
            DatesForm(LocalDate.of(2023, 1, 2), LocalDate.of(2023, 2, 3), AttemptOutcome.COMPLETED),
            ReadingDates.initialForm(DatesTarget.Existing(5), listOf(finished), day = null, today = today),
        )
        // An open one stays open (Still reading), its end ready for today if the user finishes it.
        val open = attempt(9, started = "2026-09-01", outcome = null)
        assertEquals(DatesForm(LocalDate.of(2026, 9, 1), today, null), ReadingDates.initialForm(DatesTarget.Existing(9), listOf(open), null, today))
        // "Add dates" on one without: today until the user picks.
        assertEquals(DatesForm(null, today, AttemptOutcome.COMPLETED), ReadingDates.initialForm(DatesTarget.Existing(2), listOf(attempt(2)), null, today))
    }

    @Test
    fun movingToAnotherReadingKeepsTheFieldsTheUserSet() {
        val open = attempt(9, started = "2024-08-20", outcome = null)
        val skimmed = attempt(5, started = "2023-01-02", ended = "2023-02-03", outcome = AttemptOutcome.SKIMMED)
        val attempts = listOf(open, skimmed)
        val theirs = DatesForm(LocalDate.of(2024, 8, 1), day.plusDays(2), AttemptOutcome.ABANDONED)
        // Nothing set: the reading's own fields.
        assertEquals(
            ReadingDates.initialForm(DatesTarget.Existing(9), attempts, day, today),
            ReadingDates.refill(DatesTarget.Existing(9), attempts, day, today, theirs, emptySet()),
        )
        // The user's end date only: the reading's start and outcome.
        assertEquals(
            DatesForm(LocalDate.of(2024, 8, 20), day.plusDays(2), AttemptOutcome.COMPLETED),
            ReadingDates.refill(DatesTarget.Existing(9), attempts, day, today, theirs, setOf(DatesField.END)),
        )
        // Everything the user's.
        assertEquals(theirs, ReadingDates.refill(DatesTarget.Existing(5), attempts, day, today, theirs, DatesField.entries.toSet()))
        // An outcome the reading doesn't offer is its own: skimmed isn't offered for a new one.
        assertEquals(
            AttemptOutcome.COMPLETED,
            ReadingDates.refill(DatesTarget.New, attempts, day, today, theirs.copy(outcome = AttemptOutcome.SKIMMED), setOf(DatesField.OUTCOME)).outcome,
        )
        // A reading gone from the server: the fields stay as they were.
        assertEquals(theirs, ReadingDates.refill(DatesTarget.Existing(4), attempts, null, today, theirs, emptySet()))
    }

    @Test
    fun outcomesAreFinishedAndGaveUpWithSkimmedAndStillReadingWhereTheyApply() {
        val done = AttemptOutcome.COMPLETED
        val gaveUp = AttemptOutcome.ABANDONED
        assertEquals(listOf(done, gaveUp), ReadingDates.outcomes(DatesTarget.New, emptyList()))
        assertEquals(listOf(done, gaveUp, AttemptOutcome.SKIMMED), ReadingDates.outcomes(DatesTarget.Existing(1), listOf(attempt(1, outcome = AttemptOutcome.SKIMMED))))
        assertEquals(listOf(done, gaveUp, null), ReadingDates.outcomes(DatesTarget.Existing(1), listOf(attempt(1, outcome = null))))
    }

    // --- the date rules -------------------------------------------------------------------------

    @Test
    fun noDateAfterTodayAndTheStartNotAfterTheEnd() {
        assertNull(ReadingDates.problem(DatesForm(day, day), today))
        assertNull(ReadingDates.problem(DatesForm(null, today), today))
        assertEquals(DatesProblem.START_AFTER_END, ReadingDates.problem(DatesForm(day.plusDays(1), day), today))
        assertEquals(DatesProblem.END_IN_FUTURE, ReadingDates.problem(DatesForm(null, today.plusDays(1)), today))
        assertEquals(DatesProblem.START_IN_FUTURE, ReadingDates.problem(DatesForm(today.plusDays(1), today.plusDays(2)), today))
        // Still reading: the hidden end date doesn't count.
        assertNull(ReadingDates.problem(DatesForm(day, day.minusDays(10), outcome = null), today))
        assertEquals(DatesProblem.START_IN_FUTURE, ReadingDates.problem(DatesForm(today.plusDays(1), day, outcome = null), today))
    }

    // --- what Save sends ------------------------------------------------------------------------

    @Test
    fun aNewReadingIsAPastReadWithTheServersOutcome() {
        val plan = ReadingDates.plan(DatesTarget.New, DatesForm(LocalDate.of(2024, 8, 1), day, AttemptOutcome.ABANDONED), emptyList(), "read")
        assertEquals(DatesSave.Create(LocalDate.of(2024, 8, 1), day, AttemptOutcome.ABANDONED), plan.save)
        assertNull(plan.thenStatus)
    }

    @Test
    fun fixingAReadingSendsBothDatesAndItsOutcome() {
        val marked = attempt(3, ended = "2026-09-25", origin = "manual")
        val plan = ReadingDates.plan(DatesTarget.Existing(3), DatesForm(null, day, AttemptOutcome.COMPLETED), listOf(marked), "read")
        assertEquals(DatesSave.Update(3, Patch.Clear, Patch.Set(day), Patch.Set(AttemptOutcome.COMPLETED)), plan.save)
        assertEquals("read", plan.statusAfter)
        assertNull(ReadingDates.statusChange("read", plan))
        // Still reading: only the start.
        val open = attempt(9, started = "2026-09-01", outcome = null)
        val keep = ReadingDates.plan(DatesTarget.Existing(9), DatesForm(LocalDate.of(2026, 8, 30), today, null), listOf(open), "reading")
        assertEquals(DatesSave.Update(9, Patch.Set(LocalDate.of(2026, 8, 30)), Patch.Keep, Patch.Keep), keep.save)
        assertEquals("reading", keep.statusAfter)
    }

    @Test
    fun aReadingAddedToAnUnreadBookSetsItsStatus() {
        // The server keeps unread after a past read; the sheet then says what happened.
        val finished = ReadingDates.plan(DatesTarget.New, DatesForm(null, day), emptyList(), "unread")
        assertEquals("read", finished.thenStatus)
        assertEquals("read", ReadingDates.statusChange("unread", finished))
        assertFalse(ReadingDates.staysWanted("unread", finished))
        val gaveUp = ReadingDates.plan(DatesTarget.New, DatesForm(null, day, AttemptOutcome.ABANDONED), emptyList(), "unread")
        assertEquals("abandoned", gaveUp.thenStatus)
        // No status yet: the server works it out from the new reading, nothing more is sent.
        val fresh = ReadingDates.plan(DatesTarget.New, DatesForm(null, day), emptyList(), null)
        assertNull(fresh.thenStatus)
        assertEquals("read", fresh.statusAfter)
        assertEquals("read", ReadingDates.statusChange(null, fresh))
    }

    @Test
    fun aBookOnWantToReadStaysThere() {
        // As the server, the book page's past read and the web's reading log leave it: the user may mean to read it again.
        for (outcome in listOf(AttemptOutcome.COMPLETED, AttemptOutcome.ABANDONED)) {
            val added = ReadingDates.plan(DatesTarget.New, DatesForm(null, day, outcome), emptyList(), "want_to_read")
            assertNull(added.thenStatus)
            assertEquals("want_to_read", added.statusAfter)
            assertNull(ReadingDates.statusChange("want_to_read", added))
            // The sheet says the calendar and History won't show it.
            assertTrue(ReadingDates.staysWanted("want_to_read", added))
        }
        // Fixing a reading it has: the same.
        val marked = attempt(3, ended = "2026-09-25", origin = "manual")
        val fixed = ReadingDates.plan(DatesTarget.Existing(3), DatesForm(null, day), listOf(marked), "want_to_read")
        assertNull(fixed.thenStatus)
        assertTrue(ReadingDates.staysWanted("want_to_read", fixed))
        // Any other status: nothing to say.
        assertFalse(ReadingDates.staysWanted("read", ReadingDates.plan(DatesTarget.New, DatesForm(null, day), listOf(marked), "read")))
    }

    @Test
    fun theStatusFollowsTheServersRebuild() {
        val finished = attempt(5, started = "2025-01-02", ended = "2025-02-03")
        val open = attempt(9, started = "2026-09-01", outcome = null)
        // Reading now and a finished reading added before it: a reread.
        val before = ReadingDates.plan(DatesTarget.New, DatesForm(null, day), listOf(open), "reading")
        assertEquals("rereading", before.statusAfter)
        assertNull(before.thenStatus)
        // On hold stays on hold.
        assertEquals("on_hold", ReadingDates.plan(DatesTarget.New, DatesForm(null, day), listOf(open), "on_hold").statusAfter)
        // Finishing the open reading: read.
        assertEquals("read", ReadingDates.plan(DatesTarget.Existing(9), DatesForm(null, today), listOf(finished, open), "rereading").statusAfter)
        // The newest reading (by id) decides: an older reading given up, added now, makes it abandoned.
        val older = ReadingDates.plan(DatesTarget.New, DatesForm(null, day, AttemptOutcome.ABANDONED), listOf(finished), "read")
        assertEquals("abandoned", older.statusAfter)
        assertEquals("abandoned", ReadingDates.statusChange("read", older))
        assertEquals("unread", ReadingDates.statusAfter("read", emptyList()))
        assertEquals("want_to_read", ReadingDates.statusAfter("want_to_read", listOf(finished)))
        assertEquals("skimmed", ReadingDates.statusAfter("read", listOf(finished, attempt(8, outcome = AttemptOutcome.SKIMMED))))
    }

    @Test
    fun appliedGivesANewReadingTheNextId() {
        val finished = attempt(5, ended = "2025-02-03")
        val after = ReadingDates.applied(DatesSave.Create(null, day, AttemptOutcome.COMPLETED), listOf(finished))
        assertEquals(listOf(5L, 6L), after.map { it.id })
        assertEquals("2024-09-03", after.last().endedOn)
        val fixed = ReadingDates.applied(DatesSave.Update(5, Patch.Set(day), Patch.Keep, Patch.Keep), listOf(finished)).single()
        assertEquals("2024-09-03", fixed.startedOn)
        assertEquals("2025-02-03", fixed.endedOn)
    }

    @Test
    fun readingsAreListedOpenFirstThenNewest() {
        val list = listOf(
            attempt(1, origin = "migration"),
            attempt(2, started = "2019-01-01", ended = "2019-02-01"),
            attempt(3, started = "2026-09-01", outcome = null),
            attempt(4, ended = "2024-05-05"),
        )
        assertEquals(listOf(3L, 4L, 2L, 1L), ReadingDates.ordered(list).map { it.id })
    }

    // --- the picker's query ---------------------------------------------------------------------

    @Test
    fun thePickersQueriesAreTheServersSchema() {
        val allowed = setOf("collapseSeries", "filter", "q", "randomSeed", "sort", "pagination")
        val search = ApiJson.encodeToJsonElement(PickerQuery.serializer(), PickerQueries.search("  conan doyle ")).jsonObject
        assertTrue(allowed.containsAll(search.keys))
        assertEquals("conan doyle", search["q"]!!.jsonPrimitive.content)
        assertFalse(search.containsKey("filter"))
        assertEquals("""[{"field":"relevance","dir":"desc"}]""", search["sort"].toString())
        assertEquals("""{"page":0,"size":30}""", search["pagination"].toString())
        assertEquals(200, PickerQueries.search("x".repeat(500)).q!!.length)

        val reading = ApiJson.encodeToJsonElement(PickerQuery.serializer(), PickerQueries.readingNow()).jsonObject
        assertTrue(allowed.containsAll(reading.keys))
        assertFalse(reading.containsKey("q"))
        val rule = reading["filter"]!!.jsonObject["rules"]!!.jsonArray.single().jsonObject
        assertEquals("readStatus", rule["field"]!!.jsonPrimitive.content)
        assertEquals("includesAny", rule["operator"]!!.jsonPrimitive.content)
        assertEquals(listOf("reading", "rereading", "on_hold"), (rule["value"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("""[{"field":"lastReadAt","dir":"desc"}]""", reading["sort"].toString())
    }

    @Test
    fun aCardsFormatsArePrimaryFirst() {
        val card = ApiJson.decodeFromString(
            PickerCard.serializer(),
            """{"id":4,"title":"Dracula","files":[{"id":1,"format":"PDF"},{"id":2,"format":"epub","role":"primary"},{"id":3,"format":"pdf"}],"extra":true}""",
        )
        assertEquals(listOf("epub", "pdf"), card.formats)
    }
}
