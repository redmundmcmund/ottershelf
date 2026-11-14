package io.github.ottershelf.feature.collections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteCollectionViewModelTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        CollectionDeletion.noteOwned(setOf(1L, 2L))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        CollectionDeletion.noteOwned(emptySet())
        CollectionDeletion.taken()
    }

    private class FakeRemote : CollectionsRemote {
        val deleted = mutableListOf<Long>()
        var refuse: Exception? = null
        override suspend fun choices(bookIds: List<Long>) = emptyList<CollectionChoice>()
        override suspend fun add(collectionId: Long, bookIds: List<Long>) = Unit
        override suspend fun remove(collectionId: Long, bookIds: List<Long>) = Unit
        override suspend fun create(name: String) = 0L
        override suspend fun delete(collectionId: Long) {
            refuse?.let { throw it }
            deleted += collectionId
        }
    }

    private val holiday = CollectionToDelete(1, "Holiday")

    @Test
    fun onlyTheUsersOwnCollectionsCanBeAskedFor() {
        CollectionDeletion.ask(3, "Someone else's")
        assertNull(CollectionDeletion.request.value)
        CollectionDeletion.ask(1, "Holiday")
        assertEquals(holiday, CollectionDeletion.request.value)
    }

    @Test
    fun confirmingDeletesItAndReportsIt() = runTest(scheduler) {
        val remote = FakeRemote()
        val vm = DeleteCollectionViewModel(remote)
        vm.open(holiday)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf(1L), remote.deleted)
        assertNull(vm.state.value)
        assertEquals(holiday, vm.deleted.first())
        assertFalse(1L in CollectionDeletion.owned.value)
        assertTrue(2L in CollectionDeletion.owned.value)
    }

    @Test
    fun aRefusalKeepsTheDialogWithTheReason() = runTest(scheduler) {
        val remote = FakeRemote().apply { refuse = IOException("Cannot modify this collection") }
        val vm = DeleteCollectionViewModel(remote)
        vm.open(holiday)
        vm.confirm()
        advanceUntilIdle()
        val s = vm.state.value!!
        assertFalse(s.deleting)
        assertEquals("Cannot modify this collection", s.error)
        assertTrue(1L in CollectionDeletion.owned.value)
    }

    @Test
    fun cancellingDoesNothing() = runTest(scheduler) {
        val remote = FakeRemote()
        val vm = DeleteCollectionViewModel(remote)
        vm.open(holiday)
        vm.dismiss()
        advanceUntilIdle()
        assertNull(vm.state.value)
        assertTrue(remote.deleted.isEmpty())
    }
}
