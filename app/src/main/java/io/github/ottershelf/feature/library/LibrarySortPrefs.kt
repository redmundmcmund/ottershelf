package io.github.ottershelf.feature.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import io.github.ottershelf.core.network.ApiJson

/**
 * Each list's sort and filters, on this device only (settings DataStore key
 * `library.sort.<account>.<list>`, the list from [ListKind.prefsKey]). A missing, unreadable or
 * no longer offered choice is the list's default (title A to Z; a series in series order).
 */
class LibrarySortPrefs(private val store: DataStore<Preferences>, private val account: () -> String) {
    private fun key(list: String) = stringPreferencesKey("library.sort.${account()}.$list")

    suspend fun load(list: String, kind: ListKind): ListSort {
        val text = runCatching { store.data.first()[key(list)] }.getOrNull()
        val stored = text?.let { runCatching { ApiJson.decodeFromString(StoredListSort.serializer(), it) }.getOrNull() }
        return ListSort.restore(stored, kind)
    }

    suspend fun save(list: String, sort: ListSort, kind: ListKind) {
        val key = key(list)
        runCatching {
            store.edit {
                if (sort == kind.default) it.remove(key)
                else it[key] = ApiJson.encodeToString(StoredListSort.serializer(), sort.stored())
            }
        }
    }
}
