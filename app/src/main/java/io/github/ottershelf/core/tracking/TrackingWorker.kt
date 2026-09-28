package io.github.ottershelf.core.tracking

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
import io.github.ottershelf.appContainer
import java.util.concurrent.TimeUnit

/**
 * Background delivery of what the tracker queued offline: sessions (TrackingRepository) and the
 * app's settings key (AppSettingsRepository). Unique one-time work (KEEP) with a CONNECTED
 * constraint and exponential backoff, so it goes as soon as there is a network, even after the app
 * was killed.
 */
class TrackingScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    fun enqueueFlush() {
        val request = OneTimeWorkRequestBuilder<TrackingWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(FLUSH_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(FLUSH_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** On sign-out: the queue stays on the device and goes when that account signs in again. */
    fun cancel() {
        workManager.cancelUniqueWork(FLUSH_WORK)
    }

    companion object {
        const val TAG = "tracking-sync"
        const val FLUSH_WORK = "tracking-flush"
        private const val FLUSH_DELAY_SECONDS = 10L
        private const val BACKOFF_SECONDS = 30L
    }
}

/** Sends queued sessions and a waiting settings change; retries while anything is left. */
class TrackingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        if (!container.session.isSignedIn) return Result.success()
        val sessionsDone = attempt { container.tracking.flush() }
        val settingsDone = attempt { container.appSettings.flush() }
        return if (sessionsDone && settingsDone) Result.success() else Result.retry()
    }

    private suspend fun attempt(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
