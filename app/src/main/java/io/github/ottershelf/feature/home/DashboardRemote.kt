package io.github.ottershelf.feature.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.CurrentlyReadingBook
import io.github.ottershelf.core.model.Library
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.ScrollerBatch
import io.github.ottershelf.core.model.SmartScope
import io.github.ottershelf.core.model.WidgetBatch
import io.github.ottershelf.core.model.WidgetBatchRequest
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.feature.home.model.ShelfBatchItem
import io.github.ottershelf.feature.home.model.ShelfBatchRequest
import io.github.ottershelf.feature.home.model.ShelfConfig
import io.github.ottershelf.feature.home.model.StoredShelves
import io.github.ottershelf.feature.home.model.WidgetData
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.feature.home.model.decodeWidget
import io.github.ottershelf.feature.home.model.normalizeShelves
import io.github.ottershelf.ui.components.coverModel

/** A widget's part of a batch: its data, or that the server couldn't work it out. */
sealed interface WidgetResult {
    data object Failed : WidgetResult
    data class Loaded(val data: WidgetData) : WidgetResult
}

/** What the Dashboard asks the server: the Api in the app, a fake in tests. */
interface DashboardRemote {
    /** `POST dashboard/widgets/batch` for [types] (one request); throws when the request fails. */
    suspend fun widgets(types: List<WidgetType>): Map<WidgetType, WidgetResult>

    /** `POST dashboard/scrollers/batch` (at most 8); a shelf the server failed maps to null. */
    suspend fun shelves(items: List<ShelfBatchItem>): Map<String, List<BookCard>?>

    suspend fun refreshDashboard()
    fun shelfCover(book: BookCard): Any?
    fun readingCover(book: CurrentlyReadingBook, day: Long): Any?

    /** A widget's book cover (the widgets only say whether there is one): cached for a day. */
    fun bookCover(bookId: Long, hasCover: Boolean, day: Long): Any?

    // --- customisation --------------------------------------------------------------------
    suspend fun me(): AuthUser

    /** `PATCH users/me/settings` (shallow merge of each top-level key); the stored settings. */
    suspend fun patchSettings(settings: JsonObject): JsonObject?
    suspend fun libraries(): List<Library>
    suspend fun smartScopes(): List<SmartScope>

    /** Neglected Gems' "Add to queue". */
    suspend fun setStatus(bookId: Long, status: String): ReadStatusInfo

    /** The book's read status now (`GET books/:id`), or null for none. */
    suspend fun readStatus(bookId: Long): String?
}

internal class ApiDashboardRemote(private val api: Api) : DashboardRemote {
    override suspend fun widgets(types: List<WidgetType>): Map<WidgetType, WidgetResult> {
        val body = ApiJson.encodeToJsonElement(WidgetBatchRequest.serializer(), WidgetBatchRequest(types.map { it.id }))
        return api.send("POST", "dashboard/widgets/batch", body) { text ->
            val items = ApiJson.decodeFromString(WidgetBatch.serializer(), text).items
            types.associateWith { type ->
                val item = items.firstOrNull { it.type == type.id }
                val data = item?.takeUnless { it.failed }?.let { decodeWidget(type, it.data) }
                if (data == null) WidgetResult.Failed else WidgetResult.Loaded(data)
            }
        }
    }

    override suspend fun shelves(items: List<ShelfBatchItem>): Map<String, List<BookCard>?> {
        val body = ApiJson.encodeToJsonElement(ShelfBatchRequest.serializer(), ShelfBatchRequest(items))
        return api.send("POST", "dashboard/scrollers/batch", body) { text ->
            val results = ApiJson.decodeFromString(ScrollerBatch.serializer(), text).items
            items.associate { item -> item.id to results.firstOrNull { it.id == item.id }?.takeUnless { it.failed }?.books }
        }
    }

    override suspend fun refreshDashboard() = api.refreshDashboard()
    override fun shelfCover(book: BookCard): Any? = api.coverModel(book)
    override fun readingCover(book: CurrentlyReadingBook, day: Long): Any? = bookCover(book.bookId, book.hasCover, day)

    override fun bookCover(bookId: Long, hasCover: Boolean, day: Long): Any? {
        if (!hasCover) return null
        val url = api.unversionedThumbnailUrl(bookId)
        return DailyCover(url, "$url#$day")
    }

    override suspend fun me(): AuthUser = api.me()

    override suspend fun patchSettings(settings: JsonObject): JsonObject? {
        val user = api.send("PATCH", "users/me/settings", buildJsonObject { put("settings", settings) }) {
            ApiJson.decodeFromString(JsonObject.serializer(), it)
        }
        return user["settings"] as? JsonObject
    }

    override suspend fun libraries(): List<Library> = api.libraries()
    override suspend fun smartScopes(): List<SmartScope> = api.smartScopes()
    override suspend fun setStatus(bookId: Long, status: String): ReadStatusInfo = api.setStatus(bookId, status)
    override suspend fun readStatus(bookId: Long): String? = api.book(bookId).readStatus?.status
}

/** Where the shelves are kept on the device (the web keeps them in localStorage). */
interface ShelfPrefs {
    /** The saved shelves, normalized; the web's defaults when nothing is saved. */
    suspend fun load(): List<ShelfConfig>
    suspend fun save(shelves: List<ShelfConfig>)

    /** For tests and previews. */
    class InMemory(private var shelves: List<ShelfConfig>? = null) : ShelfPrefs {
        override suspend fun load() = normalizeShelves(shelves)
        override suspend fun save(shelves: List<ShelfConfig>) {
            this.shelves = shelves
        }
    }
}

/** The settings DataStore, per account (`home.shelves.<account>`), as JSON through ApiJson. */
internal class DataStoreShelfPrefs(private val store: DataStore<Preferences>, private val account: () -> String) : ShelfPrefs {
    private fun key() = stringPreferencesKey("home.shelves.${account()}")

    override suspend fun load(): List<ShelfConfig> {
        val text = runCatching { store.data.first()[key()] }.getOrNull()
        val stored = text?.let { runCatching { ApiJson.decodeFromString(StoredShelves.serializer(), it) }.getOrNull() }
        return normalizeShelves(stored?.scrollers)
    }

    override suspend fun save(shelves: List<ShelfConfig>) {
        val text = ApiJson.encodeToString(StoredShelves.serializer(), StoredShelves(normalizeShelves(shelves)))
        store.edit { it[key()] = text }
    }
}
