package io.github.ottershelf.core.tracking

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.core.util.IsoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** TrackingRepository against a fake server: bodies, the offline queue and the manual-session check. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingRepositoryTest {

    private val now = IsoTime.parse("2026-09-25T20:00:00.000Z")!!

    private class Setup(
        val repo: TrackingRepository,
        val remote: FakeTrackingRemote,
        val changes: ReadingChanges,
        val queued: IntArray,
        val account: MutableStateFlow<AuthUser?>,
    )

    private fun TestScope.setup(store: MemoryStore = MemoryStore(), remote: FakeTrackingRemote = FakeTrackingRemote()): Setup {
        val changes = ReadingChanges()
        val queued = intArrayOf(0)
        val account = MutableStateFlow<AuthUser?>(remote.user)
        val repo = TrackingRepository(
            remote = remote,
            queue = TrackingQueue(store),
            accountKey = { "books.example_reader" },
            account = account,
            updateUser = { account.value = it },
            readingChanges = changes,
            scope = backgroundScope,
            onQueued = { queued[0]++ },
            clock = { now },
        )
        repo.start()
        runCurrent()
        return Setup(repo, remote, changes, queued, account)
    }

    private suspend fun TrackingRepository.logTimer(id: String = "timer-1", seconds: Int = 1_500) = logTimedSession(
        bookId = 7,
        fileId = 70,
        sessionId = id,
        startedAtMs = now - 3_600_000,
        endedAtMs = now - 3_600_000 + seconds * 1000L,
        activeSeconds = seconds,
        startProgress = 10.0,
        endProgress = 25.555,
    )

    @Test
    fun aTimedSessionGoesThroughTheFileRouteAsAndroid() = runTest {
        val s = setup()
        val before = s.changes.changes.value
        assertEquals(SessionSaveResult.Sent, s.repo.logTimer())
        val (fileId, body) = s.remote.timed.single()
        assertEquals(70L, fileId)
        assertEquals("timer-1", body.sessionId)
        assertEquals("android", body.source)
        assertEquals("read", body.sessionType)
        assertEquals(1_500, body.durationSeconds)
        assertEquals(25.56, body.endProgress!!, 0.0001)
        assertEquals(15.56, body.progressDelta!!, 0.0001)
        assertEquals("2026-09-25T19:00:00.000Z", body.startedAt)
        assertTrue(s.changes.changes.value > before)
        assertEquals(1, s.repo.bookVersion(7).first())
        assertEquals(0, s.repo.pending.value)
    }

    @Test
    fun sessionsUnderTenSecondsAreNotKept() = runTest {
        val s = setup()
        assertEquals(SessionSaveResult.TooShort, s.repo.logTimer(seconds = 9))
        assertTrue(s.remote.timed.isEmpty())
        assertFalse(s.repo.hasUnsent())
    }

    @Test
    fun offlineSessionsWaitAndGoOnceWithTheSameId() = runTest {
        val store = MemoryStore()
        val s = setup(store)
        s.remote.offline = true
        assertEquals(SessionSaveResult.Queued, s.repo.logTimer())
        assertEquals(1, s.queued[0])
        assertEquals(1, s.repo.pending.value)

        // A new process (the app was killed) sends it when the connection is back.
        val again = setup(store)
        runCurrent()
        assertEquals("timer-1", again.remote.timed.single().second.sessionId)
        assertFalse(again.repo.hasUnsent())
    }

    @Test
    fun aRefusedSessionIsDroppedNotRetried() = runTest {
        val s = setup()
        s.remote.sessionError = 403
        val result = s.repo.logTimer()
        assertTrue(result is SessionSaveResult.Rejected)
        assertFalse(s.repo.hasUnsent())
    }

    @Test
    fun aSessionSentWithoutAValidTokenWaitsForTheAccount() = runTest {
        // A refresh token past its lifetime (or a refresh that failed for now): the send goes out
        // without a token and the server answers 401. That isn't the server refusing the session.
        val s = setup()
        s.remote.sessionError = 401
        assertEquals(SessionSaveResult.Queued, s.repo.logTimer())
        assertEquals(SessionSaveResult.Queued, s.repo.logManualSession(7, now - 3_600_000, minutes = 20))
        assertEquals(2, s.repo.pending.value)
        assertTrue("the worker retries", runCatching { s.repo.flush() }.isFailure)
        assertEquals("nothing dropped", 2, s.repo.pending.value)

        s.remote.sessionError = null
        assertTrue(s.repo.flush())
        assertEquals(1, s.remote.timed.size)
        assertEquals(1, s.remote.manual.size)
    }

    @Test
    fun signingOutStopsThePassAndKeepsTheQueue() = runTest {
        val s = setup()
        s.remote.offline = true
        s.repo.logTimer()
        s.remote.offline = false
        s.account.value = null
        runCurrent()
        assertFalse(s.repo.flush())
        assertTrue(s.remote.timed.isEmpty())
        assertTrue(s.repo.hasUnsent())
    }

    @Test
    fun anUnconfirmedSessionWhoseBookIsGoneIsDroppedAndTheRestStillGo() = runTest {
        val s = setup()
        s.remote.loseNextManualAnswer = true
        assertEquals(SessionSaveResult.Queued, s.repo.logManualSession(7, now - 3_600_000, minutes = 30))
        // The book is deleted (or out of reach) before the next pass: looking it up answers 404.
        s.remote.sessionListError = 404
        assertEquals(SessionSaveResult.Sent, s.repo.logManualSession(8, now - 1_800_000, minutes = 10))
        assertEquals(listOf(7L, 8L), s.remote.manual.map { it.first })
        assertFalse(s.repo.hasUnsent())
    }

    @Test
    fun aTimerWithoutAFileIsQueuedOnceUnderItsId() = runTest {
        val s = setup()
        s.remote.offline = true
        repeat(2) { s.repo.logTimedSession(7, null, "timer-9", now - 3_000_000, now - 1_000_000, activeSeconds = 1_200) }
        assertEquals(1, s.repo.pending.value)
        s.remote.offline = false
        assertTrue(s.repo.flush())
        assertEquals(1, s.remote.manual.size)
    }

    @Test
    fun theSessionIsStoredBeforeItIsSent() = runTest {
        val s = setup()
        var seen: Pair<Boolean, Int>? = null
        s.repo.logTimedSession(7, 70, "timer-2", now - 600_000, now, activeSeconds = 600, stored = {
            seen = s.repo.hasUnsent() to s.remote.timed.size
        })
        assertEquals(true to 0, seen)
        assertEquals(1, s.remote.timed.size)
    }

    @Test
    fun aPauseOverMidnightSplitsTheSessionByDay() = runTest {
        val s = setup() // no timezone set: the server's days are UTC days
        val day1 = IsoTime.parse("2026-09-23T21:00:00.000Z")!!
        val day2 = IsoTime.parse("2026-09-24T21:00:00.000Z")!!
        val spans = listOf(ActiveSpan(day1, day1 + 1_800_000), ActiveSpan(day2, day2 + 1_800_000))
        val result = s.repo.logTimedSession(
            7, 70, "timer-3", startedAtMs = day1, endedAtMs = day2 + 1_800_000, activeSeconds = 3_600,
            startProgress = 10.0, endProgress = 30.0, spans = spans,
        )
        assertEquals(SessionSaveResult.Sent, result)
        val (first, second) = s.remote.timed.map { it.second }.sortedBy { it.startedAt }
        assertEquals("2026-09-23T21:00:00.000Z", first.startedAt)
        assertEquals("2026-09-23T21:30:00.000Z", first.endedAt)
        assertEquals(1_800, first.durationSeconds)
        assertEquals(10.0, first.progressDelta!!, 0.001)
        assertNull("the position goes with the last part", first.endProgress)
        assertEquals("timer-3", second.sessionId)
        assertEquals(1_800, second.durationSeconds)
        assertEquals(10.0, second.progressDelta!!, 0.001)
        assertEquals(30.0, second.endProgress!!, 0.001)

        // Saving the same timer again sends the same parts (the server keeps one per id).
        s.repo.logTimedSession(7, 70, "timer-3", day1, day2 + 1_800_000, 3_600, 10.0, 30.0, spans = spans)
        assertEquals(2, s.remote.timed.map { it.second.sessionId }.distinct().size)
    }

    @Test
    fun pausesWithinADayOrTooShortToSendDontSplit() {
        val zone = java.time.ZoneOffset.UTC
        val t = IsoTime.parse("2026-09-24T20:00:00.000Z")!!
        val sameDay = listOf(ActiveSpan(t, t + 600_000), ActiveSpan(t + 3_600_000, t + 4_200_000))
        assertEquals(listOf("s"), TimedParts.split("s", t, t + 4_200_000, 1_200, sameDay, zone).map { it.id })
        // Five seconds after midnight would be dropped by the server: it joins the evening before.
        val tail = listOf(ActiveSpan(t, t + 600_000), ActiveSpan(t + 14_400_000, t + 14_405_000))
        assertEquals(1, TimedParts.split("s", t, t + 14_405_000, 605, tail, zone).size)
        // Stretches that don't add up to the active time (an older timer) aren't trusted.
        val partial = listOf(ActiveSpan(t, t + 600_000), ActiveSpan(t + 86_400_000, t + 87_000_000))
        assertEquals(1, TimedParts.split("s", t, t + 87_000_000, 3_000, partial, zone).size)
    }

    @Test
    fun aManualSessionQueuedOfflineStillStartsTheBook() = runTest {
        val s = setup()
        s.remote.offline = true
        val day = LocalDate.of(2026, 9, 25)
        assertEquals(SessionSaveResult.Queued, s.repo.logManualSession(7, now - 3_600_000, minutes = 30, startReadingOn = day))
        s.remote.books[8] = io.github.ottershelf.core.model.BookDetail(8, readStatus = io.github.ottershelf.core.model.ReadStatusInfo(status = "read"))
        s.repo.logManualSession(8, now - 1_800_000, minutes = 10, startReadingOn = day)

        s.remote.offline = false
        assertTrue(s.repo.flush())
        assertEquals(2, s.remote.manual.size)
        val body = s.remote.statusBodies.single() // book 8 was finished meanwhile: left alone
        assertEquals("reading", body["status"]!!.jsonPrimitive.content)
        assertEquals("2026-09-25", body["startedAt"]!!.jsonPrimitive.content)
        assertEquals("reading", s.changes.statusOf(7, null))
    }

    @Test
    fun aServerErrorIsRetried() = runTest {
        val s = setup()
        s.remote.sessionError = 503
        assertEquals(SessionSaveResult.Queued, s.repo.logTimer())
        s.remote.sessionError = null
        assertTrue(s.repo.flush())
        assertEquals(1, s.remote.timed.size)
    }

    @Test
    fun aTimerWithoutAFileBecomesAManualSessionOfRoundedMinutes() = runTest {
        val s = setup()
        val result = s.repo.logTimedSession(7, null, "t", now - 3_000_000, now - 1_000_000, activeSeconds = 25 * 60 + 40, endProgress = 30.0)
        assertEquals(SessionSaveResult.Sent, result)
        assertEquals(26, s.remote.manual.single().second.durationMinutes)
        assertEquals(30.0, s.remote.manual.single().second.endProgress!!, 0.0)
    }

    @Test
    fun manualSessionsAreCheckedRules() = runTest {
        val s = setup()
        assertTrue(s.repo.logManualSession(7, now - 60_000, minutes = 0) is SessionSaveResult.Rejected)
        assertTrue(s.repo.logManualSession(7, now - 60_000, minutes = 1441) is SessionSaveResult.Rejected)
        assertTrue(s.repo.logManualSession(7, now + 60_000, minutes = 10) is SessionSaveResult.Rejected)
        assertTrue(s.remote.manual.isEmpty())
    }

    @Test
    fun aManualSessionWhoseAnswerWasLostIsNotSentTwice() = runTest {
        val s = setup()
        s.remote.loseNextManualAnswer = true
        assertEquals(SessionSaveResult.Queued, s.repo.logManualSession(7, now - 3_600_000, minutes = 30, endProgress = 40.0))
        assertEquals(1, s.remote.manual.size) // it did reach the server

        assertTrue(s.repo.flush())
        assertEquals("found in the book's sessions, not resent", 1, s.remote.manual.size)
        val window = s.remote.sessionQueries.single()
        assertEquals("2026-09-25T18:59:00.000Z", window.dateFrom)
        assertEquals("2026-09-25T19:01:00.000Z", window.dateTo)
    }

    @Test
    fun aManualSessionThatNeverArrivedIsSentAgain() = runTest {
        val store = MemoryStore()
        val s = setup(store)
        s.remote.loseNextManualAnswer = true
        s.repo.logManualSession(7, now - 3_600_000, minutes = 30)
        s.remote.serverSessions.clear() // it didn't really arrive
        assertTrue(s.repo.flush())
        assertEquals(2, s.remote.manual.size)
        assertFalse(s.repo.hasUnsent())
    }

    @Test
    fun theCalendarBeforeTheFirstSessionIsEmpty() = runTest {
        val s = setup()
        s.remote.calendarError = 400
        val year = s.repo.activityCalendar(2019)
        assertEquals(2019, year.year)
        assertTrue(year.days.isEmpty())
        s.remote.calendarError = null
        assertEquals(1, s.repo.activityCalendar(2026).days.size)
    }

    @Test
    fun statusDatesAreSentAsDatesAndNullsExplicitly() = runTest {
        val s = setup()
        s.repo.setStatus(7, "read", startedAt = Patch.Keep, finishedAt = Patch.Set(LocalDate.of(2026, 9, 24)))
        assertEquals("read", s.changes.statusOf(7, null)) // grids show it at once
        s.repo.setStatus(7, null, startedAt = Patch.Clear)
        val (first, second) = s.remote.statusBodies
        assertEquals("read", first["status"]!!.jsonPrimitive.content)
        assertEquals("2026-09-24", first["finishedAt"]!!.jsonPrimitive.content)
        assertFalse(first.containsKey("startedAt"))
        assertEquals(JsonNull, second["startedAt"])
        assertFalse(second.containsKey("status"))
    }

    @Test
    fun attemptsRatingAndReviewBodies() = runTest {
        val s = setup()
        s.repo.createPastRead(7, LocalDate.of(2025, 1, 2), LocalDate.of(2025, 2, 3), AttemptOutcome.COMPLETED)
        s.repo.updateAttempt(7, 3, outcome = Patch.Clear)
        val (created, updated) = s.remote.attemptBodies
        assertEquals("2025-01-02", created["startedOn"]!!.jsonPrimitive.content)
        assertEquals("completed", created["outcome"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, updated["outcome"])
        assertFalse(updated.containsKey("startedOn"))

        s.repo.setRating(7, null)
        s.repo.setPersonalNote(7, "x".repeat(10_050))
        assertNull(s.remote.ratings.single().second)
        assertEquals(10_000, s.remote.notes.single().second!!.length)
        assertEquals(TrackingBodies.rating(7, null).toString(), """{"bookIds":[7],"rating":null}""")
        assertEquals(TrackingBodies.note(null).toString(), """{"note":null}""")
    }

    @Test
    fun theYearlyGoalRewritesTheWholeDashboardConfig() = runTest {
        val remote = FakeTrackingRemote()
        remote.user = remote.user.copy(
            settings = io.github.ottershelf.core.model.UserSettings(
                dashboardConfig = kotlinx.serialization.json.buildJsonObject {
                    put("readingGoal", kotlinx.serialization.json.JsonPrimitive(12))
                    put("libraryIds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(3))))
                },
            ),
        )
        val s = setup(remote = remote)
        s.repo.setYearlyGoal(24)
        val sent = s.remote.settingsPatches.single()
        assertEquals(setOf("dashboardConfig"), sent.keys)
        val config = sent["dashboardConfig"] as kotlinx.serialization.json.JsonObject
        assertEquals(24, config["readingGoal"]!!.jsonPrimitive.int)
        assertTrue("other dashboard settings kept", config.containsKey("libraryIds"))
        assertEquals(24, s.repo.yearlyGoal())
    }

    @Test
    fun ratingNeedsTheMetadataPermission() {
        assertFalse(TrackingRepository.canRate(null))
        assertFalse(TrackingRepository.canRate(AuthUser(1, "a")))
        assertTrue(TrackingRepository.canRate(AuthUser(1, "a", permissions = listOf("library_edit_metadata"))))
        assertFalse(TrackingRepository.canRate(AuthUser(1, "a", permissions = listOf("library_edit_metadata", "demo_restricted"))))
        assertTrue(TrackingRepository.canRate(AuthUser(1, "a", isSuperuser = true)))
    }

    private fun claim(id: String) = CelebrationClaim(id, achievement = AchievementItem(key = "k-$id", name = "Badge $id"))

    @Test
    fun aFailedAcknowledgementGoesBeforeTheNextClaim() = runTest {
        val s = setup()
        s.remote.offline = true
        assertTrue(runCatching { s.repo.acknowledgeCelebration("c1") }.isFailure)
        s.remote.offline = false
        s.remote.claims += claim("c2")
        assertEquals("c2", s.repo.claimCelebration()?.claimId)
        assertEquals(listOf("c1"), s.remote.acknowledged)
        s.repo.acknowledgeCelebration("c2")
        assertNull(s.repo.claimCelebration())
        assertEquals(listOf("c1", "c2"), s.remote.acknowledged) // c1 only once
    }

    @Test
    fun aRefusedAcknowledgementIsNotRetried() = runTest {
        val s = setup()
        s.remote.acknowledgeError = 404
        assertTrue(runCatching { s.repo.acknowledgeCelebration("gone") }.isFailure)
        s.remote.acknowledgeError = null
        s.repo.claimCelebration()
        assertTrue(s.remote.acknowledged.isEmpty())
    }

    @Test
    fun claimsAreAnnouncedAndHandedOver() = runTest {
        val s = setup()
        val heard = mutableListOf<Unit>()
        backgroundScope.launch { s.repo.claimed.collect { heard += it } }
        runCurrent()
        assertNull(s.repo.claimCelebration())
        assertTrue(heard.isEmpty())
        s.remote.claims += claim("c1")
        s.repo.claimCelebration()
        runCurrent()
        assertEquals(1, heard.size)

        s.repo.handOverClaim(claim("a"))
        s.repo.handOverClaim(claim("b"))
        assertEquals(listOf("a", "b"), s.repo.unshownClaims.value.map { it.claimId })
        assertEquals("a", s.repo.takeUnshownClaim()?.claimId)
        assertEquals("b", s.repo.takeUnshownClaim()?.claimId)
        assertNull(s.repo.takeUnshownClaim())
    }

    @Test
    fun sessionQueryString() {
        assertEquals(
            "page=2&pageSize=100&sortBy=startedAt&sortDir=asc&dateFrom=2026-09-25T00%3A00%3A00.000Z",
            SessionQuery(page = 2, pageSize = 500, sortDir = "asc", dateFrom = "2026-09-25T00:00:00.000Z").toQueryString(),
        )
    }
}
