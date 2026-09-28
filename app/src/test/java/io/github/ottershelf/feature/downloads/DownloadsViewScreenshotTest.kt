package io.github.ottershelf.feature.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.feature.library.GridColumns
import io.github.ottershelf.feature.library.GridMetrics
import io.github.ottershelf.feature.library.ListView
import io.github.ottershelf.feature.library.ViewMenuButton
import io.github.ottershelf.feature.library.ViewMode
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import io.github.ottershelf.ui.nav.TopBarActions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Downloaded in the list view and in a four-column grid, light and dark, with the toolbar's view action. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class DownloadsViewScreenshotTest {

    @Test
    fun list() = captureLightAndDark("downloads/downloads_list") { Downloaded(ListView(ViewMode.LIST)) }

    @Test
    fun grid() = captureLightAndDark("downloads/downloads_grid_4") { Downloaded(ListView(cellDp = GridColumns.cellFor(399f, 4))) }

    @Composable
    private fun Downloaded(view: ListView) {
        WithFakeCovers {
            val metrics = remember { GridMetrics() }
            RootFrame(
                title = "Downloaded",
                search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
                onOpenDrawer = {},
                onOpenSearch = {},
                onSubmitSearch = {},
                onCloseSearch = {},
            ) { padding ->
                TopBarActions { ViewMenuButton(view, metrics, onChange = {}) }
                DownloadsContent(
                    state = DownloadsUiState(
                        loading = false,
                        active = listOf(
                            ActiveDownload(90, "The White Company", listOf("Arthur Conan Doyle"), fakeCover(7), 3_400_000, 8_000_000, false),
                        ),
                        books = listOf(
                            book(1, "The Lost World", "Arthur Conan Doyle", 27.0, "reading", cover = 1, rating = 5),
                            book(2, "A Study in Scarlet", "Arthur Conan Doyle", 100.0, "read", cover = 2, series = "1", seriesName = "Sherlock Holmes", rating = 4),
                            book(3, "The Sign of the Four", "Arthur Conan Doyle", null, null, cover = null, series = "2", seriesName = "Sherlock Holmes"),
                            book(4, "Dracula", "Bram Stoker", 64.0, "reading", cover = 4, format = "pdf"),
                            book(5, "King Solomon's Mines", "H. Rider Haggard", null, "want_to_read", cover = 5),
                            book(6, "Little Nemo, Volume One", "Winsor McCay", 12.0, "on_hold", cover = 3, format = "cbz", series = "1", seriesName = "Little Nemo"),
                        ),
                        view = view,
                    ),
                    contentPadding = padding,
                    metrics = metrics,
                )
            }
        }
    }

    private fun book(
        id: Long,
        title: String,
        author: String,
        progress: Double?,
        status: String?,
        cover: Int?,
        series: String? = null,
        seriesName: String? = null,
        rating: Int? = null,
        format: String = "epub",
    ) = DownloadedItem(
        card = BookCard(
            id = id,
            title = title,
            authors = listOf(author),
            seriesName = seriesName,
            seriesIndex = series,
            readingProgress = progress,
            readStatus = ReadStatusInfo(status = status),
            hasCover = cover != null,
            files = listOf(BookFile(id * 10, format, role = "primary")),
            rating = rating,
        ),
        fileId = id * 10,
        cover = cover?.let { fakeCover(it) },
        sizeBytes = 1_200_000,
        format = format,
    )
}
