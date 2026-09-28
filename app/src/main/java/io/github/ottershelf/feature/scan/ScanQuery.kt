package io.github.ottershelf.feature.scan

import kotlinx.serialization.Serializable
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.WorkKey

/*
 * Finding the library's copy of a scanned book (`POST books/query`, packages/types query.ts; the
 * server validates the body with its bookQuerySchema and rejects fields it doesn't know).
 *
 * The server has an `isbn` rule field (FIELD_OPERATORS: isEmpty, isNotEmpty, eq). `eq` matches
 * `book_metadata.isbn13 = value OR book_metadata.isbn10 = value` exactly (book-query-builder
 * isbnRuleToSql): no normalising, so the value must be spelled as stored. Stored ISBNs are digits
 * only (the parsers strip separators, and the columns are varchar(13) / varchar(10)), but a book
 * carries only the form its file had: the OPF parser keeps a lone ISBN-10 as isbn10 without
 * converting it, and the MOBI parser puts whatever EXTH 104 holds into isbn13. So one rule per
 * form, ORed: the ISBN-13, the ISBN-10 (978 codes), and the ISBN-10 with a lower-case x (the OPF
 * parser keeps the case). Each is checked against both columns.
 *
 * Nothing matched: the edition in the user's hands may not be the one in the library (a paperback's ISBN
 * is not the e-book's). The metadata providers name the book (`metadata-fetch/stream?isbn=`), and
 * the library's quick search (`q`: title, author, series) by that title finds other editions,
 * kept when the title and an author agree ([sameWork]), as the server's own request dedupe does.
 */

@Serializable
data class ScanRule(
    val type: String = "rule",
    val field: String,
    val operator: String,
    val value: String,
)

@Serializable
data class ScanRuleGroup(val type: String = "group", val join: String, val rules: List<ScanRule>)

@Serializable
data class ScanSort(val field: String, val dir: String)

@Serializable
data class ScanPage(val page: Int, val size: Int)

/** Exactly the bookQuerySchema fields sent; null ones are left out (`ApiJson`). */
@Serializable
data class ScanQueryBody(
    val sort: List<ScanSort>,
    val pagination: ScanPage,
    val filter: ScanRuleGroup? = null,
    val q: String? = null,
)

/** More than any one ISBN has copies (formats, editions, libraries). */
internal const val MATCH_PAGE = 20

/** The server's limit on `q`. */
private const val MAX_Q = 200

/** Every spelling of [isbn] a library book's metadata may hold. */
internal fun storedForms(isbn: Isbn): List<String> = buildList {
    add(isbn.isbn13)
    isbn.isbn10?.let { ten ->
        add(ten)
        if (ten.endsWith('X')) add(ten.dropLast(1) + 'x')
    }
}

/** Library books with [isbn] in either form: `isbn eq` per spelling, ORed. */
internal fun isbnQuery(isbn: Isbn): ScanQueryBody = ScanQueryBody(
    sort = listOf(ScanSort("title", "asc")),
    pagination = ScanPage(0, MATCH_PAGE),
    filter = ScanRuleGroup(join = "OR", rules = storedForms(isbn).map { ScanRule(field = "isbn", operator = "eq", value = it) }),
)

/** The library's quick search for [title]'s main part (before a subtitle's colon), best first. */
internal fun titleQuery(title: String): ScanQueryBody = ScanQueryBody(
    sort = listOf(ScanSort("relevance", "desc")),
    pagination = ScanPage(0, MATCH_PAGE),
    q = mainTitle(title).take(MAX_Q),
)

internal fun mainTitle(title: String): String = title.substringBefore(':').trim().ifEmpty { title.trim() }

// --- the server's answer ------------------------------------------------------------------------

/** A `books/query` item: the BookCard fields the scanner shows (packages/types book.ts). */
@Serializable
data class ScanCard(
    val id: Long,
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val seriesName: String? = null,
    val seriesIndex: String? = null,
    val publishedYear: Int? = null,
    val isbn13: String? = null,
    val readingProgress: Double? = null,
    val readStatus: ReadStatusInfo? = null,
    val hasCover: Boolean = false,
    val addedAt: String? = null,
    val updatedAt: String? = null,
    val files: List<BookFile> = emptyList(),
) {
    /** For the book page's preview (title and thumbnail at once). */
    fun toBookCard() = BookCard(
        id = id, title = title, authors = authors, seriesName = seriesName, seriesIndex = seriesIndex,
        readingProgress = readingProgress, readStatus = readStatus, hasCover = hasCover,
        addedAt = addedAt, updatedAt = updatedAt, files = files,
    )

    /** Its files' formats, primary first, each once. */
    val formats: List<String>
        get() = files.sortedByDescending { it.role == "primary" }.mapNotNull { it.format?.lowercase() }.distinct()
}

@Serializable
data class ScanBooksPage(val items: List<ScanCard> = emptyList(), val total: Int = 0)

// --- what the providers know ---------------------------------------------------------------------

/**
 * The candidate that best describes [isbn]: one carrying this very ISBN first (a provider can
 * answer with another edition's), then the most complete (cover, authors, year), then the first
 * to arrive. Only candidates with a title count.
 */
internal fun bestCandidate(candidates: List<MetadataCandidate>, isbn: Isbn): MetadataCandidate? =
    candidates.withIndex()
        .filter { it.value.shownTitle != null }
        .sortedWith(
            compareByDescending<IndexedValue<MetadataCandidate>> { carries(it.value, isbn) }
                .thenByDescending { completeness(it.value) }
                .thenBy { it.index },
        )
        .firstOrNull()?.value

private fun carries(c: MetadataCandidate, isbn: Isbn): Boolean {
    fun norm(v: String?) = v?.filter { it.isDigit() || it == 'X' || it == 'x' }?.uppercase()
    return norm(c.isbn13) == isbn.isbn13 || (isbn.isbn10 != null && norm(c.isbn10) == isbn.isbn10)
}

private fun completeness(c: MetadataCandidate): Int =
    listOf(c.coverUrl, c.authors?.firstOrNull(), c.publishedYear).count { it != null }

/**
 * Whether [card] is (another edition of) the book a provider called [title] by [authors]: the
 * same title, or the same main title (the part before a colon) on either side, compared as the
 * server's request dedupe compares works (lower case, no accents, letters and digits only); and an
 * author in common by name or surname, unless either side names none.
 */
internal fun sameWork(title: String, authors: List<String>, card: ScanCard): Boolean {
    val cardTitle = card.title ?: return false
    val theirs = setOf(WorkKey.token(title), WorkKey.token(mainTitle(title))) - ""
    val ours = setOf(WorkKey.token(cardTitle), WorkKey.token(mainTitle(cardTitle))) - ""
    if (theirs.none { it in ours }) return false
    if (authors.isEmpty() || card.authors.isEmpty()) return true
    val names = authors.map { WorkKey.token(firstLast(it)) }.toSet() - ""
    val surnames = authors.mapNotNull(::surname).toSet()
    return card.authors.any { WorkKey.token(firstLast(it)) in names || surname(it) in surnames }
}

/** "Stevenson, R. L." as "R. L. Stevenson". */
private fun firstLast(name: String): String {
    val parts = name.split(',', limit = 2)
    return if (parts.size == 2 && parts[1].isNotBlank()) "${parts[1].trim()} ${parts[0].trim()}" else name.trim()
}

/** The last word of a name ("Stevenson" of "R. L. Stevenson"), or null when too short to trust. */
private fun surname(name: String): String? =
    firstLast(name).split(Regex("\\s+")).lastOrNull()?.let(WorkKey::token)?.takeIf { it.length >= 2 }
