package io.github.ottershelf.feature.seriesnext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesNextTest {

    private fun m(id: Long, index: String?, status: String? = null, readable: Boolean = true) = SeriesMember(id, index, status, readable)

    private fun n(raw: String) = SeriesNumber.parse(raw)!!

    // --- numbers -------------------------------------------------------------------------------

    @Test
    fun `numbers order as the server orders them`() {
        val sorted = listOf("10", "2.10", "2", "1", "2.5", "2.9", "3", "0.5").sortedBy { n(it) }
        assertEquals(listOf("0.5", "1", "2", "2.5", "2.9", "2.10", "3", "10"), sorted)
    }

    @Test
    fun `leading zeros and a zero fraction are the same number`() {
        assertEquals(0, n("02").compareTo(n("2")))
        assertEquals(0, n("2.0").compareTo(n("2")))
        assertEquals(0, n("2.00").compareTo(n("002")))
        assertEquals(0, n("2.05").compareTo(n("2.5")))
        assertTrue(n("2.5") > n("2"))
        assertTrue(n("12345678901234567890") > n("9"))
    }

    @Test
    fun `anything else is no number`() {
        assertNull(SeriesNumber.parse(null))
        assertNull(SeriesNumber.parse(""))
        assertNull(SeriesNumber.parse("  "))
        assertNull(SeriesNumber.parse("1a"))
        assertNull(SeriesNumber.parse("-1"))
        assertNull(SeriesNumber.parse("1."))
        assertEquals(n("4"), SeriesNumber.parse(" 4 "))
    }

    // --- the next book -----------------------------------------------------------------------------

    @Test
    fun `the next number up`() {
        val members = listOf(m(1, "1", "read"), m(2, "2"), m(3, "3"), m(4, "4"))
        assertEquals(SeriesPick.Next(3), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `a novella between two books comes next`() {
        val members = listOf(m(1, "1"), m(2, "2"), m(25, "2.5"), m(3, "3"))
        assertEquals(SeriesPick.Next(25), SeriesNext.pick(2, "2", members))
        assertEquals(SeriesPick.Next(3), SeriesNext.pick(25, "2.5", members))
    }

    @Test
    fun `fractions compare part by part`() {
        val members = listOf(m(9, "2.9"), m(10, "2.10"), m(3, "3"))
        assertEquals(SeriesPick.Next(10), SeriesNext.pick(9, "2.9", members))
    }

    @Test
    fun `gaps are stepped over`() {
        val members = listOf(m(1, "1", "read"), m(2, "2"), m(5, "5"), m(6, "6"))
        assertEquals(SeriesPick.Next(5), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `books the user has read are skipped`() {
        val members = listOf(m(1, "1"), m(2, "2"), m(3, "3", "read"), m(4, "4", "skimmed"), m(5, "5", "abandoned"), m(6, "6"))
        assertEquals(SeriesPick.Next(5), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `a volume read in another copy is skipped`() {
        val members = listOf(m(2, "2"), m(30, "3", "read"), m(31, "3"), m(4, "4"))
        assertEquals(SeriesPick.Next(4), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `other copies of the finished book are not next`() {
        val members = listOf(m(20, "2"), m(21, "2"), m(22, "2.0"), m(3, "3"))
        assertEquals(SeriesPick.Next(3), SeriesNext.pick(20, "2", members))
    }

    @Test
    fun `of several copies, one with a readable file`() {
        val members = listOf(m(2, "2"), m(30, "3", readable = false), m(31, "3"), m(32, "3"))
        assertEquals(SeriesPick.Next(31), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `of several readable copies, the one the user is reading`() {
        val members = listOf(m(2, "2"), m(30, "3"), m(31, "3", "reading"), m(32, "3", "on_hold", readable = false))
        assertEquals(SeriesPick.Next(31), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `a copy nothing here opens is still offered when it is the only one`() {
        val members = listOf(m(2, "2"), m(3, "3", readable = false))
        assertEquals(SeriesPick.Next(3), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `the user has already started the next one`() {
        val members = listOf(m(2, "2"), m(3, "3", "reading"))
        assertEquals(SeriesPick.Next(3), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `the current book counts as read whatever its status says`() {
        // The reader's end: the server may not have set it to read yet.
        val members = listOf(m(1, "1", "read"), m(2, "2", "reading"))
        assertEquals(SeriesPick.AllRead(2), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `the list's number for this series wins over the book's own`() {
        // In two series: the page shows its number in the other one.
        val members = listOf(m(7, "3"), m(8, "4"), m(9, "5"))
        assertEquals(SeriesPick.Next(9), SeriesNext.pick(8, "1", members))
    }

    @Test
    fun `the book's own number when the list lacks it`() {
        val members = listOf(m(1, "1"), m(3, "3"))
        assertEquals(SeriesPick.Next(3), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `books without a number are never offered`() {
        val members = listOf(m(1, "1", "read"), m(2, "2"), m(50, null), m(51, ""))
        assertEquals(SeriesPick.Nothing, SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `a book without a number has no next`() {
        val members = listOf(m(1, "1"), m(2, "2"), m(50, null))
        assertEquals(SeriesPick.Nothing, SeriesNext.pick(50, null, members))
    }

    @Test
    fun `not in a series`() {
        assertEquals(SeriesPick.Nothing, SeriesNext.pick(1, null, emptyList()))
        assertEquals(SeriesPick.Nothing, SeriesNext.pick(1, "1", listOf(m(1, "1"))))
    }

    // --- the end of the series ---------------------------------------------------------------------

    @Test
    fun `last in the series with all read`() {
        val members = (1L..6L).map { m(it, "$it", if (it < 6) "read" else null) }
        assertEquals(SeriesPick.AllRead(6), SeriesNext.pick(6, "6", members))
    }

    @Test
    fun `last in the series with an earlier one unread says nothing`() {
        val members = listOf(m(1, "1", "read"), m(2, "2"), m(3, "3", "read"))
        assertEquals(SeriesPick.Nothing, SeriesNext.pick(3, "3", members))
    }

    @Test
    fun `everything after it read and everything before it too`() {
        val members = listOf(m(1, "1", "read"), m(2, "2"), m(3, "3", "read"), m(4, "4", "skimmed"))
        assertEquals(SeriesPick.AllRead(4), SeriesNext.pick(2, "2", members))
    }

    @Test
    fun `copies count once and books without a number count each`() {
        val members = listOf(
            m(10, "1", "read"), m(11, "1"),
            m(20, "2"),
            m(30, "3", "read"), m(31, "3.0"),
            m(50, null, "read"),
        )
        assertEquals(SeriesPick.AllRead(4), SeriesNext.pick(20, "2", members))
        assertEquals(SeriesPick.Nothing, SeriesNext.pick(20, "2", members + m(51, null)))
    }

    @Test
    fun `a book without a number that ends the series`() {
        val members = listOf(m(1, "1", "read"), m(2, "2", "read"), m(50, null))
        assertEquals(SeriesPick.AllRead(3), SeriesNext.pick(50, null, members))
    }

    @Test
    fun `a series of one says nothing`() {
        assertEquals(SeriesPick.Nothing, SeriesNext.pick(1, "1", listOf(m(1, "1"), m(2, "1", "read"))))
    }

    // --- the reader's last page --------------------------------------------------------------------

    @Test
    fun `the next book is looked up near the end, or at an end that comes sooner`() {
        assertTrue(SeriesNext.nearEndOfBook(0.95f))
        assertFalse(SeriesNext.nearEndOfBook(0.5f))
        // A picture book's last spread reports its first page's fraction (0.958 of 24 pages, less
        // for a short one): the reader says it is the end, so the series is looked up there too.
        assertTrue(SeriesNext.nearEndOfBook(0.8f, bookEnd = true))
        // So is the bottom of a scrolled book whose last section is shorter than the screen: short
        // of 1 by its whole share.
        assertFalse(SeriesNext.nearEndOfBook(0.85f))
        assertTrue(SeriesNext.nearEndOfBook(0.85f, bookEnd = true))
    }

    @Test
    fun `read and skimmed are done`() {
        assertTrue(SeriesNext.isDone("read"))
        assertTrue(SeriesNext.isDone("skimmed"))
        assertFalse(SeriesNext.isDone("reading"))
        assertFalse(SeriesNext.isDone("abandoned"))
        assertFalse(SeriesNext.isDone(null))
    }
}
