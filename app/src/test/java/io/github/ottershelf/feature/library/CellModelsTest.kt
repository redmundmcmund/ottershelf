package io.github.ottershelf.feature.library

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.AuthorSummary
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.SeriesSummary
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * A page arriving doesn't build the covers (portraits, series covers) of the cells already shown
 * again: each cell keeps the model it was given, the same instance, so it can skip. A new URL
 * string on every pass (the cells take their model as Any?, compared by identity) made every cell
 * on screen recompose twice per page, in the frames where paging starts during a fling.
 *
 * Two checks per screen: each shown cell's model is looked up once, and the cell itself
 * (BookGridItem, BookRow, AuthorTile, SeriesCard) doesn't run again when a page arrives. The
 * second also needs the cell's other arguments (its click lambdas) to stay the same instances;
 * it counts the bodies that run through the Compose compiler's trace markers ([BodyRuns]).
 *
 * The models come from function references, as the screens pass the ViewModels' (`viewModel::cover`).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@OptIn(InternalComposeTracingApi::class)
class CellModelsTest {

    @get:Rule
    val rule = createComposeRule()

    private val runs = BodyRuns()

    @Before
    fun countBodies() = Composer.setTracer(runs)

    @After
    fun stopCounting() = Composer.setTracer(null)

    /**
     * Counts the look-ups per id. Each look-up returns a new string, equal to the last but not the
     * same instance, as Api.thumbnailUrl does; [WithFakeCovers] draws them, with no network.
     */
    private class Models {
        val calls = mutableMapOf<Long, Int>()
        fun cover(book: BookCard): Any? = count(book.id)
        fun portrait(author: AuthorSummary): Any? = count(author.id)
        fun seriesCover(bookId: Long): Any? = count(bookId)
        private fun count(id: Long): Any? {
            calls[id] = (calls[id] ?: 0) + 1
            return fakeCover(id.toInt())
        }
    }

    /**
     * How many times each composable's body ran (a skipped call doesn't count), by its qualified
     * name. The compiler puts a trace marker at the start of every composable body that runs, with
     * the name and the source line; the debug classes the tests run keep them.
     */
    private class BodyRuns : CompositionTracer {
        private val runs = ConcurrentHashMap<String, Int>()
        override fun isTraceInProgress() = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            runs.merge(info.substringBefore(' '), 1, Int::plus)
        }
        override fun traceEventEnd() {}
        fun of(function: String) = runs[function] ?: 0
    }

    /** The pager's two emissions for a page: loading, then the page added. */
    private fun <T> nextPage(state: androidx.compose.runtime.MutableState<PagedState<T>>, all: List<T>) {
        rule.runOnIdle { state.value = state.value.copy(loading = true) }
        rule.runOnIdle { state.value = state.value.copy(items = all.take(state.value.items.size + PAGE), loading = false) }
        rule.waitForIdle()
    }

    private fun assertOncePerShown(models: Models, shown: Set<Long>) = rule.runOnIdle {
        assertTrue("nothing was shown", shown.isNotEmpty())
        assertEquals(shown.associateWith { 1 }, models.calls.filterKeys { it in shown })
    }

    /** The cells' bodies, counted when the first page was shown. */
    private fun ran(cell: String): Int = rule.runOnIdle { runs.of(cell) }.also { assertTrue("$cell never ran", it > 0) }

    private fun assertSkipped(cell: String, ranBefore: Int) = rule.runOnIdle {
        assertEquals("$cell ran again when a page arrived", ranBefore, runs.of(cell))
    }

    @Test
    fun theGridsCellsKeepTheirCovers() {
        val books = (1L..3 * PAGE).map { BookCard(it, "Book $it", listOf("Author"), hasCover = true) }
        val state = mutableStateOf(PagedState(items = books.take(PAGE), total = books.size))
        val models = Models()
        rule.setContent { OttershelfTheme { WithFakeCovers { BookGridContent(state = state.value, coverOf = models::cover) } } }
        rule.waitForIdle()
        val shown = models.calls.keys.toSet()
        val cells = ran(BOOK_GRID_ITEM)
        nextPage(state, books)
        nextPage(state, books)
        assertOncePerShown(models, shown)
        assertSkipped(BOOK_GRID_ITEM, cells)
    }

    @Test
    fun theListsRowsKeepTheirCovers() {
        val books = (1L..3 * PAGE).map { BookCard(it, "Book $it", listOf("Author"), hasCover = true) }
        val state = mutableStateOf(PagedState(items = books.take(PAGE), total = books.size))
        val models = Models()
        rule.setContent {
            OttershelfTheme { WithFakeCovers { BookGridContent(state = state.value, coverOf = models::cover, view = ListView(ViewMode.LIST)) } }
        }
        rule.waitForIdle()
        val shown = models.calls.keys.toSet()
        val rows = ran(BOOK_ROW)
        nextPage(state, books)
        assertOncePerShown(models, shown)
        assertSkipped(BOOK_ROW, rows)
    }

    @Test
    fun authorTilesKeepTheirPortraits() {
        val authors = (1L..3 * PAGE).map { AuthorSummary(it, "Author $it", imageUrl = "/api/v1/authors/$it/thumbnail?t=1") }
        val state = mutableStateOf(PagedState(items = authors.take(PAGE), total = authors.size))
        val models = Models()
        rule.setContent { OttershelfTheme { WithFakeCovers { AuthorsContent(state = state.value, portraitOf = models::portrait) } } }
        rule.waitForIdle()
        val shown = models.calls.keys.toSet()
        val tiles = ran(AUTHOR_TILE)
        nextPage(state, authors)
        assertOncePerShown(models, shown)
        assertSkipped(AUTHOR_TILE, tiles)
    }

    /**
     * A series card builds its list of covers itself; a card that runs again keeps it (a new list
     * never skips). The cards already skipped when a page arrived, so this one guards against that
     * changing.
     */
    @Test
    fun seriesCardsKeepTheirCovers() {
        // Each series' first cover id is its own id times 100, so the counts tell the series apart.
        val series = (1L..3 * PAGE).map { SeriesSummary(it, "Series $it", bookCount = 3, coverBookIds = listOf(it * 100, it * 100 + 1, it * 100 + 2, it * 100 + 3)) }
        val state = mutableStateOf(PagedState(items = series.take(PAGE), total = series.size))
        val models = Models()
        rule.setContent { OttershelfTheme { WithFakeCovers { SeriesContent(state = state.value, coverOf = models::seriesCover) } } }
        rule.waitForIdle()
        val shown = models.calls.keys.toSet()
        rule.runOnIdle {
            // Three covers fanned out per card, never the fourth.
            assertTrue(shown.none { it % 100 == 3L })
        }
        val cards = ran(SERIES_CARD)
        nextPage(state, series)
        assertOncePerShown(models, shown)
        assertSkipped(SERIES_CARD, cards)
    }

    private companion object {
        const val PAGE = 60
        const val BOOK_GRID_ITEM = "io.github.ottershelf.ui.components.BookGridItem"
        const val BOOK_ROW = "io.github.ottershelf.feature.library.BookRow"
        const val AUTHOR_TILE = "io.github.ottershelf.feature.library.AuthorTile"
        const val SERIES_CARD = "io.github.ottershelf.feature.library.SeriesCard"
    }
}
