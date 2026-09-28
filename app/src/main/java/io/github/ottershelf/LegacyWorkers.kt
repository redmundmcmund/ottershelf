package io.github.ottershelf

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import io.github.ottershelf.core.download.DownloadWorker
import io.github.ottershelf.core.sync.SyncWorker
import io.github.ottershelf.core.tracking.TrackingWorker
import io.github.ottershelf.feature.reader.annotations.ReaderNotesWorker

/**
 * Migration: work queued before the package rename (0.1.12 and earlier) still runs.
 *
 * WorkManager keeps each work item's worker class name in its database and creates the worker from
 * that name when the item runs. Until 0.1.12 the app's code was in the package
 * `net.redmund.bookorbit` (a legacy identifier, kept here only for this lookup), so work an
 * installed build had queued (a pending progress, session or notes flush, a download, the periodic
 * sync) names classes that no longer exist, and WorkManager's default factory would fail it. This
 * factory maps those old names to today's workers; every other name returns null, which lets
 * WorkManager create the worker by reflection as usual.
 *
 * The periodic sync is also registered again under its new class once (SyncScheduler, spec 3).
 * The one-time items are gone after their next run, so this can be removed once no device can
 * still hold work queued by 0.1.12 or earlier.
 */
object LegacyWorkers : WorkerFactory() {

    private const val OLD_PACKAGE = "net.redmund.bookorbit"

    /** Old class name -> today's worker. */
    internal val renamed: Map<String, (Context, WorkerParameters) -> ListenableWorker> = mapOf(
        "$OLD_PACKAGE.core.sync.SyncWorker" to ::SyncWorker,
        "$OLD_PACKAGE.core.tracking.TrackingWorker" to ::TrackingWorker,
        "$OLD_PACKAGE.core.download.DownloadWorker" to ::DownloadWorker,
        "$OLD_PACKAGE.feature.reader.annotations.ReaderNotesWorker" to ::ReaderNotesWorker,
    )

    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        renamed[workerClassName]?.invoke(appContext, workerParameters)
}
