package io.github.ottershelf.core.tracking

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One active stretch of a timer (epoch milliseconds): from a start or resume to a pause or stop. */
@Serializable
data class ActiveSpan(val startMs: Long, val endMs: Long)

/** A part of a timed session as it is sent: its own id, span and active time. */
internal data class TimedPart(val id: String, val startMs: Long, val endMs: Long, val activeSeconds: Int)

/**
 * Splits a timed session where a pause crosses midnight in the account's timezone. The server
 * spreads a session's active time evenly over its wall-clock span (splitReadingSessionByDay), so
 * 30 minutes read on each of two evenings, sent as one session, would land almost all on the
 * second day. Each day's stretches become their own part (first start to last end, their active
 * time); a stretch read straight through midnight stays whole (spreading it is right). Pure;
 * tested in TrackingRepositoryTest.
 */
internal object TimedParts {

    /**
     * The parts of the session [sessionId]. The last part keeps [sessionId] (a session that isn't
     * split is sent exactly as before); earlier parts get ids derived from it, so saving the same
     * timer again sends the same parts. [spans] that don't add up to [activeSeconds] (a timer
     * started before stretches were recorded) aren't split.
     */
    fun split(
        sessionId: String,
        startedAtMs: Long,
        endedAtMs: Long,
        activeSeconds: Int,
        spans: List<ActiveSpan>,
        zone: ZoneId,
    ): List<TimedPart> {
        val whole = listOf(TimedPart(sessionId, startedAtMs, endedAtMs, activeSeconds))
        val stretches = spans.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (stretches.size < 2) return whole
        if (kotlin.math.abs(stretches.sumOf { it.endMs - it.startMs } / 1000 - activeSeconds) > 1) return whole

        fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val groups = mutableListOf(mutableListOf(stretches.first()))
        for (s in stretches.drop(1)) {
            if (day(groups.last().last().endMs) != day(s.startMs)) groups += mutableListOf(s) else groups.last() += s
        }
        // A part under the server's minimum would be dropped: it joins its neighbour instead.
        fun seconds(g: List<ActiveSpan>) = g.sumOf { it.endMs - it.startMs } / 1000
        while (groups.size > 1) {
            val short = groups.indexOfFirst { seconds(it) < TrackingRepository.MIN_SESSION_SECONDS }
            if (short < 0) break
            val into = if (short < groups.lastIndex) short + 1 else short - 1
            val merged = (groups[short] + groups[into]).sortedBy { it.startMs }.toMutableList()
            groups[minOf(short, into)] = merged
            groups.removeAt(maxOf(short, into))
        }
        if (groups.size == 1) return whole
        return groups.mapIndexed { i, g ->
            TimedPart(
                id = if (i == groups.lastIndex) sessionId else UUID.nameUUIDFromBytes("$sessionId/$i".toByteArray()).toString(),
                startMs = g.first().startMs,
                endMs = g.last().endMs,
                activeSeconds = seconds(g).toInt(),
            )
        }
    }
}
