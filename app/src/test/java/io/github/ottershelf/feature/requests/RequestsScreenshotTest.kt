package io.github.ottershelf.feature.requests

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.core.model.BookRequestAvailability
import io.github.ottershelf.core.model.BookRequestItem
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.model.RequestDownload
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Book requests, dark theme: requests/search_dark.png (results) and requests/mine_dark.png. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class RequestsScreenshotTest {

    @Test
    fun search() = captureDark("requests/search") {
        RequestsContent(
            RequestsUiState(
                title = "Sherlock Holmes",
                author = "Conan Doyle",
                searchStatus = SearchStatus.Found(3),
                providerLabels = mapOf("openlibrary" to "Open Library", "google" to "Google Books"),
                serverDefaultName = "Books",
                works = listOf(
                    work("A Study in Scarlet", 1887, cover = fakeCover(1)),
                    work("The Sign of the Four", 1890, cover = fakeCover(2), availability = BookRequestAvailability(ownedBookId = 7)),
                    work("The Adventures of Sherlock Holmes", 1892, availability = BookRequestAvailability(existingRequestId = 3, existingRequestStatus = "approved")),
                ),
            ),
        )
    }

    @Test
    fun mine() = captureDark("requests/mine") {
        RequestsContent(
            RequestsUiState(
                tab = RequestsTab.Mine,
                meId = 1,
                requestsLoaded = true,
                requests = listOf(
                    request(1, "Frankenstein", "downloading", RequestDownload(id = 9, status = "downloading", progressPercent = 42.0), cover = fakeCover(3)),
                    request(2, "The First Men in the Moon", "pending"),
                    request(3, "Bleak House", "available", matched = 12, cover = fakeCover(4)),
                    request(4, "An Unfindable Book", "failed", reason = "No release matched the title and author."),
                ),
            ),
        )
    }

    private fun work(title: String, year: Int, cover: String? = null, availability: BookRequestAvailability? = null): RequestWork {
        val c = MetadataCandidate(provider = "openlibrary", providerId = title, title = title, authors = listOf("Arthur Conan Doyle"), publishedYear = year, coverUrl = cover)
        return RequestWork(key = title, best = c, members = listOf(c, c.copy(provider = "google")), year = year, availability = availability)
    }

    private fun request(id: Long, title: String, status: String, download: RequestDownload? = null, matched: Long? = null, cover: String? = null, reason: String? = null) =
        BookRequestItem(
            id = id,
            userId = 1,
            title = title,
            authors = listOf("Some Author"),
            publishedYear = 2020,
            status = status,
            statusReason = reason,
            coverUrl = cover,
            targetLibraryName = "Books",
            matchedBookId = matched,
            download = download,
        )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDark(name: String, content: @Composable () -> Unit) = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                WithFakeCovers { content() }
            }
        }
    }
}
