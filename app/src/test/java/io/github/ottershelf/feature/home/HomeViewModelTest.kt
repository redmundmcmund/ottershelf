package io.github.ottershelf.feature.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.ottershelf.core.download.DownloadedBook
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.CurrentlyReading
import io.github.ottershelf.core.model.CurrentlyReadingBook
import io.github.ottershelf.core.model.Library
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.ReadingStreak
import io.github.ottershelf.core.model.SmartScope
import io.github.ottershelf.core.model.UserSettings
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.feature.book.TitleHint
import io.github.ottershelf.feature.home.model.DEFAULT_WIDGETS
import io.github.ottershelf.feature.home.model.ShelfBatchItem
import io.github.ottershelf.feature.home.model.WidgetData
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.feature.home.model.dashboardConfigWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** The Dashboard's freshness rules (ported from the Nexus DashboardFragment), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val remote = FakeRemote()
    private val changes = ReadingChanges()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // elapsedRealtime is never 0 on a device; 0 means "never loaded" to the view model.
    private fun newViewModel() = HomeViewModel(remote, changes, clock = { scheduler.currentTime + 1_000_000 }, day = { 0 })

    @Test
    fun firstShowLoadsEverythingOnceWithoutClearing() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setVisible(true)
        advanceUntilIdle()

        assertEquals(1, remote.widgetCalls)
        assertEquals(1, remote.shelfCalls)
        assertEquals(0, remote.refreshCalls)
        val state = vm.state.value
        assertNotNull(state.reading)
        assertNotNull(state.streak)
        assertTrue(state.shelves.isNotEmpty() && state.shelves.all { it.books != null })
        assertFalse(state.refreshing)
        assertEquals(true, vm.loads.first())
        vm.setVisible(false)
    }

    @Test
    fun comingBackReloadsOnlyAfterFiveMinutes() = runTest(dispatcher) {
        val vm = customised(MutableStateFlow(null))
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(1, remote.widgetCalls)

        // Back from a book page two minutes later, nothing changed here: nothing is asked, not
        // even the account.
        vm.setVisible(false)
        advanceTimeBy(2 * 60_000)
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(1, remote.widgetCalls)
        assertEquals(1, remote.shelfCalls)
        assertEquals(0, remote.meCalls)

        // Again at four minutes and a half: still nothing.
        vm.setVisible(false)
        advanceTimeBy(150_000)
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(1, remote.widgetCalls)

        // Past five minutes since the load: everything, and the account (the web may have changed
        // the layout), without dropping the server's widgets (nothing changed here).
        vm.setVisible(false)
        advanceTimeBy(31_000)
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(2, remote.widgetCalls)
        assertEquals(2, remote.shelfCalls)
        assertEquals(1, remote.meCalls)
        assertEquals(0, remote.refreshCalls)
        vm.setVisible(false)
    }

    @Test
    fun aChangeWhileAwayReloadsOnTheReturnWithinFiveMinutes() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setVisible(true)
        advanceUntilIdle()

        // A session sent from the book page's reader while the Dashboard was covered.
        vm.setVisible(false)
        advanceTimeBy(60_000)
        changes.changed()
        advanceTimeBy(60_000)
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(2, remote.widgetCalls)
        assertEquals(2, remote.shelfCalls)
        assertEquals(1, remote.refreshCalls)
        vm.setVisible(false)
    }

    @Test
    fun aChangeSettlesThenClearsTheServerCacheOnce() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setVisible(true)
        advanceUntilIdle()

        // The position, then the session a moment later: one reload for both.
        changes.changed()
        runCurrent()
        advanceTimeBy(100)
        changes.changed()
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, remote.widgetCalls)
        assertEquals(1, remote.refreshCalls)
        assertEquals(2, changes.dashboardClearedFor)

        // Back soon after, nothing changed since: nothing asked.
        vm.setVisible(false)
        advanceTimeBy(10_000)
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(2, remote.widgetCalls)
        vm.setVisible(false)
    }

    @Test
    fun aChangeDuringASentLoadDoesNotCancelIt() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        remote.widgetGate = gate
        val vm = newViewModel()
        vm.setVisible(true)
        runCurrent()
        assertEquals(1, remote.widgetCalls)

        changes.changed()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        // The first load's answer shows...
        assertNotNull(vm.state.value.reading)
        advanceUntilIdle()
        // ... and it looks again afterwards, dropping the server's copy for the change.
        assertEquals(2, remote.widgetCalls)
        assertEquals(1, remote.refreshCalls)
        vm.setVisible(false)
    }

    @Test
    fun pullingDuringASentLoadRefreshesWhenItIsDone() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        remote.widgetGate = gate
        val vm = newViewModel()
        vm.setVisible(true)
        runCurrent()

        vm.refresh()
        runCurrent()
        assertEquals(1, remote.widgetCalls)
        assertTrue(vm.state.value.refreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, remote.widgetCalls)
        assertEquals(1, remote.refreshCalls)
        assertFalse(vm.state.value.refreshing)
        vm.setVisible(false)
    }

    @Test
    fun nothingFromTheServerReportsNoContentAndRetryRecovers() = runTest(dispatcher) {
        remote.fail = true
        val vm = newViewModel()
        vm.setVisible(true)
        advanceUntilIdle()

        assertEquals(false, vm.loads.first())
        val failed = vm.state.value
        assertTrue(failed.readingFailed && failed.streakFailed && failed.failedShelves.isNotEmpty())
        assertFalse(failed.refreshing)

        remote.fail = false
        vm.retry()
        advanceUntilIdle()
        val state = vm.state.value
        assertNotNull(state.reading)
        assertFalse(state.readingFailed || state.streakFailed || state.failedShelves.isNotEmpty())
        assertEquals(true, vm.loads.first())
        vm.setVisible(false)
    }

    @Test
    fun openingABookLeavesWhatItsPageShowsFirst() = runTest(dispatcher) {
        val vm = HomeViewModel(remote, changes, clock = { scheduler.currentTime + 1_000_000 }, day = { 0 }, account = { "home-test" })
        vm.setVisible(true)
        advanceUntilIdle()

        vm.opening(1) // on the shelf too: its card, with the thumbnail
        assertEquals("Dracula", BookPreview.get("home-test", 1)?.title)
        vm.opening(2) // Currently Reading only: title and authors
        assertNull(BookPreview.get("home-test", 2))
        assertEquals(TitleHint("Emma", listOf("Jane Austen")), BookPreview.hint("home-test", 2))
        vm.setVisible(false)
    }

    @Test
    fun oneBatchForTheEnabledWidgetsAndOneFailedWidgetFailsAlone() = runTest(dispatcher) {
        remote.failing = setOf(WidgetType.READING_DNA)
        val vm = newViewModel()
        vm.setVisible(true)
        advanceUntilIdle()

        assertEquals(1, remote.widgetCalls)
        // The web's defaults: the first nine widgets, in order.
        assertEquals(WidgetType.entries.take(9), remote.lastTypes)
        val state = vm.state.value
        assertEquals(setOf(WidgetType.READING_DNA), state.failedWidgets)
        assertTrue(state.hasData(WidgetType.READING_GOAL))
        // The default shelves: Recently Added, Discover, Continue Reading, in one batch.
        assertEquals(listOf("recently-added", "random", "continue-reading"), remote.lastShelves.map { it.type })
        assertEquals(listOf(20, 20, 20), remote.lastShelves.map { it.limit })
        vm.setVisible(false)
    }

    @Test
    fun savingTheWidgetsWritesTheWholeDashboardConfig() = runTest(dispatcher) {
        val config = JsonObject(mapOf("readingGoal" to JsonPrimitive(24), "somethingNewer" to JsonPrimitive("kept")))
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config))
        val user = MutableStateFlow<AuthUser?>(remote.user)
        val prefs = ShelfPrefs.InMemory()
        val vm = HomeViewModel(
            remote, changes, clock = { scheduler.currentTime + 1_000_000 }, day = { 0 },
            shelfPrefs = prefs, user = user, updateUser = { user.value = it },
        )
        vm.setVisible(true)
        advanceUntilIdle()

        vm.openCustomise()
        advanceUntilIdle()
        // Every library counts for the scope; podcasts aren't offered.
        assertEquals(3, vm.customise.value?.libraries?.size)
        assertEquals(2, vm.customise.value?.bookLibraries?.size)
        vm.editCustomise { it.toggleWidget(0).moveWidget(2, 0).toggleLibrary(7, listOf(Library(7, name = "A"), Library(8, name = "B"))) }
        vm.editCustomise { it.addShelf().setShelfRows(0, 2) }
        vm.saveCustomise()
        advanceUntilIdle()

        val sent = remote.patched?.get("dashboardConfig") as JsonObject
        assertEquals(JsonPrimitive(24), sent["readingGoal"])
        assertEquals(JsonPrimitive("kept"), sent["somethingNewer"])
        assertEquals("[8]", sent["libraryIds"].toString())
        val widgets = vm.state.value.layout.widgets
        assertEquals(WidgetType.READING_GOAL, widgets.first().type)
        assertFalse(widgets.first { it.type == WidgetType.READING_STREAK }.enabled)
        assertEquals(listOf(8L), vm.state.value.layout.libraryIds)
        assertNull(vm.customise.value)
        // The shelves stay on the device, and the dashboard asks again with them.
        assertEquals(6, prefs.load().size)
        assertEquals(2, prefs.load().first().rows)
        assertEquals(40, remote.lastShelves.first().limit)
        assertEquals(sent, user.value?.settings?.dashboardConfig)
        vm.setVisible(false)
    }

    /** A dashboardConfig with [off] widgets disabled (the rest on) and the library scope [ids]. */
    private fun config(off: Set<WidgetType>, ids: List<Long>?, goal: Int = 24): JsonObject {
        val widgets = DEFAULT_WIDGETS.map { it.copy(enabled = it.type !in off) }
        return dashboardConfigWith(JsonObject(mapOf("readingGoal" to JsonPrimitive(goal))), widgets, ids)
    }

    private fun customised(user: MutableStateFlow<AuthUser?>, prefs: ShelfPrefs = ShelfPrefs.InMemory()) = HomeViewModel(
        remote, changes, clock = { scheduler.currentTime + 1_000_000 }, day = { 0 },
        shelfPrefs = prefs, user = user, updateUser = { user.value = it },
    )

    @Test
    fun aScopeChangeKeepsWidgetsChangedOnTheWebMeanwhile() = runTest(dispatcher) {
        val before = config(off = setOf(WidgetType.LIBRARY_OVERVIEW), ids = listOf(7))
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = before))
        val user = MutableStateFlow<AuthUser?>(remote.user)
        val vm = customised(user)
        vm.setVisible(true)
        advanceUntilIdle()
        vm.openCustomise()
        advanceUntilIdle()

        // On the web, while the sheet is open: Reading DNA off, Library Overview on.
        val web = config(off = setOf(WidgetType.READING_DNA), ids = listOf(7), goal = 30)
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = web))
        val books = vm.customise.value!!.bookLibraries!!
        vm.editCustomise { it.toggleLibrary(8, books) }
        vm.saveCustomise()
        advanceUntilIdle()

        val sent = remote.patched?.get("dashboardConfig") as JsonObject
        assertEquals(web["widgets"], sent["widgets"])
        assertEquals(JsonPrimitive(30), sent["readingGoal"])
        assertEquals("[7,8]", sent["libraryIds"].toString())
        // The dashboard follows: the new widget is asked for.
        assertTrue(WidgetType.LIBRARY_OVERVIEW in vm.state.value.widgets)
        assertTrue(vm.state.value.hasData(WidgetType.LIBRARY_OVERVIEW))
        vm.setVisible(false)
    }

    @Test
    fun aWidgetChangeKeepsTheScopeSetOnTheWebMeanwhile() = runTest(dispatcher) {
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(emptySet(), ids = listOf(7))))
        val user = MutableStateFlow<AuthUser?>(remote.user)
        val vm = customised(user)
        vm.setVisible(true)
        advanceUntilIdle()
        vm.openCustomise()
        advanceUntilIdle()

        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(emptySet(), ids = listOf(8))))
        vm.editCustomise { it.toggleWidget(0) }
        vm.saveCustomise()
        advanceUntilIdle()

        val sent = remote.patched?.get("dashboardConfig") as JsonObject
        assertEquals("[8]", sent["libraryIds"].toString())
        assertEquals(JsonPrimitive(false), (sent["widgets"] as JsonArray)[0].jsonObject["enabled"])
        vm.setVisible(false)
    }

    @Test
    fun theSheetOpensOnTheAccountAsTheServerHasIt() = runTest(dispatcher) {
        val user = MutableStateFlow<AuthUser?>(AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(emptySet(), null))))
        // Changed on the web since the app last read the account.
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(setOf(WidgetType.READING_STREAK), listOf(8))))
        val vm = customised(user)
        vm.setVisible(true)
        advanceUntilIdle()
        vm.openCustomise()
        advanceUntilIdle()

        val draft = vm.customise.value!!.draft
        assertFalse(draft.widgets.first { it.type == WidgetType.READING_STREAK }.enabled)
        assertEquals(listOf(8L), draft.libraryIds)
        assertEquals(listOf(8L), vm.state.value.layout.libraryIds)
        // Nothing changed in the sheet: Save writes nothing to the server.
        vm.saveCustomise()
        advanceUntilIdle()
        assertNull(remote.patched)
        vm.setVisible(false)
    }

    @Test
    fun aShelfOnlySaveKeepsAPodcastScopeAndSendsNothing() = runTest(dispatcher) {
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(emptySet(), ids = listOf(7, 9))))
        val user = MutableStateFlow<AuthUser?>(remote.user)
        val prefs = ShelfPrefs.InMemory()
        val vm = customised(user, prefs)
        vm.setVisible(true)
        advanceUntilIdle()
        vm.openCustomise()
        advanceUntilIdle()

        vm.editCustomise { it.setShelfRows(0, 2) }
        vm.saveCustomise()
        advanceUntilIdle()
        assertNull(remote.patched)
        assertNull(vm.customise.value)
        assertEquals(2, prefs.load().first().rows)
        assertEquals(listOf(7L, 9L), vm.state.value.layout.libraryIds)
        vm.setVisible(false)
    }

    @Test
    fun librariesThatFailedToLoadLeaveTheScopeAsItIs() = runTest(dispatcher) {
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(emptySet(), ids = listOf(7))))
        remote.librariesFail = true
        val user = MutableStateFlow<AuthUser?>(remote.user)
        val vm = customised(user)
        vm.setVisible(true)
        advanceUntilIdle()
        vm.openCustomise()
        advanceUntilIdle()

        val c = vm.customise.value!!
        assertNull(c.libraries)
        assertTrue(c.librariesFailed)
        assertTrue(c.draft.validLibraries(c.libraries))

        remote.librariesFail = false
        vm.retryLibraries()
        advanceUntilIdle()
        assertEquals(3, vm.customise.value?.libraries?.size)
        assertFalse(vm.customise.value!!.librariesFailed)
        vm.setVisible(false)
    }

    @Test
    fun aWidgetEnabledOnTheWebDuringTheFirstLoadIsAskedForAfterIt() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        remote.widgetGate = gate
        val user = MutableStateFlow<AuthUser?>(null)
        val vm = customised(user)
        vm.setVisible(true)
        runCurrent()
        assertEquals(1, remote.widgetCalls)

        // The shell's auth/me lands while the first batch is on its way.
        user.value = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(emptySet(), null)))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, remote.widgetCalls)
        assertTrue(WidgetType.LIBRARY_OVERVIEW in remote.lastTypes)
        assertTrue(vm.state.value.hasData(WidgetType.LIBRARY_OVERVIEW))
        vm.setVisible(false)
    }

    @Test
    fun comingBackAfterFiveMinutesReadsTheAccountAgain() = runTest(dispatcher) {
        val user = MutableStateFlow<AuthUser?>(null)
        val vm = customised(user)
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(0, remote.meCalls)

        vm.setVisible(false)
        advanceTimeBy(HomeViewModel.STALE_MS + 1_000)
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(emptySet(), null)))
        vm.setVisible(true)
        advanceUntilIdle()
        assertEquals(1, remote.meCalls)
        assertTrue(vm.state.value.hasData(WidgetType.LONG_WAIT))
        vm.setVisible(false)
    }

    private fun arranging(user: MutableStateFlow<AuthUser?>, settings: MutableStateFlow<AppSettings>, prefs: ShelfPrefs = ShelfPrefs.InMemory()) = HomeViewModel(
        remote, changes, clock = { scheduler.currentTime + 1_000_000 }, day = { 0 },
        shelfPrefs = prefs, user = user, updateUser = { user.value = it },
        appSettings = settings, updateAppSettings = { f -> settings.value = f(settings.value) },
    )

    private fun JsonObject.type() = this["type"]!!.jsonPrimitive.content

    @Test
    fun arrangingTheCardsSavesTheOrderAndOnlyTheWidgetsOrderOnTheAccount() = runTest(dispatcher) {
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(off = setOf(WidgetType.READING_DNA), ids = listOf(7))))
        val user = MutableStateFlow<AuthUser?>(remote.user)
        val settings = MutableStateFlow(AppSettings())
        val vm = arranging(user, settings)
        vm.setVisible(true)
        advanceUntilIdle()
        val shown = vm.state.value.cards.map { it.key }
        // Nothing arranged yet: the widgets, then the shelves (Recently Added, Discover, Continue Reading).
        assertEquals(listOf("s:2", "s:3", "s:1"), shown.takeLast(3))

        vm.startEditing()
        assertTrue(vm.state.value.editing)
        // Continue Reading to the top, Reading Goal above the streak.
        val next = listOf("s:1", "w:reading-goal") + shown.filter { it != "s:1" && it != "w:reading-goal" }
        vm.arrange(next)
        assertEquals(next, vm.state.value.cards.map { it.key })
        // The whole order (hidden cards too) is in the app's settings key at once.
        assertEquals(vm.state.value.cardOrder, settings.value.dashboardOrder)
        assertTrue("w:reading-dna" in settings.value.dashboardOrder)
        assertNull(remote.patched)

        // The web turned Library Overview off meanwhile: the flags stay the server's.
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(off = setOf(WidgetType.READING_DNA, WidgetType.LIBRARY_OVERVIEW), ids = listOf(7))))
        vm.finishEditing()
        advanceUntilIdle()
        assertFalse(vm.state.value.editing)
        val sent = remote.patched?.get("dashboardConfig") as JsonObject
        assertEquals(JsonPrimitive(24), sent["readingGoal"])
        assertEquals("[7]", sent["libraryIds"].toString())
        val widgets = (sent["widgets"] as JsonArray).map { it.jsonObject }
        // Reading DNA (hidden) stays after the card it followed, Reading Goal.
        assertEquals(listOf("reading-goal", "reading-dna", "reading-streak", "currently-reading"), widgets.take(4).map { it.type() })
        assertEquals(JsonPrimitive(false), widgets.first { it.type() == "library-overview" }["enabled"])
        assertEquals(JsonPrimitive(false), widgets.first { it.type() == "reading-dna" }["enabled"])
        assertEquals(sent, user.value?.settings?.dashboardConfig)
        // The Dashboard shows the user's order (without the widget the web turned off).
        assertEquals(next.filter { it != "w:library-overview" }, vm.state.value.cards.map { it.key })
        vm.setVisible(false)
    }

    @Test
    fun movingOnlyShelvesAroundSendsNothingToTheAccount() = runTest(dispatcher) {
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(off = setOf(WidgetType.READING_DNA), ids = null)))
        val user = MutableStateFlow<AuthUser?>(remote.user)
        val settings = MutableStateFlow(AppSettings())
        val vm = arranging(user, settings)
        vm.setVisible(true)
        advanceUntilIdle()
        val shown = vm.state.value.cards.map { it.key }

        vm.startEditing()
        // Continue Reading between the streak and Currently Reading.
        val next = listOf(shown[0], "s:1") + shown.drop(1).filter { it != "s:1" }
        vm.arrange(next)
        // Leaving the Dashboard ends arranging.
        vm.setVisible(false)
        advanceUntilIdle()
        assertFalse(vm.state.value.editing)
        assertNull(remote.patched)
        assertEquals(next, vm.state.value.cards.map { it.key })
        assertEquals(listOf("w:reading-streak", "s:1"), settings.value.dashboardOrder.take(2))
    }

    @Test
    fun anOrderTheAccountRefusesIsPutBack() = runTest(dispatcher) {
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(emptySet(), ids = null)))
        val user = MutableStateFlow<AuthUser?>(remote.user)
        val settings = MutableStateFlow(AppSettings(dashboardOrder = listOf("s:1", "w:reading-streak")))
        val vm = arranging(user, settings)
        vm.setVisible(true)
        advanceUntilIdle()
        val before = vm.state.value.cards.map { it.key }
        assertEquals(listOf("s:1", "w:reading-streak"), before.take(2))

        vm.startEditing()
        vm.arrange(before.reversed())
        remote.patchFails = true
        vm.finishEditing()
        advanceUntilIdle()
        assertEquals(Unit, vm.orderFailures.first())
        assertEquals(before, vm.state.value.cards.map { it.key })
        assertEquals(listOf("s:1", "w:reading-streak"), settings.value.dashboardOrder)
        vm.setVisible(false)
    }

    @Test
    fun theOrderFollowsTheAccountAndNewCardsGoAtTheEnd() = runTest(dispatcher) {
        remote.user = AuthUser(1, "reader", settings = UserSettings(dashboardConfig = config(emptySet(), ids = null)))
        val user = MutableStateFlow<AuthUser?>(remote.user)
        val settings = MutableStateFlow(AppSettings())
        val prefs = ShelfPrefs.InMemory()
        val vm = arranging(user, settings, prefs)
        vm.setVisible(true)
        advanceUntilIdle()

        // Another phone arranged the cards (Discover first, Continue Reading between the streak and
        // Currently Reading); the shelf it had that this phone hasn't is left out.
        settings.value = AppSettings(dashboardOrder = listOf("s:3", "w:reading-streak", "s:9", "s:1", "w:currently-reading"))
        runCurrent()
        val cards = vm.state.value.cards.map { it.key }
        assertEquals(listOf("s:3", "w:reading-streak", "s:1", "w:currently-reading", "w:reading-goal"), cards.take(5))
        // The cards that order didn't know go at the end: widgets, then shelves.
        assertEquals(listOf("w:long-wait", "s:2"), cards.takeLast(2))
        assertFalse("s:9" in cards)

        // The Customise sheet has the shelves in the Dashboard's order. A shelf added and the
        // shelves reordered there: the new shelf goes at the end, and widgets and shelves keep
        // their places among each other.
        vm.openCustomise()
        advanceUntilIdle()
        assertEquals(listOf("3", "1", "2", "6", "4"), vm.customise.value!!.draft.shelves.map { it.id })
        vm.editCustomise { it.addShelf().arrangeShelves(listOf("shelf:2", "shelf:3", "shelf:1")) }
        vm.saveCustomise()
        advanceUntilIdle()
        assertNull(remote.patched)
        val after = vm.state.value.cards.map { it.key }
        assertEquals(listOf("s:2", "w:reading-streak", "s:3", "w:currently-reading"), after.take(4))
        assertEquals(listOf("w:long-wait", "s:1", "s:7"), after.takeLast(3))
        assertEquals(vm.state.value.cardOrder, settings.value.dashboardOrder)
        assertEquals(listOf("2", "3", "1", "6", "4", "7"), prefs.load().map { it.id })
        vm.setVisible(false)
    }

    @Test
    fun addToQueueLeavesABookTheUserIsReadingAlone() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setVisible(true)
        advanceUntilIdle()

        // On Currently Reading: not offered.
        assertFalse(vm.state.value.canQueue(1))
        remote.statuses = mapOf(5L to "rereading", 6L to "abandoned", 7L to "want_to_read")
        vm.queue(5)
        vm.queue(6)
        vm.queue(7)
        advanceUntilIdle()
        assertEquals(listOf(6L), remote.statusSet)
        assertEquals(setOf(6L, 7L), vm.state.value.queued)
        assertFalse(vm.state.value.canQueue(5))
        vm.setVisible(false)
    }

    @Test
    fun playOpensTheCbzKeptForACbrComic() = runTest(dispatcher) {
        val copies = mapOf(
            1L to DownloadedBook(bookId = 1, fileId = 21, format = "cbz"),
            2L to DownloadedBook(bookId = 2, fileId = 31, format = "epub"),
        )
        val vm = HomeViewModel(remote, changes, clock = { scheduler.currentTime + 1_000_000 }, day = { 0 }, downloaded = { copies[it] })
        // A CBR (or CB7) whose CBZ is on the phone: the CBZ, as the book page's Read.
        assertEquals(21L to "cbz", vm.fileToRead(1, 20, "cbr"))
        assertEquals(21L to "cbz", vm.fileToRead(1, 22, "CB7"))
        // Not downloaded, or the kept copy isn't a comic: the server's file.
        assertEquals(40L to "cbr", vm.fileToRead(4, 40, "cbr"))
        assertEquals(30L to "cbr", vm.fileToRead(2, 30, "cbr"))
        // Formats the phone keeps open the server's pick, as ever.
        assertEquals(20L to "pdf", vm.fileToRead(1, 20, "pdf"))
        assertEquals(20L to "epub", vm.fileToRead(1, 20, "epub"))
    }

    /** A book edited here (feature.bookedit): the shelves show it at once, then the server's word. */
    @Test
    fun anEditShowsOnTheShelvesAndTheReloadAfterItIsTheServersWord() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setVisible(true)
        advanceUntilIdle()
        // Authors only: the server keeps the book's updatedAt, so it can't tell the edit apart.
        val edited = BookDetail(id = 1, title = "Dracula", authors = listOf(AuthorRef(3, "Bram Stoker")))
        // Someone changes it again on the web before the Dashboard asks after the edit.
        remote.shelfCards = listOf(BookCard(id = 1, title = "Dracula", authors = listOf("B. Stoker")))
        changes.bookEdited(edited)
        runCurrent()
        assertEquals(listOf("Bram Stoker"), vm.state.value.shelves.first().books!!.first().book.authors)
        advanceUntilIdle() // the reload the edit asks for
        assertEquals(2, remote.shelfCalls)
        assertTrue(vm.state.value.shelves.all { shelf -> shelf.books!!.first().book.authors == listOf("B. Stoker") })
        vm.setVisible(false)
    }

    private class FakeRemote : DashboardRemote {
        var widgetCalls = 0
        var shelfCalls = 0
        var refreshCalls = 0
        var fail = false
        var failing: Set<WidgetType> = emptySet()
        var widgetGate: CompletableDeferred<Unit>? = null
        var lastTypes: List<WidgetType> = emptyList()
        var lastShelves: List<ShelfBatchItem> = emptyList()
        var user = AuthUser(1, "reader")
        var patched: JsonObject? = null
        var meCalls = 0
        var librariesFail = false
        var patchFails = false
        var statuses: Map<Long, String> = emptyMap()
        val statusSet = mutableListOf<Long>()
        /** What every shelf holds. */
        var shelfCards = listOf(BookCard(id = 1, title = "Dracula"))

        override suspend fun shelves(items: List<ShelfBatchItem>): Map<String, List<BookCard>?> {
            shelfCalls++
            lastShelves = items
            if (fail) throw IOException("down")
            val cards = shelfCards
            return items.associate { it.id to cards }
        }

        override suspend fun widgets(types: List<WidgetType>): Map<WidgetType, WidgetResult> {
            widgetCalls++
            lastTypes = types
            widgetGate?.let {
                widgetGate = null
                it.await()
            }
            if (fail) throw IOException("down")
            val reading = listOf(
                CurrentlyReadingBook(bookId = 1, title = "Dracula"),
                CurrentlyReadingBook(bookId = 2, title = "Emma", authors = listOf("Jane Austen")),
            )
            return types.associateWith { type ->
                when {
                    type in failing -> WidgetResult.Failed
                    type == WidgetType.CURRENTLY_READING -> WidgetResult.Loaded(WidgetData.Reading(CurrentlyReading(reading)))
                    type == WidgetType.READING_STREAK ->
                        WidgetResult.Loaded(WidgetData.Streak(ReadingStreak(currentStreak = 1, longestStreak = 3, lastSevenDays = List(7) { it == 6 })))
                    else -> WidgetResult.Loaded(WidgetData.Empty)
                }
            }
        }

        override suspend fun refreshDashboard() {
            refreshCalls++
        }

        override fun shelfCover(book: BookCard): Any? = null
        override fun readingCover(book: CurrentlyReadingBook, day: Long): Any? = null
        override fun bookCover(bookId: Long, hasCover: Boolean, day: Long): Any? = null
        override suspend fun me(): AuthUser {
            meCalls++
            return user
        }

        override suspend fun patchSettings(settings: JsonObject): JsonObject {
            if (patchFails) throw IOException("refused")
            patched = settings
            return JsonObject((user.settings?.dashboardConfig?.let { mapOf("dashboardConfig" to it) } ?: emptyMap()) + settings)
        }

        override suspend fun libraries(): List<Library> {
            if (librariesFail) throw IOException("down")
            return listOf(Library(7, name = "A"), Library(8, name = "B"), Library(9, type = "podcasts", name = "P"))
        }

        override suspend fun smartScopes(): List<SmartScope> = emptyList()

        override suspend fun setStatus(bookId: Long, status: String): ReadStatusInfo {
            statusSet += bookId
            return ReadStatusInfo(status)
        }

        override suspend fun readStatus(bookId: Long): String? = statuses[bookId]
    }
}
