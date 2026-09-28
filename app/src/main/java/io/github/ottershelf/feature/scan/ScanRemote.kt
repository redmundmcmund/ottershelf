package io.github.ottershelf.feature.scan

import kotlinx.coroutines.flow.Flow
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson

/** The scanner's reads, behind an interface for the tests. They throw the Api's exceptions. */
interface ScanRemote {
    /** The library books whose metadata holds [isbn] in either form ([isbnQuery]). */
    suspend fun byIsbn(isbn: Isbn): List<ScanCard>

    /** The library's quick search for a title ([titleQuery]). */
    suspend fun byTitle(title: String): List<ScanCard>

    /** What the metadata providers know about [isbn], as each answers (`metadata-fetch/stream`). */
    fun lookUp(isbn: Isbn): Flow<MetadataCandidate>

    /** A card's cover for [io.github.ottershelf.ui.components.BookCover], or null for none. */
    fun cover(card: ScanCard): Any?
}

class ApiScanRemote(private val api: Api) : ScanRemote {

    override suspend fun byIsbn(isbn: Isbn): List<ScanCard> = query(isbnQuery(isbn))

    override suspend fun byTitle(title: String): List<ScanCard> = query(titleQuery(title))

    private suspend fun query(body: ScanQueryBody): List<ScanCard> =
        api.send("POST", "books/query", ApiJson.encodeToJsonElement(ScanQueryBody.serializer(), body)) {
            ApiJson.decodeFromString(ScanBooksPage.serializer(), it)
        }.items

    // An e-book search: the library holds e-books, and a request is for one (as on Book requests).
    override fun lookUp(isbn: Isbn): Flow<MetadataCandidate> = api.searchMetadata("", "", MEDIA_KIND, isbn = isbn.isbn13)

    override fun cover(card: ScanCard): Any? =
        if (card.hasCover) runCatching { api.thumbnailUrl(card.id, card.updatedAt ?: card.addedAt) }.getOrNull() else null

    private companion object {
        const val MEDIA_KIND = "ebook"
    }
}
