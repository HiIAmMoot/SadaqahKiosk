package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Test

/**
 * The seam itself has almost no behaviour — these pin the contract that
 * TelemetryUploader is written against, so a future adapter cannot quietly
 * change what a transport failure looks like.
 */
class HttpPosterTest {

    @Test
    fun transportFailureIsADistinctSentinel() {
        // Any real HTTP status is >= 100, so -1 can never collide with one.
        assertTrue(HttpResponse.TRANSPORT_FAILURE < 100)
    }

    @Test
    fun aPosterCanBeSubstituted() {
        val recorded = mutableListOf<Triple<String, Map<String, String>, String>>()
        val fake = HttpPoster { url, headers, body ->
            recorded += Triple(url, headers, body)
            HttpResponse(201, null)
        }

        val response = fake.post("https://example.invalid/rest/v1/t", mapOf("k" to "v"), "[]")

        assertEquals(201, response.code)
        assertEquals(1, recorded.size)
        assertEquals("https://example.invalid/rest/v1/t", recorded[0].first)
        assertEquals("v", recorded[0].second["k"])
        assertEquals("[]", recorded[0].third)
    }

    @Test
    fun responseBodyMayBeAbsent() {
        assertNull(HttpResponse(204, null).body)
    }
}
