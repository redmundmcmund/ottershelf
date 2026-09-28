package io.github.ottershelf.feature.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import io.github.ottershelf.core.network.ApiJson

/**
 * Each list's view (grid or list, the cover size), on this device only: settings DataStore key
 * `library.view.<account>.<list>`, the list as the sort names it ([ListKind.prefsKey]: each drawer
 * list its own, every author's page one, every series' page another) or [DOWNLOADED]. A missing or
 * unreadable view is the grid at its old size.
 */
class LibraryViewPrefs(private val store: DataStore<Preferences>, private val account: () -> String) {
    private fun key(list: String) = stringPreferencesKey("library.view.${account()}.$list")

    suspend fun load(list: String): ListView {
        val text = runCatching { store.data.first()[key(list)] }.getOrNull()
        val stored = text?.let { runCatching { ApiJson.decodeFromString(StoredListView.serializer(), it) }.getOrNull() }
        return ListView.restore(stored)
    }

    suspend fun save(list: String, view: ListView) {
        val key = key(list)
        runCatching {
            store.edit {
                if (view == ListView()) it.remove(key)
                else it[key] = ApiJson.encodeToString(StoredListView.serializer(), view.stored())
            }
        }
    }

    companion object {
        /** The drawer's Downloaded (feature.downloads). */
        const val DOWNLOADED = "downloaded"
    }
}
