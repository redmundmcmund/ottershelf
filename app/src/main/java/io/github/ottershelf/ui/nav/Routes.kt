package io.github.ottershelf.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import io.github.ottershelf.core.model.BookSource

/**
 * Every screen of the app, as a Navigation 3 key. Routes are saved (as JSON) across process death,
 * so everything in them must be @Serializable and small: ids and short strings, never loaded data.
 *
 * The layout is the Nexus app's: a drawer of lists; the picked list ([Chrome.Root]) fills the
 * content area under the shell's toolbar, and a pick replaces it; everything else ([Chrome.Detail],
 * [Chrome.Immersive]) is a full screen pushed on top.
 *
 * Adding or changing a route is a shared change (this file and NavGraph.kt): see ARCHITECTURE.md.
 */
@Serializable
sealed interface Route : NavKey {

    /** How the shell frames this route. */
    val chrome: Chrome get() = Chrome.Detail

    /** Server, username and password. Shown instead of everything else while signed out. */
    @Serializable
    data object Login : Route {
        override val chrome get() = Chrome.Immersive
    }

    /** The Dashboard (Currently Reading, Reading Streak, Recently Added): the start screen. */
    @Serializable
    data object Home : Route {
        override val chrome get() = Chrome.Root
    }

    /**
     * One source's books as a grid: [sourceKey] as `BookSource.key` (`all`, `library:3`,
     * `scope:7`, `collection:2`, `author:5`, `series:9`), optionally searched ([query]).
     *
     * Picked from the drawer (All books, a library, scope or collection) it is a root list under
     * the shell's toolbar. An author's or a series' books (`author:` / `series:`) are pushed on
     * top with their own top bar, as the Nexus BooksActivity was.
     */
    @Serializable
    data class BookList(val sourceKey: String, val title: String, val query: String? = null) : Route {
        override val chrome
            get() = if (sourceKey.startsWith("author:") || sourceKey.startsWith("series:")) Chrome.Detail else Chrome.Root
    }

    /** Every author, to pick one (then [BookList] with `author:<id>`), optionally searched. */
    @Serializable
    data class Authors(val query: String? = null) : Route {
        override val chrome get() = Chrome.Root
    }

    /** Every series, to pick one (then [BookList] with `series:<id>`), optionally searched. */
    @Serializable
    data class Series(val query: String? = null) : Route {
        override val chrome get() = Chrome.Root
    }

    /** Books on this device (the drawer's Downloaded), optionally searched. */
    @Serializable
    data class Downloads(val query: String? = null) : Route {
        override val chrome get() = Chrome.Root
    }

    @Serializable
    data class BookDetail(val bookId: Long) : Route

    /** Editing a book's title, authors, series and cover (feature.bookedit), from its page's pencil. */
    @Serializable
    data class BookEdit(val bookId: Long) : Route

    /**
     * The foliate reader (EPUB, KEPUB, MOBI, AZW3, AZW, FB2), full screen. [title] shows while the
     * book loads. [cfi]: open at this highlight instead of the reading position (nothing is saved
     * until a page is turned). [format]: the file's format, lower case (null: EPUB). Build it with
     * [ReaderRouter], never directly.
     */
    @Serializable
    data class Reader(
        val bookId: Long,
        val fileId: Long,
        val title: String,
        val cfi: String? = null,
        val format: String? = null,
    ) : Route {
        override val chrome get() = Chrome.Immersive
    }

    /** The comics reader (CBZ, CBR, CB7), full screen (feature.comics). Build it with [ReaderRouter]. */
    @Serializable
    data class Comics(val bookId: Long, val fileId: Long, val title: String) : Route {
        override val chrome get() = Chrome.Immersive
    }

    /**
     * The PDF reader, full screen (feature.pdf). [page]: open at this page (a highlight's) instead of
     * the reading position (nothing is saved until a page is turned). Build it with [ReaderRouter].
     */
    @Serializable
    data class Pdf(val bookId: Long, val fileId: Long, val title: String, val page: Int? = null) : Route {
        override val chrome get() = Chrome.Immersive
    }

    /** Book requests; only offered with the `book_request_access` permission. */
    @Serializable
    data object Requests : Route

    /**
     * Book requests opened for one book (a scanned ISBN not in the library, feature.scan): the
     * search prefilled with [title] and [author] and run for [isbn]. The user requests it there.
     */
    @Serializable
    data class RequestBook(val isbn: String, val title: String? = null, val author: String? = null) : Route

    /**
     * The ISBN barcode scanner (feature.scan). [forTimer]: picking the book for the reading timer,
     * so a match opens its Timer rather than its page. [pick]: picking a book for the screen that
     * opened it (the Calendar's "Add a book"): a match is handed back (feature.scan.ScanPicks) and
     * the scanner closes.
     */
    @Serializable
    data class Scan(val forTimer: Boolean = false, val pick: Boolean = false) : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object Appearance : Route

    /** About Ottershelf: the version, BookOrbit's required notices, the source and the licences. */
    @Serializable
    data object About : Route

    // --- reading tracker (ARCHITECTURE.md, "Tracking") ---------------------------------------

    /** The reading calendar: the drawer's Tracking section. */
    @Serializable
    data object Calendar : Route {
        override val chrome get() = Chrome.Root
    }

    /** Every reading of every book with its dates (feature.history): the drawer's Tracking section, beside the Calendar. */
    @Serializable
    data object History : Route {
        override val chrome get() = Chrome.Root
    }

    /** One day's reading across all books; [date] is `YYYY-MM-DD` (the server's local day). */
    @Serializable
    data class Day(val date: String) : Route

    /** The yearly book goal and the daily minutes goal. */
    @Serializable
    data object ReadingGoals : Route

    /** The reading timer for [bookId] (also opened from the timer's notification). */
    @Serializable
    data class Timer(val bookId: Long) : Route

    /**
     * After Stop: save the session of the finished timer [sessionId] (TimerEngine's
     * `state.finished`) with the page reached. Opened from the timer screen or the notification.
     */
    @Serializable
    data class TimerResult(val bookId: Long, val sessionId: String) : Route

    /** Reading and library statistics: the drawer's Tracking section. */
    @Serializable
    data object Statistics : Route {
        override val chrome get() = Chrome.Root
    }

    /** Achievements (badges, tiers, progress): the drawer's Tracking section. */
    @Serializable
    data object Achievements : Route {
        override val chrome get() = Chrome.Root
    }

    /** The year in review for [year] (null: this year, or last year during January). */
    @Serializable
    data class Rewind(val year: Int? = null) : Route

    // --- highlights and notes (ARCHITECTURE.md, "Highlights and notes") ------------------------

    /** Every highlight and note across the library (the drawer's Tracking > Notes), optionally searched. */
    @Serializable
    data class Notes(val query: String? = null) : Route {
        override val chrome get() = Chrome.Root
    }

    /** One book's highlights and notes by chapter; [title] shows while it loads. */
    @Serializable
    data class BookHighlights(val bookId: Long, val title: String) : Route

    /** One note at a time to memorise: [bookId]'s, the liked ones ([liked]), or the whole library. */
    @Serializable
    data class Memorize(val bookId: Long? = null, val liked: Boolean = false) : Route

    /**
     * A typed or photographed quote, saved as a BookOrbit annotation (feature.quotes). [bookId]
     * null: the user picks the book; [bookFixed]: opened from that book's page, so it can't change;
     * [title] shows while the book loads; [scan] opens the camera straight away.
     */
    @Serializable
    data class AddQuote(
        val bookId: Long? = null,
        val title: String? = null,
        val bookFixed: Boolean = false,
        val scan: Boolean = false,
    ) : Route
}

/** The drawer key of [Route.Calendar] (the Tracking section). */
const val CALENDAR_KEY = "calendar"

/** The drawer key of [Route.History] (the Tracking section). */
const val HISTORY_KEY = "history"

/** The drawer key of [Route.Statistics] (the Tracking section). */
const val STATISTICS_KEY = "statistics"

/** The drawer key of [Route.Achievements] (the Tracking section). */
const val ACHIEVEMENTS_KEY = "achievements"

/** The drawer key of [Route.Notes] (the Tracking section). */
const val NOTES_KEY = "notes"

/** What the shell draws around a route's content. */
enum class Chrome {
    /**
     * A list picked from the drawer: the shell draws the toolbar (drawer toggle, title, the
     * screen's [TopBarActions], search) and the screen gets `contentPadding`. A new pick replaces
     * it rather than stacking on it.
     */
    Root,

    /** A pushed full screen: it draws its own top bar with Back, usually `DetailScaffold`. */
    Detail,

    /** Login and the reader: no shell at all, the screen handles every inset itself. */
    Immersive,
}

/** The drawer entry a root route belongs to (`BookSource.key`), or null for pushed screens. */
val Route.sourceKey: String?
    get() = when (this) {
        Route.Home -> BookSource.DASHBOARD
        is Route.Downloads -> BookSource.DOWNLOADED
        is Route.Authors -> BookSource.AUTHORS
        is Route.Series -> BookSource.SERIES
        is Route.BookList -> sourceKey
        Route.Calendar -> CALENDAR_KEY
        Route.History -> HISTORY_KEY
        Route.Statistics -> STATISTICS_KEY
        Route.Achievements -> ACHIEVEMENTS_KEY
        is Route.Notes -> NOTES_KEY
        else -> null
    }

/** A book open in one of the readers (the celebration toast and similar wait until it closes). */
val Route.isReader: Boolean
    get() = this is Route.Reader || this is Route.Comics || this is Route.Pdf

/** The search a root route shows, if any. */
val Route.query: String?
    get() = when (this) {
        is Route.Downloads -> query
        is Route.Authors -> query
        is Route.Series -> query
        is Route.BookList -> query
        is Route.Notes -> query
        else -> null
    }
