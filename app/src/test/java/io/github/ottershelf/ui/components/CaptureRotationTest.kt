package io.github.ottershelf.ui.components

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureRotationTest {
    @Test
    fun sensorDegreesMapToSurfaceRotations() {
        // Upright, and the 45° either side of it.
        listOf(0, 44, 315, 359).forEach { assertEquals("$it°", Surface.ROTATION_0, surfaceRotation(it)) }
        // Turned clockwise to landscape: the top of the phone points right.
        listOf(45, 90, 134).forEach { assertEquals("$it°", Surface.ROTATION_270, surfaceRotation(it)) }
        listOf(135, 180, 224).forEach { assertEquals("$it°", Surface.ROTATION_180, surfaceRotation(it)) }
        listOf(225, 270, 314).forEach { assertEquals("$it°", Surface.ROTATION_90, surfaceRotation(it)) }
    }
}
