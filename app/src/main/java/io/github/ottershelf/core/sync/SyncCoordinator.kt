package io.github.ottershelf.core.sync

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.ottershelf.core.network.Connectivity

/**
 * When progress and sessions get sent, and when downloaded books' positions are refreshed.
 *
 * In process: [sync], called when the app comes to the foreground, when a reading session ends, and
 * 2 s after the connection comes back (see [start]). What waits to be sent goes every time; the
 * downloaded books' positions (the baselines, a request per book) at most every
 * [BASELINE_INTERVAL_MS]. In the background: [SyncScheduler]'s WorkManager jobs call [syncNow].
 * The one-time flush is only the safety net for what couldn't be sent in process: [ProgressStore]
 * asks for it after a failed send, and [sync] does when it finds itself offline with something
 * waiting.
 *
 * [signedIn] is the Session's `isSignedIn` (thread-safe: also asked on IO); [scope] is the app
 * scope (Main.immediate); [downloadedFiles] lists (book id, file id) of this account's downloads
 * and is called on an IO thread.
 */
class SyncCoordinator(
    private val signedIn: () -> Boolean,
    private val progress: ProgressStore,
    private val connectivity: Connectivity,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
    private val downloadedFiles: () -> List<Pair<Long, Long>>,
    /** An activity of the app is on screen (the connection's return then sends at once). */
    private val inForeground: () -> Boolean = { true },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val isOnline: () -> Boolean = { connectivity.online.value },
    private val baselineIntervalMs: Long = BASELINE_INTERVAL_MS,
    /**
     * Where local reads that decode every record, and the periodic job's scheduling, run (tests
     * pass their own dispatcher).
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private var returnedJob: Job? = null

    /**
     * Once, at app start: follows the connection, and keeps the periodic sync scheduled. When the
     * connection comes back with the app on screen, what waits is sent (and the baselines too if
     * they're due); in the background (the process kept alive by a worker, say) only when something
     * is waiting: a network that flickers, or each wake from Doze, must not make requests.
     */
    fun start() {
        connectivity.watch(onReturned = {
            returnedJob?.cancel()
            returnedJob = scope.launch {
                delay(2_000) // let the connection settle before sending things (and flickers pass)
                if (inForeground()) sync(force = true)
                else if (withContext(io) { progress.hasUnsent() }) sync(force = true, baselines = false)
            }
        })
        // A WorkManager enqueue and a SharedPreferences read: not on the main thread, where this
        // runs (Application.onCreate).
        scope.launch(io) { if (signedIn()) scheduler.schedulePeriodic() }
    }

    /** Signed in: everything is sent and every downloaded book's position fetched, whatever the interval. */
    fun onSignedIn() {
        scheduler.schedulePeriodic()
        scope.launch {
            lastBaselines = null
            syncOnMain(force = true, baselines = true)
        }
    }

    /**
     * Any thread. Cancels the jobs, and the in-process sync under way with its request in flight:
     * what it hasn't sent stays queued for this account and can't go out with the next one's token.
     */
    fun onSignedOut() {
        scheduler.cancelAll()
        scope.launch {
            syncJob?.cancel()
            syncJob = null
            syncAgain = false
            againBaselines = false
            lastBaselines = null
        }
    }

    // Main thread only (all touched inside [scope], which runs on the main thread).
    /**
     * When the baselines were last fetched in this process ([elapsedRealtime]), by a pass that got
     * to the end; null: not yet.
     */
    private var lastBaselines: Long? = null
    private var syncJob: Job? = null
    private var syncAgain = false
    private var againBaselines = false

    /**
     * Sends reading progress and sessions saved while offline and, when [baselines] is set and the
     * last fetch is [baselineIntervalMs] old, refreshes downloaded books' positions from the server
     * (a GET each; the reader's end-of-session sync skips that, the periodic one covers it). Any
     * thread (the work runs on the main thread).
     *
     * [force]: run now for what is queued. Without it, and with the baselines not due, a sync runs
     * only when something waits to be sent. A forced call during a sync runs another one straight
     * after (it may have queued something new). Offline, the WorkManager flush is scheduled when
     * something waits (the connection may have gone since the caller looked).
     */
    fun sync(force: Boolean = false, baselines: Boolean = true) {
        scope.launch { syncOnMain(force, baselines) }
    }

    private fun syncOnMain(force: Boolean, baselines: Boolean) {
        if (!signedIn()) return
        if (!isOnline()) {
            // What waits (maybe queued by a caller that still saw a connection) goes with the
            // WorkManager flush once there is one.
            flushLaterIfUnsent()
            return
        }
        val baselinesDue = baselines && lastBaselines.let { it == null || elapsedRealtime() - it >= baselineIntervalMs }
        if (syncJob?.isActive == true) {
            if (force || baselinesDue) {
                syncAgain = true
                againBaselines = againBaselines || baselinesDue
            }
            return
        }
        if (!force && !baselinesDue) {
            // Only what waits to be sent. hasUnsent decodes every record: not on the main thread.
            scope.launch {
                if (withContext(io) { progress.hasUnsent() }) syncOnMain(force = true, baselines = false)
            }
            return
        }
        syncJob = scope.launch {
            var withBaselines = baselinesDue
            do {
                syncAgain = false
                // Stamped before the pass, so calls during it don't ask for the baselines again.
                val before = lastBaselines
                val stamp = if (withBaselines) elapsedRealtime().also { lastBaselines = it } else null
                val completed = progress.syncAll(if (withBaselines) listDownloads() else emptyList())
                // Cut short (the connection went, say): not a fetch, so the next resume or
                // reconnect asks again. Unless sign-in has since reset the time itself.
                if (stamp != null && !completed && lastBaselines == stamp) lastBaselines = before
                withBaselines = againBaselines
                againBaselines = false
            } while (syncAgain && isOnline())
            // A pass asked for meanwhile, but the connection went: that's the flush's now.
            if (syncAgain) flushLaterIfUnsent()
        }
    }

    /**
     * Enqueues the WorkManager flush when something waits to be sent. All on IO: hasUnsent decodes
     * every record, and the enqueue (thread-safe, like signedIn) has no place on the main thread.
     */
    private fun flushLaterIfUnsent() {
        scope.launch(io) {
            if (progress.hasUnsent() && signedIn()) scheduler.enqueueFlush()
        }
    }

    /** For the WorkManager jobs: one sync, awaited. Safe alongside [sync] (ProgressStore's mutex). */
    suspend fun syncNow(baselines: Boolean) {
        if (!signedIn()) return
        progress.syncAll(if (baselines) listDownloads() else emptyList())
    }

    private suspend fun listDownloads(): List<Pair<Long, Long>> = withContext(io) {
        runCatching { downloadedFiles() }.getOrDefault(emptyList())
    }

    companion object {
        /**
         * How often, at most, the app's own syncs fetch every downloaded book's position (the
         * periodic job, sign-in and a finished download fetch them regardless; a pass cut short
         * doesn't count). The baselines matter for a downloaded book later opened offline (opened
         * online, the reader asks the server itself), and they are the progress bars on the
         * Downloaded screen (`ProgressStore.percentage`), online too: within the interval, both can
         * be behind a position read elsewhere since.
         */
        internal const val BASELINE_INTERVAL_MS = 30_000L
    }
}
