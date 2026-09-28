package io.github.ottershelf.feature.home

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.feature.home.model.DEFAULT_SHELVES
import io.github.ottershelf.feature.home.model.DEFAULT_WIDGETS
import io.github.ottershelf.feature.home.model.DashboardLayout
import io.github.ottershelf.feature.home.model.LongWaitData
import io.github.ottershelf.feature.home.model.ReadingRhythmData
import io.github.ottershelf.feature.home.model.ShelfBatchRequest
import io.github.ottershelf.feature.home.model.ShelfConfig
import io.github.ottershelf.feature.home.model.WidgetData
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.feature.home.model.YearProjectionData
import io.github.ottershelf.feature.home.model.chunkIntoBands
import io.github.ottershelf.feature.home.model.dashboardConfigWith
import io.github.ottershelf.feature.home.model.decodeWidget
import io.github.ottershelf.feature.home.model.formatStorage
import io.github.ottershelf.feature.home.model.mergeWidgets
import io.github.ottershelf.feature.home.model.normalizeWidgets
import io.github.ottershelf.feature.home.model.normalizeShelves
import io.github.ottershelf.feature.home.model.pruneScopeShelves
import io.github.ottershelf.feature.home.model.rowWrapsContent
import io.github.ottershelf.feature.home.model.toRequest
import io.github.ottershelf.feature.home.model.widgetRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web's dashboard config rules (useDashboardWidgets.ts, useDashboardConfig.ts, shelf-rows.ts). */
class DashboardModelsTest {

    private fun json(text: String) = ApiJson.parseToJsonElement(text)

    @Test
    fun widgetsNormalizeLikeTheWeb() {
        assertEquals(DEFAULT_WIDGETS, DashboardLayout.of(null).widgets)
        val layout = DashboardLayout.of(
            json("""{"widgets":[{"id":"9","type":"reading-goal","enabled":false,"order":1},{"type":"podcast-streak"},{"id":3,"type":"reading-streak","enabled":"yes"}],"libraryIds":[3,3,-1,"4",2.5,5]}""") as JsonObject,
        )
        assertEquals(WidgetType.READING_GOAL, layout.widgets[0].type)
        assertEquals("9", layout.widgets[0].id)
        assertFalse(layout.widgets[0].enabled)
        // A non-string id takes its position; a non-boolean enabled is on.
        assertEquals("2", layout.widgets[1].id)
        assertTrue(layout.widgets[1].enabled)
        // Every other type appended, on.
        assertEquals(12, layout.widgets.size)
        assertTrue(layout.widgets.drop(2).all { it.enabled })
        assertEquals(listOf(3L, 5L), layout.libraryIds)
    }

    @Test
    fun theWrittenConfigKeepsEveryOtherKey() {
        val config = json("""{"readingGoal":30,"libraryIds":[1],"extra":{"a":1}}""") as JsonObject
        val all = dashboardConfigWith(config, DEFAULT_WIDGETS, null)
        assertEquals(JsonPrimitive(30), all["readingGoal"])
        assertEquals(json("""{"a":1}"""), all["extra"])
        assertNull(all["libraryIds"])
        assertEquals(12, (all["widgets"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(json("""{"id":"1","type":"reading-streak","enabled":true,"order":1}"""), (all["widgets"] as kotlinx.serialization.json.JsonArray)[0])
        val scoped = dashboardConfigWith(config, DEFAULT_WIDGETS, listOf(4, 4, 6))
        assertEquals(json("[4,6]"), scoped["libraryIds"])
        // A part not changed is left as the server has it.
        val scopeOnly = dashboardConfigWith(json("""{"widgets":[{"type":"x"}],"libraryIds":[1]}""") as JsonObject, null, listOf(2))
        assertEquals(json("""[{"type":"x"}]"""), scopeOnly["widgets"])
        assertEquals(json("[2]"), scopeOnly["libraryIds"])
        val widgetsOnly = dashboardConfigWith(config, DEFAULT_WIDGETS, null, setLibraries = false)
        assertEquals(json("[1]"), widgetsOnly["libraryIds"])
    }

    @Test
    fun widgetsOfATypeThisAppDoesNotKnowKeepTheirSlot() {
        val saved = json(
            """[{"id":"1","type":"reading-streak","enabled":true,"order":1},{"id":"13","type":"podcast-streak","enabled":false,"order":2,"size":"1x1"},{"id":"3","type":"reading-goal","enabled":true,"order":3}]""",
        )
        val normalized = normalizeWidgets(saved)
        // Moved in the app: the goal first, then the streak.
        val merged = mergeWidgets(saved, listOf(normalized[1], normalized[0]) + normalized.drop(2))
        assertEquals(13, merged.size)
        assertEquals("reading-goal", merged[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(json("""{"id":"13","type":"podcast-streak","enabled":false,"order":2,"size":"1x1"}"""), merged[1])
        assertEquals("reading-streak", merged[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals((1..13).toList(), merged.map { it.jsonObject["order"]?.jsonPrimitive?.content?.toInt() })
    }

    @Test
    fun widgetPayloadsDecode() {
        assertEquals(WidgetData.Empty, decodeWidget(WidgetType.LONG_WAIT, JsonNull))
        assertNull(decodeWidget(WidgetType.READING_GOAL, JsonNull))
        val wait = decodeWidget(WidgetType.LONG_WAIT, json("""{"bookId":5,"title":"Old","hasCover":true,"addedAt":"2020-01-01","waitingDays":1200,"pageCount":null,"genre":null,"fileId":9,"fileFormat":"epub"}"""))
        assertEquals(1200, (wait as LongWaitData).waitingDays)
        val projection = decodeWidget(WidgetType.YEAR_PROJECTION, json("""{"projectedBooks":14,"projectedPages":4200,"projectedHours":61.5,"booksCompletedYtd":9,"daysRemaining":97,"trend":"up"}"""))
        assertEquals(61.5, (projection as YearProjectionData).projectedHours, 0.0)
        val rhythm = decodeWidget(WidgetType.READING_RHYTHM, json("""{"days":[{"date":"2026-09-24","readingSeconds":1800}],"consistencyPercent":50,"avgSecondsPerDay":900,"activeDays":7,"totalDays":14}"""))
        assertEquals(1800.0, (rhythm as ReadingRhythmData).days.single().readingSeconds, 0.0)
        assertTrue(decodeWidget(WidgetType.CURRENTLY_READING, json("""{"books":[]}""")) is WidgetData.Reading)
    }

    @Test
    fun shortWidgetsPairInPortraitAndEverythingInLandscape() {
        val types = listOf(WidgetType.READING_STREAK, WidgetType.READING_GOAL, WidgetType.CURRENTLY_READING, WidgetType.YEAR_PROJECTION, WidgetType.LONG_WAIT, WidgetType.DIVERSITY_SCORE)
        assertEquals(
            listOf(
                listOf(WidgetType.READING_STREAK, WidgetType.READING_GOAL),
                listOf(WidgetType.CURRENTLY_READING),
                listOf(WidgetType.YEAR_PROJECTION, WidgetType.LONG_WAIT),
                listOf(WidgetType.DIVERSITY_SCORE),
            ),
            widgetRows(types, twoColumns = false),
        )
        assertEquals(3, widgetRows(types, twoColumns = true).size)
    }

    @Test
    fun theStreakWrapsItsContentOnlyWhenStacked() {
        // The Nexus arrange: stacked, the streak card is as tall as its content.
        assertTrue(rowWrapsContent(listOf(WidgetType.READING_STREAK), twoColumns = false))
        // Side by side (a short neighbour in portrait, or two columns) it has the card height.
        assertFalse(rowWrapsContent(listOf(WidgetType.READING_STREAK, WidgetType.READING_GOAL), twoColumns = false))
        assertFalse(rowWrapsContent(listOf(WidgetType.CURRENTLY_READING, WidgetType.READING_STREAK), twoColumns = true))
        assertFalse(rowWrapsContent(listOf(WidgetType.READING_STREAK), twoColumns = true))
        // Currently Reading stacked shrinks to its books too (up to the card height).
        assertTrue(rowWrapsContent(listOf(WidgetType.CURRENTLY_READING), twoColumns = false))
        assertFalse(rowWrapsContent(listOf(WidgetType.CURRENTLY_READING), twoColumns = true))
        // Every other card keeps the one height.
        assertFalse(rowWrapsContent(listOf(WidgetType.DIVERSITY_SCORE), twoColumns = false))
        val rows = widgetRows(listOf(WidgetType.CURRENTLY_READING, WidgetType.READING_STREAK), twoColumns = false)
        assertEquals(listOf(true, true), rows.map { rowWrapsContent(it, twoColumns = false) })
    }

    @Test
    fun shelvesNormalizeAndAskWithinTheServerLimits() {
        assertEquals(DEFAULT_SHELVES, normalizeShelves(emptyList()))
        val shelves = normalizeShelves(
            listOf(
                ShelfConfig("1", "continue-listening"),
                ShelfConfig("2", "want-to-read", limit = 30, rows = 3),
                ShelfConfig("3", "smart-scope", smartScopeId = 7, label = "Cosy"),
                ShelfConfig("4", "random", smartScopeId = 7, rows = 9),
            ),
        )
        assertEquals(listOf("2", "3", "4"), shelves.map { it.id })
        assertEquals(50, shelves[0].bookLimit)
        assertNull(shelves[2].smartScopeId)
        assertEquals(3, shelves[2].rows)
        val body = ApiJson.encodeToString(ShelfBatchRequest.serializer(), ShelfBatchRequest(shelves.map { it.toRequest() }))
        assertEquals(
            """{"items":[{"id":"2","type":"want-to-read","limit":50},{"id":"3","type":"smart-scope","limit":20,"smartScopeId":7},{"id":"4","type":"random","limit":60}]}"""
                .replace("\"limit\":60", "\"limit\":50"),
            body,
        )
        assertEquals(listOf("2", "4"), pruneScopeShelves(shelves, emptyMap()).map { it.id })
        assertEquals("Reading nook", pruneScopeShelves(shelves, mapOf(7L to "Reading nook"))[1].label)
    }

    @Test
    fun bandsAndStorage() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5)), chunkIntoBands(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(listOf(listOf(1)), chunkIntoBands(listOf(1), 3))
        assertEquals("0 B", formatStorage(0.0))
        assertEquals("1.5 GB", formatStorage(1.5 * 1024 * 1024 * 1024))
    }
}
