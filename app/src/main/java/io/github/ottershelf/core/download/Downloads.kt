package io.github.ottershelf.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.sync.ProgressStore
import okhttp3.Call
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipException

/** A book kept on the device for offline reading (`meta.json` in its folder). */
@Serializable
data class DownloadedBook(
    val bookId: Long,
    val fileId: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    /** Size of the file as downloaded. */
    val sizeBytes: Long = 0,
    /** The server's recorded size at download time; it changes when the server rewrites the file. */
    val serverSizeBytes: Long? = null,
    val downloadedAt: Long = 0,
    /**
     * The kept file's format, lower case: it is `book.<format>` in the folder. Downloads made before
     * other formats were kept have no such field and are EPUBs (`book.epub` + `info.json`), which is
     * what the default reads them as: the old layout is the EPUB layout, so nothing is migrated.
     */
    val format: String = "epub",
) {
    val isEpub: Boolean get() = format.equals("epub", ignoreCase = true)
}

/**
 * What the data layer has to tell the user about downloads (it shows no UI itself). The shell shows
 * these as snackbars while a screen is visible; when nothing is collecting [Downloads.events], the
 * result is posted as a notification instead.
 */
sealed interface DownloadEvent {
    val bookId: Long
    /** The book's title as downloaded, or null if it has none. */
    val title: String?

    data class Completed(override val bookId: Long, override val title: String?) : DownloadEvent

    /** [message] is the error as the server or the network gave it (English, not localised). */
    data class Failed(override val bookId: Long, override val title: String?, val message: String) : DownloadEvent
}

/**
 * A download waiting for (or being run by) its [DownloadWorker], kept on disk because WorkManager's
 * input data is too small for a book page. [target] is fixed when the download starts: an account
 * switch mid-download mustn't move it.
 */
@Serializable
internal data class DownloadRequest(
    val accountKey: String,
    val book: BookDetail,
    val file: BookFile,
    val target: String,
)

/** A download that can't succeed by trying again (as opposed to a dropped connection). */
internal class PermanentDownloadException(message: String) : IOException(message)

/**
 * Books downloaded for offline reading, in app-private storage:
 * `files/downloads/<account>/<bookId>/` holding the original file as `book.<format>` (`book.epub`,
 * `book.pdf`, `book.cbz`, `book.mobi`...; [OfflineFiles.name]), for an EPUB also the server's
 * `info.json` for it (what the reader's streaming loader asks for first), `detail.json` for the book
 * page, the cover and thumbnail, and `meta.json` ([DownloadedBook], with the format), written last so
 * its presence means the download is complete. Per account, so a second user signing in on the same
 * device doesn't see (or read) someone else's. Any format a reader here opens can be kept except CBR
 * and CB7 (BookFormats.canKeepOffline: the server extracts those, the phone can't), so an offline
 * comic is always a CBZ.
 *
 * Each download runs in a [DownloadWorker] (unique WorkManager work per account and book, CONNECTED
 * constraint), promoted to a dataSync foreground service with a progress notification and a Cancel
 * action, so it survives leaving the app. [active] mirrors WorkManager's view of those works.
 */
class Downloads(
    private val context: Context,
    private val accountKey: () -> String,
    private val api: Api,
    private val progress: ProgressStore,
    private val scope: CoroutineScope,
) {
    /** [total] is -1 while unknown; [waiting]: queued (for a connection, or behind other work). */
    data class Progress(val done: Long, val total: Long, val waiting: Boolean = false)

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val notifications = DownloadNotifications(context)

    /** Downloads queued or in progress for this account, by book id. */
    val active: StateFlow<Map<Long, Progress>> by lazy {
        workManager.getWorkInfosByTagFlow(TAG)
            .map { infos -> activeFrom(infos) }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    private val _version = MutableStateFlow(0)
    /** Bumped whenever a download completes or is removed. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _count = MutableStateFlow<Int?>(null)
    /** How many books this account has downloaded, counted off the main thread. */
    val count: StateFlow<Int?> = _count.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    /**
     * Downloads that finished or failed (not cancelled ones). Collect only while a screen is shown,
     * and call [shown] once each has been shown.
     */
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    /** Results handed to [events] that the shell hasn't confirmed showing yet (guarded by itself). */
    private val unshown = ArrayList<DownloadEvent>()

    init {
        // The shell stopped collecting (the app left the screen): whatever it didn't get to show,
        // results still in the flow's buffer or cut off mid-snackbar, becomes a notification.
        scope.launch { _events.subscriptionCount.collect { if (it == 0) postUnshown() } }
    }

    /** The shell has shown [event] (its snackbar came and went). */
    fun shown(event: DownloadEvent) {
        synchronized(unshown) { unshown.remove(event) }
    }

    private fun postUnshown() {
        val left = synchronized(unshown) { unshown.toList().also { unshown.clear() } }
        left.forEach(notifications::showResult)
    }

    /** Held by [delete] and by a download's final swap, so a finishing download can't undo a removal. */
    private val swapLock = Any()

    private val countGeneration = AtomicInteger()

    fun recount() {
        // Counts run in parallel; only the newest may publish, so an older scan can't win the race.
        val generation = countGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            val n = runCatching { list().size }.getOrNull()
            if (generation == countGeneration.get()) _count.value = n
        }
    }

    private fun changed() {
        _version.update { it + 1 }
        recount()
    }

    private val base get() = File(context.filesDir, "downloads")
    private val root: File get() = File(base, accountKey())
    private val requests get() = File(context.filesDir, "download-requests")

    private fun dir(bookId: Long) = File(root, bookId.toString())

    /**
     * Once per process, when its first activity is created (AppContainer). Leftovers of downloads
     * the process didn't live to finish (or clean up after), except those of downloads WorkManager
     * still has queued or running. An older copy set aside by a replacement that was killed halfway
     * is put back rather than deleted.
     */
    fun cleanUpLeftovers() {
        scope.launch(Dispatchers.IO) {
            val infos = runCatching { workManager.getWorkInfosByTagFlow(TAG).first() }.getOrNull()
                ?: return@launch // can't tell what's running: leave everything
            val busy = infos.filterNot { it.state.isFinished }.mapNotNull { info ->
                val account = info.tags.firstNotNullOfOrNull { it.removePrefixOrNull(ACCOUNT_TAG) } ?: return@mapNotNull null
                val book = info.tags.firstNotNullOfOrNull { it.removePrefixOrNull(BOOK_TAG) } ?: return@mapNotNull null
                "$account-$book"
            }.toSet()
            base.listFiles()?.forEach { account ->
                account.listFiles()?.forEach { f ->
                    val match = LEFTOVER.matchEntire(f.name) ?: return@forEach
                    val bookId = match.groupValues[2]
                    if ("${account.name}-$bookId" in busy) return@forEach
                    val target = File(account, bookId)
                    if (match.groupValues[1] == "old" && !target.exists() && File(f, META).isFile) {
                        f.renameTo(target)
                    } else {
                        runCatching { f.deleteRecursively() }
                    }
                }
            }
            requests.listFiles()?.forEach { f ->
                if (f.name.removeSuffix(".json") !in busy) runCatching { f.delete() }
            }
        }
    }

    fun get(bookId: Long): DownloadedBook? {
        val meta = File(dir(bookId), META)
        if (!meta.isFile) return null
        return runCatching { ApiJson.decodeFromString(DownloadedBook.serializer(), meta.readText()) }.getOrNull()
    }

    fun list(): List<DownloadedBook> =
        root.listFiles()?.mapNotNull { f -> f.name.toLongOrNull()?.let { get(it) } }.orEmpty()
            .sortedBy { (it.title ?: "").lowercase() }

    fun detail(bookId: Long): BookDetail? {
        val file = File(dir(bookId), DETAIL)
        if (!file.isFile) return null
        return runCatching { ApiJson.decodeFromString(BookDetail.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * Keeps the offline copy of the book page current (title, description, status). The book page,
     * the quick view and an edit can save at once, so under [swapLock], like [delete] and a
     * download's swap: two saves can't share the temp file (and garble the page), and one can't land
     * in a folder being removed (and leave it behind).
     */
    fun saveDetail(book: BookDetail) {
        val json = ApiJson.encodeToString(BookDetail.serializer(), book).toByteArray()
        synchronized(swapLock) {
            val dir = dir(book.id)
            if (!File(dir, META).isFile) return
            runCatching { writeReplacing(File(dir, DETAIL), json) }
        }
    }

    /**
     * [book]'s details were edited on this device (feature.bookedit): a downloaded copy's page, and
     * the title and authors the Downloaded grid shows, follow; with [artwork] so do the cover and
     * thumbnail kept with it (fetched again at the new version; removed when the book has no cover
     * any more). Nothing for a book that isn't downloaded. A cover that can't be fetched now keeps
     * the old one. Blocking: call off the main thread.
     */
    fun updateKept(book: BookDetail, artwork: Boolean) {
        val kept = get(book.id) ?: return
        val hasCover = book.coverSource != null
        val cover = if (artwork && hasCover) runCatching { api.bytes(api.coverUrl(book.id, book.updatedAt)) }.getOrNull() else null
        val thumb = if (artwork && hasCover) runCatching { api.bytes(api.thumbnailUrl(book.id, book.updatedAt)) }.getOrNull() else null
        val dir = dir(book.id)
        synchronized(swapLock) {
            // Removed (or replaced by another download) meanwhile: leave it be.
            if (get(book.id)?.fileId != kept.fileId) return
            saveDetail(book)
            val meta = kept.copy(title = book.title, authors = book.authors.map { it.name })
            runCatching { writeReplacing(File(dir, META), ApiJson.encodeToString(DownloadedBook.serializer(), meta).toByteArray()) }
            if (artwork) {
                if (!hasCover) {
                    File(dir, COVER).delete()
                    File(dir, THUMB).delete()
                } else {
                    cover?.let { runCatching { writeReplacing(File(dir, COVER), it) } }
                    thumb?.let { runCatching { writeReplacing(File(dir, THUMB), it) } }
                }
            }
        }
        changed()
    }

    private fun writeReplacing(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Can't write to storage")
        }
    }

    /** Whether the downloaded copy is still the server's current [file]. */
    fun isCurrent(downloaded: DownloadedBook, file: BookFile): Boolean =
        downloaded.fileId == file.id &&
            (downloaded.serverSizeBytes == null || file.sizeBytes == null || downloaded.serverSizeBytes == file.sizeBytes)

    fun cover(bookId: Long): File? = File(dir(bookId), COVER).takeIf { it.isFile }
    fun thumbnail(bookId: Long): File? = File(dir(bookId), THUMB).takeIf { it.isFile } ?: cover(bookId)

    /**
     * The downloaded copy of this exact EPUB file, for the foliate reader's streaming loader, or null
     * to stream it from the server (also for a downloaded file of another format: see [openOffline]).
     */
    fun openEpub(bookId: Long, fileId: Long): LocalEpub? {
        val meta = get(bookId) ?: return null
        if (meta.fileId != fileId || !meta.isEpub) return null
        return runCatching { LocalEpub(dir(bookId)) }.getOrNull()
    }

    /**
     * The downloaded copy of this exact file in any format (the original file and its format), or
     * null to read it from the server. Reads a file: call off the main thread.
     */
    fun openOffline(bookId: Long, fileId: Long): OfflineCopy? {
        val meta = get(bookId) ?: return null
        if (meta.fileId != fileId) return null
        val file = File(dir(bookId), OfflineFiles.name(meta.format)).takeIf { it.isFile } ?: return null
        return OfflineCopy(bookId, fileId, meta.format.lowercase(), file)
    }

    fun isDownloading(bookId: Long) = bookId in active.value

    /** The book page a queued or running download was started with (to list it), or null. Reads a file. */
    fun queuedBook(bookId: Long): BookDetail? = readRequest(accountKey(), bookId)?.book

    /**
     * Downloads [file] of [book] in the background; progress is published on [active], the result
     * on [events]. Does nothing if this book is already downloading. Any thread. [file] must be one
     * that can be kept offline (BookFormats.pickOfflineFile picks it; a CBR or CB7 is refused).
     */
    fun start(book: BookDetail, file: BookFile) {
        if (!BookFormats.canKeepOffline(file.format)) {
            report(DownloadEvent.Failed(book.id, book.title, "${file.format?.uppercase() ?: "This"} files can't be kept offline"))
            return
        }
        val account = accountKey()
        val request = DownloadRequest(account, book, file, dir(book.id).absolutePath)
        scope.launch(Dispatchers.IO) {
            try {
                writeRequest(request)
            } catch (e: IOException) {
                report(DownloadEvent.Failed(book.id, book.title, e.message ?: "Can't write to storage"))
                return@launch
            }
            val work = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(DownloadWorker.KEY_ACCOUNT to account, DownloadWorker.KEY_BOOK_ID to book.id))
                .addTag(TAG)
                .addTag(ACCOUNT_TAG + account)
                .addTag(BOOK_TAG + book.id)
                .build()
            // KEEP: a download of this book already queued or running carries on.
            workManager.enqueueUniqueWork(workName(account, book.id), ExistingWorkPolicy.KEEP, work)
        }
    }

    fun cancel(bookId: Long) {
        val account = accountKey()
        workManager.cancelUniqueWork(workName(account, bookId))
        scope.launch(Dispatchers.IO) { runCatching { requestFile(account, bookId).delete() } }
    }

    /** On sign-out: nothing of one account may finish into another's. */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    /**
     * Removes the downloaded copy, stopping a download of it. Cancelling the work only queues the
     * stop, so a download already finishing could still swap its copy in afterwards: its request
     * file, deleted here first under [swapLock], tells it not to (see [download]). Call off the
     * main thread.
     */
    fun delete(bookId: Long) {
        val account = accountKey()
        workManager.cancelUniqueWork(workName(account, bookId))
        synchronized(swapLock) {
            runCatching { requestFile(account, bookId).delete() }
            dir(bookId).deleteRecursively()
        }
        changed()
    }

    // --- for DownloadWorker ------------------------------------------------------------------

    internal fun readRequest(account: String, bookId: Long): DownloadRequest? {
        val file = requestFile(account, bookId)
        if (!file.isFile) return null
        return runCatching { ApiJson.decodeFromString(DownloadRequest.serializer(), file.readText()) }.getOrNull()
    }

    internal fun deleteRequest(request: DownloadRequest) {
        runCatching { requestFile(request.accountKey, request.book.id).delete() }
    }

    internal fun onCompleted(request: DownloadRequest) {
        deleteRequest(request)
        changed()
        report(DownloadEvent.Completed(request.book.id, request.book.title))
        // So the book opens at the latest position even if the next open is offline. In the app
        // scope, as the Nexus did: the worker (and "Downloading" on the book page) ends now, not
        // after a full sync. If the process dies first, the periodic sync's baselines cover it.
        if (request.accountKey == accountKey()) {
            val file = request.book.id to request.file.id
            scope.launch { progress.syncAll(listOf(file)) }
        }
    }

    internal fun onFailed(request: DownloadRequest, message: String) {
        deleteRequest(request)
        report(DownloadEvent.Failed(request.book.id, request.book.title, message))
    }

    /**
     * To whoever is watching [events]; with nobody watching, as a notification. Kept in [unshown]
     * until the shell confirms it, so leaving the app mid-snackbar still posts it.
     */
    private fun report(event: DownloadEvent) {
        val handedOver = synchronized(unshown) {
            (_events.subscriptionCount.value > 0 && _events.tryEmit(event)).also { if (it) unshown += event }
        }
        if (!handedOver) notifications.showResult(event)
    }

    /**
     * Downloads [request] into its target folder, reporting (bytes done, total or -1) to
     * [onProgress] every [PUBLISH_EVERY] bytes. Cancelling the calling coroutine (the worker being
     * stopped) cancels the HTTP request in flight: that aborts the transfer at once, even mid-read.
     * Returns false, having changed nothing, if the download was stopped or its book removed while
     * it was finishing up.
     */
    internal suspend fun download(request: DownloadRequest, onProgress: (Long, Long) -> Unit): Boolean = coroutineScope {
        val current = AtomicReference<Call?>()
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                current.get()?.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val track: (Call) -> Unit = { call ->
                    current.set(call)
                    if (!isActive) call.cancel() // stopped just before this request started
                }
                download(request.book, request.file, File(request.target), track, onProgress) {
                    requestFile(request.accountKey, request.book.id).isFile
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    /** [stillWanted] is asked under [swapLock] just before the swap: false leaves everything as it was. */
    private suspend fun download(
        book: BookDetail,
        file: BookFile,
        target: File,
        track: (Call) -> Unit,
        onProgress: (Long, Long) -> Unit,
        stillWanted: () -> Boolean,
    ): Boolean {
        val bookId = book.id
        val job = currentCoroutineContext().job
        val root = target.parentFile!!
        if (!root.isDirectory && !root.mkdirs()) throw PermanentDownloadException("Can't write to storage")
        val tmp = File(root, ".part-$bookId-${System.nanoTime()}")
        if (!tmp.mkdirs()) throw PermanentDownloadException("Can't write to storage")
        val format = BookFormats.normalize(file.format) ?: "epub"
        val isEpub = format == "epub"
        if (!BookFormats.canKeepOffline(format)) throw PermanentDownloadException("${format.uppercase()} files can't be kept offline")
        try {
            // What the reader asks for first; fetched now so the book opens with no connection.
            if (isEpub) File(tmp, INFO).writeBytes(api.epubInfo(bookId, file.id, track))

            job.ensureActive()
            val kept = File(tmp, OfflineFiles.name(format))
            var whole = false
            api.downloadFile(file.id, track) { length, body ->
                val total = if (length > 0) length else file.sizeBytes ?: -1
                if (total > 0 && root.usableSpace < total + SPACE_MARGIN) throw PermanentDownloadException("Not enough free space")
                var done = 0L
                var lastPublished = 0L
                onProgress(0, total)
                kept.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        job.ensureActive()
                        val n = body.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastPublished >= PUBLISH_EVERY || done == total) {
                            lastPublished = done
                            onProgress(done, total)
                        }
                    }
                }
                // Cut short (the connection dropped at the end of a stream): try again.
                if (length > 0 && done < length) throw IOException("The download was cut short")
                // Every byte is known to be here only when the response or the server's record says how many.
                whole = done == length || done == file.sizeBytes
            }
            if (!isEpub) {
                OfflineFiles.problem(format, kept, whole)?.let { throw PermanentDownloadException(it) }
            } else openDownloadedEpub(tmp, whole).use { local ->
                // A truncated or non-EPUB response would only fail later, offline, in the reader.
                // (Looked up like the server does, so odd-but-readable zips aren't turned away.)
                local.open("META-INF/container.xml")?.stream?.close() ?: throw PermanentDownloadException("Not an EPUB file")
                // The info and the file are two requests: if the file changed on the server in
                // between (metadata written back into it, say), the section sizes wouldn't match.
                if (!local.matchesInfo()) {
                    File(tmp, INFO).writeBytes(api.epubInfo(bookId, file.id, track))
                    if (!LocalEpub(tmp).use { it.matchesInfo() }) {
                        throw PermanentDownloadException("The book changed on the server while downloading; try again")
                    }
                }
            }

            job.ensureActive()
            runCatching { api.bytes(api.coverUrl(bookId, book.updatedAt), track) }.getOrNull()?.let { File(tmp, COVER).writeBytes(it) }
            runCatching { api.bytes(api.thumbnailUrl(bookId, book.updatedAt), track) }.getOrNull()?.let { File(tmp, THUMB).writeBytes(it) }
            File(tmp, DETAIL).writeText(ApiJson.encodeToString(BookDetail.serializer(), book))
            val meta = DownloadedBook(
                bookId = bookId,
                fileId = file.id,
                title = book.title,
                authors = book.authors.map { it.name },
                sizeBytes = kept.length(),
                serverSizeBytes = file.sizeBytes,
                downloadedAt = System.currentTimeMillis(),
                format = format,
            )
            File(tmp, META).writeText(ApiJson.encodeToString(DownloadedBook.serializer(), meta))

            job.ensureActive() // cancelled or removed while finishing up
            // The work's cancellation arrives some time after a removal: the request file, which
            // [delete] and [cancel] remove, says whether this copy is still wanted.
            synchronized(swapLock) {
                if (!job.isActive || !stillWanted()) return false
                // Replacing an older copy: set it aside rather than delete it first, so being killed
                // halfway leaves one complete copy or the other, never neither (cleanUpLeftovers puts
                // the old one back if the new one never arrived).
                val old = if (target.exists()) File(root, ".old-$bookId-${System.nanoTime()}") else null
                if (old != null && !target.renameTo(old)) throw PermanentDownloadException("Can't write to storage")
                if (!tmp.renameTo(target)) {
                    old?.renameTo(target)
                    throw PermanentDownloadException("Can't write to storage")
                }
                old?.deleteRecursively()
            }
            return true
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun requestFile(account: String, bookId: Long) = File(requests, "$account-$bookId.json")

    private fun writeRequest(request: DownloadRequest) {
        val dir = requests
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't write to storage")
        val file = requestFile(request.accountKey, request.book.id)
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(ApiJson.encodeToString(DownloadRequest.serializer(), request))
        if (!tmp.renameTo(file)) throw IOException("Can't write to storage")
    }

    private fun activeFrom(infos: List<WorkInfo>): Map<Long, Progress> {
        val account = ACCOUNT_TAG + accountKey()
        val result = HashMap<Long, Progress>()
        for (info in infos) {
            if (info.state.isFinished || account !in info.tags) continue
            val bookId = info.tags.firstNotNullOfOrNull { it.removePrefixOrNull(BOOK_TAG)?.toLongOrNull() } ?: continue
            result[bookId] = Progress(
                done = info.progress.getLong(DownloadWorker.KEY_DONE, 0),
                total = info.progress.getLong(DownloadWorker.KEY_TOTAL, -1),
                waiting = info.state != WorkInfo.State.RUNNING,
            )
        }
        return result
    }

    private fun String.removePrefixOrNull(prefix: String): String? = if (startsWith(prefix)) substring(prefix.length) else null

    companion object {
        /** Every download work carries this tag, plus [ACCOUNT_TAG]<account> and [BOOK_TAG]<book id>. */
        const val TAG = "download"
        private const val ACCOUNT_TAG = "download-account:"
        private const val BOOK_TAG = "download-book:"

        private fun workName(account: String, bookId: Long) = "download:$account:$bookId"

        private val LEFTOVER = Regex("""\.(part|old)-(\d+)-\d+""")

        private const val META = "meta.json"
        private const val DETAIL = "detail.json"
        private const val INFO = "info.json"
        private const val COVER = "cover.jpg"
        private const val THUMB = "thumb.jpg"
        private const val PUBLISH_EVERY = 256 * 1024L
        private const val SPACE_MARGIN = 20L * 1024 * 1024

        /**
         * The EPUB downloaded into [dir], opened as the reader will open it. A file the phone's zip
         * reader refuses (not a zip at all, or entry names with `..` or a leading `/`, which Android
         * rejects though the server reads them) fails for good once it is known to have arrived
         * [whole]: fetching it again would bring the same bytes. Otherwise its end may be missing,
         * and the ZipException stays a reason to try again (DownloadWorker.isTransient).
         */
        internal fun openDownloadedEpub(dir: File, whole: Boolean): LocalEpub = try {
            LocalEpub(dir)
        } catch (e: ZipException) {
            if (whole) throw PermanentDownloadException("Not an EPUB file the phone can open") else throw e
        }
    }
}
