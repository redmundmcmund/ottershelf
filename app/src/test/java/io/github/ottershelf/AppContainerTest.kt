package io.github.ottershelf

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.NativeCredentials
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.session.AuthState
import io.github.ottershelf.core.session.TokenCipher
import io.github.ottershelf.core.tracking.ActiveTimer
import io.github.ottershelf.core.tracking.TimerState
import io.github.ottershelf.feature.comics.ComicImages
import io.github.ottershelf.feature.pdf.PdfFiles
import io.github.ottershelf.feature.reader.annotations.NoteOp
import io.github.ottershelf.feature.reader.annotations.ReaderNotesScheduler
import io.github.ottershelf.feature.reader.annotations.ReaderNotesStore
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AppContainer's start and sign-out, on Robolectric's plain Application: what a process without a
 * screen starts, and what an account leaves behind.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class AppContainerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
    }

    /** Runs the main looper until [done] (work that hops to IO and back), at most [timeoutMs]. */
    private fun waitFor(timeoutMs: Long = 5_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            Thread.sleep(10)
        }
        return done()
    }

    @Test
    fun theAppearanceWaitsForTheFirstScreen() {
        val container = AppContainer(app)
        container.start() // as in a process started for a worker: no activity
        assertFalse(waitFor(timeoutMs = 2_000) { container.theme.loaded.value })

        Robolectric.buildActivity(ComponentActivity::class.java).create()
        assertTrue(waitFor { container.theme.loaded.value })
    }

    @Test
    fun tokensTheKeyCannotDecryptEndSignedOutAndStayStored() {
        // Robolectric has no AndroidKeyStore, so the real cipher can't decrypt these.
        val prefs = app.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("server", "https://books.example.net").putString("user", "reader")
            .putString("access.enc", "v1:AAAA").putString("refresh.enc", "v1:AAAA").commit()
        val container = AppContainer(app)

        container.start()

        assertTrue(waitFor { container.auth.status.value == AuthState.Status.SignedOut })
        assertFalse(container.session.isSignedIn)
        assertTrue(prefs.contains("refresh.enc"))
    }

    @Test
    fun aPauseFromTheTimersNotificationInANewProcessFindsTheTimerWhileTheTokenIsStillBeingRead() {
        // A process started by the Pause button of the running timer's notification: the refresh
        // token is still being decrypted on IO (held here) when the action arrives.
        val release = CountDownLatch(1)
        val slow = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String): String? = stored.also { release.await(5, TimeUnit.SECONDS) }
        }
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear()
            .putString("server", "https://books.example.net").putString("user", "reader")
            .putString("access.enc", "access-1").putString("refresh.enc", "refresh-1").commit()
        val key = stringPreferencesKey("tracking.timer.books.example.net_reader")
        val running = TimerState(active = ActiveTimer(sessionId = "s-1", bookId = 7, fileId = null, title = "Book", startedAtMs = 1_000, runningSinceMs = 1_000))
        val container = AppContainer(app, slow)
        runBlocking { container.settings.edit { it[key] = ApiJson.encodeToString(TimerState.serializer(), running) } }
        try {
            container.start()

            val pause = container.appScope.launch { container.timer.pause() } // as TimerActionReceiver does
            assertTrue(waitFor { pause.isCompleted })
            assertEquals(1L, release.count) // still reading the token

            val stored = runBlocking { container.settings.data.first()[key] }!!.let { ApiJson.decodeFromString(TimerState.serializer(), it) }
            assertEquals(7L, stored.active?.bookId)
            assertTrue(stored.active!!.paused)
        } finally {
            release.countDown()
            runBlocking { container.settings.edit { it.remove(key) } }
        }
    }

    private fun notesWork() = WorkManager.getInstance(app).getWorkInfosForUniqueWork("reader-notes-flush").get()

    @Test
    fun waitingReaderNotesAreLookedForOnceThereIsAScreen() {
        val plain = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String) = stored
        }
        val container = AppContainer(app, plain)
        container.session.store(
            NativeCredentials("access-1", "2099-01-01T00:00:00Z", "refresh-1", "2099-01-01T00:00:00Z", user = AuthUser(id = 1, username = "reader")),
            server = "https://books.example.net",
        )
        val account = container.session.accountKey()
        runBlocking { ReaderNotesStore.get(app).update(account, 7) { it.copy(queue = listOf(NoteOp.DeleteAnnotation("op-1", 5))) } }

        container.start() // as in a process started for a worker: no activity
        assertFalse(waitFor(timeoutMs = 1_000) { notesWork().isNotEmpty() })

        Robolectric.buildActivity(ComponentActivity::class.java).create()
        assertTrue(waitFor { notesWork().any { it.state == WorkInfo.State.ENQUEUED } })
    }

    /** An image in both of the loader's caches, as if fetched for the account signed in. */
    private fun ImageLoader.remember(key: String) {
        memoryCache!![MemoryCache.Key(key)] = MemoryCache.Value(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).asImage())
        val disk = diskCache!!
        disk.openEditor(key)!!.run {
            disk.fileSystem.write(data) { writeUtf8("jpeg") }
            commit()
        }
    }

    /** The sizes of the loader's memory and disk caches. */
    private fun ImageLoader.cached(): List<Long> = listOf(memoryCache!!.size, diskCache!!.size)

    @Test
    fun signingOutStopsTheNotesWorkerAndForgetsImagesAndPdfs() {
        val covers = ImageLoader.Builder(app)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(tmp.newFolder("covers").toOkioPath()).build() }
            .build()
        SingletonImageLoader.setUnsafe(covers)
        covers.remember("cover")
        val comics = ComicImages.loader(app) { OkHttpClient() }
        comics.remember("page")
        val pdf = File(app.cacheDir, "${PdfFiles.CACHE_DIR}/books.example_me/7.pdf").apply {
            parentFile!!.mkdirs()
            writeText("%PDF-1.7")
        }
        ReaderNotesScheduler.enqueue(app)
        val container = AppContainer(app)
        container.start()
        assertTrue((covers.cached() + comics.cached()).all { it > 0 })

        container.signOut()

        val notes = WorkManager.getInstance(app).getWorkInfosForUniqueWork("reader-notes-flush").get().single()
        assertEquals(WorkInfo.State.CANCELLED, notes.state)
        assertTrue(waitFor { (covers.cached() + comics.cached()).all { it == 0L } && !pdf.exists() })
    }
}
