package io.github.ottershelf.feature.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.settings.PageOverride
import io.github.ottershelf.core.tracking.BookSession
import io.github.ottershelf.core.tracking.PageMath
import io.github.ottershelf.core.tracking.Patch
import io.github.ottershelf.core.tracking.SessionQuery
import io.github.ottershelf.core.tracking.SessionSaveResult
import io.github.ottershelf.core.tracking.TrackingRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What the tracking part of the page can ask for. Stateless composables take it; [None] does
 * nothing (screenshot tests).
 */
interface BookTrackingActions {
    fun startTimer() {}
    /** The page total of the user's edition; null goes back to the server's page count. */
    fun setPageTotal(total: Int?) {}
    /** A hand-set current page (kept on the account until the next session). */
    fun setCurrentPage(page: Int) {}
    /** A session logged by hand: [minutes] from [startMs], ending at [endPercent] (0..100) if given. */
    fun logSession(startMs: Long, minutes: Int, endPercent: Double?) {}
    fun markFinished() {}
    fun loadMoreSessions() {}
    fun deleteSession(session: BookSession) {}
    /** Moves [session] to start at [startMs], keeping its duration. */
    fun moveSession(session: BookSession, startMs: Long) {}
    fun addPastRead(startedOn: LocalDate?, endedOn: LocalDate, outcome: String) {}
    /** 1..5, or null for none. */
    fun setRating(rating: Int?) {}
    fun saveNote(note: String) {}
    fun retryTracking() {}

    // The finish flow
    fun celebrationContinue() {}
    fun celebrationSave(rating: Int?, note: String) {}
    fun celebrationSkip() {}
    /** The user leaves the page from the flow (the next book in the series): it ends, and its achievement goes to the shell. */
    fun celebrationLeave() {}
    fun achievementSeen() {}

    companion object {
        val None = object : BookTrackingActions {}
    }
}

/**
 * The tracking part of a book's page, owned by [BookDetailViewModel] (it runs in its [scope]):
 * the book's sessions (paged) and readings, the ebook's position, the app's settings key, rating
 * and review, and the finish flow. Writes go through `container.tracking` from the app's scope, so
 * leaving the page doesn't cut them short; every write bumps the book's tracking version, which
 * reloads this and (through [BookDetailViewModel]) the book itself.
 */
class BookTracker(
    private val container: AppContainer,
    private val bookId: Long,
    private val scope: CoroutineScope,
    /** The page's book (null until loaded) and its status (optimistic). */
    private val book: () -> BookDetail?,
    private val status: () -> String?,
) : BookTrackingActions {

    private val tracking = container.tracking
    private val _state = MutableStateFlow(BookTrackingUiState(settings = container.appSettings.settings.value))
    val state: StateFlow<BookTrackingUiState> = _state.asStateFlow()

    private val _messages = Channel<TrackingMessage>(Channel.BUFFERED)
    val messages: Flow<TrackingMessage> = _messages.receiveAsFlow()

    private var loadJob: Job? = null
    /** The book's rating and note are shown from it unless one of ours is on its way. */
    private var writingReview = false

    init {
        scope.launch { container.appSettings.settings.collect { s -> _state.update { it.copy(settings = s) } } }
        scope.launch { tracking.canRate.collect { can -> _state.update { it.copy(canRate = can) } } }
        scope.launch { container.auth.user.map { serverZone() }.distinctUntilChanged().collect { z -> _state.update { it.copy(zone = z) } } }
        scope.launch {
            container.timer.state.map { it.active?.bookId == bookId }.distinctUntilChanged().collect { here ->
                _state.update { it.copy(timerHere = here) }
            }
        }
        scope.launch { tracking.bookVersion(bookId).drop(1).collect { refresh() } }
        refresh()
    }

    /** The book (re)loaded: its rating and note, and the ebook's position. */
    fun onBook(book: BookDetail) {
        val locked = RATING_FIELD in book.lockedFields
        _state.update { if (writingReview) it.copy(ratingLocked = locked) else it.copy(rating = book.rating, note = book.personalNote, ratingLocked = locked) }
        val file = PageMath.trackingFile(book.files) ?: return
        scope.launch {
            val percent = runCatching { container.api.fileProgress(file.id).percentage }.getOrNull()
            if (percent != null) _state.update { it.copy(fileProgress = percent) }
        }
    }

    /** Reloads the newest sessions and the readings (after a write, or the page showing again). */
    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update { it.copy(loading = it.sessionsPage == 0, error = null) }
            // As many as were showing (up to 100), so a write doesn't fold an opened log back up.
            val pages = _state.value.sessionsPage.coerceIn(1, MAX_PAGE_SIZE / PAGE_SIZE)
            try {
                coroutineScope {
                    val sessions = async { tracking.sessions(bookId, SessionQuery(page = 1, pageSize = pages * PAGE_SIZE)) }
                    val attempts = async { tracking.attempts(bookId) }
                    val list = sessions.await()
                    val readings = attempts.await()
                    _state.update {
                        it.copy(
                            loading = false,
                            sessions = list.items,
                            sessionsTotal = list.total,
                            sessionsPage = pages,
                            endReached = list.items.size < pages * PAGE_SIZE,
                            stats = list.stats,
                            attempts = readings.items,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message.orEmpty()) }
            }
        }
    }

    override fun retryTracking() = refresh()

    override fun loadMoreSessions() {
        val s = _state.value
        if (s.loadingMore || s.allSessionsLoaded || loadJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true) }
        loadJob = scope.launch {
            try {
                val next = tracking.sessions(bookId, SessionQuery(page = s.sessionsPage + 1, pageSize = PAGE_SIZE))
                val known = _state.value.sessions.mapTo(HashSet()) { it.id }
                if (next.items.any { it.id in known }) {
                    // Sessions were added elsewhere since the first page, so the pages shifted:
                    // reload the top pages in one request (which shows the new ones too).
                    _state.update { it.copy(sessionsPage = s.sessionsPage + 1, loadingMore = false) }
                    refresh()
                    return@launch
                }
                _state.update { cur ->
                    cur.copy(
                        sessions = cur.sessions + next.items,
                        sessionsTotal = next.total,
                        sessionsPage = s.sessionsPage + 1,
                        endReached = next.items.size < PAGE_SIZE,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(loadingMore = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false) }
                _messages.trySend(TrackingMessage(R.string.book_tracking_failed, e.message.orEmpty()))
            }
        }
    }

    // --- the settings key ---------------------------------------------------------------------

    override fun setPageTotal(total: Int?) {
        container.appSettings.update { s ->
            s.copy(pageTotals = if (total == null || total <= 0) s.pageTotals - bookId else s.pageTotals + (bookId to total))
        }
    }

    override fun setCurrentPage(page: Int) {
        val now = System.currentTimeMillis()
        container.appSettings.update { s -> s.copy(pageOverrides = s.pageOverrides + (bookId to PageOverride(page.coerceAtLeast(0), now))) }
    }

    // --- sessions -----------------------------------------------------------------------------

    override fun logSession(startMs: Long, minutes: Int, endPercent: Double?) {
        val book = book()
        val statusNow = status()
        // Only a book with several files needs the format (else the session has no file).
        val format = book?.files?.takeIf { files -> files.mapNotNull { it.format }.distinct().size > 1 }
            ?.let { PageMath.trackingFile(it)?.format }
        // A manual session leaves the status alone: a book not started yet is now being read. The
        // tracker sets it once the session is in (queued with it when offline).
        val startReading = if (statusNow == null || statusNow == ReadStatus.UNREAD.value || statusNow == ReadStatus.WANT_TO_READ.value) {
            minOf(Instant.ofEpochMilli(startMs).atZone(serverZone()).toLocalDate(), serverToday())
        } else {
            null
        }
        write { tracking ->
            when (val result = tracking.logManualSession(bookId, startMs, minutes, endPercent, format, startReading)) {
                SessionSaveResult.Sent, SessionSaveResult.Queued -> {
                    _messages.trySend(
                        TrackingMessage(if (result == SessionSaveResult.Sent) R.string.book_session_logged else R.string.book_session_queued),
                    )
                }
                SessionSaveResult.TooShort -> Unit
                is SessionSaveResult.Rejected -> _messages.trySend(TrackingMessage(R.string.book_tracking_failed, result.message))
            }
        }
    }

    override fun deleteSession(session: BookSession) {
        write(R.string.book_session_deleted) { it.deleteSession(bookId, session.id) }
    }

    override fun moveSession(session: BookSession, startMs: Long) {
        // The server keeps the duration: the new span must be exactly durationSeconds long.
        val end = startMs + session.durationSeconds * 1000L
        if (end > System.currentTimeMillis()) {
            _messages.trySend(TrackingMessage(R.string.book_session_future))
            return
        }
        write(R.string.book_session_moved) { it.moveSession(bookId, session.id, startMs, end) }
    }

    override fun addPastRead(startedOn: LocalDate?, endedOn: LocalDate, outcome: String) {
        write(R.string.book_past_read_added) { it.createPastRead(bookId, startedOn, endedOn, outcome) }
    }

    // --- rating and review --------------------------------------------------------------------

    override fun setRating(rating: Int?) {
        if (!_state.value.canRate || _state.value.ratingLocked) return
        val before = _state.value.rating
        _state.update { it.copy(rating = rating) }
        writingReview = true
        write(onFail = { _state.update { it.copy(rating = before) } }, always = { writingReview = false }) { it.setRating(bookId, rating) }
    }

    override fun saveNote(note: String) {
        val text = note.trim().takeIf { it.isNotEmpty() }
        val before = _state.value.note
        if (text == before) return
        _state.update { it.copy(note = text) }
        writingReview = true
        write(R.string.book_review_saved, onFail = { _state.update { it.copy(note = before) } }, always = { writingReview = false }) {
            it.setPersonalNote(bookId, text)
        }
    }

    // --- the finish flow ----------------------------------------------------------------------

    override fun markFinished() {
        if (_state.value.busy) return
        val started = book()?.readStatus?.startedAt
        write { tracking ->
            val today = serverToday()
            tracking.setStatus(bookId, ReadStatus.READ.value, finishedAt = Patch.Set(today))
            val days = PageMath.dateOf(started)?.let { (ChronoUnit.DAYS.between(it, today) + 1).toInt().coerceAtLeast(1) }
            _state.update { it.copy(celebration = CelebrationStep.Done(days)) }
        }
    }

    override fun celebrationContinue() {
        _state.update { it.copy(celebration = CelebrationStep.Rate) }
    }

    override fun celebrationSave(rating: Int?, note: String) {
        val s = _state.value
        if (s.canRate && !s.ratingLocked && rating != s.rating) setRating(rating)
        saveNote(note)
        claimNext()
    }

    override fun celebrationSkip() = claimNext()

    override fun celebrationLeave() = claimNext(onPage = false)

    override fun achievementSeen() {
        val claim = (_state.value.celebration as? CelebrationStep.Achievement)?.claim ?: return
        _state.update { it.copy(celebration = null) }
        container.appScope.launch {
            runCatching { tracking.acknowledgeCelebration(claim.claimId) }
        }
        claimNext()
    }

    private val claims = FinishClaims(
        appScope = container.appScope,
        pageScope = scope,
        claim = { tracking.claimCelebration() },
        show = { claim -> _state.update { it.copy(celebration = CelebrationStep.Achievement(claim)) } },
        handOver = { tracking.handOverClaim(it) },
    )

    /**
     * Shows the next server achievement waiting to be celebrated, or ends the flow. The request runs
     * in the app's scope: once the server answers it has claimed, so if the page closed meanwhile, or
     * the user left it from the flow ([onPage] false), the shell's celebration shows it instead (it would
     * otherwise be hidden for the claim's 15 minutes, or wait on a page no longer on screen).
     */
    private fun claimNext(onPage: Boolean = true) {
        _state.update { it.copy(celebration = null) }
        claims.next(onPage)
    }

    // --- plumbing -----------------------------------------------------------------------------

    /**
     * Runs [block] in the app's scope (so leaving the page doesn't cancel it), showing [done] when
     * it succeeds and the error when it fails.
     */
    private fun write(
        done: Int? = null,
        onFail: () -> Unit = {},
        always: () -> Unit = {},
        block: suspend (TrackingRepository) -> Unit,
    ) {
        _state.update { it.copy(busy = true) }
        val request = container.appScope.async { block(tracking) }
        scope.launch {
            try {
                request.await()
                done?.let { _messages.trySend(TrackingMessage(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFail()
                _messages.trySend(errorMessage(e))
            } finally {
                always()
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun errorMessage(e: Exception): TrackingMessage =
        if (e is ApiException && e.code == 409) TrackingMessage(R.string.book_session_overlap)
        else TrackingMessage(R.string.book_tracking_failed, e.message.orEmpty())

    /** The account's timezone: the server checks status dates against its own "today" there. */
    private fun serverZone(): ZoneId {
        val id = container.auth.user.value?.settings?.timezone
        return runCatching { if (id.isNullOrBlank()) ZoneId.of("UTC") else ZoneId.of(id) }.getOrDefault(ZoneId.of("UTC"))
    }

    private fun serverToday(): LocalDate = LocalDate.now(serverZone())

    private companion object {
        const val PAGE_SIZE = 25
        const val MAX_PAGE_SIZE = 100
        /** The metadata lock field that makes bulk-set-rating skip the book. */
        const val RATING_FIELD = "rating"
    }
}
