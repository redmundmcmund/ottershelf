package io.github.ottershelf.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.AppIcons
import io.github.ottershelf.ui.theme.OttershelfTheme

@Stable
class TopBarActionsHolder {
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)
}

val LocalTopBarActions = staticCompositionLocalOf<TopBarActionsHolder?> { null }

/**
 * Puts [content] (IconButtons) in the shell's toolbar, before Search, while the calling root list
 * is shown (hidden while the search field is open). Only root lists have the shell's toolbar;
 * elsewhere this does nothing.
 */
@Composable
fun TopBarActions(content: @Composable RowScope.() -> Unit) {
    val holder = LocalTopBarActions.current ?: return
    val latest by rememberUpdatedState(content)
    DisposableEffect(holder) {
        holder.actions = { latest() }
        onDispose { holder.actions = null }
    }
}

/**
 * The frame the shell puts around a root list (activity_main.xml's toolbar and divider): the
 * toolbar with the drawer toggle, [title] and Search, which expands into a search field in place
 * of the title. [content] gets the padding to apply (toolbar, system bars).
 *
 * [backClosesSearch]: whether system Back should close an open search field (false while the
 * drawer is open, or while a pushed screen covers this one).
 */
@Composable
fun RootFrame(
    title: String,
    search: SearchBarState,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onSubmitSearch: (String) -> Unit,
    onCloseSearch: () -> Unit,
    modifier: Modifier = Modifier,
    backClosesSearch: Boolean = true,
    onScan: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val holder = remember { TopBarActionsHolder() }
    BackHandler(enabled = search.open && backClosesSearch, onBack = onCloseSearch)
    Scaffold(
        modifier = modifier,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            RootTopBar(
                title = title,
                search = search,
                onOpenDrawer = onOpenDrawer,
                onOpenSearch = onOpenSearch,
                onSubmitSearch = onSubmitSearch,
                onCloseSearch = onCloseSearch,
                actions = holder.actions,
                onScan = onScan,
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalTopBarActions provides holder) { content(padding) }
    }
}

/** The Nexus toolbar: 56dp on the shell surface, 19sp medium title, a 1dp divider under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootTopBar(
    title: String,
    search: SearchBarState,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onSubmitSearch: (String) -> Unit,
    onCloseSearch: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    // Scan a book's barcode (feature.scan), just before Search; null where it isn't offered.
    onScan: (() -> Unit)? = null,
) {
    val colors = OttershelfTheme.colors
    val bar = colors.shellSurface.compositeOver(colors.background)
    Column {
        TopAppBar(
            title = {
                if (search.open) {
                    SearchField(search, onSubmitSearch)
                } else {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                    )
                }
            },
            navigationIcon = {
                if (search.open) {
                    IconButton(onClick = onCloseSearch) {
                        Icon(AppIcons.Back, contentDescription = stringResource(R.string.nav_close_search))
                    }
                } else {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(AppIcons.Menu, contentDescription = stringResource(R.string.nav_open_drawer))
                    }
                }
            },
            actions = {
                if (search.open) {
                    if (search.text.isNotEmpty()) {
                        IconButton(onClick = { search.text = ""; search.focusRequested = true }) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.nav_clear_search))
                        }
                    }
                } else {
                    actions?.invoke(this)
                    if (onScan != null) {
                        IconButton(onClick = onScan) {
                            Icon(AppIcons.ScanBarcode, contentDescription = stringResource(R.string.scan_action))
                        }
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(AppIcons.Search, contentDescription = stringResource(R.string.nav_search))
                    }
                }
            },
            expandedHeight = 56.dp,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = bar,
                scrolledContainerColor = bar,
                navigationIconContentColor = colors.foreground,
                titleContentColor = colors.foreground,
                actionIconContentColor = colors.foreground,
            ),
        )
        HorizontalDivider(thickness = 1.dp, color = colors.border)
    }
}

/** AppCompat's SearchView in the toolbar: hint "Search", submit on the keyboard's search key. */
@Composable
private fun SearchField(search: SearchBarState, onSubmit: (String) -> Unit) {
    val colors = OttershelfTheme.colors
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, color = colors.foreground)
    BasicTextField(
        value = search.text,
        onValueChange = { search.text = it },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            if (search.text.isNotBlank()) {
                onSubmit(search.text)
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (search.text.isEmpty()) {
                    Text(stringResource(R.string.nav_search), style = style.copy(color = colors.mutedForeground), maxLines = 1)
                }
                inner()
            }
        },
    )
    LaunchedEffect(search.focusRequested) {
        if (search.focusRequested) {
            focus.requestFocus()
            keyboard?.show()
            search.focusHandled()
        }
    }
}
