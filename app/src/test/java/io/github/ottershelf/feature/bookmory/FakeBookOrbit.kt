package io.github.ottershelf.feature.bookmory

import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.tracking.Patch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * An in-memory BookOrbit (3.5) for the Bookmory import, with the server's rules where the import
 * depends on them:
 * - upload sessions are unique per idempotency key; a repeated create hands back a receiving,
 *   processing or completed session only for the same filename, size and target (else 400), and a
 *   finished one carries its book (upload-session.service.ts);
 * - readings are listed in pages; `POST reading-attempts` always closes one, and the status is rebuilt
 *   from the readings except for a book held on unread or want to read (reading-attempt.service.ts);
 * - `PATCH status` opens a reading for reading (or moves an open one's start: an edit, counted), and
 *   for read/abandoned adds a reading dated today unless the latest has that outcome.
 *
 * [failAfter] cuts the connection at the Nth write: before it reaches the server, or (with
 * [failAfterApplying]) after it was applied, as when the answer is lost.
 */
class FakeBookOrbit(private val today: LocalDate = LocalDate.of(2024, 6, 1)) : BookmoryRemote {

    data class Book(val id: Long, val title: String, val subtitle: String? = null, val authors: List<String>, val isbn13: String? = null, val libraryId: Long = 1)
    data class Attempt(val id: Long, val bookId: Long, var startedOn: LocalDate?, var endedOn: LocalDate?, var outcome: String?)
    private class Session(
        val id: String, val key: String, val filename: String, val size: Long, val libraryId: Long, val sha256: String?,
        var status: String = "receiving", val data: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(), var bookId: Long? = null,
    )

    val books = mutableListOf<Book>()
    val attempts = mutableListOf<Attempt>()
    val statusRows = mutableMapOf<Long, String>()
    val ratings = mutableMapOf<Long, Int>()
    private val sessions = mutableMapOf<String, Session>()
    var libraries = listOf(UploadLibrary(1, "Books"), UploadLibrary(2, "Comics", listOf("cbz")))
    var canUpload = true
    /** A BookOrbit 3.7+ server: upload sessions are kept for its own apps (App Attest); whole uploads still work. */
    var sessionsNeedAppAttest = false
    var wholeUploads = 0
    var mayRate = true

    /** What `ratingAccess` reports; `bulk-set-rating` also refuses (403) where it says no. */
    var access = RatingAccess()
    var ratingCalls = 0

    /** A status route that answers 200 for `reading` without opening a reading (a write that does nothing). */
    var ignoreStartReading = false

    /** Writes that changed an existing reading (the import must never make one). */
    var attemptEdits = 0
    var writes = 0
    var failAfter: Int? = null
    var failAfterApplying = false
    private var nextId = 100L

    fun addBook(title: String, authors: List<String>, subtitle: String? = null, isbn13: String? = null, libraryId: Long = 1): Book =
        Book(nextId++, title, subtitle, authors, isbn13, libraryId).also { books += it }

    fun addAttempt(bookId: Long, start: LocalDate?, end: LocalDate?, outcome: String?) {
        attempts += Attempt(nextId++, bookId, start, end, outcome)
        rebuild(bookId)
    }

    fun deleteBook(id: Long) {
        books.removeAll { it.id == id }
        attempts.removeAll { it.bookId == id }
    }

    /** The server dropped finished sessions after their retention (30 days). */
    fun forgetSessions() = sessions.clear()

    private fun book(id: Long) = books.firstOrNull { it.id == id } ?: throw ApiException(404, "Book not found")

    private fun <T> write(block: () -> T): T {
        val limit = failAfter
        writes++
        if (limit != null && writes >= limit) {
            failAfter = null
            if (failAfterApplying) block()
            throw IOException("connection lost")
        }
        return block()
    }

    // --- reads -------------------------------------------------------------------------------

    override suspend fun books(page: Int, size: Int): ServerBookPage {
        require(size in 1..200)
        val sorted = books.sortedBy { it.title.lowercase() }
        val items = sorted.drop(page * size).take(size).map {
            ServerBookCard(it.id, it.libraryId, it.title, it.subtitle, it.authors, it.isbn13, ratings[it.id], statusRows[it.id]?.let { s -> io.github.ottershelf.core.model.ReadStatusInfo(status = s) })
        }
        return ServerBookPage(items, books.size, page, size)
    }

    override suspend fun uploadTargets() = UploadCapabilities(canUpload, if (canUpload) libraries else emptyList())

    override suspend fun bookStatus(bookId: Long): BookStatusAndRating? {
        if (books.none { it.id == bookId }) return null
        return BookStatusAndRating(statusRows[bookId], ratings[bookId])
    }

    override suspend fun readings(bookId: Long, page: Int, pageSize: Int): ServerReadingPage {
        require(page >= 1 && pageSize in 1..100)
        book(bookId)
        val all = attempts.filter { it.bookId == bookId }.sortedByDescending { it.id }
        return ServerReadingPage(all.drop((page - 1) * pageSize).take(pageSize).map { ServerReading(it.id, it.startedOn, it.endedOn, it.outcome) }, all.size)
    }

    // --- tracking writes -----------------------------------------------------------------------

    override suspend fun addReading(bookId: Long, startedOn: LocalDate?, endedOn: LocalDate?, outcome: String) = write {
        book(bookId)
        if (outcome !in setOf("completed", "skimmed", "abandoned")) throw ApiException(400, "outcome must be one of the following values")
        if (startedOn != null && endedOn != null && endedOn < startedOn) throw ApiException(400, "endedOn must be on or after startedOn")
        val a = Attempt(nextId++, bookId, startedOn, endedOn, outcome)
        attempts += a
        rebuild(bookId)
        ServerReading(a.id, a.startedOn, a.endedOn, a.outcome)
    }

    override suspend fun setStatus(bookId: Long, status: String, startedAt: Patch<LocalDate>) = write {
        book(bookId)
        val active = attempts.firstOrNull { it.bookId == bookId && it.outcome == null }
        when (status) {
            "reading", "rereading", "on_hold" -> {
                if (ignoreStartReading) return@write
                if (active == null) {
                    val start = when (startedAt) {
                        Patch.Keep -> today
                        Patch.Clear -> null
                        is Patch.Set -> startedAt.value
                    }
                    attempts += Attempt(nextId++, bookId, start, null, null)
                } else if (startedAt !is Patch.Keep) {
                    active.startedOn = (startedAt as? Patch.Set)?.value
                    attemptEdits++
                }
                val completed = attempts.any { it.bookId == bookId && it.outcome == "completed" }
                statusRows[bookId] = if (status == "on_hold") "on_hold" else if (completed) "rereading" else "reading"
            }
            "want_to_read", "unread" -> {
                if (startedAt is Patch.Set) throw ApiException(400, "Reading dates conflict with the requested status")
                if (active != null) {
                    active.endedOn = today
                    active.outcome = "abandoned"
                    attemptEdits++
                }
                statusRows[bookId] = status
            }
            "read", "skimmed", "abandoned" -> {
                val outcome = mapOf("read" to "completed", "skimmed" to "skimmed", "abandoned" to "abandoned").getValue(status)
                if (active != null) {
                    active.endedOn = today
                    active.outcome = outcome
                    attemptEdits++
                } else if (latest(bookId)?.outcome != outcome) {
                    attempts += Attempt(nextId++, bookId, null, today, outcome)
                }
                statusRows[bookId] = status
            }
            else -> throw ApiException(400, "status must be one of the following values")
        }
    }

    override suspend fun ratingAccess(): RatingAccess = access

    override suspend fun setRating(bookId: Long, rating: Int) = write {
        val b = book(bookId)
        ratingCalls++
        if (!mayRate || access.rule(b.libraryId) != RatingRule.Allowed) throw ApiException(403, "Forbidden")
        require(rating in 1..5)
        ratings[bookId] = rating
    }

    private fun latest(bookId: Long): Attempt? = attempts.filter { it.bookId == bookId }
        .maxWithOrNull(compareBy<Attempt>({ (it.endedOn ?: it.startedOn) != null }, { it.endedOn ?: it.startedOn }, { it.id }))

    /** reading-attempt.service.ts rebuildProjection. */
    private fun rebuild(bookId: Long) {
        val active = attempts.firstOrNull { it.bookId == bookId && it.outcome == null }
        val current = statusRows[bookId]
        val completed = attempts.any { it.bookId == bookId && it.outcome == "completed" }
        val latest = active ?: latest(bookId)
        statusRows[bookId] = when {
            active != null -> if (current == "on_hold") "on_hold" else if (completed) "rereading" else "reading"
            current == "want_to_read" || current == "unread" -> current
            latest?.outcome == "completed" -> "read"
            latest?.outcome == "skimmed" -> "skimmed"
            latest?.outcome == "abandoned" -> "abandoned"
            else -> "unread"
        }
    }

    // --- uploads -----------------------------------------------------------------------------

    override suspend fun createUpload(body: CreateUploadBody): UploadSessionInfo = write {
        if (sessionsNeedAppAttest) throw ApiException(403, "This route requires App Attest", "APP_ATTEST_REQUIRED")
        require(Regex("^[A-Za-z0-9._:-]{8,100}$").matches(body.idempotencyKey)) { "bad key" }
        require(body.target.kind == "library")
        val existing = sessions[body.idempotencyKey]
        if (existing != null && existing.status in setOf("receiving", "processing", "completed")) {
            if (existing.filename != body.filename || existing.size != body.sizeBytes || existing.libraryId != body.target.libraryId) {
                throw ApiException(400, "Idempotency key is already used by a different upload")
            }
            return@write info(existing)
        }
        if (!canUpload || libraries.none { it.id == body.target.libraryId }) throw ApiException(403, "Library upload permission is required")
        val session = Session(UUID.randomUUID().toString(), body.idempotencyKey, body.filename, body.sizeBytes, body.target.libraryId, body.sha256)
        sessions[body.idempotencyKey] = session
        info(session)
    }

    override suspend fun uploadChunk(sessionId: String, offset: Long, bytes: ByteArray): UploadSessionInfo = write {
        val s = session(sessionId)
        if (s.status != "receiving") throw ApiException(409, "Upload session is ${s.status}")
        if (offset != s.data.size().toLong()) throw ApiException(409, "Expected upload offset ${s.data.size()}, received $offset")
        if (s.data.size() + bytes.size > s.size) throw ApiException(413, "Chunk exceeds the remaining upload size")
        s.data.write(bytes)
        info(s)
    }

    override suspend fun completeUpload(sessionId: String): UploadSessionInfo = write {
        val s = session(sessionId)
        if (s.status == "completed" || s.status == "processing") return@write info(s)
        if (s.data.size().toLong() != s.size) throw ApiException(400, "Upload is incomplete")
        val bytes = s.data.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (s.sha256 != null && sha != s.sha256) throw ApiException(400, "File checksum does not match")
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) throw ApiException(400, "File contents do not match the .epub format")
        val opf = readOpf(bytes)
        val book = Book(
            nextId++,
            title = Regex("<dc:title>(.*?)</dc:title>").find(opf)!!.groupValues[1].unescape(),
            authors = Regex("<dc:creator[^>]*>(.*?)</dc:creator>").findAll(opf).map { it.groupValues[1].unescape() }.toList(),
            isbn13 = Regex("urn:isbn:(\\d{13})").find(opf)?.groupValues?.get(1),
            libraryId = s.libraryId,
        )
        books += book
        s.bookId = book.id
        s.status = "processing"
        info(s)
    }

    override suspend fun uploadWhole(libraryId: Long, filename: String, contentType: String, bytes: ByteArray): Long = write {
        if (!canUpload || libraries.none { it.id == libraryId }) throw ApiException(403, "Library upload permission is required")
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) throw ApiException(400, "File contents do not match the .epub format")
        val opf = readOpf(bytes)
        val book = Book(
            nextId++,
            title = Regex("<dc:title>(.*?)</dc:title>").find(opf)!!.groupValues[1].unescape(),
            authors = Regex("<dc:creator[^>]*>(.*?)</dc:creator>").findAll(opf).map { it.groupValues[1].unescape() }.toList(),
            isbn13 = Regex("urn:isbn:(\\d{13})").find(opf)?.groupValues?.get(1),
            libraryId = libraryId,
        )
        books += book
        wholeUploads++
        book.id
    }

    override suspend fun upload(sessionId: String): UploadSessionInfo {
        val s = session(sessionId)
        if (s.status == "processing") s.status = "completed"
        return info(s)
    }

    private fun session(id: String) = sessions.values.firstOrNull { it.id == id } ?: throw ApiException(404, "Upload session not found")

    private fun info(s: Session) = UploadSessionInfo(s.id, s.status, s.size, s.data.size().toLong(), s.bookId.takeIf { s.status == "completed" || s.status == "processing" })

    private fun readOpf(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.endsWith(".opf")) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("no opf")
    }

    private fun String.unescape() = replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Everything the user would see, for comparing two servers. */
    fun snapshot(): List<String> = books.sortedBy { it.title }.map { b ->
        val readings = attempts.filter { it.bookId == b.id }.map { "${it.startedOn}~${it.endedOn}:${it.outcome}" }.sorted()
        "${b.title} | ${b.authors} | ${b.isbn13} | status=${statusRows[b.id]} | rating=${ratings[b.id]} | $readings"
    }
}
