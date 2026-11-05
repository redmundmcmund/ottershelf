package io.github.ottershelf.feature.bookmory

import java.time.LocalDate

/** What the user chose for a row in the preview. */
sealed interface RowChoice {
    data class Book(val bookId: Long) : RowChoice
    data object Stub : RowChoice
    data object Exclude : RowChoice
}

/** The user's choices in the preview; rows not in [rows] keep their default. */
data class ImportChoices(
    val libraryId: Long? = null,
    /** Backlog dates whose readings are imported without dates. */
    val undated: Set<LocalDate> = emptySet(),
    val rows: Map<Int, RowChoice> = emptyMap(),
)

/** Everything the preview is worked out from: the file, the user's books, and the matched books' state. */
data class ImportAnalysis(
    val file: BookmoryFile,
    val books: Map<Long, ServerBook>,
    val matches: Map<Int, MatchResult>,
    val states: Map<Long, BookState>,
    val libraries: List<UploadLibrary>,
    val canUpload: Boolean,
    val ratingAccess: RatingAccess = RatingAccess(),
)

enum class RowKind { MATCHED, STUB, AMBIGUOUS, EXCLUDED, NO_LIBRARY }

/** One row as the preview lists it. */
data class PreviewRow(
    val line: Int,
    val title: String,
    val authors: List<String>,
    val kind: RowKind,
    /** The book it was matched to (or picked), when [kind] is MATCHED. */
    val book: ServerBook?,
    val matchedBy: MatchedBy?,
    /** The books to pick from, when the match was ambiguous (kept after a pick, to change it). */
    val candidates: List<ServerBook>,
    /** Whether a placeholder can be made for it instead (the user may upload). */
    val canStub: Boolean,
    val readingsToAdd: Int,
    val readingsPresent: Int,
    val readingsLeftAlone: Int,
    val writes: Int,
    /** Why its rating is left out, when it is (the account may not rate this book). */
    val ratingBlockedBy: RatingRule? = null,
)

data class PreviewTotals(
    val rows: Int,
    val matched: Int,
    val ambiguous: Int,
    val stubs: Int,
    val readingsToAdd: Int,
    val readingsPresent: Int,
    /** Left alone: the book has a reading for them with other dates. */
    val readingsLeftAlone: Int,
    val statuses: Int,
    val ratings: Int,
    /** Ratings left out because the account may not set them. */
    val ratingsBlocked: Int,
    /** The account can't edit book details at all, so no rating can be set. */
    val ratingsNotPermitted: Boolean,
    /** Rows that won't be imported: unreadable, left out, not picked, or a placeholder with no library. */
    val skipped: Int,
    val problems: Int,
)

data class ImportPreview(
    val totals: PreviewTotals,
    val rows: List<PreviewRow>,
    val problems: List<BookmoryProblem>,
    val backlog: Map<LocalDate, Int>,
    val items: List<ImportItem>,
) {
    /** Everything in the file is in BookOrbit already. */
    val nothingNew: Boolean get() = items.none { it.target is ImportTarget.Stub } &&
        totals.readingsToAdd == 0 && totals.statuses == 0 && totals.ratings == 0
}

/** Works out the preview and the import's items from the analysis and the user's choices. Pure. */
object ImportPreviews {

    fun preview(analysis: ImportAnalysis, choices: ImportChoices): ImportPreview {
        val rows = mutableListOf<PreviewRow>()
        val items = mutableListOf<ImportItem>()
        val library = choices.libraryId?.takeIf { id -> analysis.canUpload && analysis.libraries.any { it.id == id } }
        var stmt = Totals()
        for (row in analysis.file.rows) {
            val match = analysis.matches[row.line] ?: MatchResult.NotFound
            val candidates = when (match) {
                is MatchResult.Ambiguous -> match.candidates
                is MatchResult.Found -> listOf(match.book)
                MatchResult.NotFound -> emptyList()
            }
            val default: RowChoice? = when (match) {
                is MatchResult.Found -> RowChoice.Book(match.book.id)
                MatchResult.NotFound -> RowChoice.Stub
                is MatchResult.Ambiguous -> null
            }
            val choice = choices.rows[row.line] ?: default
            val plan = ImportPlanner.plan(row, choices.undated)
            val book = (choice as? RowChoice.Book)?.let { analysis.books[it.bookId] ?: candidates.firstOrNull { c -> c.id == it.bookId } }
            val kind = when {
                choice == RowChoice.Exclude -> RowKind.EXCLUDED
                choice == null -> RowKind.AMBIGUOUS
                choice == RowChoice.Stub && library == null -> RowKind.NO_LIBRARY
                choice == RowChoice.Stub -> RowKind.STUB
                book == null -> RowKind.AMBIGUOUS
                else -> RowKind.MATCHED
            }
            val rule = when (kind) {
                RowKind.MATCHED -> analysis.ratingAccess.rule(book!!.libraryId)
                RowKind.STUB, RowKind.NO_LIBRARY -> analysis.ratingAccess.rule(library)
                else -> RatingRule.Allowed
            }
            val mayRate = rule == RatingRule.Allowed
            val diff = when (kind) {
                RowKind.MATCHED -> ImportPlanner.diff(plan, analysis.states[book!!.id] ?: BookState(book.status, book.rating, emptyList()), mayRate)
                RowKind.STUB, RowKind.NO_LIBRARY -> ImportPlanner.diff(plan, BookState(null, null, emptyList()), mayRate)
                else -> null
            }
            when (kind) {
                RowKind.MATCHED -> {
                    stmt = stmt.add(diff!!).copy(matched = stmt.matched + 1)
                    items += ImportItem(plan, ImportTarget.Book(book!!.id), rule)
                }
                RowKind.STUB -> {
                    stmt = stmt.add(diff!!).copy(stubs = stmt.stubs + 1)
                    items += ImportItem(plan, ImportTarget.Stub(StubBook.of(row), library!!), rule)
                }
                RowKind.AMBIGUOUS -> stmt = stmt.copy(ambiguous = stmt.ambiguous + 1, skipped = stmt.skipped + 1)
                RowKind.EXCLUDED, RowKind.NO_LIBRARY -> stmt = stmt.copy(skipped = stmt.skipped + 1)
            }
            rows += PreviewRow(
                line = row.line,
                title = row.title,
                authors = row.authors,
                kind = kind,
                book = book.takeIf { kind == RowKind.MATCHED },
                matchedBy = (match as? MatchResult.Found)?.by ?: (match as? MatchResult.Ambiguous)?.by,
                candidates = if (match is MatchResult.Ambiguous) match.candidates else emptyList(),
                canStub = analysis.canUpload && analysis.libraries.isNotEmpty(),
                readingsToAdd = if (kind == RowKind.NO_LIBRARY) 0 else diff?.readingsToAdd ?: 0,
                readingsPresent = diff?.readingsPresent ?: 0,
                readingsLeftAlone = diff?.readingsLeftAlone ?: 0,
                writes = if (kind == RowKind.NO_LIBRARY) 0 else diff?.actions?.size ?: 0,
                ratingBlockedBy = rule.takeIf { (diff?.ratingsBlocked ?: 0) > 0 && kind != RowKind.NO_LIBRARY },
            )
        }
        val problems = analysis.file.problems
        val totals = PreviewTotals(
            rows = analysis.file.rows.size + problems.size,
            matched = stmt.matched,
            ambiguous = stmt.ambiguous,
            stubs = stmt.stubs,
            readingsToAdd = stmt.toAdd,
            readingsPresent = stmt.present,
            readingsLeftAlone = stmt.leftAlone,
            statuses = stmt.statuses,
            ratings = stmt.ratings,
            ratingsBlocked = stmt.blocked,
            ratingsNotPermitted = !analysis.ratingAccess.permitted && stmt.blocked > 0,
            skipped = stmt.skipped + problems.size,
            problems = problems.size,
        )
        return ImportPreview(totals, rows, problems, ImportPlanner.backlogDates(analysis.file.rows), items)
    }

    private data class Totals(
        val matched: Int = 0,
        val ambiguous: Int = 0,
        val stubs: Int = 0,
        val toAdd: Int = 0,
        val present: Int = 0,
        val leftAlone: Int = 0,
        val statuses: Int = 0,
        val ratings: Int = 0,
        val blocked: Int = 0,
        val skipped: Int = 0,
    ) {
        fun add(d: RowDiff) = copy(toAdd = toAdd + d.readingsToAdd, present = present + d.readingsPresent, leftAlone = leftAlone + d.readingsLeftAlone, statuses = statuses + d.statusChanges, ratings = ratings + d.ratings, blocked = blocked + d.ratingsBlocked)
    }
}

/** Reads the user's books, matches the file's rows and reads the matched books' state. */
suspend fun analyse(file: BookmoryFile, remote: BookmoryRemote, importer: BookmoryImporter): ImportAnalysis {
    val books = mutableListOf<ServerBook>()
    var page = 0
    while (true) {
        val result = remote.books(page, BOOKS_PAGE)
        books += result.items.map { it.toServerBook() }
        if (result.items.size < BOOKS_PAGE || books.size >= result.total || page >= MAX_BOOK_PAGES) break
        page++
    }
    val byId = books.associateBy { it.id }
    val matcher = BookmoryMatcher(byId.values.toList())
    val matches = file.rows.associate { it.line to matcher.match(it) }
    val ids = matches.values.flatMap {
        when (it) {
            is MatchResult.Found -> listOf(it.book.id)
            is MatchResult.Ambiguous -> it.candidates.map(ServerBook::id)
            MatchResult.NotFound -> emptyList()
        }
    }
    val states = importer.states(ids)
    val capabilities = try {
        remote.uploadTargets()
    } catch (e: io.github.ottershelf.core.network.ApiException) {
        if (e.code == 401) throw e
        UploadCapabilities()
    }
    val libraries = capabilities.libraries.filter { it.takesEpub }
    val ratingAccess = try {
        remote.ratingAccess()
    } catch (e: io.github.ottershelf.core.network.ApiException) {
        if (e.code == 401) throw e
        RatingAccess()
    }
    return ImportAnalysis(file, byId, matches, states, libraries, capabilities.canUploadToLibrary && libraries.isNotEmpty(), ratingAccess)
}

private const val BOOKS_PAGE = 200
private const val MAX_BOOK_PAGES = 500
