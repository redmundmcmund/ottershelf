package io.github.ottershelf.feature.comics

/**
 * What one screen of the paged comics reader shows: one page, or two side by side. The web's
 * spread-layout.ts (client/src/features/reader/cbz/lib), ported as is. Pages are 0-based here.
 *
 * - [pages] in reading order; [anchorPage] is the first, and is the page the reader keeps as
 *   "where the user is" (a spread reports its last page as progress, as the web does).
 * - [left] / [right] are the pages as laid out on screen (a right-to-left comic puts the first page
 *   on the right); null is the blank half beside a page that has no partner ([hasBlank]).
 */
data class Spread(
    val pages: List<Int>,
    val single: Boolean,
    val left: Int? = null,
    val right: Int? = null,
    val hasBlank: Boolean = false,
) {
    val anchorPage: Int get() = pages.first()
    val lastPage: Int get() = pages.last()

    companion object {
        fun single(page: Int) = Spread(listOf(page), single = true)

        fun pair(rtl: Boolean, first: Int, second: Int?, hasBlank: Boolean) = Spread(
            pages = if (second == null) listOf(first) else listOf(first, second),
            single = false,
            left = if (rtl) second else first,
            right = if (rtl) first else second,
            hasBlank = hasBlank,
        )
    }
}

/**
 * The spreads of a comic: one page per screen, or (two-page view) the cover alone, an optional
 * extra single after it ([shifted]), then pairs, where a wide page ([isWide]: width/height at least
 * [WIDE_PAGE_RATIO]) stands alone unless [widePagesInSpreads], and a page whose partner is wide or
 * missing gets a blank half.
 */
class SpreadLayout private constructor(val spreads: List<Spread>, private val pageCount: Int) {

    private val pageToSpread = IntArray(pageCount.coerceAtLeast(0)).also { index ->
        spreads.forEachIndexed { i, spread -> spread.pages.forEach { page -> if (page in index.indices) index[page] = i } }
    }

    /** The screen [page] is on (0 for an empty comic). */
    fun spreadIndexForPage(page: Int): Int {
        if (spreads.isEmpty() || pageToSpread.isEmpty()) return 0
        return pageToSpread[page.coerceIn(0, pageCount - 1)]
    }

    fun spreadForPage(page: Int): Spread? = spreads.getOrNull(spreadIndexForPage(page))

    /** The first page of the screen [page] is on. */
    fun anchorForPage(page: Int): Int = spreadForPage(page)?.anchorPage ?: 0

    companion object {
        /** The web's DEFAULT_WIDE_PAGE_RATIO_THRESHOLD. */
        const val WIDE_PAGE_RATIO = 1.2f

        fun create(
            pageCount: Int,
            twoPage: Boolean,
            rtl: Boolean,
            shifted: Boolean = false,
            widePagesInSpreads: Boolean = false,
            isWide: (Int) -> Boolean = { false },
        ): SpreadLayout = SpreadLayout(build(pageCount, twoPage, rtl, shifted, widePagesInSpreads, isWide), pageCount)

        /** [create] with the widths from [ratios] (page -> width / height; unknown pages count as narrow). */
        fun create(pageCount: Int, twoPage: Boolean, rtl: Boolean, shifted: Boolean, widePagesInSpreads: Boolean, ratios: Map<Int, Float>) =
            create(pageCount, twoPage, rtl, shifted, widePagesInSpreads, widePages(ratios))

        /** [create] with the pages known to be wide ([widePages]). */
        fun create(pageCount: Int, twoPage: Boolean, rtl: Boolean, shifted: Boolean, widePagesInSpreads: Boolean, wide: Set<Int>) =
            create(pageCount, twoPage, rtl, shifted, widePagesInSpreads) { it in wide }

        /**
         * The pages of [ratios] that are wide. A page reports its size more than once (the small
         * prefetch, then each full decode) with slightly different ratios; only this set changes the
         * spreads, so the reader keys its layout on it rather than on the ratios.
         */
        fun widePages(ratios: Map<Int, Float>): Set<Int> = ratios.filterValues { it >= WIDE_PAGE_RATIO }.keys.toSet()

        private fun build(
            pageCount: Int,
            twoPage: Boolean,
            rtl: Boolean,
            shifted: Boolean,
            widePagesInSpreads: Boolean,
            isWide: (Int) -> Boolean,
        ): List<Spread> {
            if (pageCount <= 0) return emptyList()
            if (!twoPage) return List(pageCount) { Spread.single(it) }
            val auto = !widePagesInSpreads
            val spreads = ArrayList<Spread>()
            // The cover is always alone.
            spreads += Spread.single(0)
            var cursor = 1
            // Shifted: one more single after the cover, then pairs.
            if (shifted && cursor < pageCount) {
                spreads += Spread.single(cursor)
                cursor++
            }
            while (cursor < pageCount) {
                val first = cursor
                val second = cursor + 1
                when {
                    auto && isWide(first) -> {
                        spreads += Spread.single(first)
                        cursor++
                    }
                    second >= pageCount || (auto && isWide(second)) -> {
                        spreads += Spread.pair(rtl, first, null, hasBlank = true)
                        cursor++
                    }
                    else -> {
                        spreads += Spread.pair(rtl, first, second, hasBlank = false)
                        cursor += 2
                    }
                }
            }
            return spreads
        }
    }
}
