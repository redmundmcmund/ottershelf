package io.github.ottershelf.feature.comics

import android.content.Context
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.Image
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.Options
import io.github.ottershelf.core.model.NativeCredentials
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.session.TokenCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * A closed comic's decoded pages leave the reader's memory cache ([ComicImages.forget]); other
 * comics' stay. Robolectric only for the Coil loader and the Api's server: nothing is fetched.
 */
@RunWith(AndroidJUnit4::class)
class ComicMemoryCacheTest {

    @get:Rule val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A decoded page as far as the cache is concerned: its size. */
    private class Page(override val size: Long) : Image {
        override val width = 1
        override val height = 1
        override val shareable = true
        override fun draw(canvas: Canvas) = Unit
    }

    private fun cache() = MemoryCache.Builder().maxSizeBytes(1L shl 20).build()

    private fun MemoryCache.put(key: String, extras: Map<String, String> = emptyMap()) {
        set(MemoryCache.Key(key, extras), MemoryCache.Value(Page(100)))
    }

    private fun MemoryCache.keyStrings() = keys.map { it.key }.toSet()

    @Test fun forgetsOnlyThatComicsServerPages() {
        val cache = cache()
        val base = "https://books.example/api/v1/cbz/files/12/pages/"
        cache.put("${base}0")
        cache.put("${base}1", mapOf("coil#size" to "1080x2392")) // the same page decoded at another size
        cache.put("${base}10")
        cache.put("https://books.example/api/v1/cbz/files/123/pages/0") // another comic (Read next)
        cache.put("https://books.example/api/v1/books/12/thumbnail")

        ComicImages.forget(cache, base)

        assertEquals(
            setOf("https://books.example/api/v1/cbz/files/123/pages/0", "https://books.example/api/v1/books/12/thumbnail"),
            cache.keyStrings(),
        )
    }

    @Test fun forgetsOnlyThatCopysPages() {
        val book = temp.newFolder("12").resolve("book.cbz").apply { writeText("zip") }
        val other = temp.newFolder("123").resolve("book.cbz").apply { writeText("zip") }
        val source = ComicSource.Local(book, listOf("p1.png", "p2.png"))
        val cache = cache()
        val pages = listOf(LocalComicPage(book, "p1.png"), LocalComicPage(book, "p2.png")).map(LocalPageFetcher::key)
        val kept = LocalPageFetcher.key(LocalComicPage(other, "p1.png"))
        (pages + kept).forEach { cache.put(it) }
        // A page of this copy cached before it was downloaded again (another time in its key).
        cache.put("comic:${book.path}:1!p1.png")
        pages.forEach { assertTrue(it, it.startsWith(source.cacheKeyPrefix)) }

        ComicImages.forget(cache, source.cacheKeyPrefix)

        assertEquals(setOf(kept), cache.keyStrings())
    }

    @Test fun nothingToForget() {
        val cache = cache()
        cache.put("https://books.example/api/v1/cbz/files/12/pages/0")
        ComicImages.forget(cache, "") // no prefix: not everything
        ComicImages.forget(null, "https://books.example/")
        assertEquals(1, cache.keys.size)
    }

    /** An Api signed in at [server] with made-up tokens; only its [Api.serverUrl] is used. */
    private fun api(server: String): Api {
        val plain = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String): String? = stored
        }
        val session = Session(context, plain)
        session.store(NativeCredentials("access", "2026-09-21T14:13:20Z", "refresh", "2026-10-21T14:13:20Z"), server = server)
        return Api(session, "Test device") {}
    }

    /** The memory-cache keys Coil makes for [source]'s pages: its mappers and keyers, and the reader's page keyer. */
    private fun ImageLoader.pageKeys(source: ComicSource): List<String> {
        val options = Options(context)
        return (0 until source.pageCount).map { i ->
            val model = requireNotNull(source.model(i)) { "page $i" }
            requireNotNull(components.key(components.map(model, options), options)) { "page $i's key" }
        }
    }

    /**
     * Each source's [ComicSource.cacheKeyPrefix] against the keys Coil really gives its pages, not
     * strings written here: what onCleared relies on. So a change to a page's model or key shape (a
     * query on the URL, say) fails here rather than leaving a closed comic's pages in memory.
     */
    @Test fun eachPrefixCoversCoilsKeysForThatComicOnly() {
        val loader = ImageLoader.Builder(context).components { add(LocalPageFetcher.Key()) }.build()
        val api = api("https://books.example/")
        val comic = ComicSource.Server(api, fileId = 12, pageCount = 12) // pages 10 and 11 too
        val next = ComicSource.Server(api, fileId = 123, pageCount = 3)
        val book = temp.newFolder("12").resolve("book.cbz").apply { writeText("zip") }
        val other = temp.newFolder("123").resolve("book.cbz").apply { writeText("zip") }
        val copy = ComicSource.Local(book, listOf("p1.png", "p2.png"))
        val otherCopy = ComicSource.Local(other, listOf("p1.png"))
        val cache = cache()
        val keys = listOf(comic, next, copy, otherCopy).associateWith { loader.pageKeys(it) }
        keys.values.flatten().forEach { cache.put(it) }
        assertEquals("https://books.example/api/v1/cbz/files/12/pages/", comic.cacheKeyPrefix)
        keys.getValue(comic).forEach { assertTrue(it, it.startsWith(comic.cacheKeyPrefix!!)) }
        keys.getValue(copy).forEach { assertTrue(it, it.startsWith(copy.cacheKeyPrefix)) }

        ComicImages.forget(cache, comic.cacheKeyPrefix!!)
        assertEquals((keys.getValue(next) + keys.getValue(copy) + keys.getValue(otherCopy)).toSet(), cache.keyStrings())

        ComicImages.forget(cache, copy.cacheKeyPrefix)
        assertEquals((keys.getValue(next) + keys.getValue(otherCopy)).toSet(), cache.keyStrings())
    }
}
