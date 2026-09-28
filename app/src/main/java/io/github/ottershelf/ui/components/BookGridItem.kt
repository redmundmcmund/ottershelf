package io.github.ottershelf.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * Minimum cell width of every book grid: the column count follows the width (Nexus
 * `grid_cell_min_width`). The Nexus 140dp gave its 7" tablet four columns; on a ~411dp phone this
 * gives three (two would make every cover as wide as half the screen), and about seven in landscape.
 */
val BOOK_GRID_MIN_CELL: Dp = 120.dp

/** The gap around a grid's cells (Nexus `grid_spacing`); each cell pads itself by the same again. */
val BOOK_GRID_SPACING: Dp = 6.dp

/**
 * A book in a grid, as the Nexus grid draws it (item_book.xml): the cover with its series number
 * top right, its read status as a disc at the bottom right (30% of the cover's width) and reading
 * progress along the bottom edge; the title (one line, medium) and authors (one line, dim) under
 * it. The cell pads itself 6dp and tints with the accent while pressed.
 *
 * [cover] is what to load ([io.github.ottershelf.core.network.Api.coverModel], or a File for a
 * downloaded book). [status] defaults to the book's own; pass the ReadingChanges override when
 * there is one. [showFormat] adds the web's format chip at the bottom left. [onLongClick], when
 * given, handles a long press on the cell (the quick view, say). [coverWidth], when given, is the
 * cover's width on screen (the grid's cell less its padding): the cover is asked for at that size
 * at once (see [BookCover]), so a grid that changes its column count asks for covers at their new size.
 */
@Composable
fun BookGridItem(
    book: BookCard,
    cover: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: ReadStatus? = ReadStatus.of(book.readStatus?.status),
    showFormat: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    coverWidth: Dp? = null,
) {
    val colors = OttershelfTheme.colors
    val title = book.title ?: stringResource(R.string.components_untitled)
    val authors = remember(book.authors) { book.authors.joinToString(", ") }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            // The pressed wash's corners (Nexus grid_item_bg: 8dp), capped so "pill" can't clip the text.
            .clip(RoundedCornerShape(OttershelfTheme.radii.md.coerceAtMost(8.dp)))
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(color = colors.primary),
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(6.dp),
    ) {
        BookCover(
            model = cover,
            title = book.title,
            authors = authors,
            seed = book.title ?: book.id.toString(),
            modifier = Modifier.fillMaxWidth(),
            contentDescription = null,
            requestWidth = coverWidth,
        ) {
            book.seriesIndex?.let {
                SeriesBadge(it, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            if (showFormat) {
                primaryOpenableFormat(book.files)?.let {
                    FormatBadge(it, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 8.dp))
                }
            }
            // 30% of the cover's width, so it scales with the column count.
            StatusBadge(
                status = status,
                size = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-5).dp, y = (-9).dp)
                    .fillMaxWidth(0.3f),
            )
            CoverProgressBar(
                progress = ((book.readingProgress ?: 0.0) / 100.0).toFloat(),
                read = status == ReadStatus.READ,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = authors,
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A cover on a horizontal shelf (the Nexus Recently Added, item_shelf_book.xml; the web's
 * DashboardScroller): [width] wide at 2:3, read status top left, series number top right, format
 * bottom right, progress along the bottom. No text: the title is its content description.
 * [onLongClick], when given, handles a long press on the cover (the quick view).
 */
@Composable
fun ShelfCover(
    book: BookCard,
    cover: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    status: ReadStatus? = ReadStatus.of(book.readStatus?.status),
    onLongClick: (() -> Unit)? = null,
) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.sm)
    BookCover(
        model = cover,
        title = book.title,
        authors = book.authors.joinToString(", "),
        seed = book.title ?: book.id.toString(),
        shape = shape,
        contentDescription = book.title,
        modifier = modifier
            .size(width, width * 1.5f)
            .clip(shape)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(indication = ripple(color = colors.primary), interactionSource = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        interactionSource = null,
                        indication = ripple(color = colors.primary),
                        role = Role.Button,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                },
            ),
    ) {
        StatusBadge(status, Modifier.align(Alignment.TopStart).padding(6.dp), size = 24.dp)
        book.seriesIndex?.let { SeriesBadge(it, Modifier.align(Alignment.TopEnd).padding(6.dp), small = true) }
        primaryOpenableFormat(book.files)?.let {
            FormatBadge(it, Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 8.dp))
        }
        CoverProgressBar(
            progress = ((book.readingProgress ?: 0.0) / 100.0).toFloat(),
            read = status == ReadStatus.READ,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
