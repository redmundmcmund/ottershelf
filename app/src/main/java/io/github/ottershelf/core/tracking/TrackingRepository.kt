package io.github.ottershelf.core.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.UserSettings
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.core.util.IsoTime
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** How saving a session went. */
sealed interface SessionSaveResult {
    /** On the server. */
    data object Sent : SessionSaveResult

    /** Kept on the device; it goes when the server can be reached (WorkManager retries). */
    data object Queued : SessionSaveResult

    /** Under 10 active seconds: the server would drop it, so it wasn't kept. */
    data object TooShort : SessionSaveResult

    /** The server (or the rules it enforces) refused it; it won't be retried. */
    data class Rejected(val message: String) : SessionSaveResult
}

/** Something the user should hear about that happened in the background. */
sealed interface TrackingEvent {
    /** A queued session the server refused when it was finally sent; it was dropped. */
    data class SessionRejected(val bookId: Long, val message: String) : TrackingEvent
}

/**
 * The reading tracker's server data: sessions, statuses and readings (attempts), rating and
 * private review, statistics and achievements. Every route and body is checked against the
 * BookOrbit source (its server controllers and DTOs).
 *
 * - **Reads** are plain suspend calls that throw the Api's exceptions; screens cache what they need.
 *   `activity-calendar` answers 400 for a year before the first session: that is an empty year here.
 * - **Sessions are queued:** [logTimedSession] and [logManualSession] store the session on the
 *   device first, then send it; what can't be sent waits for WorkManager ([onQueued],
 *   TrackingWorker -> [flush]). Timed sessions go through the file route with a phone-made UUID,
 *   so resending is harmless. Manual sessions are not retry-safe: one whose answer never came is
 *   looked for in the book's sessions before it is sent again.
 * - **Other writes** (status, readings, rating, review, delete/move a session, the yearly goal)
 *   go straight to the server and throw when they can't.
 * - **After every write** the Dashboard is told ([ReadingChanges.changed]) and the book's
 *   [bookVersion] goes up, so an open book page (or calendar, via [version]) reloads.
 */
class TrackingRepository(
    private val remote: TrackingRemote,
    private val queue: TrackingQueue,
    private val accountKey: () -> String,
    /** The signed-in user, or null while signed out. */
    private val account: StateFlow<AuthUser?>,
    private val updateUser: (AuthUser) -> Unit,
    private val readingChanges: ReadingChanges,
    private val scope: CoroutineScope,
    /** Schedules a background flush (WorkManager, when connected). */
    private val onQueued: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Whether this user may set a rating (`library_edit_metadata`, not a demo account). */
    val canRate: StateFlow<Boolean> = account
        .map { canRate(it) }
        .stateIn(scope, SharingStarted.Eagerly, canRate(account.value))

    private val _version = MutableStateFlow(0)

    /** Goes up after any tracking write from this device (calendars, stats and goals reload on it). */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val bookVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** Goes up whenever this device changes [bookId]'s tracking data (sessions, status, readings, rating, review). */
    fun bookVersion(bookId: Long): Flow<Int> = bookVersions.map { it[bookId] ?: 0 }.distinctUntilChanged()

    private val _pending = MutableStateFlow(0)

    /** Sessions waiting on this device to be sent. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val _events = MutableSharedFlow<TrackingEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<TrackingEvent> = _events.asSharedFlow()

    private val flushMutex = Mutex()

    /** Reads the queue's size and sends what's waiting (at app start and after sign-in). */
    fun start() {
        scope.launch {
            account.map { it?.id }.distinctUntilChanged().collect { id ->
                if (id == null) {
                    _pending.value = 0
                    synchronized(unacknowledged) { unacknowledged.clear() }
                    _unshown.value = emptyList()
                } else {
                    _pending.value = queue.read(accountKey()).size
                    if (_pending.value > 0) flushInBackground()
                }
            }
        }
    }

    // --- reads --------------------------------------------------------------------------------

    /** One page of a book's sessions with their stats (defaults: the newest 100). */
    suspend fun sessions(bookId: Long, query: SessionQuery = SessionQuery()): BookSessionList = remote.sessions(bookId, query)

    /** A book's readings, newest first as the server orders them (up to 100). */
    suspend fun attempts(bookId: Long): ReadingAttemptList = remote.attempts(bookId, page = 1, pageSize = 100)

    suspend fun activityOverview(): ActivityOverview = remote.activityOverview()

    /** Every day of [year]; empty (no days) for a year before the first session. */
    suspend fun activityCalendar(year: Int): ActivityCalendar = try {
        remote.activityCalendar(year)
    } catch (e: ApiException) {
        if (e.code == 400) ActivityCalendar(year = year) else throw e
    }

    /** One day's sessions across all books (the server's local day). */
    suspend fun activityDay(day: LocalDate): ActivityDayDetail = remote.activityDay(day.toString())

    /** An ISO week's sessions (UTC weeks). */
    suspend fun sessionTimeline(year: Int, week: Int): SessionTimeline = remote.sessionTimeline(year, week)

    // --- achievement celebrations -------------------------------------------------------------

    /** Acknowledgements that couldn't be sent: sent before the next claim. */
    private val unacknowledged = LinkedHashSet<String>()

    private val _claimed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** An achievement was claimed on this device: something was earned, so the catalogue has changed. */
    val claimed: SharedFlow<Unit> = _claimed.asSharedFlow()

    private val _unshown = MutableStateFlow<List<CelebrationClaim>>(emptyList())

    /** Claims a screen received as it closed (the book page's finish flow), for the shell to show. */
    val unshownClaims: StateFlow<List<CelebrationClaim>> = _unshown.asStateFlow()

    /**
     * An earned achievement not yet celebrated, or null; acknowledge it once shown. Acknowledgements
     * that failed earlier go first, so the server doesn't offer those again.
     */
    suspend fun claimCelebration(): CelebrationClaim? {
        retryAcknowledgements()
        return remote.claimCelebration()?.also { _claimed.tryEmit(Unit) }
    }

    /**
     * Marks [claimId] celebrated. The server offers an unacknowledged claim again after 15 minutes,
     * so one that can't be sent (no connection, a server error) is kept and sent before the next
     * claim; a refusal (4xx: already gone, or someone else's) isn't retried.
     */
    suspend fun acknowledgeCelebration(claimId: String) {
        try {
            remote.acknowledgeCelebration(claimId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!e.refused()) synchronized(unacknowledged) { unacknowledged += claimId }
            throw e
        }
    }

    private suspend fun retryAcknowledgements() {
        for (id in synchronized(unacknowledged) { unacknowledged.toList() }) {
            val done = try {
                remote.acknowledgeCelebration(id)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.refused()
            }
            if (!done) return // still unreachable: the claim will find out too
            synchronized(unacknowledged) { unacknowledged -= id }
        }
    }

    private fun Exception.refused() = this is ApiException && code in 400..499

    /** Hands a claim a closing screen couldn't show to the shell ([unshownClaims]). */
    fun handOverClaim(claim: CelebrationClaim) = _unshown.update { it + claim }

    /** The oldest handed-over claim (removed), or null. */
    fun takeUnshownClaim(): CelebrationClaim? {
        var taken: CelebrationClaim? = null
        _unshown.update { list ->
            taken = list.firstOrNull()
            list.drop(1)
        }
        return taken
    }

    // --- sessions -----------------------------------------------------------------------------

    /**
     * Saves a timed session (from the timer). With a [fileId] it goes through the file route
     * (retry-safe, changes the read status and achievements like any reader session); without
     * one, as a manual session of the rounded minutes. [sessionId] is the timer's UUID, so saving
     * the same timer twice is harmless (a manual one queued under it isn't queued twice).
     * [startProgress] and [endProgress] are 0..100 (from pages via PageMath); either may be null
     * when unknown.
     *
     * [spans] are the timer's active stretches: a pause across midnight (the account's timezone)
     * splits the session there ([TimedParts]), so each day gets its own reading time; the end
     * position goes with the last part and the progress gained is shared by active time.
     * [stored] runs once the session is safely on the device and before it is sent (the timer
     * forgets its result there, so a slow send can't be saved a second time).
     */
    suspend fun logTimedSession(
        bookId: Long,
        fileId: Long?,
        sessionId: String,
        startedAtMs: Long,
        endedAtMs: Long,
        activeSeconds: Int,
        startProgress: Double? = null,
        endProgress: Double? = null,
        sessionType: String = "read",
        spans: List<ActiveSpan> = emptyList(),
        stored: suspend () -> Unit = {},
    ): SessionSaveResult {
        if (activeSeconds < MIN_SESSION_SECONDS) return SessionSaveResult.TooShort
        val parts = TimedParts.split(sessionId, startedAtMs, maxOf(endedAtMs, startedAtMs), activeSeconds, spans, zone())
        val end = endProgress?.coerceIn(0.0, 100.0)?.let(::round2)
        val deltas = shares(if (startProgress != null && end != null) end - startProgress else null, parts)
        val keys = parts.mapTo(LinkedHashSet()) { it.id }
        val account = accountKey()
        if (fileId == null) {
            val minutes = parts.map { ((it.activeSeconds + 30) / 60).coerceAtLeast(1) }
            if (minutes.any { it > MAX_MANUAL_MINUTES }) return SessionSaveResult.Rejected("Minutes must be between 1 and $MAX_MANUAL_MINUTES")
            val items = parts.mapIndexed { i, p ->
                val body = ManualSessionBody(IsoTime.format(p.startMs), minutes[i], endProgress = end.takeIf { i == parts.lastIndex })
                PendingManualSession(p.id, bookId, body)
            }
            // Keyed by the timer's id: an item already waiting (maybe sent without an answer) stays as it is.
            queue.update(account) { q -> q.copy(manual = q.manual + items.filter { i -> q.manual.none { it.localId == i.localId } }) }
        } else {
            val items = parts.mapIndexed { i, p ->
                val body = TimedSessionBody(
                    sessionId = p.id,
                    startedAt = IsoTime.format(p.startMs),
                    endedAt = IsoTime.format(p.endMs),
                    durationSeconds = p.activeSeconds.coerceAtMost(((p.endMs - p.startMs) / 1000).toInt().coerceAtLeast(0)),
                    progressDelta = deltas[i],
                    endProgress = end.takeIf { i == parts.lastIndex },
                    sessionType = sessionType,
                )
                PendingTimedSession(bookId, fileId, body)
            }
            queue.update(account) { q -> q.copy(timed = q.timed.filterNot { it.body.sessionId in keys } + items) }
        }
        stored()
        return sendNow(account, keys)
    }

    /** [delta] shared among [parts] by active time (the last takes the rounding), null stays null. */
    private fun shares(delta: Double?, parts: List<TimedPart>): List<Double?> {
        if (delta == null) return parts.map { null }
        if (parts.size == 1) return listOf(round2(delta))
        val total = parts.sumOf { it.activeSeconds }.coerceAtLeast(1).toDouble()
        val first = parts.dropLast(1).map { round2(delta * it.activeSeconds / total) }
        return first + round2(delta - first.sum())
    }

    /**
     * Logs reading by hand: [minutes] (1..1440) starting at [startedAtMs] (not in the future),
     * optionally where it ended ([endProgress], 0..100). The server works out the progress gained.
     * [startReadingOn]: once the session is in, a book that isn't started yet (unread or want to
     * read, checked on the server then) is set to reading from that day; it waits in the queue
     * with the session when offline.
     */
    suspend fun logManualSession(
        bookId: Long,
        startedAtMs: Long,
        minutes: Int,
        endProgress: Double? = null,
        format: String? = null,
        startReadingOn: LocalDate? = null,
    ): SessionSaveResult {
        if (minutes !in 1..MAX_MANUAL_MINUTES) return SessionSaveResult.Rejected("Minutes must be between 1 and $MAX_MANUAL_MINUTES")
        if (startedAtMs > clock()) return SessionSaveResult.Rejected("A session can't start in the future")
        val body = ManualSessionBody(
            startedAt = IsoTime.format(startedAtMs),
            durationMinutes = minutes,
            endProgress = endProgress?.coerceIn(0.0, 100.0)?.let(::round2),
            format = format?.take(12),
        )
        val account = accountKey()
        val localId = UUID.randomUUID().toString()
        val item = PendingManualSession(localId, bookId, body, startReadingOn = startReadingOn?.toString())
        queue.update(account) { q -> q.copy(manual = q.manual + item) }
        return sendNow(account, setOf(localId))
    }

    /**
     * Flushes, then reports what happened to the items [keys] (all of them left the queue: sent).
     * Other items the server refused in the same pass are announced on [events].
     */
    private suspend fun sendNow(account: String, keys: Set<String>): SessionSaveResult {
        val refusals = mutableListOf<Refusal>()
        try {
            flushQueue(refusals)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or the server is unwell: whatever is left waits for WorkManager.
        }
        val left = queue.read(account)
        _pending.value = left.size
        refusals.filter { it.key !in keys }.forEach { _events.tryEmit(it.event) }
        val mine = refusals.firstOrNull { it.key in keys }
        val gone = left.timed.none { it.body.sessionId in keys } && left.manual.none { it.localId in keys }
        return when {
            mine != null -> SessionSaveResult.Rejected(mine.event.message)
            !gone -> {
                onQueued()
                SessionSaveResult.Queued
            }
            else -> SessionSaveResult.Sent
        }
    }

    /**
     * The file [bookId]'s timed sessions are recorded against (PageMath.trackingFile), for a timer
     * started before its book had loaded; null for a book without files, or when it can't be
     * looked up now (the session then goes through the manual route).
     */
    suspend fun trackingFileId(bookId: Long): Long? = try {
        withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { PageMath.trackingFile(remote.book(bookId).files)?.id }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The zone the server splits days in: users.settings.timezone, else UTC. */
    private fun zone(): ZoneId =
        account.value?.settings?.timezone?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

    private data class Refusal(val key: String, val event: TrackingEvent.SessionRejected)

    /** Deletes a session (its server [sessionId], `BookSession.id`). */
    suspend fun deleteSession(bookId: Long, sessionId: Long) {
        remote.deleteSession(bookId, sessionId)
        changed(bookId)
    }

    /**
     * Moves a session in time. The span must equal its stored duration exactly, and it may not
     * overlap any other session (409). Duration and progress can't be edited: delete and log again.
     */
    suspend fun moveSession(bookId: Long, sessionId: Long, startedAtMs: Long, endedAtMs: Long): TimelineSession {
        val moved = remote.moveSession(sessionId, MoveSessionBody(IsoTime.format(startedAtMs), IsoTime.format(endedAtMs)))
        changed(bookId)
        return moved
    }

    /** Whether sessions are still waiting on this device. */
    suspend fun hasUnsent(): Boolean = !queue.read(accountKey()).isEmpty()

    /**
     * Sends every waiting session it can. Returns true when the queue is empty. Items the server
     * refuses are dropped and announced on [events]; a connection failure stops the pass.
     */
    suspend fun flush(): Boolean {
        val refusals = mutableListOf<Refusal>()
        try {
            flushQueue(refusals)
        } finally {
            refusals.forEach { _events.tryEmit(it.event) }
            _pending.value = queue.read(accountKey()).size
        }
        return _pending.value == 0
    }

    private fun flushInBackground() {
        scope.launch {
            try {
                if (!flush()) onQueued()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onQueued()
            }
        }
    }

    private suspend fun flushQueue(rejected: MutableList<Refusal>) = withContext(NonCancellable) {
        flushMutex.withLock {
            val account = accountKey()
            for (item in queue.read(account).timed) {
                // Signed out (or the server ended the session) during the pass: the rest waits for that account.
                if (!signedInAs(account)) return@withLock
                val outcome = attempt { remote.saveTimedSession(item.fileId, item.body) }
                if (outcome is Outcome.Failed) throw outcome.error
                queue.update(account) { q -> q.copy(timed = q.timed.filterNot { it.body.sessionId == item.body.sessionId }) }
                if (outcome is Outcome.Refused) rejected += Refusal(item.body.sessionId, TrackingEvent.SessionRejected(item.bookId, outcome.message))
                else changed(item.bookId)
            }
            for (queued in queue.read(account).manual) {
                if (!signedInAs(account)) return@withLock
                var item = queued
                if (!item.posted) {
                    val refused = sendManual(account, item)
                    if (refused != null) {
                        dropManual(account, item)
                        rejected += Refusal(item.localId, TrackingEvent.SessionRejected(item.bookId, refused))
                        continue
                    }
                    changed(item.bookId)
                    if (item.startReadingOn == null) {
                        dropManual(account, item)
                        continue
                    }
                    item = item.copy(posted = true, unconfirmed = false)
                    queue.update(account) { q -> q.copy(manual = q.manual.map { if (it.localId == item.localId) item else it }) }
                }
                // The session is in: a book not started yet is now being read. Refused (a date the
                // server won't take) leaves the status as it is; the session is kept either way.
                val day = item.startReadingOn
                if (day != null) {
                    val outcome = attempt { startReading(item.bookId, day) }
                    if (outcome is Outcome.Failed) throw outcome.error
                }
                dropManual(account, item)
            }
        }
    }

    /**
     * Gets [item] onto the server: looked for first if an earlier send got no answer, else sent.
     * Returns null once it is there, or the server's reason when it refuses it for good (the book
     * is gone or out of reach: the lookup's 404/403 counts too). Throws when it's worth retrying.
     */
    private suspend fun sendManual(account: String, item: PendingManualSession): String? {
        if (item.unconfirmed) {
            var found = false
            val lookup = attempt { found = alreadyOnServer(item) }
            if (lookup is Outcome.Failed) throw lookup.error
            if (lookup is Outcome.Refused) return lookup.message
            if (found) return null
        }
        // Written before sending: if no answer comes, the next pass looks before resending.
        queue.update(account) { q -> q.copy(manual = q.manual.map { if (it.localId == item.localId) it.copy(unconfirmed = true) else it }) }
        val outcome = attempt { remote.createManualSession(item.bookId, item.body) }
        if (outcome is Outcome.Failed) throw outcome.error
        return (outcome as? Outcome.Refused)?.message
    }

    private suspend fun dropManual(account: String, item: PendingManualSession) {
        queue.update(account) { q -> q.copy(manual = q.manual.filterNot { it.localId == item.localId }) }
    }

    /** Sets [bookId] to reading from [day] if it still isn't started (it may have been since the session was logged). */
    private suspend fun startReading(bookId: Long, day: String) {
        val status = remote.book(bookId).readStatus?.status
        if (status != null && status != ReadStatus.UNREAD.value && status != ReadStatus.WANT_TO_READ.value) return
        val result = remote.setStatus(bookId, TrackingBodies.status(ReadStatus.READING.value, Patch.Set(day), Patch.Keep))
        readingChanges.overrideStatus(bookId, result.status)
        changed(bookId)
    }

    /** Still signed in as [account] (the queue is per account; signing out leaves it on the device). */
    private fun signedInAs(account: String): Boolean = this.account.value != null && accountKey() == account

    /** Whether the server already has [item] (a manual session sent before without an answer). */
    private suspend fun alreadyOnServer(item: PendingManualSession): Boolean {
        val startedAt = IsoTime.parse(item.body.startedAt) ?: return false
        val window = SessionQuery(
            pageSize = 100,
            dateFrom = IsoTime.format(startedAt - MATCH_WINDOW_MS),
            dateTo = IsoTime.format(startedAt + MATCH_WINDOW_MS),
        )
        val wantedEnd = item.body.endProgress
        return remote.sessions(item.bookId, window).items.any { s ->
            val sessionStart = IsoTime.parse(s.startedAt) ?: return@any false
            val sessionEnd = s.endProgress
            s.source == "manual" &&
                s.durationSeconds == item.body.durationMinutes * 60 &&
                abs(sessionStart - startedAt) < 1_000 &&
                (wantedEnd == null || sessionEnd == null || abs(sessionEnd - wantedEnd) < 0.01)
        }
    }

    private sealed interface Outcome {
        data object Done : Outcome
        data class Refused(val message: String) : Outcome
        data class Failed(val error: Exception) : Outcome
    }

    /**
     * A 4xx is final, except 401, 408 and 429; anything else (offline, 5xx) is worth retrying. A
     * 401 means no valid token (signed out meanwhile, a refresh token past its lifetime, or a
     * refresh that failed for now), not that the server refused the session: it waits for that
     * account to sign in again (the reader's queue, ProgressStore, treats it the same way).
     */
    private suspend fun attempt(block: suspend () -> Unit): Outcome = try {
        block()
        Outcome.Done
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.code in 400..499 && e.code != 401 && e.code != 408 && e.code != 429) Outcome.Refused(e.message ?: "HTTP ${e.code}") else Outcome.Failed(e)
    } catch (e: IOException) {
        Outcome.Failed(e)
    }

    // --- status, readings, rating, review -----------------------------------------------------

    /**
     * `PATCH books/:id/status`: [status] (a ReadStatus value) and/or the current reading's dates.
     * Dates may not be in the future; `want_to_read`/`unread` take no dates and an active status
     * takes no finish date (400). Setting want_to_read or unread during a reading closes it as
     * abandoned.
     */
    suspend fun setStatus(
        bookId: Long,
        status: String?,
        startedAt: Patch<LocalDate> = Patch.Keep,
        finishedAt: Patch<LocalDate> = Patch.Keep,
    ): BookStatus {
        val result = remote.setStatus(bookId, TrackingBodies.status(status, startedAt.asText(), finishedAt.asText()))
        readingChanges.overrideStatus(bookId, result.status)
        changed(bookId)
        return result
    }

    /** Records a past reading (`outcome` completed, skimmed or abandoned). */
    suspend fun createPastRead(bookId: Long, startedOn: LocalDate?, endedOn: LocalDate?, outcome: String): ReadingAttempt {
        val body = TrackingBodies.attempt(
            startedOn = startedOn?.let { Patch.Set(it.toString()) } ?: Patch.Keep,
            endedOn = endedOn?.let { Patch.Set(it.toString()) } ?: Patch.Keep,
            outcome = Patch.Set(outcome),
        )
        return remote.createAttempt(bookId, body).also { changed(bookId) }
    }

    /**
     * Changes a reading's dates or outcome. Reopening one ([outcome] = Clear) fails if another
     * reading of the book is open.
     */
    suspend fun updateAttempt(
        bookId: Long,
        attemptId: Long,
        startedOn: Patch<LocalDate> = Patch.Keep,
        endedOn: Patch<LocalDate> = Patch.Keep,
        outcome: Patch<String> = Patch.Keep,
    ): ReadingAttempt {
        val body = TrackingBodies.attempt(startedOn.asText(), endedOn.asText(), outcome)
        return remote.updateAttempt(bookId, attemptId, body).also { changed(bookId) }
    }

    suspend fun deleteAttempt(bookId: Long, attemptId: Long) {
        remote.deleteAttempt(bookId, attemptId)
        changed(bookId)
    }

    /** Starts a new reading (status rereading); [resetProgress] clears the saved positions first. */
    suspend fun startReread(bookId: Long, resetProgress: Boolean): BookStatus {
        val result = remote.startReread(bookId, resetProgress)
        readingChanges.overrideStatus(bookId, result.status)
        changed(bookId)
        return result
    }

    /** Sets this user's own rating (1..5, null for none). Only when [canRate]. */
    suspend fun setRating(bookId: Long, rating: Int?) {
        require(rating == null || rating in 1..5) { "rating must be 1..5" }
        remote.setRating(bookId, rating)
        changed(bookId)
    }

    /** The private review (up to 10,000 characters; blank or null clears it). */
    suspend fun setPersonalNote(bookId: Long, note: String?) {
        remote.setPersonalNote(bookId, note?.take(MAX_NOTE_CHARS))
        changed(bookId)
    }

    /**
     * The yearly goal (books per year): `dashboardConfig.readingGoal`. The server merges settings
     * only at the top level, so the whole dashboardConfig is read first and written back with only
     * readingGoal changed (null removes it).
     */
    suspend fun setYearlyGoal(books: Int?) {
        require(books == null || books in 1..MAX_YEARLY_GOAL) { "goal must be 1..$MAX_YEARLY_GOAL" }
        val user = remote.me()
        val config = user.settings?.dashboardConfig ?: JsonObject(emptyMap())
        val next = JsonObject(if (books == null) config - "readingGoal" else config + ("readingGoal" to JsonPrimitive(books)))
        val saved = remote.patchSettings(buildJsonObject { put("dashboardConfig", next) })
        val stored = saved?.get("dashboardConfig") as? JsonObject ?: next
        updateUser(user.copy(settings = (user.settings ?: UserSettings()).copy(dashboardConfig = stored)))
        _version.update { it + 1 }
        readingChanges.changed()
    }

    /** The yearly goal as last fetched with the user (null for none). */
    fun yearlyGoal(): Int? = (account.value?.settings?.dashboardConfig?.get("readingGoal") as? JsonPrimitive)?.content?.toDoubleOrNull()?.roundToInt()

    /**
     * [bookId]'s tracking data changed through another route (the book page's status picker uses
     * `Api.setStatus`): the same announcements as this repository's own writes.
     */
    fun changedElsewhere(bookId: Long) = changed(bookId)

    private fun changed(bookId: Long) {
        bookVersions.update { it + (bookId to ((it[bookId] ?: 0) + 1)) }
        _version.update { it + 1 }
        readingChanges.changed()
    }

    private fun Patch<LocalDate>.asText(): Patch<String> = when (this) {
        Patch.Keep -> Patch.Keep
        Patch.Clear -> Patch.Clear
        is Patch.Set -> Patch.Set(value.toString())
    }

    companion object {
        /** The server drops shorter sessions from the file route without an error. */
        const val MIN_SESSION_SECONDS = 10
        const val MAX_MANUAL_MINUTES = 1440
        const val MAX_NOTE_CHARS = 10_000
        const val MAX_YEARLY_GOAL = 240
        private const val MATCH_WINDOW_MS = 60_000L
        private const val LOOKUP_TIMEOUT_MS = 8_000L
        const val LIBRARY_EDIT_METADATA = "library_edit_metadata"
        const val DEMO_RESTRICTED = "demo_restricted"

        /** `bulk-set-rating` needs library_edit_metadata and is refused for demo accounts. */
        fun canRate(user: AuthUser?): Boolean =
            user != null && (user.isSuperuser || (LIBRARY_EDIT_METADATA in user.permissions && DEMO_RESTRICTED !in user.permissions))

        private fun round2(value: Double) = Math.round(value * 100.0) / 100.0
    }
}
