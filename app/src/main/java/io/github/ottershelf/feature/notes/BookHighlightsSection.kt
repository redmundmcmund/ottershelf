package io.github.ottershelf.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.R
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookAnnotationStats
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.CountPill
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme

/** The book page's Highlights & notes card: totals, colours and the latest few. */
@Immutable
data class HighlightsSummary(
    val loading: Boolean = true,
    val error: String? = null,
    val stats: BookAnnotationStats? = null,
    val latest: List<Annotation> = emptyList(),
)

class BookHighlightsSummaryViewModel(container: AppContainer, private val bookId: Long) : ViewModel() {
    private val remote: NotesRemote = ApiNotesRemote(container.api)
    private val _state = MutableStateFlow(HighlightsSummary())
    val state: StateFlow<HighlightsSummary> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
        // Edited or deleted on the Highlights screen (or anywhere else) for this book: show it.
        viewModelScope.launch {
            NoteChanges.changes.collect { c -> if (c.bookId == null || c.bookId == bookId) load() }
        }
    }

    /** Loads the card; a load under way is replaced, so an older answer can't land last. */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = remote.bookAnnotations(bookId, page = 1, pageSize = LATEST, newestFirst = true)
                _state.value = HighlightsSummary(loading = false, stats = page.stats, latest = page.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private companion object {
        const val LATEST = 3
    }
}

/** The card, stateful: its own ViewModel on the book page's entry. */
@Composable
fun BookHighlightsSection(bookId: Long, onOpen: () -> Unit) {
    val viewModel = appViewModel { BookHighlightsSummaryViewModel(it, bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    HighlightsSummaryCard(state, onOpen = onOpen, onRetry = viewModel::load, modifier = Modifier.padding(top = 24.dp))
}

/**
 * Highlights & notes as a DashCard: the count, the colours as one bar with a legend, notes and
 * chapters, the three newest highlights, and "See all". Tapping anywhere opens the Highlights screen.
 */
@Composable
fun HighlightsSummaryCard(state: HighlightsSummary, onOpen: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val stats = state.stats
    val total = stats?.totalHighlights ?: 0
    DashCard(
        modifier.fillMaxWidth(),
        onClick = when {
            total > 0 -> onOpen
            state.error != null && stats == null -> onRetry
            else -> null
        },
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                CardTitle(stringResource(R.string.notes_book_section), icon = "Highlighter")
                if (total > 0) {
                    Spacer(Modifier.width(8.dp))
                    CountPill(total.toString())
                }
            }
            if (total > 0) LucideIcon("ChevronRight", contentDescription = null, tint = colors.mutedForeground, size = 18.dp)
        }
        when {
            stats == null && state.error != null -> Text(
                stringResource(R.string.notes_section_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedForeground,
                modifier = Modifier.padding(top = 10.dp),
            )
            stats == null -> {
                SkeletonBox(Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp))
                SkeletonBox(Modifier.padding(top = 12.dp).fillMaxWidth().height(40.dp))
            }
            total == 0 -> Text(
                stringResource(R.string.notes_section_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedForeground,
                modifier = Modifier.padding(top = 10.dp),
            )
            else -> {
                ColorBreakdownBar(stats.colorBreakdown, Modifier.padding(top = 14.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(
                            pluralStringResource(R.plurals.notes_notes_count, stats.highlightsWithNotes, stats.highlightsWithNotes),
                            pluralStringResource(R.plurals.notes_chapters_count, stats.chaptersWithHighlights, stats.chaptersWithHighlights),
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.mutedForeground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    ColorLegend(stats.colorBreakdown, max = 4)
                }
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.latest.forEach { note ->
                        Column {
                            QuoteText(note, maxLines = 3, size = 15)
                            if (note.hasNote) NoteLine(note.note.orEmpty(), Modifier.padding(top = 6.dp, start = 13.dp), maxLines = 2)
                        }
                    }
                }
                Text(
                    stringResource(R.string.notes_see_all, total),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    modifier = Modifier.padding(top = 14.dp).clickable(onClick = onOpen),
                )
            }
        }
    }
}
