package io.github.ottershelf.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.download.DownloadedBook
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.feature.book.BookDetailUiState
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.nav.RootTopBar
import io.github.ottershelf.ui.nav.SearchBarState
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The book grids' views, light and dark at phone size: the grid at two and five columns, the list,
 * the view menu, and the quick view. Record with
 * `./gradlew recordRoborazziDebug --tests "*LibraryViewScreenshotTest"`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class LibraryViewScreenshotTest {

    /** The phone's grid width: 411dp less the grid's 6dp each side. */
    private val width = 399f

    @Test
    fun gridTwoColumns() = captureLightAndDark("library/view_grid_2") {
        AllBooks(ListView(cellDp = GridColumns.cellFor(width, 2)))
    }

    @Test
    fun gridFiveColumns() = captureLightAndDark("library/view_grid_5") {
        AllBooks(ListView(cellDp = GridColumns.cellFor(width, 5)))
    }

    @Test
    fun list() = captureLightAndDark("library/view_list") {
        AllBooks(ListView(ViewMode.LIST))
    }

    @Test
    fun listLandscape() = captureLightAndDark("library/view_list_land", widthDp = 891, heightDp = 411) {
        AllBooks(ListView(ViewMode.LIST))
    }

    /** The toolbar's view menu, open (drawn where the dropdown opens, under the action). */
    @Test
    fun viewMenu() = captureLightAndDark("library/view_menu") {
        WithFakeCovers {
            Box(Modifier.fillMaxSize()) {
                AllBooks(ListView())
                val colors = OttershelfTheme.colors
                val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
                Surface(
                    Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 56.dp).border(1.dp, colors.border, shape),
                    color = colors.popover,
                    shape = shape,
                    shadowElevation = 3.dp,
                ) {
                    ViewMenuContent(ListView(), width, onChange = {}, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }

    /** A long press on a book the user is reading: the quick view over the grid. */
    @Test
    fun quickView() = captureLightAndDark("library/quick_view") {
        Sheet { BookQuickViewContent(readingState, Modifier.fillMaxWidth().padding(top = 24.dp)) }
    }

    /** A downloaded PDF with the statuses open. */
    @Test
    fun quickViewStatuses() = captureLightAndDark("library/quick_view_statuses") {
        Sheet { BookQuickViewContent(pdfState, Modifier.fillMaxWidth().padding(top = 24.dp), statusesOpen = true) }
    }

    /** A finished book: the button says Open, so it doesn't read "Read" beside the status button. */
    @Test
    fun quickViewFinished() = captureLightAndDark("library/quick_view_finished") {
        Sheet { BookQuickViewContent(finishedState, Modifier.fillMaxWidth().padding(top = 24.dp)) }
    }

    /** Offline, the book not on the phone: the card's details, the book page and Retry. */
    @Test
    fun quickViewFailed() = captureLightAndDark("library/quick_view_failed") {
        Sheet { BookQuickViewContent(failedState, Modifier.fillMaxWidth().padding(top = 24.dp)) }
    }

    // --- scenes --------------------------------------------------------------------------------

    @Composable
    private fun AllBooks(view: ListView) {
        WithFakeCovers {
            val metrics = remember { GridMetrics() }
            Scaffold(
                topBar = {
                    RootTopBar(
                        title = "All books",
                        search = SearchBarState(mutableStateOf(false), mutableStateOf("")),
                        onOpenDrawer = {},
                        onOpenSearch = {},
                        onSubmitSearch = {},
                        onCloseSearch = {},
                        actions = {
                            ViewMenuButton(view, metrics, onChange = {})
                            SortButton(active = false, onClick = {})
                        },
                    )
                },
            ) { padding ->
                BookGridContent(
                    state = PagedState(items = books, total = 240),
                    coverOf = { if (it.hasCover) fakeCover(it.id.toInt()) else null },
                    contentPadding = PaddingValues(top = padding.calculateTopPadding()),
                    view = view,
                    metrics = metrics,
                )
            }
        }
    }

    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        WithFakeCovers {
            Box(Modifier.fillMaxSize()) {
                AllBooks(ListView())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                Surface(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    color = OttershelfTheme.colors.card,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                ) { content() }
            }
        }
    }

    // --- data ----------------------------------------------------------------------------------

    private val epub = listOf(BookFile(0, "epub", role = "primary"))

    private val books = listOf(
        card(1, "A Study in Scarlet", "Arthur Conan Doyle", "Sherlock Holmes", "1", 100.0, "read", rating = 5),
        card(2, "The Sign of the Four", "Arthur Conan Doyle", "Sherlock Holmes", "2", 42.0, "reading", rating = 4),
        card(3, "The Adventures of Sherlock Holmes", "Arthur Conan Doyle", "Sherlock Holmes", "3", null, "want_to_read"),
        card(4, "The Memoirs of Sherlock Holmes", "Arthur Conan Doyle", "Sherlock Holmes", "4", null, null, cover = false),
        card(5, "The Lost World", "Arthur Conan Doyle", null, null, 12.0, "on_hold", files = listOf(BookFile(0, "pdf", role = "primary"), BookFile(1, "epub"))),
        card(6, "The White Company", "Arthur Conan Doyle", null, null, null, null),
        card(7, "The Stark Munro Letters", "Arthur Conan Doyle", null, null, 8.0, "abandoned", rating = 2),
        card(8, "Round the Red Lamp", "Arthur Conan Doyle", null, null, null, null, cover = false),
        card(9, "Little Nemo, Volume One", "Winsor McCay", "Little Nemo", "1", 88.0, "rereading", files = listOf(BookFile(0, "cbz", role = "primary"))),
        card(10, "The Tragedy of the Korosko", "Arthur Conan Doyle", null, null, null, null),
        card(11, "Tales of Terror and Mystery", "Arthur Conan Doyle", null, null, null, "skimmed", rating = 3),
        card(12, "Rodney Stone", "Arthur Conan Doyle", null, null, null, null),
        card(13, "Sir Nigel", "Arthur Conan Doyle", null, null, 64.0, "reading"),
        card(14, "Beyond the City", "Arthur Conan Doyle", null, null, null, null),
        card(15, "The Exploits of Brigadier Gerard", "Arthur Conan Doyle", "Brigadier Gerard", "1", null, "want_to_read"),
        card(16, "The Adventures of Gerard", "Arthur Conan Doyle", "Brigadier Gerard", "2", null, null),
        card(17, "The Last Galley", "Arthur Conan Doyle", null, null, null, null, cover = false),
        card(18, "The Return of Sherlock Holmes", "Arthur Conan Doyle", "Sherlock Holmes", "6", null, null),
        card(19, "The Hound of the Baskervilles", "Arthur Conan Doyle", "Sherlock Holmes", "5", null, "read", rating = 4),
        card(20, "The Poison Belt", "Arthur Conan Doyle", "Professor Challenger", "1", null, null),
        card(21, "The Land of Mist", "Arthur Conan Doyle", "Professor Challenger", "2", null, null),
        card(22, "The Disintegration Machine", "Arthur Conan Doyle", "Professor Challenger", "3", null, null),
        card(23, "The Firm of Girdlestone", "Arthur Conan Doyle", null, null, null, null),
        card(24, "Micah Clarke", "Arthur Conan Doyle", null, null, null, null),
        card(25, "The Parasite", "Arthur Conan Doyle", null, null, null, null),
        card(26, "The Captain of the Pole-Star", "Arthur Conan Doyle", null, null, null, null),
        card(27, "The Green Flag", "Arthur Conan Doyle", null, null, null, null),
        card(28, "Uncle Bernac", "Arthur Conan Doyle", null, null, null, null),
        card(29, "Through the Magic Door", "Arthur Conan Doyle", null, null, null, null),
        card(30, "Danger! and Other Stories", "Arthur Conan Doyle", null, null, null, null),
    )

    private fun card(
        id: Long,
        title: String,
        author: String,
        series: String?,
        index: String?,
        progress: Double?,
        status: String?,
        rating: Int? = null,
        cover: Boolean = true,
        files: List<BookFile> = epub,
    ) = BookCard(
        id = id,
        title = title,
        authors = author.split(", "),
        seriesName = series,
        seriesIndex = index,
        readingProgress = progress,
        readStatus = status?.let { ReadStatusInfo(it) },
        hasCover = cover,
        files = files,
        rating = rating,
    )

    private val readingCard = books[1]

    private val readingState = QuickViewUiState(
        card = readingCard,
        cover = fakeCover(2),
        page = BookDetailUiState(
            bookId = 2,
            preview = readingCard,
            book = BookDetail(
                id = 2,
                title = "The Sign of the Four",
                authors = listOf(AuthorRef(1, "Arthur Conan Doyle")),
                seriesName = "Sherlock Holmes",
                seriesIndex = "2",
                rating = 4,
                description = "<p>Every year for six years, Miss Mary Morstan has been sent a single large pearl by a friend who never gives " +
                    "a name. Now a letter asks her to meet that friend outside the Lyceum Theatre, and she brings Sherlock Holmes " +
                    "and Dr Watson with her.</p><p>What they find at the end of that night's drive through London is a locked room, " +
                    "a dead man, and a treasure that has come all the way from India.</p>",
                files = listOf(BookFile(21, "epub", role = "primary", sizeBytes = 412_000)),
            ),
            loading = false,
            loaded = true,
            status = "reading",
            readFile = BookFile(21, "epub", role = "primary", sizeBytes = 412_000),
            offlineFile = BookFile(21, "epub", role = "primary", sizeBytes = 412_000),
        ),
    )

    private val finishedState = readingState.copy(
        card = readingCard.copy(readingProgress = 100.0, readStatus = ReadStatusInfo("read")),
        page = readingState.page.copy(status = "read"),
    )

    private val pdfCard = books[4]

    private val pdfState = QuickViewUiState(
        card = pdfCard,
        cover = fakeCover(5),
        page = BookDetailUiState(
            bookId = 5,
            preview = pdfCard,
            book = BookDetail(
                id = 5,
                title = "The Lost World",
                authors = listOf(AuthorRef(1, "Arthur Conan Doyle")),
                description = "<p>A young reporter joins the irascible Professor Challenger on a journey to a plateau in South America — where, it is said, dinosaurs still live.</p>",
                files = listOf(BookFile(51, "pdf", role = "primary", sizeBytes = 2_400_000)),
            ),
            loading = false,
            loaded = true,
            status = "on_hold",
            readFile = BookFile(51, "pdf", role = "primary", sizeBytes = 2_400_000),
            offlineFile = BookFile(51, "pdf", role = "primary", sizeBytes = 2_400_000),
            downloaded = DownloadedBook(bookId = 5, fileId = 51, title = "The Lost World", sizeBytes = 2_400_000, format = "pdf"),
        ),
    )

    private val failedState = QuickViewUiState(
        card = books[5],
        cover = fakeCover(6),
        page = BookDetailUiState(bookId = 6, preview = books[5], status = null),
        loadFailed = true,
    )
}
