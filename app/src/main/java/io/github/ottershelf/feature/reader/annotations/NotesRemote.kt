package io.github.ottershelf.feature.reader.annotations

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson

/** The server's annotation and bookmark routes for one account (a fake in tests). */
interface NotesRemote {
    /** Every annotation of the book. */
    suspend fun annotations(bookId: Long): List<Annotation>
    suspend fun createAnnotation(bookId: Long, draft: AnnotationDraft): Annotation
    suspend fun updateAnnotation(bookId: Long, id: Long, note: String?, color: String?, style: String?): Annotation
    suspend fun deleteAnnotation(bookId: Long, id: Long)
    suspend fun bookmarks(bookId: Long): List<Bookmark>
    suspend fun createBookmark(bookId: Long, cfi: String, title: String): Bookmark
    suspend fun deleteBookmark(bookId: Long, id: Long)
}

/**
 * [NotesRemote] over [Api.send]. The bodies carry exactly the DTOs' fields
 * (`create-annotation.dto.ts`, `update-annotation.dto.ts`, `create-bookmark.dto.ts`): the server
 * rejects anything else.
 */
class ApiNotesRemote(private val api: Api) : NotesRemote {

    /**
     * The whole list in one answer, as the web reader loads it (`useAnnotations`). Only this unpaged
     * route runs the server's KOReader-to-CFI backfill (`ensureCfiPositionsForBook`, a bounded batch
     * per call), so KOReader highlights get a position the reader can draw, and moved ones a fresh one.
     */
    override suspend fun annotations(bookId: Long): List<Annotation> =
        api.send("GET", "books/$bookId/annotations", null) {
            ApiJson.decodeFromString(ListSerializer(Annotation.serializer()), it)
        }.distinctBy { it.id }

    override suspend fun createAnnotation(bookId: Long, draft: AnnotationDraft): Annotation {
        val body = buildJsonObject {
            put("cfi", draft.cfi)
            draft.bookFileId?.let { put("bookFileId", it) }
            put("text", draft.text)
            put("color", draft.color)
            put("style", draft.style)
            draft.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
            draft.chapterTitle?.takeIf { it.isNotBlank() }?.let { put("chapterTitle", it.take(MAX_CHAPTER)) }
        }
        return api.send("POST", "books/$bookId/annotations", body) { ApiJson.decodeFromString(Annotation.serializer(), it) }
    }

    override suspend fun updateAnnotation(bookId: Long, id: Long, note: String?, color: String?, style: String?): Annotation {
        val body = buildJsonObject {
            // An empty note clears it (the DTO takes null for that).
            note?.let { put("note", if (it.isBlank()) JsonNull else JsonPrimitive(it)) }
            color?.let { put("color", it) }
            style?.let { put("style", it) }
        }
        return api.send("PATCH", "books/$bookId/annotations/$id", body) { ApiJson.decodeFromString(Annotation.serializer(), it) }
    }

    override suspend fun deleteAnnotation(bookId: Long, id: Long) {
        api.send("DELETE", "books/$bookId/annotations/$id", null) { }
    }

    override suspend fun bookmarks(bookId: Long): List<Bookmark> =
        api.send("GET", "books/$bookId/bookmarks", null) { ApiJson.decodeFromString(ListSerializer(Bookmark.serializer()), it) }

    override suspend fun createBookmark(bookId: Long, cfi: String, title: String): Bookmark {
        val body = buildJsonObject {
            put("cfi", cfi)
            put("title", title.take(MAX_TITLE))
        }
        return api.send("POST", "books/$bookId/bookmarks", body) { ApiJson.decodeFromString(Bookmark.serializer(), it) }
    }

    override suspend fun deleteBookmark(bookId: Long, id: Long) {
        api.send("DELETE", "books/$bookId/bookmarks/$id", null) { }
    }

    private companion object {
        const val MAX_CHAPTER = 500
        const val MAX_TITLE = 500
    }
}
