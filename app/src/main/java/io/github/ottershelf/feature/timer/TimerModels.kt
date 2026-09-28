package io.github.ottershelf.feature.timer

import kotlinx.serialization.Serializable
import io.github.ottershelf.core.settings.ProgressUnit
import io.github.ottershelf.core.tracking.PageMath
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What the timer screens know about the book: the strip under the timer ("p. 130 / 464") and the
 * save form's starting values. Pages come from [PageMath] (the total of the user's edition, else the server's).
 */
data class TimerBook(
    val bookId: Long,
    val title: String?,
    val authors: String,
    val cover: Any?,
    /** The file a timed session is recorded against (PageMath.trackingFile); null: manual route. */
    val fileId: Long?,
    /** The total of the user's edition if set, else the server's page count; null if neither. */
    val pageTotal: Int?,
    val serverPageCount: Int?,
    /** The best known progress, 0..100. */
    val percent: Double?,
    /** The current page (a hand-set one until a later session), null without a total. */
    val page: Int?,
    /** How the user enters progress for this book (pages unless the user picked percent). */
    val unit: ProgressUnit = ProgressUnit.PAGE,
    /**
     * Where a session started now begins (PageMath.startPercent): the latest position rather than
     * the highest [percent] shown, and 0 on a reread that has none of its own yet.
     */
    val startPercent: Double? = percent,
    /** [startPercent] as a page (a hand-set page until a later session), null without a total. */
    val startPage: Int? = page,
) {
    /** Where the book stands, 0..100, as a timer started now records it. */
    val startProgress: Double?
        get() = startPage?.let { p -> pageTotal?.takeIf { it > 0 }?.let { PageMath.pageToPercent(p, it) } } ?: startPercent
}

/** The save form's starting point, from the finished (or paused) timer and the book. */
data class SaveForm(
    val activeMs: Long,
    val title: String?,
    /** The page the user started on (null when unknown). */
    val startPage: Int?,
    /** The page total shown (editable): the user's edition's total, else the server's. */
    val total: Int?,
    /** The progress the user started at, 0..100. */
    val startPercent: Double?,
    val unit: ProgressUnit,
) {
    /** Under 10 s the server drops a session: there is nothing to save. */
    val tooShort: Boolean get() = activeMs / 1000 < io.github.ottershelf.core.tracking.TrackingRepository.MIN_SESSION_SECONDS
}

/** Where the user got to, as entered in the save form. */
sealed interface ProgressEntry {
    data class Pages(val page: Int, val total: Int) : ProgressEntry
    data class Percent(val percent: Double) : ProgressEntry

    /** Left empty: the session is saved without a position. */
    data object None : ProgressEntry
}

/** Why the save form's numbers can't be saved. */
enum class EntryError { NotANumber, NeedTotal, PageTooHigh, PercentRange }

/** Reads the save form: an entry, or what's wrong with it. */
object ProgressInput {

    fun parse(unit: ProgressUnit, pageText: String, totalText: String, percentText: String): Pair<ProgressEntry?, EntryError?> {
        return when (unit) {
            ProgressUnit.PAGE -> {
                val pageRaw = pageText.trim()
                val totalRaw = totalText.trim()
                if (pageRaw.isEmpty()) return ProgressEntry.None to null
                val page = pageRaw.toIntOrNull()?.takeIf { it >= 0 } ?: return null to EntryError.NotANumber
                if (totalRaw.isEmpty()) return null to EntryError.NeedTotal
                val total = totalRaw.toIntOrNull()?.takeIf { it > 0 } ?: return null to EntryError.NotANumber
                if (page > total) return null to EntryError.PageTooHigh
                ProgressEntry.Pages(page, total) to null
            }
            ProgressUnit.PERCENT -> {
                val raw = percentText.trim().removeSuffix("%").trim().replace(',', '.')
                if (raw.isEmpty()) return ProgressEntry.None to null
                val percent = raw.toDoubleOrNull() ?: return null to EntryError.NotANumber
                if (percent < 0.0 || percent > 100.0) return null to EntryError.PercentRange
                ProgressEntry.Percent(percent) to null
            }
        }
    }

    /** A percentage as the form shows it: "43" or "43.5". */
    fun percentText(percent: Double?): String {
        if (percent == null) return ""
        val tenths = (percent * 10).roundToLong() / 10.0
        return if (tenths % 1.0 == 0.0) tenths.toLong().toString() else tenths.toString()
    }
}

/** How saving went, as the result screen says it. */
@Serializable
enum class SaveOutcome { Sent, Queued, Rejected }

/**
 * A saved session and what it did for the book: the result screen's numbers. Serializable so the
 * screen keeps it across process death.
 */
@Serializable
data class SessionSummary(
    val bookId: Long,
    val sessionId: String,
    val title: String? = null,
    val authors: String = "",
    val activeSeconds: Int,
    val outcome: SaveOutcome,
    val message: String? = null,
    val total: Int? = null,
    val startPage: Int? = null,
    val endPage: Int? = null,
    val startPercent: Double? = null,
    val endPercent: Double? = null,
    /** The book's reading speed over all its sessions (pages per hour), when the server knows it. */
    val bookPagesPerHour: Double? = null,
    /** ... and in percent per hour. */
    val bookPercentPerHour: Double? = null,
    /** Reading time today (the server's day), this session included; null when unknown. */
    val todaySeconds: Long? = null,
    val dailyGoalMinutes: Int? = null,
) {
    val minutes: Int get() = (activeSeconds / 60.0).roundToInt()

    /** Pages read this session (can be negative after going back); null without both pages. */
    val pagesRead: Int? get() = if (startPage != null && endPage != null) endPage - startPage else null

    /** Percentage points gained this session. */
    val percentGained: Double? get() = if (startPercent != null && endPercent != null) endPercent - startPercent else null

    /** This session's speed: pages per hour, from at least a minute of reading and pages gained. */
    val pagesPerHour: Double?
        get() {
            val pages = pagesRead ?: return null
            if (pages <= 0 || activeSeconds < 60) return null
            return pages / (activeSeconds / 3600.0)
        }

    val pagesLeft: Int? get() = if (total != null && endPage != null) (total - endPage).coerceAtLeast(0) else null

    val percentLeft: Double? get() = endPercent?.let { (100.0 - it).coerceAtLeast(0.0) }

    /**
     * Reading time left to the end: pages left at the book's speed (this session's when the book
     * has none yet), or the percentage left at the book's percent speed. Null without a speed.
     */
    val timeLeftSeconds: Long?
        get() {
            val left = pagesLeft
            val perHour = bookPagesPerHour?.takeIf { it > 0 } ?: pagesPerHour
            if (left != null && perHour != null && perHour > 0) return (left / perHour * 3600).roundToLong()
            val pctLeft = percentLeft
            val pctPerHour = bookPercentPerHour?.takeIf { it > 0 }
                ?: percentGained?.takeIf { it > 0 && activeSeconds >= 60 }?.let { it / (activeSeconds / 3600.0) }
            if (pctLeft != null && pctPerHour != null) return (pctLeft / pctPerHour * 3600).roundToLong()
            return null
        }

    /** Whether the daily goal is shown: one is set and today's time is known. */
    val showsGoal: Boolean get() = dailyGoalMinutes != null && dailyGoalMinutes > 0 && todaySeconds != null

    val todayMinutes: Int get() = ((todaySeconds ?: 0) / 60).toInt()
}
