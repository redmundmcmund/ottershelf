package io.github.ottershelf.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.feature.home.model.DiversityScoreData
import io.github.ottershelf.feature.home.model.HighlightData
import io.github.ottershelf.feature.home.model.LibraryOverviewData
import io.github.ottershelf.feature.home.model.LongWaitData
import io.github.ottershelf.feature.home.model.MonthlyChallengeData
import io.github.ottershelf.feature.home.model.NeglectedGemsData
import io.github.ottershelf.feature.home.model.ReadingDnaData
import io.github.ottershelf.feature.home.model.ReadingGoalData
import io.github.ottershelf.feature.home.model.ReadingRhythmData
import io.github.ottershelf.feature.home.model.WidgetData
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.feature.home.model.YearProjectionData
import io.github.ottershelf.feature.home.model.formatStorage
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CardRow
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlin.math.roundToInt

/** What the ten widgets beyond Currently Reading and the streak can ask the screen to do. */
internal class WidgetActions(
    val onRetry: () -> Unit,
    val onOpenBook: (Long) -> Unit,
    val onRead: (bookId: Long, fileId: Long, format: String?, title: String) -> Unit,
    val onEditGoal: () -> Unit,
    val onNavigate: (Route) -> Unit,
    val onQueue: (Long) -> Unit,
    val cover: (bookId: Long, hasCover: Boolean) -> Any?,
    val allBooksTitle: String,
)

/**
 * One of the ten widgets (the web's dashboard/components/widgets), as a Nexus
 * dashboard card: its title, then a pulsing placeholder until its [data] arrives, its tap-to-retry
 * when it [failed], else its content. [queued] and [canQueue] are for Neglected Gems.
 */
@Composable
internal fun WidgetCard(
    type: WidgetType,
    data: WidgetData?,
    failed: Boolean,
    queued: Set<Long>,
    canQueue: (Long) -> Boolean,
    actions: WidgetActions,
    modifier: Modifier,
    rhythmDay: Int? = null,
    // The title line (a long press on it arranges the cards).
    headerModifier: Modifier = Modifier,
) {
    val title = when (type) {
        WidgetType.READING_GOAL -> (data as? ReadingGoalData)?.year?.takeIf { it > 0 }
            ?.let { stringResource(R.string.home_reading_goal_year, it) } ?: stringResource(R.string.home_reading_goal)
        else -> stringResource(widgetTitle(type))
    }
    DashCard(modifier) {
        CardTitle(title, headerModifier, icon = type.icon)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                data != null -> WidgetBody(type, data, queued, canQueue, actions, rhythmDay)
                failed -> CardFailed(actions.onRetry, Modifier.fillMaxSize())
                else -> WidgetSkeleton()
            }
        }
    }
}

internal fun widgetTitle(type: WidgetType): Int = when (type) {
    WidgetType.READING_STREAK -> R.string.home_reading_streak
    WidgetType.CURRENTLY_READING -> R.string.home_currently_reading
    WidgetType.READING_GOAL -> R.string.home_reading_goal
    WidgetType.READING_DNA -> R.string.home_reading_dna
    WidgetType.MONTHLY_CHALLENGE -> R.string.home_monthly_challenge
    WidgetType.HIGHLIGHT_OF_THE_DAY -> R.string.home_highlight
    WidgetType.NEGLECTED_GEMS -> R.string.home_neglected_gems
    WidgetType.READING_RHYTHM -> R.string.home_reading_rhythm
    WidgetType.DIVERSITY_SCORE -> R.string.home_diversity
    WidgetType.LIBRARY_OVERVIEW -> R.string.home_library_overview
    WidgetType.YEAR_PROJECTION -> R.string.home_year_projection
    WidgetType.LONG_WAIT -> R.string.home_long_wait
}

@Composable
private fun WidgetBody(type: WidgetType, data: WidgetData, queued: Set<Long>, canQueue: (Long) -> Boolean, actions: WidgetActions, rhythmDay: Int?) {
    when (data) {
        is ReadingGoalData -> GoalBody(data, actions.onEditGoal)
        is ReadingDnaData -> DnaBody(data)
        is MonthlyChallengeData -> ChallengeBody(data)
        is HighlightData -> HighlightBody(data, actions)
        is NeglectedGemsData -> GemsBody(data, queued, canQueue, actions)
        is ReadingRhythmData -> RhythmBody(data, rhythmDay)
        is DiversityScoreData -> DiversityBody(data)
        is LibraryOverviewData -> LibraryBody(data, actions)
        is YearProjectionData -> ProjectionBody(data)
        is LongWaitData -> LongWaitBody(data, actions)
        else -> when (type) {
            // Nothing to show (null from the server).
            WidgetType.HIGHLIGHT_OF_THE_DAY -> WidgetEmpty(R.string.home_highlight_empty, type.icon)
            WidgetType.LONG_WAIT -> WidgetEmpty(R.string.home_long_wait_empty, type.icon)
            else -> WidgetSkeleton()
        }
    }
}

// --- shared pieces ---------------------------------------------------------------------------

@Composable
private fun small(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
    MaterialTheme.typography.bodySmall.copy(fontSize = size.sp, lineHeight = (size + 4).sp, fontWeight = weight)

@Composable
internal fun bigNumber(size: Int = 30): TextStyle = MaterialTheme.typography.headlineLarge.copy(
    fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
    fontSize = size.sp,
    lineHeight = (size + 2).sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp,
)

@Composable
private fun WidgetEmpty(message: Int, icon: String) {
    EmptyState(stringResource(message), Modifier.fillMaxSize(), icon = icon, compact = true, contentPadding = PaddingValues(8.dp))
}

/** The web's pulsing placeholders: a disc, a line and a bar (static in screenshots). */
@Composable
private fun WidgetSkeleton() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SkeletonBox(Modifier.size(44.dp), shape = CircleShape)
        Spacer(Modifier.height(12.dp))
        SkeletonBox(Modifier.width(96.dp).height(10.dp))
        Spacer(Modifier.height(10.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(8.dp), shape = CircleShape)
    }
}

/** A score bar with its label (and value label): Reading DNA's traits, Diversity's parts. */
@Composable
private fun ScoreRow(label: String, score: Double, labelWidth: Dp, value: String? = null) {
    val colors = OttershelfTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(labelWidth), style = small(10), color = colors.mutedForeground, textAlign = TextAlign.End, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        PillProgressBar((score / 100.0).toFloat(), Modifier.weight(1f), height = 6.dp)
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, Modifier.width(76.dp), style = small(10), color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small bordered action (Neglected Gems' Add to queue and Shuffle). */
@Composable
private fun MiniButton(text: String, icon: String?, onClick: () -> Unit, enabled: Boolean = true, tint: Color? = null) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    val color = tint ?: colors.mutedForeground
    Row(
        Modifier
            .clip(shape)
            .background(if (tint != null) tint.copy(alpha = 0.1f) else Color.Transparent)
            .border(1.dp, if (tint != null) tint.copy(alpha = 0.4f) else colors.input, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LucideIcon(icon, contentDescription = null, tint = color, size = 12.dp)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = small(11), color = color)
    }
}

/** The web's `formatTime`: 45s, 25m, 1h 5m, 3h. */
@Composable
internal fun rhythmTime(seconds: Double): String {
    val s = seconds.roundToInt()
    if (s < 60) return stringResource(R.string.home_time_seconds, s)
    val minutes = (seconds / 60.0).roundToInt()
    if (minutes < 60) return stringResource(R.string.home_time_minutes, minutes)
    val rest = minutes % 60
    return if (rest > 0) stringResource(R.string.home_time_hours_minutes, minutes / 60, rest) else stringResource(R.string.home_time_hours, minutes / 60)
}

private fun Double.clean(): String = if (this == Math.floor(this)) toLong().toString() else String.format(Locale.getDefault(), "%.1f", this)

// --- Reading Goal ----------------------------------------------------------------------------

@Composable
private fun GoalBody(data: ReadingGoalData, onEditGoal: () -> Unit) {
    val colors = OttershelfTheme.colors
    val goal = data.goalBooks?.takeIf { it > 0 }
    if (goal == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.home_goal_prompt), style = small(12), color = colors.mutedForeground, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                    .background(colors.primary)
                    .clickable(role = Role.Button, onClick = onEditGoal)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.home_goal_set), style = small(12, FontWeight.Medium), color = colors.onPrimary)
            }
        }
        return
    }
    val percent = (data.completedBooks * 100.0 / goal).roundToInt().coerceIn(0, 100)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            GoalRing(data.completedBooks, goal, Modifier.size(124.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(data.completedBooks.toString(), style = bigNumber(22), color = colors.foreground)
                Text(stringResource(R.string.home_goal_of, goal), style = small(11), color = colors.mutedForeground)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_goal_percent, percent), Modifier.weight(1f), style = small(12), color = colors.mutedForeground)
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_goal_edit), onClick = onEditGoal),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon("Pencil", contentDescription = stringResource(R.string.home_goal_edit), tint = colors.mutedForeground, size = 13.dp)
            }
        }
    }
}

// --- Reading DNA -----------------------------------------------------------------------------

@Composable
private fun DnaBody(data: ReadingDnaData) {
    val colors = OttershelfTheme.colors
    if (data.booksAnalyzed < 5) {
        WidgetEmpty(R.string.home_dna_not_enough, WidgetType.READING_DNA.icon)
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.home_quoted, data.archetype),
            style = small(14, FontWeight.Bold),
            color = colors.foreground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ScoreRow(stringResource(R.string.home_dna_length), data.lengthScore, 52.dp, data.lengthLabel)
            ScoreRow(stringResource(R.string.home_dna_variety), data.varietyScore, 52.dp, data.varietyLabel)
            ScoreRow(stringResource(R.string.home_dna_rhythm), data.rhythmScore, 52.dp, data.rhythmLabel)
            ScoreRow(stringResource(R.string.home_dna_time), data.timeScore, 52.dp, data.timeLabel)
            ScoreRow(stringResource(R.string.home_dna_speed), data.speedScore, 52.dp, data.speedLabel)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            pluralStringResource(R.plurals.home_dna_based_on, data.booksAnalyzed, data.booksAnalyzed),
            style = small(10),
            color = colors.mutedForeground,
        )
    }
}

// --- Monthly Challenge -----------------------------------------------------------------------

@Composable
private fun ChallengeBody(data: MonthlyChallengeData) {
    val colors = OttershelfTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(data.title, style = small(14, FontWeight.Bold), color = colors.foreground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(3.dp))
        Text(data.description, style = small(11), color = colors.mutedForeground, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        val fraction = if (data.target > 0) (data.progress / data.target).toFloat() else 0f
        PillProgressBar(fraction, height = 10.dp, color = if (data.completed) colors.success else colors.primary)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_challenge_progress, data.progress.roundToInt(), data.target.roundToInt()),
                Modifier.weight(1f),
                style = small(11),
                color = colors.mutedForeground,
            )
            if (data.completed) {
                LucideIcon("Check", contentDescription = null, tint = colors.success, size = 12.dp)
                Spacer(Modifier.width(3.dp))
                Text(stringResource(R.string.home_challenge_complete), style = small(11, FontWeight.Medium), color = colors.success)
            }
        }
    }
}

// --- Highlight of the Day --------------------------------------------------------------------

@Composable
private fun HighlightBody(data: HighlightData, actions: WidgetActions) {
    val colors = OttershelfTheme.colors
    val text = if (data.text.length > 200) data.text.take(200) + "…" else data.text
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth().weight(1f, fill = false).height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(colors.primary.copy(alpha = 0.4f)))
            Text(
                stringResource(R.string.home_quoted, text),
                Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                style = small(12).copy(fontStyle = FontStyle.Italic, lineHeight = 18.sp),
                color = colors.foreground,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        val title = data.bookTitle ?: stringResource(R.string.home_untitled)
        CardRow(onClick = { actions.onOpenBook(data.bookId) }, modifier = Modifier.fillMaxWidth()) {
            BookCover(
                model = rememberCoverModel(actions.cover(data.bookId, data.hasCover)),
                title = data.bookTitle,
                seed = data.bookTitle ?: data.bookId.toString(),
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.size(24.dp, 36.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(title, style = small(12, FontWeight.Medium), color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                data.chapterTitle?.let {
                    Text(it, style = small(11), color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LucideIcon("ExternalLink", contentDescription = null, tint = colors.mutedForeground, size = 14.dp)
        }
    }
}

// --- Neglected Gems --------------------------------------------------------------------------

@Composable
private fun GemsBody(data: NeglectedGemsData, queued: Set<Long>, canQueue: (Long) -> Boolean, actions: WidgetActions) {
    val colors = OttershelfTheme.colors
    if (data.gems.isEmpty()) {
        WidgetEmpty(R.string.home_gems_empty, WidgetType.NEGLECTED_GEMS.icon)
        return
    }
    var index by rememberSaveable(data.gems.size) { mutableIntStateOf(0) }
    val gem = data.gems[index % data.gems.size]
    val title = gem.title ?: stringResource(R.string.home_untitled)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BookCover(
            model = rememberCoverModel(actions.cover(gem.bookId, gem.hasCover)),
            title = gem.title,
            seed = gem.title ?: gem.bookId.toString(),
            shape = RoundedCornerShape(OttershelfTheme.radii.sm),
            contentDescription = gem.title,
            modifier = Modifier
                .size(52.dp, 76.dp)
                .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
                .clickable(role = Role.Button) { actions.onOpenBook(gem.bookId) },
        )
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            Modifier.clickable { actions.onOpenBook(gem.bookId) },
            style = small(12, FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon("Star", contentDescription = null, tint = colors.starHighlight, size = 12.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.home_gems_rating, gem.rating.clean()) + " · " + stringResource(R.string.home_gems_waiting, gem.waitingDays),
                style = small(11),
                color = colors.mutedForeground,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val isQueued = gem.bookId in queued
            // Not for a book the user is reading (want to read would end that reading).
            if (isQueued || canQueue(gem.bookId)) {
                MiniButton(
                    text = stringResource(if (isQueued) R.string.home_gems_queued else R.string.home_gems_queue),
                    icon = if (isQueued) "Check" else "BookMarked",
                    onClick = { actions.onQueue(gem.bookId) },
                    enabled = !isQueued,
                    tint = if (isQueued) colors.success else null,
                )
            }
            if (data.gems.size > 1) {
                MiniButton(stringResource(R.string.home_gems_shuffle), null, onClick = { index = (index + 1) % data.gems.size })
            }
        }
    }
}

// --- Reading Rhythm --------------------------------------------------------------------------

@Composable
private fun RhythmBody(data: ReadingRhythmData, initialDay: Int?) {
    val colors = OttershelfTheme.colors
    if (data.activeDays == 0 || data.days.isEmpty()) {
        WidgetEmpty(R.string.home_rhythm_empty, WidgetType.READING_RHYTHM.icon)
        return
    }
    var selected by rememberSaveable(data.days.size) { mutableStateOf(initialDay?.takeIf { it in data.days.indices }) }
    val locale = Locale.getDefault()
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val dates = remember(data.days) { data.days.map { runCatching { LocalDate.parse(it.date.take(10)) }.getOrNull() } }
    Column(Modifier.fillMaxSize()) {
        // The readout: the picked day's time (strong) and date (secondary), else how to pick one.
        Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
            val day = selected?.let { data.days.getOrNull(it) }
            if (day != null) {
                Text(
                    if (day.readingSeconds > 0) rhythmTime(day.readingSeconds) else stringResource(R.string.home_time_none),
                    style = small(12, FontWeight.SemiBold),
                    color = colors.foreground,
                )
                Text(
                    " · " + (dates[selected!!]?.format(dayFormat) ?: day.date),
                    style = small(11),
                    color = colors.mutedForeground,
                )
            } else {
                Text(stringResource(R.string.home_rhythm_hint), style = small(11), color = colors.mutedForeground.copy(alpha = 0.8f), maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        RhythmChart(
            days = data.days,
            selected = selected,
            onSelect = { selected = it },
            description = stringResource(R.string.home_reading_rhythm),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Row(Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 4.dp)) {
            dates.forEachIndexed { i, date ->
                Text(
                    date?.dayOfWeek?.getDisplayName(DayStyle.NARROW, locale) ?: "",
                    Modifier.weight(1f),
                    style = small(9, if (i == selected) FontWeight.Bold else FontWeight.Normal),
                    color = if (i == selected) colors.foreground else colors.mutedForeground,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.3f)))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            val active = pluralStringResource(R.plurals.home_rhythm_active_days, data.activeDays, data.activeDays)
            Text(
                stringResource(R.string.home_rhythm_consistency, active, data.consistencyPercent.roundToInt()),
                Modifier.weight(1f),
                style = small(11),
                color = colors.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.home_rhythm_avg, rhythmTime(data.avgSecondsPerDay)), style = small(11), color = colors.mutedForeground)
        }
    }
}

// --- Diversity Score -------------------------------------------------------------------------

@Composable
private fun DiversityBody(data: DiversityScoreData) {
    val colors = OttershelfTheme.colors
    if (data.booksAnalyzed < 3) {
        WidgetEmpty(R.string.home_diversity_not_enough, WidgetType.DIVERSITY_SCORE.icon)
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(data.score.roundToInt().toString(), style = bigNumber(28), color = colors.foreground)
        Text(stringResource(R.string.home_diversity_of), style = small(11), color = colors.mutedForeground)
        Spacer(Modifier.height(4.dp))
        Text(data.label, style = small(12, FontWeight.Medium), color = colors.primary, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ScoreRow(stringResource(R.string.home_diversity_genre), data.genreScore, 40.dp)
            ScoreRow(stringResource(R.string.home_diversity_author), data.authorScore, 40.dp)
            ScoreRow(stringResource(R.string.home_diversity_era), data.eraScore, 40.dp)
            ScoreRow(stringResource(R.string.home_diversity_language), data.languageScore, 40.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(pluralStringResource(R.plurals.home_diversity_analyzed, data.booksAnalyzed, data.booksAnalyzed), style = small(10), color = colors.mutedForeground)
    }
}

// --- Library Overview ------------------------------------------------------------------------

@Composable
private fun LibraryBody(data: LibraryOverviewData, actions: WidgetActions) {
    val colors = OttershelfTheme.colors
    if (data.totalBooks == 0L) {
        WidgetEmpty(R.string.home_library_empty, WidgetType.LIBRARY_OVERVIEW.icon)
        return
    }
    val number = remember { NumberFormat.getIntegerInstance() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Library", number.format(data.totalBooks), stringResource(R.string.home_library_books)) {
                actions.onNavigate(Route.BookList("all", actions.allBooksTitle))
            }
            StatTile("Users", number.format(data.totalAuthors), stringResource(R.string.home_library_authors)) { actions.onNavigate(Route.Authors()) }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("BookCopy", number.format(data.totalSeries), stringResource(R.string.home_library_series)) { actions.onNavigate(Route.Series()) }
            StatTile("HardDrive", formatStorage(data.totalStorageBytes), stringResource(R.string.home_library_storage), null)
        }
        if (data.booksAddedThisYear > 0) {
            val shape = RoundedCornerShape(OttershelfTheme.radii.md)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.primary.copy(alpha = 0.05f))
                    .border(1.dp, colors.primary.copy(alpha = 0.2f), shape)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.home_library_added, data.booksAddedThisYear.toInt()), style = small(11, FontWeight.Medium), color = colors.primary)
            }
        }
    }
}

@Composable
private fun RowScope.StatTile(icon: String, value: String, label: String, onClick: (() -> Unit)?) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .background(colors.muted.copy(alpha = 0.3f))
            .border(1.dp, colors.border.copy(alpha = 0.5f), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(28.dp).background(colors.muted, RoundedCornerShape(OttershelfTheme.radii.md)),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(icon, contentDescription = null, tint = colors.mutedForeground, size = 14.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(value, style = small(15, FontWeight.Bold), color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = small(11), color = colors.mutedForeground, maxLines = 1)
        }
    }
}

// --- Year Projection -------------------------------------------------------------------------

@Composable
private fun ProjectionBody(data: YearProjectionData) {
    val colors = OttershelfTheme.colors
    val number = remember { NumberFormat.getIntegerInstance() }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(data.projectedBooks.roundToInt().toString(), style = bigNumber(30), color = colors.foreground)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.home_projection_books), Modifier.padding(bottom = 4.dp), style = small(12), color = colors.mutedForeground)
        }
        Spacer(Modifier.height(8.dp))
        val (icon, label, tint) = when (data.trend) {
            "up" -> Triple("TrendingUp", R.string.home_projection_up, colors.success)
            "down" -> Triple("TrendingDown", R.string.home_projection_down, colors.destructive)
            else -> Triple("Minus", R.string.home_projection_steady, colors.mutedForeground)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon(icon, contentDescription = null, tint = tint, size = 14.dp)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(label), style = small(12), color = tint)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat(number.format(data.projectedPages.roundToInt()), stringResource(R.string.home_projection_pages))
            MiniStat(data.projectedHours.clean(), stringResource(R.string.home_projection_hours))
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.home_projection_footer, data.booksCompletedYtd, data.daysRemaining), style = small(11), color = colors.mutedForeground)
    }
}

@Composable
private fun RowScope.MiniStat(value: String, label: String) {
    val colors = OttershelfTheme.colors
    Column(
        Modifier
            .weight(1f)
            .background(colors.muted.copy(alpha = 0.3f), RoundedCornerShape(OttershelfTheme.radii.md))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = small(12, FontWeight.SemiBold), color = colors.foreground)
        Text(label, style = small(11), color = colors.mutedForeground)
    }
}

// --- The Long Wait ---------------------------------------------------------------------------

@Composable
private fun LongWaitBody(data: LongWaitData, actions: WidgetActions) {
    val colors = OttershelfTheme.colors
    val title = data.title ?: stringResource(R.string.home_untitled)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            BookCover(
                model = rememberCoverModel(actions.cover(data.bookId, data.hasCover)),
                title = data.title,
                seed = data.title ?: data.bookId.toString(),
                shape = RoundedCornerShape(OttershelfTheme.radii.sm),
                contentDescription = data.title,
                modifier = Modifier
                    .size(64.dp, 96.dp)
                    .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
                    .clickable(role = Role.Button) { actions.onOpenBook(data.bookId) },
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    title,
                    Modifier.clickable { actions.onOpenBook(data.bookId) },
                    style = small(12, FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(data.waitingDays.toString(), style = bigNumber(24), color = colors.primary)
                Text(stringResource(R.string.home_long_wait_days), style = small(11), color = colors.mutedForeground)
                val meta = listOfNotNull(
                    data.pageCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.home_long_wait_pages, it, it) },
                    data.genre,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, style = small(10), color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OttershelfTheme.radii.lg))
                .background(colors.primary.copy(alpha = 0.1f))
                .clickable(role = Role.Button) {
                    val fileId = data.fileId
                    if (fileId != null && BookFormats.isOpenable(data.fileFormat)) actions.onRead(data.bookId, fileId, data.fileFormat, title) else actions.onOpenBook(data.bookId)
                }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon("Play", contentDescription = null, tint = colors.primary, size = 12.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.home_long_wait_start), style = small(12, FontWeight.Medium), color = colors.primary)
        }
    }
}
