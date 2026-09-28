package io.github.ottershelf.feature.quotes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookAnnotationsPage

/**
 * `POST books/:bookId/annotations` for a quote: exactly the server's `CreateAnnotationDto` fields
 * this feature uses (it rejects unknown ones). `cfi` is the placeholder ([QuotePosition]); no
 * `bookFileId`, so the position belongs to no file. Null fields are left out (`ApiJson`).
 */
@Serializable
data class CreateQuoteBody(
    val cfi: String,
    val text: String,
    val color: String,
    val note: String? = null,
    val chapterTitle: String? = null,
)

/** A book a quote can go to: the picker's rows and the form's book card. */
data class QuoteBook(val id: Long, val title: String?, val authors: String?, val cover: Any?)

interface QuotesRemote {
    suspend fun create(bookId: Long, body: CreateQuoteBody): Annotation

    /** The book's newest annotations (to find a quote whose save was cut off). */
    suspend fun newest(bookId: Long, count: Int): List<Annotation>

    /** A quote's thought and colour changed (`PATCH`, `UpdateAnnotationDto`: text and page can't change). */
    suspend fun update(bookId: Long, id: Long, note: String?, color: String): Annotation

    /** A quote moved to the web's trash. */
    suspend fun delete(bookId: Long, id: Long)

    suspend fun book(bookId: Long): QuoteBook

    /** What the user is reading now (the Currently Reading widget), for the picker's first rows. */
    suspend fun reading(): List<QuoteBook>

    /** The library searched by title or author, best first. */
    suspend fun search(query: String): List<QuoteBook>
}

class ApiQuotesRemote(private val api: Api) : QuotesRemote {
    override suspend fun create(bookId: Long, body: CreateQuoteBody): Annotation =
        api.send("POST", "books/$bookId/annotations", ApiJson.encodeToJsonElement(CreateQuoteBody.serializer(), body)) {
            ApiJson.decodeFromString(Annotation.serializer(), it)
        }

    override suspend fun newest(bookId: Long, count: Int): List<Annotation> =
        api.send("GET", "books/$bookId/annotations?page=1&pageSize=$count&sortBy=createdAt&sortDir=desc", null) {
            ApiJson.decodeFromString(BookAnnotationsPage.serializer(), it)
        }.items

    override suspend fun update(bookId: Long, id: Long, note: String?, color: String): Annotation {
        // A missing thought clears it (the DTO takes null for that).
        val body = JsonObject(mapOf("note" to (note?.let(::JsonPrimitive) ?: JsonNull), "color" to JsonPrimitive(color)))
        return api.send("PATCH", "books/$bookId/annotations/$id", body) { ApiJson.decodeFromString(Annotation.serializer(), it) }
    }

    override suspend fun delete(bookId: Long, id: Long) {
        api.send("DELETE", "books/$bookId/annotations/$id", null) { }
    }

    override suspend fun book(bookId: Long): QuoteBook {
        val book = api.book(bookId)
        return QuoteBook(
            id = book.id,
            title = book.title,
            authors = book.authors.joinToString(", ") { it.name }.ifBlank { null },
            cover = book.coverSource?.let { runCatching { api.thumbnailUrl(book.id, book.updatedAt) }.getOrNull() },
        )
    }

    override suspend fun reading(): List<QuoteBook> =
        api.dashboardWidgets().first?.books.orEmpty().map {
            QuoteBook(
                id = it.bookId,
                title = it.title,
                authors = it.authors.joinToString(", ").ifBlank { null },
                cover = if (it.hasCover) runCatching { api.unversionedThumbnailUrl(it.bookId) }.getOrNull() else null,
            )
        }

    override suspend fun search(query: String): List<QuoteBook> =
        api.books(BookSource.All(""), page = 0, size = 30, query = query).items.map {
            QuoteBook(
                id = it.id,
                title = it.title,
                authors = it.authors.joinToString(", ").ifBlank { null },
                cover = runCatching { api.thumbnailUrl(it) }.getOrNull(),
            )
        }
}
