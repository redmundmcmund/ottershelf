package io.github.ottershelf.core.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Background progress sync through WorkManager, so positions and sessions saved offline reach the
 * server even if the app is killed first, and Doze is respected:
 * - [enqueueFlush]: unique one-time work (KEEP) with a CONNECTED constraint, requested when
 *   something waits that no in-app send is taking (offline, a failed send; see [ProgressStore]
 *   `onQueued`). Thread-safe. It runs [SyncCoordinator.syncNow] without baselines and
 *   retries with backoff while something sendable is left.
 * - [schedulePeriodic]: every 12 hours while signed in, a full sync including fresh baselines for
 *   downloaded books, so they open at the latest position even when later read offline. The job
 *   already scheduled is kept; it is replaced only once, on a phone whose job came from an older
 *   spec ([PERIODIC_SPEC], remembered in the `sync` SharedPreferences).
 *
 * While the app is running, [SyncCoordinator.sync] does the same work in process straight away;
 * both go through ProgressStore's mutex, so they never interleave.
 */
class SyncScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** The spec of the periodic job this device has scheduled; kept across restarts and sign-outs. */
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun enqueueFlush() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            // In process, the reader has usually sent it already; this is the safety net.
            .setInitialDelay(FLUSH_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_PERIODIC to false))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(FLUSH_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInputData(workDataOf(SyncWorker.KEY_PERIODIC to true))
            .addTag(TAG)
            .build()
        // This runs at every process start while signed in, workers' included. KEEP leaves the job
        // and its timing alone; UPDATE would cancel and re-register it every time, the run that
        // started the process included. So only a job from an older spec is replaced, once.
        val stale = prefs.getInt(SPEC_KEY, 0) < PERIODIC_SPEC
        val policy = if (stale) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, policy, request)
        if (stale) prefs.edit().putInt(SPEC_KEY, PERIODIC_SPEC).apply()
    }

    /** On sign-out: this account's queue stays on the device and goes when it signs in again. */
    fun cancelAll() {
        workManager.cancelUniqueWork(FLUSH_WORK)
        workManager.cancelUniqueWork(PERIODIC_WORK)
    }

    companion object {
        const val TAG = "progress-sync"
        const val FLUSH_WORK = "progress-sync-flush"
        const val PERIODIC_WORK = "progress-sync-periodic"
        private const val FLUSH_DELAY_SECONDS = 30L
        private const val BACKOFF_SECONDS = 30L
        // Twice a day: each run starts the app and asks for every downloaded book's position.
        internal const val PERIODIC_HOURS = 12L

        /**
         * The periodic job's spec. Raise it whenever [schedulePeriodic]'s request changes (interval,
         * constraints, input), so a phone with the old job takes the new one once. 1: every 3 hours
         * (before 0.1.8, never stored); 2: every 12 hours, and not on a low battery; 3: the same
         * job under the worker's new class name (the package rename after 0.1.12). UPDATE keeps
         * the job's id and timing and takes the new request's class name, so the job an older build
         * registered stops depending on LegacyWorkers.
         */
        internal const val PERIODIC_SPEC = 3
        internal const val PREFS = "sync"
        internal const val SPEC_KEY = "periodicSpec"
    }
}
