package io.github.ottershelf.core.tracking

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.settings.TimerMode
import java.util.UUID

/**
 * A running (or paused) reading timer. All times are wall-clock epoch milliseconds, so nothing has
 * to keep running while the app is gone: the active time is worked out from them.
 */
@Serializable
data class ActiveTimer(
    /** The session's id when it is saved (the file route's retry-safe `sessionId`). */
    val sessionId: String,
    val bookId: Long,
    /** The file the session is recorded against; null for a book with no file (manual route). */
    val fileId: Long?,
    val title: String,
    /** When the timer was first started. */
    val startedAtMs: Long,
    /** Active time before the current run. */
    val accumulatedMs: Long = 0,
    /** When the current run started; null while paused. */
    val runningSinceMs: Long? = null,
    /** When it was last paused (the session's end if it's stopped while paused). */
    val pausedAtMs: Long? = null,
    val mode: TimerMode = TimerMode.COUNT_UP,
    /** The countdown's length (active time); null counting up. */
    val targetMs: Long? = null,
    /** Progress (0..100) when it started, for the session's progressDelta. */
    val startProgress: Double? = null,
    /** The active stretches before the current run (a session paused over midnight is split by day). */
    val spans: List<ActiveSpan> = emptyList(),
) {
    val paused: Boolean get() = runningSinceMs == null

    /** Active reading time at [now]: paused time never counts. */
    fun activeMs(now: Long): Long = accumulatedMs + (runningSinceMs?.let { (now - it).coerceAtLeast(0) } ?: 0)

    /** Countdown: active time left at [now] (negative once past the target); null counting up. */
    fun remainingMs(now: Long): Long? = targetMs?.let { it - activeMs(now) }

    /** When the last active stretch ended: now while running, the pause while paused. */
    fun lastActiveMs(now: Long): Long = if (paused) (pausedAtMs ?: now) else now

    /** Every active stretch up to [now] (the current run included). */
    fun spansUntil(now: Long): List<ActiveSpan> = runningSinceMs?.takeIf { now > it }?.let { spans + ActiveSpan(it, now) } ?: spans

    /** Paused at [now]; unchanged if it already is. */
    fun pausedAt(now: Long): ActiveTimer =
        if (paused) this else copy(accumulatedMs = activeMs(now), runningSinceMs = null, pausedAtMs = now, spans = spansUntil(now))
}

/** A stopped timer whose session hasn't been saved yet (the result screen asks for the page). */
@Serializable
data class FinishedTimer(
    val sessionId: String,
    val bookId: Long,
    val fileId: Long?,
    val title: String,
    val startedAtMs: Long,
    /** The end of the last active stretch (not the moment Stop was tapped after a long pause). */
    val endedAtMs: Long,
    val activeMs: Long,
    val mode: TimerMode = TimerMode.COUNT_UP,
    val targetMs: Long? = null,
    val startProgress: Double? = null,
    /** Its active stretches (for TrackingRepository.logTimedSession's split by day). */
    val spans: List<ActiveSpan> = emptyList(),
) {
    val activeSeconds: Int get() = (activeMs / 1000).toInt()

    /** The server drops sessions under 10 s, so there is nothing to save. */
    val tooShort: Boolean get() = activeSeconds < TrackingRepository.MIN_SESSION_SECONDS
}

/** The timer as the UI sees it: at most one active timer, and at most one result waiting. */
@Serializable
data class TimerState(val active: ActiveTimer? = null, val finished: FinishedTimer? = null)

sealed interface StartResult {
    data class Started(val timer: ActiveTimer) : StartResult

    /** A timer is already on for this book: nothing changed. */
    data class AlreadyRunning(val timer: ActiveTimer) : StartResult

    /** A timer is on for another book; stop it first. */
    data class Busy(val timer: ActiveTimer) : StartResult
}

/** The system side of the timer (notifications and the countdown alarm), faked in tests. */
interface TimerNotifier {
    fun showActive(timer: ActiveTimer, now: Long)
    fun cancelActive()
    fun showFinished(timer: FinishedTimer)
    fun cancelFinished()
    fun scheduleTimesUp(atMs: Long)
    fun cancelTimesUp()
    fun showTimesUp(timer: ActiveTimer)
}

/**
 * The reading timer: one timer at a time per account, count-up or countdown, kept in the settings
 * DataStore (`tracking.timer.<account>`) so it survives the app being killed; the active time is
 * wall-clock arithmetic, so no service has to run. While it's on, an ongoing notification shows a
 * chronometer with Pause/Resume and Stop ([TimerNotifier], TimerNotifications, TimerActionReceiver).
 *
 * Stopping turns it into a [FinishedTimer] that waits (also persisted) for the result screen to
 * save it (TrackingRepository.logTimedSession with its sessionId, then [clearFinished]). If
 * another timer is stopped before that, the older result is saved without a page
 * ([saveUnconfirmed]), so no reading time is lost.
 *
 * The timer belongs to the account signed in when it started: signing out (or the server ending
 * the session) pauses a running timer at that moment and hides it, signing in as that account again
 * brings it back, paused. Mutations are suspend functions that finish their write even if the
 * caller is cancelled; call them from any coroutine.
 */
class TimerEngine(
    private val store: DataStore<Preferences>,
    /** The signed-in account's key, or null while signed out. */
    private val account: Flow<String?>,
    private val notifier: TimerNotifier,
    private val scope: CoroutineScope,
    private val saveUnconfirmed: suspend (FinishedTimer) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(TimerState())

    /** The current account's timer. */
    val state: StateFlow<TimerState> = _state.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** Whether the current account's timer has been read from the device. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val mutex = Mutex()
    private var currentAccount: String? = null

    /** Follows the account: loads its timer and shows (or hides) the notification. */
    fun start() {
        scope.launch {
            account.distinctUntilChanged().collect { key -> switchTo(key) }
        }
    }

    private suspend fun switchTo(key: String?) = mutex.withLock {
        val previous = currentAccount
        val running = _state.value.active?.takeUnless { it.paused }
        if (previous != null && previous != key && running != null) {
            // Leaving the account while it runs (sign-out, or the server ended the session): with
            // its notification gone nothing could pause it, so it stops counting here.
            write(previous, _state.value.copy(active = running.pausedAt(clock())))
        }
        currentAccount = key
        _loaded.value = false
        val loaded = if (key == null) TimerState() else read(key)
        _state.value = loaded
        // Brought back (app start, sign-in, a reboot): a result still waiting is announced again.
        showNotifications(loaded, announceFinished = true)
        _loaded.value = key != null
    }

    /**
     * Starts a timer for [bookId] (running from now). [targetMinutes] makes it a countdown.
     * [startProgress] (0..100) is where the book stood, for the session's progress gained.
     */
    suspend fun start(
        bookId: Long,
        fileId: Long?,
        title: String,
        mode: TimerMode = TimerMode.COUNT_UP,
        targetMinutes: Int? = null,
        startProgress: Double? = null,
    ): StartResult = mutate { state ->
        val running = state.active
        when {
            running != null && running.bookId == bookId -> state to StartResult.AlreadyRunning(running)
            running != null -> state to StartResult.Busy(running)
            else -> {
                val now = clock()
                val countdown = mode == TimerMode.COUNTDOWN && targetMinutes != null && targetMinutes > 0
                val timer = ActiveTimer(
                    sessionId = newId(),
                    bookId = bookId,
                    fileId = fileId,
                    title = title,
                    startedAtMs = now,
                    runningSinceMs = now,
                    mode = if (countdown) TimerMode.COUNTDOWN else TimerMode.COUNT_UP,
                    targetMs = if (countdown) (targetMinutes ?: 0) * 60_000L else null,
                    startProgress = startProgress,
                )
                state.copy(active = timer) to StartResult.Started(timer)
            }
        }
    }

    /** Pauses the active timer (paused time never counts). */
    suspend fun pause() {
        mutate { state ->
            val timer = state.active?.takeUnless { it.paused } ?: return@mutate state to Unit
            state.copy(active = timer.pausedAt(clock())) to Unit
        }
    }

    suspend fun resume() {
        mutate { state ->
            val timer = state.active?.takeIf { it.paused } ?: return@mutate state to Unit
            state.copy(active = timer.copy(runningSinceMs = clock())) to Unit
        }
    }

    /** Pauses a running timer, resumes a paused one. */
    suspend fun toggle() {
        if (_state.value.active?.paused == true) resume() else pause()
    }

    /**
     * Stops the active timer: it becomes the [TimerState.finished] result, waiting to be saved.
     * Returns it (null if no timer was on).
     *
     * [atMs]: when the reading stopped, if that was before now (the reader opened on the same book
     * records its own session from then on). [announce] = false when the caller saves it straight
     * away: the "save your session" notification is only for a stop that leaves it waiting (the
     * notification's Stop button).
     */
    suspend fun stop(atMs: Long? = null, announce: Boolean = true): FinishedTimer? {
        var replaced: FinishedTimer? = null
        val finished = mutate(announce) { state ->
            val timer = state.active ?: return@mutate state to null
            val now = (atMs?.coerceAtMost(clock()) ?: clock()).coerceAtLeast(timer.runningSinceMs ?: timer.startedAtMs)
            val done = FinishedTimer(
                sessionId = timer.sessionId,
                bookId = timer.bookId,
                fileId = timer.fileId,
                title = timer.title,
                startedAtMs = timer.startedAtMs,
                endedAtMs = timer.lastActiveMs(now),
                activeMs = timer.activeMs(now),
                mode = timer.mode,
                targetMs = timer.targetMs,
                startProgress = timer.startProgress,
                spans = timer.spansUntil(now),
            )
            replaced = state.finished?.takeIf { it.sessionId != done.sessionId && !it.tooShort }
            TimerState(active = null, finished = done) to done
        }
        replaced?.let { old -> scope.launch { runCatching { saveUnconfirmed(old) } } }
        return finished
    }

    /**
     * What a timer started before its book had loaded learns once it has: the file its session is
     * recorded against, the title and where the book stood. Only fills what is still missing.
     */
    suspend fun fillIn(sessionId: String, fileId: Long?, title: String, startProgress: Double?) {
        mutate { state ->
            val active = state.active?.takeIf { it.sessionId == sessionId }
                ?.let { it.copy(fileId = it.fileId ?: fileId, title = it.title.ifBlank { title }, startProgress = it.startProgress ?: startProgress) }
            val finished = state.finished?.takeIf { it.sessionId == sessionId }
                ?.let { it.copy(fileId = it.fileId ?: fileId, title = it.title.ifBlank { title }, startProgress = it.startProgress ?: startProgress) }
            state.copy(active = active ?: state.active, finished = finished ?: state.finished) to Unit
        }
    }

    /** Posts the notifications again (notification permission was just granted). */
    suspend fun refreshNotifications() {
        mutex.withLock { showNotifications(_state.value, announceFinished = true) }
    }

    /** Drops the active timer without saving anything. */
    suspend fun discard() {
        mutate { state -> state.copy(active = null) to Unit }
    }

    /** The result [sessionId] was saved (or thrown away): forget it. */
    suspend fun clearFinished(sessionId: String) {
        mutate { state -> (if (state.finished?.sessionId == sessionId) state.copy(finished = null) else state) to Unit }
    }

    /** The countdown alarm went off: announce it if the target really is reached, else set it again. */
    suspend fun onTimesUp() {
        mutex.withLock {
            val timer = _state.value.active ?: return
            if (timer.paused || timer.targetMs == null) return
            val remaining = timer.remainingMs(clock()) ?: return
            if (remaining <= TIMES_UP_SLACK_MS) {
                notifier.showTimesUp(timer)
                notifier.showActive(timer, clock()) // the chronometer now counts the time past the target
            } else {
                notifier.scheduleTimesUp(clock() + remaining)
            }
        }
    }

    /** The current time, every second on the second, for a screen showing the timer. */
    fun ticks(): Flow<Long> = flow {
        while (true) {
            val now = clock()
            emit(now)
            delay(1_000 - now % 1_000)
        }
    }

    /** The active time of the current timer right now (0 without one). */
    fun activeMsNow(): Long = _state.value.active?.activeMs(clock()) ?: 0

    private suspend fun <R> mutate(announce: Boolean = true, change: (TimerState) -> Pair<TimerState, R>): R = withContext(NonCancellable) {
        mutex.withLock {
            val before = _state.value
            val (after, result) = change(before)
            if (after != before) {
                _state.value = after
                currentAccount?.let { write(it, after) }
                showNotifications(after, announceFinished = announce && before.finished != after.finished)
            }
            result
        }
    }

    /**
     * The ongoing notification and alarm follow [now]; "save your session" is posted when
     * [announceFinished] (a new result, or one brought back) and cancelled once there is none.
     */
    private fun showNotifications(now: TimerState, announceFinished: Boolean) {
        val active = now.active
        if (active != null) {
            notifier.showActive(active, clock())
            val remaining = active.remainingMs(clock())
            if (!active.paused && remaining != null && remaining > 0) notifier.scheduleTimesUp(clock() + remaining)
            else notifier.cancelTimesUp()
        } else {
            notifier.cancelActive()
            notifier.cancelTimesUp()
        }
        val finished = now.finished
        if (finished != null && !finished.tooShort) {
            if (announceFinished) notifier.showFinished(finished)
        } else {
            notifier.cancelFinished()
        }
    }

    private suspend fun read(account: String): TimerState = try {
        store.data.first()[key(account)]?.let { ApiJson.decodeFromString(TimerState.serializer(), it) } ?: TimerState()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TimerState()
    }

    private suspend fun write(account: String, state: TimerState) {
        try {
            store.edit { prefs ->
                if (state.active == null && state.finished == null) prefs.remove(key(account))
                else prefs[key(account)] = ApiJson.encodeToString(TimerState.serializer(), state)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A full disk: the timer still works until the app is killed.
        }
    }

    private fun key(account: String) = stringPreferencesKey("tracking.timer.$account")

    private companion object {
        const val TIMES_UP_SLACK_MS = 1_500L
    }
}
