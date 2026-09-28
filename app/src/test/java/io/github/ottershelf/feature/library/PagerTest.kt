package io.github.ottershelf.feature.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PagerTest {

    /** A server list of [total] ids, [failing] makes every request fail. */
    private class FakeList(var total: Int) {
        var failing = false
        val requested = mutableListOf<Int>()
        suspend fun load(page: Int, size: Int): Pair<List<Int>, Int> {
            requested += page
            if (failing) throw IOException("offline")
            val from = page * size
            return (from until minOf(from + size, total)).toList() to total
        }
    }

    @Test
    fun pagesUntilEverythingIsListed() = runTest {
        val server = FakeList(total = 5)
        val pager = Pager(this, pageSize = 2, keyOf = { it: Int -> it }, load = server::load)
        pager.start()
        advanceUntilIdle()
        assertEquals(listOf(0, 1), pager.state.value.items)
        pager.loadMore(); advanceUntilIdle()
        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3, 4), pager.state.value.items)
        assertTrue(pager.state.value.endReached)
        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), server.requested)
    }

    @Test
    fun repeatedItemsAreSkippedSoKeysStayUnique() = runTest {
        var shifted = false
        val pager = Pager(this, pageSize = 3, keyOf = { it: Int -> it }) { page, _ ->
            // The list shifted by one between the pages: page 1 repeats the last item of page 0.
            val items = if (page == 0) listOf(1, 2, 3) else listOf(3, 4, 5).also { shifted = true }
            items to 6
        }
        pager.start(); advanceUntilIdle()
        pager.loadMore(); advanceUntilIdle()
        assertTrue(shifted)
        assertEquals(listOf(1, 2, 3, 4, 5), pager.state.value.items)
    }

    @Test
    fun aFailedFirstLoadShowsTheErrorAndRetryRecovers() = runTest {
        val server = FakeList(total = 3).apply { failing = true }
        val pager = Pager(this, pageSize = 10, keyOf = { it: Int -> it }, load = server::load)
        pager.start(); advanceUntilIdle()
        assertEquals("offline", pager.state.value.error)
        assertFalse(pager.state.value.showRefresh)
        server.failing = false
        pager.retry(); advanceUntilIdle()
        assertNull(pager.state.value.error)
        assertEquals(listOf(0, 1, 2), pager.state.value.items)
    }

    @Test
    fun aFailedRefreshKeepsTheGridAndReportsIt() = runTest {
        val server = FakeList(total = 3)
        val pager = Pager(this, pageSize = 10, keyOf = { it: Int -> it }, load = server::load)
        pager.start(); advanceUntilIdle()
        server.failing = true
        pager.refresh()
        assertTrue(pager.state.value.showRefresh)
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), pager.state.value.items)
        assertNull(pager.state.value.error)
        assertFalse(pager.state.value.refreshing)
        assertEquals("offline", pager.refreshFailures.first())
    }

    @Test
    fun aPageOfNothingButRepeatsAsksForTheNextStraightAway() = runTest {
        val requested = mutableListOf<Int>()
        val pager = Pager(this, pageSize = 3, keyOf = { it: Int -> it }) { page, _ ->
            requested += page
            // The list shifted by a whole page: page 1 repeats page 0, and the grid's end wouldn't move.
            when (page) {
                0, 1 -> listOf(1, 2, 3)
                else -> listOf(4, 5, 6)
            } to 6
        }
        pager.start(); advanceUntilIdle()
        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), requested)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), pager.state.value.items)
        assertFalse(pager.state.value.loading)
    }

    @Test
    fun repeatsStopAfterAFewPages() = runTest {
        val requested = mutableListOf<Int>()
        val pager = Pager(this, pageSize = 3, keyOf = { it: Int -> it }) { page, _ ->
            requested += page
            listOf(1, 2, 3) to 100 // a server ignoring the page
        }
        pager.start(); advanceUntilIdle()
        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3, 4), requested)
        assertFalse(pager.state.value.loading)
    }

    @Test
    fun aFailedNextPageIsAskedForAgain() = runTest {
        val server = FakeList(total = 5)
        val pager = Pager(this, pageSize = 2, keyOf = { it: Int -> it }, load = server::load)
        pager.start(); advanceUntilIdle()
        server.failing = true
        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1), pager.state.value.items)
        assertNull(pager.state.value.error) // items are showing: no error card
        server.failing = false
        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 1), server.requested)
        assertEquals(listOf(0, 1, 2, 3), pager.state.value.items)
    }

    @Test
    fun anEmptyPageBeforeTheCountStopsPaging() = runTest {
        val pager = Pager(this, pageSize = 2, keyOf = { it: Int -> it }) { page, _ ->
            (if (page == 0) listOf(1, 2) else emptyList()) to 10
        }
        pager.start(); advanceUntilIdle()
        pager.loadMore(); advanceUntilIdle()
        assertTrue(pager.state.value.endReached)
        assertEquals(2, pager.state.value.total)
    }

    @Test
    fun restartClearsAndStartsOverFromTheFirstPage() = runTest {
        val server = FakeList(total = 5)
        val pager = Pager(this, pageSize = 2, keyOf = { it: Int -> it }, load = server::load)
        pager.start(); advanceUntilIdle()
        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3), pager.state.value.items)
        server.total = 3
        pager.restart()
        // Nothing of the old list shows while the new one loads.
        assertTrue(pager.state.value.items.isEmpty())
        assertTrue(pager.state.value.showRefresh)
        advanceUntilIdle()
        assertEquals(listOf(0, 1), pager.state.value.items)
        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), pager.state.value.items)
        assertEquals(listOf(0, 1, 0, 1), server.requested)
    }
}
