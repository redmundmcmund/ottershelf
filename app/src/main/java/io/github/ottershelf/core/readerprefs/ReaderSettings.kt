package io.github.ottershelf.core.readerprefs

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.format.ReaderKind
import io.github.ottershelf.core.network.ApiJson

/**
 * The web's reader format groups (packages/types reader-settings.ts `ReaderFormatGroup`, audio left
 * out): the path segment of `reader/defaults/:formatGroup`, and the key of `GET reader/defaults`.
 * The server picks a file's group from its format (`getFormatGroup`: MOBI, AZW3, FB2... are epub).
 */
enum class ReaderFormatGroup(val id: String) {
    Epub("epub"),
    Pdf("pdf"),
    Cbx("cbx");

    companion object {
        fun of(format: String?): ReaderFormatGroup = when (BookFormats.readerFor(format)) {
            ReaderKind.Comics -> Cbx
            ReaderKind.Pdf -> Pdf
            else -> Epub
        }
    }
}

/**
 * The comics reader's settings (`CbxReaderSettings`), defaults = `CBX_READER_DEFAULTS`. Values are
 * the web's ids: fitMode fit-page | fit-width | fit-height | actual; viewMode single | two-page;
 * scrollMode paginated | infinite | long-strip; direction ltr | rtl; spreadAlignment normal |
 * shifted; spreadGap 0..64 (px); widePageSingletonMode auto | disable; bgColor black | gray | white.
 */
@Serializable
data class CbxReaderSettings(
    val fitMode: String = "fit-page",
    val viewMode: String = "single",
    val scrollMode: String = "paginated",
    val direction: String = "ltr",
    val spreadAlignment: String = "normal",
    val spreadGap: Int = 0,
    val forceTwoPage: Boolean = false,
    val widePageSingletonMode: String = "auto",
    val bgColor: String = "black",
    val autoAdvance: Boolean = false,
)

/**
 * The PDF reader's settings (`PdfReaderSettings`), defaults = `PDF_READER_DEFAULTS`: scrollMode
 * vertical | horizontal | page; spread none | odd | even | auto; zoomMode fit-width | fit-page |
 * automatic | custom; customScale 0.25..4 (used with custom); rotation 0 | 90 | 180 | 270.
 */
@Serializable
data class PdfReaderSettings(
    val scrollMode: String = "page",
    val spread: String = "none",
    val zoomMode: String = "fit-page",
    val customScale: Double = 1.0,
    val rotation: Int = 0,
)

/**
 * One format group's settings: its type, defaults and the web's sanitiser (useReaderSettings.ts
 * `sanitize*PartialSettings`, the same rules the server's schema enforces), which keeps only the
 * keys it knows with valid values, so nothing the server would refuse is ever sent.
 */
class ReaderSettingsSpec<T>(
    val group: ReaderFormatGroup,
    val serializer: KSerializer<T>,
    val defaults: T,
    private val rules: Map<String, (JsonElement) -> JsonElement?>,
) {
    /** The valid keys of [raw], values normalised; unknown keys and bad values dropped. */
    fun sanitize(raw: JsonObject?): JsonObject {
        if (raw == null) return JsonObject(emptyMap())
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in raw) {
            val rule = rules[key] ?: continue
            rule(value)?.let { out[key] = it }
        }
        return JsonObject(out)
    }

    fun encode(value: T): JsonObject = ApiJson.encodeToJsonElement(serializer, value).jsonObject

    /** The web's merge: built-in defaults, then the account's default for the group, then the book's own. */
    fun effective(default: JsonObject?, book: JsonObject?): T {
        val merged = encode(defaults) + sanitize(default) + sanitize(book)
        return ApiJson.decodeFromJsonElement(serializer, JsonObject(merged))
    }

    /** The keys whose values differ from [before] to [after] (what a PATCH `set` carries). */
    fun changes(before: T, after: T): JsonObject {
        val a = encode(before)
        return JsonObject(encode(after).filter { (key, value) -> a[key] != value })
    }

    /**
     * The same group and rules with other built-in defaults (the bottom of the merge), for a reader
     * whose phone default differs from the web's (the PDF reader's vertical strip). What is stored
     * and sent is unaffected.
     */
    fun withDefaults(defaults: T): ReaderSettingsSpec<T> = ReaderSettingsSpec(group, serializer, defaults, rules)
}

object ReaderSettingsSpecs {
    private fun oneOf(vararg allowed: String): (JsonElement) -> JsonElement? = { v ->
        (v as? JsonPrimitive)?.takeIf { it.isString && it.content in allowed }
    }

    private fun bool(): (JsonElement) -> JsonElement? = { v -> (v as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull != null } }

    private fun intIn(min: Long, max: Long): (JsonElement) -> JsonElement? = { v ->
        val p = v as? JsonPrimitive
        val d = p?.takeIf { !it.isString }?.doubleOrNull
        if (d != null && d == Math.floor(d) && d >= min && d <= max) JsonPrimitive(d.toLong()) else null
    }

    private fun numberIn(min: Double, max: Double): (JsonElement) -> JsonElement? = { v ->
        val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (d != null && d.isFinite() && d >= min && d <= max) v else null
    }

    val Cbx = ReaderSettingsSpec(
        group = ReaderFormatGroup.Cbx,
        serializer = CbxReaderSettings.serializer(),
        defaults = CbxReaderSettings(),
        rules = mapOf(
            "fitMode" to oneOf("fit-page", "fit-width", "fit-height", "actual"),
            "viewMode" to oneOf("single", "two-page"),
            "scrollMode" to oneOf("paginated", "infinite", "long-strip"),
            "direction" to oneOf("ltr", "rtl"),
            "spreadAlignment" to oneOf("normal", "shifted"),
            "spreadGap" to intIn(0, 64),
            "forceTwoPage" to bool(),
            "widePageSingletonMode" to oneOf("auto", "disable"),
            "bgColor" to oneOf("black", "gray", "white"),
            "autoAdvance" to bool(),
        ),
    )

    val Pdf = ReaderSettingsSpec(
        group = ReaderFormatGroup.Pdf,
        serializer = PdfReaderSettings.serializer(),
        defaults = PdfReaderSettings(),
        rules = mapOf(
            // The web reads the old `wrapped` as vertical (the server's schema rewrites it too).
            "scrollMode" to { v ->
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                when (s) {
                    "vertical", "horizontal", "page" -> v
                    "wrapped" -> JsonPrimitive("vertical")
                    else -> null
                }
            },
            "spread" to oneOf("none", "odd", "even", "auto"),
            "zoomMode" to oneOf("fit-width", "fit-page", "automatic", "custom"),
            "customScale" to numberIn(0.25, 4.0),
            "rotation" to { v ->
                (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in setOf(0L, 90L, 180L, 270L) }?.let { JsonPrimitive(it) }
            },
        ),
    )
}
