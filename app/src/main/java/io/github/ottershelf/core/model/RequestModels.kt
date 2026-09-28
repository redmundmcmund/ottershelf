package io.github.ottershelf.core.model

import kotlinx.serialization.Serializable
import java.text.Normalizer

// Book Requests (server module book-request, types in packages/types/src/book-request.ts).

@Serializable
data class MetadataProviderInfo(val key: String, val label: String? = null)

@Serializable
data class MetadataCandidate(
    val provider: String,
    val providerId: String? = null,
    val title: String? = null,
    val displayTitle: String? = null,
    val subtitle: String? = null,
    val authors: List<String>? = null,
    val publishedYear: Int? = null,
    val language: String? = null,
    val isbn10: String? = null,
    val isbn13: String? = null,
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
    val coverUrl: String? = null,
) {
    val shownTitle: String? get() = displayTitle?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }
}

@Serializable
data class AvailabilityItem(
    val title: String,
    val mediaKind: String,
    val author: String? = null,
    val isbn13: String? = null,
    val providerKey: String? = null,
    val providerId: String? = null,
)

@Serializable
data class AvailabilityQuery(val items: List<AvailabilityItem>)

@Serializable
data class BookRequestAvailability(
    val ownedBookId: Long? = null,
    val existingRequestId: Long? = null,
    val existingRequestStatus: String? = null,
    val alreadySubscribed: Boolean = false,
)

@Serializable
data class MetadataSource(
    val providerKey: String,
    val providerId: String,
    val providerLabel: String,
    val isbn10: String? = null,
    val isbn13: String? = null,
)

@Serializable
data class CreateBookRequest(
    val title: String,
    val mediaKind: String,
    val subtitle: String? = null,
    val authors: List<String>? = null,
    val seriesName: String? = null,
    val seriesIndex: Int? = null,
    val isbn10: String? = null,
    val isbn13: String? = null,
    val publishedYear: Int? = null,
    val coverUrl: String? = null,
    val providerKey: String? = null,
    val providerId: String? = null,
    val metadataSources: List<MetadataSource>? = null,
    val preferredFormats: List<String>? = null,
    val note: String? = null,
    val targetLibraryId: Long? = null,
)

@Serializable
data class RequestDownload(
    val id: Long,
    val status: String,
    val progressPercent: Double? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
)

@Serializable
data class RequestSubscriber(val userId: Long, val username: String? = null, val name: String? = null)

@Serializable
data class BookRequestItem(
    val id: Long,
    val userId: Long,
    val requesterUsername: String? = null,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val publishedYear: Int? = null,
    val mediaKind: String = "ebook",
    val status: String,
    val statusReason: String? = null,
    val failureCode: String? = null,
    val note: String? = null,
    val decisionNote: String? = null,
    val coverUrl: String? = null,
    val targetLibraryName: String? = null,
    val matchedBookId: Long? = null,
    val download: RequestDownload? = null,
    val subscribers: List<RequestSubscriber> = emptyList(),
    val dismissed: Boolean = false,
    val createdAt: String? = null,
)

@Serializable
data class BookRequestPage(val items: List<BookRequestItem>, val total: Int)

@Serializable
data class BookRequestSubmitResult(val request: BookRequestItem, val subscribed: Boolean = false)

@Serializable
data class BookRequestSummary(val mine: Int = 0, val mineTotal: Int = 0)

@Serializable
data class RequestDestination(val libraryId: Long? = null, val libraryName: String? = null, val folderId: Long? = null)

object RequestStatus {
    private val ACTIVE = setOf("pending", "approved", "searching", "grabbed", "downloading", "importing", "needs_review")
    private val SETTLED = setOf("rejected", "cancelled", "available", "failed")

    fun isActive(status: String) = status in ACTIVE
    fun isCancellable(status: String) = status in ACTIVE || status == "failed"
    fun isSettled(status: String) = status in SETTLED

    fun label(status: String) = when (status) {
        "needs_review" -> "Needs review"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    /** ARGB colour for the status text, following the web badge tones. */
    fun color(status: String): Int = when (status) {
        "available" -> 0xFF5FD38D.toInt()                              // done
        "rejected", "failed" -> 0xFFFF7B7B.toInt()                     // failed
        "cancelled" -> 0xFF9A9CA6.toInt()                              // stopped
        "pending", "needs_review" -> 0xFFE6B450.toInt()                // waiting
        else -> 0xFF6C8CFF.toInt()                                     // in progress
    }
}

/**
 * Mirrors `normalizeWorkToken`/`bookRequestWorkKey` from packages/types, so search results collapse
 * into the same works the server would fold into one request.
 */
object WorkKey {
    private val DIACRITICS = Regex("[\\u0300-\\u036f]")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    fun token(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD)
            .replace(DIACRITICS, "")
            .replace(NON_ALNUM, "")

    fun of(title: String, author: String?, mediaKind: String): String =
        "work:${token(title).ifEmpty { title.trim().lowercase() }}:${token(author.orEmpty())}:$mediaKind"
}
