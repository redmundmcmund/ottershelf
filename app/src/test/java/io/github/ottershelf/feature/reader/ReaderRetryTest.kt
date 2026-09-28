package io.github.ottershelf.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When Retry asks the server again where to open. Plain JVM. */
class ReaderRetryTest {

    @Test
    fun anOpeningDecidedOfflineIsDecidedAgain() {
        assertTrue(decidesAgainOnRetry(decided = true, withServer = false, readSince = false, error = ReaderError.Offline))
        assertTrue(decidesAgainOnRetry(decided = true, withServer = false, readSince = false, error = ReaderError.Failed("502")))
        // The renderer was reclaimed and the page is being made again by itself.
        assertTrue(decidesAgainOnRetry(decided = true, withServer = false, readSince = false, error = null))
    }

    @Test
    fun otherwiseTheNewPageReopensWhereItWas() {
        // The server was asked.
        assertFalse(decidesAgainOnRetry(decided = true, withServer = true, readSince = false, error = ReaderError.Failed("502")))
        // Something was read: the checked push sorts out the server.
        assertFalse(decidesAgainOnRetry(decided = true, withServer = false, readSince = true, error = ReaderError.Offline))
        // The renderer crashed: the book had opened.
        assertFalse(decidesAgainOnRetry(decided = true, withServer = false, readSince = false, error = ReaderError.Stopped))
        // Still deciding, or never decided: openBook runs anyway.
        assertFalse(decidesAgainOnRetry(decided = false, withServer = false, readSince = false, error = ReaderError.Offline))
    }
}
