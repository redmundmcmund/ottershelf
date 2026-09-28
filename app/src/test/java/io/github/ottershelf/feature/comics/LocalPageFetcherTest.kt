package io.github.ottershelf.feature.comics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.Options
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.download.OfflineFiles
import io.github.ottershelf.testing.declareSize
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A downloaded CBZ's pages go through the comics loader's disk cache as files, not whole in memory, and no bigger than they say. */
@RunWith(AndroidJUnit4::class)
class LocalPageFetcherTest {

    @get:Rule val temp = TemporaryFolder()

    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun cbz(): File = temp.newFile("book.cbz").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("pages/01.png"))
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    private fun cache() = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(10L shl 20).build()

    @Test
    fun aPageIsCopiedIntoTheDiskCacheOnceAndReadAsAFile() = runTest {
        val cache = cache()
        val page = LocalComicPage(cbz(), "pages/01.png")

        val first = LocalPageFetcher(page, Options(context), cache).fetch() as SourceFetchResult
        // A file with the cache's key: what Telephoto tiles a zoomed page from.
        val file = first.source.fileOrNull()
        assertNotNull(file)
        assertArrayEquals(bytes, first.source.source().readByteArray())
        first.source.close()

        val snapshot = cache.openSnapshot(LocalPageFetcher.key(page))
        assertNotNull(snapshot)
        snapshot!!.close()
        // Read again from the cache, not the zip.
        val again = LocalPageFetcher(page, Options(context), cache).fetch() as SourceFetchResult
        assertEquals(file, again.source.fileOrNull())
        again.source.close()
    }

    private suspend fun assertFetchFails(fetcher: LocalPageFetcher) {
        try {
            fetcher.fetch()
            fail("the page was fetched")
        } catch (_: IOException) {
        }
    }

    /** A page inflating past what its zip entry declares (a zip bomb, which can declare anything) stops there. */
    @Test
    fun aPageStopsAtTheSizeItDeclares() = runTest {
        val page = LocalComicPage(cbz().also { declareSize(it, "pages/01.png", 1_000) }, "pages/01.png")
        val cache = cache()
        assertFetchFails(LocalPageFetcher(page, Options(context), cache))
        assertNull(cache.openSnapshot(LocalPageFetcher.key(page))) // dropped, not kept cut short
        // Streamed (no cache): the decoder gets an error, not the 200 KB.
        val streamed = LocalPageFetcher(page, Options(context), null).fetch() as SourceFetchResult
        assertThrows(IOException::class.java) { streamed.source.source().readByteArray() }
        streamed.source.close()
    }

    @Test
    fun aPageDeclaringMoreThanTheCapIsNotRead() = runTest {
        val page = LocalComicPage(cbz().also { declareSize(it, "pages/01.png", OfflineFiles.MAX_CBZ_PAGE_BYTES + 1) }, "pages/01.png")
        assertFetchFails(LocalPageFetcher(page, Options(context), cache()))
        assertFetchFails(LocalPageFetcher(page, Options(context), null))
    }

    @Test
    fun withoutTheCacheThePageStreamsFromTheZip() = runTest {
        val page = LocalComicPage(cbz(), "pages/01.png")
        for (fetcher in listOf(
            LocalPageFetcher(page, Options(context), null),
            LocalPageFetcher(page, Options(context, diskCachePolicy = CachePolicy.DISABLED), cache()),
        )) {
            val result = fetcher.fetch() as SourceFetchResult
            assertNull(result.source.fileOrNull())
            assertArrayEquals(bytes, result.source.source().readByteArray())
            result.source.close()
        }
    }
}
