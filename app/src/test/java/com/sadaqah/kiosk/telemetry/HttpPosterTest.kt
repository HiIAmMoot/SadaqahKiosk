package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Test

/**
 * These tests pin the contract on the testable side of the seam: the status
 * predicates that Task 2's retry policy switches on.
 */
class HttpPosterTest {

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
    fun transportFailureIsMinusOneAndIsNeitherSuccessNorPermanent() {
        assertEquals(-1, HttpResponse.TRANSPORT_FAILURE)
        val failure = HttpResponse(HttpResponse.TRANSPORT_FAILURE, null)
        assertFalse(failure.isSuccess)
        assertFalse("a transport failure must be retryable", failure.isPermanentRejection)
    }

    @Test
    fun successIsExactlyTheTwoHundreds() {
        assertFalse(HttpResponse(199, null).isSuccess)
        assertTrue(HttpResponse(200, null).isSuccess)
        assertTrue(HttpResponse(201, null).isSuccess)
        assertTrue(HttpResponse(299, null).isSuccess)
        assertFalse(HttpResponse(300, null).isSuccess)
    }

    @Test
    fun clientErrorsArePermanentRejections() {
        assertTrue(HttpResponse(400, null).isPermanentRejection)
        assertTrue(HttpResponse(401, null).isPermanentRejection)
        assertTrue(HttpResponse(499, null).isPermanentRejection)
        assertFalse(HttpResponse(399, null).isPermanentRejection)
        assertFalse(HttpResponse(500, null).isPermanentRejection)
    }

    @Test
    fun rateLimitAndRequestTimeoutAreRetryableNotPermanent() {
        assertFalse(HttpResponse(429, null).isPermanentRejection)
        assertFalse(HttpResponse(408, null).isPermanentRejection)
        assertFalse(HttpResponse(429, null).isSuccess)
        assertFalse(HttpResponse(408, null).isSuccess)
    }

    @Test
    fun serverErrorsAreNeitherSuccessNorPermanent() {
        val serverError = HttpResponse(503, "unavailable")
        assertFalse(serverError.isSuccess)
        assertFalse(serverError.isPermanentRejection)
    }

    /** A stream that hands back one character per call, the way a socket
     *  delivering a chunked body behaves. A single best-effort read would
     *  return just the first character. */
    private class DribblingStream(private val content: String) : java.io.InputStream() {
        private var index = 0
        override fun read(): Int =
            if (index >= content.length) -1 else content[index++].code
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (index >= content.length) return -1
            b[off] = content[index++].code.toByte()
            return 1
        }
    }

    @Test
    fun readCappedKeepsReadingUntilTheStreamEnds() {
        val body = """{"message":"invalid input syntax for type uuid"}"""
        assertEquals(body, readCapped(DribblingStream(body), 8192))
    }

    @Test
    fun readCappedStopsAtTheCap() {
        val out = readCapped(DribblingStream("x".repeat(500)), 100)!!
        assertEquals(100, out.length)
    }

    @Test
    fun readCappedReturnsNullForNoStream() {
        assertNull(readCapped(null, 8192))
    }

    @Test
    fun readCappedReturnsEmptyForAnEmptyStream() {
        assertEquals("", readCapped(DribblingStream(""), 8192))
    }
}
