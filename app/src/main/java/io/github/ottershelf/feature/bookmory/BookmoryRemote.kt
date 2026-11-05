package io.github.ottershelf.feature.bookmory

import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.BookQuery
import io.github.ottershelf.core.model.Pagination
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.model.SortSpec
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.tracking.Patch
import io.github.ottershelf.core.tracking.TrackingRepository
import io.github.ottershelf.feature.bookedit.LibraryRoles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate

/**
 * Every server call the Bookmory import makes, behind an interface so the import is tested against a
 * fake server ([BookmoryImporterTest]). [ApiBookmoryRemote] is the real one. Calls throw
 * [ApiException] for an HTTP error and IOException offline, like `Api`.
 */
interface BookmoryRemote {
    /** `POST books/query` (every book the user can see), page [page] (0-based) of [size] (1..200). */
    suspend fun books(page: Int, size: Int): ServerBookPage

    /** `GET uploads/capabilities`: whether the user may upload, and to which libraries. */
    suspend fun uploadTargets(): UploadCapabilities

    /** `GET books/:id`, the user's status and rating, or null when the book is gone (404). */
    suspend fun bookStatus(bookId: Long): BookStatusAndRating?

    /** `GET books/:id/reading-attempts`, page [page] (1-based) of [pageSize] (1..100). */
    suspend fun readings(bookId: Long, page: Int, pageSize: Int): ServerReadingPage

    /** `POST books/:id/reading-attempts` (CreateReadingAttemptDto): the reading the server stored. */
    suspend fun addReading(bookId: Long, startedOn: LocalDate?, endedOn: LocalDate?, outcome: String): ServerReading

    /** `PATCH books/:id/status` (SetStatusDto): [status] with `startedAt` only for reading. */
    suspend fun setStatus(bookId: Long, status: String, startedAt: Patch<LocalDate> = Patch.Keep)

    /**
     * Whether ratings may be set, and in which libraries: `bulk-set-rating` needs the permission
     * library_edit_metadata and (BookOrbit 3.5) the editor or owner role in the book's library.
     */
    suspend fun ratingAccess(): RatingAccess

    /** `POST books/bulk-set-rating` (BulkSetRatingDto) for one book. */
    suspend fun setRating(bookId: Long, rating: Int)

    /** `POST uploads` (CreateUploadSessionDto). A finished upload with the same key comes back as it is. */
    suspend fun createUpload(body: CreateUploadBody): UploadSessionInfo

    /** `POST uploads/:id/chunks`: [bytes] as the multipart field `file`, at [offset] (`Upload-Offset`). */
    suspend fun uploadChunk(sessionId: String, offset: Long, bytes: ByteArray): UploadSessionInfo

    /** `POST uploads/:id/complete`. */
    suspend fun completeUpload(sessionId: String): UploadSessionInfo

    /** `GET uploads/:id`. */
    suspend fun upload(sessionId: String): UploadSessionInfo
}

/** A `books/query` card: only what matching reads (the server sends much more; it is ignored). */
@Serializable
data class ServerBookCard(
    val id: Long,
    val libraryId: Long? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val isbn13: String? = null,
    val rating: Int? = null,
    val readStatus: ReadStatusInfo? = null,
) {
    fun toServerBook() = ServerBook(id, title, subtitle, authors, isbn13, rating, readStatus?.status, libraryId)
}

@Serializable
data class ServerBookPage(val items: List<ServerBookCard> = emptyList(), val total: Int = 0, val page: Int = 0, val size: Int = 0)

@Serializable
data class UploadLibrary(
    val id: Long,
    val name: String,
    /** Empty: every supported format. */
    val allowedFormats: List<String> = emptyList(),
) {
    val takesEpub: Boolean get() = allowedFormats.isEmpty() || allowedFormats.any { it.trim().equals("epub", ignoreCase = true) }
}

@Serializable
data class UploadCapabilities(
    val canUploadToLibrary: Boolean = false,
    val libraries: List<UploadLibrary> = emptyList(),
)

/**
 * Who may rate what: [permitted] is the account's library_edit_metadata (and not a demo account);
 * [editableLibraries] the libraries where it is editor or owner, or null when every library will do
 * (a superuser, a server before 3.5) or that isn't known.
 */
data class RatingAccess(
    val permitted: Boolean = true,
    val editableLibraries: Set<Long>? = null,
    val libraryNames: Map<Long, String> = emptyMap(),
) {
    /** The rule for a book in [libraryId] (unknown: tried, as the server decides). */
    fun rule(libraryId: Long?): RatingRule = when {
        !permitted -> RatingRule.NoPermission
        editableLibraries == null || libraryId == null || libraryId in editableLibraries -> RatingRule.Allowed
        else -> RatingRule.NotEditor(libraryNames[libraryId] ?: libraryId.toString())
    }
}

sealed interface RatingRule {
    data object Allowed : RatingRule
    /** The account can't edit book details at all. */
    data object NoPermission : RatingRule
    /** The account isn't an editor in the book's library, [library] by name. */
    data class NotEditor(val library: String) : RatingRule
}

data class BookStatusAndRating(val status: String?, val rating: Int?)

/** A page of a book's readings, oldest or newest first as the server lists them. */
data class ServerReadingPage(val items: List<ServerReading>, val total: Int)

/** CreateUploadSessionDto (server/src/modules/upload/dto): exactly these fields. */
@Serializable
data class CreateUploadBody(
    val filename: String,
    val sizeBytes: Long,
    val idempotencyKey: String,
    val target: UploadTargetBody,
    val contentType: String,
    val sha256: String,
)

/** UploadTargetDto with kind `library` (no folder: the library's own layout decides). */
@Serializable
data class UploadTargetBody(val kind: String = "library", val libraryId: Long)

/** UploadSessionResponse: the fields the import reads. */
@Serializable
data class UploadSessionInfo(
    val id: String,
    val status: String,
    val sizeBytes: Long = 0,
    val receivedBytes: Long = 0,
    val bookId: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/**
 * [BookmoryRemote] over the app's [Api]. The tracking writes go through [TrackingRepository], so the
 * rest of the app (book pages, calendar, history, the grids' statuses) hears about them as it does
 * about the book page's own.
 */
class ApiBookmoryRemote(
    private val api: Api,
    private val tracking: TrackingRepository,
    private val user: () -> AuthUser?,
    private val roles: LibraryRoles,
) : BookmoryRemote {

    private val json = ApiJson

    override suspend fun books(page: Int, size: Int): ServerBookPage {
        val body = json.encodeToJsonElement(
            BookQuery.serializer(),
            BookQuery(sort = listOf(SortSpec("title", "asc")), pagination = Pagination(page, size.coerceIn(1, 200))),
        )
        return api.send("POST", "books/query", body) { json.decodeFromString(ServerBookPage.serializer(), it) }
    }

    override suspend fun uploadTargets(): UploadCapabilities =
        api.send("GET", "uploads/capabilities", null) { json.decodeFromString(UploadCapabilities.serializer(), it) }

    override suspend fun bookStatus(bookId: Long): BookStatusAndRating? = try {
        val book = api.book(bookId)
        BookStatusAndRating(book.readStatus?.status, book.rating)
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    override suspend fun readings(bookId: Long, page: Int, pageSize: Int): ServerReadingPage {
        val list = tracking.attempts(bookId, page, pageSize)
        return ServerReadingPage(list.items.map { ServerReading(it.id, date(it.startedOn), date(it.endedOn), it.outcome) }, list.total)
    }

    private fun date(text: String?): LocalDate? = text?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    override suspend fun addReading(bookId: Long, startedOn: LocalDate?, endedOn: LocalDate?, outcome: String): ServerReading {
        val a = tracking.createPastRead(bookId, startedOn, endedOn, outcome)
        return ServerReading(a.id, date(a.startedOn), date(a.endedOn), a.outcome)
    }

    override suspend fun setStatus(bookId: Long, status: String, startedAt: Patch<LocalDate>) {
        tracking.setStatus(bookId, status, startedAt = startedAt)
    }

    override suspend fun ratingAccess(): RatingAccess {
        val u = user()
        if (!TrackingRepository.canRate(u)) return RatingAccess(permitted = false)
        if (roles.knownAnswer(u) == true) return RatingAccess()
        val libraries = try {
            api.libraries()
        } catch (e: ApiException) {
            if (e.code == 401) throw e
            // Not known: every rating is tried, and a refusal is reported once.
            return RatingAccess()
        }
        return RatingAccess(
            editableLibraries = libraries.filter { roles.canEditMetadata(u, it.id) }.mapTo(mutableSetOf()) { it.id },
            libraryNames = libraries.associate { it.id to it.name },
        )
    }

    override suspend fun setRating(bookId: Long, rating: Int) = tracking.setRating(bookId, rating)

    override suspend fun createUpload(body: CreateUploadBody): UploadSessionInfo =
        api.send("POST", "uploads", BookmoryBodies.createUpload(body)) { session(it) }

    override suspend fun uploadChunk(sessionId: String, offset: Long, bytes: ByteArray): UploadSessionInfo {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "chunk", bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return api.sendBody("POST", "uploads/$sessionId/chunks", multipart, headers = mapOf("Upload-Offset" to offset.toString())) { session(it) }
    }

    override suspend fun completeUpload(sessionId: String): UploadSessionInfo =
        api.send("POST", "uploads/$sessionId/complete", null) { session(it) }

    override suspend fun upload(sessionId: String): UploadSessionInfo =
        api.send("GET", "uploads/$sessionId", null) { session(it) }

    private fun session(text: String) = json.decodeFromString(UploadSessionInfo.serializer(), text)
}

/** The request bodies, built here so a test can check them against the server's DTOs. */
internal object BookmoryBodies {
    fun createUpload(body: CreateUploadBody): JsonObject = buildJsonObject {
        put("filename", body.filename)
        put("sizeBytes", body.sizeBytes)
        put("idempotencyKey", body.idempotencyKey)
        put("target", buildJsonObject {
            put("kind", body.target.kind)
            put("libraryId", body.target.libraryId)
        })
        put("contentType", body.contentType)
        put("sha256", body.sha256)
    }
}
