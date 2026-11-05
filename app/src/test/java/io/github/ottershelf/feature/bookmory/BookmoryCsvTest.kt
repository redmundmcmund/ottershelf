package io.github.ottershelf.feature.bookmory

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The synthetic export in `src/test/resources/bookmory/sample.csv` (invented books and people). */
fun sampleCsv(): String =
    BookmoryCsvTest::class.java.getResourceAsStream("/bookmory/sample.csv")!!.readBytes().toString(Charsets.UTF_8)

class BookmoryCsvTest {

    private val file = BookmoryCsv.parse(sampleCsv())
    private fun row(title: String) = file.rows.single { it.title.startsWith(title) }

    @Test
    fun readsEveryGoodRowAndReportsTheBrokenOne() {
        assertEquals(15, file.rows.size)
        assertEquals(1, file.problems.size)
        val problem = file.problems.single()
        assertEquals(BookmoryProblem.Reason.WRONG_COLUMN_COUNT, problem.reason)
        assertEquals("Broken Row", problem.title)
        assertEquals(DateOrder.MONTH_FIRST, file.dateOrder)
    }

    @Test
    fun theBomIsDroppedAndQuotedFieldsKeepCommasQuotesAndLineBreaks() {
        val first = file.rows.first()
        assertEquals("Quiet Harbours, Loud Hearts", first.title)
        assertEquals(2, first.line)
        assertEquals("The \"Lantern\" Keeper", row("The \"Lantern\"").title)
        // The quoted "Collections" field spans two lines, so the next row starts on line 4.
        assertEquals(4, row("The \"Lantern\"").line)
    }

    @Test
    fun columnsAreReadByName() {
        val r = row("Quiet Harbours")
        assertEquals(listOf("Mara Quill"), r.authors)
        assertEquals("Driftwood Press", r.publisher)
        assertEquals("2016-02-28", r.publicationDate)
        assertEquals("9780000000019", r.isbn)
        assertEquals("Paperback", r.bookType)
        assertEquals("English", r.language)
        assertEquals(BookmoryStatus.READ, r.status)
        assertEquals(listOf(BookmoryLog(LocalDate.of(2021, 3, 2), LocalDate.of(2021, 3, 15), 4)), r.logs)
        assertEquals(4, r.rating)
    }

    @Test
    fun multipleAuthorsOpenPeriodsAndNoRatings() {
        val r = row("The \"Lantern\"")
        assertEquals(listOf("Ivo Brand", "Lena Ferro"), r.authors)
        assertEquals(BookmoryStatus.READING, r.status)
        assertEquals(
            listOf(
                BookmoryLog(LocalDate.of(2020, 1, 5), LocalDate.of(2020, 2, 1), 5),
                BookmoryLog(LocalDate.of(2022, 6, 1), null, null),
            ),
            r.logs,
        )
        assertEquals(5, r.rating)
        assertNull(r.isbn)
    }

    @Test
    fun threeLogsAndTheLatestRatingRounded() {
        val r = row("Paper Moons")
        assertEquals("Paper Moons (Tidewater Saga, #2)", r.title)
        assertEquals(3, r.logs.size)
        assertEquals(listOf(3, null, 5), r.logs.map { it.rating })
        assertEquals(5, r.rating)
    }

    @Test
    fun allFourStatusesTheWishlistAndAnIsbn10() {
        assertEquals(BookmoryStatus.GAVE_UP, row("I Haven").status)
        assertEquals("I Haven�t Slept in Weeks", row("I Haven").title)
        val orchard = row("Orchard of Glass")
        assertEquals(BookmoryStatus.TO_READ, orchard.status)
        assertTrue(orchard.wishlist)
        assertEquals("9780000000040", orchard.isbn)
        assertTrue(orchard.logs.isEmpty())
        assertFalse(row("Saltwater Atlas").wishlist)
        assertEquals(BookmoryStatus.READ, row("The Unlogged Shelf").status)
        assertEquals(listOf(BookmoryLog(LocalDate.of(2022, 8, 1), null, 1)), row("Cold Iron Stars").logs)
    }

    @Test
    fun truncatedTitlesAreKeptAsExported() {
        assertTrue(row("Never Quite Finished").title.endsWith("go on and..."))
    }

    @Test
    fun dayFirstWhenAnyDateCannotBeMonthFirst() {
        val csv = "Title,Authors,Read status,Reading log 1 - Read period,Reading log 1 - Star ratings\n" +
            "A,X,I've read it all!,3/1/2021 ~ 25/1/2021,0.0\n" +
            "B,Y,Reading,2/3/2022 ~ ,5.0\n"
        val parsed = BookmoryCsv.parse(csv)
        assertEquals(DateOrder.DAY_FIRST, parsed.dateOrder)
        assertEquals(BookmoryLog(LocalDate.of(2021, 1, 3), LocalDate.of(2021, 1, 25), null), parsed.rows[0].logs.single())
        assertEquals(LocalDate.of(2022, 3, 2), parsed.rows[1].logs.single().startedOn)
    }

    @Test
    fun aBadDateSkipsOnlyItsRow() {
        val csv = "Title,Authors,Read status,Reading log 1 - Read period\n" +
            "A,X,Reading,2/30/2021 ~ \n" +
            "B,Y,Reading,2/3/2021 ~ \n" +
            ",Z,Reading,\n"
        val parsed = BookmoryCsv.parse(csv)
        assertEquals(listOf("B"), parsed.rows.map { it.title })
        assertEquals(listOf(BookmoryProblem.Reason.BAD_DATE, BookmoryProblem.Reason.NO_TITLE), parsed.problems.map { it.reason })
    }

    @Test(expected = NotABookmoryExport::class)
    fun aFileWithoutATitleColumnIsRefused() {
        BookmoryCsv.parse("Name,Author\nA,B\n")
    }

    @Test
    fun ratingsRoundAndClamp() {
        assertNull(BookmoryCsv.rating("0.0"))
        assertNull(BookmoryCsv.rating(""))
        assertEquals(1, BookmoryCsv.rating("0.4"))
        assertEquals(4, BookmoryCsv.rating("4.0"))
        assertEquals(5, BookmoryCsv.rating("4.5"))
        assertEquals(5, BookmoryCsv.rating("7"))
    }

    @Test
    fun statusesWithAnyApostrophe() {
        assertEquals(BookmoryStatus.READ, BookmoryCsv.status("I've read it all!"))
        assertEquals(BookmoryStatus.READ, BookmoryCsv.status("I’ve read it all!"))
        assertEquals(BookmoryStatus.READ, BookmoryCsv.status("I�ve read it all!"))
        assertEquals(BookmoryStatus.UNKNOWN, BookmoryCsv.status("Something new"))
    }
}
