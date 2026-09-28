package io.github.ottershelf.feature.downloads

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.feature.reader.captureDark
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Downloaded list under the shell's toolbar: a download on its way, then the books on the device. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class DownloadsScreenshotTest {

    @Test
    fun grid() = captureDark("downloads/downloads") {
        WithFakeCovers {
            RootFrame(
                title = "Downloaded",
                search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
                onOpenDrawer = {},
                onOpenSearch = {},
                onSubmitSearch = {},
                onCloseSearch = {},
            ) { padding ->
                DownloadsContent(
                    state = DownloadsUiState(
                        loading = false,
                        active = listOf(
                            ActiveDownload(90, "The White Company", listOf("Arthur Conan Doyle"), fakeCover(7), 3_400_000, 8_000_000, false),
                        ),
                        books = listOf(
                            book(1, "The Lost World", "Arthur Conan Doyle", 27.0, "reading", cover = 1),
                            book(2, "A Study in Scarlet", "Arthur Conan Doyle", 100.0, "read", cover = 2, series = "1"),
                            book(3, "The Sign of the Four", "Arthur Conan Doyle", null, null, cover = null, series = "2"),
                            book(4, "Dracula", "Bram Stoker", 64.0, "reading", cover = 4),
                            book(5, "King Solomon's Mines", "H. Rider Haggard", null, "want_to_read", cover = 5),
                        ),
                    ),
                    contentPadding = padding,
                )
            }
        }
    }

    private fun book(id: Long, title: String, author: String, progress: Double?, status: String?, cover: Int?, series: String? = null) =
        DownloadedItem(
            card = BookCard(
                id = id,
                title = title,
                authors = listOf(author),
                seriesIndex = series,
                readingProgress = progress,
                readStatus = ReadStatusInfo(status = status),
                hasCover = cover != null,
            ),
            fileId = id * 10,
            cover = cover?.let { fakeCover(it) },
            sizeBytes = 1_200_000,
        )
}
