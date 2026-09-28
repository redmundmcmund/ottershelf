package io.github.ottershelf.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.util.WeakHashMap

/**
 * Keeps the screen on while [on] is true and this is composed.
 *
 * Screens overlap on the window: the one leaving stays composed through the navigation's crossfade
 * after the one arriving has asked for the screen on (a reader replaced by the next book's, the timer
 * opened over a reader from its notification, Back from the timer to the reader). So each screen
 * holds it for itself ([ScreenOnHolders]): the screen stays on while any of them holds it, and one
 * leaving never turns it off for another.
 */
@Composable
fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    val holder = remember { Any() }
    DisposableEffect(view, on) {
        if (on) ScreenOnHolders.hold(view, holder)
        onDispose { ScreenOnHolders.release(view, holder) }
    }
}

/**
 * The readers keep the screen on, but only for [READING_SCREEN_ON_MS] after the last sign that the user
 * is reading: a change in any of [activity] (the page or position, the bars shown or hidden), or the
 * reader coming back on screen (unlocked, or back from another app, before any page is turned).
 * After that the phone's own screen timeout applies again, so a book left open on a table doesn't
 * keep the screen lit until the battery runs down.
 */
@Composable
fun KeepScreenOnWhileReading(vararg activity: Any?) {
    // Coming back is counted on the ON_START event, not read from the lifecycle's state: leaving and
    // coming back can both happen before the next frame, and the state would then look unchanged.
    var starts by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { starts++ }
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(starts, *activity) {
        on = true
        delay(READING_SCREEN_ON_MS)
        on = false
    }
    KeepScreenOn(on)
}

/** Ten minutes: longer than a slow page, short enough not to matter when the phone is put down. */
const val READING_SCREEN_ON_MS = 10 * 60 * 1000L

/**
 * Who holds the screen on, per window view: the view's keepScreenOn is set while anyone does, and
 * cleared when the last one lets go. Main thread only.
 */
internal object ScreenOnHolders {
    private val views = WeakHashMap<View, MutableSet<Any>>()

    fun hold(view: View, holder: Any) {
        views.getOrPut(view) { HashSet() }.add(holder)
        view.keepScreenOn = true
    }

    fun release(view: View, holder: Any) {
        val holders = views[view] ?: return
        holders.remove(holder)
        if (holders.isEmpty()) {
            views.remove(view)
            view.keepScreenOn = false
        }
    }
}
