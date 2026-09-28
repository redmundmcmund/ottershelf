package io.github.ottershelf.feature.reader.annotations

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import io.github.ottershelf.appContainer
import io.github.ottershelf.core.util.IsoTime
import io.github.ottershelf.feature.notes.NoteChanges
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A note write the server refused ([message] is the server's). */
data class NotesRejected(val message: String)

/**
 * The open book's highlights, notes and bookmarks. Every write goes through the device queue
 * ([ReaderNotesStore]) and shows at once; it's sent straight away when online, otherwise by the
 * reader when the connection comes back or by [ReaderNotesWorker]. The lists stored on the device
 * show while the server's are fetched, and offline.
 *
 * [scope] outlives the reader (the app scope), so a write started just before closing still
 * finishes; [uiScope] follows the reader (the connection watch).
 */
internal class ReaderNotes(
    private val bookId: Long,
    private val store: ReaderNotesStore,
    private val sync: NotesSync,
    private val account: String,
    private val online: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val uiScope: CoroutineScope,
    private val schedule: () -> Unit,
    /** Something reached the server; [highlights]: a highlight did, not only bookmarks. */
    private val onChanged: (highlights: Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(NotesSnapshot())
    val state: StateFlow<NotesSnapshot> = _state.asStateFlow()

    private val _rejected = MutableSharedFlow<NotesRejected>(extraBufferCapacity = 8)
    val rejected: SharedFlow<NotesRejected> = _rejected.asSharedFlow()

    /** Local id -> server id, kept for the whole visit (a popup may still name a local one). */
    private val ids = ConcurrentHashMap<Long, Long>()

    fun start() {
        scope.launch {
            val stored = store.read(account, bookId)
            if (store.exists(account, bookId)) publish(stored)
            refresh()
        }
        uiScope.launch {
            online.drop(1).filter { it }.collect { refresh() }
        }
    }

    /** The server id for [id] once a local item has reached it. */
    fun resolve(id: Long): Long = ids[id] ?: id

    fun createAnnotation(draft: AnnotationDraft): Long {
        val localId = nextLocalId()
        val op = NoteOp.CreateAnnotation(newOpId(), localId, draft, createdAt = IsoTime.format(System.currentTimeMillis()))
        enqueue { f -> f.copy(queue = f.queue + op) }
        return localId
    }

    /** [note] empty clears it; null fields are left as they are. */
    fun updateAnnotation(id: Long, note: String? = null, color: String? = null, style: String? = null) {
        enqueue { f ->
            // Resolved against the stored ids too: a send since the caller got [id] may have mapped it.
            val target = f.ids[id] ?: resolve(id)
            val create = f.queue.firstOrNull { it is NoteOp.CreateAnnotation && it.localId == target && !it.attempted } as NoteOp.CreateAnnotation?
            if (create != null) {
                val d = create.draft
                val merged = create.copy(
                    draft = d.copy(
                        note = if (note != null) note.ifBlank { null } else d.note,
                        color = color ?: d.color,
                        style = style ?: d.style,
                    ),
                )
                f.copy(queue = f.queue.map { if (it.opId == create.opId) merged else it })
            } else {
                f.copy(queue = f.queue + NoteOp.UpdateAnnotation(newOpId(), target, note, color, style))
            }
        }
    }

    fun deleteAnnotation(id: Long) {
        enqueue { f ->
            val target = f.ids[id] ?: resolve(id)
            val unsent = f.queue.any { it is NoteOp.CreateAnnotation && it.localId == target && !it.attempted }
            if (unsent) f.withoutRefsTo(target) else f.copy(queue = f.queue + NoteOp.DeleteAnnotation(newOpId(), target))
        }
    }

    fun addBookmark(cfi: String, title: String) {
        val op = NoteOp.CreateBookmark(newOpId(), nextLocalId(), cfi, title, createdAt = IsoTime.format(System.currentTimeMillis()))
        enqueue { f -> f.copy(queue = f.queue + op) }
    }

    fun deleteBookmark(id: Long) {
        enqueue { f ->
            val target = f.ids[id] ?: resolve(id)
            val unsent = f.queue.any { it is NoteOp.CreateBookmark && it.localId == target && !it.attempted }
            if (unsent) f.withoutRefsTo(target) else f.copy(queue = f.queue + NoteOp.DeleteBookmark(newOpId(), target))
        }
    }

    private fun enqueue(transform: (NotesFile) -> NotesFile) {
        scope.launch {
            publish(store.update(account, bookId, transform))
            // Online it goes now, and the worker is scheduled only if that doesn't send everything.
            if (online.value) flush() else schedule()
        }
    }

    private suspend fun flush() {
        try {
            val empty = sync.flush(bookId, onRejected = { _rejected.tryEmit(NotesRejected(it)) }, onChanged = onChanged)
            // Stopped (5xx, offline): the worker sends the rest. Also when it was running as this
            // write was queued (KEEP leaves that run alone) and has finished since.
            if (!empty) schedule()
        } catch (e: CancellationException) {
            schedule()
            throw e
        } catch (_: Exception) {
            schedule() // the write is on the device: the worker tries again
        } finally {
            publish(store.read(account, bookId))
        }
    }

    private suspend fun refresh() {
        if (!online.value) {
            if (!_state.value.loaded) _state.value = _state.value.copy(loaded = true)
            return
        }
        try {
            publish(sync.refresh(bookId, onRejected = { _rejected.tryEmit(NotesRejected(it)) }, onChanged = onChanged))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or the server failed: the stored lists stay.
            publish(store.read(account, bookId))
        }
    }

    private fun publish(file: NotesFile) {
        ids.putAll(file.ids)
        _state.value = file.visible()
    }

    private companion object {
        private val lastLocal = AtomicLong(0)

        /** Negative and unique on this device (the server's ids are positive). */
        fun nextLocalId(): Long {
            val now = -System.currentTimeMillis()
            while (true) {
                val last = lastLocal.get()
                val next = if (now < last) now else last - 1
                if (lastLocal.compareAndSet(last, next)) return next
            }
        }

        fun newOpId(): String = UUID.randomUUID().toString()
    }
}

/**
 * Background delivery of note writes made offline: unique one-time work (KEEP) with a CONNECTED
 * constraint and backoff, so they go as soon as there's a network, even after the app was killed.
 */
object ReaderNotesScheduler {
    private const val WORK = "reader-notes-flush"

    /**
     * At the first screen (not in a worker's or boot's process) and on every sign-in ([account] is
     * the signed-in account key, null while signed out): schedules the flush when that account has writes waiting, as TrackingRepository.start
     * does for sessions. A run while signed out ends, and the reader only schedules for the book it
     * opens, so without this a book not opened again would never sync.
     */
    fun start(context: Context, account: Flow<String?>, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch {
            account.distinctUntilChanged().collect { key ->
                if (key != null && ReaderNotesStore.get(app).booksWithQueue(key).isNotEmpty()) enqueue(app)
            }
        }
    }

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReaderNotesWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * On sign-out (AppContainer), with the progress and tracking jobs: a run under way stops. The
     * writes stay on the device, and [start] schedules them again at that account's next sign-in.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
    }
}

/**
 * Sends every waiting note write of the signed-in account ([NotesSync.flushAll]); retries while
 * anything is left, unless that account has signed out meanwhile.
 */
class ReaderNotesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        if (!container.session.isSignedIn) return Result.success()
        val account = container.session.accountKey()
        val store = ReaderNotesStore.get(applicationContext)
        val sync = NotesSync(store, ApiNotesRemote(container.api), account, container.stillSignedInAs(account))
        val finished = sync.flushAll { bookId, highlights ->
            container.readingChanges.changed()
            if (highlights) NoteChanges.changed(bookId)
        }
        return if (finished) Result.success() else Result.retry()
    }
}
