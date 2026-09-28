package io.github.ottershelf.feature.requests

import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookRequestItem
import io.github.ottershelf.core.model.RequestStatus
import io.github.ottershelf.core.util.IsoTime
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.AppIcons
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.text.DateFormat
import java.util.Date

/** What the Requests screen's controls do (all no-ops by default, for screenshot tests). */
class RequestsActions(
    val onBack: () -> Unit = {},
    val onTab: (RequestsTab) -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onAuthor: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onDestination: (Long?) -> Unit = {},
    /** Request or join it, or open the library's copy. */
    val onWorkAction: (RequestWork) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onShowDismissed: (Boolean) -> Unit = {},
    val onOpenDetail: (Long?) -> Unit = {},
    val onOpenBook: (Long) -> Unit = {},
    val onCancel: (BookRequestItem) -> Unit = {},
    val onConfirmCancel: (Boolean) -> Unit = {},
    val onDismiss: (BookRequestItem) -> Unit = {},
    val onLeave: (BookRequestItem) -> Unit = {},
)

/**
 * Book requests (Nexus RequestsActivity, activity_requests.xml), a full screen pushed from the
 * drawer (shown there only with `book_request_access`): the toolbar and two tabs, "Request a book"
 * (the server's metadata search) and "My requests" (followed live by polling). Tapping a request
 * opens its detail (the web's request drawer) as a bottom sheet. [prefill]: opened for one book
 * (Route.RequestBook, a scanned ISBN), its search already run; the user still taps Request themselves.
 */
@Composable
fun RequestsScreen(navigator: AppNavigator, prefill: RequestPrefill? = null) {
    val viewModel = appViewModel { RequestsViewModel(it, createSavedStateHandle(), prefill) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.setResumed(true)
        onPauseOrDispose { viewModel.setResumed(false) }
    }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it.text(resources), duration = SnackbarDuration.Long) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val actions = remember(viewModel, navigator) {
        RequestsActions(
            onBack = { navigator.back() },
            onTab = { keyboard?.hide(); viewModel.selectTab(it) },
            onTitle = viewModel::setTitle,
            onAuthor = viewModel::setAuthor,
            onSearch = { keyboard?.hide(); viewModel.search() },
            onDestination = viewModel::selectDestination,
            onWorkAction = { work ->
                val owned = work.availability?.ownedBookId
                if (owned != null) {
                    viewModel.opening(owned)
                    navigator.navigate(Route.BookDetail(owned))
                } else {
                    viewModel.submit(work)
                }
            },
            onRefresh = viewModel::refresh,
            onRetry = viewModel::loadRequests,
            onShowDismissed = viewModel::setShowDismissed,
            onOpenDetail = viewModel::openDetail,
            onOpenBook = { id ->
                viewModel.opening(id)
                viewModel.openDetail(null)
                navigator.navigate(Route.BookDetail(id))
            },
            onCancel = viewModel::askCancel,
            onConfirmCancel = viewModel::confirmCancel,
            onDismiss = viewModel::dismiss,
            onLeave = viewModel::leave,
        )
    }
    RequestsContent(state, actions, snackbar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsContent(
    state: RequestsUiState,
    actions: RequestsActions = RequestsActions(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = {
            Column {
                // The Nexus toolbar: plain, on the surface, Back and the title (no scroll tint).
                TopAppBar(
                    title = { Text(stringResource(R.string.requests_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
                            Icon(AppIcons.Back, contentDescription = stringResource(R.string.nav_back))
                        }
                    },
                    // The Nexus actionBarSize, as the shell's toolbar.
                    expandedHeight = 56.dp,
                )
                if (state.allowed) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        TabItem(stringResource(R.string.requests_tab_search), state.tab == RequestsTab.Search, Modifier.weight(1f)) {
                            actions.onTab(RequestsTab.Search)
                        }
                        TabItem(stringResource(R.string.requests_tab_mine), state.tab == RequestsTab.Mine, Modifier.weight(1f)) {
                            actions.onTab(RequestsTab.Mine)
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val bottom = padding.calculateBottomPadding()
        val direction = LocalLayoutDirection.current
        // The sides too: in landscape the camera cutout or the 3-button navigation bar is there.
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    start = padding.calculateStartPadding(direction),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction),
                ),
        ) {
            when {
                !state.allowed -> EmptyState(
                    stringResource(R.string.requests_forbidden),
                    Modifier.fillMaxSize(),
                    icon = "Lock",
                )
                state.tab == RequestsTab.Search -> SearchPanel(state, actions, bottom)
                else -> MinePanel(state, actions, bottom)
            }
        }
    }

    state.detail?.let { RequestDetailSheet(it, state, actions) }
    state.confirmCancel?.let { CancelDialog(it, actions) }
}

/** A tab (the Nexus `Tab` style): dim text, the foreground and a 2dp accent underline when selected. */
@Composable
private fun TabItem(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Box(
        modifier
            .fillMaxHeight()
            .clickable(role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (selected) {
                    val line = 2.dp.toPx()
                    drawRect(colors.primary, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = if (selected) colors.foreground else colors.mutedForeground,
            maxLines = 1,
        )
    }
}

// --- Request a book ------------------------------------------------------------------------------

@Composable
private fun SearchPanel(state: RequestsUiState, actions: RequestsActions, bottom: androidx.compose.ui.unit.Dp) {
    val colors = OttershelfTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(state.title, actions.onTitle, stringResource(R.string.requests_field_title), actions.onSearch, Modifier.weight(3f), KeyboardCapitalization.Words)
            Spacer(Modifier.width(8.dp))
            SearchField(state.author, actions.onAuthor, stringResource(R.string.requests_field_author), actions.onSearch, Modifier.weight(2f), KeyboardCapitalization.Words)
            Spacer(Modifier.width(8.dp))
            AccentButton(
                text = stringResource(R.string.requests_search),
                onClick = actions.onSearch,
                enabled = !state.searching,
                modifier = Modifier.padding(top = 8.dp).height(44.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.requests_go_to), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = colors.mutedForeground)
            Spacer(Modifier.width(8.dp))
            DestinationPicker(state, actions.onDestination, Modifier.weight(1f))
        }
        Text(
            searchStatusText(state.searchStatus),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.mutedForeground,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = bottom + 8.dp)) {
            items(state.works, key = { it.key }) { work -> ResultRow(work, state.providerLabels, actions.onWorkAction) }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    onSearch: () -> Unit,
    modifier: Modifier,
    capitalization: KeyboardCapitalization,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.padding(top = 8.dp),
        placeholder = { Text(hint, maxLines = 1) },
        singleLine = true,
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

/** The Nexus Spinner: the chosen destination and a chevron; the choices drop down under it. */
@Composable
private fun DestinationPicker(state: RequestsUiState, onPick: (Long?) -> Unit, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    var open by remember { mutableStateOf(false) }
    val selected = state.destinations.firstOrNull { it.libraryId == state.destinationId } ?: state.destinations.first()
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                .clickable { open = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                destinationName(selected, state.serverDefaultName),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LucideIcon("ChevronDown", contentDescription = null, tint = colors.mutedForeground, size = 16.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.destinations.forEach { option ->
                DropdownMenuItem(
                    text = { Text(destinationName(option, state.serverDefaultName)) },
                    onClick = {
                        open = false
                        onPick(option.libraryId)
                    },
                )
            }
        }
    }
}

@Composable
private fun destinationName(option: DestinationOption, serverDefault: String?): String = when {
    option.libraryId != null -> option.name.orEmpty()
    serverDefault != null -> stringResource(R.string.requests_server_default_named, serverDefault)
    else -> stringResource(R.string.requests_server_default)
}

@Composable
private fun searchStatusText(status: SearchStatus): String = when (status) {
    SearchStatus.Hint -> stringResource(R.string.requests_hint)
    SearchStatus.NeedInput -> stringResource(R.string.requests_need_input)
    is SearchStatus.Searching ->
        if (status.found == 0) stringResource(R.string.requests_searching)
        else stringResource(R.string.requests_searching_found, status.found)
    is SearchStatus.Found ->
        if (status.count == 0) stringResource(R.string.requests_no_matches)
        else pluralStringResource(R.plurals.requests_found, status.count, status.count)
    is SearchStatus.Failed -> stringResource(R.string.requests_search_failed, status.message.orEmpty())
}

/** A search result (item_request_result.xml): cover, title, authors · year · series, providers, state, action. */
@Composable
private fun ResultRow(work: RequestWork, providerLabels: Map<String, String>, onAction: (RequestWork) -> Unit) {
    val colors = OttershelfTheme.colors
    val best = work.best
    val title = listOfNotNull(best.shownTitle, best.subtitle?.takeIf { it.isNotBlank() }).joinToString(": ")
    val providers = work.members.map { providerLabels[it.provider] ?: it.provider }.distinct().joinToString(", ")
    val metaLine = listOfNotNull(
        best.authors?.takeIf { it.isNotEmpty() }?.joinToString(", "),
        work.year?.toString(),
        best.seriesName?.let { s -> best.seriesIndex?.let { "$s #${it.toInt()}" } ?: s },
    ).joinToString(" · ")
    val meta = listOf(metaLine, providers).filter { it.isNotEmpty() }.joinToString("\n")

    val a = work.availability
    val requested = work.requested
    val existing = a?.existingRequestStatus ?: "pending"
    val (stateText, stateColor) = when {
        work.busy -> null to Color.Unspecified
        requested != null -> stringResource(R.string.requests_state_requested, statusLabel(requested)) to statusColor(requested)
        a?.ownedBookId != null -> stringResource(R.string.requests_state_owned) to statusColor("available")
        a?.existingRequestId != null && a.alreadySubscribed ->
            stringResource(R.string.requests_state_already, statusLabel(existing)) to statusColor(existing)
        a?.existingRequestId != null -> stringResource(R.string.requests_state_someone) to statusColor(existing)
        else -> null to Color.Unspecified
    }
    val (actionText, enabled) = when {
        work.busy -> "…" to false
        requested != null -> stringResource(R.string.requests_action_requested) to false
        a?.ownedBookId != null -> stringResource(R.string.requests_action_open) to true
        a?.existingRequestId != null && a.alreadySubscribed -> stringResource(R.string.requests_action_requested) to false
        a?.existingRequestId != null -> stringResource(R.string.requests_action_join) to true
        else -> stringResource(R.string.requests_action_request) to true
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(
            model = work.cover,
            title = best.shownTitle,
            authors = best.authors?.joinToString(", "),
            seed = work.key,
            modifier = Modifier.size(64.dp, 96.dp),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (stateText != null) {
                Spacer(Modifier.height(4.dp))
                Text(stateText, style = MaterialTheme.typography.bodySmall, color = stateColor)
            }
        }
        Spacer(Modifier.width(12.dp))
        AccentButton(
            text = actionText,
            onClick = { onAction(work) },
            enabled = enabled,
            modifier = Modifier.height(40.dp).widthIn(min = 96.dp),
        )
    }
}

// --- My requests ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinePanel(state: RequestsUiState, actions: RequestsActions, bottom: androidx.compose.ui.unit.Dp) {
    val colors = OttershelfTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .padding(start = 12.dp)
                .toggleable(value = state.showDismissed, onValueChange = actions.onShowDismissed, role = Role.Checkbox)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.showDismissed, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            Text(stringResource(R.string.requests_show_dismissed), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = colors.mutedForeground)
        }
        PullToRefreshBox(
            isRefreshing = state.requestsRefreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 8.dp)) {
                items(state.requests, key = { it.id }) { item -> RequestRow(item, state.meId, actions) }
            }
            if (state.requests.isEmpty()) {
                val error = state.requestsError
                when {
                    error is RequestsError.Forbidden -> EmptyState(
                        stringResource(R.string.requests_forbidden),
                        Modifier.align(Alignment.Center),
                        icon = "Lock",
                    )
                    error is RequestsError.Failed -> ErrorState(
                        onRetry = actions.onRetry,
                        modifier = Modifier.align(Alignment.Center),
                        message = stringResource(R.string.requests_mine_failed),
                        detail = error.message,
                    )
                    state.requestsLoaded -> EmptyState(
                        stringResource(R.string.requests_mine_empty),
                        Modifier.align(Alignment.Center),
                        icon = "BookPlus",
                    )
                }
            }
        }
    }
}

/** One of my requests (item_request.xml): cover, title, meta, status, progress, reason, actions. */
@Composable
private fun RequestRow(r: BookRequestItem, meId: Long?, actions: RequestsActions) {
    val colors = OttershelfTheme.colors
    val d = r.download
    val transferring = d != null && (d.status == "downloading" || d.status == "queued")
    val percent = d?.progressPercent?.toInt()
    var status = statusLabel(r.status)
    if (transferring && percent != null) status = stringResource(R.string.requests_status_progress, status, percent)
    if (r.dismissed) status = stringResource(R.string.requests_status_dismissed, status)
    val showProgress = d != null && d.status == "downloading" && d.progressPercent != null
    val reason = r.statusReason ?: d?.errorMessage ?: r.decisionNote

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { actions.onOpenDetail(r.id) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        BookCover(
            model = r.coverUrl,
            title = r.title,
            authors = r.authors.joinToString(", "),
            seed = r.title,
            modifier = Modifier.size(64.dp, 96.dp),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge, color = colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = requestMeta(r)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = statusColor(r.status))
            if (showProgress) {
                Spacer(Modifier.height(6.dp))
                PillProgressBar(((d?.progressPercent ?: 0.0) / 100.0).toFloat(), height = 4.dp, color = statusColor(r.status))
            }
            if (!reason.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(reason, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
            }
            RequestActions(r, meId, actions, Modifier.padding(top = 4.dp))
        }
    }
}

private fun requestMeta(r: BookRequestItem): String = listOfNotNull(
    r.authors.takeIf { it.isNotEmpty() }?.joinToString(", "),
    r.publishedYear?.toString(),
    r.targetLibraryName,
).joinToString(" · ")

/** The row's text actions (the Nexus `TextAction`: accent caps, 36dp). */
@Composable
private fun RequestActions(r: BookRequestItem, meId: Long?, actions: RequestsActions, modifier: Modifier = Modifier) {
    val mine = meId == r.userId
    val subscribed = !mine && r.subscribers.any { it.userId == meId }
    val open = r.status == "available" && r.matchedBookId != null
    val cancel = mine && RequestStatus.isCancellable(r.status)
    val dismiss = RequestStatus.isSettled(r.status)
    val leave = subscribed && RequestStatus.isActive(r.status)
    if (!open && !cancel && !dismiss && !leave) return
    Row(modifier) {
        if (open) TextAction(stringResource(R.string.requests_open_book)) { r.matchedBookId?.let(actions.onOpenBook) }
        if (cancel) TextAction(stringResource(R.string.requests_cancel)) { actions.onCancel(r) }
        if (dismiss) TextAction(stringResource(if (r.dismissed) R.string.requests_restore else R.string.requests_dismiss)) { actions.onDismiss(r) }
        if (leave) TextAction(stringResource(R.string.requests_leave)) { actions.onLeave(r) }
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = OttershelfTheme.colors.primary,
        )
    }
}

@Composable
private fun CancelDialog(item: BookRequestItem, actions: RequestsActions) {
    val downloading = item.download?.status == "downloading" || item.download?.status == "queued"
    AlertDialog(
        onDismissRequest = { actions.onConfirmCancel(false) },
        title = { Text(stringResource(R.string.requests_cancel_title)) },
        text = {
            Text(
                if (downloading) stringResource(R.string.requests_cancel_message_download)
                else stringResource(R.string.requests_cancel_message, item.title),
            )
        },
        confirmButton = {
            TextButton(onClick = { actions.onConfirmCancel(true) }) {
                Text(stringResource(R.string.requests_cancel_confirm), color = OttershelfTheme.colors.destructive)
            }
        },
        dismissButton = {
            TextButton(onClick = { actions.onConfirmCancel(false) }) { Text(stringResource(R.string.requests_cancel_keep)) }
        },
    )
}

// --- Request detail (the web's request drawer) ---------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RequestDetailSheet(r: BookRequestItem, state: RequestsUiState, actions: RequestsActions) {
    val colors = OttershelfTheme.colors
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = { actions.onOpenDetail(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Row {
                BookCover(
                    model = r.coverUrl,
                    title = r.title,
                    authors = r.authors.joinToString(", "),
                    seed = r.title,
                    modifier = Modifier.size(96.dp, 144.dp),
                )
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(r.title, style = MaterialTheme.typography.headlineSmall, color = colors.foreground)
                    r.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground)
                    }
                    if (r.authors.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(r.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = colors.foreground)
                    }
                    val meta = listOfNotNull(
                        r.publishedYear?.toString(),
                        r.targetLibraryName?.let { stringResource(R.string.requests_detail_library, it) },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
                    }
                    Spacer(Modifier.height(10.dp))
                    StatusChip(presentationStatus(r))
                }
            }

            val pipeline = pipelineState(r)
            if (pipeline.tone != Tone.Done && pipeline.tone != Tone.Stopped) {
                Spacer(Modifier.height(20.dp))
                Pipeline(pipeline)
            }

            val d = r.download
            if (d != null && (d.status == "downloading" || d.status == "queued") && d.progressPercent != null) {
                Spacer(Modifier.height(16.dp))
                PillProgressBar((d.progressPercent / 100.0).toFloat(), color = colors.info)
                Spacer(Modifier.height(6.dp))
                val bytes = if (d.downloadedBytes != null && d.totalBytes != null && d.totalBytes > 0) {
                    stringResource(
                        R.string.requests_detail_bytes,
                        Formatter.formatShortFileSize(context, d.downloadedBytes),
                        Formatter.formatShortFileSize(context, d.totalBytes),
                    )
                } else null
                Text(
                    listOfNotNull(stringResource(R.string.components_percent, d.progressPercent.toInt()), bytes).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                )
            }

            val reason = r.statusReason ?: d?.errorMessage
            if (!reason.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                val alarming = r.status == "failed" || r.status == "needs_review"
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (alarming) colors.destructive else colors.mutedForeground,
                )
            }
            r.decisionNote?.takeIf { it.isNotBlank() }?.let { DetailNote(stringResource(R.string.requests_detail_decision), it) }
            r.note?.takeIf { it.isNotBlank() }?.let { DetailNote(stringResource(R.string.requests_detail_note), it) }

            Spacer(Modifier.height(16.dp))
            val info = buildList {
                IsoTime.parse(r.createdAt)?.let {
                    add(stringResource(R.string.requests_detail_requested_on, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))))
                }
                if (r.userId != state.meId) r.requesterUsername?.let { add(stringResource(R.string.requests_detail_requested_by, it)) }
                val others = r.subscribers.count { it.userId != r.userId && it.userId != state.meId }
                if (others > 0) add(pluralStringResource(R.plurals.requests_detail_subscribers, others, others))
            }
            info.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground) }

            val mine = state.meId == r.userId
            val subscribed = !mine && r.subscribers.any { it.userId == state.meId }
            val open = r.status == "available" && r.matchedBookId != null
            val cancel = mine && RequestStatus.isCancellable(r.status)
            val dismiss = RequestStatus.isSettled(r.status)
            val leave = subscribed && RequestStatus.isActive(r.status)
            if (open || cancel || dismiss || leave) {
                Spacer(Modifier.height(20.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (open) AccentButton(stringResource(R.string.requests_open_book), { r.matchedBookId?.let(actions.onOpenBook) }, icon = "BookOpen")
                    if (dismiss) SecondaryButton(stringResource(if (r.dismissed) R.string.requests_restore else R.string.requests_dismiss), { actions.onDismiss(r) })
                    if (leave) SecondaryButton(stringResource(R.string.requests_leave), { actions.onLeave(r) })
                    if (cancel) SecondaryButton(stringResource(R.string.requests_cancel_confirm), { actions.onCancel(r) })
                }
            }
        }
    }
}

@Composable
private fun DetailNote(label: String, text: String) {
    val colors = OttershelfTheme.colors
    Spacer(Modifier.height(16.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground)
    Spacer(Modifier.height(2.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.foreground)
}

/** The web's RequestStatusBadge: the label on a tinted pill edged in its tone, with a mark. */
@Composable
private fun StatusChip(status: String) {
    val colors = OttershelfTheme.colors
    val tone = toneOf(status)
    val color = tone.color()
    val shape = CircleShape
    Row(
        Modifier
            .height(22.dp)
            .clip(shape)
            .background(if (tone == Tone.Stopped) colors.muted else color.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, if (tone == Tone.Stopped) colors.border else color.copy(alpha = 0.40f)), shape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (tone) {
            Tone.Done -> LucideIcon("Check", contentDescription = null, tint = color, size = 12.dp)
            Tone.Failed -> LucideIcon("TriangleAlert", contentDescription = null, tint = color, size = 12.dp)
            else -> Box(Modifier.size(6.dp).background(color, CircleShape))
        }
        Text(statusLabel(status), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/**
 * The web's RequestPipeline on a phone: five steps (asked, approved, release found, downloading,
 * filed) as nodes on a line in the request's tone, and the step it's on named underneath.
 */
@Composable
private fun Pipeline(state: PipelineState) {
    val colors = OttershelfTheme.colors
    val tone = state.tone.color()
    val labels = listOf(
        R.string.requests_step_asked,
        R.string.requests_step_approved,
        R.string.requests_step_release,
        R.string.requests_step_downloading,
        R.string.requests_step_filed,
    )
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            labels.indices.forEach { i ->
                val step = when {
                    state.tone == Tone.Done || i < state.currentIndex -> StepState.Done
                    i == state.currentIndex -> StepState.Current
                    else -> StepState.Upcoming
                }
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (step == StepState.Done) tone else colors.card)
                        .border(2.dp, if (step == StepState.Upcoming) colors.border else tone, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    when (step) {
                        StepState.Done -> LucideIcon("Check", contentDescription = null, tint = colors.card, size = 9.dp)
                        StepState.Current -> Box(Modifier.size(6.dp).background(tone, CircleShape))
                        StepState.Upcoming -> Unit
                    }
                }
                if (i < labels.lastIndex) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(if (step == StepState.Done) tone else colors.border),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(labels[state.currentIndex.coerceIn(0, labels.lastIndex)]),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = tone,
        )
    }
}

private enum class StepState { Done, Current, Upcoming }

// --- Status tones and labels ---------------------------------------------------------------------

internal enum class Tone { Waiting, Progress, Done, Failed, Stopped }

internal data class PipelineState(val currentIndex: Int, val tone: Tone)

/** The web's STATUS_TONES (RequestStatusBadge.vue). */
internal fun toneOf(status: String): Tone = when (status) {
    "pending", "needs_review" -> Tone.Waiting
    "approved", "searching", "grabbed", "downloading", "importing" -> Tone.Progress
    "available" -> Tone.Done
    "rejected", "failed" -> Tone.Failed
    else -> Tone.Stopped
}

private val TERMINAL = setOf("available", "needs_review", "failed", "cancelled", "rejected")

/** The web's requestPresentationStatus: the status to show while the row catches up with its download. */
internal fun presentationStatus(r: BookRequestItem): String {
    if (r.status in TERMINAL) return r.status
    return when (r.download?.status) {
        "downloading" -> "downloading"
        "completed", "importing", "imported" -> "importing"
        "needs_review" -> "needs_review"
        "failed" -> "failed"
        else -> r.status
    }
}

/** The web's requestPipelineState (requestPipeline.ts). */
internal fun pipelineState(r: BookRequestItem): PipelineState = when (val status = presentationStatus(r)) {
    "failed" -> PipelineState(if (r.download != null) 3 else 2, Tone.Failed)
    "cancelled" -> PipelineState(if (r.download != null) 3 else 1, Tone.Stopped)
    "pending" -> PipelineState(1, Tone.Waiting)
    "approved", "searching" -> PipelineState(2, Tone.Progress)
    "grabbed", "downloading" -> PipelineState(3, Tone.Progress)
    "importing" -> PipelineState(4, Tone.Progress)
    "needs_review" -> PipelineState(4, Tone.Waiting)
    "available" -> PipelineState(4, Tone.Done)
    "rejected" -> PipelineState(1, Tone.Stopped)
    else -> PipelineState(0, Tone.Stopped)
}

@Composable
private fun Tone.color(): Color {
    val colors = OttershelfTheme.colors
    return when (this) {
        Tone.Waiting -> colors.warning
        Tone.Progress -> colors.info
        Tone.Done -> colors.success
        Tone.Failed -> colors.destructive
        Tone.Stopped -> colors.mutedForeground
    }
}

@Composable
private fun statusColor(status: String): Color = toneOf(status).color()

@Composable
private fun statusLabel(status: String): String = when (status) {
    "pending" -> stringResource(R.string.requests_status_pending)
    "approved" -> stringResource(R.string.requests_status_approved)
    "searching" -> stringResource(R.string.requests_status_searching)
    "grabbed" -> stringResource(R.string.requests_status_grabbed)
    "downloading" -> stringResource(R.string.requests_status_downloading)
    "importing" -> stringResource(R.string.requests_status_importing)
    "needs_review" -> stringResource(R.string.requests_status_needs_review)
    "available" -> stringResource(R.string.requests_status_available)
    "rejected" -> stringResource(R.string.requests_status_rejected)
    "failed" -> stringResource(R.string.requests_status_failed)
    "cancelled" -> stringResource(R.string.requests_status_cancelled)
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun RequestsMessage.text(res: Resources): String = when (this) {
    is RequestsMessage.Requested ->
        if (library != null) res.getString(R.string.requests_msg_requested_library, title, library)
        else res.getString(R.string.requests_msg_requested, title)
    is RequestsMessage.Joined ->
        if (library != null) res.getString(R.string.requests_msg_joined_library, library)
        else res.getString(R.string.requests_msg_joined)
    RequestsMessage.LibraryForbidden -> res.getString(R.string.requests_msg_library_forbidden)
    is RequestsMessage.RequestFailed -> res.getString(R.string.requests_msg_request_failed, message.orEmpty())
    is RequestsMessage.ActionFailed -> res.getString(
        when (action) {
            RequestAction.Cancel -> R.string.requests_msg_cancel_failed
            RequestAction.Dismiss -> R.string.requests_msg_dismiss_failed
            RequestAction.Restore -> R.string.requests_msg_restore_failed
            RequestAction.Leave -> R.string.requests_msg_leave_failed
        },
        title,
        message.orEmpty(),
    )
}
