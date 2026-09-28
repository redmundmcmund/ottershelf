package io.github.ottershelf.feature.book

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the book page's screen tells [PageFreshness] ([FollowShowing]), through the entry's
 * lifecycle as Navigation 3 drives it: a screen pushed over the page takes the entry out of
 * composition (a pause, not a stop), the app going to the background stops it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BookDetailShowingTest {

    @get:Rule
    val rule = createComposeRule()

    private class Entry : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val scope = TestScope()
    private var refreshes = 0
    private val freshness: PageFreshness = PageFreshness(scope, clock = { scope.currentTime + START }) {
        refreshes++
        freshness.reloaded()
        true
    }
    private val entry = Entry()
    private var shown by mutableStateOf(true)

    private fun showPage() {
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                if (shown) FollowShowing(freshness)
            }
        }
        settle()
    }

    /** Compose's effects run, then the page's delayed refresh, if any. */
    private fun settle() {
        rule.waitForIdle()
        scope.advanceUntilIdle()
    }

    /** A screen pushed over the page for [ms], [meanwhile] happening while it's covered. */
    private fun covered(ms: Long = 30_000, meanwhile: () -> Unit = {}) {
        rule.runOnIdle { shown = false }
        settle()
        scope.advanceTimeBy(ms)
        meanwhile()
        settle()
        rule.runOnIdle { shown = true }
        settle()
    }

    private fun lifecycle(state: Lifecycle.State) {
        rule.runOnIdle { entry.registry.currentState = state }
        settle()
    }

    @Test
    fun backFromAScreenOnTopAsksNothing() {
        showPage()
        covered()
        covered(ms = 3 * 60_000)
        assertEquals(0, refreshes)
    }

    @Test
    fun somethingSentWhileCoveredIsAskedForOnceItShows() {
        showPage()
        covered { freshness.changed() }
        assertEquals(1, refreshes)
    }

    @Test
    fun somethingSentWhileCoveredWaitsUntilItShows() {
        showPage()
        rule.runOnIdle { shown = false }
        settle()
        scope.advanceTimeBy(30_000)
        freshness.changed()
        settle()
        assertEquals(0, refreshes)
    }

    @Test
    fun theAppStoppedWhileThePageShowedAsksOnceWhenBack() {
        showPage()
        lifecycle(Lifecycle.State.CREATED)
        assertEquals(0, refreshes)
        lifecycle(Lifecycle.State.RESUMED)
        assertEquals(1, refreshes)
        covered()
        assertEquals(1, refreshes)
    }

    @Test
    fun theAppPausedWithoutStoppingAsksNothing() {
        showPage()
        // Another app's dialog, or split screen: paused, still started.
        lifecycle(Lifecycle.State.STARTED)
        lifecycle(Lifecycle.State.RESUMED)
        assertEquals(0, refreshes)
    }

    private companion object {
        /** elapsedRealtime at the test's start. */
        const val START = 5_000_000L
    }
}
