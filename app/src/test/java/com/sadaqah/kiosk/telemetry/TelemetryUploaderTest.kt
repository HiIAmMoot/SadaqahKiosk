package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TelemetryUploaderTest {

    private val baseUrl = "https://proj.supabase.co"
    private val anonKey = "anon-key-123"

    /** Records every request so tests can assert on what was actually sent. */
    private class RecordingPoster(
        private val respond: (String, String) -> HttpResponse = { _, _ -> HttpResponse(201, null) }
    ) : HttpPoster {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            calls += Triple(url, headers, body)
            return respond(url, body)
        }
    }

    private fun event(id: String, table: String = TelemetryTables.DONATIONS) =
        QueuedEvent(id, table, """{"id":"$id","amount_cents":100}""", 1_000L)

    private fun uploader(poster: HttpPoster) = TelemetryUploader(baseUrl, anonKey, poster)

    // ── Request shape ────────────────────────────────────────────────────────

    @Test
    fun postsToTheRestEndpointForTheTable() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        assertEquals("https://proj.supabase.co/rest/v1/donation_events", poster.calls[0].first)
    }

    @Test
    fun toleratesABaseUrlWithATrailingSlash() {
        val poster = RecordingPoster()
        TelemetryUploader("https://proj.supabase.co/", anonKey, poster).upload(listOf(event("e1")))
        assertEquals("https://proj.supabase.co/rest/v1/donation_events", poster.calls[0].first)
    }

    @Test
    fun sendsTheAnonKeyAsHeadersNeverInTheUrl() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        val (url, headers, _) = poster.calls[0]
        assertEquals(anonKey, headers["apikey"])
        assertEquals("Bearer $anonKey", headers["Authorization"])
        assertFalse("the key must not leak into the URL", url.contains(anonKey))
    }

    @Test
    fun requestsDuplicateTolerantInserts() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        val prefer = poster.calls[0].second["Prefer"]!!
        assertTrue("retries must not double-count: $prefer",
            prefer.contains("resolution=ignore-duplicates"))
    }

    @Test
    fun sendsJsonContentType() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        assertEquals("application/json", poster.calls[0].second["Content-Type"])
    }

    @Test
    fun bodyIsAJsonArrayOfTheRawPayloads() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1"), event("e2")))
        val array = JsonParser.parseString(poster.calls[0].third).asJsonArray
        assertEquals(2, array.size())
        assertEquals("e1", array[0].asJsonObject.get("id").asString)
        assertEquals("e2", array[1].asJsonObject.get("id").asString)
    }

    // ── Batching by table ────────────────────────────────────────────────────

    @Test
    fun groupsAMixedBatchIntoOneRequestPerTable() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(
            event("d1", TelemetryTables.DONATIONS),
            event("x1", TelemetryTables.DIAGNOSTICS),
            event("d2", TelemetryTables.DONATIONS)
        ))
        assertEquals(2, poster.calls.size)
        val urls = poster.calls.map { it.first }.toSet()
        assertTrue(urls.any { it.endsWith("/donation_events") })
        assertTrue(urls.any { it.endsWith("/diagnostic_events") })
    }

    @Test
    fun emptyInputSendsNothing() {
        val poster = RecordingPoster()
        val outcome = uploader(poster).upload(emptyList())
        assertEquals(0, poster.calls.size)
        assertTrue(outcome.uploadedIds.isEmpty())
        assertFalse(outcome.retryableFailure)
    }

    // ── Success ──────────────────────────────────────────────────────────────

    @Test
    fun successReportsEveryIdAsUploaded() {
        val outcome = uploader(RecordingPoster()).upload(listOf(event("e1"), event("e2")))
        assertEquals(setOf("e1", "e2"), outcome.uploadedIds)
        assertTrue(outcome.rejectedIds.isEmpty())
        assertFalse(outcome.retryableFailure)
        assertNull(outcome.lastError)
    }

    // ── Retryable failures ───────────────────────────────────────────────────

    @Test
    fun serverErrorLeavesEventsQueuedAndFlagsRetry() {
        val poster = RecordingPoster { _, _ -> HttpResponse(503, "unavailable") }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
        assertNotNull(outcome.lastError)
    }

    @Test
    fun transportFailureLeavesEventsQueuedAndFlagsRetry() {
        val poster = RecordingPoster { _, _ ->
            HttpResponse(HttpResponse.TRANSPORT_FAILURE, "no route to host")
        }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }

    /** One table failing must not discard another table's successful upload. */
    @Test
    fun oneTableFailingDoesNotLoseAnotherTablesSuccess() {
        val poster = RecordingPoster { url, _ ->
            if (url.endsWith("/diagnostic_events")) HttpResponse(503, "down")
            else HttpResponse(201, null)
        }
        val outcome = uploader(poster).upload(listOf(
            event("d1", TelemetryTables.DONATIONS),
            event("x1", TelemetryTables.DIAGNOSTICS)
        ))
        assertEquals(setOf("d1"), outcome.uploadedIds)
        assertTrue(outcome.retryableFailure)
    }

    // ── Poison rows ──────────────────────────────────────────────────────────

    /**
     * The failure this exists to prevent: one permanently-rejected row sitting at
     * the head of the queue, failing its whole batch forever, while everything
     * behind it ages out unsent.
     */
    @Test
    fun aRejectedBatchIsRetriedRowByRow() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            when {
                isBatch -> HttpResponse(400, "invalid input")
                body.contains("\"poison\"") -> HttpResponse(400, "invalid input")
                else -> HttpResponse(201, null)
            }
        }
        val outcome = uploader(poster).upload(listOf(
            event("good1"), event("poison"), event("good2")
        ))
        assertEquals(setOf("good1", "good2"), outcome.uploadedIds)
        assertEquals(setOf("poison"), outcome.rejectedIds)
        assertFalse("a poison row is permanent, not retryable", outcome.retryableFailure)
        assertTrue("every insert, batch or fallback, must be duplicate-tolerant",
            poster.calls.all {
                it.second["Prefer"] == "return=minimal,resolution=ignore-duplicates"
            })
    }

    @Test
    fun singleRowRetryDoesNotRunWhenTheBatchSucceeds() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1"), event("e2")))
        assertEquals("one batch request, no per-row fallback", 1, poster.calls.size)
    }

    /** A batch of one that is rejected needs no fallback — it is already isolated. */
    @Test
    fun aSingleRejectedRowIsNotRetried() {
        val poster = RecordingPoster { _, _ -> HttpResponse(400, "invalid") }
        val outcome = uploader(poster).upload(listOf(event("only")))
        assertEquals(1, poster.calls.size)
        assertEquals(setOf("only"), outcome.rejectedIds)
    }

    /** A 5xx during the per-row fallback is still retryable for that row. */
    @Test
    fun serverErrorDuringRowFallbackKeepsThatRowQueued() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            when {
                isBatch -> HttpResponse(400, "invalid input")
                body.contains("\"flaky\"") -> HttpResponse(503, "down")
                else -> HttpResponse(201, null)
            }
        }
        val outcome = uploader(poster).upload(listOf(event("good1"), event("flaky")))
        assertEquals(setOf("good1"), outcome.uploadedIds)
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }

    @Test
    fun lastErrorCarriesTheServerMessage() {
        val poster = RecordingPoster { _, _ -> HttpResponse(500, "boom from postgrest") }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertTrue(outcome.lastError!!.contains("boom from postgrest"))
    }

    @Test
    fun lastErrorNeverContainsTheAnonKey() {
        val poster = RecordingPoster { _, _ -> HttpResponse(401, "bad key $anonKey rejected") }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertFalse("an error surfaced in the UI must not expose the key",
            outcome.lastError!!.contains(anonKey))
    }

    /** The reason describe() uses the house redactor rather than a manual
     *  replace: a server can echo back tokens we never sent it. */
    @Test
    fun lastErrorRedactsTokenShapedStringsTheServerEchoesBack() {
        val leaked = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdef"
        val poster = RecordingPoster { _, _ -> HttpResponse(500, "upstream rejected $leaked") }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertFalse(outcome.lastError!!.contains(leaked))
    }
}
