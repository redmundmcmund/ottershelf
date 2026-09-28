package io.github.ottershelf.feature.quotes

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.settings.AppSettingsRepository
import io.github.ottershelf.feature.notes.NoteChanges
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.HighlightColors
import java.io.File

/** The book picker (a quote opened from the Notes feed): what the user is reading, or a search. */
@Immutable
data class PickerState(
    val query: String = "",
    val loading: Boolean = true,
    val books: List<QuoteBook> = emptyList(),
    val error: String? = null,
)

/** Reading a photographed page: the camera, the recognition, then picking the lines to keep. */
sealed interface ScanStep {
    data object Camera : ScanStep
    data class Reading(val photo: File) : ScanStep

    /** The recognised lines over the photo; [selected] holds [OcrLine.key]s. */
    data class Picking(val photo: File, val image: Bitmap, val page: OcrPage, val selected: Set<Long> = emptySet()) : ScanStep
    data class Failed(val detail: String?) : ScanStep
}

@Immutable
data class AddQuoteUiState(
    val book: QuoteBook? = null,
    val bookLoading: Boolean = false,
    val bookError: String? = null,
    /** Opened from the book's page: the book can't change. */
    val bookFixed: Boolean = false,
    val text: String = "",
    val pageFrom: String = "",
    val pageTo: String = "",
    val note: String = "",
    val color: String = HighlightColors.DEFAULT,
    /** The page photo the text came from (in the work folder), if any. */
    val photo: File? = null,
    val keepPhoto: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
    /** Saved: the screen closes. */
    val done: Boolean = false,
    val picker: PickerState? = null,
    val scan: ScanStep? = null,
) {
    val pageLabel: String? get() = QuotePosition.pageLabel(pageFrom.toIntOrNull(), pageTo.toIntOrNull())
    val canSave: Boolean get() = book != null && text.isNotBlank() && !saving
}

/** A save whose answer never came: the book it went to and what was sent (its CFI finds it). */
internal data class LostSave(val bookId: Long, val body: CreateQuoteBody)

/** What a retry does with the quote [LostSave] may have left on the server. */
internal sealed interface RetryStep {
    /** It never arrived: send the form, reusing the lost CFI when [reuseCfi]. */
    data class Afresh(val reuseCfi: Boolean) : RetryStep

    /** It arrived as the form is now: nothing to send. */
    data class Done(val found: Annotation) : RetryStep

    /** It arrived; only the thought or colour changed since, which can be edited. */
    data class Patch(val found: Annotation) : RetryStep

    /** It arrived, but the text, page or book changed since (not editable): trash it, send afresh. */
    data class Replace(val found: Annotation) : RetryStep

    companion object {
        /** [found] is the lost save's quote on the server, if any; [bookId] and [body] are the form now. */
        fun of(lost: LostSave, found: Annotation?, bookId: Long, body: CreateQuoteBody): RetryStep = when {
            found == null -> Afresh(reuseCfi = lost.bookId == bookId)
            lost.bookId != bookId || found.text != body.text || found.chapterTitle != body.chapterTitle -> Replace(found)
            found.note.orEmpty() != body.note.orEmpty() || !found.color.equals(body.color, ignoreCase = true) -> Patch(found)
            else -> Done(found)
        }
    }
}

/**
 * The Add quote screen: the form, the book picker and the page scan. Save posts one annotation
 * with a fresh placeholder CFI; a retry after an answer that never came first looks for that CFI
 * among the newest annotations of the book it went to, so a quote isn't saved twice (and an edit
 * made since isn't lost: see [RetryStep]). The form survives the process being killed ([saved]).
 */
class AddQuoteViewModel(
    private val remote: QuotesRemote,
    private val settings: AppSettingsRepository?,
    private val appContext: Context?,
    private val account: () -> String,
    bookId: Long?,
    title: String?,
    bookFixed: Boolean,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    constructor(container: AppContainer, context: Context, bookId: Long?, title: String?, bookFixed: Boolean, saved: SavedStateHandle) : this(
        remote = ApiQuotesRemote(container.api),
        settings = container.appSettings,
        appContext = context.applicationContext,
        account = { container.session.accountKey() },
        bookId = bookId,
        title = title,
        bookFixed = bookFixed,
        saved = saved,
    )

    private val _state = MutableStateFlow(restored(bookId, title, bookFixed && bookId != null))
    val state: StateFlow<AddQuoteUiState> = _state.asStateFlow()

    private var lost: LostSave? = restoredLost()
    private var searchJob: Job? = null

    init {
        _state.value.book?.id?.let { loadBook(it) }
        // The form as the user left it, for after the process was killed (in the Photo Picker, or away).
        viewModelScope.launch { _state.collect { persist(it) } }
        sweepWorkFolder()
    }

    /** The saved form over the route's book (a picked book wins when the route's isn't fixed). */
    private fun restored(bookId: Long?, title: String?, fixed: Boolean): AddQuoteUiState {
        val pickedId = saved.get<Long>(KEY_BOOK_ID)
        val book = when {
            !fixed && pickedId != null -> QuoteBook(pickedId, saved.get<String>(KEY_BOOK_TITLE), null, null)
            bookId != null -> QuoteBook(bookId, title, null, null)
            else -> null
        }
        return AddQuoteUiState(
            book = book,
            bookFixed = fixed,
            text = saved.get<String>(KEY_TEXT).orEmpty(),
            pageFrom = saved.get<String>(KEY_PAGE_FROM).orEmpty(),
            pageTo = saved.get<String>(KEY_PAGE_TO).orEmpty(),
            note = saved.get<String>(KEY_NOTE).orEmpty(),
            color = saved.get<String>(KEY_COLOR) ?: HighlightColors.DEFAULT,
            photo = saved.get<String>(KEY_PHOTO)?.let(::File)?.takeIf { it.isFile },
            keepPhoto = saved.get<Boolean>(KEY_KEEP_PHOTO) ?: false,
        )
    }

    private fun restoredLost(): LostSave? {
        val bookId = saved.get<Long>(KEY_LOST_BOOK) ?: return null
        val body = saved.get<String>(KEY_LOST_BODY) ?: return null
        return runCatching { LostSave(bookId, ApiJson.decodeFromString(CreateQuoteBody.serializer(), body)) }.getOrNull()
    }

    private fun persist(s: AddQuoteUiState) {
        saved[KEY_BOOK_ID] = s.book?.id
        saved[KEY_BOOK_TITLE] = s.book?.title
        saved[KEY_TEXT] = s.text
        saved[KEY_PAGE_FROM] = s.pageFrom
        saved[KEY_PAGE_TO] = s.pageTo
        saved[KEY_NOTE] = s.note
        saved[KEY_COLOR] = s.color
        saved[KEY_PHOTO] = s.photo?.path
        saved[KEY_KEEP_PHOTO] = s.keepPhoto
    }

    private fun setLost(value: LostSave?) {
        lost = value
        saved[KEY_LOST_BOOK] = value?.bookId
        saved[KEY_LOST_BODY] = value?.let { ApiJson.encodeToString(CreateQuoteBody.serializer(), it.body) }
    }

    private fun loadBook(id: Long) {
        _state.update { it.copy(bookLoading = true, bookError = null) }
        viewModelScope.launch {
            try {
                val book = remote.book(id)
                _state.update { if (it.book?.id == id) it.copy(book = book, bookLoading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(bookLoading = false, bookError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun setText(value: String) = _state.update { it.copy(text = value.take(TEXT_MAX), saveError = null) }
    fun setPageFrom(value: String) = _state.update { it.copy(pageFrom = QuotePosition.pageDigits(value)) }
    fun setPageTo(value: String) = _state.update { it.copy(pageTo = QuotePosition.pageDigits(value)) }
    fun setNote(value: String) = _state.update { it.copy(note = value.take(TEXT_MAX)) }
    fun setColor(hex: String) = _state.update { it.copy(color = hex) }
    fun setKeepPhoto(keep: Boolean) = _state.update { it.copy(keepPhoto = keep) }

    // --- Book picker ------------------------------------------------------------------------------

    fun openPicker() {
        if (_state.value.bookFixed) return
        _state.update { it.copy(picker = PickerState()) }
        search("")
    }

    fun closePicker() {
        searchJob?.cancel()
        _state.update { it.copy(picker = null) }
    }

    fun search(query: String) {
        _state.update { s -> s.picker?.let { s.copy(picker = it.copy(query = query, loading = true, error = null)) } ?: s }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val q = query.trim()
            if (q.isNotEmpty()) delay(SEARCH_DELAY_MS)
            try {
                val books = if (q.isEmpty()) remote.reading() else remote.search(q)
                _state.update { s -> s.picker?.let { s.copy(picker = it.copy(loading = false, books = books)) } ?: s }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s -> s.picker?.let { s.copy(picker = it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)) } ?: s }
            }
        }
    }

    fun pickBook(book: QuoteBook) {
        searchJob?.cancel()
        _state.update { it.copy(book = book, picker = null, bookError = null, saveError = null) }
    }

    // --- Scan -------------------------------------------------------------------------------------

    /** The camera (also Retake): the photo of the step it replaces goes. */
    fun openCamera() {
        discardWorkPhoto()
        _state.update { it.copy(scan = ScanStep.Camera) }
    }

    fun closeScan() {
        discardWorkPhoto()
        _state.update { it.copy(scan = null) }
    }

    /**
     * A photo taken with the camera (already in the work folder). One that lands after the camera
     * was closed (a slow capture) is deleted, and doesn't reopen the scan.
     */
    fun photoTaken(photo: File) {
        if (_state.value.scan != ScanStep.Camera) {
            discard(photo)
            return
        }
        read(photo)
    }

    /** A photo picked from the gallery. */
    fun photoPicked(uri: Uri) {
        val context = appContext ?: return
        viewModelScope.launch {
            try {
                read(PageReader.copyIn(context, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(scan = ScanStep.Failed(e.message)) }
            }
        }
    }

    /** The camera failed; ignored once it was closed (a capture aborted by closing it). */
    fun captureFailed(detail: String?) =
        _state.update { if (it.scan == ScanStep.Camera) it.copy(scan = ScanStep.Failed(detail)) else it }

    private fun read(photo: File) {
        discardWorkPhoto(except = photo)
        _state.update { it.copy(scan = ScanStep.Reading(photo)) }
        viewModelScope.launch {
            try {
                val context = checkNotNull(appContext) { "No context to read the page with" }
                val image = PageReader.decode(photo)
                val page = withContext(Dispatchers.Default) { PageReader.recognise(context, image) }
                _state.update { if ((it.scan as? ScanStep.Reading)?.photo == photo) it.copy(scan = ScanStep.Picking(photo, image, page)) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                discard(photo)
                _state.update {
                    if ((it.scan as? ScanStep.Reading)?.photo == photo) it.copy(scan = ScanStep.Failed(e.message ?: e.javaClass.simpleName)) else it
                }
            }
        }
    }

    /** Deletes the photo the scan step is working on (Reading or Picking), unless the form uses it. */
    private fun discardWorkPhoto(except: File? = null) {
        val s = _state.value
        val photo = when (val scan = s.scan) {
            is ScanStep.Reading -> scan.photo
            is ScanStep.Picking -> scan.photo
            else -> null
        }
        if (photo != null && photo != s.photo && photo != except) discard(photo)
    }

    /**
     * Work photos a screen closed long ago left behind (the process killed mid-scan): cleared when
     * the screen opens, but for the form's own and anything from today (another quote screen's).
     */
    private fun sweepWorkFolder() {
        val context = appContext ?: return
        val keep = _state.value.photo
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - SWEEP_AGE_MS
            PageReader.workDir(context).listFiles()?.forEach { if (it != keep && it.lastModified() < cutoff) it.delete() }
        }
    }

    fun toggleLine(key: Long) = updatePicking { it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key) }

    fun setLines(keys: Set<Long>, selected: Boolean) =
        updatePicking { it.copy(selected = if (selected) it.selected + keys else it.selected - keys) }

    fun selectAll(all: Boolean) = updatePicking { p -> p.copy(selected = if (all) p.page.lines.map { it.key }.toSet() else emptySet()) }

    private fun updatePicking(transform: (ScanStep.Picking) -> ScanStep.Picking) =
        _state.update { s -> (s.scan as? ScanStep.Picking)?.let { s.copy(scan = transform(it)) } ?: s }

    /** Puts the picked lines, cleaned up, into the quote (after what's there) and returns to the form. */
    fun useLines() {
        val picking = _state.value.scan as? ScanStep.Picking ?: return
        val text = QuoteCleanup.join(picking.page.lines.filter { it.key in picking.selected })
        if (text.isEmpty()) return
        val old = _state.value.photo
        _state.update {
            it.copy(
                text = listOf(it.text.trim(), text).filter { t -> t.isNotEmpty() }.joinToString("\n").take(TEXT_MAX),
                photo = picking.photo,
                scan = null,
                saveError = null,
            )
        }
        if (old != null && old != picking.photo) discard(old)
    }

    // --- Save -------------------------------------------------------------------------------------

    fun save() {
        val s = _state.value
        val book = s.book ?: return
        if (!s.canSave) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            var creating = false
            try {
                val form = CreateQuoteBody(
                    cfi = "",
                    text = s.text.trim(),
                    color = s.color,
                    note = s.note.trim().ifEmpty { null },
                    chapterTitle = s.pageLabel,
                )
                // A save before this one lost its answer: see what it left, in the book it went to.
                val prior = lost
                val step = prior?.let { p -> RetryStep.of(p, remote.newest(p.bookId, LOOK_BACK).firstOrNull { it.cfi == p.body.cfi }, book.id, form) }
                val kept: Annotation? = when (step) {
                    is RetryStep.Done -> step.found
                    is RetryStep.Patch -> remote.update(book.id, step.found.id, form.note, form.color)
                    is RetryStep.Replace -> {
                        // Text and page can't be edited (nor the book changed): the web's trash has it.
                        try {
                            remote.delete(step.found.bookId, step.found.id)
                        } catch (e: ApiException) {
                            if (e.code != 404) throw e
                        }
                        setLost(null)
                        null
                    }
                    is RetryStep.Afresh, null -> null
                }
                val created = kept ?: run {
                    val cfi = prior?.body?.cfi?.takeIf { step is RetryStep.Afresh && step.reuseCfi } ?: QuotePosition.newCfi()
                    val body = form.copy(cfi = cfi)
                    setLost(LostSave(book.id, body))
                    creating = true
                    remote.create(book.id, body)
                }
                setLost(null)
                val photo = s.photo
                if (photo != null) {
                    val context = appContext
                    if (s.keepPhoto && context != null) QuotePhotos.keep(context, account(), settings, created.id, photo) else discard(photo)
                }
                NoteChanges.changed(book.id)
                _state.update { it.copy(saving = false, done = true, photo = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server refused the new quote: nothing was saved, so the next try sends it afresh.
                if (creating && e is ApiException && e.code in 400..499) setLost(null)
                _state.update { it.copy(saving = false, saveError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    override fun onCleared() {
        // Leaving without saving: the photos being worked on go.
        val s = _state.value
        if (!s.done) s.photo?.let { discard(it) }
        when (val scan = s.scan) {
            is ScanStep.Reading -> discard(scan.photo)
            is ScanStep.Picking -> discard(scan.photo)
            else -> Unit
        }
    }

    private fun discard(file: File) {
        runCatching { file.delete() }
    }

    companion object {
        const val TEXT_MAX = 10_000
        private const val SEARCH_DELAY_MS = 350L
        private const val LOOK_BACK = 10
        private const val SWEEP_AGE_MS = 24 * 60 * 60 * 1000L

        private const val KEY_BOOK_ID = "quote.bookId"
        private const val KEY_BOOK_TITLE = "quote.bookTitle"
        private const val KEY_TEXT = "quote.text"
        private const val KEY_PAGE_FROM = "quote.pageFrom"
        private const val KEY_PAGE_TO = "quote.pageTo"
        private const val KEY_NOTE = "quote.note"
        private const val KEY_COLOR = "quote.color"
        private const val KEY_PHOTO = "quote.photo"
        private const val KEY_KEEP_PHOTO = "quote.keepPhoto"
        private const val KEY_LOST_BOOK = "quote.lostBook"
        private const val KEY_LOST_BODY = "quote.lostBody"
    }
}
