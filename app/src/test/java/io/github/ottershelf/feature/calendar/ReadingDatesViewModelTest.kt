package io.github.ottershelf.feature.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.FakeTrackingRemote
import io.github.ottershelf.core.tracking.MemoryStore
import io.github.ottershelf.core.tracking.Patch
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.core.tracking.TrackingQueue
import io.github.ottershelf.core.tracking.TrackingRepository
import io.github.ottershelf.feature.history.SavedReading
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * The picker and the dates sheet against a fake server, through the real tracker: what is sent
 * (bodies checked against the server's DTOs), what is fixed and what added, and what goes back to
 * the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingDatesViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val today = LocalDate.of(2026, 9, 26)
    private val day = LocalDate.of(2024, 9, 3)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The library's side the tracker doesn't cover: the query, the books and their readings. */
    private class Library {
        val queries = mutableListOf<PickerQuery>()
        var results: List<PickerBook> = emptyList()
        val books = mutableMapOf<Long, BookDetail>()
        val attempts = mutableMapOf<Long, List<ReadingAttempt>>()
        var scanned: PickerBook? = null

        /** `GET books/:id` fails (offline). */
        var bookFails = false

        /** The next add reaches the server and is stored, then this is thrown instead of its answer. */
        var loseNextCreate: Exception? = null

        /** The reading ids the sheet sent a PATCH for. */
        val updatedIds = mutableListOf<Long>()
    }

    private class Setup(
        val vm: ReadingDatesViewModel,
        val server: FakeTrackingRemote,
        val library: Library,
        val saved: List<SavedReading>,
        val readingChanges: ReadingChanges,
    )

    private fun TestScope.setup(): Setup {
        val server = FakeTrackingRemote()
        val library = Library()
        val readingChanges = ReadingChanges()
        val tracking = TrackingRepository(
            remote = server,
            queue = TrackingQueue(MemoryStore()),
            accountKey = { "books.example_reader" },
            account = MutableStateFlow(server.user),
            updateUser = {},
            readingChanges = readingChanges,
            scope = backgroundScope,
            onQueued = {},
        )
        val remote = ApiReadingDatesRemote(
            tracking = tracking,
            query = { q ->
                library.queries += q
                library.results
            },
            bookOf = { id -> if (library.bookFails) error("offline") else library.books[id] ?: BookDetail(id = id) },
            overrideStatus = readingChanges::overrideStatus,
            attemptsOf = { id -> library.attempts[id].orEmpty() },
        )
        val lossy = object : ReadingDatesRemote by remote {
            override suspend fun create(bookId: Long, startedOn: LocalDate?, endedOn: LocalDate, outcome: String): ReadingAttempt {
                val made = remote.create(bookId, startedOn, endedOn, outcome)
                val lost = library.loseNextCreate ?: return made
                library.loseNextCreate = null
                val stored = made.copy(startedOn = startedOn?.toString(), endedOn = endedOn.toString(), outcome = outcome, origin = "manual")
                library.attempts[bookId] = library.attempts[bookId].orEmpty() + stored
                throw lost
            }

            override suspend fun update(bookId: Long, attemptId: Long, startedOn: Patch<LocalDate>, endedOn: Patch<LocalDate>, outcome: Patch<String>): ReadingAttempt {
                library.updatedIds += attemptId
                return remote.update(bookId, attemptId, startedOn, endedOn, outcome)
            }
        }
        val vm = ReadingDatesViewModel(
            remote = lossy,
            today = { today },
            takeScanned = { library.scanned.also { library.scanned = null } },
            clearScanned = { library.scanned = null },
        )
        val saved = mutableListOf<SavedReading>()
        backgroundScope.launch { vm.saved.collect { saved += it } }
        runCurrent()
        return Setup(vm, server, library, saved, readingChanges)
    }

    private val lostWorld = PickerBook(7, "The Lost World", listOf("Arthur Conan Doyle"), formats = listOf("epub"), status = "read")

    private fun detail(id: Long, status: String?) = BookDetail(
        id = id,
        title = "The Lost World",
        authors = listOf(AuthorRef(id = 1, name = "Arthur Conan Doyle")),
        coverSource = "embedded",
        updatedAt = "2026-01-01T00:00:00Z",
        readStatus = status?.let { ReadStatusInfo(status = it, updatedAt = "2026-09-26T10:00:00Z") },
    )

    // --- the server's DTOs ----------------------------------------------------------------------

    private val date = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val outcomes = setOf("completed", "skimmed", "abandoned")

    /** CreateReadingAttemptDto: startedOn?, endedOn? (dates or null), outcome (required); nothing else. */
    private fun assertCreateBody(body: JsonObject) {
        assertTrue(body.keys.toString(), setOf("startedOn", "endedOn", "outcome").containsAll(body.keys))
        assertTrue((body["outcome"] as JsonPrimitive).content in outcomes)
        listOf("startedOn", "endedOn").forEach { k -> body[k]?.let { assertTrue(it == JsonNull || date.matches((it as JsonPrimitive).content)) } }
    }

    /** UpdateReadingAttemptDto: startedOn?, endedOn?, outcome? (each may be null); nothing else. */
    private fun assertUpdateBody(body: JsonObject) {
        assertTrue(body.keys.toString(), setOf("startedOn", "endedOn", "outcome").containsAll(body.keys))
        body["outcome"]?.let { assertTrue(it == JsonNull || (it as JsonPrimitive).content in outcomes) }
        listOf("startedOn", "endedOn").forEach { k -> body[k]?.let { assertTrue(it == JsonNull || date.matches((it as JsonPrimitive).content)) } }
    }

    // --- the picker -----------------------------------------------------------------------------

    @Test
    fun thePickerShowsWhatTheUserIsReadingThenSearchesOnceTheUserStopsTyping() = runTest(scheduler) {
        val s = setup()
        s.library.results = listOf(lostWorld)
        s.vm.openPicker(day)
        advanceUntilIdle()
        assertEquals(PickerQueries.readingNow(), s.library.queries.single())
        assertEquals(listOf(lostWorld), s.vm.state.value.picker!!.books)
        assertEquals(day, s.vm.state.value.picker!!.day)

        s.vm.search("le")
        s.vm.search("conan doyle")
        advanceTimeBy(200)
        assertEquals(1, s.library.queries.size)
        advanceUntilIdle()
        assertEquals(PickerQueries.search("conan doyle"), s.library.queries.last())
        assertEquals(2, s.library.queries.size)
        assertFalse(s.vm.state.value.picker!!.loading)
    }

    @Test
    fun theToolbarsPickerIsForToday() = runTest(scheduler) {
        val s = setup()
        s.vm.openPicker(null)
        assertEquals(today, s.vm.state.value.picker!!.day)
        s.vm.openPicker(today.plusDays(2))
        assertEquals(today, s.vm.state.value.picker!!.day)
    }

    @Test
    fun theScannerHandsTheBookBack() = runTest(scheduler) {
        val s = setup()
        s.vm.openPicker(day)
        advanceUntilIdle()
        s.vm.scan()
        assertTrue(s.vm.state.value.scanning)
        // Back without a book: the picker again.
        s.vm.onResume()
        assertFalse(s.vm.state.value.scanning)
        assertNotNull(s.vm.state.value.picker)

        s.vm.scan()
        s.library.scanned = lostWorld
        s.vm.onResume()
        assertNull(s.vm.state.value.picker)
        assertEquals(lostWorld.id, s.vm.state.value.dates!!.book.id)
        assertEquals(day, s.vm.state.value.dates!!.day)
        // Resuming again later takes nothing.
        s.vm.onResume()
        assertEquals(lostWorld.id, s.vm.state.value.dates!!.book.id)
    }

    // --- fixing or adding -----------------------------------------------------------------------

    @Test
    fun aBookMarkedReadByHandGetsItsDates() = runTest(scheduler) {
        val s = setup()
        val marked = ReadingAttempt(id = 3, bookId = 7, endedOn = "2026-09-25", outcome = AttemptOutcome.COMPLETED, origin = "manual")
        s.library.attempts[7] = listOf(marked)
        s.library.books[7] = detail(7, "read")
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        assertTrue(s.vm.state.value.dates!!.loading)
        assertFalse(s.vm.state.value.dates!!.canSave)
        advanceUntilIdle()
        val dates = s.vm.state.value.dates!!
        assertEquals(DatesTarget.Existing(3), dates.target)
        assertEquals(DatesForm(null, day, AttemptOutcome.COMPLETED), dates.form)
        assertNull(dates.statusChange)

        s.vm.setStarted(LocalDate.of(2024, 8, 12))
        s.library.attempts[7] = listOf(marked.copy(startedOn = "2024-08-12", endedOn = "2024-09-03"))
        s.vm.save()
        advanceUntilIdle()
        runCurrent()
        val body = s.server.attemptBodies.single()
        assertUpdateBody(body)
        assertEquals("""{"startedOn":"2024-08-12","endedOn":"2024-09-03","outcome":"completed"}""", body.toString())
        assertTrue(s.server.statusBodies.isEmpty())
        // Closed, and the book read again for the screen.
        assertNull(s.vm.state.value.dates)
        val saved = s.saved.single()
        assertEquals(7L, saved.card.id)
        assertEquals("read", saved.card.readStatus?.status)
        assertEquals(listOf("Arthur Conan Doyle"), saved.card.authors)
        assertTrue(saved.card.hasCover)
        assertEquals("2024-08-12", saved.attempts.single().startedOn)
    }

    @Test
    fun aReadingIsAddedAndAnUnreadBookBecomesRead() = runTest(scheduler) {
        val s = setup()
        s.library.books[7] = detail(7, "unread")
        s.vm.openPicker(day)
        s.vm.pick(lostWorld.copy(status = "unread"))
        advanceUntilIdle()
        val dates = s.vm.state.value.dates!!
        assertEquals(DatesTarget.New, dates.target)
        assertEquals("read", dates.statusChange)
        assertFalse(dates.staysWanted)
        s.vm.save()
        advanceUntilIdle()
        val create = s.server.attemptBodies.single()
        assertCreateBody(create)
        // No start: left out, as the book page's past read does.
        assertEquals("""{"endedOn":"2024-09-03","outcome":"completed"}""", create.toString())
        // SetStatusDto: the status alone, so the reading just added keeps its dates.
        assertEquals("""{"status":"read"}""", s.server.statusBodies.single().toString())
    }

    @Test
    fun aReadingAddedToABookOnWantToReadLeavesItThere() = runTest(scheduler) {
        val s = setup()
        s.library.books[7] = detail(7, "want_to_read")
        s.vm.openPicker(day)
        s.vm.pick(lostWorld.copy(status = "want_to_read"))
        advanceUntilIdle()
        s.vm.setOutcome(AttemptOutcome.ABANDONED)
        val dates = s.vm.state.value.dates!!
        assertNull(dates.statusChange)
        assertTrue(dates.staysWanted)
        s.vm.save()
        advanceUntilIdle()
        assertEquals("""{"endedOn":"2024-09-03","outcome":"abandoned"}""", s.server.attemptBodies.single().toString())
        // No status write: it isn't moved to Abandoned (nor projected to the user's Kobo as that).
        assertTrue(s.server.statusBodies.isEmpty())
    }

    @Test
    fun theUserCanFixAnotherReadingOrAddOne() = runTest(scheduler) {
        val s = setup()
        val first = ReadingAttempt(id = 2, bookId = 7, startedOn = "2019-01-01", endedOn = "2019-02-01", outcome = AttemptOutcome.COMPLETED, totalSessions = 20)
        val gaveUp = ReadingAttempt(id = 5, bookId = 7, startedOn = "2023-03-01", endedOn = "2023-03-09", outcome = AttemptOutcome.ABANDONED)
        s.library.attempts[7] = listOf(gaveUp, first)
        s.library.books[7] = detail(7, "abandoned")
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        assertEquals(DatesTarget.New, s.vm.state.value.dates!!.target)
        // Adding a finished reading after the given-up one: read.
        assertEquals("read", s.vm.state.value.dates!!.statusChange)

        s.vm.chooseTarget(DatesTarget.Existing(5))
        val dates = s.vm.state.value.dates!!
        // Its end moves to the day; its start stays, before it.
        assertEquals(DatesForm(LocalDate.of(2023, 3, 1), day, AttemptOutcome.ABANDONED), dates.form)
        assertNull(dates.problem)
        assertEquals("abandoned", dates.plan.statusAfter)
        assertEquals("abandoned", s.vm.state.value.dates!!.status)
        assertNull(dates.statusChange)
        s.vm.setOutcome(AttemptOutcome.SKIMMED) // not offered for a reading given up
        assertEquals(AttemptOutcome.ABANDONED, s.vm.state.value.dates!!.form.outcome)
        s.vm.save()
        advanceUntilIdle()
        val body = s.server.attemptBodies.single()
        assertUpdateBody(body)
        assertEquals("""{"startedOn":"2023-03-01","endedOn":"2024-09-03","outcome":"abandoned"}""", body.toString())
    }

    @Test
    fun readingsThatArriveAfterTheUserEditedDontResetTheFieldsButStillPickTheReading() = runTest(scheduler) {
        val s = setup()
        s.library.attempts[7] = listOf(ReadingAttempt(id = 3, bookId = 7, endedOn = "2026-09-25", outcome = AttemptOutcome.COMPLETED))
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        // The fields can be edited while the readings load.
        s.vm.setOutcome(AttemptOutcome.ABANDONED)
        advanceUntilIdle()
        val dates = s.vm.state.value.dates!!
        // The finish marked by hand gets the dates, as it would have untouched; the user's outcome stays.
        assertEquals(DatesTarget.Existing(3), dates.target)
        assertEquals(DatesForm(null, day, AttemptOutcome.ABANDONED), dates.form)
        assertEquals(1, dates.attempts.size)
    }

    @Test
    fun anEndDateSetWhileLoadingStillFinishesTheReadingGoingOn() = runTest(scheduler) {
        val s = setup()
        val open = ReadingAttempt(id = 9, bookId = 7, startedOn = "2024-08-20", outcome = null, totalSessions = 4)
        s.library.attempts[7] = listOf(open)
        s.library.books[7] = detail(7, "reading")
        s.vm.openPicker(null)
        s.vm.pick(lostWorld.copy(status = "reading"))
        s.vm.setEnded(day)
        advanceUntilIdle()
        val dates = s.vm.state.value.dates!!
        assertEquals(DatesTarget.Existing(9), dates.target)
        // The user's end date, the reading's own start.
        assertEquals(DatesForm(LocalDate.of(2024, 8, 20), day, AttemptOutcome.COMPLETED), dates.form)
        s.vm.save()
        advanceUntilIdle()
        assertEquals("""{"startedOn":"2024-08-20","endedOn":"2024-09-03","outcome":"completed"}""", s.server.attemptBodies.single().toString())
    }

    @Test
    fun movingTheEndOfTodaysSheetBackFixesAFinishMarkedByHandEarlier() = runTest(scheduler) {
        val s = setup()
        // Marked read by hand on 1 June: {null, 2026-06-01, completed, manual}, no sessions.
        val marked = ReadingAttempt(id = 3, bookId = 7, endedOn = "2026-06-01", outcome = AttemptOutcome.COMPLETED, origin = "manual")
        s.library.attempts[7] = listOf(marked)
        s.library.books[7] = detail(7, "read")
        // The toolbar's +: the sheet is for today.
        s.vm.openPicker(null)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        // Finished again today would be a reread.
        assertEquals(DatesTarget.New, s.vm.state.value.dates!!.target)

        // The user finished it on 3 Sept 2024: that is the finish the user marked in June.
        s.vm.setEnded(day)
        assertEquals(DatesTarget.Existing(3), s.vm.state.value.dates!!.target)
        assertEquals(DatesForm(null, day, AttemptOutcome.COMPLETED), s.vm.state.value.dates!!.form)
        // After the day the user marked it: another reading again.
        s.vm.setEnded(LocalDate.of(2026, 7, 1))
        assertEquals(DatesTarget.New, s.vm.state.value.dates!!.target)
        s.vm.setEnded(day)

        s.vm.save()
        advanceUntilIdle()
        val body = s.server.attemptBodies.single()
        assertUpdateBody(body)
        assertEquals("""{"startedOn":null,"endedOn":"2024-09-03","outcome":"completed"}""", body.toString())
    }

    @Test
    fun aReadingTheUserChoseStaysWhenTheUserMovesTheEnd() = runTest(scheduler) {
        val s = setup()
        val marked = ReadingAttempt(id = 3, bookId = 7, endedOn = "2026-06-01", outcome = AttemptOutcome.COMPLETED, origin = "manual")
        s.library.attempts[7] = listOf(marked)
        s.library.books[7] = detail(7, "read")
        s.vm.openPicker(null)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        // "Add another reading", chosen even though it was already selected.
        s.vm.chooseTarget(DatesTarget.New)
        s.vm.setEnded(day)
        assertEquals(DatesTarget.New, s.vm.state.value.dates!!.target)
        s.vm.save()
        advanceUntilIdle()
        assertCreateBody(s.server.attemptBodies.single())
    }

    @Test
    fun historysEditKeepsItsReadingAndCanLeaveItOpen() = runTest(scheduler) {
        val s = setup()
        val open = ReadingAttempt(id = 9, bookId = 7, startedOn = "2026-09-01", outcome = null)
        val placeholder = ReadingAttempt(id = 1, bookId = 7, outcome = AttemptOutcome.COMPLETED, origin = "migration")
        s.library.attempts[7] = listOf(open, placeholder)
        s.library.books[7] = detail(7, "rereading")
        s.vm.edit(lostWorld, open)
        advanceUntilIdle()
        val dates = s.vm.state.value.dates!!
        assertTrue(dates.fixed)
        assertEquals(DatesTarget.Existing(9), dates.target)
        assertEquals(null, dates.form.outcome)
        assertEquals(listOf(AttemptOutcome.COMPLETED, AttemptOutcome.ABANDONED, null), dates.outcomes)
        // The choice between readings isn't the user's here.
        s.vm.chooseTarget(DatesTarget.Existing(1))
        assertEquals(DatesTarget.Existing(9), s.vm.state.value.dates!!.target)

        s.vm.setStarted(LocalDate.of(2026, 8, 30))
        assertNull(s.vm.state.value.dates!!.statusChange)
        s.vm.save()
        advanceUntilIdle()
        val body = s.server.attemptBodies.single()
        assertUpdateBody(body)
        assertEquals("""{"startedOn":"2026-08-30"}""", body.toString())
    }

    @Test
    fun theLibrarySeesTheStatusTheServerRebuiltAfterTheSave() = runTest(scheduler) {
        val s = setup()
        val open = ReadingAttempt(id = 9, bookId = 7, startedOn = "2024-08-20", outcome = null, totalSessions = 4)
        s.library.attempts[7] = listOf(open)
        s.library.books[7] = detail(7, "reading")
        // The book page (or the quick view) showed it this session: the grids show "reading" over what they loaded.
        s.readingChanges.overrideStatus(7, "reading")
        s.vm.openPicker(day)
        s.vm.pick(lostWorld.copy(status = "reading"))
        advanceUntilIdle()
        assertEquals(DatesTarget.Existing(9), s.vm.state.value.dates!!.target)
        // Finishing the open reading: the server makes it read.
        s.library.books[7] = detail(7, "read")
        s.library.attempts[7] = listOf(open.copy(endedOn = "2024-09-03", outcome = AttemptOutcome.COMPLETED))
        s.vm.save()
        advanceUntilIdle()
        assertTrue(s.server.statusBodies.isEmpty())
        assertEquals("read", s.readingChanges.statusOf(7, "reading"))
    }

    @Test
    fun withoutTheBookReadAgainTheStatusIsTheOneTheServerWorksOut() = runTest(scheduler) {
        val s = setup()
        val finished = ReadingAttempt(id = 5, bookId = 7, startedOn = "2025-01-02", endedOn = "2025-02-03", outcome = AttemptOutcome.COMPLETED, totalSessions = 12)
        s.library.attempts[7] = listOf(finished)
        s.library.books[7] = detail(7, "read")
        s.readingChanges.overrideStatus(7, "read")
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        // An older reading given up, added now: the newest by id decides.
        s.vm.setOutcome(AttemptOutcome.ABANDONED)
        assertEquals("abandoned", s.vm.state.value.dates!!.statusChange)
        s.library.bookFails = true
        s.vm.save()
        advanceUntilIdle()
        assertCreateBody(s.server.attemptBodies.single())
        assertTrue(s.saved.isEmpty())
        assertEquals("abandoned", s.readingChanges.statusOf(7, "read"))
    }

    @Test
    fun historysEditTakesTheServersDatesForTheFieldsTheUserLeaves() = runTest(scheduler) {
        val s = setup()
        // History's copy: finished 3 Sept, no start. The server has had a start filled in since
        // (the web's reading log, a Hardcover import).
        val cached = ReadingAttempt(id = 4, bookId = 7, endedOn = "2026-09-03", outcome = AttemptOutcome.COMPLETED)
        s.library.attempts[7] = listOf(cached.copy(startedOn = "2026-08-01"))
        s.vm.edit(lostWorld, cached)
        // History's dates at once...
        assertEquals(DatesForm(null, LocalDate.of(2026, 9, 3), AttemptOutcome.COMPLETED), s.vm.state.value.dates!!.form)
        advanceUntilIdle()
        // ...then the server's.
        assertEquals(DatesForm(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 3), AttemptOutcome.COMPLETED), s.vm.state.value.dates!!.form)

        s.vm.setEnded(LocalDate.of(2026, 9, 5))
        s.vm.save()
        advanceUntilIdle()
        val body = s.server.attemptBodies.single()
        assertUpdateBody(body)
        // Only the end moved: the start the user never touched isn't cleared.
        assertEquals("""{"startedOn":"2026-08-01","endedOn":"2026-09-05","outcome":"completed"}""", body.toString())
    }

    @Test
    fun historysEditKeepsAnEndTheUserMovedWhileItsReadingLoaded() = runTest(scheduler) {
        val s = setup()
        val cached = ReadingAttempt(id = 4, bookId = 7, endedOn = "2026-09-03", outcome = AttemptOutcome.COMPLETED)
        s.library.attempts[7] = listOf(cached.copy(startedOn = "2026-08-01", outcome = AttemptOutcome.SKIMMED))
        s.vm.edit(lostWorld, cached)
        s.vm.setEnded(LocalDate.of(2026, 9, 5))
        advanceUntilIdle()
        val dates = s.vm.state.value.dates!!
        assertEquals(DatesTarget.Existing(4), dates.target)
        // The user's end; the server's start and outcome.
        assertEquals(DatesForm(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 5), AttemptOutcome.SKIMMED), dates.form)
    }

    @Test
    fun aStartTheUserClearedIsCleared() = runTest(scheduler) {
        val s = setup()
        val finished = ReadingAttempt(id = 4, bookId = 7, startedOn = "2025-01-02", endedOn = "2025-02-03", outcome = AttemptOutcome.COMPLETED)
        s.library.attempts[7] = listOf(finished)
        s.vm.edit(lostWorld, finished)
        advanceUntilIdle()
        s.vm.setStarted(null)
        s.vm.save()
        advanceUntilIdle()
        assertEquals("""{"startedOn":null,"endedOn":"2025-02-03","outcome":"completed"}""", s.server.attemptBodies.single().toString())
    }

    @Test
    fun aReadingGoneFromTheServerIsntSavedAsANewOne() = runTest(scheduler) {
        val s = setup()
        val finished = ReadingAttempt(id = 4, bookId = 7, startedOn = "2025-01-02", endedOn = "2025-02-03", outcome = AttemptOutcome.COMPLETED)
        // Deleted on another device since History loaded it.
        s.library.attempts[7] = emptyList()
        s.vm.edit(lostWorld, finished)
        assertTrue(s.vm.state.value.dates!!.loading)
        advanceUntilIdle()
        val dates = s.vm.state.value.dates!!
        assertTrue(dates.targetGone)
        assertFalse(dates.canSave)
        s.vm.save()
        advanceUntilIdle()
        assertTrue(s.server.attemptBodies.isEmpty())
    }

    @Test
    fun badDatesCantBeSaved() = runTest(scheduler) {
        val s = setup()
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        s.vm.setStarted(day.plusDays(1))
        assertEquals(DatesProblem.START_AFTER_END, s.vm.state.value.dates!!.problem)
        assertFalse(s.vm.state.value.dates!!.canSave)
        s.vm.save()
        advanceUntilIdle()
        assertTrue(s.server.attemptBodies.isEmpty())
    }

    @Test
    fun aFailedSaveKeepsTheSheetWithTheReason() = runTest(scheduler) {
        val s = setup()
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        s.server.offline = true
        s.vm.save()
        advanceUntilIdle()
        val dates = s.vm.state.value.dates!!
        assertFalse(dates.saving)
        assertEquals("offline", dates.error)
        assertTrue(s.saved.isEmpty())
        // Editing clears the message; saving again works once back online.
        s.vm.setOutcome(AttemptOutcome.COMPLETED)
        assertNull(s.vm.state.value.dates!!.error)
        s.server.offline = false
        s.vm.save()
        advanceUntilIdle()
        runCurrent()
        assertNull(s.vm.state.value.dates)
        assertEquals(1, s.saved.size)
    }

    @Test
    fun aSaveWhoseAnswerWasLostIsntAddedTwice() = runTest(scheduler) {
        val s = setup()
        s.library.books[7] = detail(7, "read")
        s.library.attempts[7] = listOf(
            ReadingAttempt(id = 3, bookId = 7, startedOn = "2020-01-02", endedOn = "2020-02-01", outcome = AttemptOutcome.COMPLETED, origin = "manual"),
        )
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        s.vm.setStarted(LocalDate.of(2024, 8, 20))
        assertEquals(DatesTarget.New, s.vm.state.value.dates!!.target)
        s.library.loseNextCreate = IOException("timeout")
        s.vm.save()
        advanceUntilIdle()
        assertEquals("timeout", s.vm.state.value.dates!!.error)

        // Tapped again: the reading is on the server, so it gets the dates instead of a second one.
        s.vm.save()
        advanceUntilIdle()
        runCurrent()
        assertEquals(2, s.server.attemptBodies.size)
        assertCreateBody(s.server.attemptBodies[0])
        assertUpdateBody(s.server.attemptBodies[1])
        assertEquals("""{"startedOn":"2024-08-20","endedOn":"2024-09-03","outcome":"completed"}""", s.server.attemptBodies[1].toString())
        assertEquals(listOf(100L), s.library.updatedIds)
        assertNull(s.vm.state.value.dates)
        assertEquals(listOf(3L, 100L), s.saved.single().attempts.map { it.id })

        // Another reading of the same book later is added as usual.
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        s.vm.chooseTarget(DatesTarget.New)
        s.vm.setStarted(LocalDate.of(2024, 8, 1))
        s.vm.save()
        advanceUntilIdle()
        assertEquals(3, s.server.attemptBodies.size)
        assertCreateBody(s.server.attemptBodies[2])
    }

    @Test
    fun aLostSaveRetriedWithOtherDatesFixesTheReadingThatArrived() = runTest(scheduler) {
        val s = setup()
        s.library.books[7] = detail(7, "read")
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        s.vm.setStarted(LocalDate.of(2024, 8, 20))
        // The server stored the reading, then a proxy answered 502.
        s.library.loseNextCreate = ApiException(502, "Bad gateway")
        s.vm.save()
        advanceUntilIdle()
        assertEquals("Bad gateway", s.vm.state.value.dates!!.error)

        // Still in the sheet, which never showed that reading, the user fixes the start and saves.
        s.vm.setStarted(LocalDate.of(2024, 8, 22))
        s.vm.save()
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf(100L), s.library.updatedIds)
        assertEquals(2, s.server.attemptBodies.size)
        assertEquals("""{"startedOn":"2024-08-22","endedOn":"2024-09-03","outcome":"completed"}""", s.server.attemptBodies[1].toString())
        assertNull(s.vm.state.value.dates)
    }

    @Test
    fun aReadingTheUserSawAfterALostSaveIsntMovedOntoAnotherOne() = runTest(scheduler) {
        val s = setup()
        s.library.books[7] = detail(7, "read")
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        s.vm.setStarted(LocalDate.of(2024, 8, 20))
        s.library.loseNextCreate = IOException("timeout")
        s.vm.save()
        advanceUntilIdle()

        // Opened again, the sheet lists the reading that arrived; the user adds a reread after it.
        s.vm.closeDates()
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        assertEquals(listOf(100L), s.vm.state.value.dates!!.attempts.map { it.id })
        s.vm.chooseTarget(DatesTarget.New)
        s.vm.setEnded(LocalDate.of(2025, 3, 10))
        s.vm.setStarted(LocalDate.of(2025, 2, 1))
        s.vm.save()
        advanceUntilIdle()
        runCurrent()
        assertTrue(s.library.updatedIds.isEmpty())
        assertEquals(2, s.server.attemptBodies.size)
        assertCreateBody(s.server.attemptBodies[1])
        assertEquals("""{"startedOn":"2025-02-01","endedOn":"2025-03-10","outcome":"completed"}""", s.server.attemptBodies[1].toString())
        assertNull(s.vm.state.value.dates)
    }

    @Test
    fun theLostReadingSavedAgainAfterReopeningIsntAddedTwice() = runTest(scheduler) {
        val s = setup()
        s.library.books[7] = detail(7, "read")
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        s.vm.setStarted(LocalDate.of(2024, 8, 20))
        s.library.loseNextCreate = IOException("timeout")
        s.vm.save()
        advanceUntilIdle()

        // Opened again, the sheet offers a new reading (the one listed has a start), and the user
        // enters the same dates again: that reading is the one the user means.
        s.vm.closeDates()
        s.vm.openPicker(day)
        s.vm.pick(lostWorld)
        advanceUntilIdle()
        assertEquals(DatesTarget.New, s.vm.state.value.dates!!.target)
        s.vm.setStarted(LocalDate.of(2024, 8, 20))
        s.vm.save()
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf(100L), s.library.updatedIds)
        assertEquals(2, s.server.attemptBodies.size)
        assertUpdateBody(s.server.attemptBodies[1])
        assertNull(s.vm.state.value.dates)
    }

    @Test
    fun readingsThatCantBeLoadedStopTheSave() = runTest(scheduler) {
        val s = setup()
        val remoteless = ReadingDatesViewModel(
            remote = object : ReadingDatesRemote by FailingRemote {},
            today = { today },
            takeScanned = { null },
            clearScanned = {},
        )
        remoteless.openPicker(day)
        remoteless.pick(lostWorld)
        advanceUntilIdle()
        assertTrue(remoteless.state.value.dates!!.loadFailed)
        assertFalse(remoteless.state.value.dates!!.canSave)
        assertTrue(s.server.attemptBodies.isEmpty())
    }

    private object FailingRemote : ReadingDatesRemote {
        override suspend fun readingNow(): List<PickerBook> = error("offline")
        override suspend fun search(text: String): List<PickerBook> = error("offline")
        override suspend fun book(bookId: Long): BookDetail = error("offline")
        override suspend fun attempts(bookId: Long): List<ReadingAttempt> = error("offline")
        override suspend fun create(bookId: Long, startedOn: LocalDate?, endedOn: LocalDate, outcome: String): ReadingAttempt = error("offline")
        override suspend fun update(
            bookId: Long,
            attemptId: Long,
            startedOn: io.github.ottershelf.core.tracking.Patch<LocalDate>,
            endedOn: io.github.ottershelf.core.tracking.Patch<LocalDate>,
            outcome: io.github.ottershelf.core.tracking.Patch<String>,
        ): ReadingAttempt = error("offline")
        override suspend fun setStatus(bookId: Long, status: String) = error("offline")
        override fun statusChanged(bookId: Long, status: String) = Unit
    }
}
