package io.github.ottershelf.feature.stats

import kotlinx.serialization.builtins.ListSerializer
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.feature.stats.model.CompletionLatency
import io.github.ottershelf.feature.stats.model.DailyReadingStat
import io.github.ottershelf.feature.stats.model.FormatStorage
import io.github.ottershelf.feature.stats.model.Overview
import io.github.ottershelf.feature.stats.model.PeakHourStat
import io.github.ottershelf.feature.stats.model.ProgressFunnelComparison
import io.github.ottershelf.feature.stats.model.StatisticsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The server's shapes (user-statistics.service.ts, statistics.service.ts) decode with ApiJson. */
class StatsModelsTest {

    @Test
    fun activityOverview() {
        val json = """
            {"generatedAt":"2026-09-25T08:00:00.000Z","timezone":"Europe/Dublin","libraryIds":[],"achievementsEnabled":true,
             "snapshot":{"today":{"totalSeconds":1500,"readingSeconds":1500,"listeningSeconds":0},
               "lastSevenDays":{"totalSeconds":19000,"readingSeconds":19000,"listeningSeconds":0},
               "previousSevenDays":{"totalSeconds":15000,"readingSeconds":14000,"listeningSeconds":1000},
               "currentStreak":6,"longestStreak":21,"completedBooksYtd":15},
             "dailyActivity":{"days":[{"day":"2026-09-25","totalSeconds":1500,"readingSeconds":1500,"listeningSeconds":0,"sessionsCount":2,
               "bySource":{"web":0,"android":1500}}]},
             "calendar":{"year":2026,"availableYears":[2025,2026],"days":[]},
             "goal":{"year":2026,"goalBooks":null,"completedBooks":15,"projectedBooks":20.4,"status":null,
               "points":[{"month":1,"actualCumulative":1,"targetCumulative":null}]},
             "rhythm":{"windowDays":365,"sessionsCount":240,"weekdays":[{"dayOfWeek":0,"averageSeconds":2400,"averageReadingSeconds":2400,
               "averageListeningSeconds":0,"sessionsCount":30,"averageBySource":{"web":2400}}],"hours":[],"favoriteDayOfWeek":0,"peakHour":21},
             "completion":{"availableSince":null,"months":[],"funnel":{"days":365,"current":{"started":1,"reached25":1,"reached50":1,"reached75":1,"completed":1},"previous":null}},
             "sources":{"windowDays":365,"totals":{"totalSeconds":0,"readingSeconds":0,"listeningSeconds":0},"slices":[]},
             "pace":{"eligibleSessions":0,"medianDurationSeconds":null,"medianProgressDelta":null,"byMedia":[]}}
        """.trimIndent()
        val o = ApiJson.decodeFromString(Overview.serializer(), json)
        assertEquals(1500.0, o.snapshot.today.readingSeconds, 0.0)
        assertEquals(14000.0, o.snapshot.previousSevenDays.readingSeconds, 0.0)
        assertNull(o.goal!!.goalBooks)
        assertEquals(21, o.rhythm.peakHour)
        assertEquals(2400.0, o.rhythm.weekdays.first().averageReadingSeconds, 0.0)
    }

    @Test
    fun charts() {
        val heat = ApiJson.decodeFromString(
            ListSerializer(DailyReadingStat.serializer()),
            """[{"day":"2026-01-01","readingSeconds":900,"progressDelta":1.25,"eventsCount":2,"bySource":{"web":900}}]""",
        )
        assertEquals(2.0, heat.first().eventsCount, 0.0)
        val peak = ApiJson.decodeFromString(
            ListSerializer(PeakHourStat.serializer()),
            """[{"hour":21,"readingSeconds":3600,"eventsCount":3,"byFormat":{"EPUB":3600},"bySource":{"web":3600}}]""",
        )
        assertEquals(21, peak.first().hour)
        val funnel = ApiJson.decodeFromString(
            ProgressFunnelComparison.serializer(),
            """{"days":90,"current":{"started":12,"reached25":10,"reached50":8,"reached75":6,"completed":5},"previous":null}""",
        )
        assertNull(funnel.previous)
        val latency = ApiJson.decodeFromString(
            CompletionLatency.serializer(),
            """{"totalCompletions":3,"medianDays":12,"percentile75Days":null,"percentile90Days":null,"buckets":[{"label":"731d+","minDays":731,"maxDays":null,"count":1}]}""",
        )
        assertNull(latency.buckets.first().maxDays)
        // A bigint the server may pass through as a string.
        val storage = ApiJson.decodeFromString(
            StatisticsResult.serializer(FormatStorage.serializer()),
            """{"items":[{"format":"epub","sizeBytes":"123456789"}],"unknownCount":0}""",
        )
        assertEquals(123456789.0, storage.items.first().sizeBytes, 0.0)
    }
}
