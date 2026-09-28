package io.github.ottershelf.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.feature.home.model.ShelfConfig
import io.github.ottershelf.feature.home.model.ShelfType
import io.github.ottershelf.feature.home.model.chunkIntoBands
import io.github.ottershelf.feature.home.model.effectiveShelfRows
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.components.ShelfCover
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.theme.OttershelfTheme

private val SHELF_COVER_WIDTH = 120.dp
private val SHELF_COVER_HEIGHT = 180.dp
private val BAND_GAP = 14.dp

/** A shelf type's name (the web's `shelfNames`). */
internal fun shelfName(type: ShelfType): Int = when (type) {
    ShelfType.CONTINUE_READING -> R.string.home_shelf_continue_reading
    ShelfType.WANT_TO_READ -> R.string.home_shelf_want_to_read
    ShelfType.UP_NEXT_IN_SERIES -> R.string.home_shelf_up_next
    ShelfType.RECENTLY_ADDED -> R.string.home_recently_added
    ShelfType.RANDOM -> R.string.home_shelf_random
    ShelfType.SMART_SCOPE -> R.string.home_shelf_smart_scope
}

/** A shelf's title: a Smart Scope shelf's scope name, else its type's name. */
@Composable
internal fun shelfTitle(config: ShelfConfig): String =
    config.label.takeIf { config.shelfType == ShelfType.SMART_SCOPE && it.isNotBlank() } ?: stringResource(shelfName(config.shelfType))

private fun shelfEmpty(type: ShelfType): Int = when (type) {
    ShelfType.CONTINUE_READING -> R.string.home_shelf_empty_continue
    ShelfType.WANT_TO_READ -> R.string.home_shelf_empty_want
    ShelfType.UP_NEXT_IN_SERIES -> R.string.home_shelf_empty_up_next
    ShelfType.RECENTLY_ADDED -> R.string.home_recently_added_empty
    ShelfType.SMART_SCOPE -> R.string.home_shelf_empty_scope
    ShelfType.RANDOM -> R.string.home_shelf_empty_default
}

/**
 * A shelf (the web's DashboardScroller, the Nexus Recently Added card): the framed icon, its name
 * and a count pill, then its covers scrolling sideways in 1..3 rows ([compact]: two at most),
 * placeholders until they arrive, its tap-to-retry if it failed, or the web's empty line.
 */
@Composable
internal fun ShelfCard(
    shelf: ShelfUi,
    compact: Boolean,
    onRetry: () -> Unit,
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // The header line (a long press on it arranges the cards).
    headerModifier: Modifier = Modifier,
    // A long press on a cover: the quick view (feature.library).
    onQuickView: ((ShelfItem) -> Unit)? = null,
) {
    val type = shelf.config.shelfType
    val books = shelf.books
    val rows = effectiveShelfRows(shelf.config.rows, compact)
    val title = shelfTitle(shelf.config)
    DashCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 14.dp, bottom = 16.dp)) {
        SectionHeader(
            title = title,
            icon = type.icon,
            // As the web counts: what's on the shelf.
            count = books?.size?.takeIf { it > 0 },
            modifier = Modifier.padding(horizontal = 14.dp).then(headerModifier),
        )
        Spacer(Modifier.height(14.dp))
        when {
            books != null && books.isNotEmpty() -> {
                val bands = remember(books, rows) { chunkIntoBands(books, rows) }
                val columns = bands.first().size
                // From the first cover after each load, unless the user has scrolled it.
                val ids = remember(bands) { bands.map { band -> band.map { it.book.id } } }
                // Inset by the card's 1dp edge, so covers scrolling past are cut off inside it.
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 1.dp),
                    state = rememberDashboardListState(ids),
                    contentPadding = PaddingValues(horizontal = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // A column holds the n-th book of each band, so every band scrolls together.
                    items(columns) { i ->
                        Column(verticalArrangement = Arrangement.spacedBy(BAND_GAP)) {
                            bands.forEach { band ->
                                val item = band.getOrNull(i)
                                if (item != null) {
                                    // The server's statuses, not this device's overrides: every status
                                    // set here reloads this.
                                    ShelfCover(
                                        item.book,
                                        item.cover,
                                        onClick = { onOpenBook(item.book.id) },
                                        width = SHELF_COVER_WIDTH,
                                        onLongClick = onQuickView?.let { { it(item) } },
                                    )
                                } else {
                                    Spacer(Modifier.size(SHELF_COVER_WIDTH, SHELF_COVER_HEIGHT))
                                }
                            }
                        }
                    }
                }
            }
            books != null -> EmptyState(
                stringResource(shelfEmpty(type)),
                Modifier.fillMaxWidth(),
                icon = type.icon,
                compact = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            )
            shelf.failed -> CardFailed(onRetry, Modifier.fillMaxWidth().height(SHELF_COVER_HEIGHT / 2))
            else -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).horizontalScroll(rememberScrollState(), enabled = false),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(PLACEHOLDERS) {
                    Column(verticalArrangement = Arrangement.spacedBy(BAND_GAP)) {
                        repeat(rows) { SkeletonBox(Modifier.size(SHELF_COVER_WIDTH, SHELF_COVER_HEIGHT)) }
                    }
                }
            }
        }
    }
}

/** When every shelf is off: the web's line and a way to Customise. */
@Composable
internal fun NoShelves(onCustomise: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        EmptyState(
            stringResource(R.string.home_no_shelves),
            icon = "LayoutDashboard",
            compact = true,
            contentPadding = PaddingValues(0.dp),
            action = {
                androidx.compose.material3.Text(
                    stringResource(R.string.home_customise),
                    modifier = Modifier.padding(4.dp).clickable(onClick = onCustomise),
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = OttershelfTheme.colors.primary,
                )
            },
        )
    }
}

private const val PLACEHOLDERS = 4
