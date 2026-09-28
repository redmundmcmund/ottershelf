package io.github.ottershelf.feature.book

import io.github.ottershelf.core.download.DownloadedBook
import io.github.ottershelf.core.download.Downloads
import io.github.ottershelf.core.model.BookFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Nexus rules for Read and the download button, as BookDetailUiState computes them. */
class BookDetailStateTest {

    private val epub = BookFile(11, "epub", "primary", 1000)
    private val copy = DownloadedBook(bookId = 1, fileId = 10, sizeBytes = 900)
    private val base = BookDetailUiState(bookId = 1, book = sampleBook, loading = false, loaded = true, readFile = epub, offlineFile = epub)

    @Test
    fun readOpensTheServersFileOnlineAndTheCopyOffline() {
        val withCopy = base.copy(downloaded = copy)
        assertEquals(ReadTarget(11L, "epub"), withCopy.readTarget(online = true))
        assertEquals(ReadTarget(10L, "epub"), withCopy.readTarget(online = false))
        assertEquals(ReadTarget(10L, "epub"), withCopy.copy(loaded = false).readTarget(online = true))
        assertEquals(ReadTarget(11L, "epub"), base.readTarget(online = false))
        assertNull(base.copy(readFile = null).readTarget(online = true))
    }

    @Test
    fun readNamesTheFormatUnlessItIsAnEpub() {
        assertNull(base.readLabelFormat)
        val pdf = BookFile(12, "pdf", "primary", 1000)
        assertEquals("PDF", base.copy(readFile = pdf).readLabelFormat)
        // Offline with only a downloaded CBZ: the copy's format.
        assertEquals("CBZ", base.copy(readFile = null, downloaded = copy.copy(format = "cbz")).readLabelFormat)
        assertEquals(ReadTarget(10L, "cbz"), base.copy(readFile = null, downloaded = copy.copy(format = "cbz")).readTarget(online = false))
    }

    @Test
    fun aCbrComicReadsItsKeptCbzOnlineToo() {
        // A CBR primary with a CBZ beside it: the download keeps the CBZ (a CBR can't be kept).
        val cbr = BookFile(20, "cbr", "primary", 1000)
        val cbz = BookFile(21, "cbz", null, 900)
        val kept = DownloadedBook(bookId = 1, fileId = 21, sizeBytes = 900, format = "cbz")
        val comic = base.copy(book = sampleBook.copy(files = listOf(cbr, cbz)), readFile = cbr, offlineFile = cbz, downloaded = kept, downloadIsCurrent = true)
        // One file online and offline, so the user's place isn't split between two.
        assertEquals(ReadTarget(21L, "cbz"), comic.readTarget(online = true))
        assertEquals(ReadTarget(21L, "cbz"), comic.readTarget(online = false))
        assertEquals("CBZ", comic.readLabelFormat)
        // Not downloaded: the CBR, which the server extracts.
        assertEquals(ReadTarget(20L, "cbr"), comic.copy(downloaded = null).readTarget(online = true))
        assertEquals("CBR", comic.copy(downloaded = null).readLabelFormat)
        // An Update waiting, or the kept file gone from the server: the server's file wins online.
        assertEquals(ReadTarget(20L, "cbr"), comic.copy(downloadIsCurrent = false).readTarget(online = true))
        assertEquals(ReadTarget(20L, "cbr"), comic.copy(book = sampleBook.copy(files = listOf(cbr))).readTarget(online = true))
        // Keepable Read files keep the Nexus rule: the server's file online.
        val pdf = BookFile(22, "pdf", "primary", 1000)
        assertEquals(ReadTarget(22L, "pdf"), comic.copy(readFile = pdf).readTarget(online = true))
    }

    @Test
    fun downloadButtonFollowsTheDownloadAndTheCopy() {
        assertEquals(DownloadButton.Download, base.downloadButton)
        assertEquals(DownloadAction.Start, base.downloadAction)
        val running = base.copy(download = Downloads.Progress(done = 250, total = 1000))
        assertEquals(DownloadButton.Downloading(percent = 25, fraction = 0.25f, waiting = false), running.downloadButton)
        assertEquals(DownloadAction.ConfirmStop, running.downloadAction)
        val current = base.copy(downloaded = copy, downloadIsCurrent = true)
        assertEquals(DownloadButton.Downloaded, current.downloadButton)
        assertEquals(DownloadAction.ConfirmRemove, current.downloadAction)
        val stale = base.copy(downloaded = copy, downloadIsCurrent = false)
        assertEquals(DownloadButton.Update, stale.downloadButton)
        assertEquals(DownloadAction.ConfirmUpdate, stale.downloadAction)
    }

    @Test
    fun downloadButtonHidesWithoutPermissionOrAKeepableFileButStaysForACopy() {
        assertEquals(DownloadButton.Hidden, base.copy(downloadAllowed = false).downloadButton)
        // A CBR-only comic: readable (the server extracts it), but nothing can be kept offline.
        val cbr = BookFile(13, "cbr", "primary", 1000)
        assertEquals(DownloadButton.Hidden, base.copy(readFile = cbr, offlineFile = null).downloadButton)
        // The only way to remove a copy the server no longer has.
        assertEquals(DownloadButton.Downloaded, base.copy(offlineFile = null, downloaded = copy).downloadButton)
        assertEquals(DownloadAction.ConfirmRemove, base.copy(offlineFile = null, downloaded = copy).downloadAction)
    }
}
