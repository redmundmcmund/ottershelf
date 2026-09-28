package io.github.ottershelf.feature.bookedit

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.network.ApiJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** The edit screen against a fake server: what's sent, what's kept on failure, what's announced. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class) // for android.net.Uri (the Photo Picker's answers)
class BookEditViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val book = BookDetail(
        id = 5,
        title = "A Study in Scarlet",
        authors = listOf(AuthorRef(1, "Arthur Conan Doyle")),
        seriesId = 3,
        seriesName = "Sherlock Holmes",
        seriesIndex = "1",
        coverSource = "extracted",
        files = listOf(BookFile(10, format = "epub", role = "primary")),
        updatedAt = "2026-09-01T00:00:00.000Z",
    )

    private class Fake(var book: BookDetail) : BookEditRemote {
        val saved = mutableListOf<JsonObject>()
        val locks = mutableListOf<List<String>>()
        val uploads = mutableListOf<ByteArray>()
        val fromUrl = mutableListOf<String>()
        var removed = 0
        var extracted = 0
        var extractFinds = true
        var saveError: Exception? = null
        var coverError: Exception? = null
        var loadError: Exception? = null
        var books = 0
        /** Holds the next `GET books/:id` back until completed. */
        var bookGate: CompletableDeferred<Unit>? = null

        override suspend fun book(bookId: Long): BookDetail {
            books++
            bookGate?.let { bookGate = null; it.await() }
            loadError?.let { throw it }
            return book
        }

        override suspend fun saveMetadata(bookId: Long, body: JsonObject): BookDetail {
            saveError?.let { throw it }
            saved += body
            val title = (body["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: book.title
            book = book.copy(title = title, updatedAt = "2026-09-26T10:00:00.000Z")
            return book
        }

        override suspend fun setLocks(bookId: Long, lockedFields: List<String>): BookDetail {
            locks += lockedFields
            book = book.copy(lockedFields = lockedFields)
            return book
        }

        override suspend fun authors(query: String) = listOf(AuthorSuggestion("Arthur Conan Doyle", 12))
        override suspend fun series(query: String) = listOf(SeriesSuggestion("Sherlock Holmes", 6, listOf("Arthur Conan Doyle")))
        override suspend fun coverProvider(): String? = "itunes"

        val searches = mutableListOf<String>()
        override suspend fun searchCovers(title: String, author: String?, audiobook: Boolean, provider: String): List<CoverResult> {
            searches += "$title|$author|$audiobook|$provider"
            return listOf(CoverResult("https://img.example.com/1.jpg", "preview", 1200, 1800, "Amazon"))
        }

        override suspend fun uploadCover(bookId: Long, jpeg: ByteArray) {
            coverError?.let { throw it }
            uploads += jpeg
            book = book.copy(coverSource = "custom", updatedAt = "2026-09-26T11:00:00.000Z")
        }

        override suspend fun coverFromUrl(bookId: Long, url: String) {
            coverError?.let { throw it }
            fromUrl += url
            book = book.copy(coverSource = "custom", updatedAt = "2026-09-26T12:00:00.000Z")
        }

        /** The server keeps a cover taken from the file, to show once the user's is removed. */
        var fileCoverKept = true

        override suspend fun removeCustomCover(bookId: Long): String? {
            removed++
            val left = if (fileCoverKept) "extracted" else null
            book = book.copy(coverSource = left, updatedAt = "2026-09-26T13:00:00.000Z")
            return left
        }

        override suspend fun extractCover(bookId: Long): Boolean {
            extracted++
            if (extractFinds) book = book.copy(coverSource = "extracted", updatedAt = "2026-09-26T14:00:00.000Z")
            return extractFinds
        }
    }

    private class Photos : CoverPhotos {
        val discarded = mutableListOf<String>()
        val picked = mutableListOf<Uri>()
        override suspend fun fromPicker(uri: Uri): PreparedCover {
            picked += uri
            return PreparedCover(File("picked.jpg"), 1000, 1500)
        }
        override suspend fun fromCamera(photo: File, frame: CameraFrame?) = PreparedCover(File("shot-ready.jpg"), 800, 1200)
        override suspend fun bytes(file: File) = byteArrayOf(9, 9)
        override fun discard(file: File) {
            discarded += file.path
        }
    }

    private data class Published(val book: BookDetail, val before: BookDetail?, val coverChanged: Boolean)

    private val published = mutableListOf<Published>()

    private fun vm(fake: Fake, canEdit: Boolean = true, saved: SavedStateHandle = SavedStateHandle(), photos: CoverPhotos = Photos()) = BookEditViewModel(
        bookId = 5,
        remote = fake,
        canEdit = canEdit,
        coverOf = { "thumb:${it.updatedAt}".takeIf { _ -> it.coverSource != null } },
        publish = { b, before, cover -> published += Published(b, before, cover) },
        photos = photos,
        saved = saved,
        debounceMs = 10,
    )

    @Test
    fun saveSendsOnlyWhatChangedAndAnnouncesTheSavedBook() = runTest(dispatcher) {
        val fake = Fake(book)
        val vm = vm(fake)
        advanceUntilIdle()
        vm.setTitle("A Study in Scarlet (50th)")
        assertTrue(vm.state.value.canSave)
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf("""{"title":"A Study in Scarlet (50th)"}"""), fake.saved.map { ApiJson.encodeToString(JsonObject.serializer(), it) })
        assertEquals(1, published.size)
        assertEquals("A Study in Scarlet (50th)", published.single().book.title)
        assertFalse(published.single().coverChanged)
        assertTrue(vm.state.value.done)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun nothingChangedCantBeSaved() = runTest(dispatcher) {
        val fake = Fake(book)
        val vm = vm(fake)
        advanceUntilIdle()
        vm.setTitle(" A Study in Scarlet ")
        assertFalse(vm.state.value.canSave)
        vm.save()
        advanceUntilIdle()
        assertTrue(fake.saved.isEmpty())
    }

    @Test
    fun offlineKeepsEverythingTypedWithAMessage() = runTest(dispatcher) {
        val fake = Fake(book).apply { saveError = IOException("Unable to resolve host") }
        val vm = vm(fake)
        advanceUntilIdle()
        vm.setSeries("Sherlock Holmes Novels")
        vm.setSeriesIndex("1.5")
        vm.save()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(EditError.Offline, s.saveError)
        assertEquals("Sherlock Holmes Novels", s.form.series)
        assertEquals("1.5", s.form.seriesIndex)
        assertFalse(s.done)
        assertTrue(s.canSave) // try again
        assertTrue(published.isEmpty())
    }

    @Test
    fun aRefusalIsWordedAsTheServerSaysOrAsForbidden() = runTest(dispatcher) {
        val fake = Fake(book).apply { saveError = ApiException(403, "Forbidden resource") }
        val vm = vm(fake)
        advanceUntilIdle()
        vm.setTitle("X")
        vm.save()
        advanceUntilIdle()
        assertEquals(EditError.Forbidden, vm.state.value.saveError)

        fake.saveError = ApiException(400, "title must be shorter than or equal to 1000 characters")
        vm.save()
        advanceUntilIdle()
        assertEquals(EditError.Server("title must be shorter than or equal to 1000 characters"), vm.state.value.saveError)
        assertEquals("X", vm.state.value.form.title)
    }

    @Test
    fun aFieldLockedMeanwhileShowsLockedAndTheRestCanBeSaved() = runTest(dispatcher) {
        val fake = Fake(book)
        val vm = vm(fake)
        advanceUntilIdle()
        vm.setTitle("New title")
        vm.setSeriesIndex("2")
        fake.saveError = ApiException(409, "Metadata fields are locked: title")
        fake.book = fake.book.copy(lockedFields = listOf("title"))
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.isLocked(LockGroup.TITLE))
        // The locked box shows what the title is, not what the user typed (it can't be saved).
        assertEquals("A Study in Scarlet", vm.state.value.form.title)
        assertEquals("2", vm.state.value.form.seriesIndex)
        assertEquals(setOf("seriesName", "seriesIndex"), vm.state.value.changes.body().keys)
        fake.saveError = null
        vm.save()
        advanceUntilIdle()
        assertEquals(setOf("seriesName", "seriesIndex"), fake.saved.single().keys)
    }

    @Test
    fun aNameLeftInTheAuthorBoxIsSavedToo() = runTest(dispatcher) {
        val fake = Fake(book)
        val vm = vm(fake)
        advanceUntilIdle()
        vm.setAuthorInput("Charles Doyle")
        assertTrue(vm.state.value.dirty)
        vm.save()
        advanceUntilIdle()
        assertEquals("""{"authors":["Arthur Conan Doyle","Charles Doyle"]}""", ApiJson.encodeToString(JsonObject.serializer(), fake.saved.single()))
    }

    @Test
    fun authorSuggestionsFollowTheBox() = runTest(dispatcher) {
        val vm = vm(Fake(book))
        advanceUntilIdle()
        vm.setAuthorInput("urs")
        advanceUntilIdle()
        assertEquals("urs", vm.state.value.authorSuggestions.query)
        assertEquals(listOf(AuthorSuggestion("Arthur Conan Doyle", 12)), vm.state.value.authorSuggestions.items)
        vm.addAuthor("Arthur Conan Doyle") // already there: nothing added
        advanceUntilIdle()
        assertEquals(listOf("Arthur Conan Doyle"), vm.state.value.form.authors)
        assertEquals("", vm.state.value.authorInput)
        assertEquals(Suggestions<AuthorSuggestion>(), vm.state.value.authorSuggestions)
    }

    @Test
    fun clearingTheSeriesClearsItsNumber() = runTest(dispatcher) {
        val vm = vm(Fake(book))
        advanceUntilIdle()
        vm.setSeries("")
        assertEquals("", vm.state.value.form.seriesIndex)
        assertEquals(SeriesChange(null, null), vm.state.value.changes.series)
    }

    @Test
    fun unlockingReadsTheLocksAfreshAndKeepsTheOthers() = runTest(dispatcher) {
        val fake = Fake(book.copy(lockedFields = listOf("seriesName", "seriesIndex")))
        val vm = vm(fake)
        advanceUntilIdle()
        assertTrue(vm.state.value.isLocked(LockGroup.SERIES))
        // Someone locked the cover since the screen opened: that lock stays.
        fake.book = fake.book.copy(lockedFields = listOf("seriesName", "seriesIndex", "cover"))
        vm.unlock(LockGroup.SERIES)
        advanceUntilIdle()
        assertEquals(listOf(listOf("cover")), fake.locks)
        assertFalse(vm.state.value.isLocked(LockGroup.SERIES))
        assertTrue(vm.state.value.isLocked(LockGroup.COVER))
    }

    @Test
    fun aCoverFoundOnlineIsSentAtOnceAndAnnounced() = runTest(dispatcher) {
        val fake = Fake(book)
        val vm = vm(fake)
        advanceUntilIdle()
        vm.setTitle("Sherlock Holmes") // the search uses what the user typed; the text stays unsaved
        vm.openCoverSearch()
        advanceUntilIdle()
        assertEquals(listOf("Sherlock Holmes|Arthur Conan Doyle|false|itunes"), fake.searches)
        val found = vm.state.value.coverSearch!!.results!!.single()
        vm.pickOnline(found)
        vm.confirmCover()
        advanceUntilIdle()
        assertEquals(listOf("https://img.example.com/1.jpg"), fake.fromUrl)
        val announced = published.single()
        assertTrue(announced.coverChanged)
        assertEquals("2026-09-26T12:00:00.000Z", announced.book.updatedAt)
        assertEquals("2026-09-01T00:00:00.000Z", announced.before?.updatedAt)
        val s = vm.state.value
        assertEquals("thumb:2026-09-26T12:00:00.000Z", s.cover)
        assertNull(s.pendingCover)
        assertNull(s.coverSearch)
        assertEquals(CoverMessage.Updated, s.coverMessage)
        assertTrue(s.dirty) // the title is still the user's to save
        assertEquals("Sherlock Holmes", s.form.title)
    }

    @Test
    fun aPhotoIsConfirmedThenUploadedAndItsFileRemoved() = runTest(dispatcher) {
        val photos = Photos()
        val fake = Fake(book)
        val vm = vm(fake, photos = photos)
        advanceUntilIdle()
        vm.photoTaken(File("shot.jpg"), frame = null)
        advanceUntilIdle()
        assertEquals(PendingCover.Photo("shot-ready.jpg", 800, 1200), vm.state.value.pendingCover)
        assertTrue(fake.uploads.isEmpty()) // not before the user says so
        vm.confirmCover()
        advanceUntilIdle()
        assertEquals(listOf(listOf<Byte>(9, 9)), fake.uploads.map { it.toList() })
        assertEquals(listOf("shot-ready.jpg"), photos.discarded)
        assertTrue(published.single().coverChanged)
    }

    @Test
    fun aCoverLockedMeanwhileEndsTheChoiceAndShowsWhyBesideUnlock() = runTest(dispatcher) {
        val fake = Fake(book)
        val vm = vm(fake)
        advanceUntilIdle()
        vm.openCoverSearch()
        advanceUntilIdle()
        vm.pickOnline(vm.state.value.coverSearch!!.results!!.single())
        // Someone locked the cover since the screen opened: cover/from-url answers 409.
        fake.coverError = ApiException(409, "Metadata fields are locked: cover")
        fake.book = fake.book.copy(lockedFields = listOf("cover"))
        vm.confirmCover()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.isLocked(LockGroup.COVER))
        // No "Try again" that can't work: the confirmation and the search are gone...
        assertNull(s.pendingCover)
        assertNull(s.coverSearch)
        // ... and the cover card says why, where Unlock is.
        assertEquals(CoverMessage.Failed(EditError.Server("Metadata fields are locked: cover")), s.coverMessage)
        assertTrue(published.isEmpty())
        // Unlocked: the reason goes with the lock, and the cover can be changed again.
        fake.coverError = null
        vm.unlock(LockGroup.COVER)
        advanceUntilIdle()
        assertNull(vm.state.value.coverMessage)
        assertTrue(vm.state.value.coverActionsEnabled)
    }

    @Test
    fun aPhotoRefusedBecauseTheCoverWasLockedIsDiscarded() = runTest(dispatcher) {
        val photos = Photos()
        val fake = Fake(book)
        val vm = vm(fake, photos = photos)
        advanceUntilIdle()
        vm.photoTaken(File("shot.jpg"), frame = null)
        advanceUntilIdle()
        fake.coverError = ApiException(409, "Metadata fields are locked: cover")
        fake.book = fake.book.copy(lockedFields = listOf("cover"))
        vm.confirmCover()
        advanceUntilIdle()
        assertNull(vm.state.value.pendingCover)
        assertEquals(listOf("shot-ready.jpg"), photos.discarded)
    }

    @Test
    fun aConflictThatIsntTheCoversLockKeepsTheChoiceToTryAgain() = runTest(dispatcher) {
        val fake = Fake(book)
        val vm = vm(fake)
        advanceUntilIdle()
        vm.photoTaken(File("shot.jpg"), frame = null)
        advanceUntilIdle()
        fake.coverError = ApiException(409, "Conflict")
        vm.confirmCover()
        advanceUntilIdle()
        assertTrue(vm.state.value.pendingCover is PendingCover.Photo)
    }

    private val pickedUri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/42")

    @Test
    fun aPhotoPickedWhileTheBookIsStillLoadingIsMadeReadyOnceItHas() = runTest(dispatcher) {
        // The process was killed while the Photo Picker was open: the screen is rebuilt, loading
        // the book again, when the photo the user picked comes back.
        val gate = CompletableDeferred<Unit>()
        val fake = Fake(book).apply { bookGate = gate }
        val photos = Photos()
        val vm = vm(fake, photos = photos)
        runCurrent()
        assertNull(vm.state.value.book)
        vm.photoPicked(pickedUri)
        runCurrent()
        assertTrue(photos.picked.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(pickedUri), photos.picked)
        assertEquals(PendingCover.Photo("picked.jpg", 1000, 1500), vm.state.value.pendingCover)
        assertTrue(fake.uploads.isEmpty()) // still the user's to confirm
    }

    @Test
    fun aPhotoPickedBeforeAFailedLoadIsMadeReadyAfterTheRetry() = runTest(dispatcher) {
        val photos = Photos()
        val gate = CompletableDeferred<Unit>()
        val fake = Fake(book).apply { loadError = IOException("offline"); bookGate = gate }
        val vm = vm(fake, photos = photos)
        runCurrent()
        vm.photoPicked(pickedUri)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(EditError.Offline, vm.state.value.loadError)
        assertNull(vm.state.value.pendingCover)
        fake.loadError = null
        vm.load()
        advanceUntilIdle()
        assertEquals(PendingCover.Photo("picked.jpg", 1000, 1500), vm.state.value.pendingCover)
    }

    @Test
    fun aPhotoPickedWhileLoadingABookWhoseCoverIsLockedIsntOffered() = runTest(dispatcher) {
        val photos = Photos()
        val gate = CompletableDeferred<Unit>()
        val fake = Fake(book.copy(lockedFields = listOf("cover"))).apply { bookGate = gate }
        val vm = vm(fake, photos = photos)
        runCurrent()
        vm.photoPicked(pickedUri)
        gate.complete(Unit)
        advanceUntilIdle()
        // The cover card says it's locked, with Unlock.
        assertTrue(vm.state.value.isLocked(LockGroup.COVER))
        assertNull(vm.state.value.pendingCover)
        assertTrue(photos.picked.isEmpty())
    }

    @Test
    fun aFailedUploadKeepsTheChoiceWithTheReason() = runTest(dispatcher) {
        val fake = Fake(book).apply { coverError = IOException("offline") }
        val vm = vm(fake)
        advanceUntilIdle()
        vm.photoTaken(File("shot.jpg"), frame = null)
        advanceUntilIdle()
        vm.confirmCover()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.pendingCover is PendingCover.Photo)
        assertEquals(CoverMessage.Failed(EditError.Offline), s.coverMessage)
        assertTrue(published.isEmpty())
        fake.coverError = null
        vm.confirmCover()
        advanceUntilIdle()
        assertEquals(1, fake.uploads.size)
    }

    @Test
    fun theFilesCoverReplacesTheUsersOnlyWhenConfirmed() = runTest(dispatcher) {
        val fake = Fake(book.copy(coverSource = "custom"))
        val vm = vm(fake)
        advanceUntilIdle()
        assertEquals(FromFile.RESTORE, vm.state.value.fromFile)
        vm.useFileCover()
        assertEquals(PendingCover.FromFileCover, vm.state.value.pendingCover)
        assertEquals(0, fake.removed)
        vm.confirmCover()
        advanceUntilIdle()
        assertEquals(1, fake.removed)
        assertEquals(null, vm.state.value.fromFile) // it's the file's cover now
        assertEquals(CoverMessage.Updated, vm.state.value.coverMessage)
    }

    @Test
    fun removingTheUsersCoverFromABookWhoseFileHasNoneSaysItHasNoCoverNow() = runTest(dispatcher) {
        // A PDF, or an EPUB without a cover: nothing from the file to fall back on.
        val fake = Fake(book.copy(coverSource = "custom")).apply { fileCoverKept = false }
        val vm = vm(fake)
        advanceUntilIdle()
        vm.useFileCover()
        vm.confirmCover()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(CoverMessage.NoCoverLeft, s.coverMessage)
        assertNull(s.cover) // the generated one
        assertNull(s.pendingCover)
        assertEquals(null, published.single().book.coverSource)
        assertEquals(FromFile.EXTRACT, s.fromFile) // the file can be looked in again
    }

    @Test
    fun aBookWithoutACoverLooksInItsFileAndSaysWhenThereIsNone() = runTest(dispatcher) {
        val fake = Fake(book.copy(coverSource = null)).apply { extractFinds = false }
        val vm = vm(fake)
        advanceUntilIdle()
        assertEquals(FromFile.EXTRACT, vm.state.value.fromFile)
        vm.useFileCover()
        advanceUntilIdle()
        assertEquals(1, fake.extracted)
        assertEquals(CoverMessage.NoneInFile, vm.state.value.coverMessage)
        assertTrue(published.isEmpty())
    }

    @Test
    fun aLockedCoverCantBeChanged() = runTest(dispatcher) {
        val fake = Fake(book.copy(lockedFields = listOf("cover")))
        val vm = vm(fake)
        advanceUntilIdle()
        assertFalse(vm.state.value.coverActionsEnabled)
        vm.photoTaken(File("shot.jpg"), frame = null)
        vm.openCoverSearch()
        advanceUntilIdle()
        assertNull(vm.state.value.pendingCover)
        assertNull(vm.state.value.coverSearch)
    }

    @Test
    fun anEditSurvivesTheProcessBeingKilled() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = vm(Fake(book), saved = saved)
        advanceUntilIdle()
        first.setTitle("Half typed")
        first.setAuthorInput("Charles V")
        // Meanwhile the server's title changed; the edit is still judged against what the user started from.
        val second = vm(Fake(book.copy(title = "Renamed on the web")), saved = saved)
        advanceUntilIdle()
        val s = second.state.value
        assertEquals("Half typed", s.form.title)
        assertEquals("Charles V", s.authorInput)
        assertEquals("A Study in Scarlet", s.original?.title)
    }

    @Test
    fun anAccountThatCantEditIsntLoaded() = runTest(dispatcher) {
        val fake = Fake(book)
        val vm = vm(fake, canEdit = false)
        advanceUntilIdle()
        assertEquals(0, fake.books)
        assertFalse(vm.state.value.canSave)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun aFailedLoadCanBeRetried() = runTest(dispatcher) {
        val fake = Fake(book).apply { loadError = IOException("offline") }
        val vm = vm(fake)
        advanceUntilIdle()
        assertEquals(EditError.Offline, vm.state.value.loadError)
        fake.loadError = null
        vm.load()
        advanceUntilIdle()
        assertEquals("A Study in Scarlet", vm.state.value.form.title)
        assertNull(vm.state.value.loadError)
    }
}
