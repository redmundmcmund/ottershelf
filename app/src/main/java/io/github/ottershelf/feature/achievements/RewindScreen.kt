package io.github.ottershelf.feature.achievements

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.ottershelf.R
import io.github.ottershelf.feature.stats.ColumnChart
import io.github.ottershelf.feature.stats.RankedBars
import io.github.ottershelf.feature.stats.RankedItem
import io.github.ottershelf.feature.stats.Readout
import io.github.ottershelf.feature.stats.duration
import io.github.ottershelf.feature.stats.figureStyle
import io.github.ottershelf.feature.stats.minutesAxis
import io.github.ottershelf.feature.stats.trim
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.PatternBackground
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The year in review, pushed full screen (from Statistics' and Achievements' toolbars). */
@Composable
fun RewindScreen(route: Route.Rewind, navigator: AppNavigator) {
    val viewModel = appViewModel { RewindViewModel(it, route.year) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Opaque, as every pushed screen is: the page colour with the user's background pattern.
    PatternBackground(Modifier.fillMaxSize()) {
        RewindContent(
            state = state,
            onClose = { navigator.back() },
            onYear = viewModel::selectYear,
            onRetry = viewModel::retry,
            onOpenBook = { navigator.navigate(Route.BookDetail(it)) },
        )
    }
}

/**
 * A story: segments along the top (one per card, filled up to the current one, tappable), the
 * title and a close button, then the cards in a pager (swipe, or Back / Next below). Each card is
 * a DashCard filling the screen with one headline figure and its detail.
 */
@Composable
fun RewindContent(
    state: RewindUiState,
    onClose: () -> Unit = {},
    onYear: (Int) -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenBook: (Long) -> Unit = {},
    initialPage: Int = 0,
) {
    val colors = OttershelfTheme.colors
    val data = state.data
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        if (data == null) {
            Header(state.year, onClose)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.error != null) {
                    ErrorState(onRetry, message = stringResource(R.string.achievements_rewind_failed), detail = state.error)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingState()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.achievements_rewind_loading), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground)
                    }
                }
            }
            return@Column
        }
        key(data.year) {
            val pages = remember(data) { data.pages() }
            val pager = rememberPagerState(initialPage.coerceIn(0, pages.size - 1)) { pages.size }
            val scope = rememberCoroutineScope()
            BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
            Segments(pages.size, pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
            Header(data.year, onClose)
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                pageSpacing = 12.dp,
            ) { index ->
                RewindCard(pages[index], data, loading = state.loading, onYear = onYear, onOpenBook = onOpenBook, modifier = Modifier.fillMaxSize().padding(vertical = 6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) {
                    SecondaryButton(stringResource(R.string.achievements_rewind_back), onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, icon = "ChevronLeft")
                }
                Spacer(Modifier.weight(1f))
                val last = pager.currentPage >= pages.size - 1
                AccentButton(
                    stringResource(if (last) R.string.achievements_rewind_done else R.string.achievements_rewind_next),
                    onClick = { if (last) onClose() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    icon = if (last) "Check" else "ChevronRight",
                )
            }
        }
    }
}

@Composable
private fun Segments(count: Int, current: Int, onPick: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .clickable { onPick(i) }
                    .padding(vertical = 6.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= current) colors.primary else colors.muted),
            )
        }
    }
}

@Composable
private fun Header(year: Int, onClose: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile("Rewind", colors.primary, size = 30.dp, iconSize = 16.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.achievements_rewind_title, year),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.foreground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            LucideIcon("X", contentDescription = stringResource(R.string.achievements_rewind_close), tint = colors.foreground, size = 22.dp)
        }
    }
}

/** One card of the story. */
@Composable
internal fun RewindCard(
    page: RewindPage,
    data: RewindData,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onYear: (Int) -> Unit = {},
    onOpenBook: (Long) -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    DashCard(
        modifier,
        containerColor = colors.card.copy(alpha = 0.92f),
        contentPadding = PaddingValues(0.dp),
    ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 20.dp, vertical = 22.dp)
                .alpha(if (loading) 0.5f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (page) {
                RewindPage.INTRO -> Intro(data, onYear)
                RewindPage.TIME -> TimePage(data)
                RewindPage.BOOKS -> BooksPage(data, onOpenBook)
                RewindPage.STREAK -> StreakPage(data)
                RewindPage.HOURS -> HoursPage(data)
                RewindPage.GENRES -> GenresPage(data)
                RewindPage.AUTHORS -> AuthorsPage(data)
                RewindPage.BADGES -> BadgesPage(data)
                RewindPage.OUTRO -> Outro(data)
            }
        }
        }
    }
}

// --- pieces -------------------------------------------------------------------------------------

@Composable
private fun Kicker(text: String, icon: String) {
    val colors = OttershelfTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        LucideIcon(icon, contentDescription = null, tint = colors.primary, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(text.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp), color = colors.primary)
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun Hero(text: String, size: Int = 56) {
    Text(text, style = figureStyle(size), color = OttershelfTheme.colors.foreground, textAlign = TextAlign.Center, maxLines = 2)
}

@Composable
private fun Line(text: String, strong: Boolean = false) {
    val colors = OttershelfTheme.colors
    Text(
        text,
        style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        color = if (strong) colors.foreground else colors.mutedForeground,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = OttershelfTheme.colors.foreground, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
}

private fun Resources.hours(seconds: Double): String =
    if (seconds >= 10 * 3600) getString(R.string.achievements_hours_short, (seconds / 3600).roundToInt().toString()) else duration(seconds)

private fun monthName(index: Int, style: TextStyle = TextStyle.FULL): String = Month.of(index + 1).getDisplayName(style, Locale.getDefault())

private fun dateText(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private fun shortDate(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(date)

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Column(
        modifier.background(colors.muted.copy(alpha = 0.6f), RoundedCornerShape(OttershelfTheme.radii.lg)).padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = figureStyle(20), color = colors.foreground, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** A column chart with the readout above it (tap or drag), one series in the accent. */
@Composable
private fun MonthsChart(values: List<Double>, valueText: (Double) -> String, integer: Boolean, minutes: Boolean) {
    var selected by remember(values) { mutableIntStateOf(-1) }
    val res = LocalContext.current.resources
    Readout(
        value = selected.takeIf { it >= 0 }?.let { valueText(values[it]) },
        label = selected.takeIf { it >= 0 }?.let { monthName(it) }.orEmpty(),
        hint = stringResource(R.string.achievements_tap_hint),
    )
    ColumnChart(
        values = if (minutes) values.map { it / 60.0 } else values,
        labels = List(12) { monthName(it, TextStyle.NARROW) },
        selected = selected,
        onSelect = { selected = it },
        formatY = { if (minutes) res.minutesAxis(it) else trim(it) },
        integerTicks = integer,
        timeAxis = minutes,
        height = 150.dp,
    )
}

// --- the cards ----------------------------------------------------------------------------------

@Composable
private fun Intro(data: RewindData, onYear: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    val res = LocalContext.current.resources
    Kicker(stringResource(R.string.achievements_rewind_intro_kicker), "Orbit")
    val years = data.availableYears
    val previous = years.filter { it < data.year }.maxOrNull()
    val next = years.filter { it > data.year }.minOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { previous?.let(onYear) }, enabled = previous != null) {
            LucideIcon("ChevronLeft", contentDescription = stringResource(R.string.achievements_rewind_previous_year), tint = if (previous != null) colors.foreground else colors.muted, size = 28.dp)
        }
        Text(data.year.toString(), style = figureStyle(76), color = colors.foreground)
        IconButton(onClick = { next?.let(onYear) }, enabled = next != null) {
            LucideIcon("ChevronRight", contentDescription = stringResource(R.string.achievements_rewind_next_year), tint = if (next != null) colors.foreground else colors.muted, size = 28.dp)
        }
    }
    if (data.hasAnything) {
        Line(stringResource(R.string.achievements_rewind_intro_line, res.hours(data.totalSeconds), pluralStringResource(R.plurals.achievements_days, data.daysRead, data.daysRead)), strong = true)
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat(data.finishedCount.toString(), stringResource(R.string.achievements_rewind_books_stat), Modifier.weight(1f))
            MiniStat(data.streak?.days?.toString() ?: "0", stringResource(R.string.achievements_rewind_streak_stat), Modifier.weight(1f))
            MiniStat(data.badges.size.toString(), stringResource(R.string.achievements_rewind_badges_stat), Modifier.weight(1f))
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.achievements_rewind_intro_hint), style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground)
    } else {
        Line(stringResource(R.string.achievements_rewind_intro_empty))
    }
}

@Composable
private fun TimePage(data: RewindData) {
    val res = LocalContext.current.resources
    Kicker(stringResource(R.string.achievements_rewind_time_kicker), "Clock")
    Hero(res.hours(data.totalSeconds))
    data.bestMonth?.let { Line(stringResource(R.string.achievements_rewind_best_month, monthName(it), res.duration(data.monthlySeconds[it]))) }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniStat(data.daysRead.toString(), stringResource(R.string.achievements_rewind_days_read), Modifier.weight(1f))
        MiniStat(data.sessions.toString(), stringResource(R.string.achievements_rewind_sessions), Modifier.weight(1f))
        MiniStat(if (data.daysRead > 0) res.duration(data.totalSeconds / data.daysRead) else "-", stringResource(R.string.achievements_rewind_per_day), Modifier.weight(1f))
    }
    Section(stringResource(R.string.achievements_rewind_month_chart))
    MonthsChart(data.monthlySeconds, { res.duration(it) }, integer = false, minutes = true)
}

@Composable
private fun BooksPage(data: RewindData, onOpenBook: (Long) -> Unit) {
    val colors = OttershelfTheme.colors
    val res = LocalContext.current.resources
    Kicker(stringResource(R.string.achievements_rewind_books_kicker), "BookCheck")
    // Every reading finished that year (as the chart below counts them); the covers are the books found.
    Hero(pluralStringResource(R.plurals.achievements_books, data.finishedCount, data.finishedCount), size = 44)
    Spacer(Modifier.height(18.dp))
    val (shownCount, more) = RewindMath.coverCells(data.finishedCount, data.finished.size)
    val cells = data.finished.take(shownCount).map { it as RewindBook? } + if (more > 0) listOf(null) else emptyList()
    cells.chunked(5).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { b ->
                if (b != null) {
                    BookCover(
                        model = b.cover,
                        title = b.book.title,
                        authors = b.book.authors.joinToString(),
                        seed = b.book.id.toString(),
                        modifier = Modifier.weight(1f).clickable { onOpenBook(b.book.id) },
                    )
                } else {
                    Box(
                        Modifier.weight(1f).aspectRatio(2f / 3f).background(colors.muted, RoundedCornerShape(OttershelfTheme.radii.sm)),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.achievements_rewind_more, more), style = figureStyle(16), color = colors.foreground) }
                }
            }
            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    data.fastest?.let { b ->
        Line(stringResource(R.string.achievements_rewind_fastest, b.book.title.orEmpty(), pluralStringResource(R.plurals.achievements_days, b.daysTaken!!, b.daysTaken)))
    }
    data.mostTime?.let { b -> Line(stringResource(R.string.achievements_rewind_most_time, b.book.title.orEmpty(), res.duration(b.seconds!!.toDouble()))) }
    if (data.completionsByMonth.any { it > 0 }) {
        Section(stringResource(R.string.achievements_rewind_finished_chart))
        MonthsChart(data.completionsByMonth, { pluralStringResource0(res, it.roundToInt()) }, integer = true, minutes = false)
    }
}

private fun pluralStringResource0(res: Resources, n: Int): String = res.getQuantityString(R.plurals.achievements_books, n, n)

@Composable
private fun StreakPage(data: RewindData) {
    val colors = OttershelfTheme.colors
    val res = LocalContext.current.resources
    val streak = data.streak ?: return
    Kicker(stringResource(R.string.achievements_rewind_streak_kicker), "Flame")
    Hero(pluralStringResource(R.plurals.achievements_days, streak.days, streak.days))
    Line(stringResource(R.string.achievements_rewind_streak_range, shortDate(streak.start), shortDate(streak.end)))
    Spacer(Modifier.height(28.dp))
    data.bestDay?.let { (date, seconds) ->
        StatRow("CalendarCheck", stringResource(R.string.achievements_rewind_best_day), stringResource(R.string.achievements_rewind_best_day_line, res.duration(seconds.toDouble()), dateText(date)))
    }
    data.allTimeStreak?.let {
        Spacer(Modifier.height(8.dp))
        StatRow("Trophy", stringResource(R.string.achievements_rewind_all_time), pluralStringResource(R.plurals.achievements_days, it, it))
    }
}

@Composable
private fun StatRow(icon: String, label: String, value: String) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.muted.copy(alpha = 0.6f), RoundedCornerShape(OttershelfTheme.radii.lg)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, colors.primary, size = 34.dp, iconSize = 17.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground)
            Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground)
        }
    }
}

@Composable
private fun HoursPage(data: RewindData) {
    val res = LocalContext.current.resources
    val peak = RewindMath.peakHour(data.peakHours) ?: return
    Kicker(stringResource(R.string.achievements_rewind_hours_kicker), "Clock")
    Hero(
        stringResource(
            when (peak) {
                in 5..10 -> R.string.achievements_rewind_early_bird
                in 11..16 -> R.string.achievements_rewind_daytime
                in 17..20 -> R.string.achievements_rewind_evening
                else -> R.string.achievements_rewind_night_owl
            },
        ),
        size = 36,
    )
    Line(stringResource(R.string.achievements_rewind_peak_line, stringResource(R.string.achievements_hour, peak)))
    Section(stringResource(R.string.achievements_rewind_hours_chart))
    var selected by remember(data.peakHours) { mutableIntStateOf(-1) }
    Readout(
        value = selected.takeIf { it >= 0 }?.let { res.duration(data.peakHours[it]) },
        label = selected.takeIf { it >= 0 }?.let { stringResource(R.string.achievements_hour, it) }.orEmpty(),
        hint = stringResource(R.string.achievements_tap_hint),
    )
    ColumnChart(
        values = data.peakHours.map { it / 60.0 },
        labels = List(24) { h -> if (h % 6 == 0) stringResource(R.string.achievements_hour, h) else null },
        selected = selected,
        onSelect = { selected = it },
        formatY = { res.minutesAxis(it) },
        timeAxis = true,
        height = 200.dp,
    )
}

@Composable
private fun GenresPage(data: RewindData) {
    val res = LocalContext.current.resources
    val top = data.genres.first()
    Kicker(stringResource(R.string.achievements_rewind_genres_kicker), "Tags")
    Hero(top.genre, size = 36)
    Line(stringResource(R.string.achievements_rewind_genres_line, res.duration(top.readingSeconds)))
    Spacer(Modifier.height(22.dp))
    RankedBars(
        items = data.genres.map { RankedItem(it.genre, it.readingSeconds) },
        valueLabel = { res.duration(it.value) },
    )
}

@Composable
private fun AuthorsPage(data: RewindData) {
    val top = data.authors.first()
    val count = top.value.roundToInt()
    Kicker(stringResource(R.string.achievements_rewind_authors_kicker), "Users")
    Hero(top.label, size = 34)
    Line(stringResource(R.string.achievements_rewind_authors_line, pluralStringResource(R.plurals.achievements_books, count, count), top.label))
    Spacer(Modifier.height(22.dp))
    val res = LocalContext.current.resources
    RankedBars(items = data.authors, valueLabel = { pluralStringResource0(res, it.value.roundToInt()) })
}

@Composable
private fun BadgesPage(data: RewindData) {
    val colors = OttershelfTheme.colors
    Kicker(stringResource(R.string.achievements_rewind_badges_kicker), "Award")
    Hero(data.badges.size.toString())
    Spacer(Modifier.height(18.dp))
    data.badges.take(8).forEach { a ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            BadgeMedallion(AchievementLogic.iconName(a.iconName), a.rarity, earned = true, fraction = null, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(rarityLabel(a.rarity), awardedText(a.awardedAt)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 1,
                )
            }
        }
    }
    if (data.badges.size > 8) {
        Text(stringResource(R.string.achievements_rewind_more, data.badges.size - 8), style = MaterialTheme.typography.labelLarge, color = colors.mutedForeground, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Outro(data: RewindData) {
    val res = LocalContext.current.resources
    Kicker(stringResource(R.string.achievements_rewind_outro_kicker, data.year), "Sparkles")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniStat(res.hours(data.totalSeconds), stringResource(R.string.achievements_rewind_hours_stat), Modifier.weight(1f))
        MiniStat(data.finishedCount.toString(), stringResource(R.string.achievements_rewind_books_stat), Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniStat(pluralStringResource(R.plurals.achievements_days, data.streak?.days ?: 0, data.streak?.days ?: 0), stringResource(R.string.achievements_rewind_streak_stat), Modifier.weight(1f))
        MiniStat(data.badges.size.toString(), stringResource(R.string.achievements_rewind_badges_stat), Modifier.weight(1f))
    }
    if (data.finished.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((-18).dp, Alignment.CenterHorizontally)) {
            data.finished.takeLast(6).forEach { b ->
                BookCover(b.cover, b.book.title, Modifier.size(width = 54.dp, height = 81.dp), authors = b.book.authors.joinToString(), seed = b.book.id.toString())
            }
        }
    }
    Line(stringResource(R.string.achievements_rewind_outro_line), strong = true)
}
