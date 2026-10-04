package io.github.ottershelf.feature.collections

import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One of the user's own collections, and whether it holds the book ([memberCount] of the books asked about). */
@Serializable
data class CollectionChoice(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val mediaType: String = "books",
    val bookCount: Int = 0,
    val memberCount: Int = 0,
) {
    val contains: Boolean get() = memberCount > 0
}

/**
 * The user's collections for one book, as the web's AddToCollectionSheet uses them:
 * `POST collections/membership {bookIds}` (the user's own collections, each with how many of those
 * books it holds), `POST` / `DELETE collections/:id/books {bookIds}`, and `POST collections {name, icon}`
 * (the web's default icon, FolderOpen). Bodies are exactly the server's DTOs.
 */
interface CollectionsRemote {
    suspend fun choices(bookId: Long): List<CollectionChoice>
    suspend fun add(collectionId: Long, bookId: Long)
    suspend fun remove(collectionId: Long, bookId: Long)

    /** Makes a collection named [name]; returns its id. */
    suspend fun create(name: String): Long
}

class ApiCollectionsRemote(private val api: Api) : CollectionsRemote {

    override suspend fun choices(bookId: Long): List<CollectionChoice> =
        api.send("POST", "collections/membership", ids(bookId)) {
            ApiJson.decodeFromString(ListSerializer(CollectionChoice.serializer()), it)
        }.filter { it.mediaType == "books" }

    override suspend fun add(collectionId: Long, bookId: Long) {
        api.send("POST", "collections/$collectionId/books", ids(bookId)) { }
    }

    override suspend fun remove(collectionId: Long, bookId: Long) {
        api.send("DELETE", "collections/$collectionId/books", ids(bookId)) { }
    }

    override suspend fun create(name: String): Long =
        api.send("POST", "collections", buildJsonObject { put("name", name); put("icon", DEFAULT_ICON) }) {
            ApiJson.decodeFromString(CollectionChoice.serializer(), it).id
        }

    private fun ids(bookId: Long): JsonObject = buildJsonObject { putJsonArray("bookIds") { add(bookId) } }

    private companion object {
        /** The web's icon for a new collection. */
        const val DEFAULT_ICON = "FolderOpen"
    }
}
