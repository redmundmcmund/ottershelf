package io.github.ottershelf.feature.achievements

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.tracking.AchievementItem
import io.github.ottershelf.core.tracking.CelebrationClaim
import io.github.ottershelf.feature.achievements.model.Achievement
import io.github.ottershelf.feature.achievements.model.AchievementCatalogue
import io.github.ottershelf.feature.achievements.model.AchievementCategory
import io.github.ottershelf.feature.stats.RankedItem
import io.github.ottershelf.feature.stats.model.GenreReadingTime
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.PatternBackground
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sin

/** Achievements, the unlock toast and the year in review, dark: achievements/..._dark.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class AchievementsScreenshotTest {

    private fun tier(group: String, tier: Int, name: String, desc: String, icon: String, rarity: String, threshold: Double, progress: Double, earned: Boolean, at: String? = null, book: Pair<Long, String>? = null) =
        Achievement(
            key = "${group}_$tier", groupKey = group, tier = tier, name = name, description = desc, iconName = icon, rarity = rarity,
            threshold = threshold, sortOrder = tier, earned = earned, awardedAt = at, currentProgress = progress,
            contextBookId = book?.first, contextBookTitle = book?.second,
        )

    private val catalogue = AchievementCatalogue(
        categories = listOf(
            AchievementCategory(
                "reading", "Reading", 5, 12,
                listOf(
                    tier("books_finished", 1, "Ink Initiate", "Finish your first book", "book-open", "common", 1.0, 17.0, true, "2025-03-02T20:00:00Z", 7L to "A Study in Scarlet"),
                    tier("books_finished", 2, "Spine Scout", "Finish 10 books", "book-open", "rare", 10.0, 17.0, true, "2026-04-11T21:00:00Z", 8L to "The Lost World"),
                    tier("books_finished", 3, "Story Stalwart", "Finish 35 books", "book-open", "epic", 35.0, 17.0, false),
                    tier("books_finished", 4, "Grand Archivist", "Finish 100 books", "book-open", "legendary", 100.0, 17.0, false),
                    tier("pages", 1, "Page Turner", "Read 1,000 pages", "file-text", "common", 1000.0, 1000.0, true, "2025-05-02T20:00:00Z"),
                    tier("pages", 2, "Paper Trail", "Read 10,000 pages", "file-text", "rare", 10000.0, 1000.0, true, "2026-01-20T20:00:00Z"),
                    tier("pages", 3, "Leaf Legion", "Read 50,000 pages", "file-text", "epic", 50000.0, 12400.0, false),
                    Achievement("marathon", name = "Marathon", description = "Read for 4 hours in one day", iconName = "timer", rarity = "epic", sortOrder = 20, earned = true, awardedAt = "2026-08-15T22:00:00Z", contextBookId = 9, contextBookTitle = "Frankenstein"),
                    Achievement("night_owl", name = "Secret Achievement", description = "Keep reading to reveal this achievement.", iconName = "lock", hidden = true, sortOrder = 21),
                    Achievement("minimalist", name = "Minimalist", description = "Finish five books under 200 pages", iconName = "minimize-2", rarity = "rare", threshold = 5.0, currentProgress = 2.0, sortOrder = 22),
                ),
            ),
            AchievementCategory(
                "dedication", "Dedication", 1, 6,
                listOf(
                    tier("streak", 1, "Kindling", "Read 7 days in a row", "flame", "common", 7.0, 21.0, true, "2026-02-09T20:00:00Z"),
                    tier("streak", 2, "Hearth", "Read 30 days in a row", "flame", "rare", 30.0, 21.0, false),
                    tier("streak", 3, "Eternal Flame", "Read 100 days in a row", "flame", "legendary", 100.0, 21.0, false),
                    Achievement("sunrise", name = "Dawn Reader", description = "Read before 6 in the morning", iconName = "sunrise", rarity = "legendary", sortOrder = 10, earned = true, awardedAt = "2026-06-21T05:00:00Z"),
                ),
            ),
        ),
        totalEarned = 8,
        totalAvailable = 18,
    )

    @Test
    fun list() = captureDark("achievements/list", heightDp = 2300) {
        Frame { padding ->
            AchievementsContent(
                AchievementsUiState(loading = false, sections = AchievementLogic.sections(catalogue), totalEarned = 8, totalAvailable = 18),
                contentPadding = padding,
            )
        }
    }

    @Test
    fun celebration() = captureDark("achievements/celebration", heightDp = PHONE_HEIGHT_DP) {
        PatternBackground(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    AchievementItem("spine", name = "Spine Scout", description = "Finish 10 books", iconName = "book-open", rarity = "rare"),
                    AchievementItem("sunrise", name = "Dawn Reader", description = "Read before 6 in the morning", iconName = "sunrise", rarity = "legendary"),
                    AchievementItem("marathon", name = "Marathon", description = "Read for 4 hours in one day", iconName = "timer", rarity = "epic"),
                    AchievementItem("first", name = "Ink Initiate", description = "Finish your first book", iconName = "book-open", rarity = "common"),
                ).forEach { a ->
                    CelebrationToast(CelebrationClaim("c-${a.key}", achievement = a), onOpen = {}, onDismiss = {}, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }

    private val year = 2026
    private val rewind: RewindData = run {
        val start = LocalDate.of(year, 1, 1)
        val dayList = (0 until 268).map { start.plusDays(it.toLong()) }.map { d ->
            val n = d.toEpochDay()
            val s = if (n % 6 == 2L || n % 13 == 0L) 0L else (1500 + 1400 * abs(sin(n * 0.37))).toLong() + if (d.dayOfWeek.value >= 6) 1800 else 0
            d to s
        }
        val titles = listOf(
            "A Study in Scarlet" to "Arthur Conan Doyle", "The Lost World" to "Arthur Conan Doyle", "Frankenstein" to "Mary Shelley",
            "The Warden" to "Anthony Trollope", "Barchester Towers" to "Anthony Trollope", "The White Company" to "Arthur Conan Doyle",
            "Doctor Thorne" to "Anthony Trollope", "Cranford" to "Elizabeth Gaskell", "The Woman in White" to "Wilkie Collins",
            "Far from the Madding Crowd" to "Thomas Hardy", "The Last Man" to "Mary Shelley", "Framley Parsonage" to "Anthony Trollope",
            "The Memoirs of Sherlock Holmes" to "Arthur Conan Doyle", "Jude the Obscure" to "Thomas Hardy", "Ivanhoe" to "Walter Scott",
        )
        val books = titles.mapIndexed { i, (t, a) -> RewindBook(BookCard(i + 1L, t, listOf(a)), fakeCover(i), daysTaken = 3 + i * 2, seconds = 3600L * (4 + i % 5)) }
        RewindData(
            year = year,
            availableYears = listOf(2024, 2025, 2026),
            totalSeconds = dayList.sumOf { it.second }.toDouble(),
            daysRead = dayList.count { it.second > 0 },
            sessions = 412,
            monthlySeconds = RewindMath.monthlySeconds(dayList),
            streak = RewindMath.longestStreak(dayList),
            bestDay = RewindMath.bestDay(dayList),
            allTimeStreak = 34,
            finished = books,
            completionsByMonth = listOf(2.0, 1.0, 2.0, 1.0, 3.0, 1.0, 2.0, 2.0, 1.0, 0.0, 0.0, 0.0),
            peakHours = List(24) { h -> if (h in 7..8) 9000.0 else if (h in 19..23) 30000.0 - abs(21 - h) * 7000 else if (h in 12..13) 5000.0 else 0.0 },
            genres = listOf("Fantasy" to 62.0, "Literary fiction" to 30.0, "Science fiction" to 21.0, "Historical" to 9.0).map { GenreReadingTime(it.first, it.second * 3600) },
            authors = RewindMath.topAuthors(books.map { it.book }),
            badges = catalogue.categories.flatMap { it.achievements }.filter { it.earned && it.awardedAt?.startsWith("2026") == true },
        )
    }

    @Test
    fun rewind() = captureDark("achievements/rewind", heightDp = PHONE_HEIGHT_DP) {
        PatternBackground(Modifier.fillMaxSize()) { RewindContent(RewindUiState(year, loading = false, data = rewind), initialPage = 2) }
    }

    /** Every card of the story stacked, to check them all in one image. */
    @Test
    fun rewindCards() {
        val pages = rewind.pages()
        captureDark("achievements/rewind_cards", heightDp = pages.size * 720) {
            PatternBackground(Modifier.fillMaxSize()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    pages.forEach { RewindCard(it, rewind, Modifier.fillMaxWidth().height(708.dp)) }
                }
            }
        }
    }

    @Composable
    private fun Frame(content: @Composable (PaddingValues) -> Unit) {
        RootFrame(
            title = "Achievements",
            search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
            onOpenDrawer = {},
            onOpenSearch = {},
            onSubmitSearch = {},
            onCloseSearch = {},
            content = content,
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDark(name: String, heightDp: Int, content: @Composable () -> Unit) = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = heightDp)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                WithFakeCovers(content)
            }
        }
    }
}
