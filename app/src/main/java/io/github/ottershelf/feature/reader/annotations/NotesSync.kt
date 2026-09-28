package io.github.ottershelf.feature.reader.annotations

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.network.ApiJson
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Each book's notes on this device (`files/reader-notes/<account>/<bookId>.json`): the server's
 * lists as last fetched and the writes still waiting ([NotesFile]). Every change is written before
 * the send it prepares. One per process ([get]); its lock covers the file reads and writes only,
 * never a network call.
 */
class ReaderNotesStore(private val root: File) {

    private val lock = Mutex()

    suspend fun read(account: String, bookId: Long): NotesFile = lock.withLock { load(file(account, bookId)) }

    /** Whether anything was ever stored for the book (its lists can show offline). */
    suspend fun exists(account: String, bookId: Long): Boolean = withContext(Dispatchers.IO) { file(account, bookId).isFile }

    /** Applies [transform] and stores the result; returns it. */
    suspend fun update(account: String, bookId: Long, transform: (NotesFile) -> NotesFile): NotesFile = lock.withLock {
        val target = file(account, bookId)
        val next = transform(load(target))
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(ApiJson.encodeToString(NotesFile.serializer(), next))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
        next
    }

    /** The books of [account] with writes waiting. */
    suspend fun booksWithQueue(account: String): List<Long> = lock.withLock {
        withContext(Dispatchers.IO) {
            File(root, account).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
                .mapNotNull { f -> f.name.removeSuffix(".json").toLongOrNull()?.takeIf { load(f).queue.isNotEmpty() } }
        }
    }

    private suspend fun load(file: File): NotesFile = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext NotesFile()
        runCatching { ApiJson.decodeFromString(NotesFile.serializer(), file.readText()) }.getOrElse { NotesFile() }
    }

    private fun file(account: String, bookId: Long) = File(File(root, account), "$bookId.json")

    companion object {
        @Volatile
        private var instance: ReaderNotesStore? = null

        fun get(context: Context): ReaderNotesStore = instance ?: synchronized(this) {
            instance ?: ReaderNotesStore(File(context.applicationContext.filesDir, "reader-notes")).also { instance = it }
        }
    }
}

/**
 * Sends one account's waiting note writes, oldest first, and fetches a book's lists. One book at a
 * time per process (the reader and [ReaderNotesWorker] may both try; the second waits).
 *
 * - A failed connection, a 5xx, 401, 408 or 429 stops the pass; everything stays for the next one.
 * - Another 4xx drops that write ([onRejected]); a 404 for a delete counts as done.
 * - An annotation create is marked attempted before it's sent. An attempted one is looked for on
 *   the server (same cfi and text, an annotation this device didn't have) before it is sent again,
 *   because the server would store a second copy.
 * - [stillSignedIn] is asked before every request (`AppContainer.stillSignedInAs(account)`): once
 *   [account] has signed out, the pass stops and the rest waits for its next sign-in, so nothing it
 *   queued goes out with another account's token, and no other account's lists are kept as its own.
 */
class NotesSync(
    private val store: ReaderNotesStore,
    private val remote: NotesRemote,
    private val account: String,
    private val stillSignedIn: () -> Boolean = { true },
) {

    /**
     * Sends [bookId]'s queue; true when nothing is left waiting. [onChanged] runs when something
     * reached the server, told whether a highlight did (not only bookmarks).
     */
    suspend fun flush(bookId: Long, onRejected: (String) -> Unit = {}, onChanged: (highlights: Boolean) -> Unit = {}): Boolean =
        lockFor(bookId).withLock {
            val pass = flushLocked(bookId, onRejected)
            if (pass.changed) onChanged(pass.highlights)
            pass.empty
        }

    /**
     * Sends every book's queue ([ReaderNotesWorker]); false when something is left to try again.
     * The books are listed again after each pass, so one written to meanwhile (the worker's unique
     * work is KEEP, so that write's own schedule did nothing) goes in this call too. Once [account]
     * has signed out, true: the rest waits for its next sign-in (ReaderNotesScheduler.start), not
     * for retries while someone else may be signed in.
     */
    suspend fun flushAll(onChanged: (bookId: Long, highlights: Boolean) -> Unit = { _, _ -> }): Boolean {
        var done = true
        val tried = HashSet<Long>()
        while (true) {
            if (!stillSignedIn()) return true
            val books = store.booksWithQueue(account).filterNot { it in tried }
            if (books.isEmpty()) break
            for (bookId in books) {
                tried += bookId
                val empty = try {
                    flush(bookId, onChanged = { highlights -> onChanged(bookId, highlights) })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!empty) done = false
            }
        }
        // Something written since to a book already sent: another run.
        if (done && store.booksWithQueue(account).isNotEmpty()) done = false
        return done || !stillSignedIn()
    }

    /**
     * Sends the queue, then fetches the book's annotations and bookmarks and keeps them. Throws when
     * the lists can't be fetched (offline, or signed out meanwhile): the stored ones stay.
     */
    suspend fun refresh(bookId: Long, onRejected: (String) -> Unit = {}, onChanged: (highlights: Boolean) -> Unit = {}): NotesFile =
        lockFor(bookId).withLock {
            val pass = flushLocked(bookId, onRejected)
            if (pass.changed) onChanged(pass.highlights)
            if (!stillSignedIn()) throw IOException("Signed out")
            val annotations = remote.annotations(bookId)
            val bookmarks = remote.bookmarks(bookId)
            if (!stillSignedIn()) throw IOException("Signed out")
            store.update(account, bookId) { f ->
                val resolved = f.resolveAttempted(annotations)
                resolved.copy(annotations = annotations, bookmarks = bookmarks).withLiveIds()
            }
        }

    private sealed interface Outcome {
        class Done(val apply: (NotesFile) -> NotesFile) : Outcome
        class Dropped(val message: String?) : Outcome
        data object Stop : Outcome
    }

    /** A pass: the queue is [empty] after it, something [changed] on the server, a highlight did ([highlights]). */
    private class Pass(val empty: Boolean, val changed: Boolean, val highlights: Boolean)

    private suspend fun flushLocked(bookId: Long, onRejected: (String) -> Unit): Pass {
        var changed = false
        var highlights = false
        while (true) {
            if (!stillSignedIn()) return Pass(empty = false, changed, highlights)
            var picked: NoteOp? = null
            val file = store.update(account, bookId) { f ->
                val op = f.queue.firstOrNull() ?: return@update f
                picked = op
                val marked = when (op) {
                    is NoteOp.CreateAnnotation -> if (op.attempted) op else op.copy(attempted = true)
                    is NoteOp.CreateBookmark -> if (op.attempted) op else op.copy(attempted = true)
                    else -> op
                }
                if (marked === op) f else f.copy(queue = f.queue.map { if (it.opId == op.opId) marked else it })
            }
            val op = picked ?: return Pass(empty = true, changed, highlights)
            when (val outcome = send(bookId, op, file)) {
                is Outcome.Done -> {
                    changed = true
                    if (op !is NoteOp.CreateBookmark && op !is NoteOp.DeleteBookmark) highlights = true
                    store.update(account, bookId) { f -> outcome.apply(f).withoutOp(op.opId) }
                }
                is Outcome.Dropped -> {
                    store.update(account, bookId) { f ->
                        val without = f.withoutOp(op.opId)
                        // Whatever waited on a highlight or bookmark that never made it goes too.
                        when (op) {
                            is NoteOp.CreateAnnotation -> without.withoutRefsTo(op.localId)
                            is NoteOp.CreateBookmark -> without.withoutRefsTo(op.localId)
                            else -> without
                        }
                    }
                    outcome.message?.let(onRejected)
                }
                Outcome.Stop -> return Pass(empty = false, changed, highlights)
            }
        }
    }

    private suspend fun send(bookId: Long, op: NoteOp, file: NotesFile): Outcome {
        fun target(id: Long) = file.ids[id] ?: id
        return try {
            when (op) {
                is NoteOp.CreateAnnotation -> {
                    if (op.attempted) {
                        val listed = remote.annotations(bookId)
                        if (!stillSignedIn()) return Outcome.Stop
                        val resolved = file.resolveAttempted(listed, only = op.opId)
                        val match = resolved.ids[op.localId]
                        if (match != null) {
                            val server = resolved.annotations
                            return Outcome.Done { f -> f.copy(annotations = server, ids = f.ids + (op.localId to match)) }
                        }
                    }
                    val created = remote.createAnnotation(bookId, op.draft)
                    Outcome.Done { f -> f.withAnnotation(created).copy(ids = f.ids + (op.localId to created.id)) }
                }
                is NoteOp.UpdateAnnotation -> {
                    val id = target(op.id)
                    if (id < 0) return Outcome.Dropped(null)
                    val updated = remote.updateAnnotation(bookId, id, op.note, op.color, op.style)
                    Outcome.Done { f -> f.withAnnotation(updated) }
                }
                is NoteOp.DeleteAnnotation -> {
                    val id = target(op.id)
                    if (id < 0) return Outcome.Dropped(null)
                    try {
                        remote.deleteAnnotation(bookId, id)
                    } catch (e: ApiException) {
                        if (e.code != 404) throw e
                    }
                    Outcome.Done { f -> f.copy(annotations = f.annotations.filterNot { it.id == id }) }
                }
                is NoteOp.CreateBookmark -> {
                    val created = remote.createBookmark(bookId, op.cfi, op.title)
                    Outcome.Done { f ->
                        f.copy(bookmarks = f.bookmarks.filterNot { it.id == created.id } + created, ids = f.ids + (op.localId to created.id))
                    }
                }
                is NoteOp.DeleteBookmark -> {
                    val id = target(op.id)
                    if (id < 0) return Outcome.Dropped(null)
                    try {
                        remote.deleteBookmark(bookId, id)
                    } catch (e: ApiException) {
                        if (e.code != 404) throw e
                    }
                    Outcome.Done { f -> f.copy(bookmarks = f.bookmarks.filterNot { it.id == id }) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            when {
                e.code == 401 || e.code == 408 || e.code == 429 || e.code >= 500 -> Outcome.Stop
                // The annotation is gone (deleted elsewhere): nothing left to change.
                e.code == 404 && op is NoteOp.UpdateAnnotation -> Outcome.Dropped(null)
                else -> Outcome.Dropped(e.message)
            }
        } catch (_: IOException) {
            Outcome.Stop
        } catch (_: Exception) {
            // An answer that couldn't be read: the create stays attempted, so it's looked for first.
            Outcome.Stop
        }
    }

    private fun lockFor(bookId: Long): Mutex = locks.getOrPut("$account/$bookId") { Mutex() }

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

private fun NotesFile.withoutOp(opId: String) = copy(queue = queue.filterNot { it.opId == opId })

private fun NotesFile.withAnnotation(a: Annotation) =
    copy(annotations = if (annotations.any { it.id == a.id }) annotations.map { if (it.id == a.id) a else it } else annotations + a)

/**
 * Keeps the local -> server ids still of use: the item is on the server's lists, or a waiting write
 * names the local id. Not cleared once the queue is empty: an open note dialog, delete confirmation
 * or popup may still hold a local id whose create this refresh sent.
 */
internal fun NotesFile.withLiveIds(): NotesFile {
    val live = annotations.mapTo(HashSet()) { it.id } + bookmarks.map { it.id }
    val named = queue.flatMapTo(HashSet()) { op ->
        when (op) {
            is NoteOp.CreateAnnotation -> listOf(op.localId)
            is NoteOp.UpdateAnnotation -> listOf(op.id)
            is NoteOp.DeleteAnnotation -> listOf(op.id)
            is NoteOp.CreateBookmark -> listOf(op.localId)
            is NoteOp.DeleteBookmark -> listOf(op.id)
        }
    }
    return copy(ids = ids.filter { (local, server) -> server in live || local in named })
}

/** Drops the waiting writes that name [localId] (its create never reached the server). */
internal fun NotesFile.withoutRefsTo(localId: Long): NotesFile = copy(
    queue = queue.filterNot { op ->
        when (op) {
            is NoteOp.CreateAnnotation -> op.localId == localId
            is NoteOp.UpdateAnnotation -> op.id == localId
            is NoteOp.DeleteAnnotation -> op.id == localId
            is NoteOp.CreateBookmark -> op.localId == localId
            is NoteOp.DeleteBookmark -> op.id == localId
        }
    },
)

/**
 * Attempted annotation creates (only [only], when given) that [server] shows arrived: one with the
 * same cfi and text that this device didn't have and hasn't matched to another create. Those are
 * mapped (ids) and leave the queue; [server] becomes the list only when [only] matched.
 */
internal fun NotesFile.resolveAttempted(server: List<Annotation>, only: String? = null): NotesFile {
    val known = annotations.mapTo(HashSet()) { it.id } + ids.values
    val taken = HashSet<Long>()
    var ids = ids
    val left = queue.filter { op ->
        if (op !is NoteOp.CreateAnnotation || !op.attempted || (only != null && op.opId != only)) return@filter true
        val match = server.firstOrNull {
            it.id !in known && it.id !in taken && it.cfi == op.draft.cfi && it.text == op.draft.text
        } ?: return@filter true
        taken += match.id
        ids = ids + (op.localId to match.id)
        only != null // looked up for a send: the caller removes it with the rest of its outcome
    }
    return copy(queue = left, ids = ids, annotations = if (only != null && taken.isNotEmpty()) server else annotations)
}
