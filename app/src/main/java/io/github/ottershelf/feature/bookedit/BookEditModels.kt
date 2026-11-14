package io.github.ottershelf.feature.bookedit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ComicMetadata
import io.github.ottershelf.core.network.ApiException

/**
 * The fields this screen edits, as typed: the light editor's four (title, authors, series and its
 * number), and the rest the full editor shows (Settings > Full metadata editing, [FullEditing]).
 * Kept in the SavedStateHandle (JSON), so an edit survives the process being killed in the Photo
 * Picker or away; a form saved by an older version reads back with the new fields empty.
 */
@Serializable
data class EditForm(
    val title: String = "",
    val authors: List<String> = emptyList(),
    val series: String = "",
    val seriesIndex: String = "",
    val subtitle: String = "",
    /** The description as plain text: paragraphs apart by a blank line ([DescriptionText]). */
    val description: String = "",
    val publisher: String = "",
    /** "YYYY" or "YYYY-MM-DD". */
    val published: String = "",
    val language: String = "",
    val pages: String = "",
    val isbn13: String = "",
    val isbn10: String = "",
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Series the book is in besides its main one ([series]), in order. */
    val otherSeries: List<SeriesEntry> = emptyList(),
    val comic: ComicForm = ComicForm(),
) {
    /**
     * This form with every field [locked] on the server put back as in [original]: a locked field
     * can't be changed here, so it shows what it is.
     */
    fun keepingLocked(original: EditForm, locked: Collection<String>): EditForm =
        LockGroup.entries.fold(this) { form, group -> if (group.lockedIn(locked)) group.keep(form, original) else form }

    companion object {
        /** The form as [book] has it: what the changes are worked out against. */
        fun of(book: BookDetail): EditForm {
            val comic = book.comicMetadata
            return EditForm(
                title = book.title.orEmpty(),
                authors = book.authors.map { it.name },
                series = book.seriesName.orEmpty(),
                seriesIndex = book.seriesIndex.orEmpty(),
                subtitle = book.subtitle.orEmpty(),
                description = DescriptionText.fromHtml(book.description.orEmpty()),
                publisher = book.publisher.orEmpty(),
                published = book.publishedDate?.takeIf { it.isNotBlank() } ?: book.publishedYear?.toString().orEmpty(),
                language = book.language.orEmpty(),
                pages = book.pageCount?.toString().orEmpty(),
                isbn13 = book.isbn13.orEmpty(),
                isbn10 = book.isbn10.orEmpty(),
                genres = book.genres,
                tags = book.tags,
                otherSeries = otherSeriesOf(book),
                comic = ComicForm(
                    issueNumber = comic?.issueNumber.orEmpty(),
                    volumeName = comic?.volumeName.orEmpty(),
                    credits = ComicList.entries.associate { it.key to it.of(comic).orEmpty() }.filterValues { it.isNotEmpty() },
                ),
            )
        }

        /** The book's series other than its main one ([BookDetail.seriesId], else the one named as main). */
        internal fun otherSeriesOf(book: BookDetail): List<SeriesEntry> {
            val all = book.seriesMemberships
            if (all.isEmpty()) return emptyList()
            var main = all.indexOfFirst { book.seriesId != null && it.seriesId == book.seriesId }
            if (main < 0 && book.seriesName != null) main = all.indexOfFirst { it.seriesName == book.seriesName }
            return all.filterIndexed { i, _ -> i != main }.mapNotNull { m ->
                m.seriesName?.takeIf { it.isNotBlank() }?.let { SeriesEntry(it, m.seriesIndex.orEmpty()) }
            }
        }
    }
}

/** Another series the book is in, and its number there (as typed). */
@Serializable
data class SeriesEntry(val name: String = "", val index: String = "")

/** A comic's issue, volume and credit lists, as typed; [credits] keyed by [ComicList.key]. */
@Serializable
data class ComicForm(
    val issueNumber: String = "",
    val volumeName: String = "",
    val credits: Map<String, List<String>> = emptyMap(),
) {
    fun list(which: ComicList): List<String> = credits[which.key].orEmpty()

    fun withList(which: ComicList, names: List<String>): ComicForm =
        copy(credits = (credits + (which.key to names)).filterValues { it.isNotEmpty() })
}

/** A comic's name lists: [key] in ComicMetadataDto, [lock] its BookMetadataLockField. */
enum class ComicList(val key: String, val lock: String, val of: (ComicMetadata?) -> List<String>?) {
    STORY_ARCS("storyArcs", "comicStoryArcs", { it?.storyArcs }),
    PENCILLERS("pencillers", "comicPencillers", { it?.pencillers }),
    INKERS("inkers", "comicInkers", { it?.inkers }),
    COLORISTS("colorists", "comicColorists", { it?.colorists }),
    LETTERERS("letterers", "comicLetterers", { it?.letterers }),
    COVER_ARTISTS("coverArtists", "comicCoverArtists", { it?.coverArtists }),
    CHARACTERS("characters", "comicCharacters", { it?.characters }),
    TEAMS("teams", "comicTeams", { it?.teams }),
    LOCATIONS("locations", "comicLocations", { it?.locations }),
}

/**
 * The server's lock fields (BookMetadataLockField) behind each part of the form. A locked field
 * isn't sent (the server refuses it with a 409 on `PATCH books/:id/metadata`) and shows as locked;
 * the series counts as locked when either of its two is, as on the web. [keep] puts the group's part
 * of a form back as it was.
 */
enum class LockGroup(val fields: List<String>, val keep: (EditForm, EditForm) -> EditForm = { form, _ -> form }) {
    TITLE(listOf("title"), { f, o -> f.copy(title = o.title) }),
    AUTHORS(listOf("authors"), { f, o -> f.copy(authors = o.authors) }),
    SERIES(listOf("seriesName", "seriesIndex"), { f, o -> f.copy(series = o.series, seriesIndex = o.seriesIndex, otherSeries = o.otherSeries) }),
    COVER(listOf("cover")),
    SUBTITLE(listOf("subtitle"), { f, o -> f.copy(subtitle = o.subtitle) }),
    DESCRIPTION(listOf("description"), { f, o -> f.copy(description = o.description) }),
    PUBLISHER(listOf("publisher"), { f, o -> f.copy(publisher = o.publisher) }),
    PUBLISHED(listOf("publishedYear"), { f, o -> f.copy(published = o.published) }),
    LANGUAGE(listOf("language"), { f, o -> f.copy(language = o.language) }),
    PAGES(listOf("pageCount"), { f, o -> f.copy(pages = o.pages) }),
    ISBN13(listOf("isbn13"), { f, o -> f.copy(isbn13 = o.isbn13) }),
    ISBN10(listOf("isbn10"), { f, o -> f.copy(isbn10 = o.isbn10) }),
    GENRES(listOf("genres"), { f, o -> f.copy(genres = o.genres) }),
    TAGS(listOf("tags"), { f, o -> f.copy(tags = o.tags) }),
    COMIC_ISSUE(listOf("comicIssueNumber"), { f, o -> f.copy(comic = f.comic.copy(issueNumber = o.comic.issueNumber)) }),
    COMIC_VOLUME(listOf("comicVolumeName"), { f, o -> f.copy(comic = f.comic.copy(volumeName = o.comic.volumeName)) }),
    COMIC_STORY_ARCS(listOf("comicStoryArcs"), comicKeep(ComicList.STORY_ARCS)),
    COMIC_PENCILLERS(listOf("comicPencillers"), comicKeep(ComicList.PENCILLERS)),
    COMIC_INKERS(listOf("comicInkers"), comicKeep(ComicList.INKERS)),
    COMIC_COLORISTS(listOf("comicColorists"), comicKeep(ComicList.COLORISTS)),
    COMIC_LETTERERS(listOf("comicLetterers"), comicKeep(ComicList.LETTERERS)),
    COMIC_COVER_ARTISTS(listOf("comicCoverArtists"), comicKeep(ComicList.COVER_ARTISTS)),
    COMIC_CHARACTERS(listOf("comicCharacters"), comicKeep(ComicList.CHARACTERS)),
    COMIC_TEAMS(listOf("comicTeams"), comicKeep(ComicList.TEAMS)),
    COMIC_LOCATIONS(listOf("comicLocations"), comicKeep(ComicList.LOCATIONS));

    fun lockedIn(locked: Collection<String>): Boolean = fields.any { it in locked }

    /** [locked] with this group's fields taken out: the whole list `PATCH metadata-locks` replaces. */
    fun unlock(locked: List<String>): List<String> = locked.filter { it !in fields }.distinct()

    /** [locked] with this group's fields added (the full editor's Lock). */
    fun lock(locked: List<String>): List<String> = (locked + fields).distinct()

    companion object {
        /** The group of a comic's name list. */
        fun of(list: ComicList): LockGroup = entries.first { list.lock in it.fields }
    }
}

private fun comicKeep(list: ComicList): (EditForm, EditForm) -> EditForm =
    { f, o -> f.copy(comic = f.comic.withList(list, o.comic.list(list))) }

/** The server's limits and its normalisation, mirrored so the form refuses what it would. */
object EditRules {
    /** UpdateBookMetadataDto: `title` MaxLength(1000). */
    const val TITLE_MAX = 1000

    /** `seriesName` MaxLength(500); authors are varchar(500) on the server. */
    const val NAME_MAX = 500

    /** SERIES_INDEX_MAX_LENGTH (packages/types series-index.ts). */
    const val SERIES_INDEX_MAX = 20

    /** SERIES_INDEX_PATTERN: "2", "2.5", "10", "1.10"; no sign, no leading or trailing point. */
    private val SERIES_INDEX = Regex("""^\d+(?:\.\d+)?$""")

    private val SPACES = Regex("""\s+""")

    /** The web's ChipInput separators: a pasted list of authors becomes one chip each. */
    private val SEPARATORS = Regex("""[,\n\t;]""")

    /** Whitespace collapsed and trimmed, as the server stores names (normalizeMetadataText). */
    fun normalizeName(raw: String): String = raw.replace(SPACES, " ").trim()

    /** The number as it would be sent: trimmed, a decimal comma read as a point (a phone keypad offers one). */
    fun seriesIndexOf(raw: String): String = raw.trim().replace(',', '.')

    /** The server's own check (isValidSeriesIndex): digits, optionally a point and more digits, 20 at most. */
    fun isValidSeriesIndex(value: String): Boolean = value.length <= SERIES_INDEX_MAX && SERIES_INDEX.matches(value)

    /** A title as typed: no line breaks (it is one line on the server), at most [TITLE_MAX]. */
    fun titleOf(raw: String): String = raw.replace('\n', ' ').replace('\r', ' ').take(TITLE_MAX)

    /**
     * [authors] with [raw] added at the end (the first author stays first), normalised; a blank
     * name, or one already there in any case (the server keeps one of each), changes nothing.
     */
    fun addAuthor(authors: List<String>, raw: String): List<String> {
        val name = normalizeName(raw).take(NAME_MAX)
        if (name.isEmpty() || authors.any { it.equals(name, ignoreCase = true) }) return authors
        return authors + name
    }

    /**
     * Typing into the author box: everything before the last separator becomes authors (a pasted
     * "A, B, C"), what follows stays in the box. Returns the authors and the box's text.
     */
    fun typeAuthor(authors: List<String>, typed: String): Pair<List<String>, String> {
        if (!SEPARATORS.containsMatchIn(typed)) return authors to typed.take(NAME_MAX)
        val parts = typed.split(SEPARATORS)
        var next = authors
        for (part in parts.dropLast(1)) next = addAuthor(next, part)
        return next to parts.last().trimStart().take(NAME_MAX)
    }

    /** The authors as they would be sent: normalised, blanks and repeats (any case) left out, in order. */
    fun authorsOf(authors: List<String>): List<String> = authors.fold(emptyList(), ::addAuthor)

    /** UpdateBookMetadataDto: `subtitle` 1000, `publisher` 500, `language` 100; ComicMetadataDto `issueNumber` 50, `volumeName` 500. */
    const val SUBTITLE_MAX = 1000
    const val LANGUAGE_MAX = 100
    const val ISSUE_MAX = 50

    /** ISBN-13 and ISBN-10 as the server keeps them (normalizeIsbnInput: no spaces or hyphens), 13 and 10 at most. */
    const val ISBN13_MAX = 13
    const val ISBN10_MAX = 10

    fun isbnOf(raw: String): String = raw.replace(Regex("""[\s-]"""), "").uppercase()

    private val YEAR = Regex("""^\d{4}$""")
    private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    /** publishedYear's range (1000..2200). */
    private val YEARS = 1000..2200

    /** What "Published" sends: a year, a full date, or null when it isn't one. */
    fun publishedOf(raw: String): Published? {
        val text = raw.trim()
        if (text.isEmpty()) return Published.None
        YEAR.matchEntire(text)?.let { return text.toInt().takeIf { it in YEARS }?.let(Published::Year) }
        val m = DATE.matchEntire(text) ?: return null
        val (y, mo, d) = m.destructured
        val valid = runCatching { java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.isSuccess
        return if (valid && y.toInt() in YEARS) Published.Date(text) else null
    }

    /** A page count: blank (none) or a whole number from 1. */
    fun pagesOf(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it >= 1 }

    /** A list of names (genres, tags, credits) with [raw] added at the end, as [addAuthor] does. */
    fun addName(names: List<String>, raw: String): List<String> = addAuthor(names, raw)

    /** A list of names as it would be sent: normalised, no blanks or repeats. */
    fun namesOf(names: List<String>): List<String> = authorsOf(names)
}

/** The "Published" field, read. */
sealed interface Published {
    data object None : Published
    data class Year(val year: Int) : Published
    data class Date(val date: String) : Published
}

/**
 * A book's description as the full editor shows it: the server keeps HTML (from the book's file, a
 * metadata source or the web's rich editor), edited here as plain paragraphs. A description that
 * isn't changed isn't sent, so its formatting stays; one that is goes back as simple HTML paragraphs.
 */
object DescriptionText {
    private val BREAK = Regex("""(?i)<br\s*/?>""")
    private val BLOCK_END = Regex("""(?i)</(p|div|h[1-6]|li|blockquote|ul|ol|tr)>""")
    private val LIST_ITEM = Regex("""(?i)<li[^>]*>""")
    private val TAG = Regex("""<[^>]*>""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val SPACES = Regex("""[ \t\u00A0]+""")
    private val BLANK_LINES = Regex("""\n{3,}""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "#39" to "'")

    fun fromHtml(html: String): String {
        if (html.isBlank()) return ""
        if ('<' !in html && '&' !in html) return html.trim()
        var text = html.replace("\r", "").replace("\n", " ")
        text = text.replace(BREAK, "\n").replace(LIST_ITEM, "\u2022 ").replace(BLOCK_END, "\n\n").replace(TAG, "")
        text = text.replace(ENTITY) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x") -> name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED[name] ?: m.value
            }
        }
        return text.lines().joinToString("\n") { it.replace(SPACES, " ").trim() }.replace(BLANK_LINES, "\n\n").trim()
    }

    /** [text]'s paragraphs (apart by a blank line) as `<p>`, its single line breaks as `<br>`; null when blank. */
    fun toHtml(text: String): String? {
        val paragraphs = text.replace("\r", "").split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return null
        return paragraphs.joinToString("") { p -> "<p>" + p.lines().joinToString("<br>") { escape(it.trim()) } + "</p>" }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** What's wrong with the form; Save waits until there's nothing. */
enum class FormProblem { TITLE_EMPTY, NO_AUTHORS, SERIES_INDEX_INVALID, OTHER_SERIES_INVALID, PUBLISHED_INVALID, PAGES_INVALID, ISBN13_INVALID, ISBN10_INVALID }

/** The book's primary series and its number as Save sends them; null name: out of the series. */
data class SeriesChange(val name: String?, val index: String?)

/**
 * What Save sends to `PATCH books/:id/metadata`: only the fields that changed, and never a locked
 * one. Null means unchanged (not sent). [extra]: the full editor's fields that changed, already in
 * UpdateBookMetadataDto's shape (a JSON null clears one).
 */
data class MetadataChanges(
    val title: String? = null,
    val authors: List<String>? = null,
    val series: SeriesChange? = null,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val isEmpty: Boolean get() = title == null && authors == null && series == null && extra.isEmpty()

    /**
     * The request body, in UpdateBookMetadataDto's shape (the server rejects any other field):
     * `title` a string, `authors` an array of names (the server finds or creates each by name, in
     * this order), and the primary series as `seriesName` + `seriesIndex` (a string such as "2.5"),
     * both sent whenever either changed; null for both takes the book out of that series, keeping
     * any other series it is in. When the book's other series changed, `seriesMemberships` (every
     * series, the main one first) is sent instead: the server then ignores `seriesName`/`seriesIndex`.
     */
    fun body(): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        title?.let { fields["title"] = JsonPrimitive(it) }
        authors?.let { names -> fields["authors"] = JsonArray(names.map(::JsonPrimitive)) }
        if ("seriesMemberships" !in extra) {
            series?.let {
                fields["seriesName"] = it.name?.let(::JsonPrimitive) ?: JsonNull
                fields["seriesIndex"] = it.index?.let(::JsonPrimitive) ?: JsonNull
            }
        }
        fields.putAll(extra)
        return JsonObject(fields)
    }

    companion object {
        /** The fields of [form] that differ from [original], leaving out those [locked] on the server. */
        fun of(original: EditForm, form: EditForm, locked: Collection<String> = emptyList()): MetadataChanges {
            fun open(group: LockGroup) = !group.lockedIn(locked)
            val title = form.title.trim().takeIf { it != original.title.trim() && open(LockGroup.TITLE) }
            val authors = EditRules.authorsOf(form.authors)
                .takeIf { it != EditRules.authorsOf(original.authors) && open(LockGroup.AUTHORS) }
            val (name, index) = seriesOf(form)
            val (wasName, wasIndex) = seriesOf(original)
            val series = SeriesChange(name, index)
                .takeIf { (name != wasName || index != wasIndex) && open(LockGroup.SERIES) }

            val extra = LinkedHashMap<String, JsonElement>()
            fun text(key: String, group: LockGroup, now: String, was: String) {
                val a = EditRules.normalizeName(now)
                if (a != EditRules.normalizeName(was) && open(group)) extra[key] = a.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
            }
            text("subtitle", LockGroup.SUBTITLE, form.subtitle, original.subtitle)
            text("publisher", LockGroup.PUBLISHER, form.publisher, original.publisher)
            text("language", LockGroup.LANGUAGE, form.language, original.language)
            if (form.description.trim() != original.description.trim() && open(LockGroup.DESCRIPTION)) {
                extra["description"] = DescriptionText.toHtml(form.description)?.let(::JsonPrimitive) ?: JsonNull
            }
            if (form.published.trim() != original.published.trim() && open(LockGroup.PUBLISHED)) {
                when (val p = EditRules.publishedOf(form.published)) {
                    Published.None -> { extra["publishedDate"] = JsonNull; extra["publishedYear"] = JsonNull }
                    is Published.Year -> extra["publishedYear"] = JsonPrimitive(p.year)
                    is Published.Date -> extra["publishedDate"] = JsonPrimitive(p.date)
                    null -> Unit // a problem: Save waits
                }
            }
            if (form.pages.trim() != original.pages.trim() && open(LockGroup.PAGES)) {
                extra["pageCount"] = EditRules.pagesOf(form.pages)?.let(::JsonPrimitive) ?: JsonNull
            }
            fun isbn(key: String, group: LockGroup, now: String, was: String) {
                val a = EditRules.isbnOf(now)
                if (a != EditRules.isbnOf(was) && open(group)) extra[key] = a.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
            }
            isbn("isbn13", LockGroup.ISBN13, form.isbn13, original.isbn13)
            isbn("isbn10", LockGroup.ISBN10, form.isbn10, original.isbn10)
            fun names(key: String, group: LockGroup, now: List<String>, was: List<String>) {
                val a = EditRules.namesOf(now)
                if (a != EditRules.namesOf(was) && open(group)) extra[key] = JsonArray(a.map(::JsonPrimitive))
            }
            names("genres", LockGroup.GENRES, form.genres, original.genres)
            names("tags", LockGroup.TAGS, form.tags, original.tags)

            // The other series: every series goes, the main one first (the server's primary).
            val others = otherSeriesOf(form)
            if (others != otherSeriesOf(original) && open(LockGroup.SERIES)) {
                val all = buildList {
                    name?.let { add(it to index) }
                    others.forEach { (n, i) -> if (none { it.first.equals(n, ignoreCase = true) }) add(n to i) }
                }
                extra["seriesMemberships"] = JsonArray(all.map { (n, i) ->
                    JsonObject(buildMap {
                        put("seriesName", JsonPrimitive(n))
                        put("seriesIndex", i?.let(::JsonPrimitive) ?: JsonNull)
                    })
                })
            }

            // A comic's fields: only those that changed, in one comicMetadata object.
            val comic = LinkedHashMap<String, JsonElement>()
            fun comicText(key: String, group: LockGroup, now: String, was: String) {
                val a = EditRules.normalizeName(now)
                if (a != EditRules.normalizeName(was) && open(group)) comic[key] = a.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
            }
            comicText("issueNumber", LockGroup.COMIC_ISSUE, form.comic.issueNumber, original.comic.issueNumber)
            comicText("volumeName", LockGroup.COMIC_VOLUME, form.comic.volumeName, original.comic.volumeName)
            ComicList.entries.forEach { list ->
                val a = EditRules.namesOf(form.comic.list(list))
                if (a != EditRules.namesOf(original.comic.list(list)) && open(LockGroup.of(list))) comic[list.key] = JsonArray(a.map(::JsonPrimitive))
            }
            if (comic.isNotEmpty()) extra["comicMetadata"] = JsonObject(comic)

            return MetadataChanges(title, authors, series, extra)
        }

        /** The series name (null: none) and its number (null: none, or no series). */
        internal fun seriesOf(form: EditForm): Pair<String?, String?> {
            val name = EditRules.normalizeName(form.series).takeIf { it.isNotEmpty() }
            val index = EditRules.seriesIndexOf(form.seriesIndex).takeIf { it.isNotEmpty() && name != null }
            return name to index
        }

        /** The other series as they would be sent: named ones only, normalised, each once. */
        internal fun otherSeriesOf(form: EditForm): List<Pair<String, String?>> =
            form.otherSeries.mapNotNull { e ->
                EditRules.normalizeName(e.name).takeIf { it.isNotEmpty() }?.let { it to EditRules.seriesIndexOf(e.index).takeIf { i -> i.isNotEmpty() } }
            }.distinctBy { it.first.lowercase() }
    }
}

/** What stops [form] from being saved (locked fields aren't checked: they aren't sent). */
fun problemsOf(original: EditForm, form: EditForm, locked: Collection<String> = emptyList()): Set<FormProblem> {
    val problems = mutableSetOf<FormProblem>()
    if (!LockGroup.TITLE.lockedIn(locked) && form.title.isBlank() && original.title.isNotBlank()) problems += FormProblem.TITLE_EMPTY
    if (!LockGroup.AUTHORS.lockedIn(locked) && EditRules.authorsOf(form.authors).isEmpty() && EditRules.authorsOf(original.authors).isNotEmpty()) {
        problems += FormProblem.NO_AUTHORS
    }
    val (name, index) = MetadataChanges.seriesOf(form)
    if (!LockGroup.SERIES.lockedIn(locked) && name != null && index != null && !EditRules.isValidSeriesIndex(index)) {
        problems += FormProblem.SERIES_INDEX_INVALID
    }
    if (!LockGroup.SERIES.lockedIn(locked) && MetadataChanges.otherSeriesOf(form).any { (_, i) -> i != null && !EditRules.isValidSeriesIndex(i) }) {
        problems += FormProblem.OTHER_SERIES_INVALID
    }
    if (!LockGroup.PUBLISHED.lockedIn(locked) && form.published.trim() != original.published.trim() && EditRules.publishedOf(form.published) == null) {
        problems += FormProblem.PUBLISHED_INVALID
    }
    if (!LockGroup.PAGES.lockedIn(locked) && form.pages.isNotBlank() && form.pages.trim() != original.pages.trim() && EditRules.pagesOf(form.pages) == null) {
        problems += FormProblem.PAGES_INVALID
    }
    if (!LockGroup.ISBN13.lockedIn(locked) && EditRules.isbnOf(form.isbn13).length > EditRules.ISBN13_MAX) problems += FormProblem.ISBN13_INVALID
    if (!LockGroup.ISBN10.lockedIn(locked) && EditRules.isbnOf(form.isbn10).length > EditRules.ISBN10_MAX) problems += FormProblem.ISBN10_INVALID
    return problems
}

/** Why a load, save or cover change didn't work, as the screen words it. */
sealed interface EditError {
    /** No answer: no connection, or it dropped. */
    data object Offline : EditError

    /** 403: the account may not edit book details. */
    data object Forbidden : EditError

    /** The server's own words (a validation error, "Metadata fields are locked: title"). */
    data class Server(val message: String) : EditError

    /** An error without words of its own. */
    data class Http(val code: Int) : EditError

    companion object {
        fun of(e: Throwable): EditError = when (e) {
            is ApiException -> when {
                e.code == 403 -> Forbidden
                e.code in 400..499 && !e.message.isNullOrBlank() && e.message != "HTTP ${e.code}" -> Server(e.message.orEmpty())
                else -> Http(e.code)
            }
            else -> Offline
        }
    }
}

/** The formats of an audiobook (packages/types FORMAT_TO_GROUP): cover searches then ask for square ones. */
internal val AUDIO_FORMATS = setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")

/** A comic's formats (COMIC_FORMAT_LIST). */
internal val COMIC_FORMATS = setOf("cbz", "cbr", "cb7", "cbx")

/** Whether [book] is a comic: its primary file (else its first) is a comic archive, or it has comic details. */
internal fun isComicBook(book: BookDetail): Boolean {
    val file = book.files.firstOrNull { it.role == "primary" } ?: book.files.firstOrNull()
    return file?.format?.lowercase() in COMIC_FORMATS || book.comicMetadata != null
}

/** Whether [book]'s primary file (else its first) is audio, as the web's cover search decides. */
internal fun isAudiobook(book: BookDetail): Boolean {
    val file = book.files.firstOrNull { it.role == "primary" } ?: book.files.firstOrNull()
    return file?.format?.lowercase() in AUDIO_FORMATS
}
