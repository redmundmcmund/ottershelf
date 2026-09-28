package io.github.ottershelf.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import io.github.ottershelf.core.network.ApiJson

/**
 * The app's own data on the account: the `bookorbitAndroid` key of users.settings (read from
 * `GET auth/me`, written whole with `PATCH users/me/settings`, see [AppSettingsRepository]). It
 * travels with the account, so it survives a new phone, and BookOrbit web leaves it alone.
 *
 * Keep it small (tens of KB at most: users.settings is loaded on every request). Maps are keyed by
 * book id (JSON object keys are strings: `{"123": 312}`).
 *
 * **Versioning:** [v] is the schema version this app wrote. Adding a field with a default needs no
 * new version. A change that reinterprets a field bumps [VERSION] and adds a step to [migrate].
 * Keys this app version doesn't know (written by a newer one) are kept when it writes the key, and
 * so is a value it can't read (see [decode] and [encodeChange]): a change writes only what it
 * changed.
 *
 * Features may add fields here (small, with defaults); list the change as a shared-file edit.
 */
@Serializable
data class AppSettings(
    val v: Int = VERSION,
    /** Page total of the user's edition per book id, when it differs from `BookDetail.pageCount`. */
    val pageTotals: Map<Long, Int> = emptyMap(),
    /**
     * A current page set by hand per book id (the pencil edit, no session behind it). It shows
     * until a session ends after [PageOverride.at]; see PageMath.currentPage.
     */
    val pageOverrides: Map<Long, PageOverride> = emptyMap(),
    /** Per book id, how progress is entered and shown when not in pages. */
    val progressUnits: Map<Long, ProgressUnit> = emptyMap(),
    /** Daily reading goal in minutes (the server has none); null for no goal. */
    val dailyGoalMinutes: Int? = null,
    val timer: TimerPrefs = TimerPrefs(),
    /** Highlights and notes (feature.notes): liked notes and Memorize reviews. */
    val notes: NotesPrefs = NotesPrefs(),
    /**
     * Quotes (feature.quotes): the source photos the user kept, annotation id -> file name in this
     * phone's `files/quote-photos/<account>/` (the photos themselves are never uploaded).
     */
    val quotePhotos: Map<Long, String> = emptyMap(),
    /**
     * The Dashboard (feature.home): every card, widgets and shelves, in the order the user arranged
     * them ("w:<widget type>", "s:<shelf id>"); empty until the user first does. The widgets' order
     * among themselves is also the account's dashboardConfig (feature/home/model/DashboardOrder.kt).
     */
    val dashboardOrder: List<String> = emptyList(),
    /**
     * Next in series (feature.seriesnext): offer the next book of a series when the user finishes one,
     * on the reader's last page, the book page, the finish celebration and the timer's result.
     * Switched in Settings > Reading.
     */
    val nextInSeries: Boolean = true,
) {
    fun pageTotal(bookId: Long, serverPageCount: Int?): Int? =
        pageTotals[bookId]?.takeIf { it > 0 } ?: serverPageCount?.takeIf { it > 0 }

    companion object {
        const val VERSION = 1

        /**
         * The users.settings key. Its name is older than the Ottershelf name and stays: the
         * account's page totals, goal and timer preferences are stored under it on the server.
         */
        const val KEY = "bookorbitAndroid"

        /** The JSON names of the fields above: replaced whole when the model is written. */
        internal val FIELD_NAMES: Set<String> =
            serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

        /**
         * Reads the stored key (null: defaults), migrating older versions. A part this app can't
         * read (a newer app's enum value in a map, a number that isn't one) falls back on its own:
         * a bad map entry is left out, a bad field takes its default, and the rest is read.
         */
        fun decode(json: JsonObject?): AppSettings {
            if (json == null) return AppSettings()
            val migrated = migrate(json)
            // A key that reads whole (nearly always) is read once; only a broken one is taken apart.
            return read(migrated)
                ?: read(readablePart(migrated, depth = 0) { it as? JsonObject ?: JsonObject(emptyMap()) } as? JsonObject)
                ?: AppSettings()
        }

        private fun read(json: JsonObject?): AppSettings? =
            json?.let { runCatching { ApiJson.decodeFromJsonElement(serializer(), it) }.getOrNull() }

        /**
         * [value] as far as it can be read where [placed] puts it (alone in the key, so every
         * other field is at its default), or null when none of it can. An object that can't be
         * read whole keeps the entries that can, if that makes it readable. [depth]: how far
         * below the key [value] is; nothing deeper than [PART_DEPTH] is taken apart, so a value
         * nested far deeper than the model (a bad server value) costs no more than a flat one.
         */
        private fun readablePart(value: JsonElement, depth: Int, placed: (JsonElement) -> JsonObject): JsonElement? {
            if (read(placed(value)) != null) return value
            if (value !is JsonObject || depth >= PART_DEPTH) return null
            val kept = LinkedHashMap<String, JsonElement>()
            for ((name, part) in value) {
                readablePart(part, depth + 1) { placed(JsonObject(mapOf(name to it))) }?.let { kept[name] = it }
            }
            return JsonObject(kept).takeIf { read(placed(it)) != null }
        }

        /** The model's deepest part: a field, a map entry or a nested field, a notes map's entry. */
        private const val PART_DEPTH = 3

        /** Rewrites an older version's JSON into the current shape (nothing to do for version 1). */
        internal fun migrate(json: JsonObject): JsonObject {
            // Version 1 is the first, so there is nothing to rewrite yet. A later step looks like
            // `if (version(json) < 2) json = ...`, one per version, in order.
            return json
        }

        /** The version that wrote [json] (1 when missing). */
        fun version(json: JsonObject?): Int = (json?.get("v") as? JsonPrimitive)?.intOrNull ?: 1

        /**
         * [settings] written over [previous]: this app's fields are replaced (a field now at its
         * null default disappears), anything else in [previous] (a newer app's fields) is kept.
         */
        fun encodeOver(previous: JsonObject?, settings: AppSettings): JsonObject {
            // Never lower the version a newer app wrote: it would migrate its own data again.
            val stamped = settings.copy(v = maxOf(VERSION, version(previous)))
            val own = ApiJson.encodeToJsonElement(serializer(), stamped) as JsonObject
            val kept = previous.orEmpty().filterKeys { it !in FIELD_NAMES }
            return JsonObject(kept + own)
        }

        /**
         * [previous], the stored key that [decode] read as [old], changed to [next]: only what
         * differs between [old] and [next] is written, down to single map entries (a field now at
         * its null default disappears). Everything else in [previous] stays as it was, a newer
         * app's fields and anything this app couldn't read included, so a change can't reset what
         * it didn't touch.
         */
        fun encodeChange(previous: JsonObject?, old: AppSettings, next: AppSettings): JsonObject {
            val before = ApiJson.encodeToJsonElement(serializer(), old)
            val after = ApiJson.encodeToJsonElement(serializer(), next)
            // The three-way merge applies exactly the change from before to after: a key the change
            // left alone keeps the stored value, a changed one takes next's.
            val changed = JsonMerge.merge(before, after, previous?.let(::migrate)) as? JsonObject ?: JsonObject(emptyMap())
            // Never lower the version a newer app wrote: it would migrate its own data again.
            return JsonObject(changed + ("v" to JsonPrimitive(maxOf(VERSION, version(previous)))))
        }
    }
}

/** A hand-set current page and when it was set (epoch milliseconds). */
@Serializable
data class PageOverride(val page: Int, val at: Long)

@Serializable
enum class ProgressUnit {
    @SerialName("page") PAGE,
    @SerialName("percent") PERCENT,
}

@Serializable
enum class TimerMode {
    @SerialName("count_up") COUNT_UP,
    @SerialName("countdown") COUNTDOWN,
}

/** The timer's defaults, as last chosen. */
@Serializable
data class TimerPrefs(
    val mode: TimerMode = TimerMode.COUNT_UP,
    /** The countdown's length. */
    val countdownMinutes: Int = 30,
    /** Keep the screen on while the timer screen shows. */
    val keepScreenOn: Boolean = true,
)

/**
 * What the app keeps about BookOrbit's annotations (feature.notes). Kept small: each map holds at
 * most [MAX_ENTRIES] entries (the oldest ids go first).
 */
@Serializable
data class NotesPrefs(
    /** Liked highlights: annotation id -> its book id (so the Liked filter asks only those books). */
    val liked: Map<Long, Long> = emptyMap(),
    /** How many times each annotation id was reviewed in Memorize. */
    val reviews: Map<Long, Int> = emptyMap(),
) {
    companion object {
        const val MAX_ENTRIES = 400
    }
}
