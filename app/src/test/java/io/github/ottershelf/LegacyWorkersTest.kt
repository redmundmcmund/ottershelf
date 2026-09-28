package io.github.ottershelf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.ottershelf.core.download.DownloadWorker
import io.github.ottershelf.core.sync.SyncWorker
import io.github.ottershelf.core.tracking.TrackingWorker
import io.github.ottershelf.feature.reader.annotations.ReaderNotesWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.reflect.KClass

/** Work queued before the package rename names the workers' old classes (LegacyWorkers). */
@RunWith(AndroidJUnit4::class)
class LegacyWorkersTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val oldPackage = "net.redmund.bookorbit"

    /** What LegacyWorkers makes for [name], given the WorkerParameters WorkManager would pass. */
    private fun <W : ListenableWorker> create(name: String, type: KClass<W>): ListenableWorker? {
        var made: ListenableWorker? = null
        TestListenableWorkerBuilder.from(context, type.java)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    LegacyWorkers.createWorker(appContext, name, workerParameters).also { made = it }
            })
            .build()
        return made
    }

    @Test
    fun everyOldWorkerNameMakesTodaysWorker() {
        val expected = listOf(SyncWorker::class, TrackingWorker::class, DownloadWorker::class, ReaderNotesWorker::class)
        for (type in expected) {
            val old = oldPackage + type.java.name.removePrefix("io.github.ottershelf")
            val worker = create(old, type)
            assertTrue("$old made ${worker?.javaClass?.name}", type.isInstance(worker))
        }
        assertEquals(expected.size, LegacyWorkers.renamed.size)
    }

    @Test
    fun eachOldNameIsTheSameClassInTheOldPackage() {
        for (old in LegacyWorkers.renamed.keys) {
            assertTrue(old, old.startsWith("$oldPackage."))
            @Suppress("UNCHECKED_CAST")
            val type = Class.forName("io.github.ottershelf" + old.removePrefix(oldPackage)).kotlin as KClass<ListenableWorker>
            assertTrue(old, type.isInstance(create(old, type)))
        }
    }

    @Test
    fun todaysNamesAndUnknownOnesAreLeftToWorkManager() {
        assertNull(create(SyncWorker::class.java.name, SyncWorker::class))
        assertNull(create("$oldPackage.core.sync.GoneWorker", SyncWorker::class))
    }

    @Test
    fun theAppGivesWorkManagerTheFactory() {
        assertSame(LegacyWorkers, OttershelfApp().workManagerConfiguration.workerFactory)
    }
}
