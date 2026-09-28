package io.github.ottershelf.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.ottershelf.core.network.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The readers' session body against the server's SaveReadingSessionDto. */
class ReadingSessionTest {

    /** SaveReadingSessionDto's fields: the server rejects any other. */
    private val dtoFields = setOf("sessionId", "startedAt", "endedAt", "durationSeconds", "progressDelta", "endProgress", "sessionType", "source")

    @Test
    fun sessionsFromTheReadersAreRecordedAsAndroid() {
        val body = ReadingSession("s1", "2026-09-26T10:00:00Z", "2026-09-26T10:20:00Z", 1_200, 0.05, 42.0)
        val json = ApiJson.encodeToJsonElement(ReadingSession.serializer(), body) as JsonObject
        assertTrue(json.keys.toString(), dtoFields.containsAll(json.keys))
        // One of CLIENT_READING_SESSION_SOURCES; left out, the server stores 'web'.
        assertEquals("android", json["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSessionQueuedBeforeTheSourceExistedIsSentAsAndroid() {
        val queued = """{"sessionId":"s1","startedAt":"2026-09-26T10:00:00Z","endedAt":"2026-09-26T10:20:00Z","durationSeconds":1200,"endProgress":42.0}"""
        assertEquals("android", ApiJson.decodeFromString(ReadingSession.serializer(), queued).source)
    }
}
