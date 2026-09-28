package io.github.ottershelf.core.sync

import io.github.ottershelf.core.model.ReadingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reader's visit: no session goes out until the user has moved since the book opened (the phone bug:
 * opening a book and leaving after 15 s without turning a page recorded a session, and the server
 * then set the book to Reading), while a real reading visit still records all of its time.
 */
class ReadingVisitTest {

    private var now = 1_800_000_000_000L
    private val sent = ArrayList<ReadingSession>()
    private var ids = 0
    private val visit = ReadingVisit(clock = { now }, send = { sent += it }, newId = { "s${++ids}" })

    @Test
    fun openingAndLeavingWithoutAMoveSendsNothing() {
        visit.activity(40.0) // the page it opened at
        now += 15_000
        visit.close()
        assertFalse(visit.moved)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aLongLookWithoutAMoveSendsNothingEither() {
        visit.activity(40.0)
        now += 4 * 60_000
        visit.activity() // the page relaid out, the bars shown: not a move
        now += 60_000
        visit.end() // the phone locked
        visit.close()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aRealVisitRecordsItsWholeTimeFromOpening() {
        visit.activity(40.0)
        now += 3 * 60_000
        visit.activity(41.0)
        visit.markMoved() // a page turn
        now += 2 * 60_000
        visit.activity(42.0)
        visit.close()
        val session = sent.single()
        assertEquals(300, session.durationSeconds)
        assertEquals(2.0, session.progressDelta!!, 1e-9)
        assertEquals(42.0, session.endProgress!!, 1e-9)
    }

    @Test
    fun aSessionEndedBeforeTheFirstMoveIsHeldUntilIt() {
        visit.activity(10.0)
        now += 2 * 60_000
        visit.end() // the phone locked while the user read the first page
        assertTrue(sent.isEmpty())
        now += 30 * 60_000
        visit.activity(10.0) // back: a new session
        now += 60_000
        visit.activity(11.0)
        visit.markMoved() // the held one goes out now
        assertEquals(listOf(120), sent.map { it.durationSeconds })
        now += 60_000
        visit.close()
        assertEquals(listOf(120, 120), sent.map { it.durationSeconds })
        assertEquals(listOf("s1", "s2"), sent.map { it.sessionId })
    }

    @Test
    fun anIdleGapEndsTheSessionAtItsLastActivity() {
        visit.activity(10.0)
        visit.markMoved()
        now += 30_000
        visit.activity(12.0)
        now += 10 * 60_000 // put down
        visit.activity(12.0) // a new session starts here
        now += 5_000
        visit.close() // under 10 s: dropped
        assertEquals(listOf(30), sent.map { it.durationSeconds })
    }

    @Test
    fun endingWhileIdleEndsAtTheLastActivity() {
        visit.activity(10.0)
        visit.markMoved()
        now += 60_000
        visit.activity(11.0)
        now += 20 * 60_000
        visit.end()
        assertEquals(listOf(60), sent.map { it.durationSeconds })
    }

    @Test
    fun underTenSecondsIsDroppedEvenAfterAMove() {
        visit.activity(10.0)
        visit.markMoved()
        now += 9_000
        visit.close()
        assertTrue(sent.isEmpty())
    }
}
