package io.github.ottershelf.feature.reader

import io.github.ottershelf.core.network.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The place saved, shown and counted is where the screen starts ([Relocate.position]), so a portrait
 * page and a landscape spread over the same text give the same percentage, except at the end of the
 * book, where it is the end (a finished book reaches 100%).
 */
class RelocatePositionTest {

    @Test
    fun aSpreadAndAPageAtTheSamePlaceGiveTheSamePosition() {
        // The same first line on screen: the spread ends two tablet columns further on.
        val portrait = Relocate(fraction = 0.412, startFraction = 0.410)
        val spread = Relocate(fraction = 0.418, startFraction = 0.410)
        assertEquals(portrait.position, spread.position, 0.0)
        assertEquals(0.410, spread.position, 0.0)
    }

    @Test
    fun theEndOfTheBookIsTheEnd() {
        assertEquals(1.0, Relocate(fraction = 1.0, startFraction = 0.997).position, 0.0)
        assertEquals(0.9995, Relocate(fraction = 0.9995, startFraction = 0.997, bookEnd = true).position, 0.0)
    }

    @Test
    fun anOlderPageWithoutTheStartFallsBackToTheFraction() {
        assertEquals(0.3, Relocate(fraction = 0.3).position, 0.0)
        val parsed = ApiJson.decodeFromString(Relocate.serializer(), """{"fraction":0.3,"page":4}""")
        assertEquals(0.3, parsed.position, 0.0)
        assertEquals(0.25, ApiJson.decodeFromString(Relocate.serializer(), """{"fraction":0.3,"startFraction":0.25}""").position, 0.0)
    }

    @Test
    fun theSessionCountsFromTheStartOfTheScreen() {
        var now = 1_000_000L
        val sent = mutableListOf<io.github.ottershelf.core.model.ReadingSession>()
        val visit = io.github.ottershelf.core.sync.ReadingVisit(clock = { now }, send = { sent += it })
        val signs = ReadingSigns(visit)
        signs.expect()
        signs.onRelocate(Relocate(fraction = 0.52, startFraction = 0.50))
        now += 60_000
        signs.onRelocate(Relocate(fraction = 0.62, startFraction = 0.60, turned = true))
        now += 60_000
        visit.end()
        val session = sent.single()
        assertEquals(60.0, session.endProgress!!, 1e-9)
        assertEquals(10.0, session.progressDelta!!, 1e-9)
    }
}
