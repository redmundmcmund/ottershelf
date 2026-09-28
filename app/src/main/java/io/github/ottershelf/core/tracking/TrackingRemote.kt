package io.github.ottershelf.core.tracking

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import java.net.URLEncoder

/**
 * Every server call the reading tracker makes, behind an interface so TrackingRepository and
 * AppSettingsRepository are tested against a fake. [ApiTrackingRemote] is the real one. Each call
 * throws ApiException for an HTTP error and IOException offline, like `Api`.
 */
interface TrackingRemote {
    // account settings (GET auth/me, PATCH users/me/settings)
    suspend fun me(): AuthUser
    /** Shallow-merges [settings] into users.settings (each top-level key replaced whole); returns the stored settings. */
    suspend fun patchSettings(settings: JsonObject): JsonObject?

    /** `GET books/:id`: the book's files and this user's read status. */
    suspend fun book(bookId: Long): BookDetail

    // sessions
    suspend fun sessions(bookId: Long, query: SessionQuery): BookSessionList
    suspend fun saveTimedSession(fileId: Long, body: TimedSessionBody)
    suspend fun createManualSession(bookId: Long, body: ManualSessionBody): BookSession
    suspend fun deleteSession(bookId: Long, sessionId: Long)
    suspend fun moveSession(sessionId: Long, body: MoveSessionBody): TimelineSession

    // status and attempts
    suspend fun setStatus(bookId: Long, body: JsonObject): BookStatus
    suspend fun attempts(bookId: Long, page: Int, pageSize: Int): ReadingAttemptList
    suspend fun createAttempt(bookId: Long, body: JsonObject): ReadingAttempt
    suspend fun updateAttempt(bookId: Long, attemptId: Long, body: JsonObject): ReadingAttempt
    suspend fun deleteAttempt(bookId: Long, attemptId: Long)
    suspend fun startReread(bookId: Long, resetProgress: Boolean): BookStatus

    // rating and review
    suspend fun setRating(bookId: Long, rating: Int?)
    suspend fun setPersonalNote(bookId: Long, note: String?)

    // statistics
    suspend fun activityOverview(): ActivityOverview
    suspend fun activityCalendar(year: Int): ActivityCalendar
    suspend fun activityDay(day: String): ActivityDayDetail
    suspend fun sessionTimeline(year: Int, week: Int): SessionTimeline

    // achievements
    suspend fun claimCelebration(): CelebrationClaim?
    suspend fun acknowledgeCelebration(claimId: String)
}

/** `GET books/:bookId/sessions` query (ListBookReadingSessionsDto). */
data class SessionQuery(
    val page: Int = 1,
    /** 1..100 */
    val pageSize: Int = 100,
    /** startedAt, durationSeconds, progressDelta or endProgress. */
    val sortBy: String = "startedAt",
    val sortDir: String = "desc",
    /** ISO instants; filter on the session's startedAt. */
    val dateFrom: String? = null,
    val dateTo: String? = null,
    /** Drops sessions without a file. */
    val format: String? = null,
) {
    fun toQueryString(): String = buildList {
        add("page=$page")
        add("pageSize=${pageSize.coerceIn(1, 100)}")
        add("sortBy=$sortBy")
        add("sortDir=$sortDir")
        dateFrom?.let { add("dateFrom=${enc(it)}") }
        dateTo?.let { add("dateTo=${enc(it)}") }
        format?.let { add("format=${enc(it)}") }
    }.joinToString("&")
}

private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

/** The request bodies that need explicit nulls (the DTOs treat null and missing differently). */
internal object TrackingBodies {

    fun status(status: String?, startedAt: Patch<String>, finishedAt: Patch<String>): JsonObject = buildJsonObject {
        if (status != null) put("status", status)
        field("startedAt", startedAt)
        field("finishedAt", finishedAt)
    }

    fun attempt(startedOn: Patch<String>, endedOn: Patch<String>, outcome: Patch<String>): JsonObject = buildJsonObject {
        field("startedOn", startedOn)
        field("endedOn", endedOn)
        field("outcome", outcome)
    }

    fun rating(bookId: Long, rating: Int?): JsonObject = buildJsonObject {
        put("bookIds", buildJsonArray { add(JsonPrimitive(bookId)) })
        put("rating", rating?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun note(note: String?): JsonObject = buildJsonObject { put("note", note?.let(::JsonPrimitive) ?: JsonNull) }

    private fun kotlinx.serialization.json.JsonObjectBuilder.field(name: String, value: Patch<String>) {
        when (value) {
            Patch.Keep -> Unit
            Patch.Clear -> put(name, JsonNull)
            is Patch.Set -> put(name, value.value)
        }
    }
}

/** [TrackingRemote] over the app's authenticated [Api] client. */
class ApiTrackingRemote(private val api: Api) : TrackingRemote {

    private val json = ApiJson

    private suspend fun <T> get(path: String, de: DeserializationStrategy<T>): T =
        api.send("GET", path, null) { json.decodeFromString(de, it) }

    private suspend fun <T> send(method: String, path: String, body: JsonElement?, de: DeserializationStrategy<T>): T =
        api.send(method, path, body) { json.decodeFromString(de, it) }

    private suspend fun sendNoContent(method: String, path: String, body: JsonElement?) {
        api.send(method, path, body) { }
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = json.encodeToJsonElement(serializer, value)

    override suspend fun me(): AuthUser = api.me()

    override suspend fun patchSettings(settings: JsonObject): JsonObject? {
        val user = send("PATCH", "users/me/settings", buildJsonObject { put("settings", settings) }, JsonObject.serializer())
        return user["settings"] as? JsonObject
    }

    override suspend fun book(bookId: Long): BookDetail = api.book(bookId)

    override suspend fun sessions(bookId: Long, query: SessionQuery): BookSessionList =
        get("books/$bookId/sessions?${query.toQueryString()}", BookSessionList.serializer())

    override suspend fun saveTimedSession(fileId: Long, body: TimedSessionBody) =
        sendNoContent("POST", "books/files/$fileId/sessions", encode(TimedSessionBody.serializer(), body))

    override suspend fun createManualSession(bookId: Long, body: ManualSessionBody): BookSession =
        send("POST", "books/$bookId/sessions", encode(ManualSessionBody.serializer(), body), BookSession.serializer())

    override suspend fun deleteSession(bookId: Long, sessionId: Long) =
        sendNoContent("DELETE", "books/$bookId/sessions/$sessionId", null)

    override suspend fun moveSession(sessionId: Long, body: MoveSessionBody): TimelineSession =
        send("PATCH", "user-statistics/session-timeline/$sessionId", encode(MoveSessionBody.serializer(), body), TimelineSession.serializer())

    override suspend fun setStatus(bookId: Long, body: JsonObject): BookStatus =
        send("PATCH", "books/$bookId/status", body, BookStatus.serializer())

    override suspend fun attempts(bookId: Long, page: Int, pageSize: Int): ReadingAttemptList =
        get("books/$bookId/reading-attempts?page=$page&pageSize=${pageSize.coerceIn(1, 100)}", ReadingAttemptList.serializer())

    override suspend fun createAttempt(bookId: Long, body: JsonObject): ReadingAttempt =
        send("POST", "books/$bookId/reading-attempts", body, ReadingAttempt.serializer())

    override suspend fun updateAttempt(bookId: Long, attemptId: Long, body: JsonObject): ReadingAttempt =
        send("PATCH", "books/$bookId/reading-attempts/$attemptId", body, ReadingAttempt.serializer())

    override suspend fun deleteAttempt(bookId: Long, attemptId: Long) =
        sendNoContent("DELETE", "books/$bookId/reading-attempts/$attemptId", null)

    override suspend fun startReread(bookId: Long, resetProgress: Boolean): BookStatus =
        send("POST", "books/$bookId/reading-attempts/start-reread", buildJsonObject { put("resetProgress", resetProgress) }, BookStatus.serializer())

    override suspend fun setRating(bookId: Long, rating: Int?) =
        sendNoContent("POST", "books/bulk-set-rating", TrackingBodies.rating(bookId, rating))

    override suspend fun setPersonalNote(bookId: Long, note: String?) {
        // Answers with the whole BookDetail; the book page reloads it itself.
        sendNoContent("PATCH", "books/$bookId/personal-note", TrackingBodies.note(note))
    }

    override suspend fun activityOverview(): ActivityOverview {
        val raw = get("user-statistics/activity-overview", JsonObject.serializer())
        return json.decodeFromJsonElement(ActivityOverview.serializer(), raw).copy(raw = raw)
    }

    override suspend fun activityCalendar(year: Int): ActivityCalendar =
        get("user-statistics/activity-calendar/$year", ActivityCalendar.serializer())

    override suspend fun activityDay(day: String): ActivityDayDetail =
        get("user-statistics/activity-days/${enc(day)}", ActivityDayDetail.serializer())

    override suspend fun sessionTimeline(year: Int, week: Int): SessionTimeline =
        get("user-statistics/session-timeline?year=$year&week=$week", SessionTimeline.serializer())

    override suspend fun claimCelebration(): CelebrationClaim? =
        api.send("POST", "achievements/celebrations/claim", null) { text ->
            // Nothing to celebrate comes back as an empty body (or a JSON null).
            if (text.isBlank() || text.trim() == "null") null else json.decodeFromString(CelebrationClaim.serializer(), text)
        }

    override suspend fun acknowledgeCelebration(claimId: String) =
        sendNoContent("POST", "achievements/celebrations/${enc(claimId)}/acknowledge", null)
}
