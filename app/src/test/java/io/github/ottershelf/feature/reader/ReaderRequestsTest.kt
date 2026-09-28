package io.github.ottershelf.feature.reader

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import io.github.ottershelf.core.download.LocalEpub
import io.github.ottershelf.core.download.OfflineCopy
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.session.TokenCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the reader page gets for its requests ([ReaderRequests.intercept]): the open book's files
 * from the downloaded copy, a refusal for anything else, and on every answer the headers that keep
 * a book's file from running as a page. The Api has no server and no token, so nothing here leaves
 * the test.
 */
@RunWith(AndroidJUnit4::class)
class ReaderRequestsTest {

    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val opened = mutableListOf<LocalEpub>()

    @After
    fun closeCopies() = opened.forEach { it.close() }

    private fun api(): Api {
        val plain = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String): String? = stored
        }
        return Api(Session(context, plain), "Test device") {}
    }

    private fun epubRequests(): ReaderRequests {
        val dir = folder.newFolder()
        ZipOutputStream(File(dir, "book.epub").outputStream()).use { zip ->
            for ((name, text) in mapOf("OEBPS/ch1.xhtml" to CHAPTER, "OEBPS/evil.js" to "Android.onReady()")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        File(dir, "info.json").writeText(
            """{"manifest":[{"href":"OEBPS/ch1.xhtml","mediaType":"application/xhtml+xml","size":${CHAPTER.length}},""" +
                """{"href":"OEBPS/evil.js","mediaType":"text/javascript","size":17}]}""",
        )
        val epub = LocalEpub(dir).also { opened += it }
        return ReaderRequests(BOOK, FILE, CompletableDeferred<LocalBook?>(LocalBook.Epub(epub)), api())
    }

    private fun wholeFileRequests(): ReaderRequests {
        val file = folder.newFile("book.mobi").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val copy = OfflineCopy(BOOK, FILE, "mobi", file)
        return ReaderRequests(BOOK, FILE, CompletableDeferred<LocalBook?>(LocalBook.Whole(copy)), api(), wholeFile = true)
    }

    private fun request(url: String, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun ReaderRequests.answer(url: String, method: String = "GET"): WebResourceResponse =
        intercept(request(url, method)).also { assertNotNull("no answer for $url", it) }!!

    private fun assertSandboxed(response: WebResourceResponse, what: String) {
        val headers = response.responseHeaders
        assertEquals(what, "no-store", headers["Cache-Control"])
        assertEquals(what, "sandbox; default-src 'none'", headers["Content-Security-Policy"])
        assertEquals(what, "nosniff", headers["X-Content-Type-Options"])
    }

    @Test
    fun theBooksFilesAreDataThatNeverRunsAsAPage() {
        val requests = epubRequests()
        val chapter = requests.answer("$API/epub/$BOOK/file/OEBPS/ch1.xhtml?fileId=$FILE")
        assertEquals(200, chapter.statusCode)
        assertEquals("application/xhtml+xml", chapter.mimeType)
        assertEquals(CHAPTER, chapter.data.use { it.readBytes().decodeToString() })
        assertSandboxed(chapter, "a chapter")
        // The book's own script, which a chapter could try to load as one: served as data.
        val script = requests.answer("$API/epub/$BOOK/file/OEBPS/evil.js?fileId=$FILE")
        assertEquals(200, script.statusCode)
        assertSandboxed(script, "the book's script")
        val info = requests.answer("$API/epub/$BOOK/info?fileId=$FILE")
        assertEquals(200, info.statusCode)
        assertSandboxed(info, "the info")
    }

    @Test
    fun theWholeFileOfAMobiIsDataToo() {
        val requests = wholeFileRequests()
        val file = requests.answer("$API/books/files/$FILE/serve")
        assertEquals(200, file.statusCode)
        assertEquals("128", file.responseHeaders["Content-Length"])
        assertSandboxed(file, "the whole file")
    }

    @Test
    fun refusalsAndFailuresCarryTheHeadersToo() {
        val requests = epubRequests()
        val missing = requests.answer("$API/epub/$BOOK/file/OEBPS/missing.xhtml?fileId=$FILE")
        assertEquals(404, missing.statusCode)
        assertSandboxed(missing, "a missing file")
        val other = requests.answer("$API/auth/me")
        assertEquals(403, other.statusCode)
        assertSandboxed(other, "another API route")
        val site = requests.answer("https://example.com/pixel.png")
        assertEquals(403, site.statusCode)
        assertSandboxed(site, "another site")
        // No server configured: the whole file can't be fetched (a 502), still with the headers.
        val whole = ReaderRequests(BOOK, FILE, CompletableDeferred<LocalBook?>().apply { complete(null) }, api(), wholeFile = true)
        val failed = whole.answer("$API/books/files/$FILE/serve")
        assertEquals(502, failed.statusCode)
        assertSandboxed(failed, "a failed fetch")
        val proxied = ReaderRequests(BOOK, FILE, CompletableDeferred<LocalBook?>().apply { complete(null) }, api())
            .answer("$API/epub/$BOOK/file/OEBPS/ch1.xhtml?fileId=$FILE")
        assertEquals(502, proxied.statusCode)
        assertSandboxed(proxied, "a failed proxy")
    }

    @Test
    fun nothingOnTheReadersHostGoesToTheNetwork() {
        val requests = epubRequests()
        // The APK's assets are the asset loader's (a missing one is a 404 there).
        assertNull(requests.intercept(request("$HOST/assets/reader/index.html")))
        assertNull(requests.intercept(request("$HOST/assets/foliate/view.js")))
        // Everything else on the host would be looked up on the network: refused here.
        for (url in listOf("$HOST/favicon.ico", "$HOST/", "$HOST", "$HOST/assetsx/a.js", "$HOST/other/api/v1/auth/me")) {
            val refused = requests.answer(url)
            assertEquals(url, 403, refused.statusCode)
            assertSandboxed(refused, url)
        }
        // The host on another port, or with a user name: the asset loader matches the whole
        // authority, so it wouldn't answer these either.
        for (url in listOf(
            "https://appassets.androidplatform.net:444/assets/reader/reader.js",
            "https://someone@appassets.androidplatform.net/assets/reader/reader.js",
            "https://appassets.androidplatform.net:8443/api/v1/epub/$BOOK/info?fileId=$FILE",
        )) {
            val refused = requests.answer(url)
            assertEquals(url, 403, refused.statusCode)
            assertSandboxed(refused, url)
        }
        // Plain http to the host, even for the assets or the book's own routes (the page is https).
        for (url in listOf("http://appassets.androidplatform.net/assets/reader/reader.js", "http://appassets.androidplatform.net/api/v1/epub/$BOOK/info?fileId=$FILE")) {
            assertEquals(url, 403, requests.answer(url).statusCode)
        }
        // Not the web: the WebView's own business (blob: chapters, data: images).
        assertNull(requests.intercept(request("blob:$HOST/0f1e2d3c")))
        assertNull(requests.intercept(request("data:image/png;base64,AAAA")))
        // The book's own routes are still answered, GET only.
        assertEquals(200, requests.answer("$API/epub/$BOOK/info?fileId=$FILE").statusCode)
        assertEquals(403, requests.answer("$API/epub/$BOOK/info?fileId=$FILE", method = "POST").statusCode)
    }

    private companion object {
        const val HOST = "https://appassets.androidplatform.net"
        const val BOOK = 5L
        const val FILE = 9L
        const val API = "https://appassets.androidplatform.net/api/v1"
        const val CHAPTER = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>One</p></body></html>"
    }
}
