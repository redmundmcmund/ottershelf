package io.github.ottershelf.feature.scan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.model.ReadStatusInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The scanner opened to pick a book (the Calendar's "Add a book"): what it finds is handed back, not opened. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanPickTest {

    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val dracula = Isbn.of13("9780306406157")!!

    private fun card(id: Long) = ScanCard(
        id = id, title = "Dracula", authors = listOf("Bram Stoker"), hasCover = true,
        files = listOf(BookFile(id = id * 10, format = "epub", role = "primary")), readStatus = ReadStatusInfo("want_to_read"),
    )

    private class Remote(private val cards: List<ScanCard>) : ScanRemote {
        override suspend fun byIsbn(isbn: Isbn) = cards
        override suspend fun byTitle(title: String) = emptyList<ScanCard>()
        override fun lookUp(isbn: Isbn): Flow<MetadataCandidate> = flow { }
        override fun cover(card: ScanCard): Any? = "cover:${card.id}"
    }

    @Test
    fun oneCopyIsHandedBack() = runTest(scheduler) {
        val vm = ScanViewModel(Remote(listOf(card(7))), forTimer = false, canRequest = { true }, pick = true)
        assertTrue(vm.state.value.pick)
        var nav: ScanNav? = null
        backgroundScope.launch { nav = vm.navigation.first() }
        vm.detected(dracula)
        advanceUntilIdle()
        runCurrent()
        val picked = nav as ScanNav.Picked
        assertEquals(7L, picked.book.id)
        assertEquals(listOf("epub"), picked.book.formats)
        assertEquals("want_to_read", picked.book.status)
    }

    @Test
    fun aCopyChosenFromSeveralIsHandedBack() = runTest(scheduler) {
        val vm = ScanViewModel(Remote(listOf(card(7), card(8))), forTimer = false, canRequest = { true }, pick = true)
        vm.detected(dracula)
        advanceUntilIdle()
        val choose = vm.state.value.result as ScanResult.Choose
        var nav: ScanNav? = null
        backgroundScope.launch { nav = vm.navigation.first() }
        runCurrent()
        vm.pick(choose.books[1])
        runCurrent()
        assertEquals(8L, (nav as ScanNav.Picked).book.id)
    }

    @Test
    fun thePickWaitsForItsScreenOnce() {
        ScanPicks.clear()
        val book = ScanBook(3, "Dracula")
        ScanPicks.hand(book)
        assertEquals(book, ScanPicks.take())
        assertNull(ScanPicks.take())
    }
}
