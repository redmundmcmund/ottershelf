package io.github.ottershelf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.ui.theme.OttershelfColors
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.oklch
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A book's 2:3 cover box, as the web and the Nexus grid size it. */
const val COVER_ASPECT = 2f / 3f

private val WHITESPACE = Regex("\\s+")

/**
 * The thumbnail to load for [book]: its versioned URL (`?t=`, cached for good by the image loader),
 * or null when it has no cover (the placeholder shows).
 */
fun Api.coverModel(book: BookCard): Any? = if (book.hasCover) thumbnailUrl(book) else null

/**
 * A book cover: [model] (a URL such as [Api.coverModel], a File for a downloaded book, or null)
 * loaded with Coil in a 2:3 box, clipped to [shape] (the web's `rounded-sm`), over the web's cover
 * surface while it loads. With no cover, or if it fails to load, the web's generated placeholder
 * shows instead: a gradient in the accent's hues with the title (initials on small covers).
 *
 * [overlay] draws on top, clipped with the cover (status, series and format badges, progress).
 * Give the size through [modifier] (a width, or a fixed size for shelves).
 *
 * [requestWidth], when given, is the cover's width on screen: the image is asked for at that size
 * at once (not after layout), so a grid whose cells change size (its column count) asks for each
 * cover at the new size and shows the one it had meanwhile ([sizedCoverRequest]).
 */
@Composable
fun BookCover(
    model: Any?,
    title: String?,
    modifier: Modifier = Modifier,
    authors: String? = null,
    seed: String? = null,
    shape: Shape = RoundedCornerShape(OttershelfTheme.radii.sm),
    contentDescription: String? = null,
    requestWidth: Dp? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    val request = if (requestWidth != null && model != null && model !is ImageRequest) {
        val context = LocalPlatformContext.current
        val widthPx = with(LocalDensity.current) { requestWidth.roundToPx() }.coerceAtLeast(1)
        val key = remember(model) { coverMemoryKey(model) }
        remember(model, widthPx, context) { sizedCoverRequest(context, model, widthPx, key) }
    } else {
        model
    }
    Box(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(shape)
            .background(colors.coverSurface),
    ) {
        var failed by remember(model) { mutableStateOf(false) }
        if (model == null || failed) {
            BookCoverPlaceholder(title = title, authors = authors, seed = seed ?: title.orEmpty(), modifier = Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        }
        overlay()
    }
}

/**
 * A cover asked for at [widthPx] wide (2:3), as the grids ask for theirs: any cached copy at least
 * that big will do ([Precision.INEXACT]), and while a bigger one loads the cached one shows
 * ([ImageRequest.Builder.placeholderMemoryCacheKey]), so covers that grow never flash empty. [key]
 * is the memory cache key ([coverMemoryKey]).
 */
fun sizedCoverRequest(context: PlatformContext, model: Any, widthPx: Int, key: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(model)
        .size(widthPx, (widthPx * 1.5f).roundToInt().coerceAtLeast(1))
        .precision(Precision.INEXACT)
        .scale(Scale.FILL)
        .memoryCacheKey(key)
        .placeholderMemoryCacheKey(key)
        .build()

/**
 * A cover model's memory cache key: a (versioned) thumbnail URL is its own key, the one Coil gives
 * it everywhere else, so the book page finds the grid's copy; a downloaded cover's file carries its
 * modification time, so a new copy isn't hidden by the old one.
 */
internal fun coverMemoryKey(model: Any): String = when (model) {
    is File -> "${model.path}:${model.lastModified()}"
    else -> model.toString()
}

/**
 * The web's generated cover (BookCoverPlaceholder.vue, book-cover.ts): a 135° gradient whose hue,
 * lightness and chroma come from a hash of [seed] around the accent's hue, a faint lattice, an inner
 * frame with corner brackets, the title in heavy type and a divider over the author. Below 64dp
 * wide only the title's initials show (they'd be unreadable otherwise).
 */
@Composable
fun BookCoverPlaceholder(
    title: String?,
    modifier: Modifier = Modifier,
    authors: String? = null,
    seed: String = title.orEmpty(),
) {
    val colors = OttershelfTheme.colors
    val palette = remember(seed, colors.tintHue, colors.pastel, colors.isDark) { CoverPalette.of(seed, colors) }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val text = title?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.components_untitled)
    Box(
        modifier.clipToBounds().drawWithCache {
            val w = size.width
            val h = size.height
            val u = w / 200f // one unit of the web's 200x300 viewBox
            val gradient = Brush.linearGradient(listOf(palette.from, palette.to), start = Offset.Zero, end = Offset(w, h))
            val small = w < 64.dp.toPx()

            val titleLayout = if (small) {
                val initials = initials(text)
                measurer.measure(
                    initials,
                    TextStyle(
                        color = palette.text,
                        fontFamily = OttershelfFonts.Sans,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = (w * 0.34f).toSp(),
                        textAlign = TextAlign.Center,
                    ),
                    constraints = Constraints.fixedWidth(w.roundToInt().coerceAtLeast(1)),
                    maxLines = 1,
                )
            } else {
                val base = when (text.length) {
                    in 0..6 -> 40f
                    in 7..12 -> 32f
                    in 13..22 -> 24f
                    in 23..35 -> 17f
                    else -> 13f
                }
                fun style(px: Float) = TextStyle(
                    color = palette.text,
                    fontFamily = OttershelfFonts.Sans,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = px.toSp(),
                    lineHeight = (px * 1.25f).toSp(),
                    textAlign = TextAlign.Center,
                )
                // The web hyphenates a word too long for a line; shrinking the type so the longest word
                // fits reads better.
                val maxWidth = (200f - 50f) * u
                val widest = widestWord(measurer, text, style(base * u))
                // A little under the full width: the layout rounds the constraint down.
                val px = if (widest > maxWidth * 0.97f) base * u * maxWidth * 0.95f / widest else base * u
                measurer.measure(
                    text,
                    style(px),
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = maxWidth.roundToInt().coerceAtLeast(1), maxHeight = (190f * u).roundToInt().coerceAtLeast(1)),
                    maxLines = 5,
                )
            }
            val authorLayout = if (small || authors.isNullOrBlank()) null else measurer.measure(
                authors,
                TextStyle(
                    color = palette.textMuted,
                    fontFamily = OttershelfFonts.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (11f * u).toSp(),
                    letterSpacing = 0.05.em,
                    textAlign = TextAlign.Center,
                ),
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = ((200f - 50f) * u).roundToInt().coerceAtLeast(1)),
                maxLines = 1,
            )

            onDrawBehind {
                drawRect(gradient)
                if (!small) {
                    // Diamond lattice, the accent at 15%.
                    val lattice = palette.accent.copy(alpha = 0.15f)
                    for (i in 1..10) {
                        drawLine(lattice, Offset((-50f + i * 40f) * u, 0f), Offset((250f + i * 40f) * u, h), strokeWidth = 0.5f * u)
                        drawLine(lattice, Offset((250f - i * 40f) * u, 0f), Offset((-50f - i * 40f) * u, h), strokeWidth = 0.5f * u)
                    }
                    // Inner frame and corner brackets, the accent at 60%.
                    val frame = palette.accent.copy(alpha = 0.6f)
                    val m = 10f * u
                    drawRect(frame, Offset(m + 5f * u, m + 5f * u), Size(w - 2 * (m + 5f * u), h - 2 * (m + 5f * u)), style = Stroke(0.8f * u))
                    val arm = 15f * u
                    val bracket = Stroke(2f * u)
                    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                        drawLine(frame, Offset(x, y + dy * arm), Offset(x, y), bracket.width)
                        drawLine(frame, Offset(x, y), Offset(x + dx * arm, y), bracket.width)
                    }
                    corner(m, m, 1f, 1f)
                    corner(w - m, m, -1f, 1f)
                    corner(w - m, h - m, -1f, -1f)
                    corner(m, h - m, 1f, -1f)
                    // Title centred in the upper zone (y 18-220), then the divider at 236.
                    val titleCenter = (18f + 220f) / 2f * (h / 300f)
                    drawText(titleLayout, topLeft = Offset((w - titleLayout.size.width) / 2f, titleCenter - titleLayout.size.height / 2f))
                    val divider = palette.accent.copy(alpha = 0.8f)
                    val dy = 236f * (h / 300f)
                    drawCircle(divider, 3f * u, Offset(w / 2f, dy))
                    drawLine(divider, Offset(w / 2f - 35f * u, dy), Offset(w / 2f - 8f * u, dy), strokeWidth = u)
                    drawLine(divider, Offset(w / 2f + 8f * u, dy), Offset(w / 2f + 35f * u, dy), strokeWidth = u)
                    authorLayout?.let {
                        drawText(it, topLeft = Offset((w - it.size.width) / 2f, dy + 10f * u))
                    }
                } else {
                    drawText(titleLayout, topLeft = Offset((w - titleLayout.size.width) / 2f, (h - titleLayout.size.height) / 2f))
                }
            }
        },
    )
}

/**
 * The width in px of [text]'s widest word in [style], on one line. One layout for all the words, a
 * word a line: unwrapped, a layout is as wide as its widest line, so this is the widest word's width
 * as its own layout would give it, without one layout per word (a placeholder builds this on every
 * size change, a pinch step included). 0 when there are no words.
 */
internal fun widestWord(measurer: TextMeasurer, text: String, style: TextStyle): Int {
    val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return 0
    return measurer.measure(words.joinToString("\n"), style, softWrap = false).size.width
}

/** Up to two initials of a title's words ("The Lost World" gives "TL"). */
internal fun initials(title: String): String =
    title.split(WHITESPACE).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { word -> (word.firstOrNull { it.isLetterOrDigit() } ?: word.first()).uppercase() }
        .ifEmpty { "?" }

/** The web's `bookCoverPalette` (book-cover.ts), for the accent's hue and tone and light or dark. */
internal data class CoverPalette(val from: Color, val to: Color, val text: Color, val accent: Color, val textMuted: Color) {
    companion object {
        private val HUE_OFFSETS = intArrayOf(-36, -24, -12, 0, 12, 24, 36)

        /** djb2, as the web computes it (UTF-16 units, unsigned 32-bit). */
        internal fun seedHash(seed: String): Long {
            var h = 5381L
            for (ch in seed) h = ((h shl 5) + h + ch.code) and 0xFFFFFFFFL
            return h
        }

        fun of(seed: String, colors: OttershelfColors): CoverPalette {
            val n = seedHash(seed)
            val hue = Math.floorMod((colors.tintHue.roundToInt() + HUE_OFFSETS[(n % HUE_OFFSETS.size).toInt()]), 360).toFloat()
            val n2 = ((n ushr 8) and 0xFF).toInt()
            val n3 = ((n ushr 16) and 0xFF).toInt()
            val pastel = colors.pastel
            val dark = colors.isDark
            val baseL = if (pastel) (if (dark) 0.56f else 0.66f) + (n2 % 5) * 0.03f else (if (dark) 0.46f else 0.45f) + (n2 % 6) * 0.05f
            val baseC = if (pastel) (if (dark) 0.05f else 0.04f) + (n3 % 4) * 0.012f else (if (dark) 0.11f else 0.12f) + (n3 % 5) * 0.02f
            val toL = baseL - if (pastel) 0.16f else 0.2f
            val accentL = min(0.95f, baseL + if (pastel) 0.2f else 0.35f)
            val accentC = max(0.02f, baseC - if (pastel) 0.02f else 0.08f)
            return CoverPalette(
                from = oklch(baseL, baseC, hue),
                to = oklch(toL, baseC, hue),
                text = oklch(0.99f, 0.01f, hue),
                accent = oklch(accentL, accentC, hue),
                textMuted = oklch(0.9f, if (pastel) 0.012f else 0.02f, hue),
            )
        }
    }
}
