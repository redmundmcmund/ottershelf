package io.github.ottershelf.core.sync

import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.util.IsoTime
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The reading sessions of one opening of a book in a reader, and whether it was reading at all.
 *
 * Opening a book and leaving without moving isn't reading, but the server takes any session on the
 * file route as reading activity: it opens a reading attempt and sets the book to Reading by itself.
 * So nothing goes out until the user has moved since the book opened ([markMoved]: a page turned, a
 * scroll, a zoom, a jump the user picked); the readers save no position until then either.
 *
 * A session starts with the first [activity] (a page shown, a relocate), ends after [IDLE_MS]
 * without any (at the last one), or at [end] (the screen stopped) and [close]; one under
 * [MIN_SECONDS] is dropped. A session that ends before the user's first move is held: it goes out with
 * that move (the time spent on the page the user opened at counts), and [close] drops it if the user never
 * moves.
 *
 * The EPUB reader (feature.reader.ReaderViewModel) and [PageReaderProgress] (comics, PDF) each keep
 * one per opening. Main thread only.
 */
class ReadingVisit(
    private val clock: () -> Long,
    /** A finished session to queue (`ProgressStore.enqueueSession`, then a sync). */
    private val send: (ReadingSession) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The user has turned a page, scrolled, zoomed or jumped since the book opened. */
    var moved: Boolean = false
        private set

    private var startWall = 0L
    private var startPct = 0.0
    private var lastActivityWall = 0L
    private var latestPct = 0.0
    private val held = ArrayList<ReadingSession>()

    /** Reading goes on at [percent] (0..100): starts a session, or keeps the current one from going idle. */
    fun activity(percent: Double = latestPct) {
        latestPct = percent
        val now = clock()
        if (startWall != 0L && now - lastActivityWall > IDLE_MS) finish(lastActivityWall)
        if (startWall == 0L) {
            startWall = now
            startPct = latestPct
        }
        lastActivityWall = now
    }

    /** The user moved: whatever was held goes out now, and every session from here on. */
    fun markMoved() {
        if (moved) return
        moved = true
        held.forEach(send)
        held.clear()
    }

    /** The screen stopped: the session ends now, or at its last activity if it had gone idle. */
    fun end() {
        val now = clock()
        finish(if (now - lastActivityWall > IDLE_MS) lastActivityWall else now)
    }

    /** The reader closed: the session ends, and what is still held (the user never moved) is dropped. */
    fun close() {
        end()
        held.clear()
    }

    private fun finish(endWall: Long) {
        val start = startWall
        if (start == 0L) return
        startWall = 0L
        val seconds = ((endWall - start) / 1000).toInt()
        if (seconds < MIN_SECONDS) return
        val body = ReadingSession(
            sessionId = newId(),
            startedAt = IsoTime.format(start),
            endedAt = IsoTime.format(endWall),
            durationSeconds = seconds,
            progressDelta = ((latestPct - startPct) * 10_000).roundToInt() / 10_000.0,
            endProgress = latestPct.coerceIn(0.0, 100.0),
        )
        if (moved) send(body) else held += body
    }

    companion object {
        const val IDLE_MS = 5 * 60_000L
        /** The server drops shorter sessions without an error. */
        const val MIN_SECONDS = 10
    }
}
