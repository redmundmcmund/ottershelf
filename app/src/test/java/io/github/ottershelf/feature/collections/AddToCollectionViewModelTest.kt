package io.github.ottershelf.feature.collections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AddToCollectionViewModelTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The server's collections: id to the books in it (adding a book already in is skipped, as the server does). */
    private class FakeRemote : CollectionsRemote {
        val members = mutableMapOf(1L to mutableSetOf<Long>(), 2L to mutableSetOf(7L), 3L to mutableSetOf(7L, 8L))
        val names = mutableMapOf(1L to "Holiday", 2L to "Favourites", 3L to "Classics")
        var refuse = false

        override suspend fun choices(bookIds: List<Long>) = members.keys.sorted().map { id ->
            CollectionChoice(id, names.getValue(id), bookCount = members.getValue(id).size, memberCount = bookIds.count { it in members.getValue(id) })
        }

        override suspend fun add(collectionId: Long, bookIds: List<Long>) {
            if (refuse) throw IOException("offline")
            members.getValue(collectionId) += bookIds
        }

        override suspend fun remove(collectionId: Long, bookIds: List<Long>) {
            if (refuse) throw IOException("offline")
            members.getValue(collectionId) -= bookIds.toSet()
        }

        override suspend fun create(name: String): Long {
            val id = (members.keys.maxOrNull() ?: 0) + 1
            members[id] = mutableSetOf()
            names[id] = name
            return id
        }
    }

    private val one = CollectionTarget(listOf(7), "The Lost World", wantToRead = true)
    private val two = CollectionTarget(listOf(7, 8))

    @Test
    fun opensWithTheBooksCollectionsTicked() = runTest(scheduler) {
        val vm = AddToCollectionViewModel(FakeRemote())
        vm.open(one)
        advanceUntilIdle()
        val s = vm.state.value!!
        assertEquals(listOf(Membership.None, Membership.All, Membership.All), s.choices.map(s::membership))
    }

    @Test
    fun aTapAddsTheBook() = runTest(scheduler) {
        val remote = FakeRemote()
        var changed = 0
        val vm = AddToCollectionViewModel(remote) { changed++ }
        vm.open(one)
        advanceUntilIdle()
        vm.toggle(vm.state.value!!.choices.first())
        advanceUntilIdle()
        assertTrue(7L in remote.members.getValue(1))
        val s = vm.state.value!!
        assertEquals(Membership.All, s.membership(s.choices.first()))
        assertEquals(1, changed)
    }

    @Test
    fun severalBooksShowWhichCollectionsHoldSomeOfThem() = runTest(scheduler) {
        val vm = AddToCollectionViewModel(FakeRemote())
        vm.open(two)
        advanceUntilIdle()
        val s = vm.state.value!!
        assertEquals(listOf(Membership.None, Membership.Some, Membership.All), s.choices.map(s::membership))
    }

    @Test
    fun aTapOnACollectionHoldingSomeAddsTheRest() = runTest(scheduler) {
        val remote = FakeRemote()
        val vm = AddToCollectionViewModel(remote)
        vm.open(two)
        advanceUntilIdle()
        vm.toggle(vm.state.value!!.choices[1]) // Favourites holds 7, not 8
        advanceUntilIdle()
        assertEquals(setOf(7L, 8L), remote.members.getValue(2))
        val s = vm.state.value!!
        assertEquals(Membership.All, s.membership(s.choices[1]))
    }

    @Test
    fun aTapOnACollectionHoldingAllTakesThemOut() = runTest(scheduler) {
        val remote = FakeRemote()
        val vm = AddToCollectionViewModel(remote)
        vm.open(two)
        advanceUntilIdle()
        vm.toggle(vm.state.value!!.choices[2]) // Classics holds both
        advanceUntilIdle()
        assertTrue(remote.members.getValue(3).isEmpty())
    }

    @Test
    fun aRefusedChangeIsPutBack() = runTest(scheduler) {
        val remote = FakeRemote().apply { refuse = true }
        val vm = AddToCollectionViewModel(remote)
        vm.open(two)
        advanceUntilIdle()
        vm.toggle(vm.state.value!!.choices[1])
        advanceUntilIdle()
        val s = vm.state.value!!
        assertEquals(Membership.Some, s.membership(s.choices[1]))
        assertNotNull(s.error)
        assertTrue(s.busy.isEmpty())
    }

    @Test
    fun aNewCollectionIsMadeWithTheBooksInIt() = runTest(scheduler) {
        val remote = FakeRemote()
        val vm = AddToCollectionViewModel(remote)
        vm.open(two)
        advanceUntilIdle()
        vm.create("  To buy  ")
        advanceUntilIdle()
        val s = vm.state.value!!
        val made = s.choices.single { it.name == "To buy" }
        assertEquals(Membership.All, s.membership(made))
        vm.close()
        assertNull(vm.state.value)
    }

    @Test
    fun aPickedSelectionGoesToTheSheetOnce() {
        CollectionPrompt.add(listOf(3L, 3L, 4L))
        assertEquals(CollectionTarget(listOf(3L, 4L)), CollectionPrompt.target.value)
        CollectionPrompt.taken()
        CollectionPrompt.add(emptyList())
        assertNull(CollectionPrompt.target.value)
    }
}
