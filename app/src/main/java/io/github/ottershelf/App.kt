package io.github.ottershelf

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.ottershelf.core.download.DownloadNotifications
import io.github.ottershelf.core.download.Downloads
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.NativeCredentials
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.Connectivity
import io.github.ottershelf.core.readerprefs.ApiReaderPrefsRemote
import io.github.ottershelf.core.readerprefs.ReaderPrefsRemote
import io.github.ottershelf.core.readerprefs.ReaderSettingsSpec
import io.github.ottershelf.core.readerprefs.ReaderSettingsStore
import io.github.ottershelf.core.session.AuthState
import io.github.ottershelf.core.session.KeystoreTokenCipher
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.session.TokenCipher
import io.github.ottershelf.core.settings.AppSettingsRepository
import io.github.ottershelf.core.settings.settingsDataStore
import io.github.ottershelf.core.sync.PageReaderProgress
import io.github.ottershelf.core.sync.ProgressStore
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.core.sync.SyncCoordinator
import io.github.ottershelf.core.sync.SyncScheduler
import io.github.ottershelf.core.theme.ThemePrefs
import io.github.ottershelf.core.theme.ThemeRepository
import io.github.ottershelf.core.tracking.ApiTrackingRemote
import io.github.ottershelf.core.tracking.TimerEngine
import io.github.ottershelf.core.tracking.TimerNotifications
import io.github.ottershelf.core.tracking.TrackingQueue
import io.github.ottershelf.core.tracking.TrackingRemote
import io.github.ottershelf.core.tracking.TrackingRepository
import io.github.ottershelf.core.tracking.TrackingScheduler
import io.github.ottershelf.feature.comics.ComicImages
import io.github.ottershelf.feature.pdf.PdfFiles
import io.github.ottershelf.feature.reader.annotations.ReaderNotesScheduler
import io.github.ottershelf.ui.icons.Lucide
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File

class OttershelfApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }

    // WorkManager starts at its first use (WorkManager.getInstance, or one of its own services and
    // receivers), with its defaults: the manifest removes its androidx.startup initializer, which
    // built it (its database, schedulers) on the main thread before onCreate in every process.
    // LegacyWorkers runs work that was queued under the workers' names before the package rename.
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().setWorkerFactory(LegacyWorkers).build()

    // Covers: Coil 3 defaults (ARGB_8888, hardware bitmaps, a memory cache of 20% of the app's memory
    // class) with crossfade, over the Api's authenticated client with a queue of their own (see
    // coverFetcher). A large disk cache that never revalidates (Coil 3's default CacheStrategy
    // ignores cache headers, the old respectCacheHeaders(false)): thumbnail URLs are versioned
    // (`?t=`) and immutable.
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(coverFetcher { container.api.client }) }
        .crossfade(true)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("covers").toOkioPath())
                .maxSizeBytes(128L * 1024 * 1024)
                .build()
        }
        .build()
}

/**
 * How the cover loader downloads: over [client] (the Api's, asked once), so thumbnails get the
 * bearer header (newBuilder keeps its interceptor, authenticator and connections), but on a queue
 * of its own. A screenful of covers then never waits ahead of the API calls (a grid's next page,
 * opening a book), which keep the Api client's queue to themselves. The same cover asked for twice
 * at once (a book on two shelves) is downloaded once.
 */
@OptIn(ExperimentalCoilApi::class)
internal fun coverFetcher(client: () -> OkHttpClient) = OkHttpNetworkFetcherFactory(
    callFactory = {
        client().newBuilder().dispatcher(Dispatcher().apply { maxRequestsPerHost = COVER_REQUESTS_PER_HOST }).build()
    },
    concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
)

/**
 * Covers downloading at once, beside the Api client's own 5 per host: over HTTP/1.1 (a connection
 * each) up to 9 connections to the server in all, where their one shared queue allowed 5; over
 * HTTP/2 one connection, multiplexed.
 */
private const val COVER_REQUESTS_PER_HOST = 4

/** The app's [AppContainer], from any Context (workers, activities, services). */
val Context.appContainer: AppContainer get() = (applicationContext as OttershelfApp).container

/**
 * Manual dependency injection: every app-wide object, created once (lazily) and shared. Screens
 * get it through their ViewModel factory (`ui.nav.appViewModel`), workers through
 * [Context.appContainer]. Nothing here is UI.
 *
 * Shared file: new app-wide objects are added here by the data-layer owner; features don't edit it.
 *
 * [tokenCipher] encrypts the session's tokens: the Keystore's; tests pass a stand-in (Robolectric
 * has no Keystore).
 */
class AppContainer(private val app: Application, private val tokenCipher: TokenCipher = KeystoreTokenCipher()) {

    /** Outlives screens: final progress/session saves must finish after the reader closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val session: Session by lazy { Session(app, tokenCipher) }

    val auth: AuthState by lazy { AuthState(session) }

    val api: Api by lazy { Api(session, deviceLabel()) { onRefreshRejected() } }

    val connectivity: Connectivity by lazy { Connectivity(app) }

    /** Whether the device has a network (not whether the server answers). */
    val online: StateFlow<Boolean> get() = connectivity.online

    val readingChanges: ReadingChanges by lazy { ReadingChanges() }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(app) }

    val progress: ProgressStore by lazy {
        ProgressStore(
            context = app,
            accountKey = session::accountKey,
            api = api,
            onSent = readingChanges::changed,
            onQueued = syncScheduler::enqueueFlush,
            stillSignedInAs = ::stillSignedInAs,
        )
    }

    val downloads: Downloads by lazy { Downloads(app, session::accountKey, api, progress, appScope) }

    val sync: SyncCoordinator by lazy {
        SyncCoordinator(
            signedIn = { session.isSignedIn },
            progress = progress,
            connectivity = connectivity,
            scheduler = syncScheduler,
            scope = appScope,
            downloadedFiles = { downloads.list().map { it.bookId to it.fileId } },
            inForeground = { startedActivities > 0 },
        )
    }

    /** Activities between onStart and onStop (main thread): above 0, the app is on screen. */
    private var startedActivities = 0

    /** What only the screens need, done when the first activity is created, not in a worker's process. */
    private var uiStarted = false

    /** Device settings (see core.settings.SettingsStore for how features add keys). */
    val settings: DataStore<Preferences> get() = app.settingsDataStore

    // --- readers (ARCHITECTURE.md, "Readers") ----------------------------------------------------

    /**
     * Position and session bookkeeping for a page-based reader (comics, PDF) of [fileId], with the
     * EPUB reader's queue, conflict and offline rules. One per open reader: create it in the reader's
     * ViewModel with its `viewModelScope`, and `close()` it from `onCleared`.
     */
    fun pageReaderProgress(bookId: Long, fileId: Long, uiScope: CoroutineScope): PageReaderProgress =
        PageReaderProgress(
            store = progress,
            bookId = bookId,
            fileId = fileId,
            remote = object : PageReaderProgress.Remote {
                override suspend fun fileProgress(fileId: Long) = api.fileProgress(fileId)
                override suspend fun readStatus(bookId: Long) = api.book(bookId).readStatus
            },
            online = { online.value },
            uiScope = uiScope,
            sendScope = appScope,
            onSessionQueued = { sync.sync(force = true, baselines = false) },
        )

    private val readerPrefsRemote: ReaderPrefsRemote by lazy { ApiReaderPrefsRemote(api) }

    /**
     * A reader's settings for [fileId] (`ReaderSettingsSpecs.Cbx` or `.Pdf`): on the device, and on
     * the account in its reader sync mode (`reader/defaults`, `reader/preferences/:fileId`).
     */
    fun <T> readerSettings(spec: ReaderSettingsSpec<T>, fileId: Long): ReaderSettingsStore<T> =
        ReaderSettingsStore(
            spec = spec,
            fileId = fileId,
            store = settings,
            remote = readerPrefsRemote,
            accountKey = session::accountKey,
            syncEnabled = { signedInUser.value?.settings?.syncReaderPreferences == true },
            scope = appScope,
        )

    /** The signed-in user, or null while signed out (AuthState keeps the last user after sign-out). */
    private val signedInUser: StateFlow<AuthUser?> by lazy {
        val initial = auth.user.value.takeIf { auth.status.value is AuthState.Status.SignedIn }
        combine(auth.status, auth.user) { status, user -> user.takeIf { status is AuthState.Status.SignedIn } }
            .stateIn(appScope, SharingStarted.Eagerly, initial)
    }

    /** Appearance settings: on the device, and on the account in sync mode (see ThemeRepository). */
    val theme: ThemeRepository by lazy {
        ThemeRepository(
            store = settings,
            remote = api,
            account = signedInUser,
            updateUser = auth::setUser,
            scope = appScope,
        )
    }

    /** What OttershelfTheme draws with; changes as the Appearance screen or the server changes it. */
    val themePrefs: StateFlow<ThemePrefs> get() = theme.prefs

    // --- reading tracker (core.tracking, core.settings.AppSettings; ARCHITECTURE.md "Tracking") --

    val trackingScheduler: TrackingScheduler by lazy { TrackingScheduler(app) }

    private val trackingRemote: TrackingRemote by lazy { ApiTrackingRemote(api) }

    /** The app's own users.settings key (`bookorbitAndroid`): page totals, daily goal, timer prefs. */
    val appSettings: AppSettingsRepository by lazy {
        AppSettingsRepository(
            store = settings,
            remote = trackingRemote,
            account = signedInUser,
            accountKey = session::accountKey,
            updateUser = auth::setUser,
            scope = appScope,
            onQueued = trackingScheduler::enqueueFlush,
        )
    }

    /** Sessions, statuses, readings, rating, review, statistics and achievements. */
    val tracking: TrackingRepository by lazy {
        TrackingRepository(
            remote = trackingRemote,
            queue = TrackingQueue(settings),
            accountKey = session::accountKey,
            account = signedInUser,
            updateUser = auth::setUser,
            readingChanges = readingChanges,
            scope = appScope,
            onQueued = trackingScheduler::enqueueFlush,
        )
    }

    private val timerNotifications: TimerNotifications by lazy { TimerNotifications(app) }

    /** The reading timer: one at a time, persisted, with its ongoing notification. */
    val timer: TimerEngine by lazy {
        TimerEngine(
            store = settings,
            account = auth.status.map { (it as? AuthState.Status.SignedIn)?.accountKey },
            notifier = timerNotifications,
            scope = appScope,
            // A result pushed out by a newer timer before it was saved: keep its time, no page.
            // (Started before its book loaded: its file is looked up now.)
            saveUnconfirmed = { done ->
                tracking.logTimedSession(
                    bookId = done.bookId,
                    fileId = done.fileId ?: tracking.trackingFileId(done.bookId),
                    sessionId = done.sessionId,
                    startedAtMs = done.startedAtMs,
                    endedAtMs = done.endedAtMs,
                    activeSeconds = done.activeSeconds,
                    startProgress = done.startProgress,
                    spans = done.spans,
                )
            },
        )
    }

    /**
     * In every process, at Application.onCreate: those started for a worker, an alarm, boot or an
     * update too. So only what those need starts here; what only the screens use waits for the
     * first activity (onActivityCreated below).
     */
    internal fun start() {
        // The refresh token is decrypted here, on an IO thread (a Keystore round trip), not on the
        // main thread when the first screen asks whether the user is signed in: until then that only
        // checks it is stored (Session.isSignedIn). Tokens the key can't decrypt still end at
        // Login, a moment later, and stay on the device (see Session.store), in case that passes.
        // Until then the account's starters below run as if signed in, so that sign-out is a real
        // one for them: a running timer is paused, as at any sign-out. (The timer isn't held back
        // until the read: TimerEngine.start has to begin loading the account's timer at once, so
        // that a Pause or Stop from its notification, in a process just started for it, waits for
        // that timer.) The access token waits for the first request, and isn't decrypted once
        // expired.
        appScope.launch(Dispatchers.IO) {
            session.refreshToken
            if (auth.status.value is AuthState.Status.SignedIn && !session.isSignedIn) auth.setSignedOut()
        }
        DownloadNotifications(app).ensureChannel()
        timerNotifications.ensureChannels()
        appSettings.start()
        tracking.start()
        timer.start()
        // The web sends a waiting theme save on `pagehide`: here, when the app leaves the screen.
        // The app's settings key does the same.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (!activity.isChangingConfigurations) {
                    theme.flushPendingSave()
                    appSettings.flushSoon()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // A process started for a worker, an alarm or boot never shows a screen: the
                // appearance (its device copy, which the splash waits for, and in sync mode the
                // account's, a request), the search for reader note writes still waiting, the icon
                // catalogue and the download folder's clean-up wait for one that does. Dispatched
                // from the activity's super.onCreate.
                if (uiStarted) return
                uiStarted = true
                theme.start()
                // Reader note writes still waiting on the device (every book file of the account is
                // read to find them): sent from here and after each sign-in. A worker's process
                // needn't look: each write schedules the notes worker, which WorkManager keeps.
                ReaderNotesScheduler.start(app, auth.status.map { (it as? AuthState.Status.SignedIn)?.accountKey }, appScope)
                appScope.launch { Lucide.preload(app) }
                downloads.cleanUpLeftovers()
            }
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        sync.start()
    }

    /**
     * Signs in and makes the app signed in: [AuthState.status] flips, so the shell leaves Login.
     * Throws the Api's exceptions (ApiException 401 for a wrong password, IOException offline), and
     * [DefaultPasswordException] for an account that must change its password in the web app first
     * (that session is signed out again straight away).
     */
    suspend fun signIn(server: String, username: String, password: String): NativeCredentials {
        val creds = api.login(server, username, password)
        if (creds.user?.isDefaultPassword == true) {
            endSession()
            throw DefaultPasswordException()
        }
        auth.setSignedIn(creds.user)
        downloads.recount() // this account's
        sync.onSignedIn()
        return creds
    }

    /** Signs out here at once, and on the server (best effort) after; the shell goes to Login. */
    fun signOut() {
        theme.cancelPendingSave()
        stopAccountWork()
        endSession()
        auth.setSignedOut()
    }

    /**
     * For a pass of writes queued by [account] (reader notes, reading sessions): true while the
     * sign-in it started under lasts. Every sign-out clears the credentials, which changes their
     * generation, so a pass started before one stops there, before the next account's token can go
     * out with its writes (even when that account's storage key is the same: keys turn unusual
     * characters into `_`).
     */
    fun stillSignedInAs(account: String): () -> Boolean {
        val generation = session.credentialsGeneration
        return { session.credentialsGeneration == generation && session.isSignedIn && session.accountKey() == account }
    }

    /**
     * What an account leaves behind when it signs out, or the server ends its session (any thread):
     * its downloads and background sends stop, while its queues (progress, the tracker's sessions,
     * settings change and timer, reader notes) stay on the device for its next sign-in. And the
     * images and PDFs fetched for it go: covers and comic pages are cached by URL alone, in Coil's
     * memory and disk caches (the app's and the comics reader's), so the next account on this phone
     * sees only what it fetches itself.
     */
    private fun stopAccountWork() {
        downloads.cancelAll()
        sync.onSignedOut()
        trackingScheduler.cancel()
        ReaderNotesScheduler.cancel(app)
        readingChanges.reset()
        appScope.launch(Dispatchers.IO) {
            for (loader in listOf(SingletonImageLoader.get(app), ComicImages.loader(app) { api.client })) {
                loader.memoryCache?.clear()
                runCatching { loader.diskCache?.clear() }
            }
            // The PDF reader's served copies (`pdf/<account>/`); downloads are kept per account.
            File(app.cacheDir, PdfFiles.CACHE_DIR).deleteRecursively()
        }
    }

    /**
     * Forgets the tokens on the device first, so neither a slow network nor the app being killed
     * can leave it signed in, then ends the session on the server in the background.
     */
    private fun endSession() {
        val refresh = session.refreshToken
        session.clearCredentials()
        if (refresh != null) appScope.launch { runCatching { api.revoke(refresh) } }
    }

    /** Fetches the signed-in user (permissions may have changed); the cached one if offline. */
    suspend fun refreshUser(): AuthUser? =
        runCatching { api.me() }.getOrNull()?.also { auth.setUser(it) } ?: auth.user.value

    // Called on an OkHttp thread when the server rejects the refresh token (the Api has already
    // cleared the credentials).
    private fun onRefreshRejected() {
        stopAccountWork()
        auth.setSignedOut()
    }

    private fun deviceLabel(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
        return "$model (Android ${Build.VERSION.RELEASE})"
    }
}

/** The account still has its default password; it has to be changed in the web app first. */
class DefaultPasswordException : Exception("This account still has its default password")
