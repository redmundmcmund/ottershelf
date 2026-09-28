package io.github.ottershelf.feature.notes

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookAnnotationStats
import io.github.ottershelf.feature.notes.model.ChapterStat
import io.github.ottershelf.feature.notes.model.ColorCount
import io.github.ottershelf.feature.notes.model.HubOverview
import io.github.ottershelf.feature.notes.model.NoteFilter
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Highlights and notes, dark: notes/..._dark.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class NotesScreenshotTest {

    private val notes = listOf(
        Annotation(
            id = 1, bookId = 1, cfi = "epubcfi(/6/4!/4/2,/1:0,/1:40)", jumpFileId = 11, jumpFileFormat = "epub",
            text = "Call me Ishmael. Some years ago, never mind how long precisely, having little or no money in my purse, I thought I would sail about a little and see the watery part of the world.",
            color = "#FACC15", note = "The best opening line. Compare with Dickens.", chapterTitle = "Loomings",
            createdAt = "2026-09-20T10:00:00Z", bookTitle = "Moby-Dick", author = "Herman Melville",
        ),
        Annotation(
            id = 2, bookId = 1, cfi = "epubcfi(/6/4!/4/8,/1:0,/1:20)", jumpFileId = 11, jumpFileFormat = "epub",
            text = "It is not down in any map; true places never are.",
            color = "#38BDF8", style = "underline", chapterTitle = "The Spouter-Inn",
            createdAt = "2026-09-18T10:00:00Z", bookTitle = "Moby-Dick", author = "Herman Melville",
        ),
        Annotation(
            id = 3, bookId = 2, text = "Whatever our souls are made of, his and mine are the same.",
            color = "#F472B6", origin = "koreader", note = "Cathy about Heathcliff",
            chapterTitle = "Chapter IX", createdAt = "2026-09-12T10:00:00Z", bookTitle = "Wuthering Heights", author = "Emily Brontë",
        ),
        Annotation(
            id = 4, bookId = 3, text = "So we beat on, boats against the current, borne back ceaselessly into the past.",
            color = "#4ADE80", chapterTitle = "IX", createdAt = "2026-09-02T10:00:00Z", bookTitle = "The Great Gatsby", author = "F. Scott Fitzgerald",
        ),
    )

    private val bookStats = BookAnnotationStats(
        totalHighlights = 34,
        colorBreakdown = listOf(ColorCount("#FACC15", 18), ColorCount("#38BDF8", 9), ColorCount("#F472B6", 5), ColorCount("#4ADE80", 2)),
        chaptersWithHighlights = 9,
        highlightsWithNotes = 12,
        chapterBreakdown = listOf(ChapterStat("Loomings", 6), ChapterStat("The Spouter-Inn", 4)),
    )

    private val cover: (Long) -> Any? = { fakeCover(it.toInt()) }

    @Test
    fun feed() = captureDark("notes/feed") {
        NotesContent(
            state = NotesUiState(
                filter = NoteFilter(hasNote = false),
                items = notes,
                total = 412, withNotes = 96, books = 38,
                overview = HubOverview(412, 38, 96, bookStats.colorBreakdown),
                liked = setOf(3L),
                photos = setOf(1L),
                loading = false,
            ),
            coverOf = cover,
            actions = HighlightActions(onOpenReader = {}, onLike = {}, onShareImage = {}, onOpenBook = {}, onViewPhoto = {}),
        )
    }

    @Test
    fun bookHighlights() = captureDark("notes/book_highlights") {
        BookHighlightsContent(
            state = BookHighlightsUiState(
                bookId = 1, title = "Moby-Dick", author = "Herman Melville", readerFiles = mapOf(11L to "epub"),
                loading = false, items = notes.take(2), stats = bookStats, liked = setOf(1L), endReached = true,
            ),
            actions = HighlightActions(onOpenReader = {}, onLike = {}, onShareImage = {}, onSaveNote = { _, _ -> }, onColor = { _, _ -> }, onStyle = { _, _ -> }, onDelete = {}),
        )
    }

    @Test
    fun bookSection() = captureDark("notes/book_section", heightDp = 520) {
        HighlightsSummaryCard(
            HighlightsSummary(loading = false, stats = bookStats, latest = notes.take(2)),
            onOpen = {}, onRetry = {},
            modifier = Modifier.padding(20.dp),
        )
    }

    @Test
    fun memorize() = captureDark("notes/memorize") {
        MemorizeContent(
            state = MemorizeUiState(loading = false, current = notes[0], count = 412, session = 7, reviews = mapOf(1L to 3), likedIds = setOf(1L)),
            coverOf = cover,
        )
    }

    @Test
    fun quoteCard() = captureDark("notes/quote_card") {
        QuoteCardContent(
            note = notes[0], title = "Moby-Dick", author = "Herman Melville", cover = fakeCover(1),
            art = CoverArt(tint = Color(0xFF3B82F6)), layer = null,
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDark(name: String, heightDp: Int = PHONE_HEIGHT_DP, content: @Composable () -> Unit) = captureRoboImage(
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
