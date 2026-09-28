package io.github.ottershelf.core.format

import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import java.util.Locale

/** Which of the app's readers opens a file. */
enum class ReaderKind {
    /** The foliate WebView reader (feature.reader): EPUB, KEPUB, MOBI, AZW3, AZW, FB2. */
    Foliate,

    /** The comics reader (feature.comics): CBZ, CBR, CB7 (the server extracts CBR/CB7 pages). */
    Comics,

    /** The PDF reader (feature.pdf). */
    Pdf,
}

/**
 * Book file formats as the app reads, opens and keeps them (packages/types: reader-settings.ts
 * `READER_OPENABLE_FORMATS` / `FORMAT_TO_GROUP`, library.ts `DEFAULT_FORMAT_PRIORITY`). Pure.
 *
 * Differences from the web: audio formats are left out (no audiobooks in this app), and KEPUB goes to
 * the foliate reader (it is an EPUB with Kobo spans; the web doesn't open it at all). Formats are
 * compared lower case.
 */
object BookFormats {

    /** The web's `DEFAULT_FORMAT_PRIORITY`, the reading formats only: a library with no priority of its own. */
    val DEFAULT_PRIORITY: List<String> = listOf("epub", "kepub", "pdf", "cbz", "cbr", "cb7", "mobi", "azw3", "azw", "fb2")

    private val READERS: Map<String, ReaderKind> = mapOf(
        "epub" to ReaderKind.Foliate,
        "kepub" to ReaderKind.Foliate,
        "mobi" to ReaderKind.Foliate,
        "azw3" to ReaderKind.Foliate,
        "azw" to ReaderKind.Foliate,
        "fb2" to ReaderKind.Foliate,
        "pdf" to ReaderKind.Pdf,
        "cbz" to ReaderKind.Comics,
        "cbr" to ReaderKind.Comics,
        "cb7" to ReaderKind.Comics,
    )

    /**
     * Formats a copy can be kept of for offline reading: every openable one except CBR and CB7. The
     * server extracts those archives itself and serves their pages as images (`cbz/files/:id/pages`);
     * the phone has no RAR or 7z reader, so the raw file would be unreadable offline. A comic kept
     * offline is therefore always a CBZ, read from the zip on the device.
     */
    private val OFFLINE: Set<String> = setOf("epub", "kepub", "mobi", "azw3", "azw", "fb2", "pdf", "cbz")

    /** [format] lower case and trimmed, or null. */
    fun normalize(format: String?): String? = format?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /** The reader that opens [format], or null when the app can't open it (audio, djvu, txt...). */
    fun readerFor(format: String?): ReaderKind? = normalize(format)?.let { READERS[it] }

    fun isOpenable(format: String?): Boolean = readerFor(format) != null

    /** A copy of a file in [format] can be downloaded and read offline (see [OFFLINE]: never CBR/CB7). */
    fun canKeepOffline(format: String?): Boolean = normalize(format) in OFFLINE

    /** Page-based readers report a page number with their position (ProgressStore / PageProgress). */
    fun isPaged(format: String?): Boolean = readerFor(format).let { it == ReaderKind.Comics || it == ReaderKind.Pdf }

    /** The file extension a downloaded copy is stored under (`book.<ext>`): the format itself. */
    fun extension(format: String?): String = normalize(format) ?: "epub"

    /**
     * The file Read opens, the way the web picks it: among the files a reader here opens, the
     * primary one; otherwise the first by the library's format priority ([priority], the book's
     * `formatPriority`; the web's default when empty), files of a format not in it last, in their
     * order. Null when no file can be opened.
     */
    fun pickFile(files: List<BookFile>, priority: List<String> = emptyList()): BookFile? =
        pick(files.filter { isOpenable(it.format) }, priority)

    /** [pickFile] for a book page. */
    fun pickFile(book: BookDetail): BookFile? = pickFile(book.files, book.formatPriority)

    /**
     * The file a download keeps: the same pick among the files that can be kept offline. For a book
     * whose Read file is a CBR or CB7 this is its CBZ, if it has one (else nothing is offered).
     */
    fun pickOfflineFile(files: List<BookFile>, priority: List<String> = emptyList()): BookFile? =
        pick(files.filter { canKeepOffline(it.format) }, priority)

    fun pickOfflineFile(book: BookDetail): BookFile? = pickOfflineFile(book.files, book.formatPriority)

    /**
     * A book whose Read file is in [format] opens its downloaded copy in [keptFormat] instead, online
     * too: a CBR or CB7 comic can't be kept, so its download is the CBZ beside it
     * ([pickOfflineFile]), and positions are kept per file, so reading one online and the other
     * offline would split the user's place between two files.
     */
    fun readsKeptCopyInstead(format: String?, keptFormat: String?): Boolean =
        readerFor(format) == ReaderKind.Comics && !canKeepOffline(format) &&
            readerFor(keptFormat) == ReaderKind.Comics && canKeepOffline(keptFormat)

    private fun pick(candidates: List<BookFile>, priority: List<String>): BookFile? {
        candidates.firstOrNull { it.role == PRIMARY }?.let { return it }
        val order = priority.mapNotNull { normalize(it) }.ifEmpty { DEFAULT_PRIORITY }
        // sortedBy is stable: files of the same (or an unranked) format keep the server's order.
        return candidates.sortedBy { file -> order.indexOf(normalize(file.format)).let { if (it < 0) Int.MAX_VALUE else it } }.firstOrNull()
    }

    private const val PRIMARY = "primary"
}
