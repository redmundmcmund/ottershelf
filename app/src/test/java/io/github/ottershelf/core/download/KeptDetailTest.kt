package io.github.ottershelf.core.download

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.session.TokenCipher
import io.github.ottershelf.core.sync.ProgressStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.concurrent.thread

/**
 * The book page kept with a download (`detail.json`) is saved from the book page, the quick view and
 * an edit, sometimes at once: each save lands whole, and only in a copy that is still there.
 */
@RunWith(AndroidJUnit4::class)
class KeptDetailTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dir = File(context.filesDir, "downloads/$ACCOUNT/7")

    private fun downloads(scope: CoroutineScope): Downloads {
        val plain = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String): String? = stored
        }
        val api = Api(Session(context, plain), "Test device") {}
        return Downloads(context, { ACCOUNT }, api, ProgressStore(context, { ACCOUNT }, api), scope)
    }

    @Test
    fun aBookThatIsntKeptGetsNoPage() = runTest {
        downloads(backgroundScope).saveDetail(BookDetail(id = 7, title = "Seven"))
        assertFalse(dir.exists())
    }

    @Test
    fun savesAtOnceEachLandWhole() = runTest {
        val downloads = downloads(backgroundScope)
        dir.mkdirs()
        File(dir, "meta.json").writeText("""{"bookId":7,"fileId":70}""")
        // Pages of different lengths: two writers sharing one temp file left a longer one's tail.
        val titles = (1..6).map { "T".repeat(it * 700) }
        titles.map { title -> thread { repeat(40) { downloads.saveDetail(BookDetail(id = 7, title = title)) } } }.forEach { it.join() }

        assertTrue(downloads.detail(7)?.title in titles)
        assertEquals(listOf("detail.json", "meta.json"), dir.list()!!.sorted()) // no temp file left behind
    }

    private companion object {
        const val ACCOUNT = "books.example_reader"
    }
}
