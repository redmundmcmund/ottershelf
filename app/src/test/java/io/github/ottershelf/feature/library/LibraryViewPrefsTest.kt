package io.github.ottershelf.feature.library

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.tracking.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each list's view on the device: per list and account, as the sort is kept. */
class LibraryViewPrefsTest {

    private val store = MemoryStore()
    private var account = "a1"
    private val prefs = LibraryViewPrefs(store) { account }

    @Test
    fun `nothing saved is the grid at its old size`() = runTest {
        assertEquals(ListView(), prefs.load("all"))
    }

    @Test
    fun `a saved view comes back for its own list only`() = runTest {
        prefs.save("all", ListView(ViewMode.LIST))
        prefs.save("library:3", ListView(cellDp = 79.8f))
        assertEquals(ListView(ViewMode.LIST), prefs.load("all"))
        assertEquals(ListView(cellDp = 79.8f), prefs.load("library:3"))
        assertEquals(ListView(), prefs.load("author"))
        assertEquals(ListView(), prefs.load(LibraryViewPrefs.DOWNLOADED))
    }

    @Test
    fun `lists are named as the sort names them`() = runTest {
        // Every author's page shares one view, and so does every series' page.
        val authorA = ListKind.prefsKey(io.github.ottershelf.core.model.BookSource.fromKey("author:5", "A"))
        val authorB = ListKind.prefsKey(io.github.ottershelf.core.model.BookSource.fromKey("author:9", "B"))
        prefs.save(authorA, ListView(ViewMode.LIST, 133f))
        assertEquals(ListView(ViewMode.LIST, 133f), prefs.load(authorB))
    }

    @Test
    fun `each account keeps its own`() = runTest {
        prefs.save("all", ListView(ViewMode.LIST))
        account = "a2"
        assertEquals(ListView(), prefs.load("all"))
        account = "a1"
        assertEquals(ListView(ViewMode.LIST), prefs.load("all"))
    }

    @Test
    fun `going back to the default forgets the list's entry`() = runTest {
        prefs.save("all", ListView(ViewMode.LIST))
        assertTrue(store.state.value.contains(stringPreferencesKey("library.view.a1.all")))
        prefs.save("all", ListView())
        assertFalse(store.state.value.contains(stringPreferencesKey("library.view.a1.all")))
        assertEquals(ListView(), prefs.load("all"))
    }

    @Test
    fun `an unreadable entry is the default`() = runTest {
        store.edit { it[stringPreferencesKey("library.view.a1.all")] = "{not json" }
        assertEquals(ListView(), prefs.load("all"))
        store.edit { it[stringPreferencesKey("library.view.a1.all")] = """{"mode":"list","cellDp":120.5,"later":true}""" }
        assertEquals(ListView(ViewMode.LIST, 120.5f), prefs.load("all"))
    }
}
