package io.github.ottershelf.feature.achievements

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.github.ottershelf.core.tracking.AchievementItem
import io.github.ottershelf.core.tracking.CelebrationClaim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** When the unlock celebration claims, holds, drops and acknowledges, against a fake server and clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class CelebrationViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Server {
        val offered = ArrayDeque<CelebrationClaim>()
        var claims = 0
        val acknowledged = mutableListOf<String>()
        /** While set, a claim's answer waits for it. */
        var answer: CompletableDeferred<Unit>? = null
    }

    private class Setup(val vm: CelebrationViewModel, val server: Server, val writes: MutableSharedFlow<Int>, val unshown: MutableStateFlow<List<CelebrationClaim>>)

    private fun claim(id: String) = CelebrationClaim(id, achievement = AchievementItem(key = id, name = "Badge $id"))

    private fun TestScope.setup(): Setup {
        val server = Server()
        val writes = MutableSharedFlow<Int>()
        val unshown = MutableStateFlow<List<CelebrationClaim>>(emptyList())
        val vm = CelebrationViewModel(
            claimNext = {
                server.claims++
                server.answer?.await()
                server.offered.removeFirstOrNull()
            },
            acknowledge = { server.acknowledged += it },
            writes = writes,
            sent = emptyFlow(),
            unshown = unshown,
            takeUnshown = {
                var taken: CelebrationClaim? = null
                unshown.update { list -> taken = list.firstOrNull(); list.drop(1) }
                taken
            },
            ackScope = backgroundScope,
            clock = { scheduler.currentTime },
        )
        runCurrent()
        return Setup(vm, server, writes, unshown)
    }

    @Test
    fun nothingIsClaimedInTheBackground() = runTest(dispatcher) {
        val s = setup()
        s.server.offered += claim("c1")
        s.writes.emit(1) // a queued session flushed by WorkManager while the app is stopped
        advanceUntilIdle()
        assertEquals(0, s.server.claims)

        s.vm.onStart(blockedHere = false)
        runCurrent()
        assertEquals("c1", s.vm.current.value?.claimId)

        // A claim still waiting to go out when the app leaves is dropped.
        s.vm.seen()
        s.vm.onStop()
        advanceUntilIdle()
        assertEquals(1, s.server.claims)
    }

    @Test
    fun aRequestAlreadyOutLandsEvenIfAScreenBlocksMeanwhile() = runTest(dispatcher) {
        val s = setup()
        s.server.offered += claim("c1")
        s.server.answer = CompletableDeferred()
        s.vm.onStart(blockedHere = false)
        runCurrent()
        assertEquals(1, s.server.claims)

        s.vm.setBlocked(true) // the user opens a book while the POST is out: the server has already claimed
        s.server.answer!!.complete(Unit)
        runCurrent()
        assertEquals("c1", s.vm.current.value?.claimId) // held until the user leaves the book page

        s.vm.setBlocked(false)
        assertEquals("c1", s.vm.current.value?.claimId)
    }

    @Test
    fun aClaimHeldPastMostOfTheLeaseIsDroppedUnacknowledged() = runTest(dispatcher) {
        val s = setup()
        s.server.offered += claim("c1")
        s.vm.onStart(blockedHere = false)
        runCurrent()
        s.vm.setBlocked(true) // the user reads for half an hour
        advanceTimeBy(30 * 60_000L)

        s.server.offered += claim("c2")
        s.vm.setBlocked(false)
        assertNull(s.vm.current.value) // the server may already have offered c1 again (the finish flow)
        advanceTimeBy(EVALUATION_DELAY_MS + 1)
        assertEquals("c2", s.vm.current.value?.claimId)
        assertTrue(s.server.acknowledged.isEmpty())
    }

    @Test
    fun aWriteWhileTheRequestIsOutClaimsOnceMore() = runTest(dispatcher) {
        val s = setup()
        val answer = CompletableDeferred<Unit>()
        s.server.answer = answer
        s.vm.onStart(blockedHere = false)
        runCurrent()
        s.writes.emit(1) // the reader's last session lands while the claim is on its way
        runCurrent()
        s.server.answer = null
        answer.complete(Unit) // that claim found nothing: the session wasn't evaluated yet
        runCurrent()
        assertNull(s.vm.current.value)
        s.server.offered += claim("marathon")
        advanceUntilIdle()
        assertEquals(2, s.server.claims)
        assertEquals("marathon", s.vm.current.value?.claimId)
    }

    @Test
    fun laterWritesPushTheWaitBack() = runTest(dispatcher) {
        val s = setup()
        s.vm.onStart(blockedHere = true) // in the foreground, on a blocking screen's way out: no claim yet
        runCurrent()

        s.writes.emit(1)
        runCurrent()
        advanceTimeBy(1_000)
        s.writes.emit(2) // a second write a second later: its own evaluation time counts
        runCurrent()
        advanceTimeBy(EVALUATION_DELAY_MS - 500)
        assertEquals(0, s.server.claims)
        advanceTimeBy(1_000)
        assertEquals(1, s.server.claims)
    }

    @Test
    fun aClaimTheBookPageHandedOverShowsFirst() = runTest(dispatcher) {
        val s = setup()
        s.vm.onStart(blockedHere = false)
        advanceUntilIdle()
        assertEquals(1, s.server.claims)

        s.unshown.value = listOf(claim("from-book"), claim("second"))
        runCurrent()
        assertEquals("from-book", s.vm.current.value?.claimId)

        s.vm.seen()
        assertEquals("second", s.vm.current.value?.claimId) // before asking the server again
        s.vm.seen()
        advanceUntilIdle()
        assertEquals(listOf("from-book", "second"), s.server.acknowledged)
        assertEquals(2, s.server.claims)
    }
}
