package io.github.ottershelf.feature.scan

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scanner's way out: once the user went back, the screen stays composed through the navigation's
 * crossfade with its entry held at CREATED (NavDisplay's back-stack-aware lifecycle). A lookup
 * answering then must not replace the screen the user went back to.
 */
@RunWith(AndroidJUnit4::class)
class ScanLeavingTest {

    @get:Rule
    val rule = createComposeRule()

    private class Entry : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun aResultAfterTheUserWentBackGoesNowhere() {
        val entry = Entry()
        val events = Channel<ScanNav>(Channel.BUFFERED)
        val acted = mutableListOf<ScanNav>()
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                CollectWhileResumed(events.receiveAsFlow()) { acted += it }
            }
        }
        rule.runOnIdle { events.trySend(ScanNav.OpenBook(7)) }
        rule.runOnIdle { assertEquals(listOf<ScanNav>(ScanNav.OpenBook(7)), acted) }
        // Back: the entry leaves the back stack and fades out, held at CREATED.
        rule.runOnIdle { entry.registry.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle { events.trySend(ScanNav.OpenBook(8)) }
        rule.runOnIdle { assertEquals(listOf<ScanNav>(ScanNav.OpenBook(7)), acted) }
    }

    @Test
    fun aResultWhileTheAppIsAwayOpensWhenTheUserComesBack() {
        val entry = Entry()
        val events = Channel<ScanNav>(Channel.BUFFERED)
        val acted = mutableListOf<ScanNav>()
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                CollectWhileResumed(events.receiveAsFlow()) { acted += it }
            }
        }
        rule.runOnIdle { entry.registry.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle { events.trySend(ScanNav.OpenBook(7)) }
        rule.runOnIdle { assertEquals(emptyList<ScanNav>(), acted) }
        rule.runOnIdle { entry.registry.currentState = Lifecycle.State.RESUMED }
        rule.runOnIdle { assertEquals(listOf<ScanNav>(ScanNav.OpenBook(7)), acted) }
    }
}
