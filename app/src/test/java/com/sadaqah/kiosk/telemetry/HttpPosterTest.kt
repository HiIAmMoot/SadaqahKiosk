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

    /** The transport holds a bare status code before it holds a response, and
     *  asking the range question there must not mean building one to throw away. */
    @Test
    fun successCanBeAskedOfAStatusCodeAlone() {
        assertFalse(HttpResponse.isSuccess(199))
        assertTrue(HttpResponse.isSuccess(200))
        assertTrue(HttpResponse.isSuccess(299))
        assertFalse(HttpResponse.isSuccess(300))
        for (code in listOf(199, 200, 204, 299, 300, 400, 503)) {
            assertEquals(HttpResponse(code, null).isSuccess, HttpResponse.isSuccess(code))
        }
    }

    @Test
    fun rowLevelRefusalsArePermanent() {
        assertTrue(HttpResponse(400, null).isPermanentRejection)
        assertTrue(HttpResponse(409, null).isPermanentRejection)
        assertTrue(HttpResponse(413, null).isPermanentRejection)
        assertTrue(HttpResponse(422, null).isPermanentRejection)
    }

    /**
     * These say the endpoint is wrong, not the row. Treating them as permanent
     * would delete a whole queue of donations over a bad key or a missing table.
     */
    @Test
    fun endpointLevelRefusalsAreRetryableNotPermanent() {
        for (code in listOf(401, 403, 404, 408, 429)) {
            assertFalse("HTTP $code must not be treated as a bad row",
                HttpResponse(code, null).isPermanentRejection)
        }
    }

    @Test
    fun serverErrorsAndUnknownStatusesAreNeitherSuccessNorPermanent() {
        for (code in listOf(300, 399, 451, 500, 503, HttpResponse.TRANSPORT_FAILURE)) {
            assertFalse(HttpResponse(code, null).isSuccess)
            assertFalse(HttpResponse(code, null).isPermanentRejection)
        }
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
        assertEquals(body, readCapped({ DribblingStream(body) }, 8192))
    }

    @Test
    fun readCappedStopsAtTheCap() {
        val out = readCapped({ DribblingStream("x".repeat(500)) }, 100)!!
        assertEquals(100, out.length)
    }

    @Test
    fun readCappedReturnsNullForNoStream() {
        assertNull(readCapped({ null }, 8192))
    }

    @Test
    fun readCappedReturnsEmptyForAnEmptyStream() {
        assertEquals("", readCapped({ DribblingStream("") }, 8192))
    }

    /**
     * A connection reset on a 2xx throws when the success stream is *acquired*,
     * not when it is read. Acquisition therefore has to sit inside the same guard
     * as the read, or the transport loses a status code it already knows and
     * degrades a real 200 into an indistinguishable transport failure.
     */
    @Test
    fun readCappedReturnsNullWhenTheStreamCannotBeOpened() {
        assertNull(readCapped({ throw java.io.IOException("connection reset") }, 8192))
    }

    // ── Redirects ────────────────────────────────────────────────────────────

    /**
     * A loopback socket that answers a fixed reply and records what it was asked.
     * The redirect question cannot be answered on the fake-poster side of the
     * seam — following a 3xx is `HttpURLConnection`'s own behaviour, so only a
     * real connection can show whether the credential headers travelled.
     */
    private class StubServer(private val reply: String) : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

        init {
            Thread {
                try {
                    while (true) {
                        socket.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader()
                            val head = StringBuilder()
                            var line = reader.readLine()
                            while (!line.isNullOrEmpty()) {
                                head.append(line).append('\n')
                                line = reader.readLine()
                            }
                            val length = Regex("(?i)content-length:\\s*(\\d+)")
                                .find(head)?.groupValues?.get(1)?.toInt() ?: 0
                            var read = 0
                            val body = CharArray(length)
                            while (read < length) {
                                val n = reader.read(body, read, length - read)
                                if (n < 0) break
                                read += n
                            }
                            head.append(String(body, 0, read))
                            requests += head.toString()
                            client.getOutputStream().apply {
                                write(reply.toByteArray(Charsets.UTF_8))
                                flush()
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // The socket closing is how this thread is meant to end.
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun aRedirectIsNotFollowedSoTheKeyNeverReachesAnotherHost() {
        StubServer("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]").use { target ->
            val redirector = StubServer(
                "HTTP/1.1 302 Found\r\n" +
                    "Location: http://127.0.0.1:${target.port}/rest/v1/donation_events\r\n" +
                    "Content-Length: 0\r\n\r\n"
            )
            redirector.use {
                val response = UrlConnectionPoster(connectTimeoutMs = 2_000, readTimeoutMs = 2_000).post(
                    url = "http://127.0.0.1:${redirector.port}/rest/v1/donation_events",
                    headers = mapOf("apikey" to "anon-key-123", "Authorization" to "Bearer anon-key-123"),
                    body = "[]"
                )

                assertEquals("a 3xx is an ordinary non-success, retryable", 302, response.code)
                assertFalse(response.isSuccess)
                assertFalse(response.isPermanentRejection)
                assertTrue(
                    "the key must not be replayed to a redirect target: ${target.requests}",
                    target.requests.isEmpty()
                )
            }
        }
    }
}
