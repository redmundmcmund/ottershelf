package io.github.ottershelf.feature.home.model

import io.github.ottershelf.core.model.Library
import io.github.ottershelf.core.model.SmartScope

enum class CustomiseTab { SHELVES, WIDGETS }

/**
 * The Customise sheet's working copy (the web's DashboardSettingsSheet drafts): nothing is kept
 * until Save. [libraryIds] null means all libraries; an empty list is "none chosen", which can't be
 * saved.
 */
data class CustomiseDraft(
    val widgets: List<WidgetConfig>,
    val shelves: List<ShelfConfig>,
    val libraryIds: List<Long>?,
    val tab: CustomiseTab = CustomiseTab.SHELVES,
    val libraryScopeOpen: Boolean = false,
) {
    /** The widget at [from] moved to [to] (the list's accessibility actions). */
    fun moveWidget(from: Int, to: Int) = copy(widgets = widgets.moved(from, to))

    /** The widgets in the order of [keys] ([widgetKeys]), as a drag in the list left them. */
    fun arrangeWidgets(keys: List<String>) = copy(widgets = widgets.arrangedBy(keys, WIDGET_ENTRY_KEY))
    fun toggleWidget(index: Int) = copy(widgets = widgets.mapAt(index) { it.copy(enabled = !it.enabled) })

    fun moveShelf(from: Int, to: Int) = copy(shelves = shelves.moved(from, to))

    /** The shelves in the order of [keys] ([shelfKeys]). */
    fun arrangeShelves(keys: List<String>) = copy(shelves = shelves.arrangedBy(keys, SHELF_ENTRY_KEY))

    /** The lists' keys for the sheet's rows (unique even if the saved lists repeat an entry). */
    val widgetKeys: List<String> get() = widgets.uniqueKeys(WIDGET_ENTRY_KEY)
    val shelfKeys: List<String> get() = shelves.uniqueKeys(SHELF_ENTRY_KEY)
    fun toggleShelf(index: Int) = copy(shelves = shelves.mapAt(index) { it.copy(enabled = !it.enabled) })
    fun setShelfRows(index: Int, rows: Int) = copy(shelves = shelves.mapAt(index) { it.copy(rows = rows.coerceIn(MIN_SHELF_ROWS, MAX_SHELF_ROWS)) })
    fun setShelfLimit(index: Int, limit: Int) = copy(shelves = shelves.mapAt(index) { it.copy(limit = limit.coerceIn(1, MAX_SHELF_BOOKS)) })

    /** The web's `onTypeChange`: a scope shelf takes the first scope; any other drops it. */
    fun setShelfType(index: Int, type: ShelfType, scopes: List<SmartScope>) = copy(
        shelves = shelves.mapAt(index) {
            if (type == ShelfType.SMART_SCOPE) {
                val first = scopes.firstOrNull()
                it.copy(type = type.id, smartScopeId = first?.id, label = first?.name.orEmpty())
            } else {
                it.copy(type = type.id, smartScopeId = null, label = "")
            }
        },
    )

    fun setShelfScope(index: Int, scope: SmartScope) =
        copy(shelves = shelves.mapAt(index) { it.copy(smartScopeId = scope.id, label = scope.name) })

    val canAddShelf: Boolean get() = shelves.size < MAX_SHELVES
    val canRemoveShelf: Boolean get() = shelves.size > 1

    /** The web's `addScroller`: a Recently Added shelf, on, at the end. */
    fun addShelf(): CustomiseDraft {
        if (!canAddShelf) return this
        val id = ((shelves.maxOfOrNull { it.id.toIntOrNull() ?: 0 } ?: 0) + 1).toString()
        return copy(shelves = shelves + ShelfConfig(id, ShelfType.RECENTLY_ADDED.id))
    }

    fun removeShelf(index: Int) = if (canRemoveShelf) copy(shelves = shelves.filterIndexed { i, _ -> i != index }) else this

    /** All libraries (null), or every one of [libraries] ticked separately to start from. */
    fun setAllLibraries(all: Boolean, libraries: List<Library>) =
        copy(libraryIds = if (all) null else libraries.map { it.id })

    fun toggleLibrary(id: Long, libraries: List<Library>): CustomiseDraft {
        val selected = (libraryIds ?: libraries.map { it.id }).toMutableList()
        if (id in selected) selected.remove(id) else selected.add(id)
        return copy(libraryIds = selected)
    }

    /** The chosen libraries that still exist (null for all). */
    fun selectedLibraries(libraries: List<Library>?): List<Long>? {
        val ids = libraryIds ?: return null
        if (libraries == null) return ids
        val known = libraries.map { it.id }.toSet()
        return ids.filter { it in known }
    }

    /** At least one library, unless all are. */
    fun validLibraries(libraries: List<Library>?): Boolean = selectedLibraries(libraries)?.isNotEmpty() ?: true

    /** The web resets the tab showing only. */
    fun reset() = when (tab) {
        CustomiseTab.WIDGETS -> copy(widgets = DEFAULT_WIDGETS)
        CustomiseTab.SHELVES -> copy(shelves = DEFAULT_SHELVES)
    }
}

private val WIDGET_ENTRY_KEY: (WidgetConfig) -> String = { "widget:${it.type.id}" }
private val SHELF_ENTRY_KEY: (ShelfConfig) -> String = { "shelf:${it.id}" }

/** The item at [from] taken out and put back at [to]; unchanged if either is out of range. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

private fun <T> List<T>.mapAt(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { i, item -> if (i == index) transform(item) else item }
