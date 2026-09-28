package io.github.ottershelf.core.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** The fields of `epub/:bookId/info` this app reads (packages/types/src/epub.ts on the server). */
@Serializable
data class EpubInfo(val manifest: List<Item> = emptyList()) {
    @Serializable
    data class Item(val href: String = "", val mediaType: String? = null, val size: Long = 0)
}

/**
 * A downloaded book served to the reader in place of the server's `epub/:bookId/info` and
 * `epub/:bookId/file/<path>`, with the same answers: the server's own info document (saved at
 * download time, so section sizes and therefore progress fractions match the web reader exactly)
 * and the raw zip entries, looked up the way the server does (epub.service.ts `findInZip`: paths
 * normalised, exact match first, then case-insensitive). The server serves entries unmodified, so
 * this is byte for byte what the reader would otherwise have fetched.
 */
class LocalEpub(dir: File) : Closeable {

    val info: ByteArray = File(dir, "info.json").readBytes()
    private val zip = ZipFile(File(dir, "book.epub"))
    private val exact = HashMap<String, ZipEntry>()
    private val lower = HashMap<String, ZipEntry>()
    private val mediaTypes = HashMap<String, String>()

    init {
        // First entry wins for duplicate names, as on the server.
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            val name = normalize(entry.name)
            if (name.isEmpty()) continue
            exact.putIfAbsent(name, entry)
            lower.putIfAbsent(name.lowercase(Locale.ROOT), entry)
        }
        runCatching { parseInfo(info) }.getOrNull()?.manifest?.forEach { item ->
            item.mediaType?.let { mediaTypes.putIfAbsent(normalize(item.href), it) }
        }
    }

    class Entry(val stream: InputStream, val size: Long, val mimeType: String)

    /**
     * [path] is the part after `file/`, each segment already percent-decoded once (as the server's
     * router does). foliate's hrefs can still carry escapes (it uses decodeURI), so a fully decoded
     * form is tried too.
     */
    fun open(path: String): Entry? {
        for (candidate in listOf(path, percentDecode(path)).distinct()) {
            val name = normalize(candidate)
            if (name.isEmpty()) continue
            val entry = exact[name] ?: lower[name.lowercase(Locale.ROOT)] ?: continue
            return Entry(zip.getInputStream(entry), entry.size, mediaTypes[name] ?: mimeFor(name))
        }
        return null
    }

    /** Every manifest item's size matches the zip, i.e. [info] describes this exact file. */
    fun matchesInfo(): Boolean {
        val manifest = runCatching { parseInfo(info) }.getOrNull()?.manifest ?: return false
        return manifest.all { item ->
            val name = normalize(item.href)
            val entry = exact[name] ?: lower[name.lowercase(Locale.ROOT)]
            // The server reports 0 for items missing from the zip; so should we.
            (entry?.size ?: 0L) == item.size
        }
    }

    override fun close() = zip.close()

    companion object {
        private val infoJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

        fun parseInfo(bytes: ByteArray): EpubInfo = infoJson.decodeFromString(EpubInfo.serializer(), bytes.decodeToString())

        /**
         * epub.service.ts `normalizeZipPath`, applied (as there) to zip entry names and requested
         * paths alike: backslashes to `/`, decodeURI, then empty, `.` and `..` segments resolved.
         */
        fun normalize(path: String): String {
            val resolved = ArrayList<String>()
            for (part in decodeUri(path.replace('\\', '/')).split('/')) {
                when (part) {
                    "", "." -> {}
                    ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
                    else -> resolved += part
                }
            }
            return resolved.joinToString("/")
        }

        private const val URI_RESERVED = ";/?:@&=+$,#"

        /**
         * JavaScript's decodeURI (escapes of reserved characters stay escaped), returning [s] unchanged
         * if it isn't validly escaped, like the server's safeDecodeURI.
         */
        fun decodeUri(s: String): String {
            if ('%' !in s) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] != '%') {
                    out.append(s[i++])
                    continue
                }
                val first = hexByte(s, i) ?: return s
                if (first < 0x80) {
                    val c = first.toChar()
                    if (c in URI_RESERVED) out.append(s, i, i + 3) else out.append(c)
                    i += 3
                    continue
                }
                val length = when {
                    first and 0xE0 == 0xC0 -> 2
                    first and 0xF0 == 0xE0 -> 3
                    first and 0xF8 == 0xF0 -> 4
                    else -> return s
                }
                val bytes = ByteArray(length)
                for (k in 0 until length) {
                    val b = hexByte(s, i + 3 * k) ?: return s
                    if (k > 0 && b and 0xC0 != 0x80) return s
                    bytes[k] = b.toByte()
                }
                out.append(String(bytes, Charsets.UTF_8))
                i += 3 * length
            }
            return out.toString()
        }

        /**
         * Every `%XX` escape decoded as UTF-8, reserved characters included and `+` left alone, as
         * android.net.Uri.decode does (replaced so this class runs on a plain JVM in unit tests). A
         * `%` without two hex digits after it stays as it is; malformed UTF-8 becomes U+FFFD.
         */
        fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = StringBuilder(s.length)
            val pending = java.io.ByteArrayOutputStream()
            fun flush() {
                if (pending.size() == 0) return
                out.append(String(pending.toByteArray(), Charsets.UTF_8))
                pending.reset()
            }
            var i = 0
            while (i < s.length) {
                val b = hexByte(s, i)
                if (b != null) {
                    pending.write(b)
                    i += 3
                } else {
                    flush()
                    out.append(s[i++])
                }
            }
            flush()
            return out.toString()
        }

        private fun hexByte(s: String, i: Int): Int? {
            if (i + 2 >= s.length || s[i] != '%') return null
            val high = Character.digit(s[i + 1], 16)
            val low = Character.digit(s[i + 2], 16)
            return if (high < 0 || low < 0) null else high * 16 + low
        }

        // The server's CONTENT_TYPES (epub.service.ts), used when the manifest doesn't list the file.
        // foliate re-types its blobs from the OPF anyway.
        private val CONTENT_TYPES = mapOf(
            "xhtml" to "application/xhtml+xml", "html" to "application/xhtml+xml", "htm" to "application/xhtml+xml",
            "css" to "text/css", "js" to "application/javascript",
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
            "svg" to "image/svg+xml", "webp" to "image/webp",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "eot" to "application/vnd.ms-fontobject",
            "xml" to "application/xml", "ncx" to "application/x-dtbncx+xml", "opf" to "application/oebps-package+xml",
            "smil" to "application/smil+xml",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "opus" to "audio/ogg", "ogg" to "audio/ogg",
            "mp4" to "video/mp4", "webm" to "video/webm",
        )

        private fun mimeFor(name: String): String =
            CONTENT_TYPES[name.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)]
                ?: "application/octet-stream"
    }
}
