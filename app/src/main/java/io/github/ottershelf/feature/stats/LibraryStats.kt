package io.github.ottershelf.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.feature.stats.model.LibrarySummary
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The Library tab: the web's library statistics (client/src/features/statistics/components/library)
 * that read well on a phone, all scoped to the libraries the user can see.
 */

internal fun LazyListScope.libraryItems(state: StatsUiState, onRetry: (StatsChart) -> Unit, onOpenBook: (Long) -> Unit) {
    val l = state.library
    item(key = "lib-header") { SectionHeader(stringResource(R.string.stats_library_overview), icon = "LibraryBig", modifier = Modifier.padding(top = 4.dp)) }
    item(key = "lib-summary") { SummaryCard(l.summary) { onRetry(StatsChart.LIB_SUMMARY) } }
    item(key = "lib-formats") {
        val total = (l.formats as? Load.Ready)?.data?.sumOf { it.count } ?: 0.0
        val other = stringResource(R.string.stats_other)
        RankedCard(stringResource(R.string.stats_formats_title), "ChartPie", l.formats, { onRetry(StatsChart.LIB_FORMATS) }) { items ->
            val c = OttershelfTheme.colors
            RankedBars(
                remember(items, other) { formatRows(items.map { it.format to it.count }, other) },
                valueLabel = { stringResource(R.string.stats_value_share, StatsMath.compact(it.value), share(it.value, total)) },
                colorOf = { item, _ -> if (item.other) Color.Unspecified else c.formatPill(item.key) },
            )
        }
    }
    item(key = "lib-storage") {
        val other = stringResource(R.string.stats_other)
        RankedCard(stringResource(R.string.stats_storage_title), "HardDrive", l.storage, { onRetry(StatsChart.LIB_STORAGE) }) { items ->
            val c = OttershelfTheme.colors
            RankedBars(
                remember(items, other) { formatRows(items.map { it.format to it.sizeBytes }, other) },
                valueLabel = { StatsMath.bytes(it.value) },
                colorOf = { item, _ -> if (item.other) Color.Unspecified else c.formatPill(item.key) },
            )
        }
    }
    item(key = "lib-added") { AddedCard(state) { onRetry(StatsChart.LIB_ADDED) } }
    item(key = "lib-authors") {
        RankedCard(stringResource(R.string.stats_authors_title), "Users", l.authors, { onRetry(StatsChart.LIB_AUTHORS) }) { items ->
            RankedBars(items.sortedByDescending { it.count }.take(10).map { RankedItem(it.name, it.count) }, valueLabel = { LocalContext.current.resources.books(it.value.roundToInt()) })
        }
    }
    item(key = "lib-series") {
        RankedCard(stringResource(R.string.stats_series_title), "Layers", l.series, { onRetry(StatsChart.LIB_SERIES) }) { items ->
            RankedBars(items.sortedByDescending { it.count }.take(10).map { RankedItem(it.name, it.count) }, valueLabel = { LocalContext.current.resources.books(it.value.roundToInt()) })
        }
    }
    item(key = "lib-genres") {
        RankedCard(stringResource(R.string.stats_genre_dist_title), "Tag", l.genres, { onRetry(StatsChart.LIB_GENRES) }) { items ->
            RankedBars(items.sortedByDescending { it.count }.take(10).map { RankedItem(it.genre, it.count) }, valueLabel = { LocalContext.current.resources.books(it.value.roundToInt()) })
        }
    }
    item(key = "lib-languages") {
        val other = stringResource(R.string.stats_other)
        val total = (l.languages as? Load.Ready)?.data?.sumOf { it.count } ?: 0.0
        RankedCard(stringResource(R.string.stats_languages_title), "Globe", l.languages, { onRetry(StatsChart.LIB_LANGUAGES) }) { items ->
            val rows = remember(items, other) {
                val ranked = items.map { if (it.language == SERVER_OTHER) RankedItem(other, it.count, other = true) else RankedItem(languageName(it.language), it.count) }
                StatsMath.topWithOther(ranked, 6, other)
            }
            RankedBars(rows, valueLabel = { stringResource(R.string.stats_value_share, StatsMath.compact(it.value), share(it.value, total)) })
        }
    }
    item(key = "lib-decades") { DecadesCard(state) { onRetry(StatsChart.LIB_DECADES) } }
    item(key = "lib-largest") {
        RankedCard(stringResource(R.string.stats_largest_title), "HardDrive", l.largest, { onRetry(StatsChart.LIB_LARGEST) }) { items ->
            val rows = items.take(10).map { RankedItem(it.title, it.sizeBytes, key = it.id.toString()) }
            RankedBars(
                rows,
                valueLabel = { item -> "${StatsMath.bytes(item.value)} · ${items.first { it.id.toString() == item.key }.format.uppercase()}" },
                onClick = { onOpenBook(it.key.toLong()) },
            )
        }
    }
}

private fun share(value: Double, total: Double): String = if (total > 0) (value / total * 100).roundToInt().toString() else "0"

/** The label the server gives the row it folds everything past its top ten into. */
private const val SERVER_OTHER = "Other"

/** Formats (or their storage) as bars: the server's own "Other" row in the comparison grey, last. */
private fun formatRows(rows: List<Pair<String, Double>>, otherLabel: String): List<RankedItem> = rows
    .map { (format, value) -> if (format == SERVER_OTHER) RankedItem(otherLabel, value, key = format, other = true) else RankedItem(format.uppercase(), value, key = format) }
    .sortedBy { it.other }

/** "en" -> "English" (in the user's language); unknown codes stay as they are. */
private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() && it != code }?.replaceFirstChar { it.titlecase() } ?: code

/** A list chart's card: ranked bars, nothing-here when the list is empty. */
@Composable
private fun <T> RankedCard(
    title: String,
    icon: String,
    load: Load<List<T>>,
    onRetry: () -> Unit,
    content: @Composable (List<T>) -> Unit,
) {
    ChartCard(
        title = title,
        icon = icon,
        load = load,
        onRetry = onRetry,
        placeholderHeight = 200.dp,
        isEmpty = { it.isEmpty() },
        emptyMessage = stringResource(R.string.stats_library_empty),
    ) { items -> content(items) }
}

/** The web's summary card as a grid of figures. */
@Composable
private fun SummaryCard(load: Load<LibrarySummary>, onRetry: () -> Unit) {
    when (load) {
        is Load.Ready -> {
            val s = load.data
            val published = if (s.publicationYearMin != null && s.publicationYearMax != null) "${s.publicationYearMin}–${s.publicationYearMax}" else "–"
            val tiles = listOf(
                Triple("BookOpen", StatsMath.compact(s.totalBooks), R.string.stats_lib_books),
                Triple("Users", StatsMath.compact(s.totalAuthors), R.string.stats_lib_authors),
                Triple("Layers", StatsMath.compact(s.totalSeries), R.string.stats_lib_series),
                Triple("Tag", StatsMath.compact(s.totalGenres), R.string.stats_lib_genres),
                Triple("Globe", StatsMath.compact(s.totalLanguages), R.string.stats_lib_languages),
                Triple("HardDrive", StatsMath.bytes(s.totalStorageBytes), R.string.stats_lib_storage),
                Triple("Sparkles", StatsMath.compact(s.booksAddedThisYear), R.string.stats_lib_added),
                Triple("CalendarDays", published, R.string.stats_lib_published),
            )
            // Faded while another library's figures load, as the chart cards are.
            Column(Modifier.alpha(if (load.stale) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (icon, value, label) -> StatBox(icon, value, stringResource(label), Modifier.weight(1f), compact = true) }
                    }
                }
            }
        }
        else -> ChartCard(stringResource(R.string.stats_library_overview), "LibraryBig", load, onRetry, placeholderHeight = 160.dp) { }
    }
}

/** Books added per month over the last five years. */
@Composable
private fun AddedCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val locale = Locale.getDefault()
    ChartCard(
        title = stringResource(R.string.stats_added_title),
        icon = "TrendingUp",
        subtitle = stringResource(R.string.stats_added_subtitle),
        load = state.library.added,
        onRetry = onRetry,
        isEmpty = { months -> months.all { it.count <= 0 } },
        emptyMessage = stringResource(R.string.stats_library_empty),
    ) { months ->
        var selected by rememberSaveable(months.size) { mutableIntStateOf(-1) }
        val sel = months.getOrNull(selected)
        Readout(
            value = sel?.let { res.books(it.count.roundToInt()) },
            label = sel?.let { "${Month.of(it.month).getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${it.year}" } ?: "",
            hint = stringResource(R.string.stats_tap_hint),
        )
        ColumnChart(
            values = months.map { it.count },
            labels = months.map { if (it.month == 1) it.year.toString() else null },
            selected = selected,
            onSelect = { selected = it },
            formatY = { StatsMath.compact(it) },
            integerTicks = true,
            description = stringResource(R.string.stats_added_title),
        )
    }
}

/** Books by publication decade. */
@Composable
private fun DecadesCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    ChartCard(
        title = stringResource(R.string.stats_decades_title),
        icon = "CalendarRange",
        load = state.library.decades,
        onRetry = onRetry,
        isEmpty = { it.isEmpty() },
        emptyMessage = stringResource(R.string.stats_library_empty),
    ) { rows ->
        // Every decade in between, so a gap reads as one; far-off outliers share a first column.
        val decades = remember(rows) { StatsMath.decadeColumns(rows) }
        val labels = decades.map { if (it.earlier) stringResource(R.string.stats_decade_before, it.decade) else stringResource(R.string.stats_decade, it.decade) }
        val values = remember(decades) { decades.map { it.count } }
        var selected by rememberSaveable(decades.size) { mutableIntStateOf(-1) }
        val sel = decades.getOrNull(selected)
        Readout(
            value = sel?.let { res.books(it.count.roundToInt()) },
            label = labels.getOrNull(selected) ?: "",
            hint = stringResource(R.string.stats_tap_hint),
        )
        ColumnChart(
            values = values,
            labels = labels,
            selected = selected,
            onSelect = { selected = it },
            formatY = { StatsMath.compact(it) },
            integerTicks = true,
            description = stringResource(R.string.stats_decades_title),
        )
    }
}
