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

/** One of the user's own collections, and how many of the books asked about it holds ([memberCount]). */
@Serializable
data class CollectionChoice(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val mediaType: String = "books",
    val bookCount: Int = 0,
    val memberCount: Int = 0,
)

/**
 * The user's collections for some books, as the web's AddToCollectionSheet uses them:
 * `POST collections/membership {bookIds}` (the user's own collections, each with how many of those
 * books it holds), `POST` / `DELETE collections/:id/books {bookIds}`, and `POST collections {name, icon}`
 * (the web's default icon, FolderOpen). Bodies are exactly the server's DTOs.
 */
interface CollectionsRemote {
    suspend fun choices(bookIds: List<Long>): List<CollectionChoice>
    suspend fun add(collectionId: Long, bookIds: List<Long>)
    suspend fun remove(collectionId: Long, bookIds: List<Long>)

    /** Makes a collection named [name]; returns its id. */
    suspend fun create(name: String): Long
}

class ApiCollectionsRemote(private val api: Api) : CollectionsRemote {

    override suspend fun choices(bookIds: List<Long>): List<CollectionChoice> =
        api.send("POST", "collections/membership", ids(bookIds)) {
            ApiJson.decodeFromString(ListSerializer(CollectionChoice.serializer()), it)
        }.filter { it.mediaType == "books" }

    override suspend fun add(collectionId: Long, bookIds: List<Long>) {
        api.send("POST", "collections/$collectionId/books", ids(bookIds)) { }
    }

    override suspend fun remove(collectionId: Long, bookIds: List<Long>) {
        api.send("DELETE", "collections/$collectionId/books", ids(bookIds)) { }
    }

    override suspend fun create(name: String): Long =
        api.send("POST", "collections", buildJsonObject { put("name", name); put("icon", DEFAULT_ICON) }) {
            ApiJson.decodeFromString(CollectionChoice.serializer(), it).id
        }

    private fun ids(bookIds: List<Long>): JsonObject = buildJsonObject { putJsonArray("bookIds") { bookIds.forEach { add(it) } } }

    private companion object {
        /** The web's icon for a new collection. */
        const val DEFAULT_ICON = "FolderOpen"
    }
}
