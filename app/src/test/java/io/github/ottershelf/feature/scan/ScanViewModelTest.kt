package io.github.ottershelf.feature.scan

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** What a scanned or typed ISBN leads to, against a fake server. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val remote = FakeRemote()
    private var now = 1_000_000L
    private val remembered = ArrayList<ScanCard>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val dracula = Isbn.of13("9780306406157")!!
    private val other = Isbn.of13("9780804429573")!!

    private fun card(id: Long, title: String = "Dracula", format: String = "epub", authors: List<String> = listOf("Bram Stoker")) = ScanCard(
        id = id, title = title, authors = authors, hasCover = true,
        files = listOf(BookFile(id = id * 10, format = format, role = "primary")), readStatus = ReadStatusInfo("reading"), publishedYear = 1897,
    )

    private fun viewModel(forTimer: Boolean = false, canRequest: Boolean = true, saved: SavedStateHandle = SavedStateHandle()) =
        ScanViewModel(remote, forTimer, { canRequest }, saved, remember = { remembered += it }, clock = { now })

    /** The next place the scan asked to go, or null when it asked for none. */
    private fun TestScope.nextNav(vm: ScanViewModel): ScanNav? {
        var got: ScanNav? = null
        val job = backgroundScope.launch { got = vm.navigation.first() }
        runCurrent()
        job.cancel()
        return got
    }

    @Test
    fun oneCopyOpensItsPage() = runTest(scheduler) {
        remote.byIsbn[dracula] = listOf(card(7))
        val vm = viewModel()
        assertTrue(vm.detected(dracula))
        assertEquals(ScanResult.Looking(dracula), vm.state.value.result)
        advanceUntilIdle()
        val result = vm.state.value.result as ScanResult.Opening
        assertEquals(7L, result.book.id)
        assertEquals(listOf("epub"), result.book.formats)
        assertEquals("reading", result.book.status)
        assertEquals("cover:7", result.book.cover)
        assertEquals(ScanNav.OpenBook(7), nextNav(vm))
        assertEquals(listOf(7L), remembered.map { it.id }) // for the book page's preview
        assertEquals(listOf(dracula), remote.asked)
    }

    @Test
    fun forTheTimerItOpensTheTimer() = runTest(scheduler) {
        remote.byIsbn[dracula] = listOf(card(7))
        val vm = viewModel(forTimer = true)
        vm.detected(dracula)
        advanceUntilIdle()
        assertEquals(ScanNav.OpenTimer(7), nextNav(vm))
    }

    @Test
    fun severalCopiesAreOfferedAndThePickOpens() = runTest(scheduler) {
        remote.byIsbn[dracula] = listOf(card(7), card(8, format = "pdf"))
        val vm = viewModel(forTimer = true)
        vm.detected(dracula)
        advanceUntilIdle()
        val choose = vm.state.value.result as ScanResult.Choose
        assertEquals(listOf(7L, 8L), choose.books.map { it.id })
        assertNull(nextNav(vm)) // nothing opens by itself
        vm.pick(choose.books[1])
        assertEquals(ScanNav.OpenTimer(8), nextNav(vm))
        assertEquals(listOf(8L), remembered.map { it.id })
    }

    @Test
    fun closingTheScannerIgnoresWhatItFindsAfter() = runTest(scheduler) {
        remote.byIsbn[dracula] = listOf(card(7))
        remote.byIsbn[other] = listOf(card(8), card(9))
        val vm = viewModel()
        // A barcode read just before the user tapped X: its lookup is still out when the user closes.
        assertTrue(vm.detected(dracula))
        vm.leave()
        advanceUntilIdle()
        assertNull(nextNav(vm))
        // The camera keeps reading frames while the screen fades out: none is taken.
        assertFalse(vm.detected(other))
        advanceUntilIdle()
        assertEquals(listOf<Isbn>(), remote.asked)
        assertNull(nextNav(vm))
    }

    @Test
    fun aChoiceShowingWhenTheUserClosesOpensNothing() = runTest(scheduler) {
        remote.byIsbn[dracula] = listOf(card(7), card(8))
        val vm = viewModel()
        vm.detected(dracula)
        advanceUntilIdle()
        val choose = vm.state.value.result as ScanResult.Choose
        vm.leave()
        vm.pick(choose.books[0])
        assertNull(nextNav(vm))
    }

    @Test
    fun notInTheLibraryShowsWhatTheProvidersKnowAndOtherEditions() = runTest(scheduler) {
        val providers = Channel<MetadataCandidate>(Channel.UNLIMITED)
        remote.lookUps[dracula] = providers.consumeAsFlow()
        remote.byTitle["Dracula"] = listOf(card(20), card(21, title = "Dracula's Guest"), card(22, authors = listOf("Someone Else")))
        val vm = viewModel()
        vm.detected(dracula)
        advanceUntilIdle()
        var missing = vm.state.value.result as ScanResult.Missing
        assertTrue(missing.lookingUp)
        assertNull(missing.candidate)
        assertTrue(missing.canRequest)

        providers.send(MetadataCandidate(provider = "google", title = "Dracula", authors = listOf("Bram Stoker")))
        advanceUntilIdle()
        missing = vm.state.value.result as ScanResult.Missing
        assertEquals("Dracula", missing.candidate?.title)
        // Only the same title by the same author.
        assertEquals(listOf(20L), missing.editions.map { it.id })
        assertEquals(listOf("Dracula"), remote.titleSearches)

        // A better one (this very ISBN) comes in; same title, so no second search.
        providers.send(MetadataCandidate(provider = "openlibrary", title = "Dracula", isbn13 = "9780306406157", coverUrl = "https://c", authors = listOf("Bram Stoker")))
        providers.close()
        advanceUntilIdle()
        missing = vm.state.value.result as ScanResult.Missing
        assertEquals("openlibrary", missing.candidate?.provider)
        assertFalse(missing.lookingUp)
        assertEquals(listOf("Dracula"), remote.titleSearches)

        vm.request()
        assertEquals(ScanNav.Request("9780306406157", "Dracula", "Bram Stoker"), nextNav(vm))

        // A copy from the other editions opens like any match.
        vm.pick(missing.editions.single())
        assertEquals(ScanNav.OpenBook(20), nextNav(vm))
    }

    @Test
    fun anIsbnNoProviderKnowsOffersBookRequestsByTitle() = runTest(scheduler) {
        val vm = viewModel(canRequest = false)
        vm.detected(dracula)
        advanceUntilIdle()
        val missing = vm.state.value.result as ScanResult.Missing
        assertFalse(missing.lookingUp)
        assertFalse(missing.lookupFailed)
        assertNull(missing.candidate)
        assertFalse(missing.canRequest)
        assertTrue(remote.titleSearches.isEmpty())
        vm.request()
        assertEquals(ScanNav.OpenRequests, nextNav(vm))
    }

    @Test
    fun aFailedProviderLookupStillSaysNotInTheLibrary() = runTest(scheduler) {
        remote.lookUps[dracula] = flow { throw IOException("offline") }
        val vm = viewModel()
        vm.detected(dracula)
        advanceUntilIdle()
        val missing = vm.state.value.result as ScanResult.Missing
        assertFalse(missing.lookingUp)
        assertTrue(missing.lookupFailed)
    }

    @Test
    fun aFailedSearchCanBeRetried() = runTest(scheduler) {
        remote.fail = IOException("Unable to resolve host")
        val vm = viewModel()
        vm.detected(dracula)
        advanceUntilIdle()
        assertEquals(ScanResult.Failed(dracula, "Unable to resolve host"), vm.state.value.result)
        remote.fail = null
        remote.byIsbn[dracula] = listOf(card(7))
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.result is ScanResult.Opening)
    }

    @Test
    fun theCameraIsIgnoredWhileAResultShowsAndTheDismissedBookForAMoment() = runTest(scheduler) {
        remote.byIsbn[dracula] = listOf(card(7), card(8))
        val vm = viewModel()
        assertTrue(vm.detected(dracula))
        advanceUntilIdle()
        assertFalse(vm.state.value.analysing)
        assertFalse(vm.detected(other)) // a result is showing
        vm.scanAgain()
        assertTrue(vm.state.value.analysing)
        // Still in view: not reopened at once...
        now += 1_000
        assertFalse(vm.detected(dracula))
        // ...but another book is taken,
        assertTrue(vm.detected(other))
        advanceUntilIdle()
        vm.scanAgain()
        // and the first one again once the moment has passed.
        now += ScanViewModel.RESCAN_MS
        assertTrue(vm.detected(dracula))
    }

    @Test
    fun typingAnIsbn() = runTest(scheduler) {
        remote.byIsbn[dracula] = listOf(card(7))
        val vm = viewModel()
        vm.openManual()
        assertFalse(vm.state.value.analysing)
        vm.setManual("978-0-306-40615-8")
        assertEquals(IsbnInput.BadCheckDigit(final = true), vm.state.value.manual?.problem)
        vm.setManual("978-0-306-4061")
        assertNull(vm.state.value.manual?.problem) // still typing
        vm.submitManual()
        assertEquals(IsbnInput.Incomplete(11), vm.state.value.manual?.problem) // flagged once the user asks
        assertTrue(vm.state.value.manual!!.submitted)

        vm.setManual("0-306-40615-2")
        assertFalse(vm.state.value.manual!!.offersX)
        vm.setManual("0-8044-2957")
        assertTrue(vm.state.value.manual!!.offersX)
        vm.setManual("0-8044-2957X") // the X key (the sheet types it at the cursor: ManualSheetTest)
        assertEquals("0-8044-2957X", vm.state.value.manual?.text)
        assertEquals(other, vm.state.value.manual?.isbn)

        vm.setManual("0-306-40615-2")
        vm.submitManual()
        assertNull(vm.state.value.manual)
        advanceUntilIdle()
        assertEquals(listOf(dracula), remote.asked) // the ISBN-10 is looked up in both forms
        assertEquals(ScanNav.OpenBook(7), nextNav(vm))
    }

    @Test
    fun aProcessDeathLooksTheIsbnUpAgain() = runTest(scheduler) {
        remote.byIsbn[dracula] = listOf(card(7), card(8))
        val saved = SavedStateHandle()
        viewModel(saved = saved).detected(dracula)
        advanceUntilIdle()
        val again = viewModel(saved = SavedStateHandle(mapOf("isbn" to saved.get<String>("isbn"))))
        advanceUntilIdle()
        assertEquals(listOf(7L, 8L), (again.state.value.result as ScanResult.Choose).books.map { it.id })

        val typing = viewModel(saved = SavedStateHandle(mapOf("manual" to "978-0-30")))
        assertEquals("978-0-30", typing.state.value.manual?.text)
    }

    private class FakeRemote : ScanRemote {
        val byIsbn = HashMap<Isbn, List<ScanCard>>()
        val byTitle = HashMap<String, List<ScanCard>>()
        val lookUps = HashMap<Isbn, Flow<MetadataCandidate>>()
        val asked = ArrayList<Isbn>()
        val titleSearches = ArrayList<String>()
        var fail: Exception? = null

        override suspend fun byIsbn(isbn: Isbn): List<ScanCard> {
            fail?.let { throw it }
            asked += isbn
            return byIsbn[isbn].orEmpty()
        }

        override suspend fun byTitle(title: String): List<ScanCard> {
            titleSearches += title
            return byTitle[title].orEmpty()
        }

        override fun lookUp(isbn: Isbn): Flow<MetadataCandidate> = lookUps[isbn] ?: flow { }

        override fun cover(card: ScanCard): Any? = "cover:${card.id}"
    }
}
