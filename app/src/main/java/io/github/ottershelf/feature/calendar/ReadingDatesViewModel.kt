package io.github.ottershelf.feature.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.tracking.Patch
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.feature.history.SavedReading
import io.github.ottershelf.feature.scan.ScanBook
import io.github.ottershelf.feature.scan.ScanPicks
import io.github.ottershelf.ui.nav.appViewModel
import java.time.LocalDate

/** The book picker: what the user is reading now, or a search of the library. */
@Immutable
data class PickerUiState(
    /** The day the dates are for (the one the user came from, else today). */
    val day: LocalDate,
    val query: String = "",
    val loading: Boolean = true,
    val books: List<PickerBook> = emptyList(),
    val failed: Boolean = false,
)

/** The dates sheet for one book. */
@Immutable
data class DatesUiState(
    val book: PickerBook,
    /** The day the user came from (the calendar's); null when editing a reading from History. */
    val day: LocalDate?,
    /** Today in the user's zone (users.settings.timezone): no date may be later. */
    val today: LocalDate,
    /** The book's readings and status are being fetched. */
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val attempts: List<ReadingAttempt> = emptyList(),
    /** The user's read status now (null: none). */
    val status: String? = book.status,
    /** The reading was chosen before the sheet opened (History's Edit dates): no choice between readings. */
    val fixed: Boolean = false,
    val target: DatesTarget = DatesTarget.New,
    /**
     * The user picked [target] themselves. Until then it follows the end date and the readings
     * ([ReadingDates.defaultTarget]): moving the end date, or the readings arriving, can move it.
     */
    val targetChosen: Boolean = false,
    val form: DatesForm,
    /** The fields the user set themselves: they stay as set when [target] moves or the readings arrive late. */
    val edited: Set<DatesField> = emptySet(),
    val saving: Boolean = false,
    /** Why the last save failed (the server's words, when it gave some). */
    val error: String? = null,
) {
    val problem: DatesProblem? get() = ReadingDates.problem(form, today)
    val plan: DatesPlan get() = ReadingDates.plan(target, form, attempts, status)

    /** The status the save moves the book to, when it changes (shown under the dates). */
    val statusChange: String? get() = if (loading || loadFailed) null else ReadingDates.statusChange(status, plan)

    /** The book stays on want to read, so the calendar and History won't show the reading (said under the dates). */
    val staysWanted: Boolean get() = !loading && !loadFailed && ReadingDates.staysWanted(status, plan)
    val outcomes: List<String?> get() = ReadingDates.outcomes(target, attempts)

    /** The reading chosen isn't among the book's any more (deleted or changed on another device). */
    val targetGone: Boolean
        get() = !loading && target.let { t -> t is DatesTarget.Existing && attempts.none { it.id == t.attemptId } }

    /** Only once the readings are in: which reading the dates go to depends on them. */
    val canSave: Boolean get() = !loading && !loadFailed && !saving && !targetGone && problem == null
}

@Immutable
data class ReadingDatesUiState(
    val picker: PickerUiState? = null,
    val dates: DatesUiState? = null,
    /** The scanner is open to pick the book (the picker waits behind it). */
    val scanning: Boolean = false,
)

/**
 * Start and end dates for a book: the picker (the Calendar's +, a Day's "Add a book for this
 * day"), then the dates sheet; or the sheet straight away for a reading ([edit], History). The
 * book's readings decide where the dates go ([ReadingDates.defaultTarget]); the user can pick another
 * reading or a new one. Saving is the book page's past-read path ([ReadingDatesRemote], the
 * tracker): the write runs in [writeScope] (the app's), so leaving the screen doesn't cut it off;
 * the book is then read again and handed to the screen on [saved] (its calendar marks or History
 * rows show it at once; the tracker's own change signal reloads them too). Save tapped again after
 * an add whose answer was lost doesn't add the reading twice ([create]).
 */
class ReadingDatesViewModel(
    private val remote: ReadingDatesRemote,
    private val today: () -> LocalDate,
    private val writeScope: CoroutineScope? = null,
    /** The book the scanner handed back (feature.scan's pick mode), taken once. */
    private val takeScanned: () -> PickerBook? = { ScanPicks.take()?.toPickerBook() },
    private val clearScanned: () -> Unit = ScanPicks::clear,
    private val searchDelayMs: Long = SEARCH_DELAY_MS,
) : ViewModel() {

    private val _state = MutableStateFlow(ReadingDatesUiState())
    val state: StateFlow<ReadingDatesUiState> = _state.asStateFlow()

    private val _saved = MutableSharedFlow<SavedReading>(extraBufferCapacity = 4)

    /** Each book saved, as read again after the save. */
    val saved: SharedFlow<SavedReading> = _saved.asSharedFlow()

    private var searchJob: Job? = null
    private var loadJob: Job? = null

    /**
     * A reading Save asked the server to add without a definite answer: [known] the book's reading
     * ids the sheet showed for that send, [sent] what was sent, [attemptId] the reading once found
     * on the server ([create]).
     */
    private data class LostCreate(val known: Set<Long>, val sent: DatesSave.Create, val attemptId: Long? = null)

    /** Per book id. Touched only in [create], one save at a time, on the main thread. */
    private val lostCreates = mutableMapOf<Long, LostCreate>()

    // --- the picker ---------------------------------------------------------------------------

    /** Opens the picker for [day] (null: today). */
    fun openPicker(day: LocalDate? = null) {
        val now = today()
        _state.update { it.copy(picker = PickerUiState(day = minOf(day ?: now, now)), dates = null, scanning = false) }
        runSearch("", wait = false)
    }

    fun search(query: String) {
        val picker = _state.value.picker ?: return
        if (query == picker.query) return
        _state.update { it.copy(picker = picker.copy(query = query)) }
        runSearch(query, wait = true)
    }

    fun retrySearch() {
        _state.value.picker?.let { runSearch(it.query, wait = false) }
    }

    fun closePicker() {
        searchJob?.cancel()
        _state.update { it.copy(picker = null, scanning = false) }
    }

    private fun runSearch(query: String, wait: Boolean) {
        searchJob?.cancel()
        _state.update { s -> s.copy(picker = s.picker?.copy(loading = true, failed = false)) }
        searchJob = viewModelScope.launch {
            if (wait) delay(searchDelayMs)
            val text = query.trim()
            try {
                val books = if (text.isEmpty()) remote.readingNow() else remote.search(text)
                _state.update { s -> s.copy(picker = s.picker?.takeIf { it.query == query }?.copy(loading = false, books = books) ?: s.picker) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { s -> s.copy(picker = s.picker?.takeIf { it.query == query }?.copy(loading = false, failed = true) ?: s.picker) }
            }
        }
    }

    /** The user picked [book]: the dates sheet for the picker's day. */
    fun pick(book: PickerBook) {
        val day = _state.value.picker?.day ?: today()
        searchJob?.cancel()
        _state.update { it.copy(picker = null, scanning = false) }
        open(book, day, edited = null)
    }

    /** "Scan a book": the picker steps aside while the scanner is open. */
    fun scan() {
        clearScanned()
        _state.update { it.copy(scanning = true) }
    }

    /** The screen showed again: back from the scanner, with a book or without one. */
    fun onResume() {
        if (!_state.value.scanning) return
        val book = takeScanned()
        if (book != null) pick(book) else _state.update { it.copy(scanning = false) }
    }

    // --- the dates sheet ----------------------------------------------------------------------

    /** The sheet for one reading of [book] (History's "Edit dates" and "Add dates"). */
    fun edit(book: PickerBook, attempt: ReadingAttempt) {
        _state.update { it.copy(picker = null, scanning = false) }
        open(book, day = null, edited = attempt)
    }

    private fun open(book: PickerBook, day: LocalDate?, edited: ReadingAttempt?) {
        val now = today()
        val attempts = listOfNotNull(edited)
        val target = edited?.let { DatesTarget.Existing(it.id) } ?: DatesTarget.New
        _state.update {
            it.copy(
                dates = DatesUiState(
                    book = book,
                    day = day,
                    today = now,
                    attempts = attempts,
                    fixed = edited != null,
                    target = target,
                    form = ReadingDates.initialForm(target, attempts, day, now),
                ),
            )
        }
        load()
    }

    fun retryLoad() = load()

    private fun load() {
        val dates = _state.value.dates ?: return
        val bookId = dates.book.id
        loadJob?.cancel()
        updateDates(bookId) { it.copy(loading = true, loadFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                val (attempts, detail) = coroutineScope {
                    val attempts = async { remote.attempts(bookId) }
                    // Only for the user's status and the formats: the sheet works without it.
                    val detail = async {
                        try {
                            remote.book(bookId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    attempts.await() to detail.await()
                }
                updateDates(bookId) { d ->
                    val status = if (detail != null) detail.readStatus?.status else d.status
                    val formats = d.book.formats.ifEmpty {
                        detail?.files.orEmpty().sortedByDescending { it.role == "primary" }.mapNotNull { it.format?.lowercase() }.distinct()
                    }
                    val next = d.copy(
                        loading = false,
                        attempts = attempts,
                        status = status,
                        book = d.book.copy(status = status, formats = formats),
                    )
                    // History's reading too: its copy can be older than the server's, and Save
                    // sends every field, so the ones the user hasn't set are the server's.
                    next.retargeted()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateDates(bookId) { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    /**
     * Where the dates go: [target] (one of the book's readings, or a new one), the user's choice from now
     * on; the fields follow it.
     */
    fun chooseTarget(target: DatesTarget) = editDates { d ->
        when {
            d.fixed -> d
            d.target == target -> d.copy(targetChosen = true)
            else -> d.copy(
                target = target,
                targetChosen = true,
                form = ReadingDates.initialForm(target, d.attempts, d.day, d.today),
                edited = emptySet(),
                error = null,
            )
        }
    }

    fun setStarted(date: LocalDate?) = editDates {
        it.copy(form = it.form.copy(started = date), edited = it.edited + DatesField.START, error = null)
    }

    /** The end date; until the user has picked a reading, the one it goes to follows it ([retargeted]). */
    fun setEnded(date: LocalDate) = editDates { d ->
        val next = d.copy(form = d.form.copy(ended = date), edited = d.edited + DatesField.END, error = null)
        if (d.fixed || d.loading) next else next.retargeted()
    }

    fun setOutcome(outcome: String?) = editDates { d ->
        if (outcome !in d.outcomes) d else d.copy(form = d.form.copy(outcome = outcome), edited = d.edited + DatesField.OUTCOME, error = null)
    }

    /**
     * The reading the dates go to, as [ReadingDates.defaultTarget] says for the end date in the
     * form, unless the user picked one (or History did: [DatesUiState.fixed]); the fields the user hasn't
     * set follow that reading as fetched ([ReadingDates.refill]).
     */
    private fun DatesUiState.retargeted(): DatesUiState {
        val next = if (fixed || targetChosen) target else ReadingDates.defaultTarget(attempts, form.ended)
        return copy(target = next, form = ReadingDates.refill(next, attempts, day, today, form, edited))
    }

    fun closeDates() {
        if (_state.value.dates?.saving == true) return
        loadJob?.cancel()
        _state.update { it.copy(dates = null) }
    }

    fun save() {
        val dates = _state.value.dates ?: return
        if (!dates.canSave) return
        val bookId = dates.book.id
        val plan = dates.plan
        val shown = dates.attempts
        updateDates(bookId) { it.copy(saving = true, error = null) }
        val request = (writeScope ?: viewModelScope).async { perform(bookId, plan, shown) }
        viewModelScope.launch {
            try {
                val saved = request.await()
                _state.update { s -> if (s.dates?.book?.id == bookId) s.copy(dates = null) else s }
                if (saved != null) _saved.tryEmit(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateDates(bookId) { it.copy(saving = false, error = e.message.orEmpty()) }
            }
        }
    }

    /**
     * The write, then the book read again for the screen (null when that read fails: the reload
     * will bring it). The server rebuilds the user's status from the readings after the write, so the
     * status read again (else the one [plan] works out) replaces any this process showed before
     * ([ReadingDatesRemote.statusChanged]): the library's grids would keep an old one otherwise.
     */
    private suspend fun perform(bookId: Long, plan: DatesPlan, shown: List<ReadingAttempt>): SavedReading? {
        when (val save = plan.save) {
            is DatesSave.Create -> create(bookId, save, shown)
            is DatesSave.Update -> remote.update(bookId, save.attemptId, save.startedOn, save.endedOn, save.outcome)
        }
        var expected: String? = plan.statusAfter
        plan.thenStatus?.let { status ->
            // The reading is in either way; a status that couldn't be set leaves the book as it was.
            try {
                remote.setStatus(bookId, status)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                expected = null
            }
        }
        return try {
            coroutineScope {
                val book = async { remote.book(bookId) }
                val attempts = async { remote.attempts(bookId) }
                val detail = book.await()
                remote.statusChanged(bookId, detail.readStatus?.status ?: ReadStatus.UNREAD.value)
                expected = null // the server's own, even if the readings can't be read
                SavedReading(ReadingDates.cardOf(detail), attempts.await())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            expected?.let { remote.statusChanged(bookId, it) }
            null
        }
    }

    /**
     * Adds the reading once, however often Save is tapped. The POST isn't idempotent, and one
     * whose answer was lost (or became a 5xx after the server stored the reading) may have added
     * it all the same. So after such a failure the next save looks among the book's readings for
     * a new one made as sent, and sets that reading's dates (a PATCH, safe to repeat) instead of
     * adding another. Only a refusal (4xx) says nothing was stored. [shown]: the book's readings
     * as the sheet showed them for this save.
     */
    private suspend fun create(bookId: Long, save: DatesSave.Create, shown: List<ReadingAttempt>) {
        val arrived = lostCreates[bookId]?.let { lost ->
            val found = lost.attemptId ?: remote.attempts(bookId)
                .filter { it.id !in lost.known && lost.sent.made(it) }
                .maxByOrNull { it.id }?.id
            // One the sheet lists (it was opened again since) the user has seen: other dates now are
            // another reading, and moving that one onto them would lose it without a word.
            val seen = shown.find { it.id == found }
            found?.takeIf { seen == null || save.made(seen) }?.also { lostCreates[bookId] = lost.copy(attemptId = it) }
        }
        if (arrived != null) {
            try {
                remote.update(bookId, arrived, save.startedOn?.let { Patch.Set(it) } ?: Patch.Clear, Patch.Set(save.endedOn), Patch.Set(save.outcome))
            } catch (e: ApiException) {
                if (e.code == 404) lostCreates.remove(bookId) // deleted meanwhile: the next save adds it again
                throw e
            }
        } else {
            lostCreates[bookId] = LostCreate(known = shown.mapTo(HashSet()) { it.id }, sent = save)
            try {
                remote.create(bookId, save.startedOn, save.endedOn, save.outcome)
            } catch (e: ApiException) {
                if (e.code in 400..499) lostCreates.remove(bookId) // refused: nothing was stored
                throw e
            }
        }
        lostCreates.remove(bookId)
    }

    /** Whether [attempt] is the reading this create adds (the server stores it as sent, origin manual). */
    private fun DatesSave.Create.made(attempt: ReadingAttempt): Boolean =
        attempt.origin == "manual" &&
            ReadingDates.date(attempt.startedOn) == startedOn &&
            ReadingDates.date(attempt.endedOn) == endedOn &&
            attempt.outcome == outcome

    private fun editDates(change: (DatesUiState) -> DatesUiState) {
        _state.update { s -> s.dates?.takeIf { !it.saving }?.let { s.copy(dates = change(it)) } ?: s }
    }

    private fun updateDates(bookId: Long, change: (DatesUiState) -> DatesUiState) {
        _state.update { s -> s.dates?.takeIf { it.book.id == bookId }?.let { s.copy(dates = change(it)) } ?: s }
    }

    private companion object {
        const val SEARCH_DELAY_MS = 300L
    }
}

/**
 * The screen's [ReadingDatesViewModel] (Calendar, Day, History): the tracker's writes in the app
 * scope, dates in the account's zone (users.settings.timezone, as the calendars split days).
 */
@Composable
fun readingDatesViewModel(): ReadingDatesViewModel = appViewModel { c ->
    ReadingDatesViewModel(
        remote = ApiReadingDatesRemote.of(c.api, c.tracking, c.readingChanges),
        today = { LocalDate.now(serverZone(c.auth.user.value)) },
        writeScope = c.appScope,
    )
}

/** A book the scanner handed back, as the picker shows it. */
internal fun ScanBook.toPickerBook() = PickerBook(id = id, title = title, authors = authors, cover = cover, formats = formats, status = status)
