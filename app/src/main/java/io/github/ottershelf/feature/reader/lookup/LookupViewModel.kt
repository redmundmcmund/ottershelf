package io.github.ottershelf.feature.reader.lookup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import io.github.ottershelf.AppContainer

/**
 * The reader's Look up sheet (selection popup > Look up): the selection's dictionary entry and
 * Wikipedia summary, and the installed apps that take text. Scoped to the reader's navigation
 * entry, so its answers ([LookupRepository]) are remembered for the reading session.
 *
 * Nothing goes to Wikimedia until the user taps Look up, and then only the selected text (and, for the
 * book's language, one `books/:id` to the user's own server when the book isn't downloaded). The
 * requests use [LookupHttp.client], never the account's.
 */
class LookupViewModel internal constructor(
    private val repository: LookupRepository,
    /** The book's metadata language as stored (may be null); throws when it can't be read now. */
    private val bookLanguage: suspend () -> String?,
    private val installedApps: suspend () -> List<TextApp>,
    private val online: StateFlow<Boolean>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where the lookups run: Wikimedia's answers (large pages for common words) are parsed there, off the main thread. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
    /** A monotonic clock in ms: how long ago the book's language couldn't be read. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : ViewModel() {

    constructor(container: AppContainer, bookId: Long, appContext: Context) : this(
        repository = LookupRepository(OkHttpWikimediaRemote()),
        bookLanguage = { readBookLanguage(container, bookId) },
        installedApps = { TextApps.query(appContext) },
        online = container.online,
    )

    private val _state = MutableStateFlow<LookupUiState?>(null)

    /** The open sheet, or null. */
    val state: StateFlow<LookupUiState?> = _state.asStateFlow()

    private var language: String? = null
    /** The read of the book's language under way: both parts of an opening wait for this one. */
    private var languageRead: Deferred<String>? = null
    /** When the book's language last couldn't be read ([now]); for a while after, English without asking. */
    private var languageFailedAt: Long? = null
    private var apps: List<TextApp>? = null
    private var appsJob: Job? = null
    private var dictionaryJob: Job? = null
    private var wikipediaJob: Job? = null

    /** Each opening; an answer for an earlier one is dropped. */
    private var generation = 0

    init {
        // Back online with a part that failed offline: try it again by itself.
        viewModelScope.launch {
            online.drop(1).filter { it }.collect {
                // A language read that failed offline is worth asking again now.
                languageFailedAt = null
                val s = _state.value ?: return@collect
                if (s.dictionary.isOfflineFailure() || s.wikipedia.isOfflineFailure()) retry()
            }
        }
    }

    /** Opens the sheet for [selection] (the popup's text). */
    fun open(selection: String) {
        val text = LookupText.display(selection)
        val term = LookupText.clean(text)
        if (term.isEmpty()) return
        val words = LookupText.wordCount(text)
        val dictionary = words <= LookupText.MAX_DICTIONARY_WORDS
        val wikipedia = words <= LookupText.MAX_WIKIPEDIA_WORDS
        generation++
        dictionaryJob?.cancel()
        wikipediaJob?.cancel()
        _state.value = LookupUiState(
            text = text,
            term = term,
            showDictionary = dictionary,
            tab = if (dictionary) LookupTab.Dictionary else LookupTab.Wikipedia,
            dictionary = if (dictionary) LookupPart.Loading else LookupPart.Skipped,
            wikipedia = if (wikipedia) LookupPart.Loading else LookupPart.Skipped,
            apps = apps.orEmpty(),
            wikipediaLang = language?.let(LookupText::wikipediaLanguage) ?: "en",
        )
        load(dictionary = dictionary, wikipedia = wikipedia)
        loadApps()
    }

    fun selectTab(tab: LookupTab) = _state.update { s ->
        if (s == null || (tab == LookupTab.Dictionary && !s.showDictionary)) s else s.copy(tab = tab)
    }

    /** Asks again for what failed. */
    fun retry() {
        val s = _state.value ?: return
        load(dictionary = s.dictionary is LookupPart.Failed, wikipedia = s.wikipedia is LookupPart.Failed)
    }

    fun close() {
        generation++
        dictionaryJob?.cancel()
        wikipediaJob?.cancel()
        _state.value = null
    }

    private fun load(dictionary: Boolean, wikipedia: Boolean) {
        val s = _state.value ?: return
        if (!dictionary && !wikipedia) return
        val gen = generation
        val bookLang = language()
        if (dictionary) {
            dictionaryJob?.cancel()
            update(gen) { it.copy(dictionary = LookupPart.Loading) }
            dictionaryJob = viewModelScope.launch {
                val lang = bookLang.await()
                val part = fetch { withContext(work) { repository.dictionary(s.term, lang) } }
                update(gen) { it.copy(dictionary = part) }
            }
        }
        if (wikipedia) {
            wikipediaJob?.cancel()
            update(gen) { it.copy(wikipedia = LookupPart.Loading) }
            wikipediaJob = viewModelScope.launch {
                val lang = bookLang.await()
                update(gen) { it.copy(wikipediaLang = LookupText.wikipediaLanguage(lang)) }
                val part = fetch { withContext(work) { repository.wikipedia(s.term, lang) } }
                update(gen) { it.copy(wikipedia = part) }
            }
        }
    }

    private suspend fun <T> fetch(block: suspend () -> T?): LookupPart<T> = try {
        block()?.let { LookupPart.Ready(it) } ?: LookupPart.NotFound
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LookupPart.Failed(offline = !online.value)
    }

    private inline fun update(gen: Int, crossinline change: (LookupUiState) -> LookupUiState) {
        if (gen != generation) return
        _state.update { it?.let(change) }
    }

    /**
     * The book's language code, read once per session: the downloaded book page, else the server.
     * One read serves both parts of an opening (and an opening made while it runs). Unknown for now
     * (offline, not downloaded, no answer in time) is English, asked again at a later lookup: after
     * [LANGUAGE_RETRY_MS], or once the connection comes back.
     */
    private fun language(): Deferred<String> {
        language?.let { return CompletableDeferred(it) }
        languageRead?.let { if (it.isActive) return it }
        languageFailedAt?.let { if (now() - it < LANGUAGE_RETRY_MS) return CompletableDeferred("en") }
        return viewModelScope.async {
            try {
                LookupText.language(bookLanguage()).also {
                    language = it
                    languageFailedAt = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                languageFailedAt = now()
                "en"
            }
        }.also { languageRead = it }
    }

    private fun loadApps() {
        if (apps != null || appsJob?.isActive == true) return
        appsJob = viewModelScope.launch {
            val found = try {
                withContext(io) { installedApps() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            apps = found
            _state.update { it?.copy(apps = found) }
        }
    }

    private fun LookupPart<*>.isOfflineFailure() = this is LookupPart.Failed && offline

    internal companion object {
        /** A book language that couldn't be read isn't asked for again within this (unless back online). */
        const val LANGUAGE_RETRY_MS = 60_000L

        private suspend fun readBookLanguage(container: AppContainer, bookId: Long): String? {
            val downloads = container.downloads
            val local = withContext(Dispatchers.IO) { runCatching { downloads.detail(bookId) }.getOrNull() }
            if (local != null) return local.language
            val book = withTimeoutOrNull(5_000) { container.api.book(bookId) } ?: throw IOException("No answer from the server")
            return book.language
        }
    }
}
