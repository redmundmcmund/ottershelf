package io.github.ottershelf.feature.home.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import io.github.ottershelf.core.model.CurrentlyReading
import io.github.ottershelf.core.model.ReadingStreak
import io.github.ottershelf.core.network.ApiJson

// --- widgets ---------------------------------------------------------------------------------

/**
 * The web dashboard's twelve widgets (packages/types/src/dashboard.ts `WIDGET_TYPE`), in the web's
 * default order. [short] is the web's `1x1` size (220px wide) as against `1x1.5` (336px): two short
 * ones next to each other share a row on the phone.
 */
enum class WidgetType(val id: String, val short: Boolean, val icon: String) {
    READING_STREAK("reading-streak", true, "Flame"),
    CURRENTLY_READING("currently-reading", false, "BookOpen"),
    READING_GOAL("reading-goal", true, "Target"),
    READING_DNA("reading-dna", false, "Dna"),
    MONTHLY_CHALLENGE("monthly-challenge", true, "Swords"),
    HIGHLIGHT_OF_THE_DAY("highlight-of-the-day", false, "Highlighter"),
    NEGLECTED_GEMS("neglected-gems", true, "Gem"),
    READING_RHYTHM("reading-rhythm", false, "HeartPulse"),
    DIVERSITY_SCORE("diversity-score", true, "Compass"),
    LIBRARY_OVERVIEW("library-overview", false, "Library"),
    YEAR_PROJECTION("year-projection", true, "TrendingUp"),
    LONG_WAIT("long-wait", true, "Clock"),
    ;

    companion object {
        fun of(id: String?): WidgetType? = entries.firstOrNull { it.id == id }
    }
}

/** One entry of `dashboardConfig.widgets`; the order is the list's. */
data class WidgetConfig(val id: String, val type: WidgetType, val enabled: Boolean)

/** useDashboardWidgets.ts `DEFAULT_WIDGETS`: the first nine on, the last three off. */
val DEFAULT_WIDGETS: List<WidgetConfig> = WidgetType.entries.mapIndexed { i, type ->
    WidgetConfig(id = (i + 1).toString(), type = type, enabled = i < 9)
}

/**
 * useDashboardWidgets.ts `normalizeWidgets`: the saved entries with a known type (an id kept if it
 * is a string, `enabled` if it is a boolean), else the defaults; then any type the saved list lacks,
 * appended (on, as every type is in the defaults), so a new widget shows up for everyone.
 */
fun normalizeWidgets(raw: JsonElement?): List<WidgetConfig> {
    var list = (raw as? JsonArray).orEmpty()
        .mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = WidgetType.of((obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) ?: return@mapNotNull null
            obj to type
        }
        .mapIndexed { i, (obj, type) ->
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (i + 1).toString()
            val enabled = (obj["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: true
            WidgetConfig(id, type, enabled)
        }
    if (list.isEmpty()) list = DEFAULT_WIDGETS
    val seen = list.map { it.type }.toSet()
    val next = list.size + 1
    val missing = WidgetType.entries.filter { it !in seen }.mapIndexed { i, type -> WidgetConfig((next + i).toString(), type, enabled = true) }
    return list + missing
}

/** The saved form (`{id, type, enabled, order}`), order counting from 1 as the web writes it. */
fun widgetsJson(widgets: List<WidgetConfig>): JsonArray = buildJsonArray {
    widgets.forEachIndexed { i, w ->
        add(
            buildJsonObject {
                put("id", w.id)
                put("type", w.type.id)
                put("enabled", w.enabled)
                put("order", i + 1)
            },
        )
    }
}

/** `dashboardConfig.libraryIds`: positive whole numbers, once each; null (all libraries) when none. */
fun normalizeLibraryIds(raw: JsonElement?): List<Long>? {
    val ids = (raw as? JsonArray).orEmpty().mapNotNull { element ->
        val p = element as? JsonPrimitive ?: return@mapNotNull null
        if (p.isString) return@mapNotNull null
        val d = p.doubleOrNull ?: return@mapNotNull null
        d.toLong().takeIf { it > 0 && it.toDouble() == d }
    }.distinct()
    return ids.ifEmpty { null }
}

/** The widgets and library scope a `dashboardConfig` holds. */
data class DashboardLayout(val widgets: List<WidgetConfig>, val libraryIds: List<Long>?) {
    val enabledWidgets: List<WidgetType> get() = widgets.filter { it.enabled }.map { it.type }

    companion object {
        fun of(config: JsonObject?): DashboardLayout =
            DashboardLayout(normalizeWidgets(config?.get("widgets")), normalizeLibraryIds(config?.get("libraryIds")))
    }
}

/**
 * The whole `dashboardConfig` to write back (useDashboardWidgets.ts `saveWidgets` +
 * `withLibraryIds`): [config] as the server has it, every other key kept (readingGoal and anything
 * a newer client added). [widgets], when given, replaces the widgets ([mergeWidgets]: entries of
 * types this app doesn't know stay); with [setLibraries], `libraryIds` is set, or removed for all
 * libraries. A part not given is left as the server has it.
 */
fun dashboardConfigWith(config: JsonObject?, widgets: List<WidgetConfig>?, libraryIds: List<Long>?, setLibraries: Boolean = true): JsonObject {
    val next = LinkedHashMap<String, JsonElement>(config.orEmpty())
    if (widgets != null) next["widgets"] = mergeWidgets(config?.get("widgets"), widgets)
    if (setLibraries) {
        val ids = libraryIds?.filter { it > 0 }?.distinct().orEmpty()
        if (ids.isEmpty()) next.remove("libraryIds") else next["libraryIds"] = JsonArray(ids.map { JsonPrimitive(it) })
    }
    return JsonObject(next)
}

/**
 * [widgets] written into the [saved] list's slots: an entry of a type this app doesn't know (a
 * newer server's widget) keeps its slot and fields, the known slots take [widgets] in order, and
 * any left over go at the end; `order` is the position, counting from 1, as the web writes it.
 */
fun mergeWidgets(saved: JsonElement?, widgets: List<WidgetConfig>): JsonArray {
    val known = ArrayDeque(normalizeWidgets(widgetsJson(widgets)))
    val slots = mutableListOf<Any>()
    (saved as? JsonArray).orEmpty().forEach { element ->
        val obj = element as? JsonObject ?: return@forEach
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
        when {
            WidgetType.of(type) == null -> slots += obj
            known.isNotEmpty() -> slots += known.removeFirst()
        }
    }
    slots.addAll(known)
    return buildJsonArray {
        slots.forEachIndexed { i, slot ->
            add(
                when (slot) {
                    is WidgetConfig -> buildJsonObject {
                        put("id", slot.id)
                        put("type", slot.type.id)
                        put("enabled", slot.enabled)
                        put("order", i + 1)
                    }
                    else -> JsonObject((slot as JsonObject) + ("order" to JsonPrimitive(i + 1)))
                },
            )
        }
    }
}

// --- widget payloads (packages/types/src/dashboard.ts) --------------------------------------

/** A widget's answer; [Empty] is a widget with nothing to show (no highlight, no long wait). */
sealed interface WidgetData {
    data object Empty : WidgetData
    data class Reading(val value: CurrentlyReading) : WidgetData
    data class Streak(val value: ReadingStreak) : WidgetData
}

@Serializable
data class ReadingGoalData(val goalBooks: Int? = null, val completedBooks: Int = 0, val year: Int = 0) : WidgetData

@Serializable
data class ReadingDnaData(
    val archetype: String = "",
    val lengthScore: Double = 0.0,
    val varietyScore: Double = 0.0,
    val rhythmScore: Double = 0.0,
    val timeScore: Double = 0.0,
    val speedScore: Double = 0.0,
    val lengthLabel: String = "",
    val varietyLabel: String = "",
    val rhythmLabel: String = "",
    val timeLabel: String = "",
    val speedLabel: String = "",
    val booksAnalyzed: Int = 0,
) : WidgetData

@Serializable
data class MonthlyChallengeData(
    val challengeType: String = "",
    val title: String = "",
    val description: String = "",
    val progress: Double = 0.0,
    val target: Double = 0.0,
    val completed: Boolean = false,
    val month: Int = 0,
    val year: Int = 0,
) : WidgetData

@Serializable
data class HighlightData(
    val text: String = "",
    val note: String? = null,
    val bookTitle: String? = null,
    val bookId: Long = 0,
    val hasCover: Boolean = false,
    val chapterTitle: String? = null,
    val createdAt: String? = null,
) : WidgetData

@Serializable
data class NeglectedGem(
    val bookId: Long,
    val title: String? = null,
    val hasCover: Boolean = false,
    val rating: Double = 0.0,
    val waitingDays: Int = 0,
    val genre: String? = null,
)

@Serializable
data class NeglectedGemsData(val gems: List<NeglectedGem> = emptyList()) : WidgetData

@Serializable
data class RhythmDay(val date: String, val readingSeconds: Double = 0.0)

@Serializable
data class ReadingRhythmData(
    val days: List<RhythmDay> = emptyList(),
    val consistencyPercent: Double = 0.0,
    val avgSecondsPerDay: Double = 0.0,
    val activeDays: Int = 0,
    val totalDays: Int = 0,
) : WidgetData

@Serializable
data class DiversityScoreData(
    val score: Double = 0.0,
    val label: String = "",
    val genreScore: Double = 0.0,
    val authorScore: Double = 0.0,
    val eraScore: Double = 0.0,
    val languageScore: Double = 0.0,
    val booksAnalyzed: Int = 0,
) : WidgetData

@Serializable
data class LibraryOverviewData(
    val totalBooks: Long = 0,
    val totalAuthors: Long = 0,
    val totalSeries: Long = 0,
    val totalStorageBytes: Double = 0.0,
    val booksAddedThisYear: Long = 0,
) : WidgetData

@Serializable
data class YearProjectionData(
    val projectedBooks: Double = 0.0,
    val projectedPages: Double = 0.0,
    val projectedHours: Double = 0.0,
    val booksCompletedYtd: Int = 0,
    val daysRemaining: Int = 0,
    /** up, down or stable */
    val trend: String = "stable",
) : WidgetData

@Serializable
data class LongWaitData(
    val bookId: Long,
    val title: String? = null,
    val hasCover: Boolean = false,
    val addedAt: String? = null,
    val waitingDays: Int = 0,
    val pageCount: Int? = null,
    val genre: String? = null,
    val fileId: Long? = null,
    val fileFormat: String? = null,
) : WidgetData

/**
 * A widget's batch `data`, decoded for its [type]; null if it doesn't parse (shown as failed).
 * A JSON null is [WidgetData.Empty] for the two widgets that may have nothing.
 */
fun decodeWidget(type: WidgetType, data: JsonElement?): WidgetData? {
    if (data == null || data is JsonNull) {
        return if (type == WidgetType.HIGHLIGHT_OF_THE_DAY || type == WidgetType.LONG_WAIT) WidgetData.Empty else null
    }
    fun <T : WidgetData> as_(serializer: KSerializer<T>): T? = runCatching { ApiJson.decodeFromJsonElement(serializer, data) }.getOrNull()
    return when (type) {
        WidgetType.CURRENTLY_READING -> runCatching { WidgetData.Reading(ApiJson.decodeFromJsonElement(CurrentlyReading.serializer(), data)) }.getOrNull()
        WidgetType.READING_STREAK -> runCatching { WidgetData.Streak(ApiJson.decodeFromJsonElement(ReadingStreak.serializer(), data)) }.getOrNull()
        WidgetType.READING_GOAL -> as_(ReadingGoalData.serializer())
        WidgetType.READING_DNA -> as_(ReadingDnaData.serializer())
        WidgetType.MONTHLY_CHALLENGE -> as_(MonthlyChallengeData.serializer())
        WidgetType.HIGHLIGHT_OF_THE_DAY -> as_(HighlightData.serializer())
        WidgetType.NEGLECTED_GEMS -> as_(NeglectedGemsData.serializer())
        WidgetType.READING_RHYTHM -> as_(ReadingRhythmData.serializer())
        WidgetType.DIVERSITY_SCORE -> as_(DiversityScoreData.serializer())
        WidgetType.LIBRARY_OVERVIEW -> as_(LibraryOverviewData.serializer())
        WidgetType.YEAR_PROJECTION -> as_(YearProjectionData.serializer())
        WidgetType.LONG_WAIT -> as_(LongWaitData.serializer())
    }
}

/**
 * How the widgets sit on the screen: two columns when [twoColumns] (landscape), else one each,
 * except that two [WidgetType.short] ones in a row share it ([cardRows] with widgets only).
 */
fun widgetRows(types: List<WidgetType>, twoColumns: Boolean): List<List<WidgetType>> = cardRows(types, twoColumns) { it }

/**
 * Whether a row of [widgetRows] is as tall as its content (up to the one card height) instead of
 * exactly that height: the Reading Streak or Currently Reading stacked on a row of its own (the
 * Nexus DashboardFragment.arrange: stacked on a narrow screen the streak card wraps its content;
 * Currently Reading shrinks to its books and scrolls past the card height). Side by side, both
 * have the dash card height.
 */
fun rowWrapsContent(row: List<WidgetType>, twoColumns: Boolean): Boolean =
    !twoColumns && row.size == 1 && row[0] in WRAPPING_WIDGETS

private val WRAPPING_WIDGETS = setOf(WidgetType.READING_STREAK, WidgetType.CURRENTLY_READING)

// --- shelves ---------------------------------------------------------------------------------

/**
 * The web's book shelves (`BOOK_SCROLLER_TYPE`) without the listening one, with the icons its
 * DashboardScroller gives them.
 */
enum class ShelfType(val id: String, val icon: String) {
    CONTINUE_READING("continue-reading", "BookMarked"),
    WANT_TO_READ("want-to-read", "BookmarkPlus"),
    UP_NEXT_IN_SERIES("up-next-in-series", "ListOrdered"),
    RECENTLY_ADDED("recently-added", "Sparkles"),
    RANDOM("random", "Shuffle"),
    SMART_SCOPE("smart-scope", "Aperture"),
    ;

    companion object {
        fun of(id: String?): ShelfType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A shelf (the web's `ScrollerConfig`, kept on the device as the web keeps it in localStorage).
 * [limit] is books per row; [rows] 1..3. [label] is the Smart Scope's name for a scope shelf.
 */
@Serializable
data class ShelfConfig(
    val id: String,
    val type: String,
    val label: String = "",
    val enabled: Boolean = true,
    val limit: Int = DEFAULT_SHELF_LIMIT,
    val rows: Int = 1,
    val smartScopeId: Long? = null,
) {
    val shelfType: ShelfType get() = ShelfType.of(type) ?: ShelfType.RECENTLY_ADDED

    /** How many books to ask for: [limit] per row, 50 at most (shelf-rows.ts `shelfBookLimit`). */
    val bookLimit: Int get() = (limit.coerceAtLeast(1) * rows.coerceIn(MIN_SHELF_ROWS, MAX_SHELF_ROWS)).coerceAtMost(MAX_SHELF_BOOKS)

    /** A scope shelf without its scope can't be asked for. */
    val requestable: Boolean get() = shelfType != ShelfType.SMART_SCOPE || (smartScopeId ?: 0) > 0

    /** Identifies what it shows, so books fetched for another setting aren't shown under it. */
    val key: String get() = "$id:$type:${smartScopeId ?: 0}:$bookLimit"
}

const val MAX_SHELVES = 8
const val MAX_SHELF_BOOKS = 50
const val DEFAULT_SHELF_LIMIT = 20
const val MIN_SHELF_ROWS = 1
const val MAX_SHELF_ROWS = 3

/** Below the web's `sm` breakpoint (640px) a shelf shows at most two rows. */
const val COMPACT_MAX_SHELF_ROWS = 2
val SHELF_ROW_OPTIONS = listOf(1, 2, 3)
val SHELF_LIMIT_OPTIONS = listOf(10, 20, 30, 50)

/** useDashboardConfig.ts `DEFAULT_SCROLLERS`, without Continue Listening (and podcasts). */
val DEFAULT_SHELVES: List<ShelfConfig> = listOf(
    ShelfConfig("2", ShelfType.RECENTLY_ADDED.id, enabled = true),
    ShelfConfig("3", ShelfType.RANDOM.id, enabled = true),
    ShelfConfig("1", ShelfType.CONTINUE_READING.id, enabled = true),
    ShelfConfig("6", ShelfType.WANT_TO_READ.id, enabled = false),
    ShelfConfig("4", ShelfType.UP_NEXT_IN_SERIES.id, enabled = false),
)

/** useDashboardConfig.ts `normalizeScrollers`: known types only, at most 8, else the defaults. */
fun normalizeShelves(shelves: List<ShelfConfig>?): List<ShelfConfig> {
    val list = shelves.orEmpty()
        .filter { ShelfType.of(it.type) != null }
        .mapIndexed { i, s ->
            s.copy(
                id = s.id.ifBlank { (i + 1).toString() },
                limit = if (s.limit > 0) s.limit.coerceAtMost(MAX_SHELF_BOOKS) else DEFAULT_SHELF_LIMIT,
                rows = s.rows.coerceIn(MIN_SHELF_ROWS, MAX_SHELF_ROWS),
                smartScopeId = s.smartScopeId?.takeIf { s.type == ShelfType.SMART_SCOPE.id && it > 0 },
            )
        }
        .take(MAX_SHELVES)
    return list.ifEmpty { DEFAULT_SHELVES }
}

/** Shelves of deleted Smart Scopes dropped, and scope shelves renamed after their scope. */
fun pruneScopeShelves(shelves: List<ShelfConfig>, scopes: Map<Long, String>): List<ShelfConfig> =
    shelves.mapNotNull { s ->
        if (s.shelfType != ShelfType.SMART_SCOPE) return@mapNotNull s
        val name = s.smartScopeId?.let { scopes[it] } ?: return@mapNotNull null
        s.copy(label = name)
    }.ifEmpty { DEFAULT_SHELVES }

/** How many rows a shelf shows on this screen (shelf-rows.ts `effectiveShelfRows`). */
fun effectiveShelfRows(rows: Int, compact: Boolean): Int {
    val r = rows.coerceIn(MIN_SHELF_ROWS, MAX_SHELF_ROWS)
    return if (compact) r.coerceAtMost(COMPACT_MAX_SHELF_ROWS) else r
}

/** shelf-rows.ts `chunkIntoBands`: the books in [rows] bands, the first ones fullest. */
fun <T> chunkIntoBands(items: List<T>, rows: Int): List<List<T>> {
    val count = rows.coerceIn(MIN_SHELF_ROWS, MAX_SHELF_ROWS)
    if (items.isEmpty()) return emptyList()
    if (count == 1) return listOf(items)
    val perBand = (items.size + count - 1) / count
    return items.chunked(perBand).take(count)
}

/** The stored form of the shelves (the web's `{scrollers, shelfLayout}` object, minus the layout). */
@Serializable
data class StoredShelves(val scrollers: List<ShelfConfig> = emptyList())

/** `POST dashboard/scrollers/batch` (DashboardScrollerBatchDto; the server rejects unknown fields). */
@Serializable
data class ShelfBatchRequest(val items: List<ShelfBatchItem>)

@Serializable
data class ShelfBatchItem(val id: String, val type: String, val limit: Int, val smartScopeId: Long? = null)

fun ShelfConfig.toRequest(): ShelfBatchItem =
    ShelfBatchItem(id = id, type = type, limit = bookLimit, smartScopeId = smartScopeId?.takeIf { shelfType == ShelfType.SMART_SCOPE })

/** A bytes figure as the web's LibraryOverviewWidget writes it: "0 B", "512 KB", "1.4 GB". */
fun formatStorage(bytes: Double): String {
    if (bytes <= 0.0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val i = (kotlin.math.ln(bytes) / kotlin.math.ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / Math.pow(1024.0, i.toDouble())
    return if (i == 0) "${value.toLong()} ${units[i]}" else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[i])
}
