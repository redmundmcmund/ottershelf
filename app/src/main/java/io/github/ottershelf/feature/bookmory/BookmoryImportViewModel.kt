package io.github.ottershelf.feature.bookmory

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

enum class ImportStep { INTRO, READING, MATCHING, FAILED, PREVIEW, RUNNING, DONE }

enum class ImportFailure { NOT_BOOKMORY, UNREADABLE, TOO_LARGE, EMPTY, SERVER }

data class BookmoryUiState(
    val step: ImportStep = ImportStep.INTRO,
    val failure: ImportFailure? = null,
    /** The server's or the network's message for a [ImportFailure.SERVER]. */
    val failureDetail: String? = null,
    val preview: ImportPreview? = null,
    val libraries: List<UploadLibrary> = emptyList(),
    val canUpload: Boolean = false,
    val choices: ImportChoices = ImportChoices(),
    val done: Int = 0,
    val total: Int = 0,
    val summary: ImportSummary? = null,
)

/**
 * Import from Bookmory: the picked file is read and matched with the user's books, the preview
 * follows the user's choices, and Import runs [BookmoryImporter] in this ViewModel (one import at a
 * time in the app: [BookmoryImportGuard]). Leaving the screen stops it; importing the same file
 * again finishes the rest. The library picked for placeholder books is remembered in the account's
 * app settings (`AppSettings.bookmoryLibraryId`).
 */
class BookmoryImportViewModel(private val container: AppContainer) : ViewModel() {

    private val remote: BookmoryRemote = ApiBookmoryRemote(container.api, container.tracking, { container.auth.user.value }, container.libraryRoles)
    private val importer = BookmoryImporter(remote)

    private val _state = MutableStateFlow(BookmoryUiState())
    val state: StateFlow<BookmoryUiState> = _state.asStateFlow()

    private var file: BookmoryFile? = null
    private var analysis: ImportAnalysis? = null
    private var job: Job? = null

    /** The file the user picked with the system's picker. */
    fun open(uri: Uri, resolver: ContentResolver) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(step = ImportStep.READING, failure = null, summary = null) }
            val parsed = withContext(Dispatchers.IO) {
                try {
                    val bytes = resolver.openInputStream(uri)?.use { input -> readLimited(input) } ?: return@withContext ImportFailure.UNREADABLE
                    BookmoryCsv.parse(bytes.toString(Charsets.UTF_8))
                } catch (_: TooLarge) {
                    ImportFailure.TOO_LARGE
                } catch (_: NotABookmoryExport) {
                    ImportFailure.NOT_BOOKMORY
                } catch (_: IOException) {
                    ImportFailure.UNREADABLE
                } catch (_: SecurityException) {
                    ImportFailure.UNREADABLE
                }
            }
            when (parsed) {
                is ImportFailure -> fail(parsed)
                is BookmoryFile -> if (parsed.rows.isEmpty()) fail(ImportFailure.EMPTY) else {
                    file = parsed
                    match()
                }
            }
        }
    }

    /** Reads the user's books again and works the preview out (after a failure, or after an import). */
    fun again() {
        if (job?.isActive == true) return
        if (file == null) {
            _state.update { BookmoryUiState() }
            return
        }
        job = viewModelScope.launch { match() }
    }

    private suspend fun match() {
        val file = file ?: return
        _state.update { it.copy(step = ImportStep.MATCHING, failure = null) }
        try {
            val result = analyse(file, remote, importer)
            analysis = result
            val remembered = container.appSettings.settings.value.bookmoryLibraryId
            _state.update { s ->
                val library = s.choices.libraryId?.takeIf { id -> result.libraries.any { it.id == id } }
                    ?: remembered?.takeIf { id -> result.libraries.any { it.id == id } }
                    ?: result.libraries.singleOrNull()?.id
                val choices = s.choices.copy(libraryId = library)
                s.copy(
                    step = ImportStep.PREVIEW,
                    libraries = result.libraries,
                    canUpload = result.canUpload,
                    choices = choices,
                    preview = ImportPreviews.preview(result, choices),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            fail(ImportFailure.SERVER, e.message.takeIf { e is ApiException })
        }
    }

    private fun fail(failure: ImportFailure, detail: String? = null) =
        _state.update { it.copy(step = ImportStep.FAILED, failure = failure, failureDetail = detail) }

    fun pickLibrary(id: Long) = choose { it.copy(libraryId = id) }

    /** [keep] false: that day's backlog readings go in without dates. */
    fun keepDates(day: LocalDate, keep: Boolean) = choose { if (keep) it.copy(undated = it.undated - day) else it.copy(undated = it.undated + day) }

    /** null: back to the row's default (its match, or a placeholder). */
    fun chooseRow(line: Int, choice: RowChoice?) = choose {
        it.copy(rows = if (choice == null) it.rows - line else it.rows + (line to choice))
    }

    private fun choose(change: (ImportChoices) -> ImportChoices) {
        val analysis = analysis ?: return
        if (_state.value.step != ImportStep.PREVIEW) return
        _state.update { s ->
            val choices = change(s.choices)
            s.copy(choices = choices, preview = ImportPreviews.preview(analysis, choices))
        }
    }

    fun import() {
        val s = _state.value
        val preview = s.preview ?: return
        if (s.step != ImportStep.PREVIEW || preview.nothingNew || job?.isActive == true) return
        if (!BookmoryImportGuard.tryStart()) return
        s.choices.libraryId?.takeIf { id -> preview.items.any { (it.target as? ImportTarget.Stub)?.libraryId == id } }?.let { id ->
            container.appSettings.update { it.copy(bookmoryLibraryId = id) }
        }
        job = viewModelScope.launch {
            try {
                _state.update { it.copy(step = ImportStep.RUNNING, done = 0, total = preview.items.size) }
                val summary = importer.run(preview.items) { done, total -> _state.update { it.copy(done = done, total = total) } }
                _state.update { it.copy(step = ImportStep.DONE, summary = summary) }
            } finally {
                BookmoryImportGuard.finish()
            }
        }
    }

    /** Stops a running import (Back, confirmed): what was imported stays. */
    fun stop() {
        job?.cancel()
        _state.update { it.copy(step = ImportStep.PREVIEW) }
        again()
    }

    private class TooLarge : IOException()

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_FILE_BYTES) throw TooLarge()
        }
        return out.toByteArray()
    }

    companion object {
        /** A real export of a few hundred books is under 100 KB. */
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }
}
