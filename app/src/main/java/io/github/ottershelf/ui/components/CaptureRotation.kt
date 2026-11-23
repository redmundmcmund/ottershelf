package io.github.ottershelf.ui.components

import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.ImageCapture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Keeps [capture]'s target rotation on how the phone is held, from the orientation sensor, while
 * this is in the composition: a photo taken in landscape comes out upright even when the display
 * doesn't turn (auto-rotate off). The public stand-in for CameraX's
 * `SessionConfig.Builder.setAutoRotationEnabled`, which is restricted to CameraX's own libraries
 * and, like this, turns only the capture, never the preview.
 */
@Composable
fun CaptureFollowsDeviceRotation(capture: ImageCapture) {
    val context = LocalContext.current
    DisposableEffect(context, capture) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                capture.targetRotation = surfaceRotation(orientation)
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
}

/** The [Surface] rotation for a sensor [orientation] in degrees (clockwise from upright), as CameraX documents it. */
internal fun surfaceRotation(orientation: Int): Int = when (orientation) {
    in 45 until 135 -> Surface.ROTATION_270
    in 135 until 225 -> Surface.ROTATION_180
    in 225 until 315 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0
}
