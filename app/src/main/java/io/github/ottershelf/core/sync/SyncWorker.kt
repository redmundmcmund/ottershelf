package io.github.ottershelf.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.ottershelf.appContainer

/**
 * Runs the progress sync for WorkManager (see [SyncScheduler]), in the app's process and with the
 * app's own [ProgressStore] singleton.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        if (!container.session.isSignedIn) return Result.success()
        val periodic = inputData.getBoolean(KEY_PERIODIC, false)
        container.sync.syncNow(baselines = periodic)
        return when {
            !container.progress.hasUnsent() -> Result.success()
            // Offline, or the server is unhappy: the one-time flush owns retrying.
            periodic -> Result.success().also { container.syncScheduler.enqueueFlush() }
            else -> Result.retry()
        }
    }

    companion object {
        const val KEY_PERIODIC = "periodic"
    }
}
