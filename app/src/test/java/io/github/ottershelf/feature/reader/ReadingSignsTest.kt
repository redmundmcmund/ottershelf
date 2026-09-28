package io.github.ottershelf.feature.reader

import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.sync.ReadingVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the page's relocates are the user's reading ([ReadingSigns]), over a real [ReadingVisit] with a
 * clock of its own. Plain JVM.
 */
class ReadingSignsTest {

    private var now = 1_800_000_000_000L
    private val sent = ArrayList<ReadingSession>()
    private val visit = ReadingVisit(clock = { now }, send = { sent += it })
    private val signs = ReadingSigns(visit)

    @Test
    fun aChapterAnimatingItsLayoutDoesntKeepTheSessionGoing() {
        signs.expect() // the book opens
        assertTrue(signs.onRelocate(Relocate(fraction = 0.40)))
        now += 30_000
        assertTrue(signs.onRelocate(Relocate(fraction = 0.41, turned = true)))
        // Scrolled part-way into a chapter whose CSS animates its height: four relocates a second,
        // each a little different, for the twenty minutes the book lies open on the table.
        repeat(20 * 60 * 4) { i ->
            now += 250
            assertFalse(signs.onRelocate(Relocate(fraction = 0.41 + (i % 7) * 0.0003, page = 12 + i % 2, pages = 300 + i % 3)))
        }
        visit.end() // the phone locked
        // One session, ended at the user's last page turn: not twenty minutes of reading.
        val session = sent.single()
        assertEquals(30, session.durationSeconds)
        assertEquals(1.0, session.progressDelta!!, 1e-9)
    }

    @Test
    fun theUsersMovesAndTheRelocateAJumpBringsAreReading() {
        signs.expect()
        assertTrue("the page it opened at", signs.onRelocate(Relocate(fraction = 0.10)))
        assertFalse("the section settling", signs.onRelocate(Relocate(fraction = 0.11)))
        assertTrue("a turn", signs.onRelocate(Relocate(fraction = 0.12, turned = true)))
        assertTrue(visit.moved)
        signs.expect() // a jump the user picked (the contents, the slider, a highlight)
        assertTrue("where the jump went", signs.onRelocate(Relocate(fraction = 0.60)))
        assertFalse("the section settling after it", signs.onRelocate(Relocate(fraction = 0.61)))
        assertFalse(signs.onRelocate(Relocate(fraction = 0.61, page = 3)))
    }

    @Test
    fun aVisitWithoutAMoveStillSendsNothing() {
        signs.expect()
        signs.onRelocate(Relocate(fraction = 0.40))
        now += 60_000
        signs.onRelocate(Relocate(fraction = 0.41)) // settling, not a move
        visit.close()
        assertFalse(visit.moved)
        assertTrue(sent.isEmpty())
    }
}
