package io.github.ottershelf.feature.bookmory

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.network.isAppAttestRefusal
import io.github.ottershelf.core.tracking.Patch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Where one row's readings go: a book the user has, or a placeholder uploaded into a library. */
sealed interface ImportTarget {
    data class Book(val bookId: Long) : ImportTarget
    data class Stub(val stub: StubBook, val libraryId: Long) : ImportTarget
}

/** One row to import: what it wants, its book, and whether the account may rate that book. */
data class ImportItem(val plan: RowPlan, val target: ImportTarget, val rating: RatingRule = RatingRule.Allowed)

data class ImportProblem(val line: Int, val title: String, val kind: Kind, val detail: String? = null) {
    enum class Kind {
        /** The placeholder was uploaded before but deleted since: the row is left alone. */
        STUB_DELETED,
        /** The book was deleted since the preview. */
        BOOK_GONE,
        /** The server refused or failed the placeholder's upload ([detail] says why). */
        UPLOAD_FAILED,
        /** The upload is still being processed on the server; running the import again finishes it. */
        UPLOAD_PENDING,
        /** A reading, status or rating was refused ([detail] is the server's message). */
        WRITE_FAILED,
        /** The account may not rate books: ratings were left out. */
        RATING_REFUSED,
        /** The account isn't an editor in the book's library ([detail]), so its rating was left out. */
        RATING_NOT_EDITOR,
    }
}

data class ImportSummary(
    val rows: Int,
    val rowsDone: Int,
    val stubsCreated: List<String>,
    val stubsFound: Int,
    val readingsAdded: Int,
    val readingsPresent: Int,
    /** Left alone: the book has a reading for them with other dates ([RowDiff.readingsLeftAlone]). */
    val readingsLeftAlone: Int = 0,
    val statusesSet: Int,
    val ratingsSet: Int,
    /** Ratings left out because the account may not set them (see [problems] for the libraries). */
    val ratingsLeftOut: Int = 0,
    val problems: List<ImportProblem>,
    /** The connection went (or the account signed out) part-way: running again finishes the rest. */
    val interrupted: Boolean,
) {
    val nothingChanged: Boolean get() = stubsCreated.isEmpty() && readingsAdded == 0 && statusesSet == 0 && ratingsSet == 0
}

/** Only one import at a time in the process, whichever screen started it. */
object BookmoryImportGuard {
    private val running = AtomicBoolean(false)
    fun tryStart(): Boolean = running.compareAndSet(false, true)
    fun finish() = running.set(false)
    val isRunning: Boolean get() = running.get()
}

/**
 * Runs an import: for each row, its book (a placeholder uploaded first when it has none), then that
 * book's state read again from the server, then only the writes still missing
 * ([ImportPlanner.diff]). Nothing is edited or deleted, so running it again (after it finished, or
 * after it was cut off anywhere) adds nothing twice:
 * - a placeholder is uploaded under a key made from the row and with the same bytes every time, so
 *   the server answers a repeated upload with the book it already made;
 * - readings are compared with the book's own, statuses and ratings are set only over none.
 *
 * A refusal (4xx/5xx) is reported for its row and the rest go on; a lost connection, or the account
 * signed out, stops the run ([ImportSummary.interrupted]).
 */
class BookmoryImporter(
    private val remote: BookmoryRemote,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {

    /** The book's status, rating and every reading, or null when it is gone. */
    suspend fun state(bookId: Long): BookState? {
        val status = remote.bookStatus(bookId) ?: return null
        val readings = mutableListOf<ServerReading>()
        var page = 1
        while (true) {
            val list = remote.readings(bookId, page, READINGS_PAGE)
            readings += list.items
            if (list.items.isEmpty() || readings.size >= list.total || page >= MAX_READING_PAGES) break
            page++
        }
        return BookState(status.status, status.rating, readings.distinctBy { it.id })
    }

    /** [state] for each of [bookIds], [PARALLEL] at a time; a book that is gone is left out. */
    suspend fun states(bookIds: Collection<Long>): Map<Long, BookState> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        bookIds.distinct().map { id -> async { gate.withPermit { id to state(id) } } }
            .awaitAll()
            .mapNotNull { (id, s) -> s?.let { id to it } }
            .toMap()
    }

    /** What a run has done so far; [summary] once it ends. */
    private class Tally {
        val created = mutableListOf<String>()
        var found = 0
        var added = 0
        var present = 0
        var leftAlone = 0
        var statuses = 0
        var ratings = 0
        var ratingsLeftOut = 0
        var done = 0
        var mayRate = true
        val problems = mutableListOf<ImportProblem>()

        fun problem(row: BookmoryRow, kind: ImportProblem.Kind, detail: String? = null) {
            problems += ImportProblem(row.line, row.title, kind, detail)
        }

        fun summary(rows: Int, interrupted: Boolean) = ImportSummary(
            rows = rows,
            rowsDone = done,
            stubsCreated = created.toList(),
            stubsFound = found,
            readingsAdded = added,
            readingsPresent = present,
            readingsLeftAlone = leftAlone,
            statusesSet = statuses,
            ratingsSet = ratings,
            ratingsLeftOut = ratingsLeftOut,
            problems = problems.toList(),
            interrupted = interrupted,
        )
    }

    /** Thrown to end a run early: the connection went, or the account signed out. */
    private class Interrupted : Exception()

    suspend fun run(items: List<ImportItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportSummary {
        val tally = Tally()
        for ((index, item) in items.withIndex()) {
            try {
                importRow(item, tally)
            } catch (_: Interrupted) {
                return tally.summary(items.size, interrupted = true)
            }
            onProgress(index + 1, items.size)
        }
        return tally.summary(items.size, interrupted = false)
    }

    /**
     * One row: its book, then its writes. A refusal is reported and the row counts as done; a lost
     * connection or a 401 ends the run ([Interrupted]).
     */
    private suspend fun importRow(item: ImportItem, tally: Tally) {
        val row = item.plan.row
        try {
            val bookId = bookFor(item, tally)
            if (bookId != null) writeRow(item, bookId, tally)
            tally.done++
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 401) throw Interrupted()
            tally.problem(row, ImportProblem.Kind.WRITE_FAILED, e.message)
            tally.done++
        } catch (_: IOException) {
            throw Interrupted()
        }
    }

    /** The row's book: its match, or its placeholder (uploaded now, or found under its key); null when there is none. */
    private suspend fun bookFor(item: ImportItem, tally: Tally): Long? = when (val target = item.target) {
        is ImportTarget.Book -> target.bookId
        is ImportTarget.Stub -> when (val result = stubOnce(target)) {
            is StubResult.Made -> {
                if (result.new) tally.created += target.stub.title else tally.found++
                result.bookId
            }
            is StubResult.Problem -> {
                tally.problem(item.plan.row, result.kind, result.detail)
                null
            }
        }
    }

    /** Reads [bookId]'s state again and makes only the writes it still lacks. */
    private suspend fun writeRow(item: ImportItem, bookId: Long, tally: Tally) {
        val row = item.plan.row
        val state = state(bookId)
        if (state == null) {
            val kind = if (item.target is ImportTarget.Stub) ImportProblem.Kind.STUB_DELETED else ImportProblem.Kind.BOOK_GONE
            tally.problem(row, kind)
            return
        }
        val diff = ImportPlanner.diff(item.plan, state, mayRate = item.rating == RatingRule.Allowed)
        tally.present += diff.readingsPresent
        tally.leftAlone += diff.readingsLeftAlone
        if (diff.ratingsBlocked > 0) {
            tally.ratingsLeftOut++
            (item.rating as? RatingRule.NotEditor)?.let { tally.problem(row, ImportProblem.Kind.RATING_NOT_EDITOR, it.library) }
        }
        val known = state.readings.mapTo(mutableSetOf()) { it.id }
        for (action in diff.actions) {
            try {
                apply(item, bookId, action, known, tally)
            } catch (e: ApiException) {
                if (e.code == 401) throw e
                if (action is ImportAction.Rate && e.code == 403) {
                    tally.ratingsLeftOut++
                    tally.mayRate = false
                    tally.problem(row, ImportProblem.Kind.RATING_REFUSED, e.message)
                } else {
                    tally.problem(row, ImportProblem.Kind.WRITE_FAILED, e.message)
                }
            }
        }
    }

    /**
     * One write. A reading counts as added only once the server shows a new one: the reading the
     * attempt route answers with, or an open reading the status route made (read back); anything
     * else counts as left alone.
     */
    private suspend fun apply(item: ImportItem, bookId: Long, action: ImportAction, known: MutableSet<Long>, tally: Tally) {
        when (action) {
            is ImportAction.AddReading -> {
                val r = action.reading
                val stored = remote.addReading(bookId, r.startedOn, r.endedOn, r.outcome!!)
                if (known.add(stored.id)) tally.added++ else tally.leftAlone++
            }
            is ImportAction.StartReading -> {
                remote.setStatus(bookId, "reading", action.startedOn?.let { Patch.Set(it) } ?: Patch.Clear)
                val opened = state(bookId)?.readings?.firstOrNull { it.isOpen && it.id !in known }
                if (opened != null) {
                    known += opened.id
                    tally.added++
                } else {
                    tally.leftAlone++
                }
            }
            ImportAction.WantToRead -> {
                remote.setStatus(bookId, "want_to_read")
                tally.statuses++
            }
            is ImportAction.SetStatus -> {
                // Worked out again from what the server now holds, so the status route never adds a
                // reading of its own.
                val now = state(bookId) ?: return
                val status = ImportPlanner.statusAfter(item.plan, now.status, now.readings) ?: return
                remote.setStatus(bookId, status)
                tally.statuses++
            }
            is ImportAction.Rate -> if (tally.mayRate) {
                remote.setRating(bookId, action.rating)
                tally.ratings++
            } else {
                tally.ratingsLeftOut++
            }
        }
    }

    private sealed interface StubResult {
        /** [new]: this run uploaded it (the server hadn't seen the key). */
        data class Made(val bookId: Long, val new: Boolean) : StubResult
        data class Problem(val kind: ImportProblem.Kind, val detail: String? = null) : StubResult
    }

    /** This run's placeholders by key, so two rows for the same book (two editions, say) make one. */
    private val stubs = HashMap<String, CompletableDeferred<StubResult>>()
    private val stubsLock = Mutex()

    /**
     * [target]'s placeholder, made once per run: a row whose key was already uploaded this run (or is
     * being uploaded by another of the parallel rows) gets that book. That matters most once sessions
     * are refused, since a whole upload has no key of its own and makes a new book every time.
     */
    private suspend fun stubOnce(target: ImportTarget.Stub): StubResult {
        val key = target.stub.idempotencyKey
        val (mine, deferred) = stubsLock.withLock {
            stubs[key]?.let { false to it } ?: (true to CompletableDeferred<StubResult>().also { stubs[key] = it })
        }
        if (!mine) {
            return when (val earlier = deferred.await()) {
                is StubResult.Made -> earlier.copy(new = false)
                is StubResult.Problem -> earlier
            }
        }
        return try {
            uploadStub(target).also { deferred.complete(it) }
        } catch (e: Throwable) {
            // Not made (the run stops, or was cancelled): a later run may try again.
            stubsLock.withLock { stubs.remove(key) }
            deferred.completeExceptionally(e)
            throw e
        }
    }

    /** The server keeps upload sessions for its own apps (BookOrbit 3.7+, App Attest): placeholders go whole. */
    @Volatile
    private var sessionsRefused = false

    /**
     * Uploads [target]'s placeholder in an upload session, or picks up the one already made under its
     * key: a finished one gives its book at once, one cut off part-way goes on from the bytes the
     * server has. A 4xx from `POST uploads` (the key used by a different file, say) is reported. A
     * server that keeps sessions for its own apps gets the file whole ([uploadWhole]).
     */
    private suspend fun uploadStub(target: ImportTarget.Stub): StubResult {
        val bytes = StubEpub.build(target.stub)
        if (sessionsRefused) return uploadWhole(target, bytes)
        val body = CreateUploadBody(
            filename = target.stub.filename,
            sizeBytes = bytes.size.toLong(),
            idempotencyKey = target.stub.idempotencyKey,
            target = UploadTargetBody(libraryId = target.libraryId),
            contentType = StubEpub.CONTENT_TYPE,
            sha256 = StubEpub.sha256Hex(bytes),
        )
        var session = try {
            remote.createUpload(body)
        } catch (e: ApiException) {
            if (e.isAppAttestRefusal) {
                sessionsRefused = true
                return uploadWhole(target, bytes)
            }
            if (e.code == 401 || e.code >= 500) throw e
            return StubResult.Problem(ImportProblem.Kind.UPLOAD_FAILED, e.message)
        }
        val new = session.status == RECEIVING && session.receivedBytes == 0L
        var polls = 0
        var lastReceived = -1L
        var stalls = 0
        while (true) {
            when (session.status) {
                RECEIVING -> {
                    // A server that keeps answering "receiving" without taking more isn't asked forever.
                    if (session.receivedBytes <= lastReceived) {
                        if (++stalls > MAX_STALLS) return StubResult.Problem(ImportProblem.Kind.UPLOAD_FAILED, session.errorMessage)
                    } else {
                        stalls = 0
                    }
                    lastReceived = session.receivedBytes
                    val received = session.receivedBytes.toInt().coerceIn(0, bytes.size)
                    session = if (received < bytes.size) {
                        remote.uploadChunk(session.id, received.toLong(), bytes.copyOfRange(received, bytes.size))
                    } else {
                        remote.completeUpload(session.id)
                    }
                }
                PROCESSING -> {
                    if (++polls > MAX_POLLS) return StubResult.Problem(ImportProblem.Kind.UPLOAD_PENDING)
                    pause(POLL_MS)
                    session = remote.upload(session.id)
                }
                COMPLETED -> {
                    val bookId = session.bookId ?: return StubResult.Problem(ImportProblem.Kind.UPLOAD_FAILED, session.errorMessage)
                    return StubResult.Made(bookId, new)
                }
                else -> return StubResult.Problem(ImportProblem.Kind.UPLOAD_FAILED, session.errorMessage ?: session.status)
            }
        }
    }

    /** [target]'s placeholder in one request (the web's upload): a new book every time, so only after sessions were refused. */
    private suspend fun uploadWhole(target: ImportTarget.Stub, bytes: ByteArray): StubResult = try {
        StubResult.Made(remote.uploadWhole(target.libraryId, target.stub.filename, StubEpub.CONTENT_TYPE, bytes), new = true)
    } catch (e: ApiException) {
        if (e.code == 401 || e.code >= 500) throw e
        StubResult.Problem(ImportProblem.Kind.UPLOAD_FAILED, e.message)
    }

    companion object {
        const val RECEIVING = "receiving"
        const val PROCESSING = "processing"
        const val COMPLETED = "completed"
        private const val READINGS_PAGE = 100
        private const val MAX_READING_PAGES = 50
        private const val POLL_MS = 1_000L
        private const val MAX_POLLS = 90
        /** Rounds in a row the server may stay at the same received size (one is the completion call). */
        private const val MAX_STALLS = 3
        private const val PARALLEL = 6
    }
}
