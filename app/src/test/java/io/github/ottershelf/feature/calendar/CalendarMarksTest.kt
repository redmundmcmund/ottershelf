package io.github.ottershelf.feature.calendar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.feature.history.CachedBook
import io.github.ottershelf.feature.history.HistoryBooksPage
import io.github.ottershelf.feature.history.HistoryCache
import io.github.ottershelf.feature.history.HistoryCacheFile
import io.github.ottershelf.feature.history.HistoryCard
import io.github.ottershelf.feature.history.HistoryLoader
import io.github.ottershelf.feature.history.HistoryRemote
import io.github.ottershelf.feature.history.ReadingLog
import io.github.ottershelf.feature.history.SavedReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.YearMonth

/** The calendar's marks: a reading started, finished or given up on a day, from the books' readings. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarMarksTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun card(id: Long, title: String, status: String = "read", updatedAt: String = "2026-09-01T00:00:00Z") =
        HistoryCard(id, title, listOf("Someone"), hasCover = true, updatedAt = updatedAt, readStatus = ReadStatusInfo(status = status, updatedAt = updatedAt))

    private fun reading(id: Long, bookId: Long, start: String?, end: String?, outcome: String? = AttemptOutcome.COMPLETED) =
        ReadingAttempt(id = id, bookId = bookId, startedOn = start, endedOn = end, outcome = outcome)

    private fun book(card: HistoryCard, vararg readings: ReadingAttempt) = CachedBook(card, card.fingerprint, readings.toList())

    private val frankenstein = card(1, "Frankenstein")
    private val dracula = card(2, "Dracula", status = "abandoned")
    private val treasureIsland = card(3, "Treasure Island", status = "reading")

    private val books = listOf(
        book(frankenstein, reading(10, 1, "2026-09-03", "2026-09-21"), reading(11, 1, null, null)),
        book(dracula, reading(20, 2, "2026-09-01", "2026-09-12", AttemptOutcome.ABANDONED)),
        book(treasureIsland, reading(30, 3, "2026-09-21", null, outcome = null), reading(31, 3, null, "2025-12-30", AttemptOutcome.SKIMMED)),
    )

    @Test
    fun eachReadingMarksItsStartAndItsEnd() {
        val marks = CalendarMath.marksOf(books)
        assertEquals(listOf(MarkKind.STARTED), marks["2026-09-03"]!!.map { it.kind })
        assertEquals(listOf(MarkKind.GAVE_UP), marks["2026-09-12"]!!.map { it.kind })
        // Finished first, then started; the open reading has no end, the undated none at all.
        assertEquals(listOf(1L to MarkKind.FINISHED, 3L to MarkKind.STARTED), marks["2026-09-21"]!!.map { it.book.bookId to it.kind })
        assertEquals(listOf(MarkKind.FINISHED), marks["2025-12-30"]!!.map { it.kind })
        assertEquals(setOf("2026-09-01", "2026-09-03", "2026-09-12", "2026-09-21", "2025-12-30"), marks.keys)
        // The cover is History's: the card's time is its version.
        assertEquals(CalendarBook(1, "Frankenstein", true, "2026-09-01T00:00:00Z"), marks["2026-09-21"]!!.first().book)
    }

    @Test
    fun aReadingStartedAndFinishedTheSameDayIsMarkedFinished() {
        val marks = CalendarMath.marksOf(listOf(book(frankenstein, reading(10, 1, "2026-09-05", "2026-09-05"))))
        assertEquals(listOf(MarkKind.FINISHED), marks["2026-09-05"]!!.map { it.kind })
    }

    @Test
    fun aDaysCoversPutTheFinishedBookInFront() {
        val marks = CalendarMath.marksOf(books)["2026-09-21"]!!
        val read = CalendarDay("2026-09-21", 3600, 2, listOf(CalendarBook(4, "Middlemarch", seconds = 3000), CalendarBook(1, "Frankenstein", seconds = 600)))
        val stack = CalendarMath.stack(read, marks)
        assertEquals(listOf(1L, 4L, 3L), stack.books.map { it.bookId })
        assertEquals(MarkKind.FINISHED, stack.badge)
        // The session's cover is kept for a book both read and finished that day.
        assertEquals(600L, stack.books.first().seconds)
        // Only started: its cover, no badge.
        val started = CalendarMath.stack(null, CalendarMath.marksOf(books)["2026-09-03"]!!)
        assertEquals(listOf(1L), started.books.map { it.bookId })
        assertNull(started.badge)
        assertTrue(CalendarMath.stack(null, emptyList()).books.isEmpty())
    }

    @Test
    fun theMonthsBooksIncludeTheOnesOnlyMarked() {
        val marks = CalendarMath.marksOf(books)
        val month = CalendarMonth("2026-09", listOf(CalendarDay("2026-09-21", 600, 1, listOf(CalendarBook(1, "Frankenstein", seconds = 600)))))
        val list = CalendarMath.books(month, marks)
        assertEquals(listOf(1L, 3L, 2L), list.map { it.book.bookId })
        val frankenstein = list[0]
        assertEquals(1, frankenstein.days)
        assertEquals(LocalDate.of(2026, 9, 21), frankenstein.finished)
        assertEquals(LocalDate.of(2026, 9, 3), frankenstein.started)
        val treasureIsland = list[1]
        assertEquals(0, treasureIsland.days)
        assertEquals(LocalDate.of(2026, 9, 21), treasureIsland.started)
        assertEquals(LocalDate.of(2026, 9, 12), list[2].gaveUp)
        // A month with marks but no data yet, and the old call without marks.
        assertEquals(3, CalendarMath.books(null, marks, YearMonth.of(2026, 9)).size)
        assertEquals(listOf(1L), CalendarMath.books(month).map { it.book.bookId })
        assertTrue(CalendarMath.books(null, marks, YearMonth.of(2026, 8)).isEmpty())
    }

    // --- the log: History's copy, with what was saved here on top -------------------------------

    private class Remote(var cards: List<HistoryCard>, val attempts: MutableMap<Long, List<ReadingAttempt>>) : HistoryRemote {
        /** Holds the book list back until completed (a load still on its way). */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun books(page: Int): HistoryBooksPage {
            val listed = cards
            gate?.await()
            return HistoryBooksPage(listed, listed.size, page, HistoryRemote.PAGE_SIZE)
        }
        override suspend fun attempts(bookId: Long): List<ReadingAttempt> = attempts[bookId].orEmpty()
    }

    @Test
    fun aSavedReadingShowsAtOnceAndOutlastsALoadThatStartedBeforeIt() = runTest {
        val cache = HistoryCache(folder.newFile("history.json").also { it.delete() })
        cache.write(HistoryCacheFile(listOf(books[0])))
        val remote = Remote(listOf(frankenstein), mutableMapOf(1L to books[0].attempts))
        val log = ReadingLog(HistoryLoader(remote), cache)
        assertEquals(setOf(1L), log.current().keys)

        // A load on its way when Dracula is given dates on this device...
        val gate = CompletableDeferred<Unit>()
        remote.gate = gate
        val early = async { log.refresh() }
        runCurrent()
        val saved = SavedReading(dracula, listOf(reading(20, 2, "2026-09-01", "2026-09-12", AttemptOutcome.ABANDONED)))
        assertEquals(setOf(1L, 2L), log.add(saved).keys)
        // ...doesn't have it, and doesn't take it away.
        gate.complete(Unit)
        assertEquals(setOf(1L, 2L), early.await().keys)
        remote.gate = null

        // A load that started after the save has it (the server does).
        remote.cards = listOf(frankenstein, dracula)
        remote.attempts[2] = saved.attempts
        assertEquals(setOf(1L, 2L), log.refresh().keys)
        assertEquals(setOf(1L, 2L), cache.read()!!.books.map { it.card.id }.toSet())
        // From then on the loads alone say.
        remote.cards = listOf(frankenstein)
        assertEquals(setOf(1L), log.refresh().keys)
        assertEquals(listOf(1L), cache.read()!!.books.map { it.card.id })
    }

    @Test
    fun aSavedBookLeftOutOfHistoryIsDropped() = runTest {
        val log = ReadingLog(HistoryLoader(Remote(emptyList(), mutableMapOf())), HistoryCache(folder.newFile("h.json").also { it.delete() }))
        val unread = card(5, "Unread one", status = "want_to_read")
        assertTrue(log.add(SavedReading(unread, emptyList())).isEmpty())
    }
}
