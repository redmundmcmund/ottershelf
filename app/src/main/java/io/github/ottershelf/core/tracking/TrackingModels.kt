package io.github.ottershelf.core.tracking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Reading-tracking types, field for field from the BookOrbit source (packages/types/src:
// reading-session.ts, book.ts, activity.ts, user-statistics.ts, achievement.ts). Responses are
// subsets (unknown fields are ignored); request bodies carry exactly what the server DTOs
// whitelist, because the server rejects unknown fields (BookOrbit's server/src/modules/**/dto define
// each route's body).

// --- sessions ----------------------------------------------------------------------------------

/** `BookReadingSession`: one row of `GET books/:bookId/sessions`, and `POST books/:bookId/sessions`' answer. */
@Serializable
data class BookSession(
    /** The server's id: what delete and move take. */
    val id: Long,
    /** Null for a manual session on a book with no file (or several files and no format). */
    val bookFileId: Long? = null,
    val startedAt: String,
    val endedAt: String,
    /** Active reading time; paused time is not in it. */
    val durationSeconds: Int = 0,
    /** Percentage points gained (negative after going back); null when unknown. */
    val progressDelta: Double? = null,
    /** 0..100, where the session ended; null when unknown. */
    val endProgress: Double? = null,
    val format: String? = null,
    /** web, ios, watchos, android, koreader, manual or kobo. */
    val source: String? = null,
    val attemptId: Long? = null,
)

@Serializable
data class DayMinutes(val day: String, val totalMinutes: Double = 0.0)

@Serializable
data class DayProgress(val day: String, val endProgress: Double = 0.0)

@Serializable
data class SourceSlice(val bucket: String, val totalSeconds: Long = 0, val totalSessions: Int = 0)

/** `BookReadingSessionStats`: over the filtered sessions, except [latestEndProgress]. */
@Serializable
data class BookSessionStats(
    val totalSessions: Int = 0,
    val totalSeconds: Long = 0,
    val avgDurationSeconds: Double = 0.0,
    val firstSessionAt: String? = null,
    val lastSessionAt: String? = null,
    val dailySummary: List<DayMinutes> = emptyList(),
    /** Sum of the positive progress deltas (percentage points)... */
    val paceProgressDelta: Double = 0.0,
    /** ...and the reading time of those same sessions: the pace is their ratio (PageMath.pace). */
    val paceDurationSeconds: Long = 0,
    val progressSummary: List<DayProgress> = emptyList(),
    /** The latest session's endProgress, ignoring the filters: a paper book's only progress. */
    val latestEndProgress: Double? = null,
    val bySource: List<SourceSlice> = emptyList(),
    val longestSessionSeconds: Long = 0,
    val longestSessionAt: String? = null,
    val backtrackCount: Int = 0,
)

/** `GET books/:bookId/sessions` (BookReadingSessionListResponse). */
@Serializable
data class BookSessionList(
    val items: List<BookSession> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 25,
    val stats: BookSessionStats = BookSessionStats(),
)

/**
 * `POST books/files/:fileId/sessions` (SaveReadingSessionDto) as this app sends it: a timed
 * session with a phone-made UUID, so resending is harmless (the server keeps the longest duration
 * for a sessionId). Sessions under 10 s are dropped by the server without an error.
 */
@Serializable
data class TimedSessionBody(
    val sessionId: String,
    val startedAt: String,
    val endedAt: String,
    /** Active seconds (the server caps it at the wall-clock span). */
    val durationSeconds: Int,
    val progressDelta: Double? = null,
    /** 0..100; null leaves the read status alone (the server infers status only from sessions that have it). */
    val endProgress: Double? = null,
    /** read, tts or listen. */
    val sessionType: String = "read",
    val source: String = "android",
)

/**
 * `POST books/:bookId/sessions` (CreateManualReadingSessionDto): whole minutes, 1..1440, not in
 * the future. The server makes its own sessionId, so a resend makes a duplicate.
 */
@Serializable
data class ManualSessionBody(
    val startedAt: String,
    val durationMinutes: Int,
    val endProgress: Double? = null,
    /** Only for a book with several file formats (at most 12 characters). */
    val format: String? = null,
)

/** `PATCH user-statistics/session-timeline/:sessionId`: same duration, new place in time. */
@Serializable
data class MoveSessionBody(val startedAt: String, val endedAt: String)

// --- status and attempts -----------------------------------------------------------------------

/** `UserBookStatus`, as `PATCH books/:id/status` answers (dates as `YYYY-MM-DD`). */
@Serializable
data class BookStatus(
    val status: String,
    val source: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val updatedAt: String? = null,
)

/** `ReadingAttempt`: one reading of a book. Only the list gives real session totals. */
@Serializable
data class ReadingAttempt(
    val id: Long,
    val bookId: Long,
    /** `YYYY-MM-DD` */
    val startedOn: String? = null,
    val endedOn: String? = null,
    /** completed, skimmed, abandoned, or null while open. */
    val outcome: String? = null,
    /** manual, bookorbit, kobo, koreader, hardcover or migration. */
    val origin: String? = null,
    val totalSessions: Int = 0,
    val totalSeconds: Long = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    val isOpen: Boolean get() = outcome == null
}

@Serializable
data class ReadingAttemptList(
    val items: List<ReadingAttempt> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 25,
    val total: Int = 0,
)

/** What an attempt's outcome can be (READING_ATTEMPT_OUTCOMES). */
object AttemptOutcome {
    const val COMPLETED = "completed"
    const val SKIMMED = "skimmed"
    const val ABANDONED = "abandoned"
}

/**
 * A field of a PATCH body that may be left alone ([Keep]), cleared ([Clear], sent as JSON null)
 * or set ([Set]). The status and attempt routes treat a missing field and a null differently.
 */
sealed interface Patch<out T> {
    data object Keep : Patch<Nothing>
    data object Clear : Patch<Nothing>
    data class Set<T>(val value: T) : Patch<T>
}

// --- user statistics ---------------------------------------------------------------------------

/** `ActivityDayPoint`: one local day (the server's midnight split, in users.settings.timezone). */
@Serializable
data class ActivityDay(
    /** `YYYY-MM-DD` */
    val day: String,
    val totalSeconds: Long = 0,
    val readingSeconds: Long = 0,
    val listeningSeconds: Long = 0,
    val sessionsCount: Int = 0,
    val bySource: Map<String, Long> = emptyMap(),
)

/** `GET user-statistics/activity-calendar/:year`: every day of the year, zero-filled. */
@Serializable
data class ActivityCalendar(
    val year: Int,
    val availableYears: List<Int> = emptyList(),
    val days: List<ActivityDay> = emptyList(),
)

@Serializable
data class ActivityDuration(val totalSeconds: Long = 0, val readingSeconds: Long = 0, val listeningSeconds: Long = 0)

@Serializable
data class ActivitySnapshot(
    val today: ActivityDuration = ActivityDuration(),
    val lastSevenDays: ActivityDuration = ActivityDuration(),
    val previousSevenDays: ActivityDuration = ActivityDuration(),
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val completedBooksYtd: Int = 0,
)

@Serializable
data class GoalPoint(val month: Int, val actualCumulative: Double = 0.0, val targetCumulative: Double? = null)

@Serializable
data class ActivityGoal(
    val year: Int,
    /** dashboardConfig.readingGoal (books per year), or null when none is set. */
    val goalBooks: Int? = null,
    val completedBooks: Int = 0,
    val projectedBooks: Double = 0.0,
    /** ahead, on_pace or behind (at +/- half a book); null without a goal. */
    val status: String? = null,
    val points: List<GoalPoint> = emptyList(),
)

/**
 * `GET user-statistics/activity-overview` (the parts a tracker shows; the rest of
 * ActivityOverviewResponse is in [raw] for anyone who needs more).
 */
@Serializable
data class ActivityOverview(
    val generatedAt: String? = null,
    val timezone: String? = null,
    val achievementsEnabled: Boolean = false,
    val snapshot: ActivitySnapshot = ActivitySnapshot(),
    val calendar: ActivityCalendar? = null,
    val goal: ActivityGoal? = null,
    @kotlinx.serialization.Transient val raw: JsonObject? = null,
)

/** A session on one day of `GET user-statistics/activity-days/:day`. */
@Serializable
data class DaySession(
    val id: Long,
    val bookId: Long,
    val bookFileId: Long? = null,
    val bookTitle: String? = null,
    val hasCover: Boolean = false,
    val coverVersion: String? = null,
    val format: String? = null,
    /** reading or listening */
    val mediaBucket: String? = null,
    val sourceBucket: String? = null,
    val startedAt: String,
    val endedAt: String,
    /** The part of the session on this day (sessions over midnight are split by time). */
    val durationOnDaySeconds: Long = 0,
    val progressDelta: Double? = null,
)

/** `GET user-statistics/activity-days/:day` (not cached by the server). */
@Serializable
data class ActivityDayDetail(
    val day: String,
    val timezone: String? = null,
    val totals: ActivityDay? = null,
    val sessions: List<DaySession> = emptyList(),
)

@Serializable
data class TimelineSession(
    /** The server's session id (what move and delete take). */
    val sessionId: Long,
    val bookId: Long,
    val bookTitle: String? = null,
    val bookFormat: String? = null,
    val bookSource: String? = null,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Int = 0,
)

/** `GET user-statistics/session-timeline?year&week`: an ISO week (UTC), at most 3000 sessions. */
@Serializable
data class SessionTimeline(
    val year: Int,
    val week: Int,
    val weekStart: String? = null,
    val weekEnd: String? = null,
    val items: List<TimelineSession> = emptyList(),
)

// --- achievements ------------------------------------------------------------------------------

@Serializable
data class AchievementItem(
    val key: String,
    val category: String? = null,
    val name: String,
    val description: String? = null,
    /** A Lucide PascalCase name. */
    val iconName: String? = null,
    /** common, rare, epic or legendary. */
    val rarity: String? = null,
    val earned: Boolean = false,
    val awardedAt: String? = null,
    val contextBookId: Long? = null,
    val contextBookTitle: String? = null,
)

/** `POST achievements/celebrations/claim`: an earned achievement not yet celebrated, held for [expiresAt]. */
@Serializable
data class CelebrationClaim(val claimId: String, val expiresAt: String? = null, val achievement: AchievementItem)
