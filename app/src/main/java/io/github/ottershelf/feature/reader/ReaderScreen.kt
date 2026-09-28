package io.github.ottershelf.feature.reader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.view.KeyEvent
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import io.github.ottershelf.ui.components.KeepScreenOnWhileReading
import io.github.ottershelf.BuildConfig
import io.github.ottershelf.R
import io.github.ottershelf.feature.reader.annotations.DeleteHighlightDialog
import io.github.ottershelf.feature.reader.annotations.NoteDialog
import io.github.ottershelf.feature.reader.annotations.NotesSheetActions
import io.github.ottershelf.feature.reader.annotations.NotesTab
import io.github.ottershelf.feature.reader.annotations.ReaderNotesSheet
import io.github.ottershelf.feature.reader.annotations.SelectionPopupActions
import io.github.ottershelf.feature.reader.annotations.SelectionPopupHost
import io.github.ottershelf.feature.reader.lookup.LookupSheet
import io.github.ottershelf.feature.reader.lookup.LookupViewModel
import io.github.ottershelf.feature.seriesnext.EndOfBookCard
import io.github.ottershelf.feature.timer.ReaderTimerNotice
import io.github.ottershelf.ui.components.ImmersiveSystemBars
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * The foliate reader for EPUB, KEPUB, MOBI, AZW3, AZW and FB2 (Nexus ReaderActivity,
 * activity_reader.xml): BookOrbit's foliate-js in a WebView filling the screen, with the reader's
 * bars drawn in Compose over it, hidden while reading and toggled by a tap in the middle of the
 * page. Immersive: the system bars hide with the reader's bars and come back with a swipe. The
 * table of contents and the reading settings are bottom sheets.
 */
@Composable
fun ReaderScreen(route: Route.Reader, navigator: AppNavigator) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = appViewModel {
        ReaderViewModel(it, route.bookId, route.fileId, route.title, appContext, route.cfi, route.format)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notes by viewModel.notesUi.collectAsStateWithLifecycle()
    val colors = OttershelfTheme.colors
    val appColors = ReaderAppColors(colors.background, colors.foreground, colors.primary, colors.isDark)
    LaunchedEffect(appColors) { viewModel.setAppColors(appColors) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPause() }

    var sheet by rememberSaveable { mutableStateOf<ReaderSheet?>(null) }
    // Look up (feature.reader.lookup): its own ViewModel, so its answers last the reading session.
    val lookup = appViewModel { LookupViewModel(it, route.bookId, appContext) }
    val lookupState by lookup.state.collectAsStateWithLifecycle()
    ImmersiveMode(barsVisible = state.chromeVisible || sheet != null || lookupState != null)
    KeepScreenOnWhileReading(state.activity, state.chromeVisible)

    val snackbars = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is ReaderMessage.FreshStart ->
                    snackbars.showSnackbar(resources.getString(R.string.reader_fresh_start, message.statusLabel))
                is ReaderMessage.NoteRejected ->
                    snackbars.showSnackbar(resources.getString(R.string.reader_note_rejected, message.message))
            }
        }
    }
    BackHandler(enabled = notes.popup != null) { viewModel.dismissPopup() }

    val popupActions = SelectionPopupActions(
        onColor = viewModel::highlight,
        onStyle = viewModel::setHighlightStyle,
        onNote = viewModel::openNote,
        onCopy = {
            notes.popup?.text?.let { text ->
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.reader_copy), text.trim()))
            }
            viewModel.dismissPopup()
        },
        onShare = {
            viewModel.shareQuote()?.let { text ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, resources.getString(R.string.reader_share_title))) }
            }
            viewModel.dismissPopup()
        },
        onDelete = viewModel::askDelete,
        onDismiss = viewModel::dismissPopup,
        onLookUp = {
            notes.popup?.text?.let(lookup::open)
            viewModel.dismissPopup()
        },
    )

    ReaderContent(
        state = state,
        actions = ReaderActions(
            onBack = { navigator.back() },
            onToc = { sheet = ReaderSheet.Toc },
            onSettings = { sheet = ReaderSheet.Settings },
            onSeek = viewModel::seek,
            onRetry = viewModel::retry,
            onNext = viewModel::next,
            onPrevious = viewModel::previous,
            onBookmark = viewModel::toggleBookmark,
            onNotes = { sheet = ReaderSheet.Notes },
            onFooter = viewModel::cycleFooter,
        ),
        snackbars = snackbars,
        bookmarked = notes.bookmarked,
        overlay = {
            notes.popup?.let { SelectionPopupHost(it, popupActions) }
            // The end of the book: the next book in the series (feature.seriesnext).
            EndOfBookCard(
                route.bookId, state.fraction, bookEnd = state.bookEnd, ready = !state.loading && state.error == null,
                raised = state.chromeVisible, navigator = navigator,
            )
        },
    ) { modifier ->
        key(state.pageGeneration) {
            ReaderWebView(viewModel, pageBackground(state.prefs), modifier)
        }
    }

    when (sheet) {
        ReaderSheet.Toc -> TocSheet(
            toc = state.toc,
            currentHref = state.tocHref,
            onPick = { href ->
                sheet = null
                viewModel.goTo(href)
            },
            onDismiss = { sheet = null },
        )
        ReaderSheet.Settings -> ReaderSettingsSheet(
            prefs = state.prefs,
            onChange = viewModel::updatePrefs,
            onDismiss = { sheet = null },
            fixedLayout = state.fixedLayout,
        )
        ReaderSheet.Notes -> ReaderNotesSheet(
            state = notes,
            initialTab = NotesTab.Highlights,
            actions = NotesSheetActions(
                onJump = { cfi ->
                    sheet = null
                    viewModel.goToCfi(cfi)
                },
                onDeleteBookmark = viewModel::deleteBookmark,
                onSearch = viewModel::search,
                onClearSearch = viewModel::clearSearch,
            ),
            onDismiss = { sheet = null },
        )
        null -> {}
    }
    notes.noteDialog?.let { NoteDialog(it, onSave = viewModel::saveNote, onDismiss = viewModel::dismissNote) }
    notes.confirmDelete?.let { DeleteHighlightDialog(it, onConfirm = viewModel::confirmDelete, onDismiss = viewModel::cancelDelete) }
    lookupState?.let { LookupSheet(it, lookup) }
    state.choice?.let { PositionChoiceDialog(it, onChoose = viewModel::choose) }
    // A reading timer on for this book would count the same time twice (feature.timer).
    ReaderTimerNotice(route.bookId)
}

enum class ReaderSheet { Toc, Settings, Notes }


/**
 * Hides the status and navigation bars while [barsVisible] is false (a swipe from an edge shows
 * them for a moment), and shows them again when the reader closes, unless another reader replaced
 * it (ui.components.ImmersiveSystemBars).
 */
@Composable
private fun ImmersiveMode(barsVisible: Boolean) = ImmersiveSystemBars(barsVisible)

/**
 * The WebView: the reader page served from the APK at the WebViewAssetLoader origin, the page's
 * `/api/v1` requests answered by [ReaderViewModel.requests], and `window.Android` bound to a
 * ViewModel bridge of its own. It lives as long as it is on screen: the ViewModel outlives it and
 * reopens the book in a new one.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun ReaderWebView(viewModel: ReaderViewModel, background: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    var web by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            val assets = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            SelectionWebView(context).apply {
                // AndroidView gives its view wrap-content layout params, and a wrap-content WebView
                // sizes its viewport to the content: the page's height: 100% then resolves to 0 and
                // the book renders into an empty box. The Nexus layout XML had match_parent.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(background.toArgb())
                settings.javaScriptEnabled = true
                // The page keeps nothing itself (the app has the settings and positions), so no
                // storage a book's script could keep things in from one book to the next.
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.textZoom = 100
                settings.setSupportZoom(false)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = true
                isFocusableInTouchMode = true
                // Volume keys turn pages; the key-ups are consumed too so there's no volume beep.
                setOnKeyListener { _, keyCode, event ->
                    when (keyCode) {
                        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) viewModel.next() else viewModel.previous()
                            }
                            true
                        }
                        else -> false
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (BuildConfig.DEBUG || message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            Log.println(
                                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.ERROR else Log.INFO,
                                "ReaderConsole", "${message.message()} (${message.sourceId()}:${message.lineNumber()})",
                            )
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        viewModel.requests.intercept(request) ?: assets.shouldInterceptRequest(request.url)

                    // Only keep the top-level page from navigating away. This is also called for
                    // subframes, and foliate loads every chapter into an iframe from a blob: URL;
                    // cancelling those leaves the paginator waiting forever for the iframe's load event.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                        request.isForMainFrame

                    // A crashed or killed renderer would otherwise take the app down with it. A kill
                    // (the system freeing memory) reopens by itself; a crash offers Retry.
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        if (web === view) viewModel.onPageGone(crashed = detail.didCrash())
                        return true
                    }
                }
                val page = WebViewPage(this)
                tag = page
                addJavascriptInterface(viewModel.attach(page), "Android")
                loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/reader/index.html")
                requestFocus()
                web = this
            }
        },
        update = { it.setBackgroundColor(background.toArgb()) },
        onRelease = { view ->
            (view.tag as? ReaderPage)?.let(viewModel::detach)
            if (web === view) web = null
            // The ViewModel already has the newest location (the page pushes it after every
            // settle, and on pagehide): nothing to wait for.
            view.onPause()
            view.stopLoading()
            view.removeJavascriptInterface("Android")
            view.destroy()
        },
    )
    // Pause the page with the screen (it pushes its location when hidden) and resume it after.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val view = web ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private class WebViewPage(private val web: WebView) : ReaderPage {
    override fun js(code: String) = web.evaluateJavascript(code, null)
    override fun evaluate(code: String, onResult: (String?) -> Unit) = web.evaluateJavascript(code) { onResult(it) }
}

/** The page colour behind the book (and the WebView until it has drawn), so there's no white flash. */
@Composable
internal fun pageBackground(prefs: ReaderPrefs): androidx.compose.ui.graphics.Color = pageColors(prefs).background

/** The book page's colours for [prefs] (the app theme's for App, the user's for Custom). */
@Composable
internal fun pageColors(prefs: ReaderPrefs): PageColors {
    val colors = OttershelfTheme.colors
    return ReaderStyles.pageColors(prefs, ReaderAppColors(colors.background, colors.foreground, colors.primary, colors.isDark))
}

/**
 * The reader's WebView: text selection keeps its handles, but the system's floating menu (Copy,
 * Share, Select all...) is emptied, so it never shows; the reader's own popup (reader.js reports
 * the settled selection) takes its place. Internal for the device tests, which host the reader page in it.
 */
internal class SelectionWebView(context: Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(callback?.let(::SilentActionMode), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(callback?.let(::SilentActionMode))
}

/** Passes everything to the WebView's own callback, then empties the menu it filled. */
private class SilentActionMode(private val inner: ActionMode.Callback) : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        inner.onCreateActionMode(mode, menu)
        menu.clear()
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        inner.onPrepareActionMode(mode, menu)
        menu.clear()
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = inner.onActionItemClicked(mode, item)

    override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
    }
}
