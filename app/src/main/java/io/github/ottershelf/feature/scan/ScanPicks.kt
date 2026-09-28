package io.github.ottershelf.feature.scan

/**
 * The book the scanner found when it was opened to pick one (`Route.Scan(pick = true)`, the
 * Calendar's "Add a book"): the scanner leaves it here and closes, and the screen that opened it
 * takes it when it shows again. One at a time, in memory only.
 */
object ScanPicks {
    private var picked: ScanBook? = null

    @Synchronized
    fun hand(book: ScanBook) {
        picked = book
    }

    /** The book picked since [clear], once (null when the user left the scanner without one). */
    @Synchronized
    fun take(): ScanBook? = picked.also { picked = null }

    @Synchronized
    fun clear() {
        picked = null
    }
}
