package io.github.ottershelf.feature.bookedit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * A cover found online (`GET books/cover/search`, CoverSearchResult): [url] is the image the server
 * fetches for `cover/from-url`; [preview] its small copy through the server's own proxy
 * (`books/cover/proxy`), so the phone only ever talks to the BookOrbit server.
 */
data class CoverResult(
    val url: String,
    val preview: Any?,
    val width: Int,
    val height: Int,
    val source: String,
)

/**
 * The server calls this screen makes, each body exactly its DTO (the server rejects unknown fields):
 * see ARCHITECTURE.md, "Editing a book's details".
 */
interface BookEditRemote {
    suspend fun book(bookId: Long): BookDetail

    /** `PATCH books/:id/metadata` with [body] ([MetadataChanges.body]); the book as saved. */
    suspend fun saveMetadata(bookId: Long, body: JsonObject): BookDetail

    /** `PATCH books/:id/metadata-locks`: the book's whole lock list becomes [lockedFields]. */
    suspend fun setLocks(bookId: Long, lockedFields: List<String>): BookDetail

    /** Authors in the library matching [query], best first. */
    suspend fun authors(query: String): List<AuthorSuggestion>

    /** Series in the library matching [query], best first. */
    suspend fun series(query: String): List<SeriesSuggestion>

    /** The user's default cover source (`user-preferences/cover-search`), null if unknown. */
    suspend fun coverProvider(): String?

    suspend fun searchCovers(title: String, author: String?, audiobook: Boolean, provider: String): List<CoverResult>

    /** `POST books/:id/cover`: [jpeg] as the book's own cover. */
    suspend fun uploadCover(bookId: Long, jpeg: ByteArray)

    /** `POST books/:id/cover/from-url`: the server fetches [url] and keeps it as the cover. */
    suspend fun coverFromUrl(bookId: Long, url: String)

    /**
     * `DELETE books/:id/cover`: the cover the user added goes for good, and the one taken from the file
     * comes back if the server has one. The new coverSource: `extracted`, or null for no cover at all.
     */
    suspend fun removeCustomCover(bookId: Long): String?

    /** `POST books/:id/re-extract-cover`: the cover read from the book's file again; whether one was found. */
    suspend fun extractCover(bookId: Long): Boolean
}

/** The upload `POST books/:id/cover` takes: one multipart file part (the server reads the first file). */
internal fun coverUploadBody(jpeg: ByteArray): MultipartBody = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart(COVER_PART, "cover.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
    .build()

/** The part's name, as the web's cover editor sends it (`form.append('file', ...)`). */
internal const val COVER_PART = "file"

/** The cover search's query (SearchCoversQueryDto: title, author, isAudiobook, provider; nothing else). */
internal fun coverSearchQuery(title: String, author: String?, audiobook: Boolean, provider: String): String {
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    return buildString {
        append("books/cover/search?title=").append(enc(title.trim()))
        author?.trim()?.takeIf { it.isNotEmpty() }?.let { append("&author=").append(enc(it)) }
        append("&isAudiobook=").append(audiobook)
        append("&provider=").append(enc(provider))
    }
}

/** The providers the search offers (COVER_SEARCH_DEFAULT_PROVIDERS), DuckDuckGo being the server's default. */
object CoverProviders {
    const val DUCKDUCKGO = "duckduckgo"
    const val ITUNES = "itunes"
    const val ALL = "all"
    val choices = listOf(DUCKDUCKGO, ITUNES, ALL)

    fun of(value: String?): String = value?.takeIf { it in choices } ?: DUCKDUCKGO
}

@Serializable
private data class CoverSearchItem(
    val url: JsonElement? = null,
    val previewUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val source: String = "",
)

class ApiBookEditRemote(private val api: Api) : BookEditRemote {

    override suspend fun book(bookId: Long): BookDetail = api.book(bookId)

    override suspend fun saveMetadata(bookId: Long, body: JsonObject): BookDetail =
        // Without `syncFileWrite` the server answers with the book and writes the file later.
        api.send("PATCH", "books/$bookId/metadata", body) { ApiJson.decodeFromString(BookDetail.serializer(), it) }

    override suspend fun setLocks(bookId: Long, lockedFields: List<String>): BookDetail =
        api.send("PATCH", "books/$bookId/metadata-locks", locksBody(lockedFields)) { ApiJson.decodeFromString(BookDetail.serializer(), it) }

    override suspend fun authors(query: String): List<AuthorSuggestion> =
        api.authors(page = 0, size = SUGGESTIONS, query = query).items.map { AuthorSuggestion(it.name, it.bookCount) }

    override suspend fun series(query: String): List<SeriesSuggestion> =
        api.series(page = 0, size = SUGGESTIONS, query = query).items.map { SeriesSuggestion(it.name, it.bookCount, it.authors) }

    override suspend fun coverProvider(): String? =
        api.send("GET", "user-preferences/cover-search", null) { text ->
            val settings = (ApiJson.parseToJsonElement(text) as? JsonObject)?.get("settings") as? JsonObject
            (settings?.get("defaultProvider") as? JsonPrimitive)?.content
        }

    override suspend fun searchCovers(title: String, author: String?, audiobook: Boolean, provider: String): List<CoverResult> =
        api.send("GET", coverSearchQuery(title, author, audiobook, provider), null) { text ->
            val items = ApiJson.parseToJsonElement(text) as? JsonArray ?: return@send emptyList()
            items.mapNotNull { element ->
                val item = runCatching { ApiJson.decodeFromJsonElement(CoverSearchItem.serializer(), element) }.getOrNull() ?: return@mapNotNull null
                val url = (item.url as? JsonPrimitive)?.content?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
                // Only a path on this server: the preview comes through its proxy, with the user's sign-in.
                val preview = item.previewUrl?.takeIf { it.startsWith("/") }?.let { runCatching { api.serverUrl(it) }.getOrNull() }
                CoverResult(url, preview, item.width, item.height, item.source)
            }
        }

    override suspend fun uploadCover(bookId: Long, jpeg: ByteArray) {
        api.sendBody("POST", "books/$bookId/cover", coverUploadBody(jpeg)) { }
    }

    override suspend fun coverFromUrl(bookId: Long, url: String) {
        api.send("POST", "books/$bookId/cover/from-url", fromUrlBody(url)) { }
    }

    override suspend fun removeCustomCover(bookId: Long): String? =
        api.send("DELETE", "books/$bookId/cover", null) { text ->
            ((ApiJson.parseToJsonElement(text) as? JsonObject)?.get("coverSource") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

    override suspend fun extractCover(bookId: Long): Boolean =
        api.send("POST", "books/$bookId/re-extract-cover", null) { text ->
            val updated = ((ApiJson.parseToJsonElement(text) as? JsonObject)?.get("updated") as? JsonPrimitive)?.content?.toIntOrNull()
            (updated ?: 0) > 0
        }

    private companion object {
        const val SUGGESTIONS = 8
    }
}

/** UpdateBookMetadataLocksDto: `{lockedFields: [...]}` (unique names of BOOK_METADATA_LOCK_FIELDS). */
internal fun locksBody(lockedFields: List<String>): JsonObject =
    JsonObject(mapOf("lockedFields" to JsonArray(lockedFields.distinct().map(::JsonPrimitive))))

/** UploadCoverFromUrlDto: `{url}`. */
internal fun fromUrlBody(url: String): JsonObject = buildJsonObject { put("url", url) }
