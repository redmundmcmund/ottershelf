package io.github.ottershelf.feature.book

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the book page asks the server again (four requests each time). It used to on every return
 * more than 2 s after the last time: from the author's books, the series, highlights, edit. Now only
 * when something was sent meanwhile, the app was stopped while it showed, or it is five minutes old;
 * and at once (after a second's settling) when something is sent while it shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailRefreshTest {

    /** [runIn]: where its coroutines run ([TestScope.backgroundScope] for collectors that never end). */
    private class Page(scope: TestScope, runIn: CoroutineScope = scope) {
        var refreshes = 0
        var loaded = true
        /** Refreshes as the view model does: once loaded, counting as a reload. */
        val freshness: PageFreshness = PageFreshness(runIn, clock = { scope.currentTime + START }) {
            if (!loaded) return@PageFreshness false
            refreshes++
            freshness.reloaded()
            true
        }

        /** A reload a tracking write of this book made (its version moved). */
        fun written() {
            freshness.reloadedByWrite()
        }
    }

    /** The page opened and loaded (the first load counts as a reload), then showing. */
    private fun TestScope.opened(): Page {
        val page = Page(this)
        page.freshness.resumed()
        advanceUntilIdle()
        return page
    }

    /** A screen pushed over the page, and back [afterMs] later. */
    private fun TestScope.away(page: Page, afterMs: Long, meanwhile: () -> Unit = {}) {
        page.freshness.paused()
        advanceTimeBy(afterMs / 2)
        meanwhile()
        advanceTimeBy(afterMs - afterMs / 2)
        page.freshness.resumed()
        advanceUntilIdle()
    }

    @Test
    fun openingTheAuthorsBooksAndComingBackAsksNothing() = runTest {
        val page = opened()
        away(page, 30_000)
        away(page, 3 * 60_000)
        assertEquals(0, page.refreshes)
    }

    @Test
    fun closingTheReaderAfterItSentProgressReloadsOnce() = runTest {
        val page = opened()
        page.freshness.paused() // the reader opens over the page
        advanceTimeBy(60_000)
        page.freshness.changed() // positions and a session sent while the user read
        advanceTimeBy(60_000)
        page.freshness.changed()
        advanceTimeBy(60_000)
        page.freshness.resumed() // closed: back on the page
        advanceUntilIdle()
        assertEquals(1, page.refreshes)
        // Nothing more on the next return.
        away(page, 30_000)
        assertEquals(1, page.refreshes)
    }

    @Test
    fun theReadersLastPushLandingAfterTheReturnIsCaught() = runTest {
        val page = opened()
        // Nothing arrived while the user read; back on the page, nothing is due...
        page.freshness.paused()
        advanceTimeBy(60_000)
        page.freshness.resumed()
        advanceTimeBy(300)
        // ... then the reader's final position and session land, a moment apart.
        page.freshness.changed()
        advanceTimeBy(200)
        page.freshness.changed()
        advanceUntilIdle()
        assertEquals(1, page.refreshes)
    }

    @Test
    fun aReturnWithTheLastPushJustBehindItReloadsOnce() = runTest {
        val page = opened()
        page.freshness.paused()
        advanceTimeBy(60_000)
        page.freshness.changed() // sent while the user read
        advanceTimeBy(60_000)
        page.freshness.resumed()
        advanceTimeBy(400)
        page.freshness.changed() // the last push, landing just after the user is back
        advanceUntilIdle()
        assertEquals(1, page.refreshes)
    }

    @Test
    fun stoppingAndResumingTheAppReloadsOnce() = runTest {
        val page = opened()
        page.freshness.paused()
        page.freshness.stopped()
        advanceTimeBy(60_000)
        page.freshness.resumed()
        advanceUntilIdle()
        assertEquals(1, page.refreshes)
        // A screen on top and back: nothing more.
        away(page, 30_000)
        assertEquals(1, page.refreshes)
    }

    @Test
    fun aPushLandingJustAfterARefreshStartedReloadsAgain() = runTest {
        val page = opened()
        page.freshness.paused()
        page.freshness.stopped()
        advanceTimeBy(60_000)
        page.freshness.resumed() // the app back: a refresh a second later
        advanceTimeBy(PageFreshness.SETTLE_MS + 1)
        assertEquals(1, page.refreshes)
        // The sync's push after the app came back, taken by the server 100 ms after that refresh
        // asked: its answers may be from before it.
        advanceTimeBy(99)
        page.freshness.changed()
        advanceUntilIdle()
        assertEquals(2, page.refreshes)
    }

    @Test
    fun aPageFiveMinutesOldReloadsWhenShownAgain() = runTest {
        val page = opened()
        away(page, 4 * 60_000)
        assertEquals(0, page.refreshes)
        away(page, 2 * 60_000)
        assertEquals(1, page.refreshes)
    }

    @Test
    fun aTrackingWriteOnThePageMakesNoSecondReload() = runTest {
        val page = opened()
        advanceTimeBy(60_000)
        // Rating the book: its version moves (the page and the tracker reload) and ReadingChanges
        // with it, in either order, a moment apart.
        page.written()
        advanceTimeBy(100)
        page.freshness.changed()
        advanceUntilIdle()
        advanceTimeBy(60_000)
        page.freshness.changed()
        advanceTimeBy(100)
        page.written()
        advanceUntilIdle()
        assertEquals(0, page.refreshes)
        away(page, 30_000)
        assertEquals(0, page.refreshes)
    }

    @Test
    fun aChangeWellAfterAWriteStillReloads() = runTest {
        val page = opened()
        advanceTimeBy(60_000)
        page.written()
        advanceTimeBy(PageFreshness.SAME_EVENT_MS + 1)
        page.freshness.changed() // not the write's own: a session the timer sent
        advanceUntilIdle()
        assertEquals(1, page.refreshes)
    }

    @Test
    fun followsWhatThisPhoneSendsFromWhereThePageStarts() = runTest {
        // Where ReadingChanges and this book's version stood when the page opened: not a change.
        val changes = MutableStateFlow(5)
        val version = MutableStateFlow(3)
        // Its coroutines run in the background scope (advanceUntilIdle skips it: runCurrent and
        // advanceTimeBy drive them).
        val page = Page(this, runIn = backgroundScope)
        var writeReloads = 0
        page.freshness.follow(changes, version, onWrite = { writeReloads++ })
        page.freshness.resumed()
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(0, page.refreshes)

        // A tracking write of this book, announced as TrackingRepository.changed does: the book's
        // version, then ReadingChanges. The page reloads for the write, and only for it.
        advanceTimeBy(60_000)
        version.value++
        changes.value++
        runCurrent()
        assertEquals(1, writeReloads)
        advanceTimeBy(10_000)
        assertEquals(0, page.refreshes)

        // The reader's or the timer's push: asked for a second later.
        advanceTimeBy(60_000)
        changes.value++
        runCurrent()
        advanceTimeBy(PageFreshness.SETTLE_MS - 1)
        assertEquals(0, page.refreshes)
        advanceTimeBy(2)
        assertEquals(1, page.refreshes)
        assertEquals(1, writeReloads)
    }

    @Test
    fun aChangeWhileTheFirstLoadRunsIsAskedForOnceItEnds() = runTest {
        val page = Page(this)
        page.loaded = false
        page.freshness.resumed()
        advanceTimeBy(2_000)
        page.freshness.changed()
        advanceUntilIdle()
        assertEquals(0, page.refreshes)
        page.loaded = true
        page.freshness.loadFinished()
        advanceUntilIdle()
        assertEquals(1, page.refreshes)
    }

    @Test
    fun leavingBeforeTheRefreshKeepsItDue() = runTest {
        val page = opened()
        page.freshness.paused()
        page.freshness.stopped()
        page.freshness.resumed()
        advanceTimeBy(500)
        // Straight into the reader: nothing asked while it's covered.
        page.freshness.paused()
        advanceUntilIdle()
        assertEquals(0, page.refreshes)
        page.freshness.resumed()
        advanceUntilIdle()
        assertEquals(1, page.refreshes)
    }

    private companion object {
        /** elapsedRealtime at the test's start. */
        const val START = 5_000_000L
    }
}
