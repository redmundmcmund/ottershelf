package io.github.ottershelf.feature.library

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinch's drawing between its events ([GridPinch] without a grid): a second pinch while the
 * first is still springing back starts from what is on screen, and the zoom keeps the point under
 * the fingers where it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GridPinchStateTest {

    /** Frames every 16 ms of the test's clock, for the spring back. */
    private fun TestScope.frames(): CoroutineScope = backgroundScope + object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            return onFrame(testScheduler.currentTime * 1_000_000L)
        }
    }

    private fun GridPinch.threeColumns() = apply {
        columns = 3
        range = 2..6
    }

    private fun assertSame(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    private val points = listOf(Offset(0f, 0f), Offset(300f, 1500f), Offset(1100f, 250f))

    @Test
    fun aPinchDuringTheSpringBackStartsFromWhatIsDrawn() = runTest {
        val committed = mutableListOf<Int>()
        val pinch = GridPinch(frames()).threeColumns()
        pinch.onCommit = { committed += it }

        val first = Offset(500f, 1000f)
        pinch.begin(first)
        pinch.zoom(1.3f, first)
        assertEquals(1.3f, pinch.scale, 1e-4f)
        // Lifted past half way: two columns, drawn at the size the covers were (1.3 / 1.5)...
        pinch.end(first)
        assertEquals(listOf(2), committed)
        assertEquals(1.3f / 1.5f, pinch.scale, 1e-4f)
        // ...springing back to 1.
        runCurrent()
        advanceTimeBy(50)
        val springing = pinch.scale
        assertTrue("spring under way: $springing", springing > 1.3f / 1.5f && springing < 0.99f)

        // A second pinch lands: nothing moves on screen, and the spring stops there.
        val drawn = points.map(pinch::drawnAt)
        val second = Offset(420f, 700f)
        pinch.begin(second)
        points.zip(drawn).forEach { (p, was) -> assertSame(was, pinch.drawnAt(p)) }
        advanceTimeBy(500)
        assertEquals(springing, pinch.scale, 0f)

        // Its zoom keeps the point under the fingers under them.
        val under = (second - pinch.offset) / pinch.scale
        pinch.zoom(1.05f, second)
        assertEquals(springing * 1.05f, pinch.scale, 1e-4f)
        assertSame(second, pinch.drawnAt(under))

        // Lifted without a step: back to the grid as laid out.
        pinch.end(second)
        assertEquals(listOf(2, 2), committed)
        advanceTimeBy(3_000) // the spring runs in the background scope, which advanceUntilIdle leaves alone
        assertEquals(1f, pinch.scale, 0f)
        assertSame(Offset.Zero, pinch.offset)
    }

    @Test
    fun theZoomIsAboutTheFingersAndTheSpringBackTooAfterAStep() = runTest {
        val pinch = GridPinch(frames()).threeColumns()
        val fingers = Offset(600f, 1300f)
        pinch.begin(fingers)
        pinch.zoom(1.2f, fingers)
        assertSame(fingers, pinch.drawnAt(fingers))
        assertSame(Offset(600f, 1300f) + (Offset(100f, 100f) - fingers) * 1.2f, pinch.drawnAt(Offset(100f, 100f)))
        // A step on the way (1.2 * 1.15 is past 1.5^0.75): what's left is drawn about the fingers.
        pinch.zoom(1.15f, fingers)
        assertEquals(2, pinch.columns)
        assertEquals(1.2f * 1.15f / 1.5f, pinch.scale, 1e-4f)
        assertSame(fingers, pinch.drawnAt(fingers))
        pinch.end(fingers)
        runCurrent()
        advanceTimeBy(64)
        assertSame(fingers, pinch.drawnAt(fingers))
        advanceTimeBy(3_000) // the spring runs in the background scope, which advanceUntilIdle leaves alone
        assertEquals(1f, pinch.scale, 0f)
    }
}
