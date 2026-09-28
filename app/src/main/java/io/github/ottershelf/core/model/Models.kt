package io.github.ottershelf.core.model

import kotlinx.serialization.Serializable

// Subsets of the BookOrbit v3 API types (packages/types in the server repo). Unknown fields are
// ignored on decode; the server rejects unknown fields on *requests*, so request models carry
// only what the DTOs whitelist.

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val clientKind: String = "native",
    val deviceLabel: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthUser(
    val id: Long,
    val username: String,
    val name: String? = null,
    val isDefaultPassword: Boolean = false,
    val isSuperuser: Boolean = false,
    val permissions: List<String> = emptyList(),
    val settings: UserSettings? = null,
) {
    /** Same rule as the server's PermissionService: superusers hold every permission. */
    fun can(permission: String) = isSuperuser || permission in permissions

    /** Appearance follows the account (`user-preferences/theme`) rather than staying on this device. */
    val syncsTheme: Boolean get() = settings?.syncThemePreferences == true
}

/** The subset of the server's `UserSettings` (packages/types/src/auth.ts) the app reads. */
@Serializable
data class UserSettings(
    /** Theme preferences are stored on the account (sync mode); missing or false means on each device. */
    val syncThemePreferences: Boolean? = null,
    /** Reader settings are stored on the account (`reader/defaults`, `reader/preferences/:fileId`); else per device. */
    val syncReaderPreferences: Boolean? = null,
    /** This app's own key (core.settings.AppSettings), written whole by AppSettingsRepository. */
    val bookorbitAndroid: kotlinx.serialization.json.JsonObject? = null,
    /** The web dashboard's config; `readingGoal` (books per year) is the only goal the server has. */
    val dashboardConfig: kotlinx.serialization.json.JsonObject? = null,
    /** IANA zone the server splits reading days in (UTC when missing). */
    val timezone: String? = null,
)

/** `NativeAuthResponse` from login; refresh returns the same minus `user`. */
@Serializable
data class NativeCredentials(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    val sessionId: Long? = null,
    val user: AuthUser? = null,
)

@Serializable
data class Library(
    val id: Long,
    val type: String = "books",
    val name: String,
    val icon: String? = null,
    val displayOrder: Int = 0,
    val bookCount: Int? = null,
)

@Serializable
data class SmartScope(
    val id: Long,
    val name: String,
    /** Lucide PascalCase name or `custom:<slug>`, as chosen in the web app. */
    val icon: String? = null,
    val mediaType: String = "books",
    val bookCount: Int? = null,
    val displayOrder: Int = 0,
)

@Serializable
data class BookCollection(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val mediaType: String = "books",
    val bookCount: Int? = null,
    val displayOrder: Int = 0,
)

@Serializable
data class SortSpec(val field: String, val dir: String)

@Serializable
data class Pagination(val page: Int, val size: Int)

@Serializable
data class BookQuery(
    val sort: List<SortSpec>,
    val pagination: Pagination,
    val q: String? = null,
)

@Serializable
data class ReadStatusInfo(
    val status: String? = null,
    /** "manual" when the user set it, "auto" when derived from progress. */
    val source: String? = null,
    val updatedAt: String? = null,
    /**
     * The current reading's start and finish: `YYYY-MM-DD` from `PATCH books/:id/status`, an ISO
     * timestamp (UTC midnight of that date) elsewhere. Read as a date, never shifted to local time
     * (core.tracking.PageMath.dateOf).
     */
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

@Serializable
data class BookCard(
    val id: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val seriesName: String? = null,
    /** As the server has it (`4`, `4.5`, `1.10`): a string, compared part by part. */
    val seriesIndex: String? = null,
    /** 0..100 */
    val readingProgress: Double? = null,
    val readStatus: ReadStatusInfo? = null,
    val hasCover: Boolean = false,
    val addedAt: String? = null,
    val updatedAt: String? = null,
    /** Id, format and role only (for the format badge); the primary file has role `primary`. */
    val files: List<BookFile> = emptyList(),
    /** This user's own rating, 1..5 (the card query joins `user_book_ratings`); null if unrated. */
    val rating: Int? = null,
    /**
     * The book itself on the server (`books.status`): `present`, `missing` (its files are gone from
     * disk until a rescan finds them) or `processing`; null when a route doesn't say.
     */
    val status: String? = null,
)

@Serializable
data class BooksPage(
    val items: List<BookCard>,
    val total: Int,
    val page: Int,
    val size: Int,
)

@Serializable
data class AuthorRef(val id: Long? = null, val name: String)

@Serializable
data class BookFile(
    val id: Long,
    val format: String? = null,
    val role: String? = null,
    val sizeBytes: Long? = null,
    val filename: String? = null,
    val durationSeconds: Double? = null,
)

@Serializable
data class BookDetail(
    val id: Long,
    val libraryName: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val authors: List<AuthorRef> = emptyList(),
    val seriesId: Long? = null,
    val seriesName: String? = null,
    val seriesIndex: String? = null,
    /**
     * Every series the book is in, the main one ([seriesId]) first: a series' own page shows each
     * book's number in that series (feature.bookedit's edits follow it).
     */
    val seriesMemberships: List<SeriesMembership> = emptyList(),
    val publisher: String? = null,
    val publishedYear: Int? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val coverSource: String? = null,
    val files: List<BookFile> = emptyList(),
    val readStatus: ReadStatusInfo? = null,
    val addedAt: String? = null,
    val updatedAt: String? = null,
    /** This user's own rating, 1..5 (setting it needs `library_edit_metadata`). */
    val rating: Int? = null,
    /** This user's private note on the book (the web's "personal review"). */
    val personalNote: String? = null,
    /** Metadata fields an admin locked (BookMetadataLockField); a locked `rating` can't be set. */
    val lockedFields: List<String> = emptyList(),
    /** The library's format priority (lower case, best first); empty: the web's default (core.format.BookFormats). */
    val formatPriority: List<String> = emptyList(),
)

/** A series a book is in, and its number there (BookSeriesMembership, the fields used here). */
@Serializable
data class SeriesMembership(val seriesId: Long, val seriesName: String? = null, val seriesIndex: String? = null)

/** `GET browse-counts`: the sidebar's Authors and Series badges (cached per user for 60s). */
@Serializable
data class BrowseCounts(val authors: Int = 0, val series: Int = 0)

/** An item of `GET authors` (AuthorSummary; its full bio is ignored). */
@Serializable
data class AuthorSummary(
    val id: Long,
    val name: String,
    /** Server-relative, versioned (`/api/v1/authors/:id/thumbnail?t=`); null without a portrait. */
    val imageUrl: String? = null,
    val bookCount: Int = 0,
)

@Serializable
data class AuthorsPage(val items: List<AuthorSummary>, val total: Int, val page: Int = 0, val size: Int = 0)

/** An item of `GET series` (SeriesSummary, the fields shown here). */
@Serializable
data class SeriesSummary(
    val id: Long,
    val name: String,
    val bookCount: Int = 0,
    val readCount: Int = 0,
    val readingCount: Int = 0,
    val authors: List<String> = emptyList(),
    /** Up to 9 books of the series that have covers, in series order. */
    val coverBookIds: List<Long> = emptyList(),
)

@Serializable
data class SeriesPage(val items: List<SeriesSummary>, val total: Int, val page: Int = 0, val size: Int = 0)

// --- dashboard ---------------------------------------------------------------------------------

/** `POST dashboard/widgets/batch` (DashboardWidgetBatchDto). */
@Serializable
data class WidgetBatchRequest(val widgets: List<String>)

/** In request order. A widget that failed comes back as `failed` with no data (still a 200). */
@Serializable
data class WidgetBatch(val items: List<WidgetBatchItem> = emptyList())

@Serializable
data class WidgetBatchItem(
    val type: String,
    val data: kotlinx.serialization.json.JsonElement? = null,
    val failed: Boolean = false,
)

/**
 * The `currently-reading` widget: up to 10 books this user has as Reading or Re-reading, the most
 * recently read first. Any format (audiobooks and comics too).
 */
@Serializable
data class CurrentlyReading(val books: List<CurrentlyReadingBook> = emptyList())

@Serializable
data class CurrentlyReadingBook(
    val bookId: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    /** 0..100, of the primary file only. */
    val progress: Double = 0.0,
    val hasCover: Boolean = false,
    /** The file the web opens to read: the primary one if it's readable at all (even a PDF). */
    val readFileId: Long? = null,
    /** Lower case: `epub`, `pdf`, ... */
    val readFileFormat: String? = null,
)

/** The `reading-streak` widget. Days are the server's UTC days. */
@Serializable
data class ReadingStreak(
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    /** Whether anything was read on each of the last 7 days, 6 days ago first and today last. */
    val lastSevenDays: List<Boolean> = emptyList(),
)

/** `POST dashboard/scrollers/batch` (DashboardScrollerBatchDto; unknown fields are rejected). */
@Serializable
data class ScrollerBatchRequest(val items: List<ScrollerRequest>)

@Serializable
data class ScrollerRequest(val id: String, val type: String, val limit: Int)

@Serializable
data class ScrollerBatch(val items: List<ScrollerResult> = emptyList())

@Serializable
data class ScrollerResult(val id: String, val books: List<BookCard> = emptyList(), val failed: Boolean = false)

/** `PATCH books/:id/status` (SetStatusDto; the server fills in started/finished dates). */
@Serializable
data class SetStatus(val status: String)

/**
 * Read statuses in the web app's menu order, with its labels and Lucide icons
 * (client/src/features/book/composables/useBookStatus.ts). [lucideIcon] is the Lucide PascalCase
 * name (drawn by ui.icons); the UI layer maps a status to its colour.
 */
enum class ReadStatus(val value: String, val label: String, val lucideIcon: String) {
    UNREAD("unread", "Unread", "Book"),
    WANT_TO_READ("want_to_read", "Want to read", "BookMarked"),
    READING("reading", "Reading", "BookOpen"),
    ON_HOLD("on_hold", "On hold", "Pause"),
    REREADING("rereading", "Re-reading", "RotateCcw"),
    READ("read", "Read", "BookCheck"),
    SKIMMED("skimmed", "Skimmed", "ScanLine"),
    ABANDONED("abandoned", "Abandoned", "BookX");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value }
    }
}

/** `GET books/files/:fileId/progress` (subset). */
@Serializable
data class FileProgress(
    val cfi: String? = null,
    val percentage: Double? = null,
    val textUpdatedAt: String? = null,
    val updatedAt: String? = null,
    /** Advances on every reading write, from any client (KOReader: push time). */
    val lastReadAt: String? = null,
    /** A page-based reader's position (comics, PDF), 1-based; null for EPUB positions. */
    val pageNumber: Int? = null,
)

/**
 * `POST books/files/:fileId/progress`, field for field as the web reader sends it
 * (server dto/save-progress.dto.ts; unknown fields are rejected). The Kobo/KOReader location
 * fields are passed through from foliate untouched so cross-device sync keeps working.
 */
@Serializable
data class SaveProgress(
    val cfi: String?,
    val percentage: Double,
    /**
     * A page-based reader's 1-based page (comics, PDF; sent with `cfi: null`, as the web's CBZ and PDF
     * readers do). Never set by the EPUB reader. See core.sync.PageProgress.
     */
    val pageNumber: Int? = null,
    val koboLocationSource: String? = null,
    val koboLocationType: kotlinx.serialization.json.JsonElement? = null,
    val koboLocationValue: kotlinx.serialization.json.JsonElement? = null,
    val koboContentSourceProgressPercent: Double? = null,
    val koreaderProgress: kotlinx.serialization.json.JsonElement? = null,
    val source: String = "text",
)

/** `POST books/files/:fileId/sessions` (SaveReadingSessionDto). */
@Serializable
data class ReadingSession(
    val sessionId: String,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Int,
    val progressDelta: Double?,
    /** Null to leave the read status alone (the server only infers status from sessions that have it). */
    val endProgress: Double?,
    /**
     * The client platform, one of the server's CLIENT_READING_SESSION_SOURCES (ios, watchos,
     * android); without it the server records the session as `web`. A session queued before this
     * field existed reads back with it.
     */
    val source: String = "android",
)

/**
 * Where a screen gets its books from: a book grid, a list to pick from (Authors, AllSeries), or the
 * Dashboard.
 */
sealed class BookSource(val title: String) {
    /** What's being read, the reading streak and what's new, as on the web app's home page. */
    class Dashboard(title: String) : BookSource(title)
    class All(title: String) : BookSource(title)
    class InLibrary(val id: Long, title: String) : BookSource(title)
    class InScope(val id: Long, title: String) : BookSource(title)
    class InCollection(val id: Long, title: String) : BookSource(title)
    /** Books downloaded to this device; listed without the server. */
    class Downloaded(title: String) : BookSource(title)
    /** Every author, to pick one ([ByAuthor]). */
    class Authors(title: String) : BookSource(title)
    /** Every series, to pick one ([InSeries]). */
    class AllSeries(title: String) : BookSource(title)
    class ByAuthor(val id: Long, title: String) : BookSource(title)
    /** In series order. */
    class InSeries(val id: Long, title: String) : BookSource(title)

    val key: String
        get() = when (this) {
            is Dashboard -> DASHBOARD
            is All -> "all"
            is InLibrary -> "library:$id"
            is InScope -> "scope:$id"
            is InCollection -> "collection:$id"
            is Downloaded -> DOWNLOADED
            is Authors -> AUTHORS
            is AllSeries -> SERIES
            is ByAuthor -> "author:$id"
            is InSeries -> "series:$id"
        }

    companion object {
        const val DASHBOARD = "dashboard"
        const val DOWNLOADED = "downloaded"
        const val AUTHORS = "authors"
        const val SERIES = "series"

        fun fromKey(key: String, title: String): BookSource {
            val id = key.substringAfter(':', "").toLongOrNull()
            return when {
                key == DASHBOARD -> Dashboard(title)
                key == DOWNLOADED -> Downloaded(title)
                key == AUTHORS -> Authors(title)
                key == SERIES -> AllSeries(title)
                key.startsWith("library:") && id != null -> InLibrary(id, title)
                key.startsWith("scope:") && id != null -> InScope(id, title)
                key.startsWith("collection:") && id != null -> InCollection(id, title)
                key.startsWith("author:") && id != null -> ByAuthor(id, title)
                key.startsWith("series:") && id != null -> InSeries(id, title)
                else -> All(title)
            }
        }
    }
}

/** A series position for display: `#4`, `#4.5`, `#1.10` (older saved copies may hold `4.0`). */
fun formatSeriesIndex(raw: String): String {
    val parts = raw.trim().split('.', limit = 2)
    val whole = parts[0].trimStart('0').ifEmpty { "0" }
    val fraction = parts.getOrNull(1)?.takeUnless { it.isEmpty() || it.all { c -> c == '0' } }
    return if (fraction != null) "$whole.$fraction" else whole
}
