package io.github.ottershelf.feature.bookedit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.feature.book.BookDetailContent
import io.github.ottershelf.feature.book.BookDetailUiState
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Edit details, light and dark: bookedit/..._light.png and _dark.png. Record with `--tests "*BookEditScreenshotTest"`. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class BookEditScreenshotTest {

    private val book = BookDetail(
        id = 1,
        title = "The Sherlock Holmes Stories",
        subtitle = "The Complete Illustrated Edition",
        authors = listOf(AuthorRef(5, "Arthur Conan Doyle")),
        seriesId = 9,
        seriesName = "Sherlock Holmes",
        seriesIndex = "7",
        coverSource = "custom",
        files = listOf(BookFile(11, "epub", "primary", 9_843_200, "The Sherlock Holmes Stories.epub")),
        readStatus = ReadStatusInfo("reading"),
        updatedAt = "2026-09-01T00:00:00.000Z",
    )

    private val original = EditForm.of(book)

    private val loaded = BookEditUiState(
        bookId = 1,
        loading = false,
        book = book,
        cover = fakeCover(3),
        original = original,
        form = original,
    )

    @Test
    fun form() = captureLightAndDark("bookedit/form") {
        WithFakeCovers {
            BookEditContent(
                loaded.copy(
                    form = original.copy(
                        title = "The Sherlock Holmes Stories: The Complete Illustrated Edition",
                        authors = listOf("Arthur Conan Doyle", "Charles Doyle"),
                        seriesIndex = "7.5",
                    ),
                    coverMessage = CoverMessage.Updated,
                ),
            )
        }
    }

    /** Messy metadata: a whole credits line as one author. The name ends in "…", the X stays. */
    private val longAuthors = listOf("Arthur Conan Doyle (Author), Charles Doyle (Illustrator), Joseph Bell (Introduction)", "Charles Doyle")

    @Test
    fun longAuthor() = captureLightAndDark("bookedit/long_author") {
        WithFakeCovers {
            BookEditContent(loaded.copy(form = original.copy(authors = longAuthors)))
        }
    }

    @Test
    fun longAuthorLargeText() = captureLightAndDark("bookedit/long_author_large_text") {
        WithFakeCovers {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                BookEditContent(loaded.copy(form = original.copy(authors = longAuthors)))
            }
        }
    }

    @Test
    fun authorSuggestions() = captureLightAndDark("bookedit/author_suggestions") {
        WithFakeCovers {
            BookEditContent(
                loaded.copy(
                    authorInput = "char",
                    authorSuggestions = Suggestions(
                        query = "char",
                        items = listOf(
                            AuthorSuggestion("Charles Doyle", 3),
                            AuthorSuggestion("Charles Dickens", 14),
                            AuthorSuggestion("Charlotte Brontë", 4),
                            AuthorSuggestion("Richard Doyle", 1),
                        ),
                    ),
                ),
                focused = EditField.AUTHORS,
            )
        }
    }

    @Test
    fun seriesSuggestions() = captureLightAndDark("bookedit/series_suggestions") {
        WithFakeCovers {
            BookEditContent(
                loaded.copy(
                    form = original.copy(series = "chron", seriesIndex = "7"),
                    seriesSuggestions = Suggestions(
                        query = "chron",
                        items = listOf(
                            SeriesSuggestion("Chronicles of Barsetshire", 6, listOf("Anthony Trollope")),
                            SeriesSuggestion("Chronicles of Carlingford", 7, listOf("Margaret Oliphant")),
                            SeriesSuggestion("Chronicles of the Canongate", 2, listOf("Walter Scott")),
                        ),
                    ),
                ),
                focused = EditField.SERIES,
            )
        }
    }

    @Test
    fun newSeries() = captureLightAndDark("bookedit/new_series") {
        WithFakeCovers {
            BookEditContent(
                loaded.copy(
                    form = original.copy(series = "Sherlock Holmes Illustrated", seriesIndex = ""),
                    seriesSuggestions = Suggestions(query = "Sherlock Holmes Illustrated", items = emptyList()),
                ),
                focused = EditField.SERIES,
            )
        }
    }

    /** The cover's actions for a book with no cover yet (the generated one shows; the file is looked in). */
    @Test
    fun coverActions() = captureLightAndDark("bookedit/cover_actions", heightDp = 520) {
        WithFakeCovers {
            BookEditContent(loaded.copy(book = book.copy(coverSource = null), cover = null, coverBusy = false))
        }
    }

    @Test
    fun coverChanging() = captureLightAndDark("bookedit/cover_busy", heightDp = 520) {
        WithFakeCovers {
            BookEditContent(loaded.copy(coverBusy = true))
        }
    }

    @Test
    fun coverSearch() = captureLightAndDark("bookedit/cover_search") {
        WithFakeCovers {
            Box(Modifier.padding(top = 24.dp)) {
                CoverSearchContent(
                    CoverSearchUi(
                        title = "The Sherlock Holmes Stories",
                        author = "Arthur Conan Doyle",
                        provider = CoverProviders.DUCKDUCKGO,
                        audiobook = false,
                        results = listOf(
                            CoverResult("https://a.example/1.jpg", fakeCover(1), 1200, 1800, "Amazon"),
                            CoverResult("https://a.example/2.jpg", fakeCover(2), 600, 900, "Goodreads"),
                            CoverResult("https://a.example/3.jpg", fakeCover(3), 1400, 2100, "Open Library"),
                            CoverResult("https://a.example/4.jpg", fakeCover(4), 500, 760, "Google Books"),
                            CoverResult("https://a.example/5.jpg", null, 800, 1200, "Wikipedia"),
                            CoverResult("https://a.example/6.jpg", fakeCover(5), 1000, 1500, "Barnes & Noble"),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun coverSearchLoading() = captureLightAndDark("bookedit/cover_search_loading", heightDp = 700) {
        WithFakeCovers {
            Box(Modifier.padding(top = 24.dp)) {
                CoverSearchContent(CoverSearchUi("Sherlock Holmes", "", CoverProviders.ALL, audiobook = false, loading = true))
            }
        }
    }

    @Test
    fun coverSearchNothing() = captureLightAndDark("bookedit/cover_search_empty", heightDp = 620) {
        WithFakeCovers {
            Box(Modifier.padding(top = 24.dp)) {
                CoverSearchContent(CoverSearchUi("Qwxyz", "", CoverProviders.ITUNES, audiobook = false, results = emptyList()))
            }
        }
    }

    @Test
    fun confirmOnlineCover() = captureLightAndDark("bookedit/cover_confirm", heightDp = 640) {
        WithFakeCovers {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                PendingCoverContent(
                    PendingCover.Online(CoverResult("https://a.example/1.jpg", fakeCover(1), 1200, 1800, "Amazon")),
                    busy = false,
                    failed = null,
                    preview = fakeCover(1),
                )
            }
        }
    }

    @Test
    fun confirmFailed() = captureLightAndDark("bookedit/cover_confirm_failed", heightDp = 640) {
        WithFakeCovers {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                PendingCoverContent(
                    PendingCover.Photo("cover.jpg", 1067, 1600),
                    busy = false,
                    failed = EditError.Offline,
                    preview = fakeCover(4),
                )
            }
        }
    }

    @Test
    fun confirmFileCover() = captureLightAndDark("bookedit/cover_restore", heightDp = 420) {
        WithFakeCovers {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                PendingCoverContent(PendingCover.FromFileCover, busy = false, failed = null)
            }
        }
    }

    /** The user's cover removed from a book whose file has none: the generated cover, and a line saying so. */
    @Test
    fun coverNoneLeft() = captureLightAndDark("bookedit/cover_none_left", heightDp = 520) {
        WithFakeCovers {
            BookEditContent(loaded.copy(book = book.copy(coverSource = null), cover = null, coverMessage = CoverMessage.NoCoverLeft))
        }
    }

    /** A cover change refused because someone locked the cover meanwhile: the reason, beside Unlock. */
    @Test
    fun coverLockedMeanwhile() = captureLightAndDark("bookedit/cover_locked_meanwhile", heightDp = 520) {
        WithFakeCovers {
            BookEditContent(
                loaded.copy(
                    book = book.copy(lockedFields = listOf("cover")),
                    coverMessage = CoverMessage.Failed(EditError.Server("Metadata fields are locked: cover")),
                ),
            )
        }
    }

    @Test
    fun locked() = captureLightAndDark("bookedit/locked") {
        WithFakeCovers {
            BookEditContent(loaded.copy(book = book.copy(lockedFields = listOf("title", "seriesName", "seriesIndex", "cover"))))
        }
    }

    @Test
    fun errors() = captureLightAndDark("bookedit/error") {
        WithFakeCovers {
            BookEditContent(
                loaded.copy(
                    form = original.copy(title = "", authors = emptyList(), seriesIndex = "7."),
                    saveError = EditError.Offline,
                ),
            )
        }
    }

    @Test
    fun serverRefused() = captureLightAndDark("bookedit/error_server") {
        WithFakeCovers {
            BookEditContent(
                loaded.copy(
                    form = original.copy(title = "The Sherlock Holmes Stories (Illustrated)"),
                    saveError = EditError.Server("Metadata fields are locked: title"),
                ).let { it.copy(book = it.book?.copy(lockedFields = emptyList())) },
            )
        }
    }

    @Test
    fun loadFailed() = captureLightAndDark("bookedit/load_failed", heightDp = 520) {
        WithFakeCovers {
            BookEditContent(BookEditUiState(bookId = 1, loading = false, loadError = EditError.Offline))
        }
    }

    @Test
    fun cantEdit() = captureLightAndDark("bookedit/cant_edit", heightDp = 520) {
        WithFakeCovers {
            BookEditContent(BookEditUiState(bookId = 1, canEdit = false, loading = false))
        }
    }

    @Test
    fun camera() = captureLightAndDark("bookedit/camera") {
        WithFakeCovers {
            CoverCameraContent(onClose = {}, onTaken = { _, _ -> }, onFailed = {}, onChoosePhoto = {})
        }
    }

    /** The book page's toolbar with the pencil, for an account that may edit. */
    @Test
    fun bookPagePencil() = captureLightAndDark("bookedit/book_page", heightDp = 520) {
        WithFakeCovers {
            BookDetailContent(
                state = BookDetailUiState(
                    bookId = 1,
                    book = book,
                    loading = false,
                    loaded = true,
                    status = "reading",
                    cover = fakeCover(3),
                    readFile = book.files.first(),
                    offlineFile = book.files.first(),
                    canEdit = true,
                ),
                tint = Color(0xFF3D7EA6),
            )
        }
    }
}
