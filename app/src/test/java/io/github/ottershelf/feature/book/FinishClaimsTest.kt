package io.github.ottershelf.feature.book

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.tracking.AchievementItem
import io.github.ottershelf.core.tracking.CelebrationClaim
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * Where the finish flow's next achievement goes: its own flow while it goes on on the page, else the
 * shell's celebration, including when the user left the page from the flow (the next book in the series)
 * and the page is still alive on the back stack under the next book.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinishClaimsTest {

    private val badge = CelebrationClaim("c1", achievement = AchievementItem(key = "finished-10", name = "Ten books"))
    private val answer = CompletableDeferred<CelebrationClaim?>()
    private val shown = mutableListOf<CelebrationClaim>()
    private val handedOver = mutableListOf<CelebrationClaim>()
    private val page = CoroutineScope(Job())

    private fun TestScope.claims() = FinishClaims(
        appScope = this,
        pageScope = page,
        claim = { answer.await() },
        show = { shown += it },
        handOver = { handedOver += it },
    )

    @Test
    fun theFlowGoingOnOnThePageShowsItThere() = runTest {
        claims().next(onPage = true)
        answer.complete(badge)
        runCurrent()
        assertEquals(listOf(badge), shown)
        assertEquals(emptyList<CelebrationClaim>(), handedOver)
    }

    @Test
    fun leavingThePageFromTheFlowHandsItToTheShell() = runTest {
        // Read or Details on the next book: the page stays alive under it, but isn't on screen.
        claims().next(onPage = false)
        answer.complete(badge)
        runCurrent()
        assertEquals(emptyList<CelebrationClaim>(), shown)
        assertEquals(listOf(badge), handedOver)
    }

    @Test
    fun aPageThatClosedWhileTheClaimWasOutHandsItToTheShell() = runTest {
        claims().next(onPage = true)
        runCurrent()
        page.cancel()
        answer.complete(badge)
        runCurrent()
        assertEquals(emptyList<CelebrationClaim>(), shown)
        assertEquals(listOf(badge), handedOver)
    }

    @Test
    fun nothingWaitingOrAFailureShowsNothing() = runTest {
        claims().next(onPage = true)
        answer.complete(null)
        runCurrent()
        val failing = FinishClaims(this, page, claim = { throw IOException("offline") }, show = { shown += it }, handOver = { handedOver += it })
        failing.next(onPage = false)
        runCurrent()
        assertEquals(emptyList<CelebrationClaim>(), shown)
        assertEquals(emptyList<CelebrationClaim>(), handedOver)
    }
}
