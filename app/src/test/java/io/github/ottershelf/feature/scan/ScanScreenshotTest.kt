package io.github.ottershelf.feature.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.CurrentlyReadingBook
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.model.ReadingStreak
import io.github.ottershelf.feature.home.HomeContent
import io.github.ottershelf.feature.home.HomeUiState
import io.github.ottershelf.feature.home.ReadingRow
import io.github.ottershelf.feature.home.model.DEFAULT_WIDGETS
import io.github.ottershelf.feature.home.model.DashboardLayout
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scanner's states that don't need the camera running (Robolectric has none): the permission
 * panels, the camera's overlay over a stand-in picture, typing an ISBN, the chooser and "Not in your
 * library"; and the two ways in (the toolbar's action, the Currently Reading card's "Scan a book").
 * Written as scan/<state>_light.png and _dark.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class ScanScreenshotTest {

    private val dracula = Isbn.of13("9780306406157")!!

    @Test
    fun permission() = captureLightAndDark("scan/permission") {
        Scan(ScanUiState(), CameraUi(CameraAccess.Ask))
    }

    @Test
    fun permissionDenied() = captureLightAndDark("scan/permission_denied") {
        Scan(ScanUiState(forTimer = true), CameraUi(CameraAccess.Denied))
    }

    @Test
    fun cameraFailed() = captureLightAndDark("scan/camera_failed") {
        Scan(ScanUiState(), CameraUi(CameraAccess.Granted, failed = "Camera 0 is in use by another app"))
    }

    @Test
    fun camera() = captureLightAndDark("scan/camera") {
        Scan(ScanUiState(forTimer = true), CameraUi(CameraAccess.Granted, torchAvailable = true, torchOn = false))
    }

    @Test
    fun manualWithAProblem() = captureLightAndDark("scan/manual_problem") {
        Scan(ScanUiState(manual = ManualEntry("978-0-306-40615-8")), CameraUi(CameraAccess.Granted, torchAvailable = true, torchOn = true))
    }

    @Test
    fun manualIsbn10() = captureLightAndDark("scan/manual_x") {
        Scan(ScanUiState(manual = ManualEntry("0-8044-2957")), CameraUi(CameraAccess.Denied))
    }

    @Test
    fun manualValid() = captureLightAndDark("scan/manual_valid") {
        Scan(ScanUiState(manual = ManualEntry("0-306-40615-2")), CameraUi(CameraAccess.Granted))
    }

    @Test
    fun looking() = captureLightAndDark("scan/looking") {
        Scan(ScanUiState(result = ScanResult.Looking(dracula)), CameraUi(CameraAccess.Granted))
    }

    @Test
    fun chooser() = captureLightAndDark("scan/chooser") {
        Scan(
            ScanUiState(
                forTimer = true,
                result = ScanResult.Choose(
                    dracula,
                    listOf(
                        ScanBook(1, "Dracula", listOf("Bram Stoker"), listOf("epub", "pdf"), 1897, "reading", fakeCover(1)),
                        ScanBook(2, "Dracula (40th Anniversary Edition)", listOf("Bram Stoker"), listOf("epub"), 2005, null, fakeCover(4)),
                        ScanBook(3, "Dracula", listOf("Bram Stoker", "Florence Stoker"), listOf("m4b"), 2007, "read", null),
                    ),
                ),
            ),
            CameraUi(CameraAccess.Granted, torchAvailable = true),
        )
    }

    @Test
    fun notInLibrary() = captureLightAndDark("scan/not_in_library") {
        Scan(
            ScanUiState(
                result = ScanResult.Missing(
                    dracula,
                    lookingUp = false,
                    candidate = MetadataCandidate(
                        provider = "google",
                        title = "The Lost World",
                        subtitle = "50th Anniversary Edition",
                        authors = listOf("Arthur Conan Doyle"),
                        publishedYear = 2019,
                        seriesName = "Professor Challenger",
                        seriesIndex = 4.0,
                        coverUrl = fakeCover(2),
                    ),
                    editions = listOf(ScanBook(9, "The Lost World", listOf("Arthur Conan Doyle"), listOf("epub"), 1912, "want_to_read", fakeCover(3))),
                    canRequest = true,
                ),
            ),
            CameraUi(CameraAccess.Granted),
        )
    }

    @Test
    fun notInLibraryLookingUp() = captureLightAndDark("scan/not_in_library_looking") {
        Scan(ScanUiState(forTimer = true, result = ScanResult.Missing(dracula, lookingUp = true, canRequest = false)), CameraUi(CameraAccess.Granted))
    }

    @Test
    fun notInLibraryUnknown() = captureLightAndDark("scan/not_in_library_unknown") {
        Scan(ScanUiState(result = ScanResult.Missing(dracula, lookingUp = false, canRequest = true)), CameraUi(CameraAccess.Granted))
    }

    @Test
    fun failed() = captureLightAndDark("scan/failed") {
        Scan(ScanUiState(result = ScanResult.Failed(dracula, "Unable to resolve host \"books.example.net\"")), CameraUi(CameraAccess.Granted))
    }

    /** The Dashboard's toolbar (Scan beside Search) and the Currently Reading card's "Scan a book". */
    @Test
    fun entryPoints() = captureLightAndDark("scan/dashboard", heightDp = 620) {
        WithFakeCovers {
            RootFrame(
                title = "Dashboard",
                search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
                onOpenDrawer = {},
                onOpenSearch = {},
                onSubmitSearch = {},
                onCloseSearch = {},
                onScan = {},
            ) { padding ->
                HomeContent(
                    state = HomeUiState(
                        layout = DashboardLayout(DEFAULT_WIDGETS.map { it.copy(enabled = it.type == WidgetType.CURRENTLY_READING || it.type == WidgetType.READING_STREAK) }, null),
                        reading = listOf(
                            ReadingRow(CurrentlyReadingBook(1, "The Lost World", listOf("Arthur Conan Doyle"), 62.0, true, 11, "epub"), fakeCover(1)),
                            ReadingRow(CurrentlyReadingBook(2, "Frankenstein", listOf("Mary Shelley"), 18.0, true, 12, "epub"), fakeCover(2)),
                        ),
                        streak = ReadingStreak(currentStreak = 4, longestStreak = 12, lastSevenDays = listOf(false, true, false, true, true, true, true)),
                    ),
                    contentPadding = padding,
                    onScanForTimer = {},
                )
            }
        }
    }

    @Composable
    private fun Scan(state: ScanUiState, camera: CameraUi) {
        WithFakeCovers {
            ScanContent(state, camera, viewfinder = { FakeCamera(it) })
        }
    }

    /** A stand-in for the camera picture: a dim, warm blur, like a book cover under a lamp. */
    @Composable
    private fun FakeCamera(modifier: Modifier) {
        Box(
            modifier.background(
                Brush.linearGradient(listOf(Color(0xFF3B3026), Color(0xFF6B5846), Color(0xFF2A2520))),
            ),
        )
    }
}
