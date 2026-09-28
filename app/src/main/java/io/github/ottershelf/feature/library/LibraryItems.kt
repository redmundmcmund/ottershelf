package io.github.ottershelf.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.github.ottershelf.R
import io.github.ottershelf.core.model.AuthorSummary
import io.github.ottershelf.core.model.SeriesSummary
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.ceil
import kotlin.math.sin

/**
 * An author as a round portrait with the name under it (item_author.xml, the web's author tiles):
 * initials on a gradient of the author's own hue when there's no portrait (or it fails).
 */
@Composable
internal fun AuthorTile(author: AuthorSummary, portrait: Any?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val resources = LocalResources.current
    val gradient = remember(author.name) { authorGradient(author.name) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(OttershelfTheme.radii.md.coerceAtMost(8.dp)))
            .clickable(indication = ripple(color = colors.primary), interactionSource = null, role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(gradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = authorInitials(author.name),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.95f),
            )
            var failed by remember(portrait) { mutableStateOf(false) }
            if (portrait != null && !failed) {
                AsyncImage(
                    model = portrait,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = author.name,
            style = MaterialTheme.typography.titleSmall,
            color = colors.foreground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = resources.getQuantityString(R.plurals.library_book_count, author.bookCount, author.bookCount),
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Up to two initials: first and last word, as the web does (Nexus AuthorsFragment.initials). */
internal fun authorInitials(name: String): String {
    val words = name.split(' ', '.', '-').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/** A soft two-tone gradient whose hue comes from the name, so each author keeps theirs. */
private fun authorGradient(name: String): Brush {
    val hue = (((name.hashCode() % 360) + 360) % 360).toFloat()
    val from = Color.hsv(hue, 0.45f, 0.62f)
    val to = Color.hsv((hue + 40f) % 360f, 0.55f, 0.38f)
    return Brush.linearGradient(listOf(from, to))
}

/**
 * A series card (item_series.xml): its first covers fanned out, the name, the authors and how
 * much of it has been read, on the card colour with the lg radius.
 */
@Composable
internal fun SeriesCard(series: SeriesSummary, coverOf: (Long) -> Any?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val resources = LocalResources.current
    // The same list on every pass, so the fan skips when a page arrives (a new list each time never would).
    val covers = remember(series.coverBookIds) { series.coverBookIds.take(3).map(coverOf) }
    Column(
        modifier = modifier
            .padding(5.dp)
            .clip(OttershelfTheme.radii.lgShape)
            .background(colors.card)
            .clickable(indication = ripple(color = colors.primary), interactionSource = null, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, top = 14.dp, end = 12.dp, bottom = 12.dp),
    ) {
        CoverFan(
            covers = covers,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = series.name,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
            color = colors.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = when {
                series.authors.size > 2 -> series.authors.take(2).joinToString(", ") + " +${series.authors.size - 2}"
                else -> series.authors.joinToString(", ")
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillProgressBar(
                progress = series.readCount.toFloat() / series.bookCount.coerceAtLeast(1),
                modifier = Modifier.weight(1f),
                height = 4.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = when {
                    series.bookCount > 0 && series.readCount >= series.bookCount -> resources.getString(R.string.library_series_all_read)
                    series.readCount > 0 -> resources.getString(R.string.library_series_read_of, series.readCount, series.bookCount)
                    else -> resources.getQuantityString(R.plurals.library_book_count, series.bookCount, series.bookCount)
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.mutedForeground,
                maxLines = 1,
            )
        }
    }
}

private const val FAN_FRONT_WIDTH = 0.42f
private const val FAN_SPREAD = 0.46f
private const val FAN_TILT = 7f
private const val FAN_BACK_SCALE = 0.86f
private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * Up to three covers fanned out, the first in front (Nexus CoverFanView, the web's series
 * picture): [covers] in series order, the second tilted back left and the third back right. The
 * height is fixed by the width, so the grid never re-lays out as covers arrive; the front stays
 * as an empty cover when there's none, so every card has the same shape.
 */
@Composable
internal fun CoverFan(covers: List<Any?>, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            // Drawn back left, back right, front, so the front one is on top.
            if (covers.size > 1) FanCover(covers[1], Modifier.layoutId(FanSlot.Left))
            if (covers.size > 2) FanCover(covers[2], Modifier.layoutId(FanSlot.Right))
            FanCover(covers.firstOrNull(), Modifier.layoutId(FanSlot.Front))
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val coverW = (width * FAN_FRONT_WIDTH).toInt()
        val coverH = coverW * 3 / 2
        // Room below for the tilted back covers' outer corners, which dip under their own bottom edge.
        val overhang = ceil(coverW * FAN_BACK_SCALE / 2 * sin(Math.toRadians(FAN_TILT.toDouble()))).toInt() + 1
        val fixed = Constraints.fixed(coverW, coverH)
        val placed = measurables.map { it.layoutId to it.measure(fixed) }
        layout(width, coverH + overhang) {
            val center = (width - coverW) / 2
            val shift = (coverW * FAN_SPREAD).toInt()
            for ((slot, placeable) in placed) {
                when (slot) {
                    FanSlot.Left -> placeable.placeWithLayer(center - shift, 0) {
                        rotationZ = -FAN_TILT
                        scaleX = FAN_BACK_SCALE
                        scaleY = FAN_BACK_SCALE
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    FanSlot.Right -> placeable.placeWithLayer(center + shift, 0) {
                        rotationZ = FAN_TILT
                        scaleX = FAN_BACK_SCALE
                        scaleY = FAN_BACK_SCALE
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    else -> placeable.place(center, 0)
                }
            }
        }
    }
}

private enum class FanSlot { Left, Right, Front }

/** One fanned cover: 4dp corners over the cover surface; its URL cached for a day (no version to go by). */
@Composable
private fun FanCover(model: Any?, modifier: Modifier) {
    val context = LocalPlatformContext.current
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(OttershelfTheme.colors.coverSurface),
    ) {
        if (model != null) {
            val request = remember(model, context) {
                val key = "$model#" + System.currentTimeMillis() / DAY_MS
                ImageRequest.Builder(context).data(model).memoryCacheKey(key).diskCacheKey(key).build()
            }
            AsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
