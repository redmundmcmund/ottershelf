package io.github.ottershelf.feature.quotes

import java.util.UUID

/**
 * Where a typed or photographed quote sits in the book: nowhere the reader can find. BookOrbit wants
 * exactly one of `cfi` or `pdf` on every annotation and doesn't check the CFI, so a quote carries a
 * placeholder CFI with no spine step, e.g. `epubcfi(!/4/2[bookorbit-quote-3f9a1c2e]/1:0)`.
 *
 * Why this shape (evidence in ARCHITECTURE.md, "Quotes"):
 * - It tokenises everywhere (foliate, the server's `cfi.utils.ts`, the web's regex helpers, the
 *   app's `Cfi`), so nothing that sorts or labels annotations chokes on it.
 * - With no step before `!`, foliate's `resolveCFI` throws inside `resolveNavigation`, which returns
 *   undefined: nothing is drawn and a jump (web sidebar, Highlights tab, hub link) goes nowhere. A
 *   spec-valid CFI outside the spine instead resolves to section -1, and `View.goTo` then falls back
 *   to the first section, moving the web reader (and its saved position) to the start of the book.
 * - The server's KOReader and Kobo conversions stop at `missing_spine_step` without opening the
 *   EPUB, store a failed device position and skip it; the CFI row stays `exact`, so it isn't counted
 *   as needing review.
 * - The id assertion marks it as a quote and keeps each one's CFI unique (the web keys drawn
 *   annotations by CFI).
 */
object QuotePosition {
    // Saved in every quote on the server: kept although the app is now Ottershelf, so the quotes
    // made before still read as quotes.
    private const val MARK = "[bookorbit-quote"

    /** A new placeholder CFI for one quote. */
    fun newCfi(): String = "epubcfi(!/4/2${MARK}-${UUID.randomUUID().toString().take(8)}]/1:0)"

    /** Whether [cfi] is a quote's placeholder (so no reader can open it). */
    fun isQuote(cfi: String?): Boolean = cfi != null && cfi.startsWith("epubcfi(!") && cfi.contains(MARK)

    /**
     * The page or pages, as stored in `chapterTitle`: "p. 45" or "p. 45-47"; null without a start
     * page. An end page at or before the start is ignored.
     */
    fun pageLabel(from: Int?, to: Int?): String? {
        val start = from?.takeIf { it > 0 } ?: return null
        val end = to?.takeIf { it > start }
        return if (end == null) "p. $start" else "p. $start-$end"
    }

    /** The digits of a page field, at most five. */
    fun pageDigits(input: String): String = input.filter { it.isDigit() }.take(5)
}
