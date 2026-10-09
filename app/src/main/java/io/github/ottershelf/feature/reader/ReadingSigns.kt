package io.github.ottershelf.feature.reader

import io.github.ottershelf.core.sync.ReadingVisit

/**
 * The user's reading among the page's relocates: what keeps the visit's session from going idle
 * ([ReadingVisit], five minutes) and the screen on. foliate also relocates by itself, while a
 * section settles and, for as long as a chapter's CSS animates its layout, on every frame; scrolled
 * part-way into such a chapter each one differs, so reader.js lets four a second through. Counted
 * as reading, those kept a book left open there in one session that never ended, with the screen
 * lit. So only these count: a relocate the user moved to ([Relocate.turned]: a turn, swipe or scroll),
 * and the one [expect]ed after the book opens in a page (the time on the page it opened at counts)
 * or after a jump the user picked (the session goes on from there).
 *
 * Main thread only, as [ReadingVisit].
 */
internal class ReadingSigns(private val visit: ReadingVisit) {
    private var expected = false

    /** The page is landing after an opening or a jump: its own relocates until the next turn place the session. */
    private var landing = false

    /** The book is opening in a page, or the user picked a jump: the next relocate is the user's. */
    fun expect() {
        expected = true
    }

    /** A relocate from the page. True when it was the user's reading: the session goes on at its place. */
    fun onRelocate(r: Relocate): Boolean {
        val theirs = r.turned || expected
        val wasExpected = expected && !r.turned
        expected = false
        if (!theirs) {
            // Still landing after opening or a jump (foliate shows the chapter's start, then the saved
            // place): where the page went, not reading. Later relocates by itself (a chapter animating
            // its layout) don't move the session at all.
            if (landing) visit.settle(r.fraction * 100)
            return false
        }
        landing = wasExpected
        visit.activity(r.fraction * 100)
        if (r.turned) visit.markMoved()
        return true
    }
}
