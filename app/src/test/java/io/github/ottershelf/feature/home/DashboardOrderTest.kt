package io.github.ottershelf.feature.home

import io.github.ottershelf.feature.home.model.CustomiseDraft
import io.github.ottershelf.feature.home.model.DEFAULT_WIDGETS
import io.github.ottershelf.feature.home.model.ShelfConfig
import io.github.ottershelf.feature.home.model.WidgetConfig
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.feature.home.model.arranged
import io.github.ottershelf.feature.home.model.arrangedBy
import io.github.ottershelf.feature.home.model.cardOrder
import io.github.ottershelf.feature.home.model.cardRows
import io.github.ottershelf.feature.home.model.moved
import io.github.ottershelf.feature.home.model.reorderWidgets
import io.github.ottershelf.feature.home.model.uniqueKeys
import io.github.ottershelf.feature.home.model.widgetOfKey
import io.github.ottershelf.feature.home.model.widgetRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Dashboard's one order of widgets and shelves (model/DashboardOrder.kt). */
class DashboardOrderTest {

    private val w = listOf("w:reading-streak", "w:currently-reading", "w:reading-goal")
    private val s = listOf("s:2", "s:3", "s:1")

    @Test
    fun withNothingSavedTheWidgetsComeFirstThenTheShelves() {
        assertEquals(w + s, cardOrder(emptyList(), w, s))
    }

    @Test
    fun theSavedOrderInterleavesWidgetsAndShelves() {
        val saved = listOf("s:3", "w:reading-streak", "s:2", "w:currently-reading", "w:reading-goal", "s:1")
        assertEquals(saved, cardOrder(saved, w, s))
    }

    @Test
    fun theWidgetsPlacesFollowTheAccountsOrder() {
        // Arranged on the phone, then the web put Reading Goal first among the widgets: the
        // widgets' places stay where they were among the shelves, and take the web's order.
        val saved = listOf("s:3", "w:reading-streak", "s:2", "w:currently-reading", "w:reading-goal", "s:1")
        val web = listOf("w:reading-goal", "w:reading-streak", "w:currently-reading")
        assertEquals(
            listOf("s:3", "w:reading-goal", "s:2", "w:reading-streak", "w:currently-reading", "s:1"),
            cardOrder(saved, web, s),
        )
    }

    @Test
    fun theShelvesKeepTheSavedOrderOnANewPhone() {
        // A new phone has the default shelves in the default order; the saved order still holds.
        val saved = listOf("s:1", "w:reading-streak", "s:3", "s:2", "w:currently-reading", "w:reading-goal")
        assertEquals(saved, cardOrder(saved, w, listOf("s:2", "s:3", "s:1")))
        // After the Customise sheet reordered them, the shelves' places take its order.
        assertEquals(
            listOf("s:2", "w:reading-streak", "s:1", "s:3", "w:currently-reading", "w:reading-goal"),
            cardOrder(saved, w, listOf("s:2", "s:1", "s:3"), shelvesFollowList = true),
        )
    }

    @Test
    fun unknownCardsGoAtTheEndAndRemovedOnesDropOut() {
        val saved = listOf("s:1", "w:reading-goal", "s:9", "w:gone-widget", "w:reading-streak", "s:1")
        val widgets = listOf("w:reading-streak", "w:reading-goal", "w:long-wait")
        assertEquals(
            // s:9 and the unknown widget are gone, s:1 counts once; Long Wait and s:4 are new.
            listOf("s:1", "w:reading-streak", "w:reading-goal", "w:long-wait", "s:4"),
            cardOrder(saved, widgets, listOf("s:1", "s:4")),
        )
    }

    @Test
    fun widgetsTheSavedOrderLacksTakeTheAccountsOrderAndReadingAgainChangesNothing() {
        // Only Reading Goal was placed; the widget places (new ones at the end) take the
        // account's order, so the phone and the web always agree.
        val once = cardOrder(listOf("s:1", "w:reading-goal"), w, s)
        assertEquals(listOf("s:1", "w:reading-streak", "w:currently-reading", "w:reading-goal", "s:2", "s:3"), once)
        assertEquals(once, cardOrder(once, w, s))
    }

    @Test
    fun arrangingTheCardsOnShowKeepsHiddenOnesAfterTheirKind() {
        // Reading Goal (hidden) follows the streak; dragging s:1 above the streak.
        val full = listOf("w:reading-streak", "w:reading-goal", "s:1", "s:2")
        assertEquals(
            listOf("s:1", "w:reading-streak", "w:reading-goal", "s:2"),
            arranged(full, listOf("s:1", "w:reading-streak", "s:2")),
        )
        assertEquals(full, arranged(full, listOf("w:reading-streak", "s:1", "s:2")))
        // A widget dragged below two shelves: exactly the order the user left, the hidden widget
        // still after it (the widgets' own order unchanged).
        val a = "w:reading-goal"
        val h = "w:reading-dna"
        assertEquals(listOf("s:1", "s:2", a, h), arranged(listOf(a, h, "s:1", "s:2"), listOf("s:1", "s:2", a)))
        // Hidden cards that led their kind stay in front of it; a hidden shelf follows its shelf.
        assertEquals(
            listOf("s:1", "s:6", h, a, "w:long-wait"),
            arranged(listOf(h, a, "w:long-wait", "s:1", "s:6"), listOf("s:1", a, "w:long-wait")),
        )
    }

    @Test
    fun reorderingWidgetsChangesOnlyTheirOrder() {
        val server = DEFAULT_WIDGETS.map { if (it.type == WidgetType.READING_DNA) it.copy(enabled = false) else it }
        val order = listOf(WidgetType.LONG_WAIT, WidgetType.READING_DNA, WidgetType.READING_STREAK)
        val result = reorderWidgets(server, order)
        assertEquals(order, result.take(3).map { it.type })
        // The rest keep their order after them, every flag and id as the server has them.
        assertEquals(server.map { it.type }.filter { it !in order }, result.drop(3).map { it.type })
        assertEquals(server.toSet(), result.toSet())
        assertEquals(false, result[1].enabled)
    }

    @Test
    fun keysNameTheirWidgets() {
        assertEquals(WidgetType.READING_GOAL, widgetOfKey("w:reading-goal"))
        assertNull(widgetOfKey("s:reading-goal"))
        assertNull(widgetOfKey("w:not-a-widget"))
    }

    @Test
    fun rowsPairShortWidgetsButAShelfHasARowOfItsOwn() {
        val cards = listOf<Any>(WidgetType.READING_STREAK, "shelf", WidgetType.READING_GOAL, WidgetType.MONTHLY_CHALLENGE, WidgetType.CURRENTLY_READING, "shelf2")
        val widgetOf = { c: Any -> c as? WidgetType }
        assertEquals(
            listOf(
                listOf(WidgetType.READING_STREAK),
                listOf("shelf"),
                listOf(WidgetType.READING_GOAL, WidgetType.MONTHLY_CHALLENGE),
                listOf(WidgetType.CURRENTLY_READING),
                listOf("shelf2"),
            ),
            cardRows(cards, twoColumns = false, widgetOf),
        )
        // Two columns: the widgets between two shelves two by two.
        assertEquals(
            listOf(
                listOf(WidgetType.READING_STREAK),
                listOf("shelf"),
                listOf(WidgetType.READING_GOAL, WidgetType.MONTHLY_CHALLENGE),
                listOf(WidgetType.CURRENTLY_READING),
                listOf("shelf2"),
            ),
            cardRows(cards, twoColumns = true, widgetOf),
        )
        // Widgets alone lay out as before.
        assertEquals(WidgetType.entries.chunked(2), widgetRows(WidgetType.entries, twoColumns = true))
    }

    @Test
    fun theSheetsListsMoveAndArrangeByKey() {
        val shelves = listOf(ShelfConfig("2", "recently-added"), ShelfConfig("3", "random"), ShelfConfig("1", "continue-reading"))
        val draft = CustomiseDraft(DEFAULT_WIDGETS, shelves, null)
        assertEquals(listOf("shelf:2", "shelf:3", "shelf:1"), draft.shelfKeys)
        assertEquals(listOf("1", "2", "3"), draft.arrangeShelves(listOf("shelf:1", "shelf:2", "shelf:3")).shelves.map { it.id })
        assertEquals(listOf("3", "1", "2"), draft.moveShelf(0, 2).shelves.map { it.id })
        assertEquals(draft, draft.moveShelf(0, 5))
        val widgets = draft.arrangeWidgets(listOf("widget:long-wait", "widget:reading-goal")).widgets
        assertEquals(listOf(WidgetType.LONG_WAIT, WidgetType.READING_GOAL, WidgetType.READING_STREAK), widgets.take(3).map { it.type })
        assertEquals(WidgetType.READING_GOAL, draft.moveWidget(2, 0).widgets.first().type)
    }

    @Test
    fun repeatedEntriesGetKeysOfTheirOwn() {
        val twice = listOf(WidgetConfig("1", WidgetType.READING_GOAL, true), WidgetConfig("2", WidgetType.READING_GOAL, false))
        assertEquals(listOf("g", "g#2"), twice.uniqueKeys { "g" })
        assertEquals(twice.reversed(), twice.arrangedBy(listOf("g#2", "g")) { "g" })
        assertEquals(listOf(3, 1, 2), listOf(1, 2, 3).moved(2, 0))
    }
}
