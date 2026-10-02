package io.github.ottershelf.core.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.format.ReaderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * "Keep books on this device" (Settings > Offline, on by default): an ebook opened from the server is
 * downloaded quietly as it opens ([Downloads.start] with quiet), so the next open needs no
 * connection and its progress syncs when one is back. Only the foliate reader's formats, which are
 * small (EPUB, KEPUB, MOBI, AZW3, AZW, FB2): PDFs and comics still stream unless downloaded by hand.
 * A device setting (the device's storage is what it costs), for every account on it.
 */
object KeepOnOpen {
    private val KEY = booleanPreferencesKey("downloads.keepOnOpen")

    fun enabled(store: DataStore<Preferences>): Flow<Boolean> =
        store.data.map { it[KEY] ?: true }.catch { emit(true) }

    suspend fun set(store: DataStore<Preferences>, on: Boolean) {
        store.edit { it[KEY] = on }
    }

    /** Whether opening a file in [format] keeps it. */
    fun applies(format: String?): Boolean =
        BookFormats.readerFor(format) == ReaderKind.Foliate && BookFormats.canKeepOffline(format)
}
