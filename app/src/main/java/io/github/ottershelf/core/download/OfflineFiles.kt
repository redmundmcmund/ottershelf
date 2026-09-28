package io.github.ottershelf.core.download

import io.github.ottershelf.core.format.BookFormats
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A downloaded book's file as a reader opens it offline ([Downloads.openOffline]): [file] is the
 * original, byte for byte as the server sent it, in [format] (lower case). EPUBs also have their
 * server `info.json` beside it and open through [LocalEpub] ([Downloads.openEpub]); a comic is
 * always a CBZ (CBR and CB7 aren't kept offline, see BookFormats.canKeepOffline).
 */
data class OfflineCopy(val bookId: Long, val fileId: Long, val format: String, val file: File)

/**
 * Naming and checks for the kept file of any format (the folder layout is in [Downloads]). Pure JVM.
 */
internal object OfflineFiles {

    /** `book.<format>`: `book.epub` as it always was, `book.pdf`, `book.cbz`, `book.mobi`... */
    fun name(format: String?): String = "book.${BookFormats.extension(format)}"

    /** The server's page extensions (cbz.service.ts `IMAGE_EXTS`). */
    private val CBZ_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

    /**
     * The most one CBZ page may inflate to. A real page, even a large PNG scan or a webtoon strip, is
     * tens of MB; a zip bomb's page is gigabytes of nothing, written into the comics disk cache.
     */
    const val MAX_CBZ_PAGE_BYTES = 128L * 1024 * 1024

    /** What a CBZ's pages may inflate to together, per byte of the file: images barely compress. */
    private const val MAX_CBZ_EXPANSION = 100

    /**
     * Whether a zip entry is a page of a CBZ as the server lists them (cbz.service.ts `getCbzIndex`):
     * a file, no hidden path part (a segment starting with a dot: `__MACOSX/._01.jpg`, `.thumbs/`),
     * one of the server's image extensions, stored or deflated with some bytes. The comics reader
     * shows exactly these (feature.comics.LocalCbz orders them), so a kept CBZ is one it can page.
     */
    fun isCbzPage(name: String, directory: Boolean, method: Int, compressedSize: Long): Boolean =
        !directory && !name.endsWith("/") && !isHidden(name) && isCbzImage(name) &&
            (method == ZipEntry.STORED || method == ZipEntry.DEFLATED) && compressedSize > 0

    private fun isCbzImage(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        return dot != -1 && name.substring(dot + 1).lowercase(Locale.ROOT) in CBZ_IMAGE_EXTENSIONS
    }

    private fun isHidden(name: String): Boolean = name.split('/').any { it.startsWith(".") }

    /**
     * Why [file] can't be a readable [format] file, or null when it looks right. EPUBs are checked
     * separately (LocalEpub: container and info match). The others get a cheap signature check so a
     * truncated or non-book response fails now rather than later, offline, in the reader: a PDF's
     * `%PDF-` header, a CBZ's pages ([cbzProblem]), a KEPUB's zip; MOBI, AZW, AZW3 and FB2 are only
     * required to be non-empty (their variants are too many to police here).
     *
     * A zip that won't open is a problem only when the file is known to have arrived [whole].
     * Otherwise its end (where a zip keeps its directory) may be missing, and the IOException is
     * thrown so the download is tried again (DownloadWorker.isTransient), as for an EPUB.
     */
    fun problem(format: String?, file: File, whole: Boolean = true): String? {
        if (!file.isFile || file.length() == 0L) return "The download is empty"
        return when (BookFormats.normalize(format)) {
            "pdf" -> if (hasPdfHeader(file)) null else "Not a PDF file"
            "cbz" -> cbzProblem(file, whole)
            "kepub" -> kepubProblem(file, whole)
            else -> null
        }
    }

    /** The PDF spec allows the header anywhere in the first 1024 bytes. */
    private fun hasPdfHeader(file: File): Boolean {
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }
        if (n <= 0) return false
        return String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
    }

    private fun kepubProblem(file: File, whole: Boolean): String? = try {
        ZipFile(file).use { zip -> if (zip.entries().asSequence().none { !it.isDirectory }) "Not a KEPUB file" else null }
    } catch (e: IOException) {
        if (!whole) throw e
        "Not a KEPUB file"
    }

    /**
     * A CBZ needs a page the comics reader shows ([isCbzPage]), no page declaring more than
     * [MAX_CBZ_PAGE_BYTES], and pages declaring no more than [MAX_CBZ_EXPANSION] times the file
     * together (the reader also stops a page inflating past what it declares).
     */
    private fun cbzProblem(file: File, whole: Boolean): String? {
        try {
            ZipFile(file).use { zip ->
                var pages = 0
                var total = 0L
                for (entry in zip.entries()) {
                    if (!isCbzPage(entry.name, entry.isDirectory, entry.method, entry.compressedSize)) continue
                    if (entry.size !in 0..MAX_CBZ_PAGE_BYTES) return "A page in this CBZ file is too large"
                    pages++
                    total += entry.size
                }
                return when {
                    pages == 0 -> "No pages in this CBZ file"
                    total > file.length() * MAX_CBZ_EXPANSION -> "The pages of this CBZ file are too large"
                    else -> null
                }
            }
        } catch (e: IOException) {
            if (!whole) throw e
            return "Not a CBZ file"
        }
    }
}
