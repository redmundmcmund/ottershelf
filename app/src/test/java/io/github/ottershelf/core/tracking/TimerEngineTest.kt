package io.github.ottershelf.core.tracking

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.settings.TimerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TimerEngine on a fake clock: active time, persistence, the notification calls, results. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerEngineTest {

    private class FakeNotifier : TimerNotifier {
        var active: ActiveTimer? = null
        var finished: FinishedTimer? = null
        var alarmAt: Long? = null
        var timesUp = 0
        override fun showActive(timer: ActiveTimer, now: Long) { active = timer }
        override fun cancelActive() { active = null }
        override fun showFinished(timer: FinishedTimer) { finished = timer }
        override fun cancelFinished() { finished = null }
        override fun scheduleTimesUp(atMs: Long) { alarmAt = atMs }
        override fun cancelTimesUp() { alarmAt = null }
        override fun showTimesUp(timer: ActiveTimer) { timesUp++ }
    }

    private var now = 1_000_000L
    private var ids = 0

    private class Setup(
        val engine: TimerEngine,
        val notifier: FakeNotifier,
        val saved: MutableList<FinishedTimer>,
        val account: MutableStateFlow<String?>,
    )

    private fun TestScope.setup(store: MemoryStore = MemoryStore(), account: String? = "acc"): Setup {
        val notifier = FakeNotifier()
        val saved = mutableListOf<FinishedTimer>()
        val accountFlow = MutableStateFlow(account)
        val engine = TimerEngine(
            store = store,
            account = accountFlow,
            notifier = notifier,
            scope = backgroundScope,
            saveUnconfirmed = { saved += it },
            clock = { now },
            newId = { "session-${++ids}" },
        )
        engine.start()
        runCurrent()
        return Setup(engine, notifier, saved, accountFlow)
    }

    @Test
    fun pausedTimeNeverCounts() = runTest {
        val s = setup()
        s.engine.start(bookId = 1, fileId = 10, title = "Dracula")
        now += 60_000
        s.engine.pause()
        now += 600_000 // ten minutes paused
        s.engine.resume()
        now += 30_000
        val done = s.engine.stop()!!
        assertEquals(90_000, done.activeMs)
        assertEquals(1_000_000L, done.startedAtMs)
        assertEquals(now, done.endedAtMs)
        assertEquals("session-1", done.sessionId)
        assertNull(s.engine.state.value.active)
        assertEquals(done, s.engine.state.value.finished)
        assertNull(s.notifier.active)
        assertEquals(done, s.notifier.finished)
    }

    @Test
    fun stoppingWhilePausedEndsAtThePause() = runTest {
        val s = setup()
        s.engine.start(1, 10, "Dracula")
        now += 120_000
        s.engine.pause()
        val pausedAt = now
        now += 8 * 3_600_000 // left overnight
        val done = s.engine.stop()!!
        assertEquals(120_000, done.activeMs)
        assertEquals(pausedAt, done.endedAtMs)
    }

    @Test
    fun oneTimerAtATime() = runTest {
        val s = setup()
        assertTrue(s.engine.start(1, 10, "Dracula") is StartResult.Started)
        assertTrue(s.engine.start(1, 10, "Dracula") is StartResult.AlreadyRunning)
        assertTrue(s.engine.start(2, 20, "Emma") is StartResult.Busy)
        assertEquals(1L, s.engine.state.value.active!!.bookId)
    }

    @Test
    fun survivesTheAppBeingKilled() = runTest {
        val store = MemoryStore()
        val s = setup(store)
        s.engine.start(1, 10, "Dracula")
        now += 45_000
        s.engine.pause()

        val again = setup(store)
        val restored = again.engine.state.value.active!!
        assertTrue(restored.paused)
        assertEquals(45_000, restored.activeMs(now + 99_999))
        assertEquals(restored, again.notifier.active) // the notification comes back
    }

    @Test
    fun theTimerBelongsToItsAccount() = runTest {
        val store = MemoryStore()
        val s = setup(store)
        s.engine.start(1, 10, "Dracula")
        s.account.value = null
        runCurrent()
        assertNull(s.engine.state.value.active)
        assertNull(s.notifier.active)
        s.account.value = "acc"
        runCurrent()
        assertEquals(1L, s.engine.state.value.active!!.bookId)
    }

    @Test
    fun signingOutPausesARunningTimerThere() = runTest {
        val s = setup()
        s.engine.start(1, 10, "Dracula")
        now += 60_000
        s.account.value = null // signed out, or the server ended the session
        runCurrent()
        now += 3 * 3_600_000 // hours later...
        s.account.value = "acc"
        runCurrent()
        val back = s.engine.state.value.active!!
        assertTrue(back.paused)
        assertEquals("the signed-out time doesn't count", 60_000, back.activeMs(now))
    }

    @Test
    fun anInAppSaveStopsWithoutTheSaveYourSessionNotification() = runTest {
        val s = setup()
        s.engine.start(1, 10, "Dracula")
        now += 120_000
        val done = s.engine.stop(announce = false)!!
        assertNull(s.notifier.finished)
        assertEquals(done, s.engine.state.value.finished)
    }

    @Test
    fun aWaitingResultIsAnnouncedAgainWhenBroughtBack() = runTest {
        val store = MemoryStore()
        val s = setup(store)
        s.engine.start(1, 10, "Dracula")
        now += 120_000
        s.engine.stop()
        s.notifier.finished = null // swiped away, or the phone restarted

        val again = setup(store)
        assertEquals(1L, again.notifier.finished!!.bookId)
    }

    @Test
    fun stoppingAsOfAnEarlierMomentEndsThere() = runTest {
        val s = setup()
        s.engine.start(1, 10, "Dracula")
        now += 600_000
        val openedAt = now
        now += 20_000 // the reader's notice was answered 20 s later
        val done = s.engine.stop(atMs = openedAt)!!
        assertEquals(600_000, done.activeMs)
        assertEquals(openedAt, done.endedAtMs)
    }

    @Test
    fun itsActiveStretchesAreKeptForTheSave() = runTest {
        val s = setup()
        s.engine.start(1, 10, "Dracula")
        val first = now
        now += 60_000
        s.engine.pause()
        now += 600_000
        s.engine.resume()
        val second = now
        now += 30_000
        val done = s.engine.stop()!!
        assertEquals(listOf(ActiveSpan(first, first + 60_000), ActiveSpan(second, second + 30_000)), done.spans)
    }

    @Test
    fun aTimerStartedBeforeItsBookLoadedIsFilledInLater() = runTest {
        val s = setup()
        val started = (s.engine.start(1, null, "") as StartResult.Started).timer
        s.engine.fillIn(started.sessionId, fileId = 10, title = "Dracula", startProgress = 12.5)
        val timer = s.engine.state.value.active!!
        assertEquals(10L, timer.fileId)
        assertEquals("Dracula", timer.title)
        assertEquals(12.5, timer.startProgress!!, 0.0)
        assertEquals("the start time is the tap", started.startedAtMs, timer.startedAtMs)
    }

    @Test
    fun aResultPushedOutBeforeItWasSavedIsSavedWithoutAPage() = runTest {
        val s = setup()
        s.engine.start(1, 10, "Dracula")
        now += 600_000
        val first = s.engine.stop()!!
        s.engine.start(2, 20, "Emma")
        now += 300_000
        s.engine.stop()
        runCurrent()
        assertEquals(listOf(first), s.saved)
        assertEquals(2L, s.engine.state.value.finished!!.bookId)

        s.engine.clearFinished("session-2")
        assertNull(s.engine.state.value.finished)
        assertNull(s.notifier.finished)
    }

    @Test
    fun countdownSetsItsAlarmAndAnnouncesTheEnd() = runTest {
        val s = setup()
        s.engine.start(1, 10, "Dracula", mode = TimerMode.COUNTDOWN, targetMinutes = 20)
        assertEquals(now + 20 * 60_000, s.notifier.alarmAt)
        now += 5 * 60_000
        s.engine.pause()
        assertNull(s.notifier.alarmAt)
        now += 60_000
        s.engine.resume()
        assertEquals(now + 15 * 60_000, s.notifier.alarmAt)
        assertEquals(15 * 60_000L, s.engine.state.value.active!!.remainingMs(now))

        now += 10 * 60_000
        s.engine.onTimesUp() // an early alarm: set again
        assertEquals(0, s.notifier.timesUp)
        assertEquals(now + 5 * 60_000, s.notifier.alarmAt)
        now += 5 * 60_000
        s.engine.onTimesUp()
        assertEquals(1, s.notifier.timesUp)
    }

    @Test
    fun tooShortResultsAreFlagged() = runTest {
        val s = setup()
        s.engine.start(1, 10, "Dracula")
        now += 9_000
        val done = s.engine.stop()!!
        assertTrue(done.tooShort)
        assertNull(s.notifier.finished) // nothing to save, nothing to announce
        assertFalse(FinishedTimer("x", 1, 1, "", 0, 10_000, 10_000).tooShort)
    }
}
