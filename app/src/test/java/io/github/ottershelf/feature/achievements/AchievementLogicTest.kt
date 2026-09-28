package io.github.ottershelf.feature.achievements

import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.theme.Accent
import io.github.ottershelf.core.theme.ThemePrefs
import io.github.ottershelf.feature.achievements.model.AchievementCatalogue
import io.github.ottershelf.feature.achievements.model.FilteredBookQuery
import io.github.ottershelf.feature.achievements.model.QueryGroup
import io.github.ottershelf.feature.achievements.model.QueryPage
import io.github.ottershelf.feature.achievements.model.QueryRule
import io.github.ottershelf.feature.achievements.model.QuerySort
import io.github.ottershelf.feature.stats.ChartPalette
import io.github.ottershelf.ui.theme.ottershelfColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AchievementLogicTest {

    /** A trimmed `GET achievements` answer in the server's shape (achievement.service.ts toItem). */
    private val json = """
        {"categories":[
          {"key":"reading","label":"Reading","earnedCount":2,"totalCount":5,"achievements":[
            {"key":"books_finished_1","groupKey":"books_finished","tier":1,"category":"reading","name":"Ink Initiate","description":"Finish your first book","iconName":"book-open","rarity":"common","threshold":1,"hidden":false,"sortOrder":1,"earned":true,"awardedAt":"2026-02-03T20:00:00.000Z","context":{"bookId":7,"bookTitle":"Dracula"},"contextBookId":7,"contextBookTitle":"Dracula","currentProgress":12},
            {"key":"books_finished_2","groupKey":"books_finished","tier":2,"category":"reading","name":"Spine Scout","description":"Finish 10 books","iconName":"book-open","rarity":"rare","threshold":10,"hidden":false,"sortOrder":2,"earned":true,"awardedAt":"2025-11-03T20:00:00.000Z","context":null,"currentProgress":12},
            {"key":"books_finished_3","groupKey":"books_finished","tier":3,"category":"reading","name":"Story Stalwart","description":"Finish 35 books","iconName":"book-open","rarity":"epic","threshold":35,"hidden":false,"sortOrder":3,"earned":false,"awardedAt":null,"context":null,"currentProgress":12},
            {"key":"night_owl","groupKey":null,"tier":null,"category":"reading","name":"Secret Achievement","description":"Keep reading to reveal this achievement.","iconName":"lock","rarity":"common","threshold":null,"hidden":true,"sortOrder":9,"earned":false,"awardedAt":null,"context":null,"contextBookId":null,"contextBookTitle":null,"currentProgress":null},
            {"key":"minimalist","groupKey":null,"tier":null,"category":"reading","name":"Minimalist","description":"Read 5 short books","iconName":"minimize-2","rarity":"rare","threshold":5,"hidden":false,"sortOrder":5,"earned":false,"awardedAt":null,"context":null,"currentProgress":2}
          ]},
          {"key":"devices","label":"Devices","earnedCount":0,"totalCount":1,"achievements":[
            {"key":"phone","groupKey":null,"tier":null,"category":"devices","name":"Pocket Reader","description":"Read on a phone","iconName":"smartphone","rarity":"common","threshold":null,"hidden":false,"sortOrder":1,"earned":false,"awardedAt":null,"context":null,"currentProgress":null}
          ]}
        ],"totalEarned":2,"totalAvailable":6}
    """.trimIndent()

    private val catalogue = ApiJson.decodeFromString(AchievementCatalogue.serializer(), json)

    @Test
    fun iconNamesBecomePascalCase() {
        assertEquals("BookOpen", AchievementLogic.iconName("book-open"))
        assertEquals("Minimize2", AchievementLogic.iconName("minimize-2"))
        assertEquals("Lock", AchievementLogic.iconName("lock"))
        assertEquals("Trophy", AchievementLogic.iconName("Trophy"))
        assertEquals("Trophy", AchievementLogic.iconName(null))
    }

    @Test
    fun tiersGroupIntoOneBadgeShownAsTheHighestEarned() {
        val badges = AchievementLogic.badges(catalogue.categories[0].achievements)
        assertEquals(listOf("books_finished", "minimalist", "night_owl"), badges.map { it.key })
        val tiered = badges[0]
        assertTrue(tiered.tiered)
        assertEquals("Spine Scout", tiered.name)
        assertEquals("rare", tiered.rarity)
        assertEquals(2, tiered.earnedCount)
        assertEquals(35.0, tiered.threshold!!, 0.0)
        assertEquals("Story Stalwart", tiered.nextName)
        assertEquals(12f / 35f, tiered.fraction!!, 0.001f)
        assertTrue(tiered.inProgress)
        assertFalse(tiered.complete)
    }

    /** The phone check: "20 of 87 earned" over "Earned 14" read as a disagreement. */
    @Test
    fun theHeaderCountsTiersAndTheChipsBadges() {
        val state = AchievementsUiState(
            loading = false,
            sections = AchievementLogic.sections(catalogue),
            totalEarned = catalogue.totalEarned,
            totalAvailable = catalogue.totalAvailable,
        )
        // Tiers (the server's): Ink Initiate and Spine Scout, of six.
        assertEquals(2, state.totalEarned)
        assertEquals(6, state.totalAvailable)
        // Badges: the books-finished badge is one, whatever its tiers.
        assertEquals(1, state.earnedBadges)
        assertEquals(4, state.totalBadges)
        assertEquals(2, state.inProgressBadges)
    }

    @Test
    fun secretsAreLockedAndNeverInProgress() {
        val secret = AchievementLogic.badges(catalogue.categories[0].achievements).first { it.key == "night_owl" }
        assertTrue(secret.secret)
        assertEquals("Lock", secret.icon)
        assertFalse(secret.inProgress)
        assertNull(secret.fraction)
    }

    @Test
    fun filters() {
        val all = AchievementLogic.sections(catalogue).flatMap { it.badges }
        assertEquals(4, all.size)
        assertEquals(listOf("books_finished"), all.filter { AchievementLogic.matches(it, AchievementFilter.EARNED) }.map { it.key })
        assertEquals(listOf("books_finished", "minimalist"), all.filter { AchievementLogic.matches(it, AchievementFilter.IN_PROGRESS) }.map { it.key })
    }

    @Test
    fun earnedInAYear() {
        val earned = AchievementLogic.earnedIn(catalogue, 2026, ZoneOffset.UTC)
        assertEquals(listOf("books_finished_1"), earned.map { it.key })
    }

    @Test
    fun finishedBooksQueryMatchesTheServerSchema() {
        val body = FilteredBookQuery(
            filter = QueryGroup(rules = listOf(QueryRule.between("finishedAt", "2026-01-01", "2026-12-31"))),
            sort = listOf(QuerySort("finishedAt", "asc")),
            pagination = QueryPage(0, 200),
        )
        assertEquals(
            """{"filter":{"type":"group","join":"AND","rules":[{"type":"rule","field":"finishedAt","operator":"between","value":"2026-01-01","valueTo":"2026-12-31"}]},"sort":[{"field":"finishedAt","dir":"asc"}],"pagination":{"page":0,"size":200}}""",
            ApiJson.encodeToString(FilteredBookQuery.serializer(), body),
        )
        assertEquals(body, ApiAchievementsRemote.finishedInQuery(2026))
    }

    @Test
    fun finishCandidatesQueryMatchesTheServerSchema() {
        // finishedAt takes "after" with a date key; readStatus takes "includesAny" with a list.
        assertEquals(
            """{"filter":{"type":"group","join":"OR","rules":[{"type":"rule","field":"finishedAt","operator":"after","value":"2025-12-31"},""" +
                """{"type":"rule","field":"readStatus","operator":"includesAny","value":["reading","rereading","on_hold","abandoned","skimmed"]}]},""" +
                """"sort":[],"pagination":{"page":2,"size":200}}""",
            ApiJson.encodeToString(FilteredBookQuery.serializer(), ApiAchievementsRemote.finishCandidatesQuery(2025, 2)),
        )
    }

    /**
     * The dataviz validator's gates on the rarity colours, per mode, against the card of every
     * accent: every pair clears normal-vision Delta E 15 and CVD Delta E 8, and each colour clears
     * 3:1 on the card (they tint icons and rings).
     */
    @Test
    fun rarityPalettePassesTheValidator() {
        for (dark in listOf(false, true)) {
            val palette = RarityPalette.colors(dark)
            for (i in palette.indices) for (j in i + 1 until palette.size) {
                val normal = ChartPalette.deltaE(palette[i], palette[j])
                val cvd = ChartPalette.cvdDeltaE(palette[i], palette[j])
                assertTrue("dark=$dark $i-$j normal $normal", normal >= ChartPalette.NORMAL_FLOOR)
                assertTrue("dark=$dark $i-$j cvd $cvd", cvd >= ChartPalette.CVD_TARGET)
            }
            for (accent in Accent.entries) {
                val card = ottershelfColors(ThemePrefs(accent = accent), dark).card
                palette.forEachIndexed { i, c ->
                    val contrast = ChartPalette.contrast(c, card)
                    assertTrue("dark=$dark $accent rarity $i contrast $contrast", contrast >= 3.0)
                }
            }
        }
    }
}
