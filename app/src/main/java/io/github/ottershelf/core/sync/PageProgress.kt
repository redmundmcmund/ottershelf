package io.github.ottershelf.core.sync

import io.github.ottershelf.core.model.SaveProgress
import io.github.ottershelf.core.sync.ProgressStore.Position
import kotlin.math.roundToInt

/**
 * Position math for the page-based readers (comics, PDF), as the web's CBZ and PDF readers do it
 * (CbzReaderView.vue, PdfV4ReaderView.vue, useReaderProgress.ts). Pages are 1-based everywhere.
 * Pure.
 *
 * - Saved: `{cfi: null, pageNumber, percentage}` with percentage = page / pageCount * 100 (the last
 *   page is 100, so the server's finish threshold is reached on it). A two-page spread reports its
 *   last page, as the web does.
 * - Opened: the saved page number (clamped to the book); without one, a page estimated from the
 *   percentage (another client, or KOReader, saved only a percentage); else page 1.
 */
object PageProgress {

    /** 0..100 for [pageNumber] of [pageCount]; 0 while the page count isn't known. */
    fun percentage(pageNumber: Int, pageCount: Int): Double {
        if (pageCount <= 0) return 0.0
        return (pageNumber.coerceIn(1, pageCount).toDouble() / pageCount * 100).coerceIn(0.0, 100.0)
    }

    /** The position a page reader stores and sends for [pageNumber] of [pageCount]. */
    fun position(pageNumber: Int, pageCount: Int): Position =
        Position(cfi = null, percentage = percentage(pageNumber, pageCount), pageNumber = pageNumber.coerceIn(1, pageCount.coerceAtLeast(1)))

    /** The progress body for [pageNumber] of [pageCount] (exactly the fields the web's page readers send). */
    fun body(pageNumber: Int, pageCount: Int): SaveProgress {
        val p = position(pageNumber, pageCount)
        return SaveProgress(cfi = null, percentage = p.percentage, pageNumber = p.pageNumber)
    }

    /** Where to open a book of [pageCount] pages saved at [position] (null: never read): 1..pageCount. */
    fun startPage(position: Position?, pageCount: Int): Int {
        if (pageCount <= 1 || position == null) return 1
        val saved = position.pageNumber
        if (saved != null && saved >= 1) return saved.coerceAtMost(pageCount)
        if (position.percentage > 0) {
            val estimated = (position.percentage / 100 * pageCount).roundToInt()
            return estimated.coerceIn(1, pageCount)
        }
        return 1
    }
}
