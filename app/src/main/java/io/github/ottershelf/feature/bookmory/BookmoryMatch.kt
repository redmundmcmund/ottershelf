package io.github.ottershelf.feature.bookmory

import io.github.ottershelf.feature.scan.Isbn
import java.text.Normalizer

/** A book the signed-in user can see, as `POST books/query` lists it (the fields matching needs). */
data class ServerBook(
    val id: Long,
    val title: String?,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val isbn13: String? = null,
    /** This user's rating, 1..5, or null. */
    val rating: Int? = null,
    /** This user's read status, or null. */
    val status: String? = null,
    /** The library it is in (whether the user may rate it depends on the role there). */
    val libraryId: Long? = null,
)

/** How a row was found among the user's books, or not. */
sealed interface MatchResult {
    /** One book: by ISBN, by title and author, or by the title without its subtitle. */
    data class Found(val book: ServerBook, val by: MatchedBy) : MatchResult

    /** Several books fit equally well: the user picks one in the preview. */
    data class Ambiguous(val candidates: List<ServerBook>, val by: MatchedBy) : MatchResult

    /** None: the row becomes a placeholder book (or is left out). */
    data object NotFound : MatchResult
}

enum class MatchedBy { ISBN, TITLE, SHORT_TITLE }

/**
 * Text normalisation for matching titles and authors: compatibility decomposition (NFKD), accents
 * and anything else outside ASCII dropped, lower case, apostrophes removed (so "Haven't" and
 * "Havent" agree) and other punctuation turned into spaces. Bookmory's U+FFFD (an apostrophe or a
 * letter it couldn't store) becomes [WILDCARD], which matches zero or one character. Pure.
 */
object BookmoryText {

    /** Stands for one unknown character (U+FFFD in the export) in a normalised title. */
    const val WILDCARD = '\u0001'

    private val SERIES_SUFFIX = Regex("""\s*\([^()]*#\s*\d+(?:\.\d+)?\s*\)\s*$""")
    private val ELLIPSIS = Regex("""\s*(\.\.\.|…)\s*$""")
    private val APOSTROPHES = Regex("""['’‘ʼ`´]""")
    private val COMBINING = Regex("""\p{M}+""")
    private val NOT_WORD = Regex("""[^a-z0-9\u0001]+""")
    private val NAME_SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv", "phd", "md")

    private val TRAILING_NOTE = Regex("""\s*[(\[][^()\[\]]*[)\]]\s*$""")
    private val NOTE_START = Regex("""\s[(\[]""")

    /**
     * The normalised title without its trailing notes in brackets ("(Harbour Lights Mysteries
     * Book 1)", "(Series, Book 1)", "(A Series Novel)", "[Large Print]"), and without anything
     * from an unclosed " (" on; null when nothing is left. Only for the step after the exact title,
     * which still needs an author in common.
     */
    fun notelessKey(title: String): String? {
        var t = ELLIPSIS.replace(title.trim(), "")
        while (true) {
            val next = TRAILING_NOTE.replace(t, "").trim()
            if (next == t) break
            t = next
        }
        NOTE_START.find(t)?.let { if (it.range.first > 0) t = t.substring(0, it.range.first) }
        return normalize(t).takeIf { it.isNotEmpty() }
    }

    /** Whether Bookmory cut the title short ("... from..."). */
    fun isTruncated(title: String): Boolean = ELLIPSIS.containsMatchIn(title)

    /** The title without a trailing "(Series, #2)" and without Bookmory's trailing "...". */
    fun bareTitle(title: String): String = SERIES_SUFFIX.replace(ELLIPSIS.replace(title.trim(), ""), "").trim()

    fun normalize(text: String): String {
        val withWildcards = text.replace('�', WILDCARD)
        val decomposed = Normalizer.normalize(withWildcards, Normalizer.Form.NFKD)
        val ascii = COMBINING.replace(decomposed, "")
            .replace(APOSTROPHES, "")
            .lowercase()
            .replace("&", " and ")
        return NOT_WORD.replace(ascii, " ").trim()
    }

    /** The normalised title, series suffix and "..." dropped. */
    fun titleKey(title: String): String = normalize(bareTitle(title))

    /** The part before a subtitle (": ..." or " - ..."), normalised; null when there is none. */
    fun shortTitleKey(title: String): String? {
        val bare = bareTitle(title)
        val cut = listOf(bare.indexOf(':'), bare.indexOf(" - "), bare.indexOf(" – "), bare.indexOf(" — "))
            .filter { it > 0 }.minOrNull() ?: return null
        return normalize(bare.substring(0, cut)).takeIf { it.isNotEmpty() }
    }

    /** An author's surname: the last word of the normalised name, past "Jr." and the like. */
    fun surname(author: String): String? {
        val words = normalize(author).split(' ').filter { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
        return words.lastOrNull { it !in NAME_SUFFIXES } ?: words.lastOrNull()
    }

    fun surnames(authors: List<String>): Set<String> = authors.mapNotNull(::surname).toSet()

    /**
     * Whether [csv] (a normalised row title, maybe with wildcards) names [server] (normalised):
     * equal, or, when [prefix] (the row's title was cut short), the start of it.
     */
    fun titleMatches(csv: String, server: String, prefix: Boolean): Boolean {
        if (csv.isEmpty() || server.isEmpty()) return false
        if (WILDCARD !in csv) return if (prefix) server.startsWith(csv) else server == csv
        val pattern = csv.split(WILDCARD).joinToString(".?") { Regex.escape(it) }
        val regex = Regex(if (prefix) "$pattern.*" else pattern)
        return regex.matches(server)
    }
}

/**
 * Finds a Bookmory row among the user's books. In order, the first step that finds anything
 * decides: the ISBN (13, or 10 as 13), then the title (series suffix dropped, U+FFFD as a wildcard,
 * a "..."-cut title as a prefix) with at least one author's surname in common, then the title
 * without a trailing note in brackets on either side, then the title without its subtitle, both
 * with an author in common. More than one book at that step is ambiguous.
 * Pure: everything is in memory.
 */
class BookmoryMatcher(books: List<ServerBook>) {

    private class Indexed(
        val book: ServerBook,
        val isbn13: String?,
        val keys: List<String>,
        val shortKeys: List<String>,
        val notelessKeys: List<String>,
        val surnames: Set<String>,
    )

    private val indexed = books.map { b ->
        val title = b.title.orEmpty()
        val full = listOfNotNull(b.subtitle?.takeIf { it.isNotBlank() }?.let { "$title: $it" })
        Indexed(
            book = b,
            isbn13 = b.isbn13?.let { raw -> isbn13Of(raw) },
            keys = (listOf(title) + full).map(BookmoryText::titleKey).filter { it.isNotEmpty() }.distinct(),
            shortKeys = listOfNotNull(BookmoryText.titleKey(title).takeIf { it.isNotEmpty() && b.subtitle != null }, BookmoryText.shortTitleKey(title)).distinct(),
            notelessKeys = (listOf(title) + full).mapNotNull(BookmoryText::notelessKey).distinct(),
            surnames = BookmoryText.surnames(b.authors),
        )
    }

    private val byIsbn: Map<String, List<Indexed>> = indexed.filter { it.isbn13 != null }.groupBy { it.isbn13!! }

    fun match(row: BookmoryRow): MatchResult {
        row.isbn?.let(::isbn13Of)?.let { isbn ->
            byIsbn[isbn]?.let { return result(it, MatchedBy.ISBN) }
        }
        val truncated = BookmoryText.isTruncated(row.title)
        val key = BookmoryText.titleKey(row.title)
        // A prefix shorter than this would match too much ("The...").
        val prefix = truncated && key.length >= MIN_PREFIX
        val surnames = BookmoryText.surnames(row.authors)

        fun authorsAgree(candidate: Indexed, exactTitle: Boolean): Boolean = when {
            surnames.isNotEmpty() && candidate.surnames.isNotEmpty() -> surnames.any { it in candidate.surnames }
            // A row or book without authors: only an exact title will do.
            else -> exactTitle
        }

        if (key.isNotEmpty() && (!truncated || prefix)) {
            val byTitle = indexed.filter { c ->
                c.keys.any { BookmoryText.titleMatches(key, it, prefix) } && authorsAgree(c, !prefix)
            }
            if (byTitle.isNotEmpty()) return result(byTitle, MatchedBy.TITLE)
        }

        // A note in brackets after the title on either side ("Title (Series Book 1)"), with an
        // author in common: never on the title alone.
        val noteless = BookmoryText.notelessKey(row.title)
        if (noteless != null && surnames.isNotEmpty() && (!truncated || prefix)) {
            val byNote = indexed.filter { c ->
                c.surnames.any { it in surnames } && c.notelessKeys.any { BookmoryText.titleMatches(noteless, it, prefix) }
            }
            if (byNote.isNotEmpty()) return result(byNote, MatchedBy.TITLE)
        }

        val short = BookmoryText.shortTitleKey(row.title) ?: key.takeIf { !truncated }
        if (!short.isNullOrEmpty()) {
            val byShort = indexed.filter { c ->
                surnames.isNotEmpty() && (c.shortKeys + c.keys).any { BookmoryText.titleMatches(short, it, prefix = false) } &&
                    authorsAgree(c, exactTitle = false)
            }
            if (byShort.isNotEmpty()) return result(byShort, MatchedBy.SHORT_TITLE)
        }
        return MatchResult.NotFound
    }

    private fun result(found: List<Indexed>, by: MatchedBy): MatchResult {
        val books = found.map { it.book }.distinctBy { it.id }
        return if (books.size == 1) MatchResult.Found(books.single(), by) else MatchResult.Ambiguous(books, by)
    }

    companion object {
        const val MIN_PREFIX = 12

        /** Any ISBN (10 or 13, hyphens allowed) as its ISBN-13; digits that aren't one stay as they are. */
        fun isbn13Of(raw: String): String? {
            val clean = raw.uppercase().filter { it.isDigit() || it == 'X' }
            if (clean.isEmpty()) return null
            return (Isbn.of13(clean) ?: Isbn.of10(clean))?.isbn13 ?: clean
        }
    }
}
