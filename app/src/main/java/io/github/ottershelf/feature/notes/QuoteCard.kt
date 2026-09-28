package io.github.ottershelf.feature.notes

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.ottershelf.R
import io.github.ottershelf.feature.book.CoverTint
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme

/** The card's shape: 1:1 (a post), 4:5 (a tall post) or 9:16 (a story). Exported 1080 px wide. */
enum class CardAspect(val w: Int, val h: Int, val label: String) {
    SQUARE(1, 1, "1:1"),
    PORTRAIT(4, 5, "4:5"),
    STORY(9, 16, "9:16"),
}

/** The card's background: the theme's page colour, a gradient from the cover's tint, or the cover blurred. */
enum class CardBackground { THEME, TINT, BLUR }

/** The largest the quote's text may be (it shrinks to fit). */
enum class CardTextSize(val maxSp: Int) { SMALL(18), MEDIUM(24), LARGE(32) }

/** The cover's tint ([CoverTint], as the book page's wash) and a blurred copy, for the backgrounds. */
@Immutable
data class CoverArt(val tint: Color? = null, val blurred: ImageBitmap? = null)

private val DESIGN_WIDTH = 360.dp
private const val EXPORT_WIDTH_PX = 1080

/** A shareable image of one highlight, full screen: the card, its options, Save and Share. */
@Composable
internal fun QuoteCardDialog(note: Annotation, title: String?, author: String?, cover: Any?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val art = rememberCoverArt(cover)
        val context = LocalContext.current
        val resources = LocalResources.current
        val scope = rememberCoroutineScope()
        val layer = rememberGraphicsLayer()
        var status by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        fun capture(block: suspend (Bitmap) -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    val bitmap = layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                    block(bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    status = resources.getString(R.string.notes_image_failed)
                } finally {
                    busy = false
                }
            }
        }
        val name = "Ottershelf quote ${note.id}"
        QuoteCardContent(
            note = note,
            title = title,
            author = author,
            cover = cover,
            art = art,
            layer = layer,
            status = status,
            busy = busy,
            onClose = onDismiss,
            onShare = { capture { NotesShare.shareImage(context, it, name, resources.getString(R.string.notes_share_image)) } },
            onSave = {
                capture {
                    val ok = NotesShare.saveImage(context, it, name)
                    status = resources.getString(if (ok) R.string.notes_image_saved else R.string.notes_image_failed)
                }
            },
        )
    }
}

/** Stateless: the preview (recorded into [layer] at export size) and the options. */
@Composable
internal fun QuoteCardContent(
    note: Annotation,
    title: String?,
    author: String?,
    cover: Any?,
    art: CoverArt,
    layer: GraphicsLayer?,
    status: String? = null,
    busy: Boolean = false,
    onClose: () -> Unit = {},
    onShare: () -> Unit = {},
    onSave: () -> Unit = {},
    initialAspect: CardAspect = CardAspect.PORTRAIT,
    initialBackground: CardBackground = CardBackground.TINT,
) {
    val colors = OttershelfTheme.colors
    var aspect by rememberSaveable { mutableStateOf(initialAspect) }
    var background by rememberSaveable { mutableStateOf(initialBackground) }
    var size by rememberSaveable { mutableStateOf(CardTextSize.MEDIUM) }
    var withNote by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(title = stringResource(R.string.notes_card_title), onBack = onClose)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
                val designHeight = DESIGN_WIDTH * aspect.h / aspect.w
                val fit = min(maxWidth / DESIGN_WIDTH, maxHeight / designHeight).coerceAtMost(1.2f)
                Box(Modifier.size(DESIGN_WIDTH * fit, designHeight * fit), contentAlignment = Alignment.Center) {
                    QuoteCardArt(
                        note = note, title = title, author = author, cover = cover, art = art,
                        background = background, textSize = size, withNote = withNote,
                        modifier = Modifier
                            .requiredSize(DESIGN_WIDTH, designHeight)
                            .graphicsScale(fit)
                            .recordInto(layer, EXPORT_WIDTH_PX, EXPORT_WIDTH_PX * aspect.h / aspect.w),
                    )
                }
            }
            DashCard(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                OptionRow(stringResource(R.string.notes_card_shape)) {
                    NotesSegmented(CardAspect.entries.map { it to it.label }, aspect, { aspect = it })
                }
                OptionRow(stringResource(R.string.notes_card_background)) {
                    NotesSegmented(
                        listOf(
                            CardBackground.THEME to stringResource(R.string.notes_card_bg_theme),
                            CardBackground.TINT to stringResource(R.string.notes_card_bg_tint),
                            CardBackground.BLUR to stringResource(R.string.notes_card_bg_blur),
                        ),
                        background, { background = it },
                    )
                }
                OptionRow(stringResource(R.string.notes_card_text)) {
                    NotesSegmented(
                        listOf(CardTextSize.SMALL to "A", CardTextSize.MEDIUM to "A+", CardTextSize.LARGE to "A++"),
                        size, { size = it },
                    )
                }
                if (note.hasNote) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.notes_card_with_note), style = MaterialTheme.typography.bodyMedium, color = colors.foreground, modifier = Modifier.weight(1f))
                        Switch(checked = withNote, onCheckedChange = { withNote = it })
                    }
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryButton(stringResource(R.string.notes_card_save), onClick = onSave, icon = "Download", enabled = !busy, modifier = Modifier.weight(1f))
                AccentButton(stringResource(R.string.notes_card_share), onClick = onShare, icon = "Share2", enabled = !busy, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = OttershelfTheme.colors.mutedForeground, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

/** Scales the laid-out card down (or up) to the preview, about its centre. */
private fun Modifier.graphicsScale(scale: Float): Modifier = this.then(
    Modifier.drawWithContent { scale(scale) { this@drawWithContent.drawContent() } },
)

/**
 * Records what this node draws into [layer] at [widthPx] x [heightPx] (vector content stays sharp:
 * it is drawn again at that scale) and shows the recording at the node's own size. Previews and
 * screenshot tests just draw.
 */
@Composable
private fun Modifier.recordInto(layer: GraphicsLayer?, widthPx: Int, heightPx: Int): Modifier {
    if (layer == null || LocalInspectionMode.current) return this
    return this.drawWithContent {
        val k = widthPx / size.width
        layer.record(size = IntSize(widthPx, heightPx)) {
            scale(k, k, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
        }
        scale(1f / k, 1f / k, pivot = Offset.Zero) { drawLayer(layer) }
    }
}

/**
 * The card itself, laid out at 360dp wide: the background, the quote mark in the highlight's colour,
 * the text in the serif (shrinking to fit, up to [textSize]), the user's note if asked, then the book
 * (cover, title, author) and the Ottershelf badge and wordmark.
 */
@Composable
internal fun QuoteCardArt(
    note: Annotation,
    title: String?,
    author: String?,
    cover: Any?,
    art: CoverArt,
    background: CardBackground,
    textSize: CardTextSize,
    withNote: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = OttershelfTheme.colors
    val effective = when {
        background == CardBackground.BLUR && art.blurred == null -> CardBackground.TINT
        else -> background
    }
    val onImage = effective != CardBackground.THEME
    val text = if (onImage) Color.White else colors.foreground
    val dim = if (onImage) Color.White.copy(alpha = 0.72f) else colors.mutedForeground
    val base = art.tint ?: colors.primary
    Box(modifier.clip(RoundedCornerShape(OttershelfTheme.radii.xl2))) {
        when (effective) {
            CardBackground.THEME -> Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .background(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.16f), Color.Transparent), center = Offset.Zero, radius = 900f)),
            )
            CardBackground.TINT -> Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(lerp(base, Color.Black, 0.25f), lerp(base, Color.Black, 0.72f)), start = Offset.Zero, end = Offset(900f, 1600f)),
                ),
            )
            CardBackground.BLUR -> {
                Image(
                    art.blurred!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }
        }
        if (effective == CardBackground.THEME) {
            Box(Modifier.fillMaxSize().border(1.dp, colors.dashCardBorder, RoundedCornerShape(OttershelfTheme.radii.xl2)))
        }
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            LucideIcon("Quote", contentDescription = null, tint = highlightColor(note.color), size = 32.dp)
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), contentAlignment = Alignment.CenterStart) {
                BasicText(
                    note.text,
                    style = quoteStyle(textSize.maxSp).copy(color = text, lineHeight = 1.4.em),
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = textSize.maxSp.sp, stepSize = 0.5.sp),
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (withNote && note.hasNote) {
                Text(
                    note.note.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic, fontSize = 12.sp),
                    color = dim,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(if (onImage) Color.White.copy(alpha = 0.25f) else colors.border))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(cover, title, Modifier.width(30.dp), authors = author, seed = note.bookId.toString())
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title.orEmpty(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    author?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(8.dp))
                Image(painterResource(R.drawable.ottershelf_badge), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    buildAnnotatedString {
                        append("Otter")
                        withStyle(SpanStyle(color = if (onImage) Color.White else colors.primary)) { append("shelf") }
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = OttershelfFonts.Serif, fontWeight = FontWeight.SemiBold),
                    color = dim,
                )
            }
        }
    }
}

/** A segmented control in the app's look (muted track, the picked one on the card colour). */
@Composable
internal fun <T> NotesSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    Row(modifier.fillMaxWidth().background(colors.muted, RoundedCornerShape(radii.lg)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (value, label) ->
            val picked = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(radii.md))
                    .background(if (picked) colors.card else colors.muted)
                    .then(if (picked) Modifier.border(1.dp, colors.border, RoundedCornerShape(radii.md)) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (picked) colors.foreground else colors.mutedForeground, maxLines = 1)
            }
        }
    }
}

/** Loads a small software copy of [model] and works out its [CoverArt] (nothing while it loads or without a cover). */
@Composable
internal fun rememberCoverArt(model: Any?): CoverArt {
    val context = LocalPlatformContext.current
    var art by remember(model) { mutableStateOf(CoverArt()) }
    androidx.compose.runtime.LaunchedEffect(model) {
        if (model == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(96, 144)
            .allowHardware(false)
            .memoryCacheKey("notes-art:$model")
            .build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        art = withContext(Dispatchers.Default) {
            CoverArt(tint = CoverTint.of(bitmap)?.let { Color(it) }, blurred = CoverBlur.of(bitmap)?.asImageBitmap())
        }
    }
    return art
}

/** A heavily blurred small copy of a cover: scaled to 36x54, then three box-blur passes (about a Gaussian). */
internal object CoverBlur {
    private const val W = 36
    private const val H = 54
    private const val RADIUS = 3

    fun of(src: Bitmap): Bitmap? = runCatching {
        val small = Bitmap.createScaledBitmap(src, W, H, true)
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        if (small !== src) small.recycle()
        repeat(3) { blur(px, W, H, RADIUS) }
        Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** One horizontal and one vertical box pass over ARGB [px], edges clamped. */
    internal fun blur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val n = 2 * r + 1
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val p = if (horizontal) src[line * w + j] else src[j * w + line]
                    a += p ushr 24 and 0xFF; rr += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
                }
                val out = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            }
        }
    }
}
