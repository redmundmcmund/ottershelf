package io.github.ottershelf.feature.requests

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.ottershelf.core.model.CreateBookRequest
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.network.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The request body's metadata sources, which the server validates strictly. */
class RequestBodyTest {

    private fun work(vararg members: MetadataCandidate) =
        RequestWork(key = "k", best = members.first(), members = members.toList(), year = null)

    @Test
    fun everySourceCarriesALabelEvenWhenTheProviderNamesDidNotLoad() {
        val w = work(
            MetadataCandidate(provider = "openlibrary", providerId = "OL1W", title = "Dracula"),
            MetadataCandidate(provider = "google", providerId = "g1", title = "Dracula"),
            MetadataCandidate(provider = "google", providerId = "g1", title = "Dracula"), // same source twice
            MetadataCandidate(provider = "hardcover", title = "Dracula"), // no id: not a source
        )

        val named = metadataSources(w, mapOf("openlibrary" to "Open Library"))!!
        assertEquals(listOf("Open Library", "google"), named.map { it.providerLabel })

        val unnamed = metadataSources(w, emptyMap())!!
        assertEquals(listOf("openlibrary", "google"), unnamed.map { it.providerLabel })

        // Always sent (the server's DTO has no @IsOptional on it).
        val body = CreateBookRequest(title = "Dracula", mediaKind = "book", metadataSources = unnamed)
        val sent = ApiJson.parseToJsonElement(ApiJson.encodeToString(CreateBookRequest.serializer(), body))
        val sources = sent.jsonObject["metadataSources"]!!.jsonArray
        assertEquals("openlibrary", sources[0].jsonObject["providerLabel"]!!.jsonPrimitive.content)
    }

    @Test
    fun labelsAreCutToTheServersLimit() {
        val w = work(MetadataCandidate(provider = "p", providerId = "1", title = "T"))
        assertEquals(100, metadataSources(w, mapOf("p" to "x".repeat(150)))!!.single().providerLabel.length)
    }

    @Test
    fun noSourcesWithoutProviderIds() {
        assertNull(metadataSources(work(MetadataCandidate(provider = "p", title = "T")), emptyMap()))
    }
}
