package io.github.ottershelf.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back over the Dashboard (the bottom entry, nothing to pop) closes the drawer instead of leaving
 * the app, including while the drawer is still opening (DrawerBackHandler). Material's own handler
 * waits until the drawer has settled open, and Android decides at the start of a back gesture.
 */
@RunWith(AndroidJUnit4::class)
class DrawerBackTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var drawer: DrawerState
    private lateinit var scope: CoroutineScope

    private fun show(shellHandler: Boolean = true) = rule.setContent {
        scope = rememberCoroutineScope()
        drawer = rememberDrawerState(DrawerValue.Closed)
        if (shellHandler) DrawerBackHandler(drawer) { scope.launch { drawer.close() } }
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = { ModalDrawerSheet(drawerState = drawer) { Text("menu") } },
        ) { Text("dashboard") }
    }

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun backWhileTheDrawerIsStillOpeningClosesIt() {
        show()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { scope.launch { drawer.open() } }
        rule.mainClock.advanceTimeBy(48)
        assertEquals("heading open", DrawerValue.Open, drawer.targetValue)
        assertFalse("not settled yet", drawer.isOpen)

        pressBack()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertFalse("Back left the app", rule.activity.isFinishing)
        assertTrue("Back left the drawer open", drawer.isClosed)
    }

    @Test
    fun backWithTheDrawerOpenClosesIt() {
        show()
        rule.runOnUiThread { scope.launch { drawer.open() } }
        rule.waitForIdle()
        assertTrue(drawer.isOpen)

        pressBack()
        rule.waitForIdle()
        assertFalse("Back left the app", rule.activity.isFinishing)
        assertTrue("Back left the drawer open", drawer.isClosed)
    }

    @Test
    fun backWithTheDrawerClosedLeavesTheApp() {
        show()
        rule.waitForIdle()
        pressBack()
        rule.waitForIdle()
        assertTrue("Back over the Dashboard should leave the app", rule.activity.isFinishing)
    }
}
