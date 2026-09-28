package io.github.ottershelf.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsoTimeTest {

    @Test
    fun parsesWhatTheServerSends() {
        assertEquals(1_790_000_000_123L, IsoTime.parse("2026-09-21T14:13:20.123Z"))
        assertEquals(1_790_000_000_000L, IsoTime.parse("2026-09-21T14:13:20Z"))
        assertEquals(1_790_000_000_000L, IsoTime.parse("2026-09-21T16:13:20+02:00"))
        assertEquals(1_790_000_000_123L, IsoTime.parse("2026-09-21T14:13:20.123456Z"))
    }

    @Test
    fun rejectsEmptyAndGarbage() {
        assertNull(IsoTime.parse(null))
        assertNull(IsoTime.parse(""))
        assertNull(IsoTime.parse("yesterday"))
    }

    @Test
    fun formatsLikeTheWebClient() {
        assertEquals("2026-09-21T14:13:20.000Z", IsoTime.format(1_790_000_000_000L))
        assertEquals("2026-09-21T14:13:20.123Z", IsoTime.format(1_790_000_000_123L))
    }
}
