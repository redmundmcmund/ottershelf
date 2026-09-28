package io.github.ottershelf.feature.book

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.download.Downloads
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.feature.library.captureDark
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A loaded book page in dark theme, downloading. Record with `--tests "*BookDetailScreenshotTest"`. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class BookDetailScreenshotTest {

    @Test
    fun loaded() = captureDark("book/detail") {
        WithFakeCovers {
            BookDetailContent(state = sampleState, tint = Color(0xFF3D7EA6))
        }
    }

    /** A book whose Read file isn't an EPUB names its format: "Read PDF". */
    @Test
    fun readsAPdf() = captureDark("book/detail_pdf") {
        WithFakeCovers {
            val pdf = sampleBook.files.first { it.format == "pdf" }
            BookDetailContent(state = sampleState.copy(readFile = pdf, offlineFile = pdf, download = null), tint = Color(0xFF3D7EA6))
        }
    }
}

private val epub = BookFile(11, "epub", "primary", 1_843_200, "A Study in Scarlet.epub")

internal val sampleBook = BookDetail(
    id = 1,
    libraryName = "Fiction",
    title = "A Study in Scarlet",
    subtitle = "Sherlock Holmes, Book One",
    description = "<p>Dr John Watson, home from Afghanistan with a wounded shoulder, takes rooms in Baker Street with Sherlock Holmes.</p>" +
        "<p>When a man is found dead in an empty house off the Brixton Road, a word written in blood on the wall beside him, " +
        "Scotland Yard asks Holmes for help. The trail runs across London and back to the American West, " +
        "and shows for the first time the method that made his name.</p><p>With the original illustrations.</p>",
    authors = listOf(AuthorRef(5, "Arthur Conan Doyle"), AuthorRef(null, "George Hutchinson")),
    seriesId = 9,
    seriesName = "Sherlock Holmes",
    seriesIndex = "1",
    publisher = "Ward, Lock & Co.",
    publishedYear = 1887,
    pageCount = 183,
    language = "en",
    coverSource = "embedded",
    files = listOf(epub, BookFile(12, "pdf", "alternate", 5_300_000, "A Study in Scarlet.pdf")),
    readStatus = ReadStatusInfo("reading"),
)

private val sampleState = BookDetailUiState(
    bookId = 1,
    book = sampleBook,
    loading = false,
    loaded = true,
    status = "reading",
    cover = fakeCover(1),
    readFile = epub,
    offlineFile = epub,
    download = Downloads.Progress(done = 830_000, total = 1_843_200),
)
