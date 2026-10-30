package io.github.ottershelf.devicetest

import android.os.SystemClock
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.ottershelf.ui.nav.DrawerBackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real Back key (through the window's OnBackInvokedDispatcher, where Android decides whether the
 * app takes Back) pressed while the drawer over the Dashboard is still opening closes the drawer
 * instead of leaving the app (ui.nav.DrawerBackHandler). Reproduced on a Pixel 8 Pro, Android 17,
 * 2026-10-30: Back 0.1-0.2 s after opening the menu sent the app home; 0.6 s closed the menu.
 */
@RunWith(AndroidJUnit4::class)
class DrawerBackDeviceTest {

    private class Outcome(val drawerClosed: Boolean, val left: Boolean)

    private fun backWhileOpening(shellHandler: Boolean): Outcome {
        lateinit var drawer: DrawerState
        lateinit var scope: CoroutineScope
        LockScreenHost.launch().use { host ->
            host.setContent {
                scope = rememberCoroutineScope()
                drawer = rememberDrawerState(DrawerValue.Closed)
                if (shellHandler) DrawerBackHandler(drawer) { scope.launch { drawer.close() } }
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = { ModalDrawerSheet(drawerState = drawer) { Text("menu") } },
                ) { Text("dashboard") }
            }
            SystemClock.sleep(1_000)
            Device.onMain { scope.launch { drawer.open() } }
            SystemClock.sleep(80)
            val opening = Device.onMain { drawer.targetValue == DrawerValue.Open && !drawer.isOpen }
            host.requireOnTop()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            SystemClock.sleep(1_500)
            val outcome = Device.onMain { Outcome(drawer.isClosed, host.activity.isFinishing || host.activity.isDestroyed) }
            Device.report("handler=$shellHandler: still opening at Back=$opening, drawer closed=${outcome.drawerClosed}, left=${outcome.left}")
            return outcome
        }
    }

    @Test
    fun backWhileTheDrawerIsOpeningClosesIt() {
        val outcome = backWhileOpening(shellHandler = true)
        assertFalse("Back left the app", outcome.left)
        assertTrue("Back left the drawer open", outcome.drawerClosed)
    }

    /** Without the shell's handler, for the record: the same Back leaves the app (reported, not asserted). */
    @Test
    fun materialAloneForTheRecord() {
        backWhileOpening(shellHandler = false)
    }
}
