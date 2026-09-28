package io.github.ottershelf.core.download

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.session.TokenCipher
import io.github.ottershelf.core.sync.ProgressStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Download results reach the user: as a snackbar the shell confirms, or else as a notification. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DownloadEventsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val posted get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DownloadNotifications(context).ensureChannel()
    }

    private fun TestScope.newDownloads(): Downloads {
        val plain = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String): String? = stored
        }
        val api = Api(Session(context, plain), "Test device") {}
        val progress = ProgressStore(context, { ACCOUNT }, api)
        return Downloads(context, { ACCOUNT }, api, progress, backgroundScope)
    }

    private fun request(bookId: Long) =
        DownloadRequest(ACCOUNT, BookDetail(id = bookId, title = "Book $bookId"), BookFile(id = bookId * 10), target = "")

    @Test
    fun aResultCutOffByLeavingTheAppBecomesANotification() = runTest {
        val downloads = newDownloads()
        val received = mutableListOf<DownloadEvent>()
        // Received, but never confirmed: the app left while its snackbar was waiting or showing.
        val shell = launch { downloads.events.collect { received += it } }
        runCurrent()
        downloads.onFailed(request(1), "offline")
        runCurrent()
        assertEquals(1, received.size)
        assertTrue(posted.isEmpty())

        shell.cancel()
        runCurrent()
        assertEquals(1, posted.size)
    }

    @Test
    fun aResultTheShellShowedIsNotPostedAgain() = runTest {
        val downloads = newDownloads()
        val shell = launch { downloads.events.collect { downloads.shown(it) } }
        runCurrent()
        downloads.onFailed(request(2), "offline")
        runCurrent()
        shell.cancel()
        runCurrent()
        assertTrue(posted.isEmpty())
    }

    @Test
    fun withNobodyWatchingAResultIsANotificationAtOnce() = runTest {
        val downloads = newDownloads()
        runCurrent()
        downloads.onFailed(request(3), "offline")
        assertEquals(1, posted.size)
    }

    private companion object {
        const val ACCOUNT = "books.example.net_reader"
    }
}
