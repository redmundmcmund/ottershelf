package io.github.ottershelf.feature.achievements

import androidx.compose.runtime.Immutable
import io.github.ottershelf.feature.achievements.model.Achievement
import io.github.ottershelf.feature.achievements.model.AchievementCatalogue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The filter chips over the catalogue. */
enum class AchievementFilter { ALL, EARNED, IN_PROGRESS }

/**
 * One badge card: a single achievement, or every tier of a tiered one (the web's TieredGroup),
 * shown as its highest earned tier (else its first) with progress toward the next.
 *
 * @property icon the Lucide PascalCase name.
 * @property secret a hidden achievement not earned yet: shown locked as "???".
 * @property progress / [threshold] progress toward the next tier (or the achievement), when tracked.
 * @property awardedAt when the shown tier was earned (ISO).
 */
@Immutable
data class Badge(
    val key: String,
    val category: String,
    val name: String,
    val description: String?,
    val icon: String,
    val rarity: String,
    val tiers: List<Achievement>,
    val earnedCount: Int,
    val secret: Boolean,
    val progress: Double?,
    val threshold: Double?,
    val nextName: String?,
    val awardedAt: String?,
    val contextBookId: Long?,
    val contextBookTitle: String?,
) {
    val tiered: Boolean get() = tiers.size > 1
    val earned: Boolean get() = earnedCount > 0
    val complete: Boolean get() = earnedCount == tiers.size

    /** 0..1 toward the next threshold; null when there is nothing to track (or it is complete). */
    val fraction: Float?
        get() {
            if (complete) return null
            val p = progress ?: return null
            val t = threshold?.takeIf { it > 0 } ?: return null
            return (p / t).toFloat().coerceIn(0f, 1f)
        }

    /** Working toward something: some progress (or tiers) but not every tier yet. */
    val inProgress: Boolean get() = !complete && !secret && ((progress ?: 0.0) > 0.0 || (tiered && earnedCount > 0))
}

/** A category of the catalogue: its badges and the server's counts (tiers count one each). */
@Immutable
data class BadgeSection(val key: String, val label: String, val earned: Int, val total: Int, val badges: List<Badge>)

object AchievementLogic {

    /** `book-open` -> `BookOpen`, `minimize-2` -> `Minimize2`; a PascalCase name is kept. */
    fun iconName(name: String?): String {
        if (name.isNullOrBlank()) return "Trophy"
        if ('-' !in name && name.first().isUpperCase()) return name
        return name.split('-').filter { it.isNotEmpty() }.joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    }

    /** The category's icon (the web's AchievementCategorySection). */
    fun categoryIcon(key: String): String = when (key) {
        "reading" -> "BookOpen"
        "library" -> "Library"
        "exploration" -> "Compass"
        "dedication" -> "Flame"
        "devices" -> "TabletSmartphone"
        else -> "Trophy"
    }

    /** Tiers grouped (by group key, sorted by tier), singles kept; ordered by sort order as the web does. */
    fun badges(achievements: List<Achievement>): List<Badge> {
        val groups = LinkedHashMap<String, MutableList<Achievement>>()
        val singles = mutableListOf<Achievement>()
        for (a in achievements) {
            if (a.groupKey != null && a.tier != null) groups.getOrPut(a.groupKey) { mutableListOf() } += a else singles += a
        }
        val grouped = groups.map { (key, list) ->
            val tiers = list.sortedBy { it.tier ?: 0 }
            val earned = tiers.filter { it.earned }
            val top = earned.lastOrNull()
            val next = tiers.firstOrNull { !it.earned }
            val shown = top ?: tiers.first()
            tiers.first().sortOrder to Badge(
                key = key,
                category = shown.category,
                name = shown.name,
                description = shown.description,
                icon = iconName(shown.iconName),
                rarity = shown.rarity,
                tiers = tiers,
                earnedCount = earned.size,
                secret = false,
                progress = next?.currentProgress,
                threshold = next?.threshold,
                nextName = next?.name,
                awardedAt = top?.awardedAt,
                contextBookId = top?.contextBookId,
                contextBookTitle = top?.contextBookTitle,
            )
        }
        val single = singles.map { a ->
            a.sortOrder to Badge(
                key = a.key,
                category = a.category,
                name = a.name,
                description = a.description,
                icon = if (a.hidden && !a.earned) "Lock" else iconName(a.iconName),
                rarity = a.rarity,
                tiers = listOf(a),
                earnedCount = if (a.earned) 1 else 0,
                secret = a.hidden && !a.earned,
                progress = a.currentProgress,
                threshold = a.threshold,
                nextName = null,
                awardedAt = a.awardedAt,
                contextBookId = a.contextBookId,
                contextBookTitle = a.contextBookTitle,
            )
        }
        return (grouped + single).sortedBy { it.first }.map { it.second }
    }

    fun sections(catalogue: AchievementCatalogue): List<BadgeSection> = catalogue.categories.map { c ->
        BadgeSection(c.key, c.label.ifBlank { c.key.replaceFirstChar { it.uppercaseChar() } }, c.earnedCount, c.totalCount, badges(c.achievements))
    }

    fun matches(badge: Badge, filter: AchievementFilter): Boolean = when (filter) {
        AchievementFilter.ALL -> true
        AchievementFilter.EARNED -> badge.earned
        AchievementFilter.IN_PROGRESS -> badge.inProgress
    }

    /** Achievements earned in [year] (in [zone]), oldest first. */
    fun earnedIn(catalogue: AchievementCatalogue, year: Int, zone: ZoneId): List<Achievement> =
        catalogue.categories.flatMap { it.achievements }
            .filter { it.earned && awardedDate(it.awardedAt, zone)?.year == year }
            .sortedBy { it.awardedAt }

    fun awardedDate(iso: String?, zone: ZoneId): LocalDate? =
        iso?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
}
