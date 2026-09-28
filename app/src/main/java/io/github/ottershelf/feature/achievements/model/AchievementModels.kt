package io.github.ottershelf.feature.achievements.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * `GET achievements` (packages/types/src/achievement.ts, AchievementCatalogueResponse) and the
 * book query the year in review sends. Responses are subsets: unknown fields are ignored. A
 * hidden achievement not earned yet comes back already masked by the server ("Secret
 * Achievement", icon `lock`, no group, tier, threshold or progress).
 */

@Serializable
data class Achievement(
    val key: String,
    /** Tiers of one badge share a group key (books_finished_1..4); null for a single badge. */
    val groupKey: String? = null,
    val tier: Int? = null,
    /** reading, library, exploration, dedication or devices. */
    val category: String = "reading",
    val name: String = "",
    val description: String? = null,
    /** A kebab-case Lucide name (`book-open`); see AchievementLogic.iconName. */
    val iconName: String? = null,
    /** common, rare, epic or legendary. */
    val rarity: String = "common",
    val threshold: Double? = null,
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
    val earned: Boolean = false,
    /** ISO timestamp. */
    val awardedAt: String? = null,
    /** Only when the book is still one the user can open. */
    val contextBookId: Long? = null,
    val contextBookTitle: String? = null,
    /** Toward [threshold]; null when the server doesn't track progress for it. */
    val currentProgress: Double? = null,
)

@Serializable
data class AchievementCategory(
    val key: String,
    val label: String = "",
    val earnedCount: Int = 0,
    val totalCount: Int = 0,
    val achievements: List<Achievement> = emptyList(),
)

@Serializable
data class AchievementCatalogue(
    val categories: List<AchievementCategory> = emptyList(),
    val totalEarned: Int = 0,
    val totalAvailable: Int = 0,
)

// --- the book query (POST books/query), with a filter: BookQuery in packages/types/src/query.ts ---

/** A rule: [value] is a string (a date key) or a list of strings (`includesAny`). */
@Serializable
data class QueryRule(
    val type: String = "rule",
    val field: String,
    val operator: String,
    val value: JsonElement? = null,
    val valueTo: String? = null,
) {
    companion object {
        fun between(field: String, from: String, to: String) = QueryRule(field = field, operator = "between", value = JsonPrimitive(from), valueTo = to)

        fun after(field: String, date: String) = QueryRule(field = field, operator = "after", value = JsonPrimitive(date))

        fun includesAny(field: String, values: List<String>) = QueryRule(field = field, operator = "includesAny", value = JsonArray(values.map(::JsonPrimitive)))
    }
}

@Serializable
data class QueryGroup(val type: String = "group", val join: String = "AND", val rules: List<QueryRule>)

@Serializable
data class QuerySort(val field: String, val dir: String)

@Serializable
data class QueryPage(val page: Int, val size: Int)

/** Exactly the fields the server's bookQuerySchema takes that this app sends. */
@Serializable
data class FilteredBookQuery(
    val filter: QueryGroup,
    val sort: List<QuerySort>,
    val pagination: QueryPage,
)
