package io.github.ottershelf.feature.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.ottershelf.R
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CardRow
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The Book Calendar: a root list (the drawer's Tracking > Calendar) under the shell's toolbar. */
@Composable
fun CalendarScreen(navigator: AppNavigator, contentPadding: PaddingValues) {
    val viewModel = appViewModel { CalendarViewModel(it, this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!.cacheDir) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from a book or the reader (the reader's sessions don't go through the tracker), or the app resumed.
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val layer = rememberGraphicsLayer()
    val background = OttershelfTheme.colors.background
    val saved = stringResource(R.string.calendar_saved)
    val saveFailed = stringResource(R.string.calendar_save_failed)
    var saving by remember { mutableStateOf(false) }
    val dates = readingDatesViewModel()
    ReadingDatesHost(dates, navigator, onSaved = viewModel::readingSaved)

    CalendarToolbar(
        // A book's start and end dates, for today (a day's screen adds them for that day).
        onAddBook = { dates.openPicker(state.today) },
        onHistory = { navigator.navigate(Route.History) },
        onGoals = { navigator.navigate(Route.ReadingGoals) },
        saving = saving,
        onSaveImage = {
            saving = true
            scope.launch {
                val ok = MonthImage.save(context, layer, background, state.month)
                saving = false
                snackbar.showSnackbar(if (ok) saved else saveFailed)
            }
        },
    )

    CalendarContent(
        state = state,
        coverOf = viewModel::cover,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onThisMonth = viewModel::thisMonth,
        onRefresh = viewModel::refresh,
        onOpenDay = { navigator.navigate(Route.Day(it.toString())) },
        onOpenBook = { navigator.navigate(Route.BookDetail(it)) },
        contentPadding = contentPadding,
        snackbar = snackbar,
        captureLayer = layer,
    )
}

/** The calendar's actions in the shell's toolbar: Add a book, History, Reading goals, Save as image. */
@Composable
internal fun CalendarToolbar(onAddBook: () -> Unit, onHistory: () -> Unit, onGoals: () -> Unit, saving: Boolean, onSaveImage: () -> Unit) {
    TopBarActions {
        IconButton(onClick = onAddBook) {
            LucideIcon("Plus", contentDescription = stringResource(R.string.calendar_add_book), tint = LocalContentColor.current, size = 22.dp)
        }
        // The reading history: every book with its start and end dates (feature.history).
        IconButton(onClick = onHistory) {
            LucideIcon("RotateCcwClock", contentDescription = stringResource(R.string.history_open_history), tint = LocalContentColor.current, size = 22.dp, fallback = "CalendarRange")
        }
        IconButton(onClick = onGoals) {
            LucideIcon("Target", contentDescription = stringResource(R.string.calendar_goals_title), tint = LocalContentColor.current, size = 22.dp)
        }
        IconButton(enabled = !saving, onClick = onSaveImage) {
            LucideIcon("Download", contentDescription = stringResource(R.string.calendar_save_image), tint = LocalContentColor.current, size = 22.dp)
        }
    }
}

/**
 * The calendar: the month card (heading, month and its summary with arrows, the Monday-first grid
 * with each day's covers; swipe to change month) and the month's books. [captureLayer] records the
 * month card for "save as image".
 */
@Composable
fun CalendarContent(
    state: CalendarUiState,
    coverOf: (CalendarBook) -> Any?,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onThisMonth: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    onOpenBook: (Long) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    captureLayer: GraphicsLayer? = null,
) {
    val colors = OttershelfTheme.colors
    val direction = LocalLayoutDirection.current
    val pullState = rememberPullToRefreshState()
    // What each month last showed, so the month sliding out keeps its covers.
    val shown = remember { HashMap<YearMonth, CalendarMonth>() }
    state.data?.let { shown[YearMonth.parse(it.month)] = it }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = colors.card,
                    color = colors.primary,
                )
            },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = contentPadding.calculateStartPadding(direction) + 12.dp,
                        end = contentPadding.calculateEndPadding(direction) + 12.dp,
                        top = 12.dp,
                        bottom = contentPadding.calculateBottomPadding() + 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MonthCard(state, shown, coverOf, onPrevious, onNext, onThisMonth, onOpenDay, onRefresh, captureLayer)
                val books = remember(state.data, state.marks, state.month) {
                    CalendarMath.books(state.data?.takeIf { it.month == state.month.toString() }, state.marks, state.month)
                }
                if (books.isNotEmpty()) MonthBooks(books, state.month.year, coverOf, onOpenBook)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding()))
    }
}

@Composable
private fun MonthCard(
    state: CalendarUiState,
    shown: Map<YearMonth, CalendarMonth>,
    coverOf: (CalendarBook) -> Any?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onThisMonth: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onRetry: () -> Unit,
    captureLayer: GraphicsLayer?,
) {
    val colors = OttershelfTheme.colors
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestNext by rememberUpdatedState(onNext)
    val data = state.data?.takeIf { it.month == state.month.toString() }
    DashCard(
        Modifier
            .fillMaxWidth()
            .then(
                if (captureLayer != null) {
                    Modifier.drawWithContent {
                        captureLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(captureLayer)
                    }
                } else {
                    Modifier
                },
            )
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        val threshold = 64.dp.toPx()
                        if (total > threshold) latestPrevious() else if (total < -threshold) latestNext()
                    },
                ) { change, amount ->
                    change.consume()
                    total += amount
                }
            },
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle(stringResource(R.string.calendar_heading), icon = "CalendarDays")
                Text(
                    stringResource(R.string.calendar_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                    modifier = Modifier.padding(start = 24.dp, top = 1.dp),
                )
            }
            if (state.loading && !state.refreshing) SmallSpinner()
        }
        Spacer(Modifier.height(12.dp))
        MonthHeader(state, data, onPrevious, onNext, onThisMonth)
        Spacer(Modifier.height(12.dp))
        WeekdayRow()
        Spacer(Modifier.height(4.dp))
        AnimatedContent(
            targetState = state.month,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it else it } + fadeOut())
            },
            label = "month",
        ) { month ->
            MonthGrid(month, if (month == state.month) data else shown[month], state.marks, state.today, coverOf, onOpenDay)
        }
        val marked = remember(state.marks, state.month) { state.marks.keys.any { it.startsWith("${state.month}-") } }
        when {
            state.failed && data == null -> ErrorState(
                onRetry = onRetry,
                message = stringResource(R.string.calendar_load_failed),
                compact = true,
                contentPadding = PaddingValues(top = 12.dp),
            )
            state.failed -> Text(
                stringResource(R.string.calendar_refresh_failed),
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onRetry),
            )
            !state.loading && data != null && data.days.isEmpty() && !marked -> Text(
                stringResource(R.string.calendar_nothing_this_month),
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun SmallSpinner() {
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    if (inspection) {
        CircularProgressIndicator(progress = { 0.3f }, modifier = Modifier.size(16.dp), color = OttershelfTheme.colors.primary, strokeWidth = 2.dp)
    } else {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = OttershelfTheme.colors.primary, strokeWidth = 2.dp)
    }
}

/** "September 2026" between the arrows, the month's summary under it; tap the month for this month. */
@Composable
private fun MonthHeader(state: CalendarUiState, data: CalendarMonth?, onPrevious: () -> Unit, onNext: () -> Unit, onThisMonth: () -> Unit) {
    val colors = OttershelfTheme.colors
    val locale = Locale.getDefault()
    val title = remember(state.month, locale) { state.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) } }
    val summary = CalendarMath.summary(data)
    // The books the month's list shows: read, and started or ended there without a session.
    val books = remember(data, state.marks, state.month) { CalendarMath.books(data, state.marks, state.month).size }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = state.canGoBack) {
            Chevron(left = true, tint = if (state.canGoBack) colors.foreground else colors.mutedForeground.copy(alpha = 0.4f))
        }
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                .clickable(onClick = onThisMonth)
                .padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                color = colors.foreground,
                maxLines = 1,
            )
            Text(
                when {
                    summary.daysRead == 0 && books > 0 -> booksLabel(books)
                    summary.daysRead == 0 -> stringResource(if (data == null) R.string.calendar_summary_loading else R.string.calendar_summary_none)
                    else -> stringResource(
                        R.string.calendar_summary,
                        pluralStringResource(R.plurals.calendar_days_read, summary.daysRead, summary.daysRead),
                        durationLabel(summary.totalSeconds),
                        booksLabel(maxOf(books, summary.books)),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onNext, enabled = state.canGoForward) {
            Chevron(left = false, tint = if (state.canGoForward) colors.foreground else colors.mutedForeground.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun WeekdayRow() {
    val colors = OttershelfTheme.colors
    val locale = Locale.getDefault()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        DayOfWeek.entries.forEach { day ->
            Text(
                day.getDisplayName(TextStyle.SHORT, locale).take(3),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) colors.mutedForeground.copy(alpha = 0.7f) else colors.mutedForeground,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    data: CalendarMonth?,
    marks: Map<String, List<CalendarMark>>,
    today: LocalDate,
    coverOf: (CalendarBook) -> Any?,
    onOpenDay: (LocalDate) -> Unit,
) {
    val weeks = remember(month) { CalendarMath.weeks(month) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                week.forEach { date ->
                    val cellModifier = Modifier.weight(1f).aspectRatio(CELL_ASPECT)
                    if (date == null) {
                        Spacer(cellModifier)
                    } else {
                        DayCell(date, data?.day(date), marks[date.toString()].orEmpty(), today, coverOf, { onOpenDay(date) }, cellModifier)
                    }
                }
            }
        }
    }
}

/**
 * A day: its number (accent and framed for today), then the covers of the books read that day,
 * and of the books whose reading started or ended then (a finished or given-up one in front, with
 * its mark).
 */
@Composable
private fun DayCell(
    date: LocalDate,
    day: CalendarDay?,
    marks: List<CalendarMark>,
    today: LocalDate,
    coverOf: (CalendarBook) -> Any?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    val isToday = date == today
    val future = date > today
    val read = day != null && day.sessionsCount > 0
    val stack = remember(day, marks) { CalendarMath.stack(day, marks) }
    Column(
        modifier
            .clip(shape)
            .background(colors.muted.copy(alpha = if (read || marks.isNotEmpty()) 0.35f else 0.14f))
            .then(if (isToday) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .clickable(enabled = !future, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
            color = when {
                isToday -> colors.primary
                future -> colors.mutedForeground.copy(alpha = 0.45f)
                else -> colors.mutedForeground
            },
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
            maxLines = 1,
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 1.dp), contentAlignment = Alignment.Center) {
            when {
                stack.books.isNotEmpty() -> CoverStack(stack.books, stack.badge, coverOf)
                // A day with reading whose detail hasn't loaded yet.
                read -> Box(Modifier.size(6.dp).background(colors.primary, CircleShape))
            }
        }
    }
}

/**
 * A day's covers: one fills the space; two or three overlap diagonally (the first in front); more
 * show three and a "+N". [badge]: the front book was finished (a check) or given up (a cross) that day.
 */
@Composable
private fun CoverStack(books: List<CalendarBook>, badge: MarkKind?, coverOf: (CalendarBook) -> Any?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shown = books.take(MAX_STACK)
        val scale = when (shown.size) {
            1 -> 1f
            2 -> 0.74f
            else -> 0.64f
        }
        // The biggest 2:3 cover that fits, then scaled for the stack.
        val fullHeight = minOf(maxHeight, maxWidth * 1.5f)
        val coverHeight = fullHeight * scale
        val coverWidth = coverHeight / 1.5f
        val steps = (shown.size - 1).coerceAtLeast(1)
        val dx = if (shown.size == 1) 0.dp else (minOf(maxWidth, fullHeight / 1.5f + 6.dp) - coverWidth) / steps
        val dy = if (shown.size == 1) 0.dp else (fullHeight - coverHeight) / steps
        val left = (maxWidth - (coverWidth + dx * (shown.size - 1))) / 2
        val top = (maxHeight - (coverHeight + dy * (shown.size - 1))) / 2
        // Drawn back to front: the first last, on top.
        shown.indices.reversed().forEach { i ->
            val book = shown[i]
            BookCover(
                model = coverOf(book),
                title = book.title,
                modifier = Modifier
                    .offset(x = left + dx * i, y = top + dy * i)
                    .size(coverWidth, coverHeight)
                    .then(if (shown.size > 1) Modifier.border(0.5.dp, OttershelfTheme.colors.background.copy(alpha = 0.6f), RoundedCornerShape(OttershelfTheme.radii.sm)) else Modifier),
                seed = book.bookId.toString(),
            )
        }
        if (badge != null) {
            // On the front cover's bottom corner, over the covers behind it.
            MarkBadge(badge, Modifier.offset(x = left + coverWidth - MARK_SIZE + 3.dp, y = top + coverHeight - MARK_SIZE + 3.dp))
        }
        if (books.size > MAX_STACK) {
            Text(
                "+${books.size - MAX_STACK}",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(OttershelfTheme.colors.overlayPill, CircleShape)
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}

/** A finished book's check (the read colour) or a given-up one's cross (the abandoned colour), ringed in the page colour. */
@Composable
internal fun MarkBadge(kind: MarkKind, modifier: Modifier = Modifier, size: Dp = MARK_SIZE) {
    val colors = OttershelfTheme.colors
    val status = if (kind == MarkKind.GAVE_UP) ReadStatus.ABANDONED else ReadStatus.READ
    val description = stringResource(if (kind == MarkKind.GAVE_UP) R.string.calendar_mark_gave_up else R.string.calendar_mark_finished)
    Box(
        modifier
            .size(size)
            .background(colors.background, CircleShape)
            .padding(1.5.dp)
            .background(colors.readStatus(status), CircleShape)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(if (kind == MarkKind.GAVE_UP) "X" else "Check", contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, size = size * 0.62f)
    }
}

/** The month's books: cover, title, days and time (most read first), and the readings started or ended this month. */
@Composable
private fun MonthBooks(books: List<MonthBook>, year: Int, coverOf: (CalendarBook) -> Any?, onOpenBook: (Long) -> Unit) {
    val colors = OttershelfTheme.colors
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
        SectionHeader(stringResource(R.string.calendar_month_books), icon = "BookOpen", count = books.size, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            books.forEach { item ->
                CardRow(onClick = { onOpenBook(item.book.bookId) }) {
                    Box {
                        BookCover(coverOf(item.book), item.book.title, Modifier.width(36.dp), seed = item.book.bookId.toString())
                        val badge = if (item.finished != null) MarkKind.FINISHED else if (item.gaveUp != null) MarkKind.GAVE_UP else null
                        if (badge != null) MarkBadge(badge, Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp), size = 16.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                        Text(
                            item.book.title ?: stringResource(R.string.calendar_untitled),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.foreground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            monthBookLine(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.mutedForeground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Chevron(left = false, tint = colors.mutedForeground, size = 18.dp, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
}

/** "5 days · 3 h · Finished 21 Sept", or for a book only marked this month: "Started 3 Sept · Finished 21 Sept". */
@Composable
private fun monthBookLine(item: MonthBook): String {
    val locale = Locale.getDefault()
    val format = remember(locale) { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale) }
    val parts = buildList {
        if (item.days > 0) {
            add(pluralStringResource(R.plurals.calendar_days, item.days, item.days))
            add(durationLabel(item.seconds))
        } else {
            item.started?.let { add(stringResource(R.string.calendar_dates_started_on, it.format(format))) }
        }
        item.finished?.let { add(stringResource(R.string.calendar_dates_finished_on, it.format(format))) }
        item.gaveUp?.let { add(stringResource(R.string.calendar_dates_gave_up_on, it.format(format))) }
    }
    return parts.joinToString(" · ")
}

private val CELL_GAP: Dp = 3.dp

/** Width / height of a day cell: room for the day's number and a 2:3 cover. */
private const val CELL_ASPECT = 0.6f

private const val MAX_STACK = 3

/** A day cell's finished / given-up mark. */
private val MARK_SIZE: Dp = 14.dp
