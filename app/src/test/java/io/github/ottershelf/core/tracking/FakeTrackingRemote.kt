package io.github.ottershelf.core.tracking

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.UserSettings
import io.github.ottershelf.core.network.ApiException
import java.io.IOException

/** An in-memory Preferences DataStore. */
class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

/** A BookOrbit server for the tracker's calls: records what was sent, fails when told to. */
class FakeTrackingRemote : TrackingRemote {
    var user = AuthUser(id = 1, username = "reader")
    /** Every users.settings PATCH body (the `settings` object). */
    val settingsPatches = mutableListOf<JsonObject>()
    val timed = mutableListOf<Pair<Long, TimedSessionBody>>()
    val manual = mutableListOf<Pair<Long, ManualSessionBody>>()
    val statusBodies = mutableListOf<JsonObject>()
    val attemptBodies = mutableListOf<JsonObject>()
    val ratings = mutableListOf<Pair<Long, Int?>>()
    val notes = mutableListOf<Pair<Long, String?>>()
    val deleted = mutableListOf<Long>()
    val sessionQueries = mutableListOf<SessionQuery>()
    var serverSessions = mutableListOf<BookSession>()

    /** Throws IOException (no connection) on every call. */
    var offline = false
    /** The next manual create reaches the server but its answer is lost. */
    var loseNextManualAnswer = false
    /** An HTTP error for the session routes. */
    var sessionError: Int? = null
    var calendarError: Int? = null
    /** An HTTP error for `GET books/:id/sessions` (the manual-session lookup). */
    var sessionListError: Int? = null
    /** `GET books/:id` answers: files and the read status. */
    val books = mutableMapOf<Long, BookDetail>()
    private var nextId = 100L

    private fun check() {
        if (offline) throw IOException("offline")
    }

    override suspend fun me(): AuthUser {
        check()
        return user
    }

    override suspend fun patchSettings(settings: JsonObject): JsonObject? {
        check()
        settingsPatches += settings
        val current = user.settings ?: UserSettings()
        var next = current
        settings["bookorbitAndroid"]?.let { next = next.copy(bookorbitAndroid = it as JsonObject) }
        settings["dashboardConfig"]?.let { next = next.copy(dashboardConfig = it as JsonObject) }
        user = user.copy(settings = next)
        return JsonObject(settings)
    }

    override suspend fun book(bookId: Long): BookDetail {
        check()
        return books[bookId] ?: BookDetail(id = bookId)
    }

    override suspend fun sessions(bookId: Long, query: SessionQuery): BookSessionList {
        check()
        sessionListError?.let { throw ApiException(it, "HTTP $it") }
        sessionQueries += query
        return BookSessionList(items = serverSessions.toList(), total = serverSessions.size)
    }

    override suspend fun saveTimedSession(fileId: Long, body: TimedSessionBody) {
        check()
        sessionError?.let { throw ApiException(it, "HTTP $it") }
        timed += fileId to body
    }

    override suspend fun createManualSession(bookId: Long, body: ManualSessionBody): BookSession {
        check()
        sessionError?.let { throw ApiException(it, "HTTP $it") }
        manual += bookId to body
        val end = io.github.ottershelf.core.util.IsoTime.parse(body.startedAt)!! + body.durationMinutes * 60_000L
        val session = BookSession(
            id = nextId++,
            startedAt = body.startedAt,
            endedAt = io.github.ottershelf.core.util.IsoTime.format(end),
            durationSeconds = body.durationMinutes * 60,
            endProgress = body.endProgress,
            source = "manual",
        )
        serverSessions += session
        if (loseNextManualAnswer) {
            loseNextManualAnswer = false
            throw IOException("connection reset")
        }
        return session
    }

    override suspend fun deleteSession(bookId: Long, sessionId: Long) {
        check()
        deleted += sessionId
    }

    override suspend fun moveSession(sessionId: Long, body: MoveSessionBody): TimelineSession {
        check()
        return TimelineSession(sessionId, 1, startedAt = body.startedAt, endedAt = body.endedAt)
    }

    override suspend fun setStatus(bookId: Long, body: JsonObject): BookStatus {
        check()
        statusBodies += body
        val status = (body["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "reading"
        books[bookId] = (books[bookId] ?: BookDetail(id = bookId)).copy(readStatus = ReadStatusInfo(status = status))
        return BookStatus(status = status)
    }

    override suspend fun attempts(bookId: Long, page: Int, pageSize: Int): ReadingAttemptList {
        check()
        return ReadingAttemptList()
    }

    override suspend fun createAttempt(bookId: Long, body: JsonObject): ReadingAttempt {
        check()
        attemptBodies += body
        return ReadingAttempt(id = nextId++, bookId = bookId)
    }

    override suspend fun updateAttempt(bookId: Long, attemptId: Long, body: JsonObject): ReadingAttempt {
        check()
        attemptBodies += body
        return ReadingAttempt(id = attemptId, bookId = bookId)
    }

    override suspend fun deleteAttempt(bookId: Long, attemptId: Long) = check()

    override suspend fun startReread(bookId: Long, resetProgress: Boolean): BookStatus {
        check()
        return BookStatus(status = "rereading")
    }

    override suspend fun setRating(bookId: Long, rating: Int?) {
        check()
        ratings += bookId to rating
    }

    override suspend fun setPersonalNote(bookId: Long, note: String?) {
        check()
        notes += bookId to note
    }

    override suspend fun activityOverview(): ActivityOverview {
        check()
        return ActivityOverview()
    }

    override suspend fun activityCalendar(year: Int): ActivityCalendar {
        check()
        calendarError?.let { throw ApiException(it, "Activity year is not available") }
        return ActivityCalendar(year = year, availableYears = listOf(year), days = listOf(ActivityDay("$year-01-01", totalSeconds = 60)))
    }

    override suspend fun activityDay(day: String): ActivityDayDetail {
        check()
        return ActivityDayDetail(day = day)
    }

    override suspend fun sessionTimeline(year: Int, week: Int): SessionTimeline {
        check()
        return SessionTimeline(year, week)
    }

    /** What `claimCelebration` hands out, in order (then null). */
    val claims = ArrayDeque<CelebrationClaim>()
    /** Every acknowledgement that reached the server, in order. */
    val acknowledged = mutableListOf<String>()
    /** An HTTP error for the acknowledge route. */
    var acknowledgeError: Int? = null

    override suspend fun claimCelebration(): CelebrationClaim? {
        check()
        return claims.removeFirstOrNull()
    }

    override suspend fun acknowledgeCelebration(claimId: String) {
        check()
        acknowledgeError?.let { throw ApiException(it, "Celebration claim not found") }
        acknowledged += claimId
    }
}
