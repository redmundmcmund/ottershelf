package io.github.ottershelf.feature.downloads

import io.github.ottershelf.core.download.DownloadedBook
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ReadStatusInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Downloaded's cards also open the book page (the quick view's Book page puts the card in its
 * preview), which asks for the server's thumbnail by the card's dates: they must be real ones.
 */
class DownloadedCardTest {

    private val download = DownloadedBook(bookId = 7, fileId = 70, title = "Dracula", authors = listOf("Bram Stoker"), format = "epub")

    @Test
    fun theCardCarriesThePagesDatesSoItsThumbnailIsVersioned() {
        val detail = BookDetail(7, title = "Dracula", addedAt = "2025-01-02T03:04:05Z", updatedAt = "2026-05-06T07:08:09Z", readStatus = ReadStatusInfo("reading"))
        val card = downloadedCard(download, detail, hasThumbnail = true, readingProgress = 42.0)
        assertEquals("2026-05-06T07:08:09Z", card.updatedAt)
        assertEquals("2025-01-02T03:04:05Z", card.addedAt)
        assertTrue(card.hasCover)
        assertEquals(42.0, card.readingProgress!!, 0.0)
        assertEquals("reading", card.readStatus?.status)
    }

    @Test
    fun withoutDatesNoServerThumbnailIsClaimed() {
        // A made-up version (?t=0) would be cached for good; the page loads its own instead.
        assertFalse(downloadedCard(download, detail = null, hasThumbnail = true, readingProgress = null).hasCover)
        assertFalse(downloadedCard(download, BookDetail(7), hasThumbnail = true, readingProgress = null).hasCover)
        assertFalse(downloadedCard(download, BookDetail(7, addedAt = "2025-01-02T03:04:05Z"), hasThumbnail = false, readingProgress = null).hasCover)
    }
}
