package io.github.ottershelf.core.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import io.github.ottershelf.core.model.FileProgress
import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.model.SaveProgress
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.network.ApiJson
import kotlin.math.abs

/**
 * Reading positions and sessions kept on the device, so a downloaded book reopens where it was left
 * with no connection, and whatever couldn't be sent is sent later.
 *
 * BookOrbit's progress endpoint is last-write-wins: it stamps its own time, takes no client
 * timestamp, and a save can move the read status (a drop of 10+ points counts as a re-read, so an
 * old position arriving late can turn Read into Re-reading) as well as the Kobo bookmark. So a
 * position saved without a connection is only sent if the server still has the position this device
 * last saw there (the baseline). If something else moved it meanwhile, nothing is sent and the reader
 * asks which of the two to keep. Timestamps aren't used to pick a winner: the server can't take one,
 * KOReader stamps push time, Kobo its own clock.
 *
 * Reading sessions carry their own times and the server de-duplicates them by id, so they are queued
 * and retried. Sessions from reading whose position is in dispute (or was discarded) lose their end
 * point, so they count for time and stats but can't move the read status.
 *
 * [onSent]: the server has taken positions or sessions from this device (called on a background
 * thread; once per [syncAll]).
 *
 * [onQueued]: something waits to be sent and no send of it is under way, so the app makes sure it
 * goes even if the process dies first (the AppContainer schedules the WorkManager flush, whose
 * enqueue is thread-safe). Called on the caller's thread when a position or session is stored with
 * nobody sending it now (offline: `sendingNow = false`), and on the IO thread, under the mutex,
 * after a [push] or [syncAll] that left something unsent (no answer, a server error, a refusal
 * that keeps it). Not after a send that worked: a reader that saved again meanwhile sends that
 * itself. Never once the sign-in the send started under has ended.
 *
 * Records are per account ([accountKey], the Session's), in SharedPreferences. The store must be a
 * process-wide singleton: readers and the WorkManager sync worker share its open-file counts and
 * its mutex, which the conflict detection relies on.
 */
class ProgressStore(
    context: Context,
    private val accountKey: () -> String,
    private val api: ProgressRemote,
    private val onSent: () -> Unit = {},
    private val onQueued: () -> Unit = {},
    /** Where sends run (tests pass their own dispatcher). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * For a flush of the sessions [account] queued: true while the sign-in it started under lasts
     * (`AppContainer.stillSignedInAs`, the check NotesSync makes too). Asked before every send.
     */
    private val stillSignedInAs: (account: String) -> () -> Boolean = { account -> { accountKey() == account } },
) {

    /**
     * A reading position: [cfi] + [percentage] for the EPUB reader; [pageNumber] (1-based) +
     * [percentage] with no cfi for the page-based readers (comics, PDF; see [PageProgress]).
     */
    @Serializable
    data class Position(val cfi: String? = null, val percentage: Double = 0.0, val pageNumber: Int? = null) {
        fun sameAs(other: Position): Boolean =
            // The server stores percentage as float4, so compare with a tolerance. A page number only
            // counts when both sides have one: EPUB positions never do (the web, this app and KOReader
            // send none for reflowable files), so their comparison is exactly what it always was.
            cfi == other.cfi && abs(percentage - other.percentage) < 0.01 &&
                (pageNumber == null || other.pageNumber == null || pageNumber == other.pageNumber)

        companion object {
            fun of(server: FileProgress) = Position(server.cfi, server.percentage ?: 0.0, server.pageNumber)
            fun of(body: SaveProgress) = Position(body.cfi, body.percentage, body.pageNumber)
        }
    }

    @Serializable
    data class Record(
        val bookId: Long,
        /** What the server had when this device last read or wrote it. Null: never seen. */
        val baseline: Position? = null,
        /** Newest position reached on this device that the server doesn't have yet. */
        val pending: SaveProgress? = null,
        /** Device time [pending] was reached; also identifies it. */
        val pendingAt: Long = 0,
        /** Positions sent without an answer (the server may or may not have them): not a conflict. */
        val attempted: List<Position> = emptyList(),
        /** The server's position when it was found to have moved away from [baseline]. */
        val conflict: Position? = null,
        /** When that server position was last read there (ISO), for the prompt. */
        val conflictReadAt: String? = null,
    ) {
        val pendingPosition: Position? get() = pending?.let { Position.of(it) }

        /** Where to open when the server can't be asked. */
        val bestKnown: Position? get() = pendingPosition ?: baseline
    }

    @Serializable
    private data class QueuedSession(val fileId: Long, val bookId: Long, val session: ReadingSession)

    enum class Push { SENT, CONFLICT, FAILED, REJECTED, NOTHING }

    private enum class Check { CLEAN, ALREADY_THERE, CONFLICT }

    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val json get() = ApiJson
    private val mutex = Mutex()
    private val openFiles = HashMap<Long, Int>()

    // Under the mutex: during syncAll, what reached the server is reported once at the end of its
    // sends, not per item (the dashboard asks again each time it hears).
    private var batching = false
    private var batchSent = false

    /** The server has (or very likely has) something new from this device. */
    private fun reportSent() {
        if (batching) batchSent = true else onSent()
    }

    private fun key(fileId: Long) = "${accountKey()}|file|$fileId"
    private fun sessionsKey() = "${accountKey()}|sessions"

    // --- records -----------------------------------------------------------------------------

    @Synchronized
    fun get(fileId: Long): Record? {
        val raw = prefs.getString(key(fileId), null) ?: return null
        return runCatching { json.decodeFromString(Record.serializer(), raw) }.getOrNull()
    }

    @Synchronized
    private fun update(fileId: Long, bookId: Long, change: (Record) -> Record): Record {
        val next = change(get(fileId) ?: Record(bookId))
        prefs.edit().putString(key(fileId), json.encodeToString(Record.serializer(), next)).apply()
        return next
    }

    /** Files of this account with a position not yet on the server. */
    @Synchronized
    private fun pendingFiles(): List<Long> {
        val prefix = "${accountKey()}|file|"
        return prefs.all.keys.filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toLongOrNull() }
            .filter { get(it)?.pending != null }
    }

    /**
     * Whether a [syncAll] has something it could send: a pending position that isn't waiting for the
     * user to settle a conflict, or a queued session (those go even for a conflicted file, without
     * their end point). The sync worker retries while this is true.
     */
    @Synchronized
    fun hasUnsent(): Boolean =
        queuedSessions().isNotEmpty() || pendingFiles().any { get(it)?.conflict == null }

    /**
     * A reader has this file open. Background sync then leaves its baseline alone: the reader compares
     * against the position it opened at, and moving that under it would let an old position through.
     */
    @Synchronized
    fun opened(fileId: Long) {
        openFiles[fileId] = (openFiles[fileId] ?: 0) + 1
    }

    @Synchronized
    fun closed(fileId: Long) {
        val left = (openFiles[fileId] ?: 1) - 1
        if (left <= 0) openFiles.remove(fileId) else openFiles[fileId] = left
    }

    @Synchronized
    private fun isOpen(fileId: Long) = fileId in openFiles

    /** The server was just read (or written) and holds [position]. Leaves anything pending alone. */
    fun setBaseline(fileId: Long, bookId: Long, position: Position) {
        update(fileId, bookId) { it.copy(baseline = position, attempted = emptyList()) }
    }

    /**
     * Records a position reached on this device, before trying to send it. [sendingNow]: the caller
     * pushes it straight away (online), so the flush is left to that push's failure ([onQueued]).
     */
    fun savePending(fileId: Long, bookId: Long, body: SaveProgress, sendingNow: Boolean = false): Record =
        update(fileId, bookId) { it.copy(pending = body, pendingAt = System.currentTimeMillis()) }
            .also { if (!sendingNow) onQueued() }

    /** Keep this device's position: the next push sends it even though the server moved. */
    fun resolveKeepLocal(fileId: Long, bookId: Long, server: Position) {
        update(fileId, bookId) { it.copy(baseline = server, attempted = emptyList(), conflict = null, conflictReadAt = null) }
    }

    /** Keep the server's position: drop what this device had, and let its sessions not move the status. */
    fun resolveKeepServer(fileId: Long, bookId: Long, server: Position) {
        update(fileId, bookId) {
            it.copy(baseline = server, pending = null, pendingAt = 0, attempted = emptyList(), conflict = null, conflictReadAt = null)
        }
        stripSessionEnds(fileId)
    }

    /** What the reader should do on opening, given the server's position. */
    sealed interface Opening {
        /**
         * Start at [position]; [send]: a position read here is to be sent now; [atServer]: nothing was
         * pending; [inStep]: the server is known to hold the baseline (false: check before sending).
         */
        data class Start(val position: Position, val send: Boolean, val atServer: Boolean, val inStep: Boolean = true) : Opening
        /** Both moved: ask which to keep. */
        data class Ask(val mine: Position, val mineAt: Long, val server: Position, val serverReadAt: String?) : Opening
    }

    /**
     * The reader opened online and got the server's position: the same judgement as [reconcile]
     * (including this device's own unanswered sends), made in one step on the record as it is.
     * [before] is the record as it was when the server was asked: if a send (from the last reader,
     * or a sync) changed it meanwhile, the answer may predate it, so nothing is concluded from it.
     */
    fun decideOnOpen(fileId: Long, bookId: Long, server: FileProgress, before: Record?): Opening {
        val serverPos = Position.of(server)
        var result: Opening = Opening.Start(serverPos, send = false, atServer = true)
        var disputed = false
        update(fileId, bookId) { current ->
            val pending = current.pendingPosition
            when {
                current.baseline != before?.baseline || current.pendingAt != (before?.pendingAt ?: 0) -> {
                    result = Opening.Start(current.bestKnown ?: serverPos, send = false, atServer = false, inStep = false)
                    current
                }
                pending == null -> current.copy(baseline = serverPos, attempted = emptyList())
                pending.sameAs(serverPos) -> {
                    // Sent before, answer lost: in step, and its sessions are this device's own.
                    result = Opening.Start(serverPos, send = false, atServer = false)
                    current.copy(baseline = serverPos, pending = null, pendingAt = 0, attempted = emptyList(), conflict = null, conflictReadAt = null)
                }
                current.conflict == null &&
                    ((current.baseline ?: Position()).sameAs(serverPos) || current.attempted.any { it.sameAs(serverPos) }) -> {
                    // Read on here while nothing else moved it: carry on from here and send it.
                    result = Opening.Start(pending, send = true, atServer = false)
                    current.copy(baseline = serverPos, attempted = emptyList())
                }
                else -> {
                    // Recorded, so a send already under way (a sync) stops rather than overwrites.
                    result = Opening.Ask(pending, current.pendingAt, serverPos, server.lastReadAt ?: server.updatedAt)
                    disputed = true
                    current.copy(conflict = serverPos, conflictReadAt = server.lastReadAt ?: server.updatedAt)
                }
            }
        }
        if (disputed) stripSessionEnds(fileId)
        return result
    }

    /** The saved percentage for a book grid's progress bar (0..100), or null. */
    fun percentage(fileId: Long): Double? = get(fileId)?.bestKnown?.percentage

    // --- sending -----------------------------------------------------------------------------

    /**
     * Sends [fileId]'s pending position. With [checked], first makes sure the server still has the
     * baseline and records a conflict instead of sending if it doesn't; without, sends as is (the
     * reader does that while it knows it's in step with the server).
     */
    suspend fun push(fileId: Long, checked: Boolean): Push = withContext(io) {
        mutex.withLock {
            val signedIn = stillSignedInAs(accountKey())
            pushLocked(fileId, checked).also { result ->
                // Left unsent (no answer, a server error, a refusal that keeps it, or a newer save
                // behind a failed send): the flush is the safety net.
                if ((result == Push.FAILED || result == Push.REJECTED) && signedIn() && hasUnsent()) onQueued()
            }
        }
    }

    private suspend fun pushLocked(fileId: Long, checked: Boolean): Push {
        var check = checked
        var sentSomething = false
        // Loops only when the server turned out to hold this device's own position already and a
        // newer one is waiting behind it.
        repeat(3) {
            val record = get(fileId) ?: return Push.NOTHING
            val body = record.pending ?: return if (sentSomething) Push.SENT else Push.NOTHING
            if (record.conflict != null) return Push.CONFLICT
            var posting = false
            try {
                if (check) {
                    when (reconcile(fileId, record, api.fileProgress(fileId))) {
                        Check.CLEAN -> {}
                        Check.CONFLICT -> return Push.CONFLICT
                        Check.ALREADY_THERE -> {
                            sentSomething = true
                            check = false
                            return@repeat // anything newer is sent next time round
                        }
                    }
                }
                posting = true
                api.saveProgress(fileId, body)
                markSent(fileId, record.pendingAt, Position.of(body))
                reportSent()
                return Push.SENT
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (posting && e.code >= 500) {
                    // The server (or a proxy in front of it) failed after perhaps storing it: BookOrbit
                    // saves the position before its Kobo and audiobook syncs. Treat as unanswered.
                    val sent = Position.of(body)
                    update(fileId, record.bookId) { it.copy(attempted = (it.attempted + sent).takeLast(MAX_ATTEMPTED)) }
                    reportSent()
                    return Push.FAILED
                }
                if (e.code == 400 || e.code == 403 || e.code == 404) {
                    // Rejected for good (invalid, no access, or the file is gone): don't retry forever.
                    update(fileId, record.bookId) { if (it.pendingAt == record.pendingAt) it.copy(pending = null, pendingAt = 0) else it }
                }
                return Push.REJECTED
            } catch (e: Exception) {
                if (posting) {
                    // No answer: the server may have it. Remember it so it isn't later taken for
                    // someone else's position.
                    val sent = Position.of(body)
                    update(fileId, record.bookId) { it.copy(attempted = (it.attempted + sent).takeLast(MAX_ATTEMPTED)) }
                }
                return Push.FAILED
            }
        }
        return if (sentSomething) Push.SENT else Push.NOTHING
    }

    /** Compares the server's position with what this device expects there. */
    private fun reconcile(fileId: Long, record: Record, server: FileProgress): Check {
        val serverPos = Position.of(server)
        if ((record.baseline ?: Position()).sameAs(serverPos)) return Check.CLEAN
        if (record.attempted.any { it.sameAs(serverPos) }) {
            // One of this device's own sends got there after all.
            setBaseline(fileId, record.bookId, serverPos)
            if (record.pendingPosition?.sameAs(serverPos) != true) return Check.CLEAN
        }
        if (record.pendingPosition?.sameAs(serverPos) == true) {
            markSent(fileId, record.pendingAt, serverPos)
            reportSent() // it got there without an answer (or after a failure)
            return Check.ALREADY_THERE
        }
        // Something else moved it. Judged against the record as it is now: a newer pending position
        // may have been saved during the request, but the server moved away from the baseline either way.
        // The record may also have been settled meanwhile (the user answered the prompt).
        var outcome = Check.ALREADY_THERE
        var arrived = false
        update(fileId, record.bookId) { current ->
            when {
                current.pending == null -> current
                current.pendingPosition!!.sameAs(serverPos) -> {
                    arrived = true
                    current.copy(baseline = serverPos, pending = null, pendingAt = 0, attempted = emptyList())
                }
                (current.baseline ?: Position()).sameAs(serverPos) || current.attempted.any { it.sameAs(serverPos) } -> {
                    outcome = Check.CLEAN
                    current.copy(baseline = serverPos, attempted = emptyList())
                }
                else -> {
                    outcome = Check.CONFLICT
                    current.copy(conflict = serverPos, conflictReadAt = server.lastReadAt ?: server.updatedAt)
                }
            }
        }
        if (arrived) reportSent()
        if (outcome == Check.CONFLICT) stripSessionEnds(fileId)
        return outcome
    }

    private fun markSent(fileId: Long, pendingAt: Long, sent: Position) {
        val bookId = get(fileId)?.bookId ?: return
        update(fileId, bookId) {
            // A newer position may have been saved while this one was in flight: keep that pending.
            if (it.pendingAt == pendingAt) it.copy(baseline = sent, pending = null, pendingAt = 0, attempted = emptyList())
            else it.copy(baseline = sent, attempted = emptyList())
        }
    }

    private fun dropPending(fileId: Long) {
        val bookId = get(fileId)?.bookId ?: return
        update(fileId, bookId) { it.copy(pending = null, pendingAt = 0, conflict = null, conflictReadAt = null) }
    }

    // --- sessions ----------------------------------------------------------------------------

    @Synchronized
    private fun queuedSessions(): List<QueuedSession> {
        val raw = prefs.getString(sessionsKey(), null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(QueuedSession.serializer()), raw) }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun editSessions(change: (List<QueuedSession>) -> List<QueuedSession>) {
        val next = change(queuedSessions()).takeLast(MAX_QUEUED_SESSIONS)
        prefs.edit().putString(sessionsKey(), json.encodeToString(ListSerializer(QueuedSession.serializer()), next)).apply()
    }

    /** This file's queued sessions keep their time but can no longer move the read status. */
    private fun stripSessionEnds(fileId: Long) {
        editSessions { list -> list.map { if (it.fileId == fileId) it.copy(session = it.session.copy(endProgress = null)) else it } }
    }

    /**
     * Queued, to be sent by the next [syncAll] (which checks for conflicts first). [sendingNow]: the
     * caller runs that sync straight away (online), so the flush is left to its failure ([onQueued]).
     */
    fun enqueueSession(fileId: Long, bookId: Long, body: ReadingSession, sendingNow: Boolean = false) {
        val disputed = get(fileId)?.conflict != null
        editSessions { it + QueuedSession(fileId, bookId, if (disputed) body.copy(endProgress = null) else body) }
        if (!sendingNow) onQueued()
    }

    /**
     * Oldest first; stops at the first network failure, and before the next send once the sign-in
     * it started under has ended (a sign-out, and maybe another account's sign-in, while a request
     * was out): the rest are this account's, kept for its next sign-in. Returns false if some are
     * still queued.
     */
    private suspend fun flushSessionsLocked(): Boolean {
        val signedIn = stillSignedInAs(accountKey())
        for (item in queuedSessions()) {
            if (!signedIn()) return false
            val body = if (get(item.fileId)?.conflict != null) item.session.copy(endProgress = null) else item.session
            try {
                api.saveSession(item.fileId, body)
                reportSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val permanent = e is ApiException && e.code in 400..499 && e.code != 401 && e.code != 408 && e.code != 429
                if (!permanent) return false
            }
            editSessions { list -> list.filterNot { it.session.sessionId == item.session.sessionId } }
        }
        return true
    }

    // --- background sync ---------------------------------------------------------------------

    /**
     * Everything waiting to go to the server, plus fresh baselines for [downloadedFiles] (book id to
     * file id) so they open at the latest position once the connection is gone. Stops quietly at the
     * first network failure; a file the server refuses is skipped (and its pending position dropped
     * if the file is gone or out of reach) so it can't hold up the rest. A pass that stopped, or
     * left something to try again, with something still unsent calls [onQueued].
     *
     * Returns false when the pass stopped before the end, so [downloadedFiles] weren't all asked (a
     * server error on one of them doesn't stop it: that one keeps the baseline it had).
     */
    suspend fun syncAll(downloadedFiles: Collection<Pair<Long, Long>>): Boolean = withContext(io) {
        mutex.withLock {
            val signedIn = stillSignedInAs(accountKey())
            val pass = syncAllLocked(downloadedFiles)
            if (pass != Pass.DONE && signedIn() && hasUnsent()) onQueued()
            pass != Pass.STOPPED
        }
    }

    /** How far a [syncAll] got. */
    private enum class Pass {
        DONE,
        /** To the end, with something left to try again (a server error on a file, a refusal that keeps it). */
        RETRY,
        /** Stopped early: a network failure, or the sign-in ended. */
        STOPPED,
    }

    /** [syncAll] under the mutex. */
    private suspend fun syncAllLocked(downloadedFiles: Collection<Pair<Long, Long>>): Pass {
        var finished = true
        try {
            batching = true
            try {
                // Find conflicts first: a conflicted file's sessions go without an end point.
                val clean = ArrayList<Long>()
                for (fileId in pendingFiles()) {
                    val record = get(fileId) ?: continue
                    if (record.conflict != null || record.pending == null) continue
                    val server = try {
                        api.fileProgress(fileId)
                    } catch (e: ApiException) {
                        if (e.code == 403 || e.code == 404) dropPending(fileId) else finished = false
                        continue
                    }
                    if (reconcile(fileId, record, server) == Check.CLEAN) clean += fileId
                }
                // Sessions before positions, so a Reading/Read change is dated by the session's end.
                if (!flushSessionsLocked()) return Pass.STOPPED
                for (fileId in clean) {
                    when (pushLocked(fileId, checked = false)) {
                        Push.FAILED -> return Pass.STOPPED
                        Push.REJECTED -> finished = false
                        else -> {}
                    }
                }
            } finally {
                // Once for the lot, and before the baselines (a request per downloaded book).
                batching = false
                if (batchSent) {
                    batchSent = false
                    onSent()
                }
            }
            for ((bookId, fileId) in downloadedFiles) {
                if (isOpen(fileId) || get(fileId)?.pending != null) continue
                val server = try {
                    api.fileProgress(fileId)
                } catch (e: ApiException) {
                    continue
                }
                setBaseline(fileId, bookId, Position.of(server))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or the server is unhappy: try again on the next trigger.
            return Pass.STOPPED
        }
        return if (finished) Pass.DONE else Pass.RETRY
    }

    companion object {
        private const val MAX_QUEUED_SESSIONS = 500
        private const val MAX_ATTEMPTED = 5
    }
}
