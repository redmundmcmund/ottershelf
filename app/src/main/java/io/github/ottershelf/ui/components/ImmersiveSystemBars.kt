package io.github.ottershelf.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.WeakHashMap

/**
 * A reader filling the screen: the status and navigation bars hide while [barsVisible] is false (a
 * swipe from an edge shows them for a moment), and come back when the last such screen leaves.
 *
 * Readers can overlap: Read on the next book replaces one reader with another, and the one leaving
 * stays composed for the navigation's crossfade after the new one has hidden the bars. So the newest
 * immersive screen on the window decides ([ImmersiveOwners]): the one leaving doesn't bring the bars
 * back over the one arriving. Applied again on every return to the app (the system may have shown
 * them meanwhile).
 */
@Composable
fun ImmersiveSystemBars(barsVisible: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    val owners = remember(window) { ImmersiveOwners.of(window) }
    ImmersiveSystemBars(
        barsVisible = barsVisible,
        owners = owners,
        key = controller,
        setUp = { controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE },
        show = { visible ->
            if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        },
    )
}

/** [ImmersiveSystemBars] over [owners], with [show] doing the window's part (tests pass their own). */
@Composable
internal fun ImmersiveSystemBars(
    barsVisible: Boolean,
    owners: ImmersiveOwners,
    key: Any,
    setUp: () -> Unit,
    show: (visible: Boolean) -> Unit,
) {
    val owner = remember(owners) { ImmersiveOwners.Owner() }
    DisposableEffect(owners, key) {
        setUp()
        owner.show = show
        owners.enter(owner, barsVisible)
        onDispose { owners.leave(owner) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owners, key, barsVisible, lifecycle) {
        owners.update(owner, barsVisible)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) owners.update(owner, barsVisible) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * The immersive screens composed on one window, oldest first. Only the newest sets the bars; when it
 * leaves, the one before it (if any is still there) sets them again, and when the last leaves they
 * are shown. Main thread only.
 */
internal class ImmersiveOwners {
    class Owner {
        var visible = true
        var show: (Boolean) -> Unit = {}
    }

    private val owners = ArrayList<Owner>()

    fun enter(owner: Owner, visible: Boolean) {
        owners.remove(owner)
        owners += owner
        owner.visible = visible
        owner.show(visible)
    }

    /** [owner]'s bars should now be [visible]: applied if it is the newest. */
    fun update(owner: Owner, visible: Boolean) {
        owner.visible = visible
        if (owners.lastOrNull() === owner) owner.show(visible)
    }

    fun leave(owner: Owner) {
        owners.remove(owner)
        val newest = owners.lastOrNull()
        // The last one out brings the bars back; otherwise the newest still here keeps them as it wants.
        if (newest == null) owner.show(true) else newest.show(newest.visible)
    }

    companion object {
        private val windows = WeakHashMap<Window, ImmersiveOwners>()

        fun of(window: Window): ImmersiveOwners = windows.getOrPut(window) { ImmersiveOwners() }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
