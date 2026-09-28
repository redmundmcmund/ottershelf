package io.github.ottershelf.feature.pdf

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** When the PDF reader's cached copy is opened, and when the server's answer says it mustn't be. */
class PdfFilesTest {

    @get:Rule val temp = TemporaryFolder()

    private val content = "%PDF-1.4\n"

    /** A server that answers the one-byte size check with [code] (and the cached copy's size). */
    private fun files(root: File, code: Int) = PdfFiles(
        root = root,
        client = {
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("")
                    .header("Content-Range", "bytes 0-0/${content.length}")
                    .body("%".toResponseBody())
                    .build()
            }.build()
        },
        serveUrl = { "https://books.example/api/v1/books/files/$it/serve" },
        offline = { _, _ -> null },
    )

    private fun cachedCopy(): Pair<File, File> {
        val root = temp.newFolder()
        return root to File(root, "7.pdf").apply { writeText(content) }
    }

    @Test
    fun theCachedCopyOpensWhileTheServerHasTheSameFileOrDoesNotAnswer() = runTest {
        for ((code, online) in listOf(206 to true, 500 to true, 401 to true, 403 to false)) {
            val (root, cached) = cachedCopy()
            val source = files(root, code).obtain(1, 7, online = online) {}
            assertEquals(cached, source.file)
            assertTrue(source.local && source.cached)
        }
    }

    @Test
    fun aRefusedFileIsNotOpenedFromTheCache() = runTest {
        for ((code, message) in listOf(403 to "Not allowed to read this file", 404 to "The server said 404")) {
            val (root, cached) = cachedCopy()
            try {
                files(root, code).obtain(1, 7, online = true) {}
                fail("opened a file the server refused ($code)")
            } catch (e: IOException) {
                assertEquals(message, e.message)
            }
            assertFalse(cached.exists())
        }
    }
}
