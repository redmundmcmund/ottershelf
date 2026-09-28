package io.github.ottershelf.feature.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The reader's one `GET books/:id` per open, shared by the authors and the read status. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedRequestTest {

    private var calls = 0
    private var answer = CompletableDeferred<String?>()

    private suspend fun fetch(): String? {
        calls++
        return answer.await()
    }

    @Test
    fun bothAskersShareOneRequest() = runTest {
        val detail = SharedRequest(backgroundScope, ::fetch)
        val authors = detail.get()
        runCurrent()
        val status = detail.get() // openBook, while the authors' request is still out

        answer.complete("book")
        assertEquals("book", authors.await())
        assertEquals("book", status.await())
        assertEquals("book", detail.get().await()) // and later ones get the same answer
        assertEquals(1, calls)
    }

    @Test
    fun aFailedRequestIsAskedAgain() = runTest {
        val detail = SharedRequest(backgroundScope, ::fetch)
        answer.complete(null)
        assertNull(detail.get().await())

        answer = CompletableDeferred("book")
        assertEquals("book", detail.get().await())
        assertEquals(2, calls)
    }

    @Test
    fun resetAsksAgain() = runTest {
        val detail = SharedRequest(backgroundScope, ::fetch)
        answer.complete("before")
        assertEquals("before", detail.get().await())

        detail.reset() // Retry decides where to open again
        answer = CompletableDeferred("after")
        assertEquals("after", detail.get().await())
        assertEquals(2, calls)
    }
}
