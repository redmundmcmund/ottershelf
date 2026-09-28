package io.github.ottershelf.feature.bookedit

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.network.ApiJson
import java.io.File

/** BookOrbit's permission for `PATCH books/:id/metadata` and the cover routes (superusers hold it too). */
const val EDIT_METADATA_PERMISSION = "library_edit_metadata"

/** Whether [user] may edit book details: the web's `hasPermission('library_edit_metadata')`. */
fun canEditDetails(user: AuthUser?): Boolean = user?.can(EDIT_METADATA_PERMISSION) == true

/** How "Use the file's cover" works for the book's cover now. */
enum class FromFile {
    /**
     * The user's cover goes (`DELETE books/:id/cover`, asked first) and the file's own comes back, if the
     * server has one from the file ([CoverMessage.NoCoverLeft] when it hasn't).
     */
    RESTORE,

    /** No cover yet: the book's file is read for one (`POST books/:id/re-extract-cover`). */
    EXTRACT,
}

/** The cover card's line under the actions. */
sealed interface CoverMessage {
    data object Updated : CoverMessage
    data object NoneInFile : CoverMessage

    /** The user's cover removed, and the book's file had none to show instead: the book has no cover now. */
    data object NoCoverLeft : CoverMessage
    data object PhotoUnreadable : CoverMessage
    data object CameraFailed : CoverMessage
    data class Failed(val error: EditError) : CoverMessage
}

/** A cover chosen and waiting for "Use this cover" (or, for [FromFile], confirmation). */
sealed interface PendingCover {
    /** A photo from the phone or the camera, made ready in the work folder ([PreparedCover]). */
    data class Photo(val path: String, val width: Int, val height: Int) : PendingCover

    data class Online(val result: CoverResult) : PendingCover

    /** Back to the cover in the book's file: the one the user added is removed. */
    data object FromFileCover : PendingCover
}

/** The online cover search's sheet: what's searched for and what came back ([results] null before). */
data class CoverSearchUi(
    val title: String,
    val author: String,
    val provider: String,
    val audiobook: Boolean,
    val loading: Boolean = false,
    val results: List<CoverResult>? = null,
    val error: EditError? = null,
)

/**
 * The edit screen.
 *
 * @property book the book as the server last said (cover, locks); null until loaded.
 * @property cover the cover to show (the book's versioned thumbnail), null for the generated one.
 * @property original the form as loaded: Save sends only what differs from it.
 * @property authorInput the author box's text, not yet a chip (Save adds it).
 * @property coverBusy a cover change (or a photo being made ready) is under way.
 * @property camera the camera shows over the form.
 * @property unlocking a lock being removed on the server.
 * @property done saved: the screen closes.
 */
data class BookEditUiState(
    val bookId: Long,
    val canEdit: Boolean = true,
    val loading: Boolean = true,
    val loadError: EditError? = null,
    val book: BookDetail? = null,
    val cover: Any? = null,
    val original: EditForm? = null,
    val form: EditForm = EditForm(),
    val authorInput: String = "",
    val authorSuggestions: Suggestions<AuthorSuggestion> = Suggestions(),
    val seriesSuggestions: Suggestions<SeriesSuggestion> = Suggestions(),
    val saving: Boolean = false,
    val saveError: EditError? = null,
    val coverBusy: Boolean = false,
    val coverMessage: CoverMessage? = null,
    val pendingCover: PendingCover? = null,
    val coverSearch: CoverSearchUi? = null,
    val camera: Boolean = false,
    val unlocking: LockGroup? = null,
    val unlockError: EditError? = null,
    val done: Boolean = false,
) {
    val title: String? get() = book?.title ?: original?.title?.takeIf { it.isNotBlank() }

    val locked: List<String> get() = book?.lockedFields.orEmpty()

    fun isLocked(group: LockGroup): Boolean = group.lockedIn(locked)

    /** The form as Save sends it: a name still in the author box counts (the web's commitPending). */
    val formToSave: EditForm
        get() = if (authorInput.isBlank()) form else form.copy(authors = EditRules.addAuthor(form.authors, authorInput))

    val changes: MetadataChanges get() = original?.let { MetadataChanges.of(it, formToSave, locked) } ?: MetadataChanges()

    val problems: Set<FormProblem> get() = original?.let { problemsOf(it, formToSave, locked) } ?: emptySet()

    /** Something typed that isn't saved (Back asks first). */
    val dirty: Boolean get() = !changes.isEmpty

    val canSave: Boolean get() = canEdit && book != null && dirty && problems.isEmpty() && !saving

    /** What "Use the file's cover" does now, or null when it isn't offered (already the file's, no file). */
    val fromFile: FromFile?
        get() {
            val b = book ?: return null
            if (b.files.isEmpty()) return null
            return when (b.coverSource) {
                "custom" -> FromFile.RESTORE
                null -> FromFile.EXTRACT
                else -> null
            }
        }

    /** Cover actions can run: loaded, not locked, nothing else under way. */
    val coverActionsEnabled: Boolean get() = canEdit && book != null && !isLocked(LockGroup.COVER) && !coverBusy
}

/** Photos turned into uploads (Android's decoders; a fake in tests). */
interface CoverPhotos {
    suspend fun fromPicker(uri: Uri): PreparedCover
    suspend fun fromCamera(photo: File, frame: CameraFrame?): PreparedCover
    suspend fun bytes(file: File): ByteArray
    fun discard(file: File)
}

private class AndroidCoverPhotos(private val context: Context) : CoverPhotos {
    override suspend fun fromPicker(uri: Uri) = CoverImage.fromPicker(context, uri)
    override suspend fun fromCamera(photo: File, frame: CameraFrame?) = CoverImage.fromCamera(context, photo, frame)
    override suspend fun bytes(file: File): ByteArray = withContext(Dispatchers.IO) { file.readBytes() }
    override fun discard(file: File) {
        file.delete()
    }
}

/**
 * Editing a book's title, authors, series and number, and its cover (see ARCHITECTURE.md, "Editing a
 * book's details"). The text fields are saved together with one `PATCH books/:id/metadata` carrying
 * only what changed; a cover change is its own call and applies at once. Everything that changed is
 * announced ([publish]) so the rest of the app shows it straight away.
 */
class BookEditViewModel(
    private val bookId: Long,
    private val remote: BookEditRemote,
    canEdit: Boolean,
    private val coverOf: (BookDetail) -> Any?,
    private val publish: (book: BookDetail, before: BookDetail?, coverChanged: Boolean) -> Unit,
    private val photos: CoverPhotos,
    private val saved: SavedStateHandle = SavedStateHandle(),
    debounceMs: Long = SuggestionSearch.DEBOUNCE_MS,
) : ViewModel() {

    private val _state = MutableStateFlow(BookEditUiState(bookId = bookId, canEdit = canEdit, loading = canEdit))
    val state: StateFlow<BookEditUiState> = _state.asStateFlow()

    private val authorSearch = SuggestionSearch(viewModelScope, debounceMs) { remote.authors(it) }
    private val seriesSearch = SuggestionSearch(viewModelScope, debounceMs) { remote.series(it) }

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var provider: String? = null

    /** A photo picked before the book had loaded ([photoPicked]): made ready once it has. */
    private var pickedWhileLoading: Uri? = null

    init {
        viewModelScope.launch { authorSearch.state.collect { s -> _state.update { it.copy(authorSuggestions = s) } } }
        viewModelScope.launch { seriesSearch.state.collect { s -> _state.update { it.copy(seriesSuggestions = s) } } }
        if (canEdit) load()
    }

    /** Loads the book (fresh: its values and locks as the server has them now). */
    fun load() {
        if (loadJob?.isActive == true || !_state.value.canEdit) return
        _state.update { it.copy(loading = true, loadError = null) }
        loadJob = viewModelScope.launch {
            try {
                val book = remote.book(bookId)
                // After the process was killed: the edit the user was making, against what it was made on.
                val original = restore(KEY_ORIGINAL) ?: EditForm.of(book)
                val form = (restore(KEY_FORM) ?: original).keepingLocked(original, book.lockedFields)
                val authorInput = saved.get<String>(KEY_AUTHOR_INPUT).orEmpty()
                _state.update {
                    it.copy(
                        loading = false,
                        book = book,
                        cover = coverOf(book),
                        original = it.original ?: original,
                        form = if (it.original == null) form else it.form,
                        authorInput = if (it.original == null) authorInput else it.authorInput,
                    )
                }
                persist()
                // Unless the cover turns out to be locked (its card says so, with Unlock).
                pickedWhileLoading?.let { uri ->
                    pickedWhileLoading = null
                    photoPicked(uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = EditError.of(e)) }
            }
        }
    }

    // --- the text fields ------------------------------------------------------------------------

    fun setTitle(text: String) = edit { it.copy(title = EditRules.titleOf(text)) }

    fun setAuthorInput(text: String) {
        val s = _state.value
        val (authors, rest) = EditRules.typeAuthor(s.form.authors, text)
        _state.update { it.copy(form = it.form.copy(authors = authors), authorInput = rest, saveError = null) }
        authorSearch.query(rest)
        persist()
    }

    /** A suggestion tapped, the "Add" row, or the keyboard's Done in the author box. */
    fun addAuthor(name: String = _state.value.authorInput) {
        _state.update { it.copy(form = it.form.copy(authors = EditRules.addAuthor(it.form.authors, name)), authorInput = "", saveError = null) }
        authorSearch.clear()
        persist()
    }

    fun removeAuthor(index: Int) = edit { form ->
        if (index !in form.authors.indices) form else form.copy(authors = form.authors.filterIndexed { i, _ -> i != index })
    }

    fun setSeries(text: String) {
        val name = text.take(EditRules.NAME_MAX)
        // No series, no number: the number box empties with the series.
        edit { it.copy(series = name, seriesIndex = if (name.isBlank()) "" else it.seriesIndex) }
        seriesSearch.query(name)
    }

    fun pickSeries(name: String) {
        edit { it.copy(series = name) }
        seriesSearch.clear()
    }

    fun clearSeries() {
        edit { it.copy(series = "", seriesIndex = "") }
        seriesSearch.clear()
    }

    fun setSeriesIndex(text: String) = edit { it.copy(seriesIndex = text.trim().take(EditRules.SERIES_INDEX_MAX)) }

    private fun edit(change: (EditForm) -> EditForm) {
        _state.update { it.copy(form = change(it.form), saveError = null) }
        persist()
    }

    // --- saving ---------------------------------------------------------------------------------

    /** One `PATCH books/:id/metadata` with what changed. A failure keeps everything the user typed. */
    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val changes = s.changes
        val before = s.book
        _state.update { it.copy(form = s.formToSave, authorInput = "", saving = true, saveError = null) }
        authorSearch.clear()
        persist()
        viewModelScope.launch {
            try {
                val book = remote.saveMetadata(bookId, changes.body())
                publish(book, before, false)
                val form = EditForm.of(book)
                _state.update { it.copy(saving = false, book = book, cover = coverOf(book), original = form, form = form, done = true) }
                clearSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, saveError = EditError.of(e)) }
                // Locked on the server since the screen opened: show it, so the rest can be saved.
                if (e is ApiException && e.code == 409) refreshLocks()
            }
        }
    }

    private suspend fun refreshLocks() {
        val fresh = try {
            remote.book(bookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        _state.update { s ->
            val original = s.original
            s.copy(
                book = s.book?.copy(lockedFields = fresh.lockedFields) ?: fresh,
                form = if (original != null) s.form.keepingLocked(original, fresh.lockedFields) else s.form,
            )
        }
        persist()
    }

    /**
     * Unlocks [group] on the server at once (asked first on the screen), as the web's lock button
     * does: the lock list is read fresh and written back without these fields, so a lock someone
     * added meanwhile stays.
     */
    fun unlock(group: LockGroup) {
        val s = _state.value
        if (s.unlocking != null || !s.isLocked(group)) return
        _state.update { it.copy(unlocking = group, unlockError = null) }
        viewModelScope.launch {
            try {
                val fresh = remote.book(bookId)
                val book = remote.setLocks(bookId, group.unlock(fresh.lockedFields))
                _state.update {
                    it.copy(
                        unlocking = null,
                        book = it.book?.copy(lockedFields = book.lockedFields) ?: book,
                        // A cover change refused for the lock: that reason no longer holds.
                        coverMessage = it.coverMessage.takeUnless { m -> group == LockGroup.COVER && m is CoverMessage.Failed },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(unlocking = null, unlockError = EditError.of(e)) }
            }
        }
    }

    // --- the cover ------------------------------------------------------------------------------

    /**
     * A photo from the Photo Picker: made ready (upright, smaller, JPEG), then shown to confirm. One
     * that comes back before the book has loaded (the process was killed while the picker was open,
     * so the screen was rebuilt and is loading again) waits for it rather than being dropped.
     */
    fun photoPicked(uri: Uri) {
        val s = _state.value
        if (s.book == null && s.canEdit) {
            pickedWhileLoading = uri
            return
        }
        prepare { photos.fromPicker(uri) }
    }

    fun openCamera() = _state.update { it.copy(camera = true, coverMessage = null) }

    fun closeCamera() = _state.update { it.copy(camera = false) }

    fun photoTaken(file: File, frame: CameraFrame?) {
        _state.update { it.copy(camera = false) }
        if (!_state.value.coverActionsEnabled) {
            photos.discard(file)
            return
        }
        prepare { photos.fromCamera(file, frame) }
    }

    fun cameraFailed() = _state.update { it.copy(camera = false, coverMessage = CoverMessage.CameraFailed) }

    private fun prepare(make: suspend () -> PreparedCover) {
        if (!_state.value.coverActionsEnabled) return
        _state.update { it.copy(coverBusy = true, coverMessage = null) }
        viewModelScope.launch {
            try {
                val photo = make()
                _state.value.pendingCover.discardPhoto()
                _state.update { it.copy(coverBusy = false, pendingCover = PendingCover.Photo(photo.file.path, photo.width, photo.height)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(coverBusy = false, coverMessage = CoverMessage.PhotoUnreadable) }
            }
        }
    }

    fun openCoverSearch() {
        val s = _state.value
        val book = s.book ?: return
        if (!s.coverActionsEnabled) return
        val title = s.form.title.trim().ifEmpty { book.title.orEmpty() }
        val author = s.formToSave.authors.firstOrNull() ?: book.authors.firstOrNull()?.name.orEmpty()
        _state.update {
            it.copy(coverSearch = CoverSearchUi(title, author, provider ?: CoverProviders.DUCKDUCKGO, isAudiobook(book)), coverMessage = null)
        }
        viewModelScope.launch {
            if (provider == null) {
                // The user's default source on the web (DuckDuckGo unless the user chose another there).
                val chosen = CoverProviders.of(runCatchingNonCancel { remote.coverProvider() })
                if (provider == null) { // not picked in the sheet meanwhile
                    provider = chosen
                    _state.update { st -> st.copy(coverSearch = st.coverSearch?.copy(provider = chosen)) }
                }
            }
            // A source picked meanwhile has searched already.
            val search = _state.value.coverSearch
            if (search != null && search.results == null && !search.loading) searchCovers()
        }
    }

    fun closeCoverSearch() {
        searchJob?.cancel()
        _state.update { it.copy(coverSearch = null) }
    }

    fun setSearchTitle(text: String) = _state.update { it.copy(coverSearch = it.coverSearch?.copy(title = text)) }

    fun setSearchAuthor(text: String) = _state.update { it.copy(coverSearch = it.coverSearch?.copy(author = text)) }

    fun setProvider(value: String) {
        val chosen = CoverProviders.of(value)
        provider = chosen
        _state.update { it.copy(coverSearch = it.coverSearch?.copy(provider = chosen)) }
        searchCovers()
    }

    fun searchCovers() {
        val search = _state.value.coverSearch ?: return
        if (search.title.isBlank()) return
        searchJob?.cancel()
        _state.update { it.copy(coverSearch = it.coverSearch?.copy(loading = true, error = null)) }
        searchJob = viewModelScope.launch {
            try {
                val results = remote.searchCovers(search.title, search.author, search.audiobook, search.provider)
                _state.update { it.copy(coverSearch = it.coverSearch?.copy(loading = false, results = results)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(coverSearch = it.coverSearch?.copy(loading = false, error = EditError.of(e))) }
            }
        }
    }

    fun pickOnline(result: CoverResult) = _state.update { it.copy(pendingCover = PendingCover.Online(result), coverMessage = null) }

    /** "Use the file's cover": asked first when it removes the cover the user added, straight away otherwise. */
    fun useFileCover() {
        val s = _state.value
        if (!s.coverActionsEnabled) return
        when (s.fromFile) {
            FromFile.RESTORE -> _state.update { it.copy(pendingCover = PendingCover.FromFileCover, coverMessage = null) }
            FromFile.EXTRACT -> changeCover(PendingCover.FromFileCover)
            null -> Unit
        }
    }

    /** "Use this cover" in the confirmation: the change is sent now (not with Save). */
    fun confirmCover() {
        val pending = _state.value.pendingCover ?: return
        changeCover(pending)
    }

    fun cancelCover() {
        _state.value.pendingCover.discardPhoto()
        _state.update { it.copy(pendingCover = null, coverMessage = null) }
    }

    private fun changeCover(pending: PendingCover) {
        val s = _state.value
        val before = s.book ?: return
        if (s.isLocked(LockGroup.COVER) || s.coverBusy || !s.canEdit) return
        val fromFile = s.fromFile
        _state.update { it.copy(coverBusy = true, coverMessage = null) }
        viewModelScope.launch {
            try {
                var done: CoverMessage = CoverMessage.Updated
                when (pending) {
                    is PendingCover.Photo -> remote.uploadCover(bookId, photos.bytes(File(pending.path)))
                    is PendingCover.Online -> remote.coverFromUrl(bookId, pending.result.url)
                    PendingCover.FromFileCover -> when (fromFile) {
                        // Nothing from the file to fall back on (an EPUB without a cover, a PDF): the
                        // server leaves the book without a cover and says so (coverSource null).
                        FromFile.RESTORE -> if (remote.removeCustomCover(bookId) == null) done = CoverMessage.NoCoverLeft
                        FromFile.EXTRACT -> if (!remote.extractCover(bookId)) {
                            _state.update { it.copy(coverBusy = false, pendingCover = null, coverMessage = CoverMessage.NoneInFile) }
                            return@launch
                        }
                        null -> Unit
                    }
                }
                pending.discardPhoto()
                // The new cover's version (the book's updatedAt) for everything that shows it.
                val shown = runCatchingNonCancel { remote.book(bookId) } ?: before
                publish(shown, before, true)
                _state.update {
                    it.copy(
                        coverBusy = false,
                        book = shown,
                        cover = coverOf(shown),
                        pendingCover = null,
                        coverSearch = null,
                        coverMessage = done,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The choice stays up with the reason, so the user can try again (or cancel).
                val error = EditError.of(e)
                _state.update { it.copy(coverBusy = false, coverMessage = CoverMessage.Failed(error)) }
                if (e is ApiException && e.code == 409) {
                    refreshLocks()
                    // Locked meanwhile: trying again can't work until it's unlocked, so the choice
                    // (and the search it came from) goes, and the reason shows in the cover card,
                    // beside Unlock.
                    if (_state.value.isLocked(LockGroup.COVER)) {
                        _state.value.pendingCover.discardPhoto()
                        searchJob?.cancel()
                        _state.update { it.copy(pendingCover = null, coverSearch = null) }
                    }
                }
            }
        }
    }

    private fun PendingCover?.discardPhoto() {
        if (this is PendingCover.Photo) photos.discard(File(path))
    }

    override fun onCleared() {
        _state.value.pendingCover.discardPhoto()
    }

    // --- process death ----------------------------------------------------------------------------

    private fun persist() {
        val s = _state.value
        val original = s.original ?: return
        saved[KEY_ORIGINAL] = ApiJson.encodeToString(EditForm.serializer(), original)
        saved[KEY_FORM] = ApiJson.encodeToString(EditForm.serializer(), s.form)
        saved[KEY_AUTHOR_INPUT] = s.authorInput
    }

    private fun clearSaved() {
        saved.remove<String>(KEY_ORIGINAL)
        saved.remove<String>(KEY_FORM)
        saved.remove<String>(KEY_AUTHOR_INPUT)
    }

    private fun restore(key: String): EditForm? =
        saved.get<String>(key)?.let { runCatching { ApiJson.decodeFromString(EditForm.serializer(), it) }.getOrNull() }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        private const val KEY_ORIGINAL = "bookedit.original"
        private const val KEY_FORM = "bookedit.form"
        private const val KEY_AUTHOR_INPUT = "bookedit.authorInput"

        fun create(container: AppContainer, context: Context, bookId: Long, saved: SavedStateHandle): BookEditViewModel {
            val app = context.applicationContext
            container.appScope.launch(Dispatchers.IO) { runCatching { CoverImage.cleanUp(app) } }
            val publisher = BookEditPublisher(container) { runCatching { SingletonImageLoader.get(app) }.getOrNull() }
            val api = container.api
            return BookEditViewModel(
                bookId = bookId,
                remote = ApiBookEditRemote(api),
                canEdit = canEditDetails(container.auth.user.value),
                coverOf = { book -> book.coverSource?.let { runCatching { api.thumbnailUrl(book.id, book.updatedAt) }.getOrNull() } },
                publish = publisher::publish,
                photos = AndroidCoverPhotos(app),
                saved = saved,
            )
        }
    }
}
