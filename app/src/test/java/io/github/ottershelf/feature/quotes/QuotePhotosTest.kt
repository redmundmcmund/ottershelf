package io.github.ottershelf.feature.quotes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.UserSettings
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.settings.AppSettingsRepository
import io.github.ottershelf.core.tracking.FakeTrackingRemote
import io.github.ottershelf.core.tracking.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Kept quote photos are linked by the settings key on the server, so a link naming anything but the
 * photo's own file (`../../shared_prefs/...`) is never read or deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class QuotePhotosTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = "books.example_reader"
    private val dir get() = QuotePhotos.dir(context, account)

    /** A file the app keeps elsewhere in its sandbox (the user's session, say). */
    private fun victim(): File =
        File(context.filesDir.parentFile, "shared_prefs/victim.xml").apply { parentFile!!.mkdirs(); writeText("<map/>") }

    /** From `files/quote-photos/<account>/` up to the app's folder. */
    private val traversal = "../../../shared_prefs/victim.xml"

    private fun TestScope.settings(links: Map<Long, String>): AppSettingsRepository {
        val remote = FakeTrackingRemote()
        remote.user = AuthUser(
            id = 1,
            username = "reader",
            settings = UserSettings(bookorbitAndroid = AppSettings.encodeOver(null, AppSettings(quotePhotos = links))),
        )
        val user = MutableStateFlow<AuthUser?>(remote.user)
        return AppSettingsRepository(MemoryStore(), remote, user, { account }, { user.value = it }, backgroundScope, {}).also {
            it.start()
            runCurrent()
        }
    }

    @Test
    fun onlyTheNameKeepWritesIsAPhoto() {
        val dir = dir
        assertEquals(File(dir, "5.jpg"), QuotePhotos.linkedFile(dir, 5, "5.jpg"))
        assertNull(QuotePhotos.linkedFile(dir, 5, traversal))
        assertNull(QuotePhotos.linkedFile(dir, 5, "7.jpg")) // another quote's photo
        assertNull(QuotePhotos.linkedFile(dir, 5, "sub/5.jpg"))
        assertNull(QuotePhotos.linkedFile(dir, 5, "/5.jpg"))
        assertNull(QuotePhotos.linkedFile(dir, 5, null))
    }

    @Test
    fun aCraftedLinkIsNeitherShownNorRemoved() = runTest {
        val victim = victim()
        val settings = settings(mapOf(1L to traversal))

        assertTrue(QuotePhotos.onThisPhone(context, account, settings.settings.value.quotePhotos).isEmpty())
        assertNull(QuotePhotos.of(context, account, settings, 1))
        QuotePhotos.remove(context, account, settings, 1)

        assertTrue(victim.isFile)
        assertFalse(1L in settings.settings.value.quotePhotos) // the link itself goes
    }

    @Test
    fun trimmingTheOldestLinksDeletesOnlyTheirOwnPhotos() = runTest {
        val victim = victim()
        // The lowest ids are trimmed first: a crafted one among them, and the user's oldest real photo.
        val links = mapOf(-1L to traversal) + (1L..QuotePhotos.MAX_PHOTOS).associateWith { "$it.jpg" }
        val oldest = File(dir.apply { mkdirs() }, "1.jpg").apply { writeText("jpeg") }
        val settings = settings(links)
        val photo = File(context.cacheDir, "page.jpg").apply { writeText("jpeg") }

        assertTrue(QuotePhotos.keep(context, account, settings, 1_000, photo))

        assertTrue(victim.isFile)
        assertFalse(oldest.exists())
        assertTrue(File(dir, "1000.jpg").isFile)
        val kept = settings.settings.value.quotePhotos
        assertEquals(QuotePhotos.MAX_PHOTOS, kept.size)
        assertFalse(-1L in kept || 1L in kept)
    }
}
