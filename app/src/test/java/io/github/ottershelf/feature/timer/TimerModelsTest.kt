package io.github.ottershelf.feature.timer

import io.github.ottershelf.core.settings.ProgressUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerModelsTest {

    @Test
    fun pagesParse() {
        assertEquals(ProgressEntry.Pages(150, 464) to null, ProgressInput.parse(ProgressUnit.PAGE, "150", "464", ""))
        assertEquals(ProgressEntry.None to null, ProgressInput.parse(ProgressUnit.PAGE, " ", "464", ""))
        assertEquals(null to EntryError.PageTooHigh, ProgressInput.parse(ProgressUnit.PAGE, "500", "464", ""))
        assertEquals(null to EntryError.NeedTotal, ProgressInput.parse(ProgressUnit.PAGE, "12", "", ""))
        assertEquals(null to EntryError.NotANumber, ProgressInput.parse(ProgressUnit.PAGE, "12", "0", ""))
    }

    @Test
    fun percentParse() {
        assertEquals(ProgressEntry.Percent(43.5) to null, ProgressInput.parse(ProgressUnit.PERCENT, "", "", "43,5"))
        assertEquals(ProgressEntry.Percent(100.0) to null, ProgressInput.parse(ProgressUnit.PERCENT, "", "", "100%"))
        assertEquals(null to EntryError.PercentRange, ProgressInput.parse(ProgressUnit.PERCENT, "", "", "101"))
        assertEquals(ProgressEntry.None to null, ProgressInput.parse(ProgressUnit.PERCENT, "", "", ""))
        assertEquals("43", ProgressInput.percentText(43.0))
        assertEquals("43.3", ProgressInput.percentText(43.33))
    }

    @Test
    fun summaryNumbers() {
        val s = SessionSummary(
            bookId = 1, sessionId = "s", activeSeconds = 30 * 60, outcome = SaveOutcome.Sent,
            total = 464, startPage = 130, endPage = 150, startPercent = 28.02, endPercent = 32.33,
        )
        assertEquals(30, s.minutes)
        assertEquals(20, s.pagesRead)
        assertEquals(40.0, s.pagesPerHour!!, 0.001)
        assertEquals(314, s.pagesLeft)
        // No book pace yet: this session's 40 pages an hour.
        assertEquals((314 / 40.0 * 3600).toLong(), s.timeLeftSeconds)
        // The book's pace wins when the server has one.
        assertEquals(3600L * 314 / 60, s.copy(bookPagesPerHour = 60.0).timeLeftSeconds)
        assertFalse(s.showsGoal)
        assertTrue(s.copy(dailyGoalMinutes = 30, todaySeconds = 1200).showsGoal)
    }

    @Test
    fun summaryWithoutPages() {
        val s = SessionSummary(bookId = 1, sessionId = "s", activeSeconds = 1800, outcome = SaveOutcome.Queued, startPercent = 10.0, endPercent = 15.0)
        assertNull(s.pagesRead)
        assertNull(s.pagesPerHour)
        assertEquals(5.0, s.percentGained!!, 0.001)
        assertEquals(85.0, s.percentLeft!!, 0.001)
        // 10 percent an hour: 8.5 hours left.
        assertEquals(30_600L, s.timeLeftSeconds)
        assertNull(SessionSummary(bookId = 1, sessionId = "s", activeSeconds = 600, outcome = SaveOutcome.Sent).timeLeftSeconds)
    }

    @Test
    fun bookStartProgress() {
        val book = TimerBook(1, "T", "A", null, 5, pageTotal = 464, serverPageCount = 300, percent = 40.0, page = 130)
        assertEquals(28.02, book.startProgress!!, 0.001)
        assertEquals(40.0, book.copy(pageTotal = null, page = null).startProgress!!, 0.001)
    }
}
