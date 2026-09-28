package io.github.ottershelf.feature.home.model

/*
 * The Dashboard's cards (widgets and shelves) in one order the user arranges.
 *
 * Where it is kept:
 * - the widgets' order among themselves is the account's `dashboardConfig.widgets` (the web shows
 *   its widgets in that order, and may change it);
 * - the whole order, shelves included, is the app's settings key (`AppSettings.dashboardOrder`,
 *   card keys), so it travels with the account to a new phone.
 *
 * Reading them together ([cardOrder]): the saved order says which place holds a widget and which a
 * shelf; the widgets' places take the widgets in `dashboardConfig`'s order (so the phone and the
 * web always agree, and a change made on the web shows), the shelves' places keep the saved order.
 * A card the saved order doesn't know gets a place at the end; one that no longer exists drops
 * out. Hidden cards keep their places, so one shown again comes back where it was.
 */

/** A widget's key in the saved order. */
fun widgetKey(type: WidgetType): String = "$WIDGET_PREFIX${type.id}"

/** A shelf's key in the saved order (shelf ids are the device's, "1".."8"). */
fun shelfKey(id: String): String = "$SHELF_PREFIX$id"

fun isWidgetKey(key: String): Boolean = key.startsWith(WIDGET_PREFIX)

fun isShelfKey(key: String): Boolean = key.startsWith(SHELF_PREFIX)

/** The widget a key names, if it is one this app knows. */
fun widgetOfKey(key: String): WidgetType? = if (isWidgetKey(key)) WidgetType.of(key.removePrefix(WIDGET_PREFIX)) else null

private const val WIDGET_PREFIX = "w:"
private const val SHELF_PREFIX = "s:"

/**
 * Every card's key, in order (see the file comment). [saved] is the stored order, [widgets] every
 * widget's key in `dashboardConfig`'s order, [shelves] every shelf's key in the device's order.
 * [shelvesFollowList]: the shelves' places take [shelves]'s order too (after the Customise sheet
 * reordered them), rather than keeping the saved one. Duplicates count once. Reading its own
 * result again gives the same order.
 */
fun cardOrder(saved: List<String>, widgets: List<String>, shelves: List<String>, shelvesFollowList: Boolean = false): List<String> {
    val w = widgets.distinct()
    val s = shelves.distinct()
    val known = (w + s).toSet()
    val kept = saved.distinct().filter { it in known }
    val placed = kept.toSet()
    // New cards get places at the end: widgets, then shelves.
    var order = kept + w.filterNot { it in placed } + s.filterNot { it in placed }
    order = order.refilled(w)
    if (shelvesFollowList) order = order.refilled(s)
    return order
}

/**
 * The places of this list that hold one of [sequence]'s keys, taken by those keys in [sequence]'s
 * order; every other place stays as it is. Both lists hold each key once.
 */
fun List<String>.refilled(sequence: List<String>): List<String> {
    val here = toSet()
    val members = sequence.filter { it in here }
    val memberSet = members.toSet()
    val next = members.iterator()
    return map { if (it in memberSet) next.next() else it }
}

/**
 * [full] (every card, hidden ones too) after the user arranged the cards on show into [shown]: exactly
 * that order, with each hidden card kept right after the card of its own kind (widget or shelf)
 * it followed, or before the first of its kind if it led them. So the widgets' order among
 * themselves changes only where the user moved a widget, and a card shown again comes back beside the
 * one it was next to.
 */
fun arranged(full: List<String>, shown: List<String>): List<String> {
    val all = full.distinct()
    val shownSet = shown.toSet()
    val known = all.toSet()
    val result = shown.distinct().filter { it in known }.toMutableList()
    all.forEachIndexed { i, key ->
        if (key in shownSet) return@forEachIndexed
        val widget = isWidgetKey(key)
        val previous = all.subList(0, i).lastOrNull { isWidgetKey(it) == widget }
        val at = if (previous != null) {
            result.indexOf(previous) + 1
        } else {
            result.indexOfFirst { isWidgetKey(it) == widget }.takeIf { it >= 0 } ?: result.size
        }
        result.add(at, key)
    }
    return result
}

/**
 * [widgets] (as the server has them: flags, ids, the web's own entries) put in [order]; widgets
 * [order] doesn't name keep their order after the others. Only the order changes.
 */
fun reorderWidgets(widgets: List<WidgetConfig>, order: List<WidgetType>): List<WidgetConfig> {
    val rank = order.distinct().withIndex().associate { (i, type) -> type to i }
    return widgets.withIndex().sortedWith(compareBy({ rank[it.value.type] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
}

/**
 * How the cards sit on the screen, row by row: a shelf has a row of its own; widgets between two
 * shelves are laid out as [widgetRows] lays them out (two columns when [twoColumns], else one each
 * with two short neighbours sharing a row). [widgetOf] is the widget a card is, or null for a shelf.
 */
fun <T> cardRows(cards: List<T>, twoColumns: Boolean, widgetOf: (T) -> WidgetType?): List<List<T>> {
    val rows = mutableListOf<List<T>>()
    var i = 0
    while (i < cards.size) {
        val card = cards[i]
        val type = widgetOf(card)
        if (type == null) {
            rows += listOf(card)
            i++
            continue
        }
        val next = cards.getOrNull(i + 1)
        val nextType = next?.let(widgetOf)
        if (next != null && nextType != null && (twoColumns || (type.short && nextType.short))) {
            rows += listOf(card, next)
            i += 2
        } else {
            rows += listOf(card)
            i++
        }
    }
    return rows
}

/** Unique keys for a list whose items may repeat a key: "k", then "k#2", "k#3"... */
fun <T> List<T>.uniqueKeys(key: (T) -> String): List<String> {
    val seen = HashMap<String, Int>()
    return map { item ->
        val base = key(item)
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        if (n == 1) base else "$base#$n"
    }
}

/** This list put in the order of [keys] ([uniqueKeys] of [key]); items [keys] doesn't name go last, in order. */
fun <T> List<T>.arrangedBy(keys: List<String>, key: (T) -> String): List<T> {
    val rank = keys.withIndex().associate { (i, k) -> k to i }
    return zip(uniqueKeys(key)).withIndex()
        .sortedWith(compareBy({ rank[it.value.second] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value.first }
}
