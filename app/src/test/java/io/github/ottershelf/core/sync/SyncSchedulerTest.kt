package io.github.ottershelf.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** The periodic job is registered once and kept: every process start calls [SyncScheduler.schedulePeriodic]. */
@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        context.getSharedPreferences(SyncScheduler.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // The class name WorkManager stored for a job. Through reflection: WorkDatabase is a Room database, and
    // Room isn't on the test compile classpath.
    private fun workerClassOf(id: String): String {
        val db = WorkManagerImpl::class.java.getMethod("getWorkDatabase").invoke(WorkManagerImpl.getInstance(context))!!
        val dao = db.javaClass.getMethod("workSpecDao").invoke(db)!!
        val spec = dao.javaClass.getMethod("getWorkSpec", String::class.java).invoke(dao, id) as WorkSpec
        return spec.workerClassName
    }

    private fun periodic(): WorkInfo = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK).get()
        .single { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun laterProcessStartsLeaveTheScheduledJobAlone() {
        SyncScheduler(context).schedulePeriodic()
        val first = periodic()

        // A worker's process, then the app opened: each builds its own scheduler.
        SyncScheduler(context).schedulePeriodic()
        SyncScheduler(context).schedulePeriodic()

        val now = periodic()
        assertEquals(first.id, now.id)
        assertEquals(first.generation, now.generation) // not cancelled and registered again
        assertEquals(TimeUnit.HOURS.toMillis(SyncScheduler.PERIODIC_HOURS), now.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun theJobFromAnOlderVersionIsReplacedOnce() {
        // What 0.1.7 scheduled, with no spec stored.
        val old = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(SyncScheduler.PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, old)

        SyncScheduler(context).schedulePeriodic()
        val updated = periodic()
        assertEquals(TimeUnit.HOURS.toMillis(SyncScheduler.PERIODIC_HOURS), updated.periodicityInfo!!.repeatIntervalMillis)
        assertTrue(updated.constraints.requiresBatteryNotLow())

        SyncScheduler(context).schedulePeriodic()
        assertEquals(updated.generation, periodic().generation)
    }

    @Test
    fun theJobABuildBeforeThePackageRenameRegisteredTakesTheNewWorkerClass() {
        // What 0.1.12 scheduled: spec 2, its worker named in the old package (WorkManager keeps the name).
        val old = PeriodicWorkRequestBuilder<SyncWorker>(SyncScheduler.PERIODIC_HOURS, TimeUnit.HOURS).build()
        old.workSpec.workerClassName = "net.redmund.bookorbit.core.sync.SyncWorker"
        workManager.enqueueUniquePeriodicWork(SyncScheduler.PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, old)
        context.getSharedPreferences(SyncScheduler.PREFS, Context.MODE_PRIVATE).edit().putInt(SyncScheduler.SPEC_KEY, 2).commit()
        assertEquals("net.redmund.bookorbit.core.sync.SyncWorker", workerClassOf(old.stringId))

        SyncScheduler(context).schedulePeriodic()

        val now = periodic()
        assertEquals(old.id, now.id) // the same job, its timing kept
        assertEquals(SyncWorker::class.java.name, workerClassOf(old.stringId))
        assertEquals(SyncScheduler.PERIODIC_SPEC, context.getSharedPreferences(SyncScheduler.PREFS, Context.MODE_PRIVATE).getInt(SyncScheduler.SPEC_KEY, 0))
    }

    @Test
    fun signingInAgainSchedulesItAfresh() {
        val scheduler = SyncScheduler(context)
        scheduler.schedulePeriodic()
        val before = periodic()

        scheduler.cancelAll() // signed out
        scheduler.schedulePeriodic() // signed in again

        val after = periodic()
        assertEquals(TimeUnit.HOURS.toMillis(SyncScheduler.PERIODIC_HOURS), after.periodicityInfo!!.repeatIntervalMillis)
        assertNotEquals(before.id, after.id)
    }
}
