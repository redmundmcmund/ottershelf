package io.github.ottershelf.feature.seriesnext

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.feature.book.BookDetailContent
import io.github.ottershelf.feature.book.BookDetailUiState
import io.github.ottershelf.feature.book.BookTrackingActions
import io.github.ottershelf.feature.book.BookTrackingUiState
import io.github.ottershelf.feature.book.CelebrationStep
import io.github.ottershelf.feature.book.FinishCelebrationContent
import io.github.ottershelf.feature.reader.ReaderContent
import io.github.ottershelf.feature.reader.ReaderUiState
import io.github.ottershelf.feature.timer.SaveOutcome
import io.github.ottershelf.feature.timer.SessionSummary
import io.github.ottershelf.feature.timer.TimerResultContent
import io.github.ottershelf.feature.timer.TimerResultUiState
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The next book in the series: on the finish celebration, the book page (and its "all read" line),
 * the timer's result and the reader's last page. Record with `--tests "*SeriesNextScreenshotTest"`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class SeriesNextScreenshotTest {

    @Test
    fun celebration() = captureLightAndDark("seriesnext/celebration") {
        WithFakeCovers {
            FinishCelebrationContent(
                step = CelebrationStep.Done(days = 12),
                title = finished.title,
                cover = fakeCover(1),
                tracking = BookTrackingUiState(loading = false),
                actions = BookTrackingActions.None,
            ) {
                NextInSeriesCard(next, onRead = {}, onDetails = {})
            }
        }
    }

    @Test
    fun celebrationAllRead() = captureLightAndDark("seriesnext/celebration_all_read") {
        WithFakeCovers {
            FinishCelebrationContent(
                step = CelebrationStep.Done(days = 12),
                title = finished.title,
                cover = fakeCover(1),
                tracking = BookTrackingUiState(loading = false),
                actions = BookTrackingActions.None,
            ) {
                NextInSeriesCard(SeriesNextState.AllRead(6, "The Oz Books"), onRead = {}, onDetails = {})
            }
        }
    }

    @Test
    fun bookPage() = captureLightAndDark("seriesnext/book_page") {
        WithFakeCovers {
            BookDetailContent(
                state = finishedState,
                tint = Color(0xFF3D7EA6),
                tracking = finishedTracking,
                today = LocalDate.of(2026, 9, 26),
                seriesNext = { modifier -> NextInSeriesRow(next, onRead = {}, onDetails = {}, modifier = modifier) },
            )
        }
    }

    @Test
    fun bookPageAllRead() = captureLightAndDark("seriesnext/book_page_all_read") {
        WithFakeCovers {
            BookDetailContent(
                state = finishedState,
                tint = Color(0xFF3D7EA6),
                tracking = finishedTracking,
                today = LocalDate.of(2026, 9, 26),
                seriesNext = { modifier ->
                    NextInSeriesRow(SeriesNextState.AllRead(6, "The Oz Books"), onRead = {}, onDetails = {}, modifier = modifier)
                },
            )
        }
    }

    /** A next book nothing here can open: Details only. */
    @Test
    fun bookPageDetailsOnly() = captureLightAndDark("seriesnext/book_page_details_only") {
        WithFakeCovers {
            BookDetailContent(
                state = finishedState,
                tint = Color(0xFF3D7EA6),
                tracking = finishedTracking,
                today = LocalDate.of(2026, 9, 26),
                seriesNext = { modifier ->
                    NextInSeriesRow(
                        SeriesNextState.Next(nextBook.copy(file = null, card = nextBook.card.copy(title = "The Emerald City of Oz: the long title of a sixth book", seriesIndex = "4.5"))),
                        onRead = {},
                        onDetails = {},
                        modifier = modifier,
                    )
                },
            )
        }
    }

    @Test
    fun timerResult() = captureLightAndDark("seriesnext/timer_result") {
        WithFakeCovers {
            TimerResultContent(
                TimerResultUiState(
                    loaded = true,
                    summary = SessionSummary(
                        bookId = 3, sessionId = "s1", title = finished.title, authors = "L. Frank Baum", activeSeconds = 47 * 60,
                        outcome = SaveOutcome.Sent, total = 592, startPage = 548, endPage = 592, startPercent = 92.6, endPercent = 100.0,
                        bookPagesPerHour = 56.0,
                    ),
                ),
                next = { NextInSeriesCard(next, onRead = {}, onDetails = {}) },
            )
        }
    }

    @Test
    fun readerEnd() = captureLightAndDark("seriesnext/reader_end") {
        WithFakeCovers {
            ReaderContent(
                state = ReaderUiState(title = finished.title!!, loading = false, fraction = 1f, page = 1480, pages = 1481),
                overlay = { EndCard(next) },
            ) { modifier -> LastPage(modifier) }
        }
    }

    @Test
    fun readerEndAllRead() = captureLightAndDark("seriesnext/reader_end_all_read") {
        WithFakeCovers {
            ReaderContent(
                state = ReaderUiState(title = finished.title!!, loading = false, fraction = 1f),
                overlay = { EndCard(SeriesNextState.AllRead(6, "The Oz Books")) },
            ) { modifier -> LastPage(modifier) }
        }
    }

    /** As EndOfBookCard places it (the bars hidden). */
    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.EndCard(state: SeriesNextState) {
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            EndOfBookCardContent(state, onRead = {}, onDetails = {}, onClose = {})
        }
    }

    @Composable
    private fun LastPage(modifier: Modifier) {
        Column(modifier.padding(horizontal = 26.dp, vertical = 28.dp)) {
            listOf(
                "“My darling child!” she cried, folding the little girl in her arms and covering her face with kisses. " +
                    "“Where in the world did you come from?”",
                "“From the Land of Oz,” said Dorothy gravely. “And here is Toto, too. And oh, Aunt Em! " +
                    "I’m so glad to be at home again!”",
            ).forEach { paragraph ->
                Text(
                    paragraph,
                    fontSize = 18.sp,
                    lineHeight = 27.sp,
                    color = OttershelfTheme.colors.foreground,
                    textAlign = TextAlign.Justify,
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "THE END",
                style = MaterialTheme.typography.titleMedium,
                color = OttershelfTheme.colors.foreground,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }

    private companion object {
        val finished = BookDetail(
            id = 3,
            libraryName = "Fiction",
            title = "The Wonderful Wizard of Oz",
            authors = listOf(AuthorRef(5, "L. Frank Baum")),
            seriesId = 9,
            seriesName = "The Oz Books",
            seriesIndex = "1",
            publisher = "George M. Hill Company",
            publishedYear = 1900,
            pageCount = 259,
            language = "en",
            coverSource = "embedded",
            files = listOf(BookFile(31, "epub", "primary", 1_843_200, "The Wonderful Wizard of Oz.epub")),
            readStatus = ReadStatusInfo("read", startedAt = "2026-09-14T00:00:00.000Z", finishedAt = "2026-09-25T00:00:00.000Z"),
        )

        val finishedState = BookDetailUiState(
            bookId = 3,
            book = finished,
            loading = false,
            loaded = true,
            status = "read",
            cover = fakeCover(1),
            readFile = finished.files.first(),
            offlineFile = finished.files.first(),
        )

        val finishedTracking = BookTrackingUiState(loading = false, fileProgress = 100.0)

        val nextBook = NextBook(
            card = BookCard(
                id = 4,
                title = "The Marvelous Land of Oz",
                authors = listOf("L. Frank Baum"),
                seriesName = "The Oz Books",
                seriesIndex = "2",
                hasCover = true,
                files = listOf(BookFile(41, "epub", "primary")),
            ),
            seriesName = "The Oz Books",
            cover = fakeCover(3),
            file = BookFile(41, "epub", "primary"),
        )

        val next = SeriesNextState.Next(nextBook)
    }
}
