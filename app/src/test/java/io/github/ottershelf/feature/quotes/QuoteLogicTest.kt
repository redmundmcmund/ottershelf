package io.github.ottershelf.feature.quotes

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.reader.annotations.Cfi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/** The placeholder position, the OCR clean-up and the save path (with a fake server). */
@OptIn(ExperimentalCoroutinesApi::class)
class QuoteLogicTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- QuotePosition ----------------------------------------------------------------------------

    @Test
    fun placeholderHasNoSpineStepAndIsUnique() {
        val a = QuotePosition.newCfi()
        val b = QuotePosition.newCfi()
        assertNotEquals(a, b)
        assertTrue(a.matches(Regex("""epubcfi\(!/4/2\[bookorbit-quote-[0-9a-f]{8}]/1:0\)""")))
        assertTrue(QuotePosition.isQuote(a))
        // The app's own CFI helper (reader sheet ordering, sameRange) treats it as unplaceable.
        assertNull(Cfi.parse(a))
        assertFalse(Cfi.sameRange(a, b))
    }

    @Test
    fun realCfisAreNotQuotes() {
        assertFalse(QuotePosition.isQuote("epubcfi(/6/4!/4/2,/1:0,/1:40)"))
        assertFalse(QuotePosition.isQuote(null))
        assertFalse(QuotePosition.isQuote("epubcfi(/6/4[bookorbit-quote-x]!/4/2/1:0)"))
    }

    @Test
    fun pageLabels() {
        assertEquals("p. 45", QuotePosition.pageLabel(45, null))
        assertEquals("p. 45-47", QuotePosition.pageLabel(45, 47))
        assertEquals("p. 45", QuotePosition.pageLabel(45, 45))
        assertEquals("p. 45", QuotePosition.pageLabel(45, 12))
        assertNull(QuotePosition.pageLabel(null, 47))
        assertNull(QuotePosition.pageLabel(0, null))
        assertEquals("123", QuotePosition.pageDigits("1a2-3"))
        assertEquals("12345", QuotePosition.pageDigits("1234567"))
    }

    // --- QuoteCleanup -----------------------------------------------------------------------------

    private fun line(block: Int, index: Int, text: String) = OcrLine(block, index, text, 0f, 0f, 1f, 1f)

    @Test
    fun joinsLinesAndBrokenWords() {
        val text = QuoteCleanup.join(
            listOf(
                line(0, 0, "It is a truth universally ac-"),
                line(0, 1, "knowledged, that a single  man in"),
                line(0, 2, "possession of a good fortune, must be in want of a wife ."),
            ),
        )
        assertEquals("It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.", text)
    }

    @Test
    fun keepsRealHyphensAndStartsParagraphsAfterSentences() {
        val text = QuoteCleanup.join(
            listOf(
                line(1, 0, "They were Anglo-"),
                line(1, 1, "Saxon kings."),
                line(2, 0, "The ﬁrst of"),
                line(2, 1, "them"),
                line(3, 0, "ruled long."),
            ),
        )
        assertEquals("They were Anglo-Saxon kings.\nThe first of them ruled long.", text)
    }

    @Test
    fun ordersByBlockThenLine() {
        val text = QuoteCleanup.join(listOf(line(0, 1, "world"), line(0, 0, "hello")))
        assertEquals("hello world", text)
    }

    // --- Saving -----------------------------------------------------------------------------------

    private class FakeRemote : QuotesRemote {
        val created = mutableListOf<Pair<Long, CreateQuoteBody>>()
        var failNext: Exception? = null
        var loseAnswer = false
        val stored = mutableListOf<Annotation>()
        val trashed = mutableListOf<Annotation>()
        val patched = mutableListOf<Long>()

        override suspend fun create(bookId: Long, body: CreateQuoteBody): Annotation {
            failNext?.let { failNext = null; throw it }
            created += bookId to body
            val a = Annotation(
                id = 100L + created.size, bookId = bookId, cfi = body.cfi, text = body.text,
                note = body.note, color = body.color, chapterTitle = body.chapterTitle,
            )
            stored += a
            if (loseAnswer) {
                loseAnswer = false
                throw IOException("timeout")
            }
            return a
        }

        override suspend fun newest(bookId: Long, count: Int) = stored.filter { it.bookId == bookId }.reversed().take(count)

        override suspend fun update(bookId: Long, id: Long, note: String?, color: String): Annotation {
            patched += id
            val i = stored.indexOfFirst { it.id == id && it.bookId == bookId }
            return stored[i].copy(note = note, color = color).also { stored[i] = it }
        }

        override suspend fun delete(bookId: Long, id: Long) {
            val a = stored.first { it.id == id && it.bookId == bookId }
            stored -= a
            trashed += a
        }
        override suspend fun book(bookId: Long) = QuoteBook(bookId, "Emma", "Jane Austen", null)
        override suspend fun reading() = listOf(QuoteBook(1, "Emma", "Jane Austen", null))
        override suspend fun search(query: String) = listOf(QuoteBook(2, "Persuasion", "Jane Austen", null))
    }

    private fun viewModel(remote: QuotesRemote, bookId: Long? = 7, saved: SavedStateHandle = SavedStateHandle()) =
        AddQuoteViewModel(remote, settings = null, appContext = null, account = { "a" }, bookId = bookId, title = "Emma", bookFixed = bookId != null, saved = saved)

    @Test
    fun savesExactlyTheDtoFields() = runTest(dispatcher) {
        val remote = FakeRemote()
        val vm = viewModel(remote)
        advanceUntilIdle()
        vm.setText("  Silly things do cease to be silly if they are done by sensible people.  ")
        vm.setPageFrom("45")
        vm.setPageTo("47")
        vm.setNote(" ")
        vm.setColor("#38BDF8")
        vm.save()
        advanceUntilIdle()
        val (bookId, body) = remote.created.single()
        assertEquals(7L, bookId)
        assertEquals("Silly things do cease to be silly if they are done by sensible people.", body.text)
        assertEquals("p. 45-47", body.chapterTitle)
        assertNull(body.note)
        assertEquals("#38BDF8", body.color)
        assertTrue(QuotePosition.isQuote(body.cfi))
        assertTrue(vm.state.value.done)
    }

    @Test
    fun aLostAnswerIsFoundInsteadOfSavedTwice() = runTest(dispatcher) {
        val remote = FakeRemote().apply { loseAnswer = true }
        val vm = viewModel(remote)
        advanceUntilIdle()
        vm.setText("A quote")
        vm.save()
        advanceUntilIdle()
        assertEquals("timeout", vm.state.value.saveError)
        vm.save()
        advanceUntilIdle()
        assertEquals(1, remote.created.size)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun aRetryAfterALostAnswerKeepsWhatWasEditedSince() = runTest(dispatcher) {
        // A new thought and colour: the arrived quote is edited, not sent again.
        val remote = FakeRemote().apply { loseAnswer = true }
        val vm = viewModel(remote)
        advanceUntilIdle()
        vm.setText("A quote")
        vm.save()
        advanceUntilIdle()
        vm.setNote("Later thought")
        vm.setColor("#4ADE80")
        vm.save()
        advanceUntilIdle()
        assertEquals(1, remote.created.size)
        assertEquals(listOf(101L), remote.patched)
        assertEquals("Later thought", remote.stored.single().note)
        assertEquals("#4ADE80", remote.stored.single().color)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun aTypoFixedAfterALostAnswerReplacesTheArrivedQuote() = runTest(dispatcher) {
        val remote = FakeRemote().apply { loseAnswer = true }
        val vm = viewModel(remote)
        advanceUntilIdle()
        vm.setText("A qoute")
        vm.save()
        advanceUntilIdle()
        vm.setText("A quote")
        vm.save()
        advanceUntilIdle()
        // Text can't be edited on the server: the old one goes to the trash, the fixed one is new.
        assertEquals(listOf("A qoute"), remote.trashed.map { it.text })
        assertEquals(listOf("A quote"), remote.stored.map { it.text })
        assertNotEquals(remote.trashed.single().cfi, remote.stored.single().cfi)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun anotherBookPickedAfterALostAnswerLeavesNoHiddenCopy() = runTest(dispatcher) {
        val remote = FakeRemote().apply { loseAnswer = true }
        val vm = viewModel(remote, bookId = null)
        advanceUntilIdle()
        vm.pickBook(QuoteBook(3, "Mansfield Park", null, null))
        vm.setText("A quote")
        vm.save()
        advanceUntilIdle()
        vm.pickBook(QuoteBook(4, "Persuasion", null, null))
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf(3L), remote.trashed.map { it.bookId })
        assertEquals(listOf(4L), remote.stored.map { it.bookId })
    }

    @Test
    fun retryStepsFollowWhatChanged() {
        val body = CreateQuoteBody("epubcfi(!/4/2[bookorbit-quote-00000000]/1:0)", "Text", "#FACC15", note = "n", chapterTitle = "p. 4")
        val lost = LostSave(7, body)
        val found = Annotation(id = 1, bookId = 7, cfi = body.cfi, text = "Text", color = "#facc15", note = "n", chapterTitle = "p. 4")
        assertEquals(RetryStep.Afresh(reuseCfi = true), RetryStep.of(lost, null, 7, body))
        assertEquals(RetryStep.Afresh(reuseCfi = false), RetryStep.of(lost, null, 8, body))
        assertEquals(RetryStep.Done(found), RetryStep.of(lost, found, 7, body))
        assertEquals(RetryStep.Patch(found), RetryStep.of(lost, found, 7, body.copy(note = null)))
        assertEquals(RetryStep.Replace(found), RetryStep.of(lost, found, 7, body.copy(chapterTitle = "p. 5")))
        assertEquals(RetryStep.Replace(found), RetryStep.of(lost, found, 8, body))
    }

    @Test
    fun theFormAndALostSaveSurviveTheProcessBeingKilled() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val remote = FakeRemote().apply { loseAnswer = true }
        val first = viewModel(remote, saved = saved)
        advanceUntilIdle()
        first.setText("A quote")
        first.setPageFrom("12")
        first.setNote("Mine")
        first.save()
        advanceUntilIdle()

        val again = viewModel(remote, saved = saved)
        advanceUntilIdle()
        assertEquals("A quote", again.state.value.text)
        assertEquals("12", again.state.value.pageFrom)
        assertEquals("Mine", again.state.value.note)
        again.save()
        advanceUntilIdle()
        assertEquals(1, remote.created.size) // found, not sent twice
        assertTrue(again.state.value.done)
    }

    @Test
    fun aLateCaptureDoesNotReopenAClosedScan() = runTest(dispatcher) {
        val vm = viewModel(FakeRemote())
        advanceUntilIdle()
        val photo = File.createTempFile("page", ".jpg")
        vm.photoTaken(photo)
        vm.captureFailed("aborted")
        assertNull(vm.state.value.scan)
        assertFalse(photo.exists())
    }

    @Test
    fun aRefusedSaveIsSentAfreshWithANewPosition() = runTest(dispatcher) {
        val remote = FakeRemote().apply { failNext = ApiException(400, "Bad") }
        val vm = viewModel(remote)
        advanceUntilIdle()
        vm.setText("A quote")
        vm.save()
        advanceUntilIdle()
        assertFalse(vm.state.value.done)
        vm.save()
        advanceUntilIdle()
        assertEquals(1, remote.created.size)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun needsABookAndText() = runTest(dispatcher) {
        val remote = FakeRemote()
        val vm = viewModel(remote, bookId = null)
        advanceUntilIdle()
        vm.setText("Words")
        assertFalse(vm.state.value.canSave)
        vm.openPicker()
        advanceUntilIdle()
        assertEquals("Emma", vm.state.value.picker?.books?.single()?.title)
        vm.pickBook(QuoteBook(3, "Mansfield Park", null, null))
        assertTrue(vm.state.value.canSave)
        vm.setText("   ")
        assertFalse(vm.state.value.canSave)
    }
}
