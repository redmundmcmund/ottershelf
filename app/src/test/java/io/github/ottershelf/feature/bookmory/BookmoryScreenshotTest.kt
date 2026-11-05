package io.github.ottershelf.feature.bookmory

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Import from Bookmory: the preview and the summary of the synthetic export against the fake server.
 * Record with `./gradlew recordRoborazziDebug --tests "*BookmoryScreenshotTest"`; the PNGs land in
 * app/build/outputs/roborazzi/bookmory/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class BookmoryScreenshotTest {

    private val file = BookmoryCsv.parse(sampleCsv())

    private fun server() = FakeBookOrbit().apply {
        addBook("Quiet Harbours", listOf("Mara Quill"), isbn13 = "9780000000019")
        addAttempt(addBook("Paper Moons", listOf("Odile Marsh")).id, LocalDate.of(2019, 1, 1), LocalDate.of(2019, 1, 22), "completed")
        addBook("Saltwater Atlas", listOf("Nico Pell"))
        addBook("Saltwater Atlas", listOf("Nico Pell"), subtitle = "Maps of the Coast")
    }

    private fun previewState(server: FakeBookOrbit, choices: ImportChoices): BookmoryUiState = runBlocking {
        val analysis = analyse(file, server, BookmoryImporter(server, pause = {}))
        BookmoryUiState(
            step = ImportStep.PREVIEW,
            preview = ImportPreviews.preview(analysis, choices),
            libraries = analysis.libraries + UploadLibrary(7, "Placeholders"),
            canUpload = true,
            choices = choices,
        )
    }

    @Test
    fun intro() = captureLightAndDark("bookmory/intro") {
        WithFakeCovers { BookmoryImportContent(BookmoryUiState()) }
    }

    @Test
    fun preview() {
        val state = previewState(server(), ImportChoices(libraryId = 1, undated = setOf(LocalDate.of(2018, 1, 12))))
        captureLightAndDark("bookmory/preview", heightDp = 1800) {
            WithFakeCovers { BookmoryImportContent(state, initiallyShowRows = true, initiallyOpenRow = file.rows.single { it.title == "Saltwater Atlas" }.line) }
        }
    }

    @Test
    fun ratingsLeftOut() {
        val server = server().apply {
            books.replaceAll { if (it.title == "Paper Moons") it.copy(libraryId = 3) else it }
            access = RatingAccess(editableLibraries = setOf(1), libraryNames = mapOf(3L to "Shared shelf"))
        }
        val state = previewState(server, ImportChoices(libraryId = 1))
        captureLightAndDark("bookmory/ratings_library", heightDp = 1800) {
            WithFakeCovers { BookmoryImportContent(state, initiallyShowRows = true) }
        }
        val none = previewState(server().apply { access = RatingAccess(permitted = false) }, ImportChoices(libraryId = 1))
        captureLightAndDark("bookmory/ratings_not_permitted") {
            WithFakeCovers { BookmoryImportContent(none) }
        }
    }

    @Test
    fun nothingNew() {
        val server = server()
        runBlocking {
            val importer = BookmoryImporter(server, pause = {})
            val analysis = analyse(file, server, importer)
            val line = file.rows.single { it.title == "Saltwater Atlas" }.line
            importer.run(ImportPreviews.preview(analysis, ImportChoices(libraryId = 1, rows = mapOf(line to RowChoice.Exclude))).items)
        }
        val line = file.rows.single { it.title == "Saltwater Atlas" }.line
        val state = previewState(server, ImportChoices(libraryId = 1, rows = mapOf(line to RowChoice.Exclude)))
        captureLightAndDark("bookmory/nothing_new") {
            WithFakeCovers { BookmoryImportContent(state) }
        }
    }

    @Test
    fun running() = captureLightAndDark("bookmory/running") {
        WithFakeCovers { BookmoryImportContent(BookmoryUiState(step = ImportStep.RUNNING, done = 42, total = 237)) }
    }

    @Test
    fun summary() {
        val summary = ImportSummary(
            rows = 15, rowsDone = 15,
            stubsCreated = listOf("The \"Lantern\" Keeper", "Never Quite Finished: a long and winding subtitle about many things that go on and...", "I Haven't Slept in Weeks", "Orchard of Glass"),
            stubsFound = 1, readingsAdded = 19, readingsPresent = 1, statusesSet = 3, ratingsSet = 6,
            problems = listOf(
                ImportProblem(5, "Cold Iron Stars", ImportProblem.Kind.STUB_DELETED),
                ImportProblem(9, "Fern & Feather", ImportProblem.Kind.WRITE_FAILED, "endedOn must be on or after startedOn"),
            ),
            interrupted = false,
        )
        captureLightAndDark("bookmory/summary", heightDp = 1400) {
            WithFakeCovers { BookmoryImportContent(BookmoryUiState(step = ImportStep.DONE, summary = summary)) }
        }
    }

    @Test
    fun interrupted() {
        val summary = ImportSummary(rows = 15, rowsDone = 6, stubsCreated = listOf("Orchard of Glass"), stubsFound = 0, readingsAdded = 7, readingsPresent = 0, statusesSet = 1, ratingsSet = 2, problems = emptyList(), interrupted = true)
        captureLightAndDark("bookmory/interrupted") {
            WithFakeCovers { BookmoryImportContent(BookmoryUiState(step = ImportStep.DONE, summary = summary)) }
        }
    }
}
