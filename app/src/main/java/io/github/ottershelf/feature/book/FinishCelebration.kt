package io.github.ottershelf.feature.book

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The finish flow, full screen (Bookmory's completion celebration): confetti over the cover and a
 * short message, then the rating and private review, then any achievement the server just awarded.
 */
@Composable
fun FinishCelebrationContent(
    step: CelebrationStep,
    title: String?,
    cover: Any?,
    tracking: BookTrackingUiState,
    actions: BookTrackingActions,
    modifier: Modifier = Modifier,
    /** Under the message on the first step: the next book in the series (feature.seriesnext), if any. */
    next: @Composable () -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    Box(modifier.fillMaxSize().background(colors.background)) {
        if (step !is CelebrationStep.Rate) Confetti(Modifier.fillMaxSize(), key = step)
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            val minHeight = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minHeight)
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (step) {
                    is CelebrationStep.Done -> DoneStep(step, title, cover, actions, next)
                    CelebrationStep.Rate -> RateStep(title, cover, tracking, actions)
                    is CelebrationStep.Achievement -> AchievementStep(step, actions)
                }
            }
        }
    }
}

@Composable
private fun DoneStep(step: CelebrationStep.Done, title: String?, cover: Any?, actions: BookTrackingActions, next: @Composable () -> Unit) {
    val colors = OttershelfTheme.colors
    Spacer(Modifier.height(24.dp))
    BookCover(model = cover, title = title, modifier = Modifier.size(170.dp, 255.dp))
    Spacer(Modifier.height(28.dp))
    Text(
        stringResource(R.string.book_celebrate_title),
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        color = colors.foreground,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.book_celebrate_message),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, fontSize = 17.sp),
        color = colors.foreground,
        textAlign = TextAlign.Center,
    )
    title?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.mutedForeground, textAlign = TextAlign.Center)
    }
    step.days?.let { days ->
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.background(colors.accentTint, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon("CalendarCheck", contentDescription = null, tint = colors.primary, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(pluralStringResource(R.plurals.book_celebrate_days, days, days), style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
    // 32dp between the message and Continue, with the next book (if any) in the middle.
    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) { next() }
    Spacer(Modifier.height(16.dp))
    AccentButton(
        text = stringResource(R.string.book_continue),
        onClick = actions::celebrationContinue,
        modifier = Modifier.widthIn(min = 200.dp),
    )
}

@Composable
private fun RateStep(title: String?, cover: Any?, tracking: BookTrackingUiState, actions: BookTrackingActions) {
    val colors = OttershelfTheme.colors
    var rating by rememberSaveable { mutableStateOf(tracking.rating) }
    var note by rememberSaveable { mutableStateOf(tracking.note.orEmpty()) }
    BookCover(model = cover, title = title, modifier = Modifier.size(96.dp, 144.dp))
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.book_rate_title),
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        color = colors.foreground,
    )
    if (tracking.canRate) {
        StarRating(
            rating = rating,
            onRate = { n -> rating = if (n == rating) null else n },
            modifier = Modifier.padding(top = 14.dp),
            size = 40.dp,
            enabled = !tracking.ratingLocked,
        )
        if (tracking.ratingLocked) RatingLockedNote(Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(20.dp))
    ReviewField(note, { note = it }, Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.book_review_private),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = colors.mutedForeground,
    )
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth()) {
        SecondaryButton(stringResource(R.string.book_rate_skip), onClick = actions::celebrationSkip, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        AccentButton(stringResource(R.string.book_save), onClick = { actions.celebrationSave(rating, note) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AchievementStep(step: CelebrationStep.Achievement, actions: BookTrackingActions) {
    val colors = OttershelfTheme.colors
    val a = step.claim.achievement
    val tint = when (a.rarity) {
        "legendary" -> colors.warning
        "epic" -> colors.primary
        "rare" -> colors.info
        else -> colors.success
    }
    Box(
        Modifier.size(112.dp).background(tint.copy(alpha = 0.16f), CircleShape).border(2.dp, tint.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(a.iconName ?: "Trophy", contentDescription = null, tint = tint, size = 52.dp, fallback = "Trophy")
    }
    Spacer(Modifier.height(24.dp))
    Text(
        stringResource(R.string.book_achievement_title).uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
        color = tint,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        a.name,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = colors.foreground,
        textAlign = TextAlign.Center,
    )
    a.description?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.mutedForeground, textAlign = TextAlign.Center)
    }
    a.rarity?.let { rarity ->
        Spacer(Modifier.height(14.dp))
        Text(
            rarity.replaceFirstChar { it.titlecase(Locale.getDefault()) },
            modifier = Modifier.background(tint.copy(alpha = 0.16f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
    Spacer(Modifier.height(32.dp))
    AccentButton(stringResource(R.string.book_achievement_ok), onClick = actions::achievementSeen, modifier = Modifier.widthIn(min = 200.dp))
}

private class Piece(
    val x: Float,
    val startY: Float,
    val delay: Float,
    val speed: Float,
    val sway: Float,
    val swayRate: Float,
    val phase: Float,
    val spin: Float,
    val flip: Float,
    val w: Float,
    val h: Float,
    val color: Int,
)

private const val CONFETTI_SECONDS = 6f
private const val CONFETTI_PIECES = 150

/**
 * Confetti falling over the whole screen for a few seconds (dp units, drawn on a Canvas). In
 * previews and screenshot tests it is a still frame partway through.
 */
@Composable
private fun Confetti(modifier: Modifier, key: Any) {
    val colors = OttershelfTheme.colors
    val palette = remember(colors) { listOf(colors.primary, colors.success, colors.warning, colors.info, colors.destructive, colors.starHighlight) }
    val pieces = remember {
        val random = Random(7)
        List(CONFETTI_PIECES) {
            Piece(
                x = random.nextFloat(),
                startY = random.nextFloat(),
                delay = random.nextFloat() * 0.8f,
                speed = 140f + random.nextFloat() * 160f,
                sway = 8f + random.nextFloat() * 22f,
                swayRate = 1.5f + random.nextFloat() * 2.5f,
                phase = random.nextFloat() * 6.28f,
                spin = (random.nextFloat() - 0.5f) * 540f,
                flip = 2f + random.nextFloat() * 5f,
                w = 6f + random.nextFloat() * 5f,
                h = 10f + random.nextFloat() * 8f,
                color = random.nextInt(6),
            )
        }
    }
    val still = LocalInspectionMode.current
    var t by remember(key) { mutableFloatStateOf(if (still) 1.6f else 0f) }
    if (!still) {
        LaunchedEffect(key) {
            val start = withFrameMillis { it }
            while (t < CONFETTI_SECONDS) {
                t = (withFrameMillis { it } - start) / 1000f
            }
        }
    }
    // The clock is read only while drawing, so each frame redraws without recomposing.
    Canvas(modifier) {
        if (t >= CONFETTI_SECONDS) return@Canvas
        val d = density
        val fade = if (t > CONFETTI_SECONDS - 1f) (CONFETTI_SECONDS - t).coerceIn(0f, 1f) else 1f
        for (p in pieces) {
            val time = t - p.delay
            if (time < 0f) continue
            val y = (-p.startY * size.height / d - 20f + p.speed * time + 60f * time * time) * d
            if (y > size.height + 20f * d) continue
            val x = p.x * size.width + sin(time * p.swayRate + p.phase) * p.sway * d
            val squash = abs(cos(time * p.flip + p.phase))
            val w = p.w * d
            val h = p.h * d * (0.25f + 0.75f * squash)
            rotate(p.spin * time + p.phase * 57f, pivot = Offset(x, y)) {
                drawRect(
                    color = palette[p.color].copy(alpha = fade),
                    topLeft = Offset(x - w / 2f, y - h / 2f),
                    size = Size(w, h),
                )
            }
        }
    }
}
