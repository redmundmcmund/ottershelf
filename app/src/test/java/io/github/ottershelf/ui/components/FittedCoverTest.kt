package io.github.ottershelf.ui.components

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FittedCoverTest {
    private val box = Size(200f, 300f)

    @Test
    fun aTwoByThreeCoverFillsItsBox() {
        assertTrue(sameShape(Size(400f, 600f), box))
        // 0.65 wide for 1 high: within 3% of 2:3.
        assertTrue(sameShape(Size(650f, 1000f), box))
    }

    @Test
    fun aTallNarrowOrAWideCoverIsFittedOverTheBlur() {
        // A mass-market paperback, about 1:1.6.
        assertFalse(sameShape(Size(600f, 1000f), box))
        // An audiobook's square cover.
        assertFalse(sameShape(Size(500f, 500f), box))
    }

    @Test
    fun anUnknownSizeCountsAsTheSameShape() {
        assertTrue(sameShape(Size.Zero, box))
        assertTrue(sameShape(Size(400f, 600f), Size.Zero))
        assertTrue(sameShape(Size.Unspecified, box))
    }
}
