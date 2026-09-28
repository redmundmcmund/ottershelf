package io.github.ottershelf.feature.bookedit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.model.SeriesMembership
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.feature.library.BookListEdits
import io.github.ottershelf.feature.library.Pager
import org.junit.Assert.assertEquals
import org.junit.Test

/** A book list (feature.library's Pager) showing books edited on this phone, against a fake server. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookListEditsTest {

    private val updated = "2026-09-01T00:00:00.000Z"

    /** The server's list: what it answers now. [gate] holds the next answer back until completed. */
    private class Server(var cards: List<BookCard>) {
        var gate: CompletableDeferred<Unit>? = null
        var asked = 0

        suspend fun load(): Pair<List<BookCard>, Int> {
            asked++
            val answer = cards // as the server has it when asked
            gate?.let { gate = null; it.await() }
            return answer to answer.size
        }
    }

    private val changes = ReadingChanges()

    private fun TestScope.list(server: Server, source: BookSource = BookSource.All("All books")): Pager<BookCard> {
        val edits = BookListEdits(changes, source)
        val pager = Pager<BookCard>(this, pageSize = 60, keyOf = { it.id }) { _, _ -> edits.page { server.load() } }
        // In the background, so the test doesn't wait for it; runCurrent() lets it see an edit.
        backgroundScope.launch { edits.follow(pager) }
        runCurrent()
        pager.start()
        return pager
    }

    private val dracula = BookCard(id = 1, title = "Dracula", authors = listOf("Bram Stoker"), updatedAt = updated)
    private val emma = BookCard(id = 2, title = "Emma", authors = listOf("Jane Austen"), updatedAt = updated)

    /** Dracula with its authors edited: the server leaves `updatedAt` as it was for authors alone. */
    private fun draculaBy(vararg authors: AuthorRef) = BookDetail(id = 1, title = "Dracula", authors = authors.toList(), updatedAt = updated)

    private val stoker = AuthorRef(10, "Bram Stoker")
    private val rackham = AuthorRef(11, "Arthur Rackham")

    @Test
    fun theCardsShowingTakeAnEditAtOnce() = runTest {
        val server = Server(listOf(dracula, emma))
        val pager = list(server)
        advanceUntilIdle()
        changes.bookEdited(draculaBy(stoker, rackham))
        runCurrent()
        assertEquals(listOf("Bram Stoker", "Arthur Rackham"), pager.state.value.items.first().authors)
        assertEquals(1, server.asked) // nothing asked for it
    }

    @Test
    fun aListLoadedAfterAnEditIsTheServersWordEvenWithTheSameUpdatedAt() = runTest {
        val server = Server(listOf(dracula, emma))
        val pager = list(server)
        advanceUntilIdle()
        changes.bookEdited(draculaBy(stoker, rackham))
        runCurrent()
        // Someone else puts the authors back on the web, authors only again: updatedAt still the same.
        server.cards = listOf(dracula.copy(authors = listOf("Bram Stoker")), emma)
        pager.refresh()
        advanceUntilIdle()
        assertEquals(listOf("Bram Stoker"), pager.state.value.items.first().authors)
    }

    @Test
    fun aPageOnItsWayDuringAnEditTakesIt() = runTest {
        val server = Server(listOf(dracula, emma))
        val pager = list(server)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        server.gate = gate
        pager.refresh()
        runCurrent() // asked: the server answers from before the edit
        changes.bookEdited(draculaBy(stoker, rackham))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Bram Stoker", "Arthur Rackham"), pager.state.value.items.first().authors)
        assertEquals(listOf("Jane Austen"), pager.state.value.items[1].authors)
    }

    @Test
    fun anAuthorsListLoadsAgainWhenTheBookLeavesItAndTheReloadStaysAsTheServerSays() = runTest {
        // Bram Stoker's books (author 10).
        val server = Server(listOf(dracula))
        val pager = list(server, BookSource.ByAuthor(10, "Bram Stoker"))
        advanceUntilIdle()
        server.cards = emptyList()
        changes.bookEdited(draculaBy(AuthorRef(12, "Florence Stoker")))
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, server.asked)
        assertEquals(emptyList<BookCard>(), pager.state.value.items)
    }

    @Test
    fun aSeriesPageKeepsItsOwnNumberForABookWhoseMainSeriesIsAnother() = runTest {
        // Series B's page (21), where the server shows Dracula as B #7; its main series is A (9), #3 there.
        val server = Server(listOf(dracula.copy(seriesName = "B", seriesIndex = "7")))
        val pager = list(server, BookSource.InSeries(21, "B"))
        advanceUntilIdle()
        changes.bookEdited(
            BookDetail(
                id = 1, title = "Dracula (fixed)", authors = listOf(stoker), seriesId = 9, seriesName = "A", seriesIndex = "3",
                seriesMemberships = listOf(SeriesMembership(9, "A", "3"), SeriesMembership(21, "B", "7")), updatedAt = updated,
            ),
        )
        runCurrent()
        advanceUntilIdle()
        val card = pager.state.value.items.single()
        assertEquals("Dracula (fixed)", card.title)
        assertEquals("B" to "7", card.seriesName to card.seriesIndex)
        assertEquals(1, server.asked) // still in B: nothing to load again
    }

    @Test
    fun aSeriesPageLoadsAgainWhenTheBookLeavesIt() = runTest {
        val server = Server(listOf(dracula.copy(seriesName = "B", seriesIndex = "7")))
        val pager = list(server, BookSource.InSeries(21, "B"))
        advanceUntilIdle()
        server.cards = emptyList()
        changes.bookEdited(BookDetail(id = 1, title = "Dracula", seriesId = 9, seriesName = "A", seriesIndex = "3", seriesMemberships = listOf(SeriesMembership(9, "A", "3"))))
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, server.asked)
        assertEquals(emptyList<BookCard>(), pager.state.value.items)
    }
}
