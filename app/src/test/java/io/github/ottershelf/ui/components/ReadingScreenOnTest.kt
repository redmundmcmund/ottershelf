package io.github.ottershelf.ui.components

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keeping the screen on: the readers' ten minutes after the last page (again after a break), and
 * screens overlapping on one window through the navigation's crossfade (Read next, the timer opened
 * over a reader and Back from it), where the one leaving must not turn the screen off for the one
 * arriving.
 */
@RunWith(AndroidJUnit4::class)
class ReadingScreenOnTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var view: View

    private class Entry : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun screenOn(): Boolean {
        var on = false
        rule.runOnIdle { on = view.keepScreenOn }
        return on
    }

    private fun pass(ms: Long) = rule.mainClock.advanceTimeBy(ms)

    @Test
    fun theReaderKeepsTheScreenOnForTenMinutesAfterTheLastPage() {
        val page = mutableIntStateOf(1)
        rule.setContent {
            view = LocalView.current
            KeepScreenOnWhileReading(page.intValue)
        }
        assertTrue(screenOn())
        pass(READING_SCREEN_ON_MS - SECOND)
        assertTrue(screenOn())
        pass(2 * SECOND)
        assertFalse(screenOn())
        // A page turned: another ten minutes.
        rule.runOnIdle { page.intValue = 2 }
        assertTrue(screenOn())
        pass(READING_SCREEN_ON_MS - SECOND)
        assertTrue(screenOn())
        pass(2 * SECOND)
        assertFalse(screenOn())
    }

    @Test
    fun comingBackAfterALongBreakKeepsTheScreenOnBeforeAnyPageIsTurned() {
        val entry = Entry()
        rule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                KeepScreenOnWhileReading(1, false)
            }
        }
        pass(5 * MINUTE)
        // The user locks the phone for half an hour, long past the ten minutes...
        rule.runOnIdle { entry.registry.currentState = Lifecycle.State.CREATED }
        pass(30 * MINUTE)
        rule.runOnIdle { entry.registry.currentState = Lifecycle.State.RESUMED }
        assertTrue(screenOn())
        // ...and they start again when the user is back, on the same page.
        pass(READING_SCREEN_ON_MS - SECOND)
        assertTrue(screenOn())
        pass(2 * SECOND)
        assertFalse(screenOn())
        // Put down until the screen went off by itself, then woken and unlocked.
        rule.runOnIdle { entry.registry.currentState = Lifecycle.State.CREATED }
        pass(MINUTE)
        rule.runOnIdle { entry.registry.currentState = Lifecycle.State.RESUMED }
        assertTrue(screenOn())
    }

    @Test
    fun awayAndBackBeforeTheNextFrameStillStartsTheTenMinutesAgain() {
        val entry = Entry()
        rule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                KeepScreenOnWhileReading(1)
            }
        }
        // Away and back with no frame in between: the lifecycle's state looks the same afterwards.
        fun awayAndBack() {
            rule.runOnIdle {
                entry.registry.currentState = Lifecycle.State.CREATED
                entry.registry.currentState = Lifecycle.State.RESUMED
            }
            rule.waitForIdle()
        }
        // After the screen went off by itself...
        pass(READING_SCREEN_ON_MS + SECOND)
        assertFalse(screenOn())
        awayAndBack()
        assertTrue(screenOn())
        // ...and before: another full ten minutes from coming back.
        pass(9 * MINUTE)
        awayAndBack()
        pass(READING_SCREEN_ON_MS - SECOND)
        assertTrue(screenOn())
        pass(2 * SECOND)
        assertFalse(screenOn())
    }

    @Test
    fun theReaderLeavingAfterTheNextOneArrivedLeavesTheScreenOn() {
        val first = mutableStateOf(true)
        val second = mutableStateOf(false)
        rule.setContent {
            view = LocalView.current
            if (first.value) KeepScreenOnWhileReading(0.62f, false)
            // The next book opens at its start, the reader's initial position: no key changes.
            if (second.value) KeepScreenOnWhileReading(0f, false)
        }
        // Read next: the new reader arrives while the old one is still fading out...
        rule.runOnIdle { second.value = true }
        assertTrue(screenOn())
        // ...and the old one leaves 700 ms later.
        rule.runOnIdle { first.value = false }
        assertTrue(screenOn())
        // Leaving the new one too lets the screen go off.
        rule.runOnIdle { second.value = false }
        assertFalse(screenOn())
    }

    @Test
    fun theTimerAndAReaderDoNotTurnTheScreenOffForEachOther() {
        val reader = mutableStateOf(true)
        val timer = mutableStateOf(false)
        val timerRunning = mutableStateOf(true)
        rule.setContent {
            view = LocalView.current
            if (reader.value) KeepScreenOnWhileReading(0.3f, false)
            if (timer.value) KeepScreenOn(timerRunning.value)
        }
        // The timer's notification opens the timer over the reader, which then fades out.
        rule.runOnIdle { timer.value = true }
        rule.runOnIdle { reader.value = false }
        assertTrue(screenOn())
        // Back to the reader: the timer fading out leaves the reader's screen on.
        rule.runOnIdle { reader.value = true }
        rule.runOnIdle { timer.value = false }
        assertTrue(screenOn())
        // A paused timer doesn't hold the screen, a running one does.
        rule.runOnIdle {
            reader.value = false
            timer.value = true
            timerRunning.value = false
        }
        assertFalse(screenOn())
        rule.runOnIdle { timerRunning.value = true }
        assertTrue(screenOn())
    }

    @Test
    fun aReaderPastItsTenMinutesDoesNotTurnTheScreenOffForTheTimer() {
        val reader = mutableStateOf(true)
        rule.setContent {
            view = LocalView.current
            KeepScreenOn(true)
            if (reader.value) KeepScreenOnWhileReading(1)
        }
        pass(READING_SCREEN_ON_MS + SECOND)
        assertTrue(screenOn())
        rule.runOnIdle { reader.value = false }
        assertTrue(screenOn())
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
    }
}
