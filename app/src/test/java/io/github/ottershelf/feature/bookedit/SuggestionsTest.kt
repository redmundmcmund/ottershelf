package io.github.ottershelf.feature.bookedit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Suggestions as the user types: debounced, one request at a time, and an old answer never replaces a newer one. */
@OptIn(ExperimentalCoroutinesApi::class)
class SuggestionsTest {

    private class Remote {
        val asked = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        /** Answers held back until completed (else answered at once). */
        val held = mutableMapOf<String, CompletableDeferred<List<String>>>()
        var fail = false

        suspend fun search(q: String): List<String> {
            asked += q
            if (fail) throw IOException("offline")
            val gate = held[q] ?: return listOf("$q 1", "$q 2")
            try {
                return gate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += q
                throw e
            }
        }
    }

    private fun TestScope.search(remote: Remote) = SuggestionSearch(backgroundScope, debounceMs = 250) { remote.search(it) }

    /** Past any debounce, with everything due run (advanceUntilIdle leaves backgroundScope's work alone). */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun typingQuicklyAsksOnceForTheLastText() = runTest {
        val remote = Remote()
        val s = search(remote)
        s.query("F")
        advanceTimeBy(100)
        s.query("Fr")
        advanceTimeBy(100)
        s.query("Fra")
        advanceTimeBy(249)
        runCurrent()
        assertEquals(emptyList<String>(), remote.asked)
        assertTrue(s.state.value.loading)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Fra"), remote.asked)
        assertEquals(Suggestions(query = "Fra", items = listOf("Fra 1", "Fra 2")), s.state.value)
    }

    @Test
    fun aSlowAnswerToAnOldQueryIsDroppedAndItsRequestCancelled() = runTest {
        val remote = Remote()
        val slow = CompletableDeferred<List<String>>()
        remote.held["Le"] = slow
        val s = search(remote)
        s.query("Le")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("Le"), remote.asked)
        s.query("Conan D")
        runCurrent()
        // The old request goes (its HTTP call with it); its answer, arriving anyway, isn't taken.
        assertEquals(listOf("Le"), remote.cancelled)
        slow.complete(listOf("Radcliffe"))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(Suggestions(query = "Conan D", items = listOf("Conan D 1", "Conan D 2")), s.state.value)
    }

    @Test
    fun anAnswerIsKeptOnlyWhileTheBoxStillSaysItsQuery() = runTest {
        val remote = Remote()
        val gate = CompletableDeferred<List<String>>()
        remote.held["Ba"] = gate
        val s = search(remote)
        s.query("Ba")
        advanceTimeBy(300)
        runCurrent()
        s.clear() // a suggestion picked
        gate.complete(listOf("Poe"))
        settle()
        assertEquals(Suggestions<String>(), s.state.value)
    }

    @Test
    fun theLastAnswerShowsWhileTheNextIsComing() = runTest {
        val remote = Remote()
        val s = search(remote)
        s.query("An")
        advanceTimeBy(300)
        runCurrent()
        s.query("Ann")
        runCurrent()
        assertEquals(listOf("An 1", "An 2"), s.state.value.items)
        assertTrue(s.state.value.loading)
        assertEquals("Ann", s.state.value.query)
    }

    @Test
    fun aBlankBoxAsksNothingAndShowsNothing() = runTest {
        val remote = Remote()
        val s = search(remote)
        s.query("Ur")
        s.query("   ")
        settle()
        assertEquals(emptyList<String>(), remote.asked)
        assertEquals(Suggestions<String>(), s.state.value)
    }

    @Test
    fun theSameTextAgainDoesntAskAgainUnlessItFailed() = runTest {
        val remote = Remote()
        val s = search(remote)
        s.query("Dracula")
        settle()
        s.query(" Dracula ")
        settle()
        assertEquals(listOf("Dracula"), remote.asked)

        remote.fail = true
        s.query("Dra")
        settle()
        assertTrue(s.state.value.failed)
        assertEquals(emptyList<String>(), s.state.value.items)
        remote.fail = false
        s.query("Dra")
        settle()
        assertEquals(listOf("Dracula", "Dra", "Dra"), remote.asked)
        assertFalse(s.state.value.failed)
    }

    // --- the rows under the boxes -------------------------------------------------------------------

    private fun authors(query: String, vararg names: Pair<String, Int>) =
        Suggestions(query = query, items = names.map { (n, c) -> AuthorSuggestion(n, c) })

    @Test
    fun theLibrarysMatchesComeFirstThenAddingTheNameAsTyped() {
        val rows = authorRows("Conan D", authors("Conan D", "Arthur Conan Doyle" to 12, "Legrand" to 1), chosen = emptyList())
        assertEquals(
            listOf(AuthorRow.Existing(AuthorSuggestion("Arthur Conan Doyle", 12)), AuthorRow.Existing(AuthorSuggestion("Legrand", 1)), AuthorRow.Add("Conan D")),
            rows,
        )
    }

    @Test
    fun anExactMatchComesFirstAndTakesThePlaceOfAdd() {
        val rows = authorRows("edgar poe", authors("edgar poe", "Edgar A. Poe" to 20, "Edgar Poe" to 9), chosen = emptyList())
        assertEquals(listOf(AuthorRow.Existing(AuthorSuggestion("Edgar Poe", 9)), AuthorRow.Existing(AuthorSuggestion("Edgar A. Poe", 20))), rows)
    }

    @Test
    fun authorsAlreadyChosenArentSuggested() {
        val rows = authorRows("Ann", authors("Ann", "Ann Radcliffe" to 5, "Anne Brontë" to 3), chosen = listOf("ann radcliffe"))
        assertEquals(listOf(AuthorRow.Existing(AuthorSuggestion("Anne Brontë", 3)), AuthorRow.Add("Ann")), rows)
        // Typing a chosen name offers nothing to add.
        assertEquals(emptyList<AuthorRow>(), authorRows("Ann Radcliffe", authors("Ann Radcliffe", "Ann Radcliffe" to 5), chosen = listOf("Ann Radcliffe")))
    }

    @Test
    fun anAnswerForOtherTextOffersOnlyAdd() {
        val rows = authorRows("Stev", authors("St", "Stephen Crane" to 2), chosen = emptyList())
        assertEquals(listOf(AuthorRow.Add("Stev")), rows)
        assertEquals(emptyList<AuthorRow>(), authorRows("  ", authors("", "X" to 1), chosen = emptyList()))
    }

    @Test
    fun seriesSuggestionsPutAnExactNameFirstAndGoOnceOneIsPicked() {
        val found = Suggestions(
            query = "the oz books",
            items = listOf(SeriesSuggestion("The Oz Books Extras", 4, emptyList()), SeriesSuggestion("The Oz Books", 9, listOf("L. Frank Baum"))),
        )
        assertEquals(listOf("The Oz Books", "The Oz Books Extras"), seriesRows("the oz books", found).map { it.name })
        val picked = found.copy(query = "The Oz Books")
        assertEquals(emptyList<SeriesSuggestion>(), seriesRows("The Oz Books", picked))
        assertEquals(emptyList<SeriesSuggestion>(), seriesRows("Other", found))
    }

    @Test
    fun aSeriesTheLibraryDoesntHaveIsNew() {
        val found = Suggestions(query = "Sherlock Holmes", items = listOf(SeriesSuggestion("Sherlock Holmes Novels", 6, emptyList())))
        assertTrue(isNewSeries("Sherlock Holmes", found))
        assertFalse(isNewSeries("sherlock holmes novels", found.copy(query = "sherlock holmes novels")))
        assertFalse(isNewSeries("Sherlock Holmes", found.copy(loading = true)))
        assertFalse(isNewSeries("Sherlock Holmes", found.copy(failed = true)))
        assertFalse(isNewSeries("Earth", found))
    }
}
