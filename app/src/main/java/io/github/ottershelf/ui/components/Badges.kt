package io.github.ottershelf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.formatSeriesIndex
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfColors
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme

/** The web's READER_OPENABLE_FORMATS (reader-settings.ts): what the format badge picks from. */
val OPENABLE_FORMATS = setOf(
    "epub", "mobi", "azw3", "azw", "fb2", "pdf", "cbz", "cbr", "cb7",
    "m4b", "mp3", "m4a", "opus", "ogg", "flac",
)

/** As the web badges a book: the primary one among the files a reader opens, else the first of them. */
fun primaryOpenableFormat(files: List<BookFile>): String? {
    val readable = files.filter { it.format?.lowercase() in OPENABLE_FORMATS }
    return (readable.firstOrNull { it.role == "primary" } ?: readable.firstOrNull())?.format
}

/**
 * A file format over a cover ("EPUB"): white caps on the format's colour at 90%
 * (format-colors.ts), as on the web's cards and the Nexus shelf.
 */
@Composable
fun FormatBadge(format: String, modifier: Modifier = Modifier) {
    Text(
        text = format.uppercase(),
        modifier = modifier
            .background(OttershelfColors.formatBadge(format).copy(alpha = 0.9f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        style = TextStyle(
            fontFamily = OttershelfFonts.Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            letterSpacing = 0.08.em,
            color = Color.White,
        ),
        maxLines = 1,
    )
}

/**
 * A format as a text chip (the book page's file list): the format's per-theme pill colour as text
 * on a faint fill of itself.
 */
@Composable
fun FormatChip(format: String, modifier: Modifier = Modifier) {
    val color = OttershelfTheme.colors.formatPill(format)
    Text(
        text = format.uppercase(),
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(OttershelfTheme.radii.sm.coerceAtLeast(4.dp)))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = TextStyle(
            fontFamily = OttershelfFonts.Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            letterSpacing = 0.06.em,
            color = color,
        ),
        maxLines = 1,
    )
}

/** A read status's Lucide icon in its colour (the web's STATUS_ICONS / STATUS_COLORS). */
@Composable
fun StatusIcon(status: ReadStatus, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    LucideIcon(
        name = status.lucideIcon,
        contentDescription = null,
        modifier = modifier,
        tint = OttershelfTheme.colors.readStatus(status),
        size = size,
    )
}

/**
 * The read-status disc on a cover: the status icon in its colour on a dark disc, [size] across with
 * the icon inset 22% (the shelf uses 24dp). With [size] null the disc takes its width from
 * [modifier] (the Nexus grid gives it 30% of the cover's width: `Modifier.fillMaxWidth(0.3f)`).
 * Nothing is drawn for unread, as on the web.
 */
@Composable
fun StatusBadge(status: ReadStatus?, modifier: Modifier = Modifier, size: Dp? = 24.dp) {
    if (status == null || status == ReadStatus.UNREAD) return
    Box(
        modifier = modifier
            .then(if (size != null) Modifier.size(size) else Modifier)
            .aspectRatio(1f)
            .background(OttershelfTheme.colors.statusDisc, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(
            name = status.lucideIcon,
            contentDescription = status.label,
            modifier = Modifier.fillMaxSize(1f - 2 * 0.22f),
            tint = OttershelfTheme.colors.readStatus(status),
            size = Dp.Unspecified,
        )
    }
}

/** A book's position in its series over a cover ("#3"): white on a dark pill. [small] for shelves. */
@Composable
fun SeriesBadge(index: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = stringResource(R.string.components_series_position, formatSeriesIndex(index)),
        modifier = modifier
            .background(OttershelfTheme.colors.overlayPill, CircleShape)
            .padding(horizontal = if (small) 6.dp else 7.dp, vertical = 1.dp),
        style = TextStyle(
            fontFamily = OttershelfFonts.Sans,
            fontWeight = FontWeight.Medium,
            fontSize = if (small) 10.sp else 11.sp,
            lineHeight = if (small) 14.sp else 15.sp,
            color = Color.White,
        ),
        maxLines = 1,
    )
}

/**
 * A count beside a title (the Nexus shelf's pill, the web's `rounded-full border bg-muted
 * text-[11px] font-bold`).
 */
@Composable
fun CountPill(count: String, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Text(
        text = count,
        modifier = modifier
            .background(colors.muted, CircleShape)
            .border(1.dp, colors.border, CircleShape)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        style = TextStyle(
            fontFamily = OttershelfFonts.Sans,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = colors.countForeground,
        ),
        maxLines = 1,
    )
}
