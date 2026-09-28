package io.github.ottershelf.feature.seriesnext

import io.github.ottershelf.core.model.ReadStatus

/**
 * A series number as the server orders them (packages/types series-index.ts `compareSeriesIndices`,
 * the SQL sort key in series-index-sql.utils.ts): `4`, `4.5`, `1.10`. The whole part is compared as
 * a number, then a number without a fraction comes before one with it, then the fractions are
 * compared as whole numbers too (so `2.10` follows `2.9`, as on the server). Leading zeros don't
 * count, and a fraction of only zeros is none (`4.0` is `4`, as `formatSeriesIndex` shows it), so
 * two copies of one volume numbered `4` and `4.0` are the same volume here.
 */
internal data class SeriesNumber(val whole: String, val fraction: String?) : Comparable<SeriesNumber> {

    override fun compareTo(other: SeriesNumber): Int {
        val wholes = compareSegments(whole, other.whole)
        if (wholes != 0) return wholes
        return when {
            fraction == null && other.fraction == null -> 0
            fraction == null -> -1
            other.fraction == null -> 1
            else -> compareSegments(fraction, other.fraction)
        }
    }

    companion object {
        /** The server's `SERIES_INDEX_PATTERN`. */
        private val PATTERN = Regex("""^\d+(?:\.\d+)?$""")

        /** [raw] as the server stores it, or null when it isn't a series number (missing, blank, "1a"). */
        fun parse(raw: String?): SeriesNumber? {
            val text = raw?.trim() ?: return null
            if (!PATTERN.matches(text)) return null
            val parts = text.split('.', limit = 2)
            val fraction = parts.getOrNull(1)?.let(::segment)?.takeUnless { it == "0" }
            return SeriesNumber(segment(parts[0]), fraction)
        }

        /** Digits without leading zeros ("0" for none), so the length orders them first. */
        private fun segment(digits: String): String = digits.trimStart('0').ifEmpty { "0" }

        private fun compareSegments(a: String, b: String): Int =
            if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    }
}

/**
 * One book of the series as the next-book choice sees it: [index] its number in this series (the
 * server's contextual `seriesIndex`), [status] the user's read status (as the series list just said),
 * [readable] a reader here opens one of its files, and they are on the server's disk.
 */
internal data class SeriesMember(val bookId: Long, val index: String?, val status: String?, val readable: Boolean)

/** What follows the book the user finished. */
internal sealed interface SeriesPick {
    /** The next book to offer. */
    data class Next(val bookId: Long) : SeriesPick

    /** Nothing left: the user has read every one of the series' [count] volumes in the library. */
    data class AllRead(val count: Int) : SeriesPick

    /** Nothing to say (not numbered, nothing after it in the library, or earlier ones unread). */
    data object Nothing : SeriesPick
}

/**
 * The next book in a series, worked out on the phone from the series' books in the library (the
 * server's `series/:id/books/:bookId/next` is the web reader's handoff: the immediate neighbour with
 * a readable file, even one the user has read, and another copy of the same number counts as "next").
 * Pure.
 */
internal object SeriesNext {

    private val DONE = setOf(ReadStatus.READ.value, ReadStatus.SKIMMED.value)
    private val IN_PROGRESS = setOf(ReadStatus.READING.value, ReadStatus.REREADING.value, ReadStatus.ON_HOLD.value)

    /** The user has read it (skimmed counts, as the History's Finished does). */
    fun isDone(status: String?): Boolean = status in DONE

    /**
     * What to offer after [currentId] (numbered [currentIndex] when the list doesn't say), which the user
     * has just finished or reached the end of, so it counts as read whatever its status says:
     *
     * - The books are grouped into volumes by number: several books on one number are copies of one
     *   volume (formats, editions, two libraries). A volume counts as read when any of its copies is,
     *   and the current book's copies share its volume.
     * - The next book is in the volume with the smallest number above the current one's that the user
     *   hasn't read (so gaps are stepped over, and read ones skipped). Of its copies: one with a file a
     *   reader here opens first, then one the user is reading, then the series' order.
     * - Books without a number have no place in the order: they are never offered, and a book without
     *   a number has no next. Each counts as a volume of its own.
     * - Nothing after it and every volume read (at least two): [SeriesPick.AllRead].
     */
    fun pick(currentId: Long, currentIndex: String?, members: List<SeriesMember>): SeriesPick {
        val current = members.firstOrNull { it.bookId == currentId }
        val currentNumber = SeriesNumber.parse(current?.index ?: currentIndex)
        val others = members.filter { it.bookId != currentId }

        val numbered = others.mapNotNull { m -> SeriesNumber.parse(m.index)?.let { it to m } }
            .groupBy({ it.first }, { it.second })
        val unnumbered = others.filter { SeriesNumber.parse(it.index) == null }

        if (currentNumber != null) {
            val next = numbered.entries
                .filter { (number, _) -> number > currentNumber }
                .sortedBy { it.key }
                .firstOrNull { (_, copies) -> copies.none { isDone(it.status) } }
            if (next != null) {
                val chosen = next.value.sortedWith(
                    compareByDescending<SeriesMember> { it.readable }.thenByDescending { it.status in IN_PROGRESS },
                ).first()
                return SeriesPick.Next(chosen.bookId)
            }
        }

        // Every volume: the numbered ones (the current's own number is read through it) and one per
        // book without a number, the current book included.
        val volumes = numbered.filterKeys { it != currentNumber }.values.toList() + unnumbered.map { listOf(it) }
        val count = volumes.size + 1
        val allRead = volumes.all { copies -> copies.any { isDone(it.status) } }
        return if (allRead && count >= 2) SeriesPick.AllRead(count) else SeriesPick.Nothing
    }

    /**
     * Near the end: the next book is looked up now, so the card is ready on the last page. The card
     * itself shows at the end the reader reports (nothing left to turn or scroll to), never at a
     * fraction: foliate's marks the end of what shows when paginated, so the last pages of a long
     * book all come within half a percent of 1, and the top when scrolled, so the bottom of a short
     * book stays short of it.
     */
    const val PREFETCH_FRACTION = 0.9f

    /**
     * Time to look the next book up: [fraction] through the book, or already at its end ([bookEnd],
     * the reader's word: a picture book's last spread, or the bottom of a short scrolled one, can be
     * below it).
     */
    fun nearEndOfBook(fraction: Float, bookEnd: Boolean = false): Boolean = bookEnd || fraction >= PREFETCH_FRACTION
}
