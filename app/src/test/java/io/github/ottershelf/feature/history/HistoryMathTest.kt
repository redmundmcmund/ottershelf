package io.github.ottershelf.feature.history

import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.tracking.ReadingAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

/** The history's rows, groups and loading (HistoryMath, HistoryLoader). */
class HistoryMathTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun card(id: Long, status: String = "read", updatedAt: String = "2026-09-01T10:00:00Z", rating: Double? = null) = HistoryCard(
        id = id,
        title = "Book $id",
        authors = listOf("Author $id"),
        hasCover = true,
        updatedAt = "2026-01-01T00:00:00Z",
        rating = rating,
        readStatus = ReadStatusInfo(status = status, updatedAt = updatedAt),
    )

    private fun attempt(id: Long, bookId: Long, start: String?, end: String?, outcome: String?) =
        ReadingAttempt(id = id, bookId = bookId, startedOn = start, endedOn = end, outcome = outcome)

    // --- rows ------------------------------------------------------------------------------------

    @Test
    fun aFinishedReadingShowsItsDaysBothEndsCounted() {
        val rows = HistoryMath.entries(card(1, rating = 4.0), listOf(attempt(10, 1, "2026-09-03", "2026-09-21", "completed")), today)
        val row = rows.single()
        assertEquals(HistoryOutcome.FINISHED, row.outcome)
        assertEquals(LocalDate.of(2026, 9, 3), row.started)
        assertEquals(LocalDate.of(2026, 9, 21), row.ended)
        assertEquals(19, row.days)
        assertEquals(1, row.readingNumber)
        assertEquals(4, row.rating)
        assertEquals(1, HistoryMath.days(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3)))
    }

    @Test
    fun rereadsAreNumberedByTheReadingsFinishedBefore() {
        val attempts = listOf(
            attempt(30, 1, "2026-08-01", null, null), // reading again now (the server lists newest first)
            attempt(20, 1, "2025-05-01", "2025-05-02", "abandoned"),
            attempt(10, 1, "2024-01-01", "2024-02-01", "completed"),
            attempt(5, 1, null, null, "completed"), // the server's placeholder for a reading before its records
        )
        val rows = HistoryMath.entries(card(1, status = "rereading"), attempts, today).associateBy { it.attemptId }
        assertEquals(1, rows.getValue(5).readingNumber)
        assertEquals(2, rows.getValue(10).readingNumber)
        assertEquals(3, rows.getValue(20).readingNumber) // a reread the user gave up
        assertEquals(3, rows.getValue(30).readingNumber)
        assertEquals(HistoryOutcome.READING, rows.getValue(30).outcome)
        assertEquals(56, rows.getValue(30).days) // Day 56: 1 Aug to 25 Sept
        assertNull(rows.getValue(30).ended)
        assertEquals(HistoryOutcome.GAVE_UP, rows.getValue(20).outcome)
    }

    @Test
    fun anOpenReadingOfABookOnHoldIsOnHold() {
        val row = HistoryMath.entries(card(1, status = "on_hold"), listOf(attempt(1, 1, "2026-07-01", null, null)), today).single()
        assertEquals(HistoryOutcome.ON_HOLD, row.outcome)
        assertTrue(HistoryFilter.READING.matches(row.outcome))
    }

    // --- groups ----------------------------------------------------------------------------------

    private val library: List<HistoryEntry> by lazy {
        HistoryMath.entries(card(1), listOf(attempt(1, 1, "2026-09-03", "2026-09-21", "completed")), today) +
            HistoryMath.entries(card(2, status = "abandoned"), listOf(attempt(2, 2, "2026-08-01", "2026-08-12", "abandoned")), today) +
            HistoryMath.entries(card(3), listOf(attempt(3, 3, "2025-12-28", "2026-01-05", "completed")), today) +
            HistoryMath.entries(card(4), listOf(attempt(4, 4, "2025-03-01", "2025-03-10", "completed")), today) +
            HistoryMath.entries(card(5, status = "reading"), listOf(attempt(5, 5, "2026-09-20", null, null)), today) +
            HistoryMath.entries(card(6, status = "skimmed"), listOf(attempt(6, 6, null, null, "skimmed")), today)
    }

    @Test
    fun groupsAreNewestFirstWithTheOpenReadingsOnTop() {
        val groups = HistoryMath.groups(library, HistoryFilter.ALL)
        assertEquals(listOf("now", "year-2026", "year-2025", "undated"), groups.map { it.key })
        val now = groups[0] as HistoryGroup.Now
        assertEquals(listOf(5L), now.entries.map { it.bookId })
        val y2026 = groups[1] as HistoryGroup.Year
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 8), YearMonth.of(2026, 1)), y2026.months.map { it.month })
        // Finished in 2026: books 1 (19 days) and 3 (started in 2025, 9 days); one given up.
        assertEquals(2, y2026.finished)
        assertEquals(28, y2026.days)
        assertEquals(1, y2026.gaveUp)
        val y2025 = groups[2] as HistoryGroup.Year
        assertEquals(1, y2025.finished)
        assertEquals(10, y2025.days)
        assertEquals(listOf(6L), (groups[3] as HistoryGroup.Undated).entries.map { it.bookId })
    }

    @Test
    fun filtersPickRowsButAYearsSummaryCountsItAll() {
        val gaveUp = HistoryMath.groups(library, HistoryFilter.GAVE_UP)
        assertEquals(listOf("year-2026"), gaveUp.map { it.key })
        val year = gaveUp.single() as HistoryGroup.Year
        assertEquals(listOf(2L), year.months.flatMap { it.entries }.map { it.bookId })
        assertEquals(2, year.finished)

        assertEquals(listOf("now"), HistoryMath.groups(library, HistoryFilter.READING).map { it.key })
        // Finished takes the skimmed one too.
        assertEquals(listOf("year-2026", "year-2025", "undated"), HistoryMath.groups(library, HistoryFilter.FINISHED).map { it.key })
        assertTrue(HistoryMath.groups(emptyList(), HistoryFilter.ALL).isEmpty())
    }

    // --- loading ---------------------------------------------------------------------------------

    private class FakeRemote(var cards: List<HistoryCard>) : HistoryRemote {
        val attempts = HashMap<Long, List<ReadingAttempt>>()
        val asked = ArrayList<Long>()
        val failing = HashSet<Long>()
        var pagesAsked = 0

        override suspend fun books(page: Int): HistoryBooksPage {
            pagesAsked++
            val slice = cards.drop(page * HistoryRemote.PAGE_SIZE).take(HistoryRemote.PAGE_SIZE)
            return HistoryBooksPage(slice, total = cards.size, page = page, size = HistoryRemote.PAGE_SIZE)
        }

        override suspend fun attempts(bookId: Long): List<ReadingAttempt> {
            asked += bookId
            if (bookId in failing) throw IOException("offline")
            return attempts[bookId].orEmpty()
        }
    }

    @Test
    fun onlyBooksWhoseStatusChangedAreFetchedAgain() = runTest {
        val remote = FakeRemote((1L..450L).map { card(it) })
        remote.cards.forEach { remote.attempts[it.id] = listOf(attempt(it.id, it.id, "2026-01-01", "2026-01-02", "completed")) }
        val loader = HistoryLoader(remote)

        val first = loader.load(emptyMap(), force = false)
        assertEquals(3, remote.pagesAsked) // 200 + 200 + 50
        assertEquals(450, first.books.size)
        assertEquals(450, remote.asked.size)

        remote.asked.clear()
        remote.cards = remote.cards.map { if (it.id == 7L) card(7, updatedAt = "2026-09-25T08:00:00Z") else it }
        val second = loader.load(first.books.associateBy { it.card.id }, force = false)
        assertEquals(listOf(7L), remote.asked)
        assertEquals(450, second.books.size)

        remote.asked.clear()
        loader.load(second.books.associateBy { it.card.id }, force = true) // a pull
        assertEquals(450, remote.asked.size)
    }

    @Test
    fun aBookWhoseReadingsFailKeepsTheSavedOnesAndIsTriedAgain() = runTest {
        val remote = FakeRemote(listOf(card(1), card(2)))
        remote.attempts[1] = listOf(attempt(1, 1, "2026-01-01", "2026-01-02", "completed"))
        remote.attempts[2] = listOf(attempt(2, 2, "2026-02-01", "2026-02-02", "completed"))
        val loader = HistoryLoader(remote)
        val saved = loader.load(emptyMap(), force = false).books.associateBy { it.card.id }

        remote.cards = listOf(card(1, updatedAt = "2026-09-25T08:00:00Z"), card(2, updatedAt = "2026-09-25T08:00:00Z"), card(3))
        remote.failing += listOf(1L, 3L)
        val result = loader.load(saved, force = false)
        assertEquals(2, result.failed)
        assertEquals(listOf(1L, 2L), result.books.map { it.card.id }) // 3 was never loaded: left out
        val one = result.books.first { it.card.id == 1L }
        assertEquals(saved.getValue(1).attempts, one.attempts)
        assertEquals(saved.getValue(1).fingerprint, one.fingerprint) // old fingerprint: fetched again next time
    }
}
