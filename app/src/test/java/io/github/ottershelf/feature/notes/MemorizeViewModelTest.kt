package io.github.ottershelf.feature.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookAnnotationsPage
import io.github.ottershelf.feature.notes.model.BookFacet
import io.github.ottershelf.feature.notes.model.HubOverview
import io.github.ottershelf.feature.notes.model.HubPage
import io.github.ottershelf.feature.notes.model.NoteFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Memorize against a fake hub: failures show Retry (never crash the app), never "no highlights yet". */
@OptIn(ExperimentalCoroutinesApi::class)
class MemorizeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The hub's highlights; the calls numbered in [failOn] fail as if offline. [staleTotal]: the first answer's total. */
    private class FakeHub(val notes: List<Annotation>, val failOn: Set<Int> = emptySet(), val staleTotal: Int? = null) : NotesRemote {
        var calls = 0
        var offline = false

        override suspend fun hub(filter: NoteFilter, page: Int, pageSize: Int): HubPage {
            calls++
            if (offline || calls in failOn) throw IOException("offline")
            val total = if (calls == 1 && staleTotal != null) staleTotal else notes.size
            return HubPage(items = notes.drop((page - 1) * pageSize).take(pageSize), total = total, page = page, pageSize = pageSize)
        }

        override suspend fun bookAnnotations(bookId: Long, page: Int, pageSize: Int, newestFirst: Boolean): BookAnnotationsPage = error("unused")
        override suspend fun overview(): HubOverview = error("unused")
        override suspend fun books(limit: Int): List<BookFacet> = error("unused")
        override suspend fun update(bookId: Long, id: Long, body: JsonObject): Annotation = error("unused")
        override suspend fun delete(bookId: Long, id: Long) = error("unused")
        override suspend fun exportMarkdown(bookId: Long): String = error("unused")
    }

    private val notes = (1L..3L).map { Annotation(id = it, bookId = 1, text = "text $it") }

    private fun memorize(hub: FakeHub) =
        MemorizeViewModel(NotesRepository(hub, settings = null), settings = null, coverOf = { null }, bookId = null, liked = false)

    @Test
    fun aFailedFirstFetchShowsRetryAndRetryLoads() = runTest(dispatcher) {
        val hub = FakeHub(notes).apply { offline = true }
        val vm = memorize(hub)
        advanceUntilIdle() // before the fix the failed child async escaped to the thread here
        assertEquals("offline", vm.state.value.error)
        assertNull(vm.state.value.current)
        assertFalse(vm.state.value.empty)

        hub.offline = false
        vm.retry()
        advanceUntilIdle()
        assertNotNull(vm.state.value.current)
        assertNull(vm.state.value.error)
    }

    @Test
    fun aFailedPrefetchIsFetchedAgainOnTheTap() = runTest(dispatcher) {
        // Calls: 1 the total, 2 the first note, 3 the prefetch (fails).
        val vm = memorize(FakeHub(notes, failOn = setOf(3)))
        advanceUntilIdle()
        val first = vm.state.value.current
        assertNotNull(first)

        vm.next()
        advanceUntilIdle()
        assertNotNull(vm.state.value.current)
        assertFalse(vm.state.value.empty)
        assertEquals(1, vm.state.value.session)
        assertEquals(mapOf(first!!.id to 1), vm.state.value.reviews)
    }

    @Test
    fun aTotalThatWentStaleIsReadAgainRatherThanEndingEmpty() = runTest(dispatcher) {
        // The server said three, but one was deleted since: index 2 comes back empty.
        val hub = FakeHub(notes.take(2), staleTotal = 3)
        val random = RandomNotes(NotesRepository(hub, settings = null), NoteFilter()) { emptyMap() }
        repeat(6) { assertNotNull(random.next()) }
        assertEquals(2, random.count)
    }
}
