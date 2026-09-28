package io.github.ottershelf.feature.pdf

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.ottershelf.core.download.OfflineCopy
import io.github.ottershelf.core.download.OfflineFiles
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Where the PDF reader gets its file: the downloaded copy (core.download, `Downloads.openOffline`)
 * if this exact file is kept offline; else the server's `GET books/files/:fileId/serve` (the route
 * the web reader reads, not the download route, so opening a book isn't counted as a download),
 * streamed with the authenticated client into `cacheDir/pdf/<account>/<fileId>.pdf`.
 *
 * The cached copy is reused while its length matches the server's (a one-byte range request asks
 * for it), and offline or when the server doesn't answer. When the server refuses the file (403: no
 * access to it any more; 404: gone), the copy is deleted and the refusal shown, as for a download.
 * The last [KEEP] files are kept; the system may clear the cache at any time. A download shorter
 * than its Content-Length, or without a `%PDF-` header, isn't kept.
 */
internal class PdfFiles(
    private val root: File,
    private val client: () -> OkHttpClient,
    private val serveUrl: (fileId: Long) -> String,
    private val offline: (bookId: Long, fileId: Long) -> OfflineCopy?,
) {
    class OfflineException : IOException("No connection")

    /** The server refused the file: [code] 403 or 404, with the message a download shows. */
    class RefusedException(val code: Int) : IOException(refusal(code))

    /**
     * [local]: the file was already on the phone (a download or the cache), so the server needn't be
     * waited for. [cached]: it is this reader's cached copy (not the download), so a copy that won't
     * open may be deleted and fetched again.
     */
    data class Source(val file: File, val local: Boolean, val cached: Boolean)

    private fun cached(fileId: Long) = File(root, "$fileId.pdf")

    /** Whether the file is on the phone already (reads the disk). */
    fun onPhone(bookId: Long, fileId: Long): Boolean =
        offline(bookId, fileId)?.format == "pdf" || cached(fileId).isFile

    /** The file to open; [onProgress] 0..1 while downloading (null: size unknown). */
    suspend fun obtain(bookId: Long, fileId: Long, online: Boolean, onProgress: (Float?) -> Unit): Source = withContext(Dispatchers.IO) {
        offline(bookId, fileId)?.takeIf { it.format == "pdf" && it.file.isFile }?.let { return@withContext Source(it.file, local = true, cached = false) }
        val target = cached(fileId)
        if (target.isFile) {
            val current = !online || isCurrent(fileId, target)
            if (current) {
                target.setLastModified(System.currentTimeMillis())
                return@withContext Source(target, local = true, cached = true)
            }
        }
        if (!online) throw OfflineException()
        download(fileId, target, onProgress)
        trim(target)
        Source(target, local = false, cached = true)
    }

    /**
     * Whether the cached [target] is still the server's file: the same size, or no answer (network
     * down, a timeout, 5xx). A refusal deletes it and throws [RefusedException].
     */
    private fun isCurrent(fileId: Long, target: File): Boolean {
        val size = try {
            remoteSize(fileId)
        } catch (e: RefusedException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            null
        }
        return size == null || size == target.length()
    }

    /** The file's size on the server, from a one-byte range request (`Content-Range: bytes 0-0/<size>`). */
    private fun remoteSize(fileId: Long): Long? {
        val request = Request.Builder().url(serveUrl(fileId)).header("Range", "bytes=0-0").get().build()
        return client().newCall(request).execute().use { response ->
            when (response.code) {
                206 -> response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                200 -> response.body.contentLength().takeIf { it >= 0 }
                403, 404 -> throw RefusedException(response.code)
                else -> null
            }
        }
    }

    private suspend fun download(fileId: Long, target: File, onProgress: (Float?) -> Unit) = coroutineScope {
        root.mkdirs()
        val part = File(root, "$fileId.pdf.part")
        val call = client().newCall(Request.Builder().url(serveUrl(fileId)).get().build())
        // Leaving the reader cancels the transfer at once, even mid-read.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException(refusal(response.code))
                val body = response.body
                val total = body.contentLength()
                onProgress(if (total > 0) 0f else null)
                var read = 0L
                var shown = -1
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = (read * 100 / total).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    onProgress(read.toFloat() / total)
                                }
                            }
                        }
                    }
                }
                if (total > 0 && read != total) throw IOException("The download was cut short")
            }
            OfflineFiles.problem("pdf", part)?.let { throw IOException(it) }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't keep the file")
        } finally {
            watcher.cancel()
            part.delete()
        }
    }

    /** Keeps the [KEEP] most recently opened files (always [current]); drops leftovers of broken transfers. */
    private fun trim(current: File) {
        val files = root.listFiles() ?: return
        files.filter { it.name.endsWith(".part") }.forEach { it.delete() }
        files.filter { it.name.endsWith(".pdf") && it != current }
            .sortedByDescending { it.lastModified() }
            .drop(KEEP - 1)
            .forEach { it.delete() }
    }

    companion object {
        const val KEEP = 3

        /** The copies' folder in `cacheDir`, one subfolder per account; sign-out deletes it (AppContainer). */
        const val CACHE_DIR = "pdf"

        /** What an error [code] from the serve route says (shown on the reader's error card). */
        fun refusal(code: Int): String = if (code == 403) "Not allowed to read this file" else "The server said $code"
    }
}
