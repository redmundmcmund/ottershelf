package io.github.ottershelf.devicetest

import android.annotation.SuppressLint
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.BuildConfig
import io.github.ottershelf.core.download.LocalEpub
import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.session.TokenCipher
import io.github.ottershelf.core.sync.ReadingVisit
import io.github.ottershelf.feature.reader.LocalBook
import io.github.ottershelf.feature.reader.Opened
import io.github.ottershelf.feature.reader.PageSettings
import io.github.ottershelf.feature.reader.ReaderAppColors
import io.github.ottershelf.feature.reader.ReaderContent
import io.github.ottershelf.feature.reader.ReaderPrefs
import io.github.ottershelf.feature.reader.ReaderRequests
import io.github.ottershelf.feature.reader.ReaderStyles
import io.github.ottershelf.feature.reader.ReaderUiState
import io.github.ottershelf.feature.reader.ReadingSigns
import io.github.ottershelf.feature.reader.Relocate
import io.github.ottershelf.feature.reader.SelectionWebView
import io.github.ottershelf.feature.reader.pageBackground
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `window.Android` for the reader page in the device tests: the same ten methods as
 * [ReaderViewModel.Bridge] (reader.js calls exactly these), recording what the page reports.
 *
 * "The user has moved since opening" is followed exactly as [ReaderViewModel] does it: the relocates go
 * through [ReadingSigns] to a [ReadingVisit] (the first after the book opens and the user's own are
 * activity), and a relocate with [Relocate.turned] marks the move ([ReadingVisit.moved]; until it
 * is true the reader saves no position and sends no session).
 */
class ReaderProbe {
    data class Call(val name: String, val payload: String?, val at: Long)

    val calls = CopyOnWriteArrayList<Call>()
    val relocates = CopyOnWriteArrayList<Relocate>()
    val locations = CopyOnWriteArrayList<Relocate>()
    val consoleErrors = CopyOnWriteArrayList<String>()
    val sessions = CopyOnWriteArrayList<ReadingSession>()

    @Volatile var opened: Opened? = null
    @Volatile var error: String? = null
    @Volatile var ready = 0

    /** What onReady does (the host opens the book, as ReaderViewModel.onPageReady does). */
    @Volatile var onReadyAction: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())

    /** Main thread only, as in the ViewModel. */
    val visit = ReadingVisit(clock = System::currentTimeMillis, send = { sessions += it })
    private val signs = ReadingSigns(visit)

    /** [ReadingVisit.moved], read on the main thread. */
    val moved: Boolean get() = Device.onMain { visit.moved }

    private fun record(name: String, payload: String?) {
        calls += Call(name, payload, SystemClock.uptimeMillis())
    }

    @JavascriptInterface
    fun onReady() {
        record("onReady", null)
        ready++
        main.post {
            signs.expect() // ReaderViewModel.openAt: the relocate the opening brings is the user's
            onReadyAction()
        }
    }

    @JavascriptInterface
    fun onOpened(payload: String) {
        record("onOpened", payload)
        opened = runCatching { ApiJson.decodeFromString(Opened.serializer(), payload) }.getOrNull()
    }

    @JavascriptInterface
    fun onSelection(payload: String) = record("onSelection", payload)

    @JavascriptInterface
    fun onSelectionCleared() = record("onSelectionCleared", null)

    @JavascriptInterface
    fun onAnnotationTap(payload: String) = record("onAnnotationTap", payload)

    @JavascriptInterface
    fun onSearchResults(payload: String) = record("onSearchResults", payload)

    @JavascriptInterface
    fun onRelocate(payload: String) {
        record("onRelocate", payload)
        val r = runCatching { ApiJson.decodeFromString(Relocate.serializer(), payload) }.getOrNull() ?: return
        relocates += r
        // ReaderViewModel.onRelocated: activity when it's the user's reading, the user's move when the user turned.
        main.post { signs.onRelocate(r) }
    }

    @JavascriptInterface
    fun onLocation(payload: String) {
        record("onLocation", payload)
        runCatching { ApiJson.decodeFromString(Relocate.serializer(), payload) }.getOrNull()?.let { locations += it }
    }

    @JavascriptInterface
    fun onToggleUi() = record("onToggleUi", null)

    @JavascriptInterface
    fun onError(message: String) {
        record("onError", message)
        error = message
    }

    fun turned(): List<Relocate> = relocates.filter { it.turned }

    fun summary(): String = "relocates=${relocates.size} (turned ${turned().size}), locations=${locations.size}, moved=$moved, " +
        "last=${relocates.lastOrNull()?.let { "fraction ${it.fraction} page ${it.page}/${it.pages} time ${it.timeSection}/${it.timeTotal}/${it.timeBook}" }}, " +
        "error=$error, console=${consoleErrors.take(5)}"
}

/**
 * A [Session] for the reader's [ReaderRequests], which needs an [Api] for the server fallback: its
 * preferences file is a separate one (`device-tests-session`), never read with credentials, and
 * nothing is ever stored in it. The fixture book is always answered from the device, so the Api is
 * never used; were it asked, it has no server and no token.
 */
private class IsolatedPrefsContext(base: android.content.Context) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("device-tests-$name", mode)
}

private object NoCipher : TokenCipher {
    override fun encrypt(plain: String): String = error("the device tests never store a token")
    override fun decrypt(stored: String): String? = null
}

/** An [Api] that can't reach anything: no server URL, no token, separate (empty) preferences. */
internal fun offlineApi(): Api {
    val session = Session(IsolatedPrefsContext(Device.context), NoCipher)
    check(session.serverUrl == null && session.refreshToken == null) { "the isolated session isn't empty" }
    return Api(session, "device-test") {}
}

/**
 * The foliate reader page hosted the way [ReaderScreen] hosts it: the app's [SelectionWebView]
 * with ReaderWebView's settings, the page from the APK's assets at the WebViewAssetLoader origin,
 * its `/api/v1` requests answered by the app's [ReaderRequests] (here from a downloaded copy on the
 * device), `window.Android` bound, inside the reader's own [ReaderContent] on the book's page colour.
 */
class ReaderSession private constructor(
    val host: LockScreenHost,
    val probe: ReaderProbe,
    private val webRef: AtomicReference<WebView?>,
    private val state: androidx.compose.runtime.MutableState<ReaderUiState>,
    private val colors: AtomicReference<ReaderAppColors?>,
    private val epub: LocalEpub,
) : AutoCloseable {

    val web: WebView get() = webRef.get() ?: error("no WebView")

    /** Runs [code] in the page and returns its JSON-encoded result (WebView.evaluateJavascript). */
    fun js(code: String, timeoutMs: Long = 10_000): String? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<String?>()
        Device.onMain { web.evaluateJavascript(code) { out.set(it); latch.countDown() } }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "JS didn't answer: ${code.take(80)}" }
        return out.get()
    }

    /** Evaluates [body] (a function body returning a JSON-able object) and hands back the object. */
    fun probe(body: String): JsonObject {
        val raw = js("JSON.stringify((() => { $body })())") ?: error("no answer")
        val text = ApiJson.decodeFromString(String.serializer(), raw)
        return ApiJson.parseToJsonElement(text).jsonObject
    }

    fun awaitOpened(timeoutMs: Long = 30_000) {
        Device.waitUntil("the book to open (${probe.summary()})", timeoutMs) { probe.opened != null || probe.error != null }
        check(probe.error == null) { "reader.js reported: ${probe.error}" }
        Device.onMain { state.value = state.value.copy(loading = false, fixedLayout = probe.opened?.fixedLayout == true) }
    }

    /** Waits until nothing new has come from the page for [quietMs]. */
    fun awaitQuiet(quietMs: Long = 1_500, timeoutMs: Long = 15_000) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val last = probe.calls.lastOrNull()?.at ?: 0L
            if (SystemClock.uptimeMillis() - last >= quietMs) return
            SystemClock.sleep(100)
        }
        throw AssertionError("the page never settled: ${probe.summary()}")
    }

    /** Applies [prefs] as ReaderViewModel.updatePrefs does (`readerSettings` with what changes the page). */
    fun settings(prefs: ReaderPrefs) {
        Device.onMain { state.value = state.value.copy(prefs = prefs) }
        val json = settingsJson(prefs, colors.get())
        js("if (window.readerSettings) { readerSettings($json) }")
    }

    /** The WebView's rectangle on the screen, in px. */
    fun webOnScreen(): Rect = Device.onMain {
        val xy = IntArray(2)
        web.getLocationOnScreen(xy)
        Rect(xy[0], xy[1], xy[0] + web.width, xy[1] + web.height)
    }

    override fun close() {
        host.close()
        runCatching { epub.close() }
    }

    companion object {
        const val BOOK_ID = 990_001L
        const val FILE_ID = 990_002L

        internal fun settingsJson(prefs: ReaderPrefs, colors: ReaderAppColors?): JsonObject =
            ApiJson.encodeToJsonElement(PageSettings.serializer(), ReaderStyles.pageSettings(prefs, colors)).jsonObject

        /** Opens the fixture book in [bookDir] with [prefs], at [cfi] (else [percentage]), as ReaderViewModel.openAt does. */
        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        fun open(bookDir: File, prefs: ReaderPrefs, cfi: String? = null, percentage: Double = 0.0): ReaderSession {
            val epub = LocalEpub(bookDir)
            check(epub.matchesInfo()) { "the fixture's info doesn't match its zip" }
            val local = CompletableDeferred<LocalBook?>(LocalBook.Epub(epub))
            val requests = ReaderRequests(BOOK_ID, FILE_ID, local, offlineApi(), wholeFile = false)
            val probe = ReaderProbe()
            val webRef = AtomicReference<WebView?>(null)
            val colors = AtomicReference<ReaderAppColors?>(null)
            val state = mutableStateOf(ReaderUiState(title = FixtureEpub.TITLE, prefs = prefs))
            probe.onReadyAction = {
                val args = buildJsonObject {
                    put("bookId", BOOK_ID)
                    put("fileId", FILE_ID)
                    put("format", JsonNull)
                    put("cfi", cfi?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("percentage", percentage)
                    put("settings", settingsJson(state.value.prefs, colors.get()))
                }
                webRef.get()?.evaluateJavascript("readerOpen($args)", null)
            }
            val host = LockScreenHost.launch()
            try {
                host.setContent {
                    OttershelfTheme {
                        val c = OttershelfTheme.colors
                        colors.set(ReaderAppColors(c.background, c.foreground, c.primary, c.isDark))
                        val ui by state
                        ReaderContent(state = ui) { modifier ->
                            ReaderPageView(requests, probe, pageBackground(ui.prefs).toArgb(), webRef, modifier)
                        }
                    }
                }
            } catch (e: Throwable) {
                host.close()
                epub.close()
                throw e
            }
            return ReaderSession(host, probe, webRef, state, colors, epub)
        }
    }
}

/** ReaderScreen's ReaderWebView, with the test's bridge in place of the ViewModel's. */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun ReaderPageView(
    requests: ReaderRequests,
    probe: ReaderProbe,
    background: Int,
    webRef: AtomicReference<WebView?>,
    modifier: androidx.compose.ui.Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            val assets = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            SelectionWebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(background)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.textZoom = 100
                settings.setSupportZoom(false)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = true
                isFocusableInTouchMode = true
                addJavascriptInterface(probe, "Android")
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        val line = "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                        Log.println(if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.ERROR else Log.INFO, "ReaderConsole", line)
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) probe.consoleErrors += line
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        requests.intercept(request) ?: assets.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        probe.error = "renderer gone (crashed ${detail.didCrash()})"
                        return true
                    }
                }
                loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/reader/index.html")
                requestFocus()
                webRef.set(this)
            }
        },
        update = { it.setBackgroundColor(background) },
        onRelease = { view ->
            if (webRef.get() === view) webRef.set(null)
            view.onPause()
            view.stopLoading()
            view.removeJavascriptInterface("Android")
            view.destroy()
        },
    )
}

