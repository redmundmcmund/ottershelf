package io.github.ottershelf.feature.bookmory

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private fun row(status: BookmoryStatus, vararg logs: BookmoryLog, wishlist: Boolean = false) = BookmoryRow(
        line = 2, title = "T", authors = listOf("A B"), publisher = null, publicationDate = null, language = null,
        isbn = null, bookType = null, status = status, wishlist = wishlist, logs = logs.toList(),
    )

    private val empty = BookState(null, null, emptyList())

    @Test
    fun readLogsAreCompletedOldestFirst() {
        val plan = ImportPlanner.plan(row(BookmoryStatus.READ, BookmoryLog(d(2020, 1, 1), d(2020, 1, 9), 3), BookmoryLog(d(2021, 1, 1), d(2021, 2, 1), 4)))
        assertEquals(
            listOf(PlannedReading(d(2020, 1, 1), d(2020, 1, 9), "completed", exportedStart = d(2020, 1, 1)), PlannedReading(d(2021, 1, 1), d(2021, 2, 1), "completed")),
            plan.readings,
        )
        assertEquals(4, plan.rating)
        assertEquals(false, plan.wantToRead)
    }

    @Test
    fun gaveUpMakesTheLastAbandonedEvenWithoutAnEnd() {
        val plan = ImportPlanner.plan(row(BookmoryStatus.GAVE_UP, BookmoryLog(d(2020, 1, 1), d(2020, 1, 9), null), BookmoryLog(d(2022, 1, 1), null, null)))
        assertEquals(listOf("completed", "abandoned"), plan.readings.map { it.outcome })
        assertNull(plan.readings[1].endedOn)
    }

    @Test
    fun readingKeepsTheLastOpenOnlyWithoutAnEnd() {
        val open = ImportPlanner.plan(row(BookmoryStatus.READING, BookmoryLog(d(2020, 1, 1), d(2020, 1, 9), null), BookmoryLog(d(2022, 6, 1), null, null)))
        assertEquals(listOf("completed", null), open.readings.map { it.outcome })
        val closed = ImportPlanner.plan(row(BookmoryStatus.READING, BookmoryLog(d(2022, 6, 1), d(2022, 7, 1), null)))
        assertEquals(listOf("completed"), closed.readings.map { it.outcome })
        // An open period on a book read to the end is a completed reading without an end date.
        val read = ImportPlanner.plan(row(BookmoryStatus.READ, BookmoryLog(d(2022, 6, 1), null, null)))
        assertEquals(PlannedReading(d(2022, 6, 1), null, "completed", exportedStart = d(2022, 6, 1)), read.readings.single())
    }

    @Test
    fun rowsWithoutLogs() {
        assertTrue(ImportPlanner.plan(row(BookmoryStatus.TO_READ)).wantToRead)
        assertTrue(ImportPlanner.plan(row(BookmoryStatus.UNKNOWN, wishlist = true)).wantToRead)
        assertEquals(listOf(PlannedReading(null, null, "completed", onlyIfNone = true)), ImportPlanner.plan(row(BookmoryStatus.READ)).readings)
        assertEquals(listOf(PlannedReading(null, null, null, onlyIfNone = true)), ImportPlanner.plan(row(BookmoryStatus.READING)).readings)
        assertEquals(listOf(PlannedReading(null, null, "abandoned", onlyIfNone = true)), ImportPlanner.plan(row(BookmoryStatus.GAVE_UP)).readings)
    }

    @Test
    fun aFreshBookGetsEverything() {
        val plan = ImportPlanner.plan(row(BookmoryStatus.READING, BookmoryLog(d(2020, 1, 1), d(2020, 1, 9), 5), BookmoryLog(d(2022, 6, 1), null, null)))
        val diff = ImportPlanner.diff(plan, empty)
        assertEquals(
            listOf(
                ImportAction.AddReading(PlannedReading(d(2020, 1, 1), d(2020, 1, 9), "completed", exportedStart = d(2020, 1, 1))),
                ImportAction.StartReading(d(2022, 6, 1)),
                ImportAction.Rate(5),
            ),
            diff.actions,
        )
        assertEquals(2, diff.readingsToAdd)
    }

    @Test
    fun readingsAlreadyThereAreSkipped() {
        val plan = ImportPlanner.plan(row(BookmoryStatus.READING, BookmoryLog(d(2020, 1, 1), d(2020, 1, 9), 5), BookmoryLog(d(2022, 6, 1), null, null)))
        // The first reading is there (its end edited on the server), the open one was finished since.
        val state = BookState("read", 3, listOf(ServerReading(1, d(2020, 1, 1), d(2020, 1, 20), "completed"), ServerReading(2, d(2022, 6, 1), d(2022, 8, 1), "completed")))
        val diff = ImportPlanner.diff(plan, state)
        assertEquals(emptyList<ImportAction>(), diff.actions)
        assertEquals(2, diff.readingsPresent)
    }

    @Test
    fun anOpenReadingIsNeverOpenedTwice() {
        val plan = ImportPlanner.plan(row(BookmoryStatus.READING, BookmoryLog(d(2022, 6, 1), null, null)))
        val state = BookState("reading", null, listOf(ServerReading(1, d(2023, 1, 1), null, null)))
        assertEquals(emptyList<ImportAction>(), ImportPlanner.diff(plan, state).actions)
    }

    @Test
    fun undatedReadingsMatchByTheNullDatesOrTheExportedStart() {
        val p = PlannedReading(null, null, "completed", exportedStart = d(2018, 1, 12))
        assertTrue(ImportPlanner.isPresent(p, listOf(ServerReading(1, null, null, "completed"))))
        assertTrue(ImportPlanner.isPresent(p, listOf(ServerReading(1, d(2018, 1, 12), d(2018, 1, 12), "completed"))))
        assertEquals(false, ImportPlanner.isPresent(p, listOf(ServerReading(1, null, null, "abandoned"))))
        val noStart = PlannedReading(null, d(2020, 1, 1), "completed")
        assertTrue(ImportPlanner.isPresent(noStart, listOf(ServerReading(1, null, d(2020, 1, 1), "completed"))))
        assertEquals(false, ImportPlanner.isPresent(noStart, listOf(ServerReading(1, null, d(2020, 1, 2), "completed"))))
    }

    @Test
    fun wantToReadOnlyOverNothing() {
        val plan = ImportPlanner.plan(row(BookmoryStatus.TO_READ))
        assertEquals(listOf(ImportAction.WantToRead), ImportPlanner.diff(plan, empty).actions)
        assertEquals(listOf(ImportAction.WantToRead), ImportPlanner.diff(plan, BookState("unread", null, emptyList())).actions)
        assertEquals(emptyList<ImportAction>(), ImportPlanner.diff(plan, BookState("want_to_read", null, emptyList())).actions)
        // Never over a status the user has: no downgrade.
        assertEquals(emptyList<ImportAction>(), ImportPlanner.diff(plan, BookState("read", null, listOf(ServerReading(1, null, d(2020, 1, 1), "completed")))).actions)
    }

    @Test
    fun aRowWithoutLogsOnlyFillsAnEmptyBook() {
        val plan = ImportPlanner.plan(row(BookmoryStatus.READ))
        assertEquals(1, ImportPlanner.diff(plan, empty).readingsToAdd)
        assertEquals(0, ImportPlanner.diff(plan, BookState("reading", null, listOf(ServerReading(1, d(2024, 1, 1), null, null)))).readingsToAdd)
    }

    @Test
    fun aBookHeldOnWantToReadIsMovedToTheLatestOutcome() {
        val plan = ImportPlanner.plan(row(BookmoryStatus.GAVE_UP, BookmoryLog(d(2020, 1, 1), d(2020, 1, 9), null), BookmoryLog(d(2021, 1, 1), d(2021, 1, 5), null)))
        val diff = ImportPlanner.diff(plan, BookState("want_to_read", null, emptyList()))
        assertEquals(ImportAction.SetStatus("abandoned"), diff.actions.last())
        // A book whose status the server derives from its readings needs no status write.
        assertTrue(ImportPlanner.diff(plan, empty).actions.none { it is ImportAction.SetStatus })
    }

    @Test
    fun theLatestReadingIsTheServers() {
        val list = listOf(ServerReading(5, null, null, "abandoned"), ServerReading(1, d(2020, 1, 1), d(2020, 2, 1), "completed"), ServerReading(2, d(2019, 1, 1), null, "skimmed"))
        assertEquals(1L, ImportPlanner.latest(list)!!.id)
        assertEquals(6L, ImportPlanner.latest(list + ServerReading(6, null, d(2020, 2, 1), "completed"))!!.id)
    }

    @Test
    fun ratingOnlyOverNone() {
        val plan = ImportPlanner.plan(row(BookmoryStatus.READ, BookmoryLog(d(2020, 1, 1), d(2020, 1, 9), 4)))
        assertTrue(ImportPlanner.diff(plan, empty).actions.contains(ImportAction.Rate(4)))
        assertTrue(ImportPlanner.diff(plan, BookState(null, 2, emptyList())).actions.none { it is ImportAction.Rate })
    }

    @Test
    fun backlogDatesNeedFiveRowsOnTheSameFirstDay() {
        val day = d(2018, 1, 12)
        val rows = List(5) { row(BookmoryStatus.READ, BookmoryLog(day, day, null)) } +
            List(4) { row(BookmoryStatus.READ, BookmoryLog(d(2018, 1, 13), d(2018, 1, 13), null)) }
        assertEquals(mapOf(day to 5), ImportPlanner.backlogDates(rows))
        val undated = ImportPlanner.plan(rows.first(), setOf(day))
        assertEquals(PlannedReading(null, null, "completed", exportedStart = day), undated.readings.single())
        assertEquals(PlannedReading(day, day, "completed", exportedStart = day), ImportPlanner.plan(rows.first()).readings.single())
    }

    @Test
    fun onlyTheFirstClosedLogLosesItsDates() {
        val day = d(2018, 1, 12)
        val plan = ImportPlanner.plan(row(BookmoryStatus.READING, BookmoryLog(day, null, null)), setOf(day))
        assertEquals(PlannedReading(day, null, null), plan.readings.single())
    }
}
