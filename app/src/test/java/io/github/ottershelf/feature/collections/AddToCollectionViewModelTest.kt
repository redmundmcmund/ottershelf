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
import org.junit.Assert.assertFalse
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

    private class FakeRemote : CollectionsRemote {
        val members = mutableMapOf(1L to mutableSetOf<Long>(), 2L to mutableSetOf(7L))
        val names = mutableMapOf(1L to "Holiday", 2L to "Favourites")
        var refuse = false
        var changes = 0

        override suspend fun choices(bookId: Long) = members.keys.sorted().map { id ->
            CollectionChoice(id, names.getValue(id), bookCount = members.getValue(id).size, memberCount = if (bookId in members.getValue(id)) 1 else 0)
        }

        override suspend fun add(collectionId: Long, bookId: Long) {
            if (refuse) throw IOException("offline")
            members.getValue(collectionId) += bookId
        }

        override suspend fun remove(collectionId: Long, bookId: Long) {
            if (refuse) throw IOException("offline")
            members.getValue(collectionId) -= bookId
        }

        override suspend fun create(name: String): Long {
            val id = (members.keys.maxOrNull() ?: 0) + 1
            members[id] = mutableSetOf()
            names[id] = name
            return id
        }
    }

    private val book = PromptBook(7, "The Lost World")

    @Test
    fun opensWithTheBooksCollectionsTicked() = runTest(scheduler) {
        val vm = AddToCollectionViewModel(FakeRemote())
        vm.open(book)
        advanceUntilIdle()
        val choices = vm.state.value!!.choices
        assertEquals(listOf(false, true), choices.map { it.contains })
    }

    @Test
    fun aTapAddsTheBook() = runTest(scheduler) {
        val remote = FakeRemote()
        var changed = 0
        val vm = AddToCollectionViewModel(remote) { changed++ }
        vm.open(book)
        advanceUntilIdle()
        vm.toggle(vm.state.value!!.choices.first())
        advanceUntilIdle()
        assertTrue(7L in remote.members.getValue(1))
        assertTrue(vm.state.value!!.choices.first().contains)
        assertEquals(1, changed)
    }

    @Test
    fun aRefusedChangeIsPutBack() = runTest(scheduler) {
        val remote = FakeRemote().apply { refuse = true }
        val vm = AddToCollectionViewModel(remote)
        vm.open(book)
        advanceUntilIdle()
        vm.toggle(vm.state.value!!.choices.first())
        advanceUntilIdle()
        val s = vm.state.value!!
        assertFalse(s.choices.first().contains)
        assertNotNull(s.error)
        assertTrue(s.busy.isEmpty())
    }

    @Test
    fun aNewCollectionIsMadeWithTheBookInIt() = runTest(scheduler) {
        val remote = FakeRemote()
        val vm = AddToCollectionViewModel(remote)
        vm.open(book)
        advanceUntilIdle()
        vm.create("  To buy  ")
        advanceUntilIdle()
        val made = vm.state.value!!.choices.single { it.name == "To buy" }
        assertTrue(made.contains)
        vm.close()
        assertNull(vm.state.value)
    }
}
