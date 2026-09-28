package io.github.ottershelf.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The app's single Preferences DataStore (`files/datastore/settings.preferences_pb`), for device
 * settings such as appearance and reader preferences. Only one DataStore may exist per file in a
 * process, so everyone goes through `AppContainer.settings`.
 *
 * Each feature declares its own keys in its own package (e.g. `feature.settings.AppearanceKeys`,
 * prefixed with the feature name: `appearance.theme`), so no shared file needs editing to add one.
 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
