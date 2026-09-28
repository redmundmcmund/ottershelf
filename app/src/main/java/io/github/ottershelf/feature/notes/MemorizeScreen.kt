package io.github.ottershelf.feature.notes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.R
import io.github.ottershelf.core.settings.AppSettingsRepository
import io.github.ottershelf.core.settings.NotesPrefs
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.NoteFilter
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme

@Immutable
data class MemorizeUiState(
    val liked: Boolean = false,
    val bookId: Long? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val current: Annotation? = null,
    /** How many there are to go through, once known. */
    val count: Int? = null,
    /** Reviewed on this visit. */
    val session: Int = 0,
    /** Reviews so far per id (the app settings key). */
    val reviews: Map<Long, Int> = emptyMap(),
    val likedIds: Set<Long> = emptySet(),
    val empty: Boolean = false,
)

/**
 * Memorize: one highlight at a time, at random without repeats ([RandomNotes]); tapping moves on and
 * counts a review of the one shown (`AppSettings.notes.reviews`). The next is fetched ahead.
 * Reviews are counted here and written to the settings key together, every [REVIEW_BATCH] and on
 * leaving, rather than one whole-key save per card.
 */
class MemorizeViewModel(
    private val repo: NotesRepository,
    private val settings: AppSettingsRepository?,
    private val coverOf: (Long) -> Any?,
    bookId: Long?,
    liked: Boolean,
) : ViewModel() {

    constructor(container: AppContainer, bookId: Long?, liked: Boolean) : this(
        repo = NotesRepository(ApiNotesRemote(container.api), container.appSettings, container.session.accountKey()),
        settings = container.appSettings,
        coverOf = { id -> runCatching { container.api.unversionedThumbnailUrl(id) }.getOrNull() },
        bookId = bookId,
        liked = liked,
    )

    private val source = RandomNotes(repo, NoteFilter(bookId = bookId, liked = liked)) { settings?.settings?.value?.notes?.liked.orEmpty() }
    private var ahead: Deferred<Annotation?>? = null

    /** Reviews on this visit not yet in the settings key (id -> how many). */
    private val pending = mutableMapOf<Long, Int>()
    private var stored: Map<Long, Int> = emptyMap()

    private val _state = MutableStateFlow(MemorizeUiState(liked = liked, bookId = bookId))
    val state: StateFlow<MemorizeUiState> = _state.asStateFlow()

    private val _messages = Channel<NotesMessage>(Channel.BUFFERED)
    val messages: Flow<NotesMessage> = _messages.receiveAsFlow()

    init {
        settings?.let { repository ->
            viewModelScope.launch {
                repository.settings.collect { s ->
                    stored = s.notes.reviews
                    _state.update { it.copy(reviews = shownReviews(), likedIds = s.notes.liked.keys) }
                }
            }
        }
        advance(countCurrent = false)
    }

    fun cover(bookId: Long): Any? = coverOf(bookId)

    /** The next one (a review of the one shown). */
    fun next() = advance(countCurrent = true)

    fun retry() {
        ahead?.cancel()
        ahead = null
        advance(countCurrent = false)
    }

    fun toggleLike(note: Annotation) {
        if (!repo.toggleLike(note)) _messages.trySend(NotesMessage.LikesFull)
    }

    private fun shownReviews(): Map<Long, Int> =
        if (pending.isEmpty()) stored else stored + pending.mapValues { (id, n) -> (stored[id] ?: 0) + n }

    private fun saveReviews() {
        if (pending.isEmpty()) return
        val counts = pending.toMap()
        pending.clear()
        repo.addReviews(counts)
    }

    private fun advance(countCurrent: Boolean) {
        if (countCurrent && _state.value.loading) return // one tap at a time
        val shown = _state.value.current
        // After a failed fetch the tap tries again; the card still shown isn't counted twice.
        if (countCurrent && shown != null && _state.value.error == null) {
            pending[shown.id] = (pending[shown.id] ?: 0) + 1
            if (pending.values.sum() >= REVIEW_BATCH) saveReviews()
            _state.update { it.copy(session = it.session + 1, reviews = shownReviews()) }
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // The fetch is a plain call, so its failure lands in the catch below. (A failed child
                // `async` would cancel this coroutine and reach the thread, closing the app.) A
                // prefetch that failed or found nothing is fetched again here.
                val prefetched = ahead
                ahead = null
                val note = prefetched?.await() ?: source.next()
                val count = source.count ?: 0
                if (note == null && count > 0) {
                    // There are highlights but none could be picked (deleted meanwhile): not "none yet".
                    _state.update { it.copy(loading = false, current = null, count = count, error = "") }
                    return@launch
                }
                _state.update { it.copy(loading = false, current = note, count = count, empty = note == null) }
                if (note != null && count > 1) ahead = prefetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** The next one, fetched ahead. In the ViewModel's scope (a supervisor), so a failure stays in it. */
    private fun prefetch(): Deferred<Annotation?> = viewModelScope.async {
        try {
            source.next()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        // Written from the app scope by the settings repository, so it reaches the server after this.
        saveReviews()
    }

    private companion object {
        const val REVIEW_BATCH = 10
    }
}

/** Memorize (pushed from Notes or a book's Highlights). */
@Composable
fun MemorizeScreen(route: Route.Memorize, navigator: AppNavigator) {
    val viewModel = appViewModel { MemorizeViewModel(it, route.bookId, route.liked) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sharing by remember { mutableStateOf<Annotation?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            if (message == NotesMessage.LikesFull) {
                snackbar.showSnackbar(resources.getString(R.string.notes_likes_full, NotesPrefs.MAX_ENTRIES), duration = SnackbarDuration.Long)
            }
        }
    }
    MemorizeContent(
        state = state,
        snackbar = snackbar,
        coverOf = viewModel::cover,
        onBack = { navigator.back() },
        onNext = viewModel::next,
        onRetry = viewModel::retry,
        onLike = viewModel::toggleLike,
        onShare = { sharing = it },
        onOpenReader = { note -> readerRoute(note)?.let(navigator::navigate) },
        onOpenBook = { navigator.navigate(Route.BookDetail(it.bookId)) },
    )
    sharing?.let { note -> QuoteCardDialog(note, note.bookTitle, note.author, viewModel.cover(note.bookId), onDismiss = { sharing = null }) }
}

/**
 * The note large in a DashCard (quote mark in its colour, the text in the serif, the user's note, the book,
 * how often reviewed), tap anywhere on it for the next; under it the visit's count and the actions.
 */
@Composable
internal fun MemorizeContent(
    state: MemorizeUiState,
    coverOf: (Long) -> Any?,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
    onNext: () -> Unit = {},
    onRetry: () -> Unit = {},
    onLike: (Annotation) -> Unit = {},
    onShare: (Annotation) -> Unit = {},
    onOpenReader: (Annotation) -> Unit = {},
    onOpenBook: (Annotation) -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    Scaffold(
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.notes_memorize),
                subtitle = when {
                    state.liked -> stringResource(R.string.notes_memorize_liked)
                    state.bookId != null -> state.current?.bookTitle
                    else -> stringResource(R.string.notes_memorize_all)
                },
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val outer = Modifier.fillMaxSize().padding(padding)
        val current = state.current
        when {
            state.error != null && current == null ->
                ErrorState(onRetry, outer, message = stringResource(R.string.notes_load_failed), detail = state.error.takeIf { it.isNotBlank() })
            state.empty -> EmptyState(
                stringResource(if (state.liked) R.string.notes_empty_liked else R.string.notes_empty),
                outer,
                icon = if (state.liked) "Heart" else "Highlighter",
            )
            current == null -> LoadingState(outer)
            else -> Column(outer.padding(horizontal = 12.dp, vertical = 12.dp)) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = { (slideInHorizontally { it / 6 } + fadeIn()) togetherWith fadeOut() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentKey = { it.id },
                    label = "memorize",
                ) { note ->
                    MemorizeCard(note, coverOf(note.bookId), state.reviews[note.id] ?: 0, onNext, onOpenBook)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(
                            stringResource(R.string.notes_memorize_tap),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.foreground,
                        )
                        Text(
                            pluralStringResource(R.plurals.notes_memorize_session, state.session, state.session) +
                                (state.count?.let { "  ·  " + pluralStringResource(R.plurals.notes_highlights_count, it, it) } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.mutedForeground,
                        )
                    }
                    CardIcon("Image", stringResource(R.string.notes_share_image), { onShare(current) })
                    if (canOpenInReader(current)) CardIcon("BookOpen", stringResource(R.string.notes_open_reader), { onOpenReader(current) })
                    LikeIcon(current.id in state.likedIds) { onLike(current) }
                }
            }
        }
    }
}

@Composable
private fun MemorizeCard(note: Annotation, cover: Any?, reviews: Int, onNext: () -> Unit, onOpenBook: (Annotation) -> Unit) {
    val colors = OttershelfTheme.colors
    DashCard(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onNext),
        contentPadding = PaddingValues(22.dp),
    ) {
        LucideIcon("Quote", contentDescription = null, tint = highlightColor(note.color), size = 30.dp)
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(note.text, style = quoteStyle(21), color = colors.foreground)
                if (note.hasNote) NoteLine(note.note.orEmpty(), Modifier.padding(top = 16.dp))
            }
        }
        HorizontalDivider(color = colors.border)
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onOpenBook(note) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(cover, note.bookTitle, Modifier.width(34.dp), authors = note.author, seed = note.bookId.toString())
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    note.bookTitle ?: stringResource(R.string.notes_untitled),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(note.author, note.chapterTitle?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            if (reviews == 0) stringResource(R.string.notes_memorize_new) else pluralStringResource(R.plurals.notes_memorize_reviewed, reviews, reviews),
            style = MaterialTheme.typography.labelSmall,
            color = colors.mutedForeground,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(16.dp),
        )
    }
}
