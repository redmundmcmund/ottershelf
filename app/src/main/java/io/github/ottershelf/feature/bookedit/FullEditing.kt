package io.github.ottershelf.feature.bookedit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * "Full metadata editing" (Settings > Editing, off by default): the edit screen shows every field of
 * the web's editor this app handles (subtitle, description, publisher, published, language, pages,
 * ISBNs, genres, tags, other series, a comic's issue, volume and credits, and Lock beside each field),
 * not only the title, authors, series and cover. A device setting, for every account on it.
 */
object FullEditing {
    private val KEY = booleanPreferencesKey("bookedit.full")

    fun enabled(store: DataStore<Preferences>): Flow<Boolean> =
        store.data.map { it[KEY] ?: false }.catch { emit(false) }

    suspend fun set(store: DataStore<Preferences>, on: Boolean) {
        store.edit { it[KEY] = on }
    }
}
