package io.github.ottershelf.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.AuthorsPage
import io.github.ottershelf.core.model.AvailabilityItem
import io.github.ottershelf.core.model.AvailabilityQuery
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookCollection
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookQuery
import io.github.ottershelf.core.model.BookRequestAvailability
import io.github.ottershelf.core.model.BookRequestItem
import io.github.ottershelf.core.model.BookRequestPage
import io.github.ottershelf.core.model.BookRequestSubmitResult
import io.github.ottershelf.core.model.BookRequestSummary
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.model.BooksPage
import io.github.ottershelf.core.model.BrowseCounts
import io.github.ottershelf.core.model.CreateBookRequest
import io.github.ottershelf.core.model.CurrentlyReading
import io.github.ottershelf.core.model.FileProgress
import io.github.ottershelf.core.model.Library
import io.github.ottershelf.core.model.LoginRequest
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.model.MetadataProviderInfo
import io.github.ottershelf.core.model.NativeCredentials
import io.github.ottershelf.core.model.Pagination
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.model.ReadingStreak
import io.github.ottershelf.core.model.RefreshRequest
import io.github.ottershelf.core.model.RequestDestination
import io.github.ottershelf.core.model.SaveProgress
import io.github.ottershelf.core.model.ScrollerBatch
import io.github.ottershelf.core.model.ScrollerBatchRequest
import io.github.ottershelf.core.model.ScrollerRequest
import io.github.ottershelf.core.model.SeriesPage
import io.github.ottershelf.core.model.SetStatus
import io.github.ottershelf.core.model.SmartScope
import io.github.ottershelf.core.model.SortSpec
import io.github.ottershelf.core.model.WidgetBatch
import io.github.ottershelf.core.model.WidgetBatchRequest
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.sync.ProgressRemote
import io.github.ottershelf.core.theme.ThemePreferencesBody
import io.github.ottershelf.core.theme.ThemeRemote
import io.github.ottershelf.core.theme.ThemeStorageModeBody
import io.github.ottershelf.core.util.IsoTime
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String, val errorCode: String? = null) : IOException(message)

/**
 * BookOrbit REST client (`/api/v1`) using native-client auth: bearer access token, refresh token
 * in the request body, rotated on every refresh. No cookie jar, deliberately: the server rejects a
 * body refresh token that conflicts with a `refresh_token` cookie.
 *
 * [deviceLabel] names this device in the web app's list of sessions (e.g. "Google Pixel 8 Pro
 * (Android 17)"); the app builds it from android.os.Build. [onSignedOut] runs (on an OkHttp thread)
 * when the server rejects the refresh token: the credentials are already cleared by then.
 */
class Api(
    private val session: Session,
    private val deviceLabel: String,
    private val onSignedOut: () -> Unit,
) : ProgressRemote, ThemeRemote {

    val json: Json get() = ApiJson

    private val jsonType = "application/json".toMediaType()
    private val refreshLock = Any()

    /** No auth handling: the base of the clients below. */
    private val bareClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Login, refresh and logout. Never follows a redirect: OkHttp would post a 307/308's body (the
     * password, or the refresh token) again to wherever it points. A redirect is an error instead.
     * The whole call has 15 s (DNS included), since a refresh holds the lock every request waits on;
     * login and logout set their own limits.
     */
    private val authClient: OkHttpClient = bareClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The user is watching the button, and the server takes a moment to check a password. */
    private val loginClient: OkHttpClient by lazy { authClient.newBuilder().callTimeout(30, TimeUnit.SECONDS).build() }

    /** Authenticated client, shared with the image loader so covers get the bearer header too. */
    val client: OkHttpClient = bareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.header("Authorization") != null || !isOwnServer(request.url)) {
                chain.proceed(request)
            } else {
                val token = currentAccessToken()
                chain.proceed(
                    if (token == null) request
                    else request.newBuilder().header("Authorization", "Bearer $token").build()
                )
            }
        }
        .authenticator { _, response ->
            if (response.priorResponse != null || !isOwnServer(response.request.url)) return@authenticator null
            val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
            val fresh = synchronized(refreshLock) {
                val stored = session.accessToken
                // Another request may already have refreshed while this one was in flight.
                if (stored != null && stored != failed) stored else refreshBlocking()
            } ?: return@authenticator null
            response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
        }
        .build()

    /** The server URL as last parsed, so the interceptor doesn't parse it again for every request. */
    @Volatile private var parsedServer: Pair<String, HttpUrl?>? = null

    private fun serverHttpUrl(): HttpUrl? {
        val server = session.serverUrl ?: return null
        parsedServer?.let { (text, url) -> if (text == server) return url }
        return server.toHttpUrlOrNull().also { parsedServer = server to it }
    }

    /**
     * Whether [url] is on the signed-in server, so it may carry the bearer token. Compared part by
     * part, never as text: `https://books.example.net@evil.example/` and
     * `https://books.example.net.evil.example/` both start with the server's URL but go elsewhere,
     * and some image URLs (book request covers) are chosen by other users.
     */
    internal fun isOwnServer(url: HttpUrl): Boolean {
        val server = serverHttpUrl() ?: return false
        val base = server.encodedPath.trimEnd('/')
        return url.scheme == server.scheme && url.host == server.host && url.port == server.port &&
            url.username.isEmpty() && url.password.isEmpty() &&
            (url.encodedPath == base || url.encodedPath.startsWith("$base/"))
    }

    private fun api(path: String): String =
        "${session.serverUrl ?: throw IOException("No server configured")}/api/v1/$path"

    /**
     * Access token, refreshed first if it expires within 30s (as the web client does). When the
     * refresh fails for a passing reason, the old token while it lasts, else that IOException.
     */
    private fun currentAccessToken(): String? = synchronized(refreshLock) {
        val expiresAt = session.accessExpiresAtMs
        // Only a token that hasn't expired is ever used, so an expired one isn't decrypted (a
        // Keystore round trip): the usual case at app start, since they last 15 minutes.
        val token = if (expiresAt > System.currentTimeMillis()) session.accessToken else null
        if (token != null && expiresAt - 30_000 > System.currentTimeMillis()) return@synchronized token
        try {
            refreshBlocking()
        } catch (e: IOException) {
            if (token != null && expiresAt > System.currentTimeMillis()) token else throw e
        }
    }

    /**
     * When (System.nanoTime) a refresh last failed for a passing reason, or null: for
     * [REFRESH_PAUSE_MS] after it no refresh is tried, since every request waits for [refreshLock]
     * and each would otherwise try again and wait as long while the server is slow or down. Not the
     * wall clock, which network time or the user may set back and so stretch the pause. Guarded by
     * [refreshLock].
     */
    private var refreshPausedAtNs: Long? = null

    private fun refreshPaused(): Boolean = refreshPausedAtNs?.let { msSince(it) < REFRESH_PAUSE_MS } ?: false

    private fun msSince(nanoTime: Long): Long = (System.nanoTime() - nanoTime) / 1_000_000

    /**
     * Must hold [refreshLock]. Returns the new access token, or null if the session is gone (or was
     * rejected just now). A refresh that fails for a passing reason, or is paused after one, throws
     * an IOException, so the request fails at once instead of going without a token. Runs inside the
     * interceptor and the authenticator, where anything thrown other than an IOException would crash
     * the app from an OkHttp thread.
     */
    private fun refreshBlocking(): String? {
        val generation = session.credentialsGeneration
        val refresh = session.refreshToken ?: return null
        if (refreshPaused()) throw IOException(RENEWAL_PAUSED)
        val request = Request.Builder()
            .url(api("auth/refresh"))
            .post(encode(RefreshRequest(refresh)))
            .build()
        val started = System.nanoTime()
        var answered = false
        return try {
            authClient.newCall(request).execute().use { response ->
                answered = true
                when {
                    response.isSuccessful -> {
                        val creds = json.decodeFromString(NativeCredentials.serializer(), response.body.limited(MAX_JSON_BYTES).string())
                        refreshPausedAtNs = null
                        // Signed out while this was on its way: the tokens mustn't come back.
                        if (session.store(creds, ifGeneration = generation)) creds.accessToken else null
                    }
                    // The server's own rejection: it answers a bad, expired or reused token with 401
                    // (400 for a malformed request), never 403, which can only come from something in
                    // front of it and is passing. Only these tokens go: if the user signed out and in again
                    // meanwhile, the new session's stay.
                    response.code == 400 || response.code == 401 -> {
                        if (session.clearCredentials(ifGeneration = generation)) onSignedOut()
                        null
                    }
                    else -> throw IOException("$RENEWAL_FAILED (HTTP ${response.code})")
                }
            }
        } catch (e: Exception) {
            // Offline, a time-out, the server busy, or not an answer from it (a proxy's login page
            // sent as 200, say): keep the refresh token and try again later. After an answer, or a
            // failure that took a while, not for REFRESH_PAUSE_MS; one that came back at once (no
            // network yet) costs nothing to try again, and the connection may be back any moment.
            if (answered || msSince(started) >= SLOW_REFRESH_MS) refreshPausedAtNs = System.nanoTime()
            throw e as? IOException ?: IOException(RENEWAL_FAILED, e)
        }
    }

    // --- auth --------------------------------------------------------------------------------

    /**
     * Signs in at [server]. The server is stored only with the tokens, once it has worked: until
     * then the stored one, and any tokens kept for it, stay as they were.
     */
    suspend fun login(server: String, username: String, password: String): NativeCredentials {
        val body = LoginRequest(
            username = username,
            password = password,
            deviceLabel = deviceLabel.take(100),
        )
        val request = Request.Builder().url("${server.trimEnd('/')}/api/v1/auth/login").post(encode(body)).build()
        val creds = call(loginClient, request, NativeCredentials.serializer())
        try {
            session.store(creds, server = server)
        } catch (e: IOException) {
            // Not kept on this device (the Keystore failed): end that session rather than leave it
            // on the server, unusable, until it expires.
            runCatching { revokeAt(server, creds.refreshToken) }
            throw e
        }
        return creds
    }

    /**
     * Ends the session [refreshToken] belongs to on the server (`auth/logout`). Leaves the stored
     * credentials alone: the caller clears them first, so a slow network (or the app being killed
     * meanwhile) can't keep the device signed in. Best effort, with a short limit.
     */
    suspend fun revoke(refreshToken: String) =
        revokeAt(session.serverUrl ?: throw IOException("No server configured"), refreshToken)

    private suspend fun revokeAt(server: String, refreshToken: String) {
        val request = Request.Builder()
            .url("${server.trimEnd('/')}/api/v1/auth/logout")
            .post(encode(RefreshRequest(refreshToken)))
            .build()
        call(revokeClient, request) { }
    }

    private val revokeClient: OkHttpClient by lazy { authClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }

    suspend fun me(): AuthUser = get("auth/me", AuthUser.serializer())

    // --- appearance (ThemeRepository) ----------------------------------------------------------

    override suspend fun themePreferences(): JsonObject? =
        get("user-preferences/theme", JsonObject.serializer())["settings"] as? JsonObject

    override suspend fun saveThemePreferences(body: ThemePreferencesBody) {
        call(client, Request.Builder().url(api("user-preferences/theme")).put(encode(body)).build()) { }
    }

    override suspend fun setThemeStorageMode(body: ThemeStorageModeBody) {
        call(client, Request.Builder().url(api("users/me/theme-storage-mode")).patch(encode(body)).build()) { }
    }

    // --- library data ------------------------------------------------------------------------

    suspend fun libraries(): List<Library> = get("libraries", ListSerializer(Library.serializer()))

    suspend fun smartScopes(): List<SmartScope> = get("smart-scopes", ListSerializer(SmartScope.serializer()))

    suspend fun collections(): List<BookCollection> =
        get("collections", ListSerializer(BookCollection.serializer()))

    suspend fun books(source: BookSource, page: Int, size: Int, query: String? = null): BooksPage {
        val path = when (source) {
            is BookSource.All -> "books/query"
            is BookSource.InLibrary -> "libraries/${source.id}/books"
            is BookSource.InScope -> "smart-scopes/${source.id}/books/query"
            is BookSource.InCollection -> "collections/${source.id}/books/query"
            // Dedicated routes (the books query can only match author/series names, not ids). They
            // take no search text, and reject parameters they don't know.
            is BookSource.ByAuthor ->
                return get("authors/${source.id}/books?page=$page&size=$size&sort=title&order=asc", BooksPage.serializer())
            is BookSource.InSeries ->
                return get("series/${source.id}/books?page=$page&size=$size&sort=seriesIndex&order=asc", BooksPage.serializer())
            is BookSource.Dashboard, is BookSource.Downloaded, is BookSource.Authors, is BookSource.AllSeries ->
                throw IllegalArgumentException("${source.key} isn't a server book list")
        }
        val body = BookQuery(
            sort = listOf(SortSpec("title", "asc")),
            pagination = Pagination(page, size),
            q = query?.takeIf { it.isNotBlank() },
        )
        val request = Request.Builder().url(api(path)).post(encode(body)).build()
        return call(client, request, BooksPage.serializer())
    }

    suspend fun book(id: Long): BookDetail = get("books/$id", BookDetail.serializer())

    suspend fun browseCounts(): BrowseCounts = get("browse-counts", BrowseCounts.serializer())

    suspend fun authors(page: Int, size: Int, query: String?): AuthorsPage =
        get("authors?page=$page&size=$size&${searchParams(query)}", AuthorsPage.serializer())

    suspend fun series(page: Int, size: Int, query: String?): SeriesPage =
        get("series?page=$page&size=$size&${searchParams(query)}", SeriesPage.serializer())

    /** By name, or by best match when searching (both lists support `relevance` only with `q`). */
    private fun searchParams(query: String?): String {
        val q = query?.trim().orEmpty()
        return if (q.isEmpty()) "sort=name&order=asc"
        else "q=${java.net.URLEncoder.encode(q, "UTF-8")}&sort=relevance&order=desc"
    }

    // --- dashboard ---------------------------------------------------------------------------

    /**
     * Currently Reading and Reading Streak in one request (the web asks the same way). Either is
     * null if the server couldn't work it out; the other still arrives.
     */
    suspend fun dashboardWidgets(): Pair<CurrentlyReading?, ReadingStreak?> {
        val body = WidgetBatchRequest(listOf("currently-reading", "reading-streak"))
        val request = Request.Builder().url(api("dashboard/widgets/batch")).post(encode(body)).build()
        return call(client, request) { text ->
            val items = json.decodeFromString(WidgetBatch.serializer(), text).items
            fun <T> widget(type: String, de: DeserializationStrategy<T>): T? {
                val data = items.firstOrNull { it.type == type && !it.failed }?.data as? JsonObject ?: return null
                return runCatching { json.decodeFromJsonElement(de, data) }.getOrNull()
            }
            widget("currently-reading", CurrentlyReading.serializer()) to widget("reading-streak", ReadingStreak.serializer())
        }
    }

    /** The newest books, [limit] at most (1..50): the web's Recently Added shelf. Null if the server failed it. */
    suspend fun recentlyAdded(limit: Int): List<BookCard>? {
        // The batch route: the single-shelf one also counts this month's additions, which isn't shown.
        val body = ScrollerBatchRequest(listOf(ScrollerRequest(id = "recent", type = "recently-added", limit = limit)))
        val request = Request.Builder().url(api("dashboard/scrollers/batch")).post(encode(body)).build()
        return call(client, request, ScrollerBatch.serializer()).items.firstOrNull()?.takeUnless { it.failed }?.books
    }

    /** Drops the server's cached widgets for this user; otherwise they're kept for 2 minutes. */
    suspend fun refreshDashboard() {
        call(client, Request.Builder().url(api("dashboard/refresh")).post(ByteArray(0).toRequestBody(null)).build()) { }
    }

    /**
     * An author's portrait from the list's server-relative, versioned `imageUrl`. Only a path
     * (`/...`) is taken: anything else pasted after the server URL could change its host.
     */
    fun serverUrl(relative: String): String {
        if (!relative.startsWith("/")) throw IOException("Not a server path: $relative")
        return (session.serverUrl ?: throw IOException("No server configured")) + relative
    }

    suspend fun setStatus(bookId: Long, status: String): ReadStatusInfo {
        val request = Request.Builder().url(api("books/$bookId/status")).patch(encode(SetStatus(status))).build()
        return call(client, request, ReadStatusInfo.serializer())
    }

    /** Pre-generated JPEG thumbnail. `?t=` makes the server mark it immutable, so it caches hard. */
    fun thumbnailUrl(book: BookCard): String = thumbnailUrl(book.id, book.updatedAt ?: book.addedAt)

    fun thumbnailUrl(id: Long, updatedAt: String?): String = api("books/$id/thumbnail?t=${version(updatedAt)}")

    /** For books known only by id (series covers): no fake version, which the server would take as final. */
    fun unversionedThumbnailUrl(id: Long): String = api("books/$id/thumbnail")

    fun coverUrl(id: Long, updatedAt: String?): String = api("books/$id/cover?t=${version(updatedAt)}")

    private fun version(iso: String?): Long = IsoTime.parse(iso) ?: 0L

    // --- reading -----------------------------------------------------------------------------

    override suspend fun fileProgress(fileId: Long): FileProgress =
        get("books/files/$fileId/progress", FileProgress.serializer())

    override suspend fun saveProgress(fileId: Long, body: SaveProgress) {
        val request = Request.Builder().url(api("books/files/$fileId/progress")).post(encode(body)).build()
        call(client, request) { }
    }

    override suspend fun saveSession(fileId: Long, body: ReadingSession) {
        val request = Request.Builder().url(api("books/files/$fileId/sessions")).post(encode(body)).build()
        call(client, request) { }
    }

    /**
     * Synchronous passthrough for the reader WebView: `/api/v1/...` requested by the page is sent to
     * the server with the bearer token. Called on a WebView IO thread.
     *
     * Only the reader's own routes ([PROXY_PREFIXES]) go through: anything else a page script (or a
     * WebView debugger) asks for is refused, so the token can't be used to read the rest of the API.
     */
    fun proxy(pathAndQuery: String): Response {
        val server = session.serverUrl ?: throw IOException("No server configured")
        // Checked on the URL as OkHttp will send it, i.e. after `..` and `%2e%2e` are resolved.
        val url = (server + pathAndQuery).toHttpUrlOrNull() ?: throw IOException("Bad reader URL")
        val base = server.toHttpUrl().encodedPath.trimEnd('/')
        if (PROXY_PREFIXES.none { url.encodedPath.startsWith(base + it) }) {
            throw IOException("Not a reader route: ${url.encodedPath}")
        }
        return client.newCall(Request.Builder().url(url).get().build()).execute()
    }

    // --- downloads (blocking: call on an IO thread) -------------------------------------------
    // [onCall] receives each request before it starts, so the caller can cancel it: that aborts the
    // transfer at once, even mid-read.

    /** The reader's `epub/:bookId/info` document for this file, as the server sends it. */
    fun epubInfo(bookId: Long, fileId: Long, onCall: ((Call) -> Unit)? = null): ByteArray =
        execute(client, api("epub/$bookId/info?fileId=$fileId"), onCall) { it.body.limited(MAX_JSON_BYTES).bytes() }

    /** A cover or thumbnail, whole; one over [MAX_IMAGE_BYTES] is refused. */
    fun bytes(url: String, onCall: ((Call) -> Unit)? = null): ByteArray =
        execute(client, url, onCall) { it.body.limited(MAX_IMAGE_BYTES).bytes() }

    /** Streams the original file; [consume] gets the Content-Length (-1 if unknown) and the body. */
    fun downloadFile(fileId: Long, onCall: ((Call) -> Unit)?, consume: (Long, InputStream) -> Unit) =
        execute(downloadClient, api("books/files/$fileId/download"), onCall) {
            consume(it.body.contentLength(), it.body.byteStream())
        }

    private val downloadClient: OkHttpClient by lazy { client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }

    private fun <T> execute(http: OkHttpClient, url: String, onCall: ((Call) -> Unit)?, block: (Response) -> T): T {
        val call = http.newCall(Request.Builder().url(url).get().build())
        onCall?.invoke(call)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw apiError(response)
            block(response)
        }
    }

    // --- book requests -----------------------------------------------------------------------

    suspend fun metadataProviders(): List<MetadataProviderInfo> =
        get("metadata-fetch/providers", ListSerializer(MetadataProviderInfo.serializer()))

    /**
     * Metadata search as the server streams it (SSE): one `data:` event per candidate, as each
     * provider answers. `provider-status` events (timeouts, throttling) are skipped. [isbn]: searched
     * first by each provider, which falls back to the title and author when it knows no such ISBN.
     */
    fun searchMetadata(title: String, author: String, mediaKind: String, isbn: String? = null): Flow<MetadataCandidate> = flow {
        val url = api("metadata-fetch/stream").toHttpUrl().newBuilder()
            .apply {
                if (title.isNotBlank()) addQueryParameter("title", title.trim())
                if (author.isNotBlank()) addQueryParameter("author", author.trim())
                if (!isbn.isNullOrBlank()) addQueryParameter("isbn", isbn.trim())
            }
            .addQueryParameter("mediaKind", mediaKind)
            .build()
        val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()
        val call = streamClient.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) throw apiError(response)
            val source = response.body.source()
            var event: String? = null
            val data = StringBuilder()
            // No candidate comes near MAX_EVENT_BYTES; one that does (a broken server, or a huge
            // field it passes on) is dropped without being kept in memory, and the rest still come.
            var tooLong = false
            while (true) {
                if (source.indexOf(LF, 0, MAX_EVENT_BYTES) == -1L && source.buffer.size >= MAX_EVENT_BYTES) {
                    source.skipLine()
                    tooLong = true
                    continue
                }
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (!tooLong && data.isNotEmpty() && (event == null || event == "message")) {
                            runCatching { json.decodeFromString(MetadataCandidate.serializer(), data.toString()) }
                                .getOrNull()?.let { emit(it) }
                        }
                        event = null
                        data.setLength(0)
                        tooLong = false
                    }
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") && !tooLong -> {
                        if (data.length + line.length > MAX_EVENT_BYTES) {
                            tooLong = true
                            data.setLength(0)
                        } else {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).trimStart())
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Long reads: metadata providers can take a while to answer. */
    private val streamClient: OkHttpClient by lazy { client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build() }

    /** Skips to just past the next line break (or to the end), holding at most [MAX_EVENT_BYTES] of it. */
    private fun BufferedSource.skipLine() {
        while (true) {
            val lf = indexOf(LF, 0, MAX_EVENT_BYTES)
            if (lf != -1L) return skip(lf + 1)
            if (buffer.size == 0L) return
            skip(buffer.size)
        }
    }

    suspend fun requestAvailability(items: List<AvailabilityItem>): List<BookRequestAvailability> {
        val request = Request.Builder().url(api("book-requests/availability")).post(encode(AvailabilityQuery(items))).build()
        return call(client, request, ListSerializer(BookRequestAvailability.serializer()))
    }

    suspend fun submitRequest(body: CreateBookRequest): BookRequestSubmitResult {
        val request = Request.Builder().url(api("book-requests")).post(encode(body)).build()
        return call(client, request, BookRequestSubmitResult.serializer())
    }

    suspend fun myRequests(page: Int, includeDismissed: Boolean): BookRequestPage =
        get(
            "book-requests?page=$page&limit=50&sortBy=createdAt&sortDir=desc" +
                if (includeDismissed) "&includeDismissed=true" else "",
            BookRequestPage.serializer(),
        )

    suspend fun requestSummary(): BookRequestSummary = get("book-requests/summary", BookRequestSummary.serializer())

    suspend fun requestDestinations(): Map<String, RequestDestination> =
        get("book-requests/default-destinations", MapSerializer(String.serializer(), RequestDestination.serializer()))

    suspend fun cancelRequest(id: Long): BookRequestItem = postEmpty("book-requests/$id/cancel", BookRequestItem.serializer())

    suspend fun dismissRequest(id: Long): BookRequestItem = postEmpty("book-requests/$id/dismiss", BookRequestItem.serializer())

    suspend fun restoreRequest(id: Long): BookRequestItem = postEmpty("book-requests/$id/restore", BookRequestItem.serializer())

    suspend fun leaveRequest(id: Long) {
        call(client, Request.Builder().url(api("book-requests/$id/subscription")).delete().build()) { }
    }

    private suspend fun <T> postEmpty(path: String, de: DeserializationStrategy<T>): T =
        call(client, Request.Builder().url(api(path)).post(ByteArray(0).toRequestBody(null)).build(), de)

    // --- other data-layer remotes (core.tracking) ---------------------------------------------

    /**
     * One authenticated JSON call to `/api/v1/[pathAndQuery]`, for remotes that live beside their
     * feature's data layer (core.tracking.ApiTrackingRemote) rather than in this file. [body] is
     * sent as JSON (an empty body when null on POST/PATCH); [parse] gets the response text ("" for
     * a 204). Throws [ApiException] for an HTTP error and IOException when offline.
     */
    internal suspend fun <T> send(method: String, pathAndQuery: String, body: JsonElement?, parse: (String) -> T): T {
        val requestBody = when {
            body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)
            method == "GET" || method == "DELETE" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        return call(client, Request.Builder().url(api(pathAndQuery)).method(method, requestBody).build(), parse)
    }

    /**
     * [send] with a body that isn't JSON (feature.bookedit's multipart cover upload): the same
     * authenticated client, errors and cancellation.
     */
    internal suspend fun <T> sendBody(method: String, pathAndQuery: String, body: RequestBody, parse: (String) -> T): T =
        call(client, Request.Builder().url(api(pathAndQuery)).method(method, body).build(), parse)

    // --- plumbing ----------------------------------------------------------------------------

    private inline fun <reified T> encode(body: T): RequestBody =
        json.encodeToString(serializer<T>(), body).toRequestBody(jsonType)

    private suspend fun <T> get(path: String, de: DeserializationStrategy<T>): T =
        call(client, Request.Builder().url(api(path)).get().build(), de)

    private suspend fun <T> call(http: OkHttpClient, request: Request, de: DeserializationStrategy<T>): T =
        call(http, request) { body -> json.decodeFromString(de, body) }

    /** Enqueues (so cancelling the coroutine cancels the HTTP call) and parses off the main thread. */
    private suspend fun <T> call(http: OkHttpClient, request: Request, parse: (String) -> T): T =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw apiError(it)
                            parse(it.body.limited(MAX_JSON_BYTES).string())
                        }
                    }
                    if (!cont.isActive) return
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }

    private fun errorCode(body: String): String? = runCatching {
        ((json.parseToJsonElement(body) as JsonObject)["errorCode"] as? JsonPrimitive)?.content
    }.getOrNull()

    private fun errorMessage(code: Int, body: String): String {
        val message = runCatching {
            when (val m = (json.parseToJsonElement(body) as JsonObject)["message"]) {
                is JsonPrimitive -> m.content
                is JsonArray -> m.joinToString("; ") { (it as? JsonPrimitive)?.content ?: it.toString() }
                else -> null
            }
        }.getOrNull()
        return message ?: "HTTP $code"
    }

    /**
     * An error answer as an [ApiException], from at most [MAX_ERROR_BYTES] of its body: plenty for
     * any message, and never a flood read into memory. A body that can't be read gives "HTTP <code>".
     */
    private fun apiError(response: Response): ApiException {
        val text = runCatching {
            val source = response.body.source()
            source.request(MAX_ERROR_BYTES)
            source.buffer.readUtf8(minOf(source.buffer.size, MAX_ERROR_BYTES))
        }.getOrDefault("")
        return ApiException(response.code, errorMessage(response.code, text), errorCode(text))
    }

    /**
     * This body, once it is known to be at most [limit] bytes (as declared, or as read so far when
     * it comes in chunks): a broken or hostile server could otherwise send gigabytes into memory.
     * Book files and the reader's resources stream instead, and aren't limited.
     */
    private fun ResponseBody.limited(limit: Long): ResponseBody {
        if (contentLength() > limit || source().request(limit + 1)) {
            throw IOException("The server's answer is too large (over ${limit shr 20} MB)")
        }
        return this
    }

    companion object {
        /** What [proxy] forwards: the EPUB info and entries the reader streams (reader.js). */
        val PROXY_PREFIXES = listOf("/api/v1/epub/")

        /** How long no refresh is tried after one that failed for a passing reason. */
        private const val REFRESH_PAUSE_MS = 30_000L

        /** A failed refresh that took at least this long pauses the next ones even without an answer. */
        private const val SLOW_REFRESH_MS = 5_000L

        /** The most read into memory from a JSON answer or the reader's `epub/<id>/info`. */
        private const val MAX_JSON_BYTES = 16L shl 20

        /** The most read into memory for a cover or thumbnail kept with a download. */
        private const val MAX_IMAGE_BYTES = 32L shl 20

        /** The most of an error answer's body read for its message. */
        private const val MAX_ERROR_BYTES = 64L shl 10

        /** The longest metadata search event (all its `data:` lines) that is read; longer ones are skipped. */
        private const val MAX_EVENT_BYTES = 1L shl 20
        private const val LF: Byte = 10

        private const val RENEWAL_FAILED = "The server didn't renew the sign-in"
        private const val RENEWAL_PAUSED = "The server didn't renew the sign-in; trying again shortly"
    }
}
