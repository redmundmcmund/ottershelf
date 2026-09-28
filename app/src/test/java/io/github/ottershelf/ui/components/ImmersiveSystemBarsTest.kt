package io.github.ottershelf.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Immersive readers overlapping on one window: Read on the next book replaces one reader with
 * another, and the one leaving stays composed through the crossfade after the new one has hidden
 * the bars. Its leaving must not bring them back over the new reader.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveSystemBarsTest {

    @get:Rule
    val rule = createComposeRule()

    /** What the window's bars were last set to (true: shown). */
    private var bars: Boolean? = null
    private val set = mutableListOf<Boolean>()
    private val show: (Boolean) -> Unit = {
        bars = it
        set += it
    }

    @Test
    fun theReaderLeavingAfterItsReplacementArrivedLeavesTheBarsHidden() {
        val owners = ImmersiveOwners()
        val first = mutableStateOf(true)
        val second = mutableStateOf(false)
        rule.setContent {
            if (first.value) ImmersiveSystemBars(barsVisible = false, owners = owners, key = "A", setUp = {}, show = show)
            if (second.value) ImmersiveSystemBars(barsVisible = false, owners = owners, key = "B", setUp = {}, show = show)
        }
        rule.runOnIdle { assertEquals(false, bars) }
        // The next book's reader arrives while the first is still fading out...
        second.value = true
        rule.runOnIdle { assertEquals(false, bars) }
        // ...and the first leaves 700 ms later: the bars stay hidden over the new one.
        first.value = false
        rule.runOnIdle { assertEquals(false, bars) }
        // Leaving the new reader too brings them back.
        second.value = false
        rule.runOnIdle { assertEquals(true, bars) }
    }

    @Test
    fun onlyTheNewestReaderSetsTheBars() {
        val owners = ImmersiveOwners()
        val a = ImmersiveOwners.Owner().apply { show = { set += it } }
        val b = ImmersiveOwners.Owner().apply { show = { set += it } }
        owners.enter(a, visible = false)
        owners.enter(b, visible = false)
        set.clear()
        // The old one's bars toggling on its way out changes nothing.
        owners.update(a, visible = true)
        assertEquals(emptyList<Boolean>(), set)
        // The new one's do.
        owners.update(b, visible = true)
        assertEquals(listOf(true), set)
        // A reader under it leaving puts the newest's wish back; the last one out shows the bars.
        owners.update(b, visible = false)
        owners.leave(a)
        assertEquals(listOf(true, false, false), set)
        owners.leave(b)
        assertEquals(listOf(true, false, false, true), set)
    }
}
