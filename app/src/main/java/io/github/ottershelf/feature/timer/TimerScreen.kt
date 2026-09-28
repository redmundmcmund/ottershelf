package io.github.ottershelf.feature.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.ottershelf.AppContainer
import io.github.ottershelf.R
import io.github.ottershelf.core.settings.ProgressUnit
import io.github.ottershelf.core.settings.TimerMode
import io.github.ottershelf.core.settings.TimerPrefs
import io.github.ottershelf.core.tracking.ActiveTimer
import io.github.ottershelf.core.tracking.FinishedTimer
import io.github.ottershelf.core.tracking.StartResult
import io.github.ottershelf.core.tracking.TimerNotifications
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.CardRow
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.KeepScreenOn
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.components.rememberNotificationPermissionRequest
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.text.DateFormat
import java.util.Date

/** Countdown lengths offered with one tap. */
private val QUICK_MINUTES = listOf(15, 30, 45, 60)
private const val MIN_MINUTES = 5
private const val MAX_MINUTES = 240
private const val STEP_MINUTES = 5
/** Wider than tall and less than this high (a phone in landscape): the clock and the rest side by side. */
private val SIDE_BY_SIDE_BELOW = 520.dp

/**
 * The timer screen for one book. [active] is this book's running or paused timer, [finished] its
 * stopped timer waiting to be saved, [other] a timer running for another book (only one at a time).
 */
data class TimerUiState(
    val bookId: Long,
    /** The engine has read the timer from the device (until then nothing is known). */
    val loaded: Boolean = false,
    val book: TimerBook? = null,
    val active: ActiveTimer? = null,
    val finished: FinishedTimer? = null,
    val other: ActiveTimer? = null,
    val prefs: TimerPrefs = TimerPrefs(),
    /** The save sheet, while it shows. */
    val sheet: SaveForm? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
) {
    val title: String? get() = book?.title ?: active?.title?.ifBlank { null } ?: finished?.title?.ifBlank { null }
}

/** What the screen does once something is done. */
sealed interface TimerEvent {
    data class OpenResult(val sessionId: String) : TimerEvent
    data object Close : TimerEvent
}

private data class TimerLocal(
    val book: TimerBook? = null,
    val sheet: SaveForm? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
)

/**
 * The timer for [bookId]: the engine's state (container.timer), the book's pages, the timer
 * defaults in the app's settings key, and the save flow (Save pauses, the sheet asks for the page,
 * then stop + TrackingRepository.logTimedSession and the result screen).
 */
class TimerViewModel(private val container: AppContainer, private val bookId: Long) : ViewModel() {

    private val sessions = TimerSessions(container)
    private val local = MutableStateFlow(TimerLocal())
    private var loadJob: Job? = null

    val state: StateFlow<TimerUiState> = combine(
        container.timer.state,
        container.timer.loaded,
        container.appSettings.settings,
        local,
    ) { timer, loaded, settings, l ->
        TimerUiState(
            bookId = bookId,
            loaded = loaded,
            book = l.book,
            active = timer.active?.takeIf { it.bookId == bookId },
            finished = timer.finished?.takeIf { it.bookId == bookId },
            other = timer.active?.takeIf { it.bookId != bookId },
            prefs = settings.timer,
            sheet = l.sheet,
            saving = l.saving,
            saveError = l.saveError,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TimerUiState(bookId))

    /** The time, every second (while the screen shows). */
    val now: StateFlow<Long> = container.timer.ticks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), System.currentTimeMillis())

    private val _events = Channel<TimerEvent>(Channel.BUFFERED)
    val events: Flow<TimerEvent> = _events.receiveAsFlow()

    init {
        loadBook()
    }

    private fun loadBook() {
        loadJob = viewModelScope.launch {
            attempt { sessions.loadBook(bookId) }?.let { book -> local.update { it.copy(book = book) } }
        }
    }

    /**
     * Starts the timer at once (the reading starts at the tap). A book still loading joins it when
     * it arrives (its file and where it stood); one that never loads has its file looked up when
     * the session is saved.
     */
    fun start() {
        val prefs = container.appSettings.settings.value.timer
        val known = local.value.book
        container.appScope.launch {
            val result = container.timer.start(
                bookId = bookId,
                fileId = known?.fileId,
                title = known?.title.orEmpty(),
                mode = prefs.mode,
                targetMinutes = prefs.countdownMinutes.takeIf { prefs.mode == TimerMode.COUNTDOWN },
                startProgress = known?.startProgress,
            )
            if (known != null || result !is StartResult.Started) return@launch
            loadJob?.join()
            val book = local.value.book ?: return@launch
            container.timer.fillIn(result.timer.sessionId, book.fileId, book.title.orEmpty(), book.startProgress)
        }
    }

    /** Notification permission was just granted: show the timer's notification now. */
    fun refreshNotifications() {
        container.appScope.launch { container.timer.refreshNotifications() }
    }

    fun toggle() {
        container.appScope.launch { container.timer.toggle() }
    }

    fun setMode(mode: TimerMode) = container.appSettings.update { it.copy(timer = it.timer.copy(mode = mode)) }

    fun setMinutes(minutes: Int) =
        container.appSettings.update { it.copy(timer = it.timer.copy(countdownMinutes = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES))) }

    fun setKeepScreenOn(on: Boolean) = container.appSettings.update { it.copy(timer = it.timer.copy(keepScreenOn = on)) }

    /** Throws the timer (or its stopped session) away, then closes. */
    fun discard() {
        local.update { it.copy(sheet = null) }
        container.appScope.launch {
            val timer = container.timer.state.value
            when {
                timer.active?.bookId == bookId -> container.timer.discard()
                timer.finished?.bookId == bookId -> timer.finished?.let { container.timer.clearFinished(it.sessionId) }
            }
        }
        _events.trySend(TimerEvent.Close)
    }

    /** Save: pauses a running timer (the session ends here) and asks for the page reached. */
    fun openSave() {
        viewModelScope.launch {
            if (container.timer.state.value.active?.let { it.bookId == bookId && !it.paused } == true) container.timer.pause()
            if (local.value.book == null) {
                // It failed to load earlier (offline?): one more try for the page total, briefly.
                if (loadJob?.isActive != true) loadBook()
                withTimeoutOrNull(SHEET_BOOK_WAIT_MS) { loadJob?.join() }
            }
            val timer = container.timer.state.value
            val book = local.value.book
            val active = timer.active?.takeIf { it.bookId == bookId }
            val finished = timer.finished?.takeIf { it.bookId == bookId }
            val form = when {
                active != null -> sessions.form(book, active.activeMs(System.currentTimeMillis()), active.startProgress, active.title)
                finished != null -> sessions.form(book, finished.activeMs, finished.startProgress, finished.title)
                else -> return@launch
            }
            local.update { it.copy(sheet = form, saveError = null) }
        }
    }

    /** The sheet was closed: the timer stays paused until the user resumes it. */
    fun dismissSave() = local.update { it.copy(sheet = null, saveError = null) }

    fun save(entry: ProgressEntry, unit: ProgressUnit) {
        val current = local.value
        val form = current.sheet ?: return
        if (current.saving) return
        local.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            // In the app scope: leaving the screen mustn't cut a save in half.
            val work = container.appScope.async {
                val timer = container.timer.state.value
                // Saved right here: no "save your session" notification for it.
                val finished = if (timer.active?.bookId == bookId) container.timer.stop(announce = false) else timer.finished?.takeIf { it.bookId == bookId }
                finished?.let { sessions.save(it, local.value.book, form, entry, unit) }
            }
            val result = try {
                work.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(saving = false, saveError = e.message ?: e.javaClass.simpleName) }
                return@launch
            }
            local.update { it.copy(saving = false, sheet = null) }
            when (result) {
                is SaveResult.Saved -> {
                    TimerResults.put(result.summary)
                    _events.send(TimerEvent.OpenResult(result.summary.sessionId))
                }
                SaveResult.TooShort, SaveResult.AlreadySaved, null -> _events.send(TimerEvent.Close)
            }
        }
    }

    private companion object {
        const val SHEET_BOOK_WAIT_MS = 3_000L
    }
}

/** Everything the timer screen can do. */
data class TimerActions(
    val onBack: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onMode: (TimerMode) -> Unit = {},
    val onMinutes: (Int) -> Unit = {},
    val onKeepScreenOn: (Boolean) -> Unit = {},
    val onOpenOther: (Long) -> Unit = {},
    val onDismissSave: () -> Unit = {},
    val onConfirmSave: (ProgressEntry, ProgressUnit) -> Unit = { _, _ -> },
)

@Composable
fun TimerScreen(route: Route.Timer, navigator: AppNavigator) {
    val viewModel = appViewModel { TimerViewModel(it, route.bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    // Asked when a timer starts: without it the timer still runs, but shows no notification. The
    // timer has started by the time the user answers, so its notification is posted once the user allows it.
    val askNotifications = rememberNotificationPermissionRequest { granted -> if (granted) viewModel.refreshNotifications() }

    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is TimerEvent.OpenResult -> navigator.replace(Route.TimerResult(route.bookId, event.sessionId))
                TimerEvent.Close -> navigator.back()
            }
        }
    }

    // The screen stays on while the timer runs here, if the user wants it to.
    KeepScreenOn(state.prefs.keepScreenOn && state.active?.paused == false)

    TimerContent(
        state = state,
        now = now,
        actions = TimerActions(
            onBack = { navigator.back() },
            onStart = {
                askNotifications()
                viewModel.start()
            },
            onToggle = viewModel::toggle,
            onDiscard = viewModel::discard,
            onSave = viewModel::openSave,
            onMode = viewModel::setMode,
            onMinutes = viewModel::setMinutes,
            onKeepScreenOn = viewModel::setKeepScreenOn,
            onOpenOther = { navigator.replace(Route.Timer(it)) },
            onDismissSave = viewModel::dismissSave,
            onConfirmSave = viewModel::save,
        ),
    )
}

private enum class Phase { Loading, Setup, Running, Paused, Stopped, Busy }

/**
 * Bookmory's timer in the Nexus card style: the time large in a dashboard card with the big
 * pause/resume button, Cancel and Save under it, the keep-screen-on switch, and the book's strip
 * at the bottom. Before starting, the card picks count up or countdown (and its minutes).
 */
@Composable
fun TimerContent(state: TimerUiState, now: Long, actions: TimerActions = TimerActions()) {
    val direction = LocalLayoutDirection.current
    val phase = when {
        !state.loaded -> Phase.Loading
        state.active != null -> if (state.active.paused) Phase.Paused else Phase.Running
        state.finished != null -> Phase.Stopped
        state.other != null -> Phase.Busy
        else -> Phase.Setup
    }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.timer_title), onBack = actions.onBack) },
        containerColor = OttershelfTheme.colors.background,
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(
                    start = padding.calculateStartPadding(direction) + 12.dp,
                    end = padding.calculateEndPadding(direction) + 12.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 12.dp,
                ),
        ) {
            // Landscape on a phone: the clock on the left at full height, the rest beside it (with
            // Start, which wouldn't fit under the countdown picker).
            val sideBySide = maxWidth > maxHeight && maxHeight < SIDE_BY_SIDE_BELOW
            // The clock card takes the room left; its content scrolls when that is too little.
            val clock: @Composable (Modifier) -> Unit = { modifier ->
                DashCard(modifier, contentPadding = PaddingValues(20.dp)) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val viewport = maxHeight
                        Box(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (phase) {
                                Phase.Loading -> LoadingState()
                                Phase.Setup -> SetupBody(state.prefs, actions, withStart = !sideBySide)
                                Phase.Busy -> BusyBody(state.other, actions)
                                Phase.Running, Phase.Paused, Phase.Stopped -> ClockBody(state, now, phase, actions)
                            }
                        }
                    }
                }
            }
            val controls: @Composable () -> Unit = {
                if (sideBySide && phase == Phase.Setup) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BigButton(icon = "Play", label = stringResource(R.string.timer_start), onClick = actions.onStart)
                    }
                }
                if (phase == Phase.Running || phase == Phase.Paused || phase == Phase.Stopped) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(
                            stringResource(R.string.timer_cancel),
                            onClick = { confirmCancel = true },
                            icon = "X",
                            modifier = Modifier.weight(1f),
                        )
                        AccentButton(
                            stringResource(R.string.timer_save),
                            onClick = actions.onSave,
                            icon = "Check",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                KeepScreenOnRow(state.prefs.keepScreenOn, actions.onKeepScreenOn)
                BookStrip(state.book, state.title)
            }
            if (sideBySide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    clock(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    ) { controls() }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    clock(Modifier.fillMaxWidth().weight(1f))
                    controls()
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            containerColor = OttershelfTheme.colors.popover,
            title = { Text(stringResource(R.string.timer_cancel_title)) },
            text = { Text(stringResource(R.string.timer_cancel_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    actions.onDiscard()
                }) { Text(stringResource(R.string.timer_cancel_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.timer_keep_reading)) }
            },
        )
    }

    state.sheet?.let { form ->
        SaveSessionSheet(
            form = form,
            saving = state.saving,
            error = state.saveError,
            onSave = actions.onConfirmSave,
            onDismiss = actions.onDismissSave,
            onDiscard = actions.onDiscard,
        )
    }
}

/** Before starting: count up or countdown, the countdown's minutes, and Start ([withStart]; landscape puts it beside the card). */
@Composable
private fun SetupBody(prefs: TimerPrefs, actions: TimerActions, withStart: Boolean = true) {
    val colors = OttershelfTheme.colors
    val countdown = prefs.mode == TimerMode.COUNTDOWN
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Segmented(
            options = listOf(stringResource(R.string.timer_count_up), stringResource(R.string.timer_countdown)),
            selected = if (countdown) 1 else 0,
            onSelect = { actions.onMode(if (it == 0) TimerMode.COUNT_UP else TimerMode.COUNTDOWN) },
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(if (withStart) 28.dp else 12.dp))
        Text(stringResource(R.string.timer_ready), style = MaterialTheme.typography.titleMedium, color = colors.mutedForeground)
        Text(
            TimerNotifications.clock(if (countdown) prefs.countdownMinutes * 60_000L else 0L),
            style = clockStyle(),
            color = colors.foreground,
        )
        if (countdown) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(
                    "Minus",
                    stringResource(R.string.timer_less),
                    onClick = { actions.onMinutes(prefs.countdownMinutes - STEP_MINUTES) },
                    enabled = prefs.countdownMinutes > MIN_MINUTES,
                )
                Text(
                    stringResource(R.string.timer_minutes, prefs.countdownMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.foreground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(96.dp),
                )
                RoundIconButton(
                    "Plus",
                    stringResource(R.string.timer_more),
                    onClick = { actions.onMinutes(prefs.countdownMinutes + STEP_MINUTES) },
                    enabled = prefs.countdownMinutes < MAX_MINUTES,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_MINUTES.forEach { m ->
                    ChoicePill(stringResource(R.string.timer_minutes, m), selected = prefs.countdownMinutes == m, onClick = { actions.onMinutes(m) })
                }
            }
        }
        if (withStart) {
            Spacer(Modifier.height(32.dp))
            BigButton(icon = "Play", label = stringResource(R.string.timer_start), onClick = actions.onStart)
        }
    }
}

/** The running, paused or stopped timer: status, the time, and the big pause/resume button. */
@Composable
private fun ClockBody(state: TimerUiState, now: Long, phase: Phase, actions: TimerActions) {
    val colors = OttershelfTheme.colors
    val active = state.active
    val finished = state.finished
    val over = active?.overtime(now) == true
    val label = when (phase) {
        Phase.Running -> if (over) R.string.timer_times_up else R.string.timer_reading
        Phase.Paused -> R.string.timer_paused
        else -> R.string.timer_stopped
    }
    val time = when {
        active != null -> clockText(active, now)
        finished != null -> TimerNotifications.clock(finished.activeMs)
        else -> ""
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleMedium,
            color = if (phase == Phase.Running) colors.primary else colors.mutedForeground,
        )
        Text(
            time,
            style = clockStyle(),
            color = when {
                over -> colors.primary
                phase == Phase.Running -> colors.foreground
                else -> colors.foreground.copy(alpha = 0.7f)
            },
            maxLines = 1,
        )
        val target = active?.targetMs ?: finished?.targetMs
        if (target != null && target > 0) {
            val done = (active?.activeMs(now) ?: finished?.activeMs ?: 0L).toFloat() / target
            Spacer(Modifier.height(6.dp))
            PillProgressBar(done.coerceIn(0f, 1f), Modifier.width(220.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.timer_goal_of, (target / 60_000L).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedForeground,
            )
        } else {
            val started = active?.startedAtMs ?: finished?.startedAtMs
            if (started != null) {
                val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.timer_started_at, format.format(Date(started))),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        when (phase) {
            Phase.Running -> BigButton(icon = "Pause", label = stringResource(R.string.timer_pause), onClick = actions.onToggle)
            Phase.Paused -> BigButton(icon = "Play", label = stringResource(R.string.timer_resume), onClick = actions.onToggle)
            else -> Text(
                stringResource(R.string.timer_stopped_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
    }
}

/** Another book's timer is running: only one at a time. */
@Composable
private fun BusyBody(other: ActiveTimer?, actions: TimerActions) {
    val colors = OttershelfTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LucideIcon("Timer", contentDescription = null, tint = colors.mutedForeground, size = 36.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.timer_busy, other?.title?.ifBlank { null } ?: stringResource(R.string.timer_untitled)),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.foreground,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        if (other != null) {
            Spacer(Modifier.height(18.dp))
            AccentButton(stringResource(R.string.timer_open_other), onClick = { actions.onOpenOther(other.bookId) }, icon = "Timer")
        }
    }
}

/** The big round start / pause / resume button with its word under it. */
@Composable
private fun BigButton(icon: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIconButton(icon, label, onClick = onClick, size = 96.dp, iconSize = 38.dp, filled = true, iconOffset = if (icon == "Play") 4.dp else 0.dp)
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = OttershelfTheme.colors.mutedForeground)
    }
}

@Composable
private fun KeepScreenOnRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = OttershelfTheme.colors
    CardRow(
        onClick = { onChange(!on) },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 14.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
    ) {
        LucideIcon("Sun", contentDescription = null, tint = if (on) colors.primary else colors.mutedForeground, size = 18.dp)
        Text(
            stringResource(R.string.timer_keep_screen_on),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.foreground,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Switch(checked = on, onCheckedChange = onChange)
    }
}
