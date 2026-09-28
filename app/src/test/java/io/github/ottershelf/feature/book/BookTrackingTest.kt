package io.github.ottershelf.feature.book

import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.settings.PageOverride
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.BookSession
import io.github.ottershelf.core.tracking.BookSessionStats
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.core.util.IsoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BookTrackingTest {

    private val utc = ZoneOffset.UTC

    private fun session(id: Long, start: String, minutes: Int = 30, delta: Double? = 5.0, end: Double? = null) = BookSession(
        id = id,
        startedAt = start,
        endedAt = IsoTime.format(IsoTime.parse(start)!! + minutes * 60_000L),
        durationSeconds = minutes * 60,
        progressDelta = delta,
        endProgress = end,
        source = "android",
    )

    private val current = ReadingAttempt(id = 2, bookId = 1, startedOn = "2026-09-14")
    private val past = ReadingAttempt(id = 1, bookId = 1, startedOn = "2019-03-01", endedOn = "2019-03-20", outcome = AttemptOutcome.COMPLETED)

    @Test
    fun `timeline merges readings and sessions newest first, start below and end above a day's sessions`() {
        val finishedToday = current.copy(endedOn = "2026-09-16", outcome = AttemptOutcome.COMPLETED)
        val state = BookTrackingUiState(
            sessions = listOf(
                session(12, "2026-09-16T20:00:00Z", end = 100.0),
                session(11, "2026-09-14T08:00:00Z"),
            ),
            sessionsTotal = 2,
            attempts = listOf(finishedToday, past),
        )
        val keys = timelineOf(state, total = 200, zone = utc).map { it.key }
        assertEquals(listOf("a2e", "s12", "s11", "a2s", "a1e", "a1s"), keys)
        val last = timelineOf(state, total = 200, zone = utc)[1] as TimelineItem.Session
        assertEquals(10, last.pages) // 5 percentage points of 200 pages
        assertEquals(200, last.endPage)
    }

    @Test
    fun `older readings wait until the sessions before them are loaded`() {
        val state = BookTrackingUiState(
            sessions = listOf(session(12, "2026-09-16T20:00:00Z"), session(11, "2026-09-15T08:00:00Z")),
            sessionsTotal = 5,
            attempts = listOf(current, past),
        )
        assertEquals(listOf("s12", "s11"), timelineOf(state, total = null, zone = utc).map { it.key })
        assertNull((timelineOf(state, total = null, zone = utc)[0] as TimelineItem.Session).pages)
    }

    @Test
    fun `progress uses the user's page total, the higher progress, and a hand-set page until a later session`() {
        val book = sampleBook.copy(pageCount = 183, readStatus = ReadStatusInfo("reading", startedAt = "2026-09-14T00:00:00.000Z"))
        val base = BookTrackingUiState(
            sessions = listOf(session(11, "2026-09-15T08:00:00Z")),
            sessionsTotal = 1,
            stats = BookSessionStats(totalSessions = 3, latestEndProgress = 40.0, paceProgressDelta = 20.0, paceDurationSeconds = 7200),
            fileProgress = 50.0,
            attempts = listOf(current, past),
            settings = AppSettings(pageTotals = mapOf(1L to 200)),
        )
        val p = progressOf(book, "reading", base, LocalDate.of(2026, 9, 16))
        assertEquals(3, p.dayNumber)
        assertEquals(200, p.total)
        assertTrue(p.totalIsOwn)
        assertEquals(100, p.currentPage) // 50% of 200
        assertEquals(2, p.readingNumber) // one completed + this one
        assertEquals(20.0, p.pace!!.pagesPerHour!!, 0.001) // 10 %/h of 200 pages
        assertEquals(5L * 3600, p.timeLeftSeconds) // 50% left at 10%/h
        assertFalse(p.pageSetByHand)

        val later = IsoTime.parse("2026-09-16T10:00:00Z")!!
        val byHand = progressOf(book, "reading", base.copy(settings = base.settings.copy(pageOverrides = mapOf(1L to PageOverride(130, later)))), LocalDate.of(2026, 9, 16))
        assertTrue(byHand.pageSetByHand)
        assertEquals(130, byHand.currentPage)
        assertEquals(65.0, byHand.percent!!, 0.001)

        val earlier = IsoTime.parse("2026-09-15T07:00:00Z")!!
        val superseded = progressOf(book, "reading", base.copy(settings = base.settings.copy(pageOverrides = mapOf(1L to PageOverride(130, earlier)))), LocalDate.of(2026, 9, 16))
        assertFalse(superseded.pageSetByHand)
        assertEquals(100, superseded.currentPage)
    }

    @Test
    fun `no speed or time left until 30 minutes of reading across 3 sessions`() {
        val book = sampleBook.copy(pageCount = null, readStatus = ReadStatusInfo("reading", startedAt = "2026-09-23T00:00:00.000Z"))
        val today = LocalDate.of(2026, 9, 25)
        // The phone's case: 7 short sessions, 9 minutes in all, 20% gained, no page total.
        val short = BookTrackingUiState(
            sessions = listOf(session(11, "2026-09-25T08:00:00Z", minutes = 1)),
            sessionsTotal = 7,
            stats = BookSessionStats(totalSessions = 7, latestEndProgress = 20.0, paceProgressDelta = 52.5, paceDurationSeconds = 9 * 60),
        )
        val p = progressOf(book, "reading", short, today)
        assertNull(p.pace)
        assertNull(p.timeLeftSeconds)

        // Enough time but only two sessions: still nothing.
        val twoLong = short.copy(stats = short.stats.copy(totalSessions = 2, paceProgressDelta = 20.0, paceDurationSeconds = 3600))
        assertNull(progressOf(book, "reading", twoLong, today).pace)

        // At the floor: a time left, but no speed without a page total (and never in %/h).
        val enough = short.copy(stats = short.stats.copy(totalSessions = 3, paceProgressDelta = 5.0, paceDurationSeconds = MIN_PACE_SECONDS))
        val q = progressOf(book, "reading", enough, today)
        assertNull(q.pace!!.pagesPerHour)
        assertEquals(8L * 3600, q.timeLeftSeconds) // 80% left at 10%/h

        // With the user's page total, the speed is in pages.
        val withTotal = enough.copy(settings = AppSettings(pageTotals = mapOf(1L to 300)))
        assertEquals(30.0, progressOf(book, "reading", withTotal, today).pace!!.pagesPerHour!!, 0.001)
    }

    @Test
    fun `a finished book shows full and no day count`() {
        val book = sampleBook.copy(readStatus = ReadStatusInfo("read", startedAt = "2026-09-01", finishedAt = "2026-09-20"))
        val p = progressOf(book, "read", BookTrackingUiState(), LocalDate.of(2026, 9, 25))
        assertTrue(p.finished)
        assertNull(p.dayNumber)
        assertEquals(100.0, p.percent!!, 0.0)
        assertEquals(LocalDate.of(2026, 9, 20), p.finishedOn)
    }
}
