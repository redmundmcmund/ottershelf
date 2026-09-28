package io.github.ottershelf.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.feature.history.CachedBook
import io.github.ottershelf.feature.history.HistoryCard
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Adding a book's dates from the calendar, light and dark: the picker, the dates sheet for a new
 * book and for one with a reading already, History's edit, and the marks it leaves on the month
 * and the day. The sheets are drawn in place over the calendar (a ModalBottomSheet opens a window
 * a capture can't see): the screen, the scrim, the sheet with its handle.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class ReadingDatesScreenshotTest {

    private val today = LocalDate.of(2026, 9, 26)
    private val day = LocalDate.of(2026, 9, 24)

    private val lostWorld = PickerBook(1, "The Lost World", listOf("Arthur Conan Doyle"), fakeCover(1), listOf("epub"), "read")

    @Test
    fun picker() = captureLightAndDark("calendar/add_picker") {
        OverCalendar {
            BookPickerContent(
                PickerUiState(
                    day = day,
                    query = "conan doyle",
                    loading = false,
                    books = listOf(
                        lostWorld,
                        PickerBook(2, "The White Company", listOf("Arthur Conan Doyle"), fakeCover(2), listOf("epub", "pdf"), "want_to_read"),
                        PickerBook(3, "A Study in Scarlet", listOf("Arthur Conan Doyle"), null, listOf("cbz"), "reading"),
                        PickerBook(4, "The Stark Munro Letters", listOf("Arthur Conan Doyle"), fakeCover(4), emptyList(), null),
                    ),
                ),
            )
        }
    }

    @Test
    fun datesForANewBook() = captureLightAndDark("calendar/add_dates_new") {
        OverCalendar {
            ReadingDatesContent(
                DatesUiState(
                    book = PickerBook(2, "The White Company", listOf("Arthur Conan Doyle"), fakeCover(2), listOf("epub"), "want_to_read"),
                    day = day,
                    today = today,
                    loading = false,
                    form = DatesForm(LocalDate.of(2026, 9, 2), day, AttemptOutcome.COMPLETED),
                ),
            )
        }
    }

    /** Marked read by hand (no start, the day it was marked as its end): offered to fix, or a reread. */
    @Test
    fun datesForABookWithAReading() = captureLightAndDark("calendar/add_dates_existing") {
        val marked = ReadingAttempt(id = 3, bookId = 1, endedOn = "2026-09-25", outcome = AttemptOutcome.COMPLETED, origin = "manual")
        val older = ReadingAttempt(id = 2, bookId = 1, startedOn = "2019-04-02", endedOn = "2019-04-20", outcome = AttemptOutcome.COMPLETED, totalSessions = 14)
        OverCalendar {
            ReadingDatesContent(
                DatesUiState(
                    book = lostWorld,
                    day = day,
                    today = today,
                    loading = false,
                    attempts = listOf(marked, older),
                    status = "read",
                    target = DatesTarget.Existing(3),
                    form = DatesForm(null, day, AttemptOutcome.COMPLETED),
                ),
            )
        }
    }

    /** History's "Edit dates" on the reading going on: kept open, or finished or given up. */
    @Test
    fun editFromHistory() = captureLightAndDark("calendar/edit_dates_open") {
        val open = ReadingAttempt(id = 9, bookId = 3, startedOn = "2026-09-12", outcome = null, totalSessions = 6)
        val first = ReadingAttempt(id = 2, bookId = 3, startedOn = "2021-03-01", endedOn = "2021-03-20", outcome = AttemptOutcome.COMPLETED)
        OverCalendar {
            ReadingDatesContent(
                DatesUiState(
                    book = PickerBook(3, "A Study in Scarlet", listOf("Arthur Conan Doyle"), null, emptyList(), "rereading"),
                    day = null,
                    today = today,
                    loading = false,
                    attempts = listOf(open, first),
                    status = "rereading",
                    fixed = true,
                    target = DatesTarget.Existing(9),
                    form = DatesForm(LocalDate.of(2026, 9, 12), today, null),
                ),
            )
        }
    }

    @Test
    fun monthWithMarks() = captureLightAndDark("calendar/month_marks", heightDp = 1500) {
        WithFakeCovers { Calendar() }
    }

    @Test
    fun dayWithReadings() = captureLightAndDark("calendar/day_readings") {
        WithFakeCovers {
            DayContent(
                DayUiState(
                    date = day,
                    loading = false,
                    totalSeconds = 1500,
                    zone = ZoneId.of("Europe/Dublin"),
                    sessions = listOf(
                        DaySessionRow(1, 5, "Middlemarch", fakeCover(5), day.atTime(7, 40).atZone(ZoneId.of("Europe/Dublin")).toInstant().toEpochMilli(), day.atTime(8, 5).atZone(ZoneId.of("Europe/Dublin")).toInstant().toEpochMilli(), 1500, 3.2, pages = 28),
                    ),
                    readings = listOf(
                        DayReadingRow(2, "The White Company", fakeCover(2), MarkKind.FINISHED),
                        DayReadingRow(6, "Dracula", fakeCover(6), MarkKind.GAVE_UP),
                        DayReadingRow(3, "A Study in Scarlet", null, MarkKind.STARTED),
                    ),
                ),
                onAddBook = {},
            )
        }
    }

    // --- the frame ------------------------------------------------------------------------------

    private fun card(id: Long, title: String, status: String) =
        HistoryCard(id, title, listOf("Arthur Conan Doyle"), hasCover = id != 3L, readStatus = io.github.ottershelf.core.model.ReadStatusInfo(status = status))

    private fun reading(id: Long, bookId: Long, start: String?, end: String?, outcome: String? = AttemptOutcome.COMPLETED) =
        ReadingAttempt(id = id, bookId = bookId, startedOn = start, endedOn = end, outcome = outcome)

    /** September with sessions on some days and readings given dates by hand on others. */
    @Composable
    private fun Calendar() {
        fun book(id: Long, title: String, seconds: Long, cover: Boolean = true) = CalendarBook(id, title, cover, null, seconds)
        val middlemarch = book(5, "Middlemarch", 1500)
        val frankenstein = book(7, "Frankenstein", 2400)
        val month = CalendarMonth(
            month = "2026-09",
            days = buildList {
                listOf(1, 2, 4, 7, 8, 9).forEach { add(CalendarDay("2026-09-%02d".format(it), 2400, 1, listOf(frankenstein))) }
                add(CalendarDay("2026-09-10", 3900, 2, listOf(frankenstein, middlemarch)))
                listOf(15, 16, 17, 22, 23, 24, 25).forEach { add(CalendarDay("2026-09-$it", 1500, 1, listOf(middlemarch))) }
            },
            availableYears = listOf(2025, 2026),
        )
        val books = listOf(
            CachedBook(card(2, "The White Company", "read"), "", listOf(reading(20, 2, "2026-09-03", "2026-09-24"))),
            CachedBook(card(6, "Dracula", "abandoned"), "", listOf(reading(60, 6, "2026-09-05", "2026-09-12", AttemptOutcome.ABANDONED))),
            CachedBook(card(7, "Frankenstein", "read"), "", listOf(reading(70, 7, "2026-08-28", "2026-09-10"))),
            CachedBook(card(3, "A Study in Scarlet", "reading"), "", listOf(reading(30, 3, "2026-09-18", null, null))),
        )
        RootFrame(
            title = "Calendar",
            search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
            onOpenDrawer = {},
            onOpenSearch = {},
            onSubmitSearch = {},
            onCloseSearch = {},
        ) { padding ->
            CalendarToolbar(onAddBook = {}, onHistory = {}, onGoals = {}, saving = false, onSaveImage = {})
            CalendarContent(
                state = CalendarUiState(
                    month = YearMonth.of(2026, 9),
                    today = today,
                    data = month,
                    firstMonth = YearMonth.of(2025, 1),
                    marks = CalendarMath.marksOf(books),
                ),
                coverOf = { if (it.hasCover) fakeCover(it.bookId.toInt()) else null },
                contentPadding = padding,
            )
        }
    }

    /** [sheet] as the bottom sheet shows it over the calendar: the scrim, the rounded top and its handle. */
    @Composable
    private fun OverCalendar(sheet: @Composable () -> Unit) {
        WithFakeCovers {
            Box(Modifier.fillMaxSize()) {
                Calendar()
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                val radius = OttershelfTheme.radii.xl2
                Surface(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    color = OttershelfTheme.colors.card,
                    shape = RoundedCornerShape(topStart = radius, topEnd = radius),
                ) {
                    Column(Modifier.padding(bottom = 16.dp)) {
                        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(32.dp, 4.dp).background(OttershelfTheme.colors.mutedForeground.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
                        }
                        sheet()
                    }
                }
            }
        }
    }
}
