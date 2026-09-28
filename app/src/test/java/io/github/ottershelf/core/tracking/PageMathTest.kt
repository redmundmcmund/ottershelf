package io.github.ottershelf.core.tracking

import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.settings.PageOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PageMathTest {

    @Test
    fun pagesAndPercentRoundTrip() {
        for (page in 0..337) assertEquals(page, PageMath.percentToPage(PageMath.pageToPercent(page, 337), 337))
        assertEquals(33.33, PageMath.pageToPercent(100, 300), 0.0)
        assertEquals(300, PageMath.percentToPage(120.0, 300))
        assertEquals(0, PageMath.percentToPage(50.0, 0))
    }

    @Test
    fun theUsersEditionsTotalWinsOverTheServers() {
        val book = BookDetail(id = 4, pageCount = 320)
        assertEquals(320, PageMath.pageTotal(book, AppSettings()))
        assertEquals(410, PageMath.pageTotal(book, AppSettings(pageTotals = mapOf(4L to 410))))
        assertNull(PageMath.pageTotal(BookDetail(id = 4), AppSettings()))
    }

    @Test
    fun aHandSetPageShowsUntilANewerSession() {
        val override = PageOverride(page = 120, at = 5_000)
        assertEquals(120, PageMath.currentPage(300, 10.0, override, lastSessionEndMs = 4_000))
        assertEquals(30, PageMath.currentPage(300, 10.0, override, lastSessionEndMs = 6_000))
        assertEquals(120, PageMath.currentPage(300, null, override, lastSessionEndMs = null))
        assertNull(PageMath.currentPage(null, 10.0, null, null))
        assertEquals(60.0, PageMath.currentPercent(55.0, 60.0)!!, 0.0)
        assertNull(PageMath.currentPercent(null, null))
    }

    @Test
    fun aSessionStartsFromTheLatestPositionNotTheHighest() {
        // The EPUB was opened once (30%), paper reading has since reached 12%.
        val epub = PageMath.Position(30.0, atMs = 1_000)
        val paper = PageMath.Position(12.0, atMs = 9_000)
        assertEquals(12.0, PageMath.startPercent(listOf(epub, paper))!!, 0.0)
        // No times known: the higher one, as before.
        assertEquals(30.0, PageMath.startPercent(listOf(PageMath.Position(30.0, null), PageMath.Position(12.0, null)))!!, 0.0)
        assertNull(PageMath.startPercent(emptyList()))
    }

    @Test
    fun aRereadStartsAfreshUntilItHasAPositionOfItsOwn() {
        val finished = listOf(PageMath.Position(100.0, atMs = 1_000), PageMath.Position(100.0, atMs = 2_000))
        // Re-reading since 5_000: the 100% belongs to the first reading.
        assertEquals(0.0, PageMath.startPercent(finished, readingSinceMs = 5_000)!!, 0.0)
        val again = finished + PageMath.Position(8.6, atMs = 6_000)
        assertEquals(8.6, PageMath.startPercent(again, readingSinceMs = 5_000)!!, 0.0)
    }

    @Test
    fun dayNumberReadsTheDateWithoutShifting() {
        val today = LocalDate.of(2026, 9, 25)
        assertEquals(1, PageMath.dayNumber("2026-09-25", today))
        // UTC midnight of the 20th stays the 20th (converting to a zone west of UTC would give the 19th).
        assertEquals(6, PageMath.dayNumber("2026-09-20T00:00:00.000Z", today))
        assertNull(PageMath.dayNumber(null, today))
        assertNull(PageMath.dayNumber("soon", today))
    }

    @Test
    fun readingNumberCountsCompletedReadings() {
        val done = ReadingAttempt(id = 1, bookId = 1, outcome = AttemptOutcome.COMPLETED)
        val dropped = ReadingAttempt(id = 2, bookId = 1, outcome = AttemptOutcome.ABANDONED)
        val open = ReadingAttempt(id = 3, bookId = 1)
        assertEquals(1, PageMath.readingNumber(listOf(open), reading = true))
        assertEquals(2, PageMath.readingNumber(listOf(done, dropped, open), reading = true))
        assertEquals(1, PageMath.readingNumber(listOf(done), reading = false))
        assertEquals(1, PageMath.readingNumber(emptyList(), reading = false))
    }

    @Test
    fun paceAndTimeLeft() {
        val stats = BookSessionStats(paceProgressDelta = 20.0, paceDurationSeconds = 7_200)
        val pace = PageMath.pace(stats, total = 300)!!
        assertEquals(10.0, pace.percentPerHour, 1e-9)
        assertEquals(30.0, pace.pagesPerHour!!, 1e-9)
        assertEquals(6 * 3600L, PageMath.timeLeftSeconds(40.0, pace))
        assertNull(PageMath.pace(BookSessionStats(), 300))
        assertNull(PageMath.timeLeftSeconds(40.0, null))
        assertEquals(15, PageMath.pagesOf(5.0, 300))
    }

    @Test
    fun theTrackingFileIsThePrimaryOne() {
        val files = listOf(BookFile(id = 1, role = "alternate"), BookFile(id = 2, role = "primary"))
        assertEquals(2L, PageMath.trackingFile(files)!!.id)
        assertEquals(1L, PageMath.trackingFile(files.take(1))!!.id)
        assertNull(PageMath.trackingFile(emptyList()))
    }
}
