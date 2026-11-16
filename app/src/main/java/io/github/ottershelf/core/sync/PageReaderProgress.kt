package io.github.ottershelf.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.ottershelf.core.model.FileProgress
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.model.SaveProgress
import io.github.ottershelf.core.sync.ProgressStore.Position
import io.github.ottershelf.core.util.IsoTime

/**
 * Position and reading-session bookkeeping for a page-based reader (comics, PDF): the EPUB reader's
 * rules (feature.reader.ReaderViewModel, the Nexus ReaderActivity's) without a WebView, so every
 * format syncs through the same [ProgressStore] queue, conflict checks and offline records, and its
 * sessions reach tracking, the dashboard and statistics exactly as the EPUB reader's do.
 *
 * Get one from `AppContainer.pageReaderProgress(bookId, fileId, viewModelScope)` in the reader's
 * ViewModel, then:
 * - [open] once the reader knows whether it has a downloaded copy: where to start (the server's
 *   position, this device's newer offline one, or the user's pick through [choice] / [choose] when
 *   both moved), or from page 1 after a manual Read/Unread since the user last read it ([Opening.freshStart]:
 *   nothing is saved until the user turns a page). Open at `opening.startPage(pageCount)`.
 * - [onPage] on every page change (1-based; a spread reports its last page), [onGesture] when the user
 *   zooms or scrolls within a page, [onActivity] for other reading activity, so the session isn't
 *   taken as idle.
 * - [onPause] when the screen stops (saves now, ends the session), [close] from `onCleared`.
 * - Collect [jumps]: a conflict found mid-book was settled for the server's position; go there.
 *
 * Nothing is saved and no session sent until the user moves since opening (a page other than the first
 * shown, or [onGesture]): see [ReadingVisit].
 *
 * Saves are debounced 2 s after the last page change, kept on the device first
 * ([ProgressStore.savePending]) and sent from [sendScope] (the app scope, so leaving the reader
 * never loses one), checked against the server's position until a send proves the two in step.
 * A session ends after 5 minutes idle or on leaving; under 10 s is dropped; it is queued
 * ([ProgressStore.enqueueSession]) and [onSessionQueued] runs the sync.
 *
 * Main thread only (the app scope and a ViewModel's scope are both Main.immediate).
 */
class PageReaderProgress(
    private val store: ProgressStore,
    private val bookId: Long,
    private val fileId: Long,
    private val remote: Remote,
    private val online: () -> Boolean,
    /** The reader's own scope (debounce, the two-positions prompt). */
    private val uiScope: CoroutineScope,
    /** Outlives the reader: sends. */
    private val sendScope: CoroutineScope,
    /** A session was queued: sync now (`container.sync.sync(force = true, baselines = false)`). */
    private val onSessionQueued: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The two reads [open] makes. */
    interface Remote {
        /** `GET books/files/:fileId/progress` */
        suspend fun fileProgress(fileId: Long): FileProgress

        /** The book's read status (`GET books/:id`), for the fresh start after a manual Read/Unread. */
        suspend fun readStatus(bookId: Long): ReadStatusInfo?
    }

    /** Both moved: this device's position ([mine], reached at [mineAt]) or the server's. */
    data class Choice(val mine: Position, val mineAt: Long, val server: Position, val serverReadAt: String?)

    /** Where [open] decided to start. */
    data class Opening(
        /** Null: from the first page. */
        val position: Position?,
        /** Started over because the book was marked this by hand since the user last read it (null: not). */
        val freshStart: ReadStatus?,
        /** The server answered (false: opened from this device's record, or at page 1). */
        val withServer: Boolean,
    ) {
        fun startPage(pageCount: Int): Int = PageProgress.startPage(position, pageCount)
    }

    private val _choice = MutableStateFlow<Choice?>(null)
    /** The two-positions prompt while it waits for [choose]. */
    val choice: StateFlow<Choice?> = _choice.asStateFlow()

    private val _jumps = Channel<Position>(Channel.CONFLATED)
    /** A conflict found while reading was settled for the server's position: the reader goes there. */
    val jumps: Flow<Position> = _jumps.receiveAsFlow()

    private val _movedOn = Channel<Position>(Channel.CONFLATED)
    /** After a resume the book had been read on elsewhere, and [jumps] went there: the reader says so. */
    val movedOn: Flow<Position> = _movedOn.receiveAsFlow()

    private val _resyncing = MutableStateFlow(false)
    /** Back from a pause, asking the server where the book is now: the reader takes no input. */
    val resyncing: StateFlow<Boolean> = _resyncing.asStateFlow()

    private val _finishedElsewhere = Channel<Unit>(Channel.CONFLATED)
    /**
     * After a resume: the book had been finished on another device meanwhile (Read now, and not when
     * this reader opened it). The reader closes, saying so; the cover ([resyncing]) stays until then.
     */
    val finishedElsewhere: Flow<Unit> = _finishedElsewhere.receiveAsFlow()

    /** The book was Read when this reader opened it (a re-read): finishing elsewhere doesn't close it. */
    private var openedRead = false

    private val _unchecked = Channel<Unit>(Channel.CONFLATED)
    /** After a resume the server didn't answer in time: the reader goes on unconfirmed, and says so. */
    val unchecked: Flow<Unit> = _unchecked.receiveAsFlow()

    private var closed = false
    private var choosing = false
    private var pendingChoice: CompletableDeferred<Boolean>? = null

    /** [open] has decided where to start. */
    private var opened = false
    /** Paused since the last resume ([onResume] then asks the server again). */
    private var stopped = false
    /** Counts resumes, so an older resume's check that answers late is ignored. */
    private var resumes = 0
    /** Sent to [jumps] after a resume: [resyncing] lasts until the reader reports that page. */
    private var landing: Position? = null
    private var landingTimeout: Job? = null

    // Progress: debounced like the web reader (2 s after the last page change), flushed on pause/close.
    private var latest: Position? = null
    private var unsaved = false
    private var saveJob: Job? = null

    // Reading sessions, as useReadingSession does (end after 5 min idle or when leaving), sent only
    // once the user has moved since opening; nothing is saved before that either.
    private val visit = ReadingVisit(clock = clock, send = ::sendSession)
    /** The first page shown since [open]: any other one is a move. */
    private var firstPage: Int? = null

    // A fresh start saves nothing until the user actually turns a page.
    private var freshStart = false
    private var freshStartPage: Int? = null

    // Whether the server is known to still have this device's baseline (see ReaderViewModel).
    private var verified = false
    private var pushing = false
    private var pushAgain = false

    init {
        store.opened(fileId)
    }

    /** Something was read since [open] (Retry should not decide again: the checked push covers it). */
    val readSinceOpen: Boolean get() = latest != null

    /**
     * Decides where to open (suspends while the server is asked, and while [choice] waits for an
     * answer). [hasLocalCopy]: a downloaded copy opens without the server, so it is waited for only
     * briefly. [jumpPage]: open at this page (a PDF highlight) and, as after a fresh start, save
     * nothing until the user turns a page. May be called again (Retry) while [readSinceOpen] is false.
     */
    suspend fun open(hasLocalCopy: Boolean, jumpPage: Int? = null): Opening {
        val record = store.get(fileId)
        val wait = if (hasLocalCopy) SERVER_WAIT_LOCAL_MS else SERVER_WAIT_MS
        val status = uiScope.async { withTimeoutOrNull(wait) { runCatching { remote.readStatus(bookId) }.getOrNull() } }
        uiScope.launch { openedRead = status.await()?.status == ReadStatus.READ.value }
        val server = withTimeoutOrNull(wait) { runCatching { remote.fileProgress(fileId) }.getOrNull() }
        val pending = record?.pendingPosition
        freshStart = false
        freshStartPage = null
        if (!visit.moved) firstPage = null // Retry may open elsewhere
        val position: Position? = if (server != null) {
            when (val decision = store.decideOnOpen(fileId, bookId, server, record)) {
                is ProgressStore.Opening.Start -> {
                    verified = decision.inStep
                    if (decision.send) pushPending()
                    if (decision.atServer) freshStart = startsFromBeginning(status.await(), server)
                    if (freshStart) null else decision.position
                }
                is ProgressStore.Opening.Ask ->
                    choosePosition(decision.mine, decision.mineAt, decision.server, decision.serverReadAt, online = true)
            }
        } else {
            verified = false
            val conflict = record?.conflict
            if (pending != null && conflict != null) {
                choosePosition(pending, record.pendingAt, conflict, record.conflictReadAt, online = false)
            } else {
                record?.bestKnown
            }
        }
        val jump = jumpPage?.let { Position(cfi = null, percentage = position?.percentage ?: 0.0, pageNumber = it) }
        val freshStatus = if (freshStart && jump == null) ReadStatus.of(status.await()?.status) ?: ReadStatus.READ else null
        if (jump != null) freshStart = true
        opened = true
        return Opening(jump ?: position, freshStatus, withServer = server != null)
    }

    /** The user answered the two-positions prompt. */
    fun choose(keepMine: Boolean) {
        pendingChoice?.complete(keepMine)
    }

    /** The page on screen changed (or was first shown): [pageNumber] 1-based, of [pageCount]. */
    fun onPage(pageNumber: Int, pageCount: Int) {
        if (closed || pageCount <= 0) return
        val position = PageProgress.position(pageNumber, pageCount)
        if (_resyncing.value) {
            // While the server is asked and the reader sent there (it takes no input) nothing counts.
            val target = landing
            if (target != null && position.pageNumber == PageProgress.startPage(target, pageCount)) {
                // Landed on the server's place: this visit starts here, and reading can go on.
                firstPage = position.pageNumber
                endResync()
            } else if (target == null && firstPage == null) {
                firstPage = position.pageNumber // the page shown as the screen came back
            }
            return
        }
        if (freshStart) {
            val first = freshStartPage
            if (first == null) freshStartPage = position.pageNumber
            else if (first != position.pageNumber) freshStart = false
        }
        val first = firstPage
        if (first == null) firstPage = position.pageNumber
        else if (first != position.pageNumber) visit.markMoved()
        visit.activity(position.percentage)
        // Not moved since opening (or a fresh start not turned yet): where it opened stays as saved.
        if (freshStart || !visit.moved) return
        if (position != latest) {
            latest = position
            unsaved = true
        }
        saveJob?.cancel()
        if (unsaved) saveJob = uiScope.launch {
            delay(SAVE_DELAY_MS)
            save()
        }
    }

    /** Reading goes on without a page change: the session isn't idle. Not a move by itself. */
    fun onActivity() {
        if (closed) return
        visit.activity()
    }

    /** The user zoomed, or scrolled within a page: a move (the visit counts as reading) and activity. */
    fun onGesture() {
        if (closed || _resyncing.value) return
        visit.markMoved()
        visit.activity()
    }

    /** The screen stopped (the app left, the phone locked): save now and end the session. */
    fun onPause() {
        if (closed) return
        stopped = true
        endResync() // left before it finished: the next resume asks again
        saveJob?.cancel()
        if (!freshStart) save()
        visit.end()
    }

    /**
     * The screen is back after a pause (call before reporting the page shown). The book may have
     * been read on elsewhere meanwhile, even finished. So the reader takes no input ([resyncing])
     * until the server has been asked again, as on opening: if it moved on, [jumps] takes the reader
     * there and [movedOn] says so; if this device also has reading the server hasn't got (read
     * offline), the user picks. Only if the server can't be reached in time does the reader carry on
     * from where it was, and then nothing is sent unchecked (the checked push and the session's sync
     * find any difference later: a prompt; sessions lose their end point).
     */
    fun onResume() {
        if (closed || !stopped) return
        stopped = false
        endResync()
        visit.rest()
        firstPage = null // the page shown now is where this visit starts
        if (!opened || choosing) return
        verified = false
        if (!online()) return
        val before = store.get(fileId)
        val generation = ++resumes
        _resyncing.value = true
        uiScope.launch {
            var landingOn: Position? = null
            var finished = false
            // Asked alongside: a book finished elsewhere meanwhile closes the reader.
            val status = async { withTimeoutOrNull(RESYNC_WAIT_MS) { runCatching { remote.readStatus(bookId) }.getOrNull() } }
            try {
                val server = withTimeoutOrNull(RESYNC_WAIT_MS) { runCatching { remote.fileProgress(fileId) }.getOrNull() }
                if (server == null) {
                    if (generation == resumes && !closed) _unchecked.trySend(Unit)
                    return@launch
                }
                if (closed || generation != resumes || choosing) return@launch
                when (val decision = store.decideOnOpen(fileId, bookId, server, before)) {
                    is ProgressStore.Opening.Start -> {
                        verified = decision.inStep
                        if (decision.send) pushPending()
                        if (decision.atServer && before?.baseline?.sameAs(decision.position) != true) {
                            // Moved on elsewhere. Finished there too (Read now, not when opened here:
                            // this device's own finish leaves the server at its own place) closes the reader.
                            if (!openedRead && status.await()?.status == ReadStatus.READ.value) {
                                finished = true
                            } else {
                                landingOn = decision.position
                                _movedOn.trySend(decision.position)
                            }
                        }
                    }
                    is ProgressStore.Opening.Ask -> {
                        val chosen = choosePosition(decision.mine, decision.mineAt, decision.server, decision.serverReadAt, online = true)
                        if (chosen === decision.server) landingOn = decision.server
                    }
                }
            } finally {
                status.cancel()
                if (generation == resumes) {
                    val target = landingOn
                    when {
                        finished && !closed -> _finishedElsewhere.trySend(Unit)
                        target != null && !closed -> showServerPosition(target)
                        else -> endResync()
                    }
                }
            }
        }
    }

    /**
     * Sends the reader to the server's [position] (not the user's move: nothing is saved until they
     * turn a page). Input stays off until the reader reports that page ([onPage]), or [LAND_WAIT_MS].
     */
    private fun showServerPosition(position: Position) {
        saveJob?.cancel()
        latest = null
        unsaved = false
        freshStart = false
        freshStartPage = null
        landing = position
        firstPage = null
        landingTimeout?.cancel()
        landingTimeout = uiScope.launch {
            delay(LAND_WAIT_MS)
            endResync()
        }
        _jumps.trySend(position)
    }

    private fun endResync() {
        landing = null
        landingTimeout?.cancel()
        landingTimeout = null
        _resyncing.value = false
    }

    /** The reader closed (`onCleared`): the last save and session go out in [sendScope]. */
    fun close() {
        if (closed) return
        closed = true
        saveJob?.cancel()
        pendingChoice?.cancel()
        if (!freshStart) save()
        visit.close() // a session held for want of a move is dropped here
        store.closed(fileId)
    }

    // --- saving --------------------------------------------------------------------------------

    private fun save() {
        val p = latest ?: return
        if (!unsaved) return
        unsaved = false
        val body = SaveProgress(cfi = null, percentage = p.percentage, pageNumber = p.pageNumber)
        val record = store.get(fileId)
        // Where the server already is (just opened there, or jumped to its position): nothing to send.
        if (record?.pending == null && record?.baseline?.sameAs(p) == true) return
        // Kept on the device first, so it survives no connection, a failed send or the app closing.
        // Online, pushPending sends it now (a failed send schedules the flush); offline, the flush.
        // The connection is read once for both: were it read twice and lost in between, neither
        // the save nor the push would leave the position to the flush.
        val connected = online()
        store.savePending(fileId, bookId, body, sendingNow = connected)
        pushPending(connected)
    }

    /**
     * Sends the stored position; overlapping calls collapse into one follow-up send. [connected]:
     * the connection as the caller found it.
     */
    private fun pushPending(connected: Boolean = online()) {
        if (!connected) {
            verified = false
            return
        }
        if (pushing) {
            pushAgain = true
            return
        }
        pushing = true
        sendScope.launch {
            try {
                do {
                    pushAgain = false
                    val result = store.push(fileId, checked = !verified)
                    when (result) {
                        ProgressStore.Push.SENT -> verified = true
                        ProgressStore.Push.FAILED, ProgressStore.Push.REJECTED -> verified = false
                        ProgressStore.Push.CONFLICT -> if (!closed) resolveConflictWhileReading()
                        ProgressStore.Push.NOTHING -> {}
                    }
                    // NOTHING: a sync sent it meanwhile; a save made since still goes (no flush covers it).
                } while (pushAgain && (result == ProgressStore.Push.SENT || result == ProgressStore.Push.NOTHING))
            } finally {
                pushing = false
            }
        }
    }

    /** The connection came back mid-book and the server had moved meanwhile. */
    private fun resolveConflictWhileReading() {
        if (choosing) return
        val record = store.get(fileId) ?: return
        val mine = record.pendingPosition ?: return
        val server = record.conflict ?: return
        uiScope.launch {
            val chosen = choosePosition(mine, record.pendingAt, server, record.conflictReadAt, online = true)
            if (chosen === server) _jumps.trySend(server)
        }
    }

    /** Asks which position to keep (nothing is sent until then); returns the one kept. */
    private suspend fun choosePosition(mine: Position, mineAt: Long, server: Position, serverReadAt: String?, online: Boolean): Position {
        val answer = CompletableDeferred<Boolean>()
        pendingChoice = answer
        choosing = true
        _choice.value = Choice(mine, mineAt, server, serverReadAt)
        try {
            return if (answer.await()) {
                store.resolveKeepLocal(fileId, bookId, server)
                if (online) {
                    verified = true
                    pushPending()
                }
                mine
            } else {
                store.resolveKeepServer(fileId, bookId, server)
                verified = online
                server
            }
        } finally {
            choosing = false
            pendingChoice = null
            _choice.value = null
        }
    }

    /** A manual Read/Unread set after the user last read it starts the book over (see ReaderViewModel). */
    private fun startsFromBeginning(status: ReadStatusInfo?, saved: FileProgress?): Boolean {
        if (status?.source != "manual") return false
        if (status.status != ReadStatus.READ.value && status.status != ReadStatus.UNREAD.value) return false
        if ((saved?.percentage ?: 0.0) <= 0.0 && saved?.cfi == null && saved?.pageNumber == null) return false
        val statusAt = IsoTime.parse(status.updatedAt) ?: return false
        val readAt = IsoTime.parse(saved?.textUpdatedAt ?: saved?.updatedAt) ?: return true
        return statusAt > readAt
    }

    // --- sessions ------------------------------------------------------------------------------

    private fun sendSession(body: ReadingSession) {
        store.enqueueSession(fileId, bookId, body, sendingNow = online())
        onSessionQueued()
    }

    companion object {
        const val IDLE_MS = ReadingVisit.IDLE_MS
        const val MIN_SESSION_SECONDS = ReadingVisit.MIN_SECONDS
        const val SAVE_DELAY_MS = 2_000L
        /** How long to wait for the server's position before opening a downloaded book without it. */
        const val SERVER_WAIT_LOCAL_MS = 4_000L
        const val SERVER_WAIT_MS = 20_000L
        /** How long a reader back from a pause waits for the server, input off, before going on unconfirmed. */
        const val RESYNC_WAIT_MS = 10_000L
        /** How long it waits for the reader to report the server's page before taking input again anyway. */
        const val LAND_WAIT_MS = 5_000L
    }
}
