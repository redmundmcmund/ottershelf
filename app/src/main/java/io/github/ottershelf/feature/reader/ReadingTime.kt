package io.github.ottershelf.feature.reader

import kotlin.math.roundToLong

/**
 * Reading time left, for the footer: in the chapter and in the book, in seconds (null when it
 * can't be told). [fromPace]: worked out from the user's own reading speed in this book, else foliate's
 * generic estimate.
 */
data class TimeLeft(val chapterSeconds: Long?, val bookSeconds: Long?, val fromPace: Boolean)

/**
 * The time-left maths. Pure; `ReadingTimeTest`.
 *
 * The user's speed is the book's measured pace (percent of the book per hour, from its reading sessions:
 * the book page's `trustedPace`, so both show the same). The book's time left is what remains of it
 * at that pace. The chapter's share of the book comes from foliate's size-based times (the minutes
 * left in the chapter against the whole book's), so the user's pace applies to it too. Without a pace,
 * foliate's own estimate (its fixed rate per byte, relocate `time.section` / `time.total`).
 */
object ReadingTime {

    /**
     * [fraction]: 0..1 through the book. [sectionMinutes], [totalMinutes]: foliate's minutes left in
     * the chapter and the book; [bookMinutes]: its minutes for the whole book. [percentPerHour]: the user's
     * pace, or null.
     */
    fun timeLeft(
        fraction: Double,
        sectionMinutes: Double?,
        totalMinutes: Double?,
        bookMinutes: Double?,
        percentPerHour: Double?,
    ): TimeLeft? {
        val section = sectionMinutes?.takeIf { it.isFinite() && it >= 0 }
        val total = totalMinutes?.takeIf { it.isFinite() && it >= 0 }
        val whole = bookMinutes?.takeIf { it.isFinite() && it > 0 }
        val pace = percentPerHour?.takeIf { it.isFinite() && it > 0 }
        if (pace != null) {
            val left = (1.0 - fraction.coerceIn(0.0, 1.0)) * 100.0
            val book = seconds(left / pace)
            val chapter = if (section != null && whole != null) {
                seconds((section / whole).coerceIn(0.0, 1.0) * 100.0 / pace).coerceAtMost(book)
            } else null
            return TimeLeft(chapter, book, fromPace = true)
        }
        if (section == null && total == null) return null
        return TimeLeft(section?.let { seconds(it / 60.0) }, total?.let { seconds(it / 60.0) }, fromPace = false)
    }

    private fun seconds(hours: Double): Long = (hours * 3600.0).roundToLong().coerceAtLeast(0)
}
