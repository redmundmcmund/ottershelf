package io.github.ottershelf.feature.reader

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import io.github.ottershelf.core.download.LocalEpub
import io.github.ottershelf.core.download.OfflineCopy
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.format.ReaderKind
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * The downloaded copy the reader reads from: an EPUB unpacked for the streaming loader, or the
 * original file of the formats read whole (KEPUB, MOBI, AZW3, AZW, FB2).
 */
internal sealed interface LocalBook : Closeable {
    class Epub(val epub: LocalEpub) : LocalBook {
        override fun close() = epub.close()
    }

    class Whole(val copy: OfflineCopy) : LocalBook {
        override fun close() {}
    }
}

/**
 * Answers the reader page's `/api/v1/...` requests (same-origin on the WebViewAssetLoader domain).
 *
 * Only the routes the page uses for the open book and file are answered, GET only:
 * - an EPUB ([wholeFile] false): foliate's streaming loader's `epub/<bookId>/info?fileId=<fileId>`
 *   and `epub/<bookId>/file/<path>?fileId=<fileId>` (the server serves EPUB files only there);
 * - the other formats foliate reads ([wholeFile] true: KEPUB, MOBI, AZW3, AZW, FB2): the whole file
 *   from `books/files/<fileId>/serve`, the route the web reader opens them from.
 *
 * Anything else the page (or a WebView debugger) asks for gets a 403, so the bearer token can't be
 * used to read the rest of the API; so does every other site, and anything on the page's own host
 * outside the APK's `/assets/`, so nothing a book asks for reaches the network. A downloaded copy
 * answers from the device, connection or not; otherwise the request goes to the server (epub
 * routes through [Api.proxy], which also checks the route; the whole file with the account's
 * client).
 *
 * Every answer is a book's file (or a refusal) that the page fetches as data, never a page of its
 * own, and says so ([HEADERS]): a book that frames or opens one of its files gets it without
 * scripts, in an origin of its own, away from the reader page and `window.Android`.
 *
 * Called on WebView IO threads.
 */
internal class ReaderRequests(
    private val bookId: Long,
    private val fileId: Long,
    private val localCopy: Deferred<LocalBook?>,
    private val api: Api,
    private val wholeFile: Boolean = false,
) {
    /** The downloaded copy of this file, once looked up (null: not downloaded, or not yet known). */
    @Volatile
    var local: LocalBook? = null
        private set

    /** Waits for the downloaded copy to be looked up (opening an EPUB reads its zip index). */
    suspend fun awaitLocal(): LocalBook? = localCopy.await().also { local = it }

    /** The whole file can take a while on a slow connection: the book shows only once it's all here. */
    private val fileClient: OkHttpClient by lazy { api.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }

    /**
     * A response for [request], or null for the APK's `/assets/` (the asset loader's turn) and for
     * schemes other than http(s). Other sites get a 403: a book (or a page it frames) gets no
     * network, even past the page's CSP. So does anything else on the page's own host (not under
     * `/assets/` or `/api/`, not https, or on another port or with a user name): the loader
     * doesn't answer it, and the WebView would look for it on the network.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" && url.scheme != "http") return null
        // The whole authority, as the asset loader matches it: the host alone would let
        // `appassets.androidplatform.net:444/assets/...` past both of them to the network.
        if (url.authority != WebViewAssetLoader.DEFAULT_DOMAIN) {
            Log.w(TAG, "Refused a request to another site: ${url.host}")
            return response(403, "Forbidden")
        }
        val path = url.path.orEmpty()
        if (url.scheme == "https" && path.startsWith("/assets/")) return null
        if (url.scheme != "https" || !path.startsWith("/api/")) {
            Log.w(TAG, "Refused a request outside the reader's assets and API: ${url.scheme} $path")
            return response(403, "Forbidden")
        }
        val get = request.method.equals("GET", ignoreCase = true)
        return when {
            get && wholeFile && isFileRoute(url.pathSegments, fileId) -> serveLocalFile() ?: proxyFile()
            get && !wholeFile && isReaderRoute(url.pathSegments, url.getQueryParameter("fileId"), bookId, fileId) ->
                serveLocal(url) ?: proxy(url)
            else -> {
                Log.w(TAG, "Refused a request the reader doesn't make: ${request.method} ${url.path}")
                response(403, "Forbidden")
            }
        }
    }

    /** `epub/<bookId>/info` and `epub/<bookId>/file/...` of a downloaded book, from the device. */
    private fun serveLocal(url: Uri): WebResourceResponse? {
        val epub = ((local ?: runBlocking { awaitLocal() }) as? LocalBook.Epub)?.epub ?: return null
        val segments = url.pathSegments // each one percent-decoded, as the server's router does
        return when (segments[4]) {
            "info" -> WebResourceResponse("application/json", "utf-8", 200, "OK", HEADERS, ByteArrayInputStream(epub.info))
            else -> {
                val entry = runCatching { epub.open(segments.drop(5).joinToString("/")) }.getOrNull()
                    ?: return response(404, "Not Found")
                WebResourceResponse(entry.mimeType, null, 200, "OK", HEADERS, entry.stream)
            }
        }
    }

    /** The whole downloaded file (KEPUB, MOBI, AZW3, AZW, FB2), from the device. */
    private fun serveLocalFile(): WebResourceResponse? {
        val copy = ((local ?: runBlocking { awaitLocal() }) as? LocalBook.Whole)?.copy ?: return null
        val stream = runCatching { FileInputStream(copy.file) }.getOrNull() ?: return null
        val headers = HEADERS + ("Content-Length" to copy.file.length().toString())
        return WebResourceResponse(OCTET_STREAM, null, 200, "OK", headers, stream)
    }

    /** `books/files/<fileId>/serve` from the server, streamed as it arrives. */
    private fun proxyFile(): WebResourceResponse = try {
        val request = Request.Builder().url(api.serverUrl("/api/v1/books/files/$fileId/serve")).get().build()
        val response = fileClient.newCall(request).execute()
        val code = response.code.takeIf { it in 200..299 || it in 400..599 } ?: 502
        val length = response.body.contentLength().takeIf { it >= 0 && code in 200..299 }
        WebResourceResponse(
            OCTET_STREAM, null, code, response.message.ifEmpty { if (code < 300) "OK" else "Error" },
            if (length != null) HEADERS + ("Content-Length" to length.toString()) else HEADERS,
            response.body.byteStream(),
        )
    } catch (e: Exception) {
        response(502, "Bad Gateway", e.message ?: "proxy error")
    }

    private fun proxy(url: Uri): WebResourceResponse = try {
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val response = api.proxy(path)
        val contentType = response.header("Content-Type") ?: "application/octet-stream"
        val mime = contentType.substringBefore(';').trim()
        val charset = contentType.substringAfter("charset=", "").trim().ifEmpty { null }
        val code = response.code.takeIf { it in 200..299 || it in 400..599 } ?: 502
        WebResourceResponse(
            mime, charset, code, response.message.ifEmpty { if (code < 300) "OK" else "Error" },
            HEADERS, response.body.byteStream(),
        )
    } catch (e: Exception) {
        response(502, "Bad Gateway", e.message ?: "proxy error")
    }

    private fun response(code: Int, reason: String, body: String = "") =
        WebResourceResponse("text/plain", "utf-8", code, reason, HEADERS, ByteArrayInputStream(body.toByteArray()))

    companion object {
        private const val TAG = "Reader"

        /**
         * On every answer. `no-store`: nothing from a book lands in the WebView's cache. The policy:
         * foliate fetches each file and shows it from a blob: URL, which the policy never reaches
         * (fetch ignores it), so chapters, their images and fonts are as before; but a file a book
         * loads as a document itself (a frame, a refresh) is sandboxed: no scripts, and an origin of
         * its own. `nosniff`: a file is only what its type says (never a script or stylesheet by
         * its content). The server's answers carry a policy and nosniff of their own (helmet), which
         * aren't forwarded.
         */
        private val HEADERS = mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "sandbox; default-src 'none'",
            "X-Content-Type-Options" to "nosniff",
        )

        /**
         * Whether a request is one of the reader's own for [bookId] / [fileId]: `api/v1/epub/<bookId>/info`
         * or `api/v1/epub/<bookId>/file/<path...>`, with `fileId=<fileId>`. [segments] are percent-decoded.
         */
        fun isReaderRoute(segments: List<String>, fileIdParam: String?, bookId: Long, fileId: Long): Boolean {
            if (segments.size < 5 || segments[0] != "api" || segments[1] != "v1" || segments[2] != "epub") return false
            if (segments[3] != bookId.toString() || fileIdParam != fileId.toString()) return false
            if (segments.any { it == ".." || it == "." }) return false
            return (segments.size == 5 && segments[4] == "info") || (segments[4] == "file" && segments.size > 5)
        }

        /** Whether a request is exactly `api/v1/books/files/<fileId>/serve` (the whole file). [segments] are percent-decoded. */
        fun isFileRoute(segments: List<String>, fileId: Long): Boolean =
            segments == listOf("api", "v1", "books", "files", fileId.toString(), "serve")

        /**
         * Whether the foliate reader opens [format] as one whole file (foliate's makeBook, as the web
         * reader does) rather than streaming it through the server's epub routes, which serve EPUB
         * files only: KEPUB, MOBI, AZW3, AZW and FB2. Null (an older route) is an EPUB.
         */
        fun readsWholeFile(format: String?): Boolean {
            val f = BookFormats.normalize(format) ?: return false
            return f != "epub" && BookFormats.readerFor(f) == ReaderKind.Foliate
        }

        private const val OCTET_STREAM = "application/octet-stream"
    }
}

/** The reader's settings on this device (the app's settings DataStore, key `reader.prefs`). */
internal class ReaderPrefsStore(private val store: DataStore<Preferences>) {

    val prefs: Flow<ReaderPrefs> = store.data.map { decode(it[KEY]) }

    suspend fun save(prefs: ReaderPrefs) {
        store.edit { it[KEY] = ApiJson.encodeToString(ReaderPrefs.serializer(), prefs) }
    }

    private fun decode(raw: String?): ReaderPrefs =
        raw?.let { runCatching { ApiJson.decodeFromString(ReaderPrefs.serializer(), it) }.getOrNull() } ?: ReaderPrefs()

    private companion object {
        val KEY = stringPreferencesKey("reader.prefs")
    }
}
