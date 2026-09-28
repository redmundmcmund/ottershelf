package io.github.ottershelf.feature.library

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.ottershelf.ui.nav.RootTopBar
import io.github.ottershelf.ui.nav.SearchBarState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.model.AuthorSummary
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.SeriesSummary
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library lists in dark theme at phone size: an author's books (the pushed book grid with its
 * toolbar), Authors and Series. Record with
 * `./gradlew recordRoborazziDebug --tests "*LibraryScreenshotTest"`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class LibraryScreenshotTest {

    @Test
    fun bookGrid() = captureDark("library/books") {
        WithFakeCovers {
            Scaffold(topBar = { DetailTopBar(title = "Arthur Conan Doyle", subtitle = "Author", onBack = {}) }) { padding ->
                BookGridContent(
                    state = PagedState(items = sampleBooks, total = 40),
                    coverOf = { if (it.hasCover) fakeCover(it.id.toInt()) else null },
                    contentPadding = padding,
                )
            }
        }
    }

    @Test
    fun authors() = captureDark("library/authors") {
        WithFakeCovers {
            AuthorsContent(
                state = PagedState(items = sampleAuthors, total = 80),
                portraitOf = { if (it.imageUrl != null) fakeCover(it.id.toInt()) else null },
            )
        }
    }

    @Test
    fun series() = captureDark("library/series") {
        WithFakeCovers {
            SeriesContent(
                state = PagedState(items = sampleSeries, total = 20),
                coverOf = { fakeCover(it.toInt()) },
            )
        }
    }

    /** All books sorted by read status with Hide read on: the toolbar's action, the chip row, the grid. */
    @Test
    fun sortedGrid() = captureDark("library/books_sorted") {
        WithFakeCovers {
            val sort = ListSort(SortField.READ_STATUS, hideRead = true)
            val order = STATUS_SEGMENTS.flatten()
            val books = sampleBooks
                .filter { sort.shows(it.readStatus?.status) }
                .sortedBy { order.indexOf(ListSort.statusOf(it.readStatus?.status)) }
            Scaffold(
                topBar = {
                    RootTopBar(
                        title = "All books",
                        search = SearchBarState(mutableStateOf(false), mutableStateOf("")),
                        onOpenDrawer = {},
                        onOpenSearch = {},
                        onSubmitSearch = {},
                        onCloseSearch = {},
                        actions = { SortButton(active = true, onClick = {}) },
                    )
                },
            ) { padding ->
                SortableList(sort = sort, kind = ListKind.QUERY, padding = padding, onOpenSort = {}, onSort = {}) { gridPadding ->
                    BookGridContent(
                        state = PagedState(items = books, total = books.size),
                        coverOf = { if (it.hasCover) fakeCover(it.id.toInt()) else null },
                        contentPadding = gridPadding,
                        filters = sort,
                    )
                }
            }
        }
    }

    /** The sort and filter sheet over the grid (Author, Z to A, Hide unread). */
    @Test
    fun sortSheet() = captureDark("library/sort_sheet") {
        WithFakeCovers {
            Box(Modifier.fillMaxSize()) {
                BookGridContent(state = PagedState(items = sampleBooks, total = 40), coverOf = { if (it.hasCover) fakeCover(it.id.toInt()) else null })
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = OttershelfTheme.colors.card,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    ) {
                        SortSheetContent(
                            sort = ListSort(SortField.AUTHOR, descending = true, hideUnread = true),
                            kind = ListKind.QUERY,
                            onChange = {},
                            onDone = {},
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }
            }
        }
    }

    /** Hide read and Hide unread both on, and nothing left: the explanation and Clear filters. */
    @Test
    fun filteredEmpty() = captureDark("library/books_filtered_empty") {
        WithFakeCovers {
            val sort = ListSort(hideRead = true, hideUnread = true)
            Scaffold(topBar = { DetailTopBar(title = "Arthur Conan Doyle", subtitle = "Author", onBack = {}, actions = { SortButton(active = true, onClick = {}) }) }) { padding ->
                SortableList(sort = sort, kind = ListKind.AUTHOR, padding = padding, onOpenSort = {}, onSort = {}) { gridPadding ->
                    BookGridContent(
                        state = PagedState(items = emptyList(), total = 0),
                        coverOf = { null },
                        contentPadding = gridPadding,
                        filters = sort,
                    )
                }
            }
        }
    }
}

/** Dark theme only, at phone size (the brief asks for one image per screen). */
@OptIn(ExperimentalRoborazziApi::class)
internal fun captureDark(name: String, content: @Composable () -> Unit) {
    captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

private val sampleBooks = listOf(
    BookCard(1, "A Study in Scarlet", listOf("Arthur Conan Doyle"), "Sherlock Holmes", "1", 100.0, ReadStatusInfo("read"), hasCover = true),
    BookCard(2, "The Sign of the Four", listOf("Arthur Conan Doyle"), "Sherlock Holmes", "2", 42.0, ReadStatusInfo("reading"), hasCover = true),
    BookCard(3, "The Adventures of Sherlock Holmes", listOf("Arthur Conan Doyle"), "Sherlock Holmes", "3", null, ReadStatusInfo("want_to_read"), hasCover = true),
    BookCard(4, "The Memoirs of Sherlock Holmes", listOf("Arthur Conan Doyle"), "Sherlock Holmes", "4", null, null, hasCover = false),
    BookCard(5, "The Lost World", listOf("Arthur Conan Doyle"), null, null, 12.0, ReadStatusInfo("on_hold"), hasCover = true),
    BookCard(6, "The White Company", listOf("Arthur Conan Doyle"), null, null, null, null, hasCover = true),
    BookCard(7, "The Stark Munro Letters", listOf("Arthur Conan Doyle"), null, null, null, ReadStatusInfo("abandoned"), hasCover = true),
    BookCard(8, "Round the Red Lamp", listOf("Arthur Conan Doyle"), null, null, null, null, hasCover = false),
    BookCard(9, "Sir Nigel", listOf("Arthur Conan Doyle"), null, null, 88.0, ReadStatusInfo("rereading"), hasCover = true),
    BookCard(10, "The Tragedy of the Korosko", listOf("Arthur Conan Doyle"), null, null, null, null, hasCover = true),
    BookCard(11, "Tales of Terror and Mystery", listOf("Arthur Conan Doyle"), null, null, null, ReadStatusInfo("skimmed"), hasCover = true),
    BookCard(12, "Rodney Stone", listOf("Arthur Conan Doyle"), null, null, null, null, hasCover = true),
)

private val sampleAuthors = listOf(
    AuthorSummary(1, "Arthur Conan Doyle", imageUrl = "/a/1", bookCount = 23),
    AuthorSummary(2, "Anthony Trollope", imageUrl = null, bookCount = 41),
    AuthorSummary(3, "L. M. Montgomery", imageUrl = "/a/3", bookCount = 9),
    AuthorSummary(4, "Edgar A. Poe", imageUrl = null, bookCount = 12),
    AuthorSummary(5, "Louisa M. Alcott", imageUrl = null, bookCount = 1),
    AuthorSummary(6, "Jules Verne", imageUrl = "/a/6", bookCount = 7),
    AuthorSummary(7, "Mary Shelley", imageUrl = null, bookCount = 2),
    AuthorSummary(8, "Alexandre Dumas", imageUrl = null, bookCount = 18),
    AuthorSummary(9, "Anna Sewell", imageUrl = "/a/9", bookCount = 6),
    AuthorSummary(10, "Oscar Wilde", imageUrl = null, bookCount = 2),
    AuthorSummary(11, "China Miéville", imageUrl = null, bookCount = 11),
    AuthorSummary(12, "Rudyard Kipling", imageUrl = null, bookCount = 8),
)

private val sampleSeries = listOf(
    SeriesSummary(1, "Sherlock Holmes", bookCount = 6, readCount = 2, authors = listOf("Arthur Conan Doyle"), coverBookIds = listOf(1, 2, 3)),
    SeriesSummary(2, "Barsetshire", bookCount = 6, readCount = 6, authors = listOf("Anthony Trollope"), coverBookIds = listOf(4, 5, 0)),
    SeriesSummary(3, "Anne of Green Gables", bookCount = 3, readCount = 0, authors = listOf("L. M. Montgomery"), coverBookIds = listOf(2, 3)),
    SeriesSummary(4, "Dupin Tales", bookCount = 3, readCount = 0, authors = listOf("Edgar A. Poe"), coverBookIds = listOf(5)),
    SeriesSummary(5, "The Jungle Books", bookCount = 7, readCount = 3, authors = listOf("Rudyard Kipling"), coverBookIds = listOf(1, 3, 5)),
    SeriesSummary(6, "Anthology", bookCount = 1, readCount = 0, authors = listOf("A", "B", "C", "D"), coverBookIds = emptyList()),
)
