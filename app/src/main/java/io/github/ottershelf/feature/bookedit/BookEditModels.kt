package io.github.ottershelf.feature.bookedit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.network.ApiException

/**
 * The four fields this screen edits, as typed. Kept in the SavedStateHandle (JSON), so an edit
 * survives the process being killed in the Photo Picker or away.
 */
@Serializable
data class EditForm(
    val title: String = "",
    val authors: List<String> = emptyList(),
    val series: String = "",
    val seriesIndex: String = "",
) {
    /**
     * This form with every field [locked] on the server put back as in [original]: a locked field
     * can't be changed here, so it shows what it is.
     */
    fun keepingLocked(original: EditForm, locked: Collection<String>): EditForm {
        var form = this
        if (LockGroup.TITLE.lockedIn(locked)) form = form.copy(title = original.title)
        if (LockGroup.AUTHORS.lockedIn(locked)) form = form.copy(authors = original.authors)
        if (LockGroup.SERIES.lockedIn(locked)) form = form.copy(series = original.series, seriesIndex = original.seriesIndex)
        return form
    }

    companion object {
        /** The form as [book] has it: what the changes are worked out against. */
        fun of(book: BookDetail) = EditForm(
            title = book.title.orEmpty(),
            authors = book.authors.map { it.name },
            series = book.seriesName.orEmpty(),
            seriesIndex = book.seriesIndex.orEmpty(),
        )
    }
}

/**
 * The server's lock fields (BookMetadataLockField) behind each part of the form. A locked field
 * isn't sent (the server refuses it with a 409 on `PATCH books/:id/metadata`) and shows as locked;
 * the series counts as locked when either of its two is, as on the web.
 */
enum class LockGroup(val fields: List<String>) {
    TITLE(listOf("title")),
    AUTHORS(listOf("authors")),
    SERIES(listOf("seriesName", "seriesIndex")),
    COVER(listOf("cover"));

    fun lockedIn(locked: Collection<String>): Boolean = fields.any { it in locked }

    /** [locked] with this group's fields taken out: the whole list `PATCH metadata-locks` replaces. */
    fun unlock(locked: List<String>): List<String> = locked.filter { it !in fields }.distinct()
}

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
}

/** What's wrong with the form; Save waits until there's nothing. */
enum class FormProblem { TITLE_EMPTY, NO_AUTHORS, SERIES_INDEX_INVALID }

/** The book's primary series and its number as Save sends them; null name: out of the series. */
data class SeriesChange(val name: String?, val index: String?)

/**
 * What Save sends to `PATCH books/:id/metadata`: only the fields that changed, and never a locked
 * one. Null means unchanged (not sent).
 */
data class MetadataChanges(
    val title: String? = null,
    val authors: List<String>? = null,
    val series: SeriesChange? = null,
) {
    val isEmpty: Boolean get() = title == null && authors == null && series == null

    /**
     * The request body, in UpdateBookMetadataDto's shape (the server rejects any other field):
     * `title` a string, `authors` an array of names (the server finds or creates each by name, in
     * this order), and the primary series as `seriesName` + `seriesIndex` (a string such as "2.5"),
     * both sent whenever either changed; null for both takes the book out of that series, keeping
     * any other series it is in.
     */
    fun body(): JsonObject {
        val fields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        title?.let { fields["title"] = JsonPrimitive(it) }
        authors?.let { names -> fields["authors"] = JsonArray(names.map(::JsonPrimitive)) }
        series?.let {
            fields["seriesName"] = it.name?.let(::JsonPrimitive) ?: JsonNull
            fields["seriesIndex"] = it.index?.let(::JsonPrimitive) ?: JsonNull
        }
        return JsonObject(fields)
    }

    companion object {
        /** The fields of [form] that differ from [original], leaving out those [locked] on the server. */
        fun of(original: EditForm, form: EditForm, locked: Collection<String> = emptyList()): MetadataChanges {
            val title = form.title.trim().takeIf { it != original.title.trim() && !LockGroup.TITLE.lockedIn(locked) }
            val authors = EditRules.authorsOf(form.authors)
                .takeIf { it != EditRules.authorsOf(original.authors) && !LockGroup.AUTHORS.lockedIn(locked) }
            val (name, index) = seriesOf(form)
            val (wasName, wasIndex) = seriesOf(original)
            val series = SeriesChange(name, index)
                .takeIf { (name != wasName || index != wasIndex) && !LockGroup.SERIES.lockedIn(locked) }
            return MetadataChanges(title, authors, series)
        }

        /** The series name (null: none) and its number (null: none, or no series). */
        internal fun seriesOf(form: EditForm): Pair<String?, String?> {
            val name = EditRules.normalizeName(form.series).takeIf { it.isNotEmpty() }
            val index = EditRules.seriesIndexOf(form.seriesIndex).takeIf { it.isNotEmpty() && name != null }
            return name to index
        }
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

/** Whether [book]'s primary file (else its first) is audio, as the web's cover search decides. */
internal fun isAudiobook(book: BookDetail): Boolean {
    val file = book.files.firstOrNull { it.role == "primary" } ?: book.files.firstOrNull()
    return file?.format?.lowercase() in AUDIO_FORMATS
}
