package io.github.ottershelf.core.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** ISO-8601 instants as the server sends and takes them. */
object IsoTime {

    // What the web client sends (`Date.toISOString()`): always milliseconds, always `Z`.
    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

    /** Epoch milliseconds of [value] (`...Z`, or with an offset, with or without fractions), or null. */
    fun parse(value: String?): Long? {
        if (value.isNullOrEmpty()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** `2026-09-24T18:30:00.000Z` for [epochMillis]. */
    fun format(epochMillis: Long): String = FORMAT.format(Instant.ofEpochMilli(epochMillis))
}
