package io.github.ottershelf.feature.bookmory

import io.github.ottershelf.feature.scan.Isbn
import java.time.DateTimeException
import java.time.LocalDate
import kotlin.math.roundToInt

/** What Bookmory's "Read status" column says. */
enum class BookmoryStatus { READ, READING, TO_READ, GAVE_UP, UNKNOWN }

/** One "Reading log N" of a row: its read period and star rating. Either date may be missing. */
data class BookmoryLog(val startedOn: LocalDate?, val endedOn: LocalDate?, val rating: Int?)

/**
 * One book of a Bookmory CSV export. [line] is the row's number in the file (the header is 1), for
 * reports. [title] is as exported: it may end in "..." (Bookmory cuts long titles) and carry U+FFFD
 * where an apostrophe was.
 */
data class BookmoryRow(
    val line: Int,
    val title: String,
    val authors: List<String>,
    val publisher: String?,
    /** `YYYY-MM-DD`, `YYYY-MM` or `YYYY`, as far as the export's "Publication date" goes. */
    val publicationDate: String?,
    val language: String?,
    /** An ISBN-13 when the column holds a valid ISBN-10 or -13, else its digits as given (or null). */
    val isbn: String?,
    /** "E-book", "Audio book", "Paperback", ... as exported. */
    val bookType: String?,
    val status: BookmoryStatus,
    val wishlist: Boolean,
    /** Oldest first. */
    val logs: List<BookmoryLog>,
) {
    /** The row's rating: the latest log's that has one (1..5), else null. */
    val rating: Int? get() = logs.lastOrNull { it.rating != null }?.rating
}

/** A row that couldn't be read, with why (shown in the preview, never imported). */
data class BookmoryProblem(val line: Int, val title: String?, val reason: Reason) {
    enum class Reason { WRONG_COLUMN_COUNT, NO_TITLE, BAD_DATE }
}

/** Whether the read periods are month-first (Bookmory's US format) or day-first. */
enum class DateOrder { MONTH_FIRST, DAY_FIRST }

data class BookmoryFile(
    val rows: List<BookmoryRow>,
    val problems: List<BookmoryProblem>,
    val dateOrder: DateOrder,
)

/** The header doesn't look like a Bookmory export (no Title column). */
class NotABookmoryExport : Exception("Not a Bookmory export")

/**
 * Reads a Bookmory CSV export (UTF-8, optionally with a BOM; RFC 4180 quoting). Columns are found by
 * their header names, so a reordered or extended export still reads; a row that can't be read is
 * reported in [BookmoryFile.problems] and the rest are read. Pure: no Android, no I/O.
 */
object BookmoryCsv {

    const val TITLE = "Title"
    const val AUTHORS = "Authors"
    const val PUBLISHER = "Publisher"
    const val PUBLICATION_DATE = "Publication date"
    const val LANGUAGE = "Language"
    const val ISBN = "ISBN"
    const val BOOK_TYPE = "Book type"
    const val WISHLIST = "Wishlist"
    const val READ_STATUS = "Read status"
    private val LOG_PERIOD = Regex("""Reading log (\d+) - Read period""")
    private fun ratingColumn(n: Int) = "Reading log $n - Star ratings"

    fun parse(text: String): BookmoryFile {
        val records = records(text.removePrefix("﻿"))
        val header = records.firstOrNull()?.second?.map { it.trim().removePrefix("﻿") } ?: throw NotABookmoryExport()
        val index = header.withIndex().associate { (i, name) -> name to i }
        if (TITLE !in index) throw NotABookmoryExport()
        val logNumbers = header.mapNotNull { LOG_PERIOD.matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted()

        val body = records.drop(1).filterNot { (_, fields) -> fields.all { it.isBlank() } }
        val order = dateOrderOf(body.flatMap { (_, fields) -> logNumbers.mapNotNull { fields.getOrNull(index.getValue("Reading log $it - Read period")) } })

        val rows = mutableListOf<BookmoryRow>()
        val problems = mutableListOf<BookmoryProblem>()
        for ((line, fields) in body) {
            fun col(name: String): String? = index[name]?.let { fields.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            val title = col(TITLE)
            if (fields.size != header.size) {
                problems += BookmoryProblem(line, title, BookmoryProblem.Reason.WRONG_COLUMN_COUNT)
                continue
            }
            if (title == null) {
                problems += BookmoryProblem(line, null, BookmoryProblem.Reason.NO_TITLE)
                continue
            }
            val logs = try {
                logNumbers.mapNotNull { n ->
                    val (start, end) = period(col("Reading log $n - Read period"), order) ?: return@mapNotNull null
                    BookmoryLog(start, end, rating(col(ratingColumn(n))))
                }
            } catch (_: BadDate) {
                problems += BookmoryProblem(line, title, BookmoryProblem.Reason.BAD_DATE)
                continue
            }
            rows += BookmoryRow(
                line = line,
                title = title,
                authors = col(AUTHORS)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                publisher = col(PUBLISHER),
                publicationDate = col(PUBLICATION_DATE)?.let(::publicationDate),
                language = col(LANGUAGE),
                isbn = col(ISBN)?.let(::isbn),
                bookType = col(BOOK_TYPE),
                status = status(col(READ_STATUS)),
                wishlist = col(WISHLIST).equals("Yes", ignoreCase = true),
                logs = logs.sortedWith(compareBy(nullsLast()) { it.startedOn ?: it.endedOn }),
            )
        }
        return BookmoryFile(rows, problems, order)
    }

    /**
     * Day-first only when some read period's first part can't be a month (over 12); Bookmory writes
     * M/D/YYYY, so that is the default.
     */
    internal fun dateOrderOf(periods: List<String>): DateOrder {
        val firsts = periods.flatMap { it.split('~') }.mapNotNull { DATE.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
        return if (firsts.any { it > 12 }) DateOrder.DAY_FIRST else DateOrder.MONTH_FIRST
    }

    private val DATE = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""")

    private class BadDate : Exception()

    /** "M/D/YYYY ~ M/D/YYYY", "M/D/YYYY ~ " (still reading) or empty (null). */
    internal fun period(text: String?, order: DateOrder): Pair<LocalDate?, LocalDate?>? {
        if (text.isNullOrBlank()) return null
        val parts = text.split('~')
        if (parts.size > 2) throw BadDate()
        val start = date(parts[0], order)
        val end = parts.getOrNull(1)?.let { date(it, order) }
        if (start == null && end == null) return null
        if (start != null && end != null && end < start) throw BadDate()
        return start to end
    }

    private fun date(text: String, order: DateOrder): LocalDate? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val m = DATE.matchEntire(t) ?: throw BadDate()
        val (a, b, year) = m.destructured
        val (month, day) = if (order == DateOrder.MONTH_FIRST) a.toInt() to b.toInt() else b.toInt() to a.toInt()
        return try {
            LocalDate.of(year.toInt(), month, day)
        } catch (_: DateTimeException) {
            throw BadDate()
        }
    }

    /** "4.0" -> 4; "0.0", blank or unreadable -> null; rounded and kept within 1..5. */
    internal fun rating(text: String?): Int? {
        val value = text?.trim()?.toDoubleOrNull() ?: return null
        if (value.isNaN() || value <= 0.0) return null
        return value.roundToInt().coerceIn(1, 5)
    }

    internal fun status(text: String?): BookmoryStatus {
        val letters = text.orEmpty().lowercase().filter { it in 'a'..'z' }
        return when {
            letters.isEmpty() -> BookmoryStatus.UNKNOWN
            letters.contains("readitall") -> BookmoryStatus.READ
            letters == "reading" -> BookmoryStatus.READING
            letters == "toread" -> BookmoryStatus.TO_READ
            letters == "gaveup" -> BookmoryStatus.GAVE_UP
            else -> BookmoryStatus.UNKNOWN
        }
    }

    /** A valid ISBN-10 or -13 as its ISBN-13; other digits as they are; nothing usable -> null. */
    internal fun isbn(text: String): String? {
        val clean = text.uppercase().filter { it.isDigit() || it == 'X' }
        if (clean.isEmpty()) return null
        return (Isbn.of13(clean) ?: Isbn.of10(clean))?.isbn13 ?: clean.filter(Char::isDigit).takeIf { it.length >= 9 }
    }

    /** "2016/02/28" -> "2016-02-28"; "2016/02" -> "2016-02"; "2016" -> "2016"; else null. */
    internal fun publicationDate(text: String): String? {
        val m = Regex("""(\d{4})(?:[/.-](\d{1,2})(?:[/.-](\d{1,2}))?)?""").matchEntire(text.trim()) ?: return null
        val (y, mo, d) = m.destructured
        if (mo.isEmpty()) return y
        val month = mo.toInt().takeIf { it in 1..12 } ?: return y
        if (d.isEmpty()) return "%s-%02d".format(y, month)
        return try {
            LocalDate.of(y.toInt(), month, d.toInt()).toString()
        } catch (_: DateTimeException) {
            "%s-%02d".format(y, month)
        }
    }

    /**
     * The file's records as (line number, fields): commas between fields, double quotes around a
     * field that holds commas, quotes ("" inside) or line breaks. CRLF, LF and CR all end a record.
     * An unterminated quote runs to the end of the file (one malformed record, not an error).
     */
    internal fun records(text: String): List<Pair<Int, List<String>>> {
        val out = mutableListOf<Pair<Int, List<String>>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var line = 1
        var recordLine = 1
        var i = 0
        var any = false
        fun endRecord() {
            fields.add(field.toString())
            field.setLength(0)
            out += recordLine to fields
            fields = mutableListOf()
            any = false
        }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> {
                        if (c == '\n') line++
                        field.append(c)
                    }
                }
            } else {
                when (c) {
                    '"' -> { quoted = true; any = true }
                    ',' -> { fields.add(field.toString()); field.setLength(0); any = true }
                    '\r', '\n' -> {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        endRecord()
                        line++
                        recordLine = line
                    }
                    else -> { field.append(c); any = true }
                }
            }
            i++
        }
        if (any || field.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return out
    }
}
