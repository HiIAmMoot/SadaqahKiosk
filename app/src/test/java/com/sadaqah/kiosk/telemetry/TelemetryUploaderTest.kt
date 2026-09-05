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

    /** A batch of one that is rejected needs no fallback — it is already isolated,
     *  and no sibling row exists whose success could contradict the refusal. */
    @Test
    fun aSingleRejectedRowIsNotRetried() {
        val poster = RecordingPoster { _, _ -> HttpResponse(400, "invalid") }
        val outcome = uploader(poster).upload(listOf(event("only")))
        assertEquals(1, poster.calls.size)
        assertEquals(setOf("only"), outcome.rejectedIds)
        assertFalse("a lone malformed row is still dropped, not stalled", outcome.retryableFailure)
    }

    /**
     * The batch-deleting failure. PostgREST answers 400 for a stale schema cache
     * or an unknown column — fleet-wide, identical for every row, and it clears
     * on a reload. The fallback would then "confirm" a hundred donations as bad
     * and the caller would delete a hundred records the server never took.
     *
     * Uniform refusal refutes the one-bad-row hypothesis the fallback rests on,
     * so nothing is reported rejected and the rows stay queued.
     */
    @Test
    fun aFallbackThatRefusesEveryRowReportsNothingRejected() {
        val poster = RecordingPoster { _, _ -> HttpResponse(400, "PGRST204 column not in schema cache") }
        val outcome = uploader(poster).upload(listOf(event("e1"), event("e2"), event("e3")))

        assertTrue("no row may be deleted on a fleet-wide refusal", outcome.rejectedIds.isEmpty())
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue("the batch must stay queued for the next flush", outcome.retryableFailure)
        assertNotNull("the operator still needs the reason", outcome.lastError)
    }

    /** The other side of the same rule: one row getting through is the evidence
     *  that the endpoint is fine and the refusal really is about that row. */
    @Test
    fun oneRowGettingThroughIsEnoughToHonourAnotherRowsRejection() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            when {
                isBatch -> HttpResponse(400, "invalid input")
                body.contains("\"poison\"") -> HttpResponse(400, "invalid input")
                else -> HttpResponse(201, null)
            }
        }
        val outcome = uploader(poster).upload(listOf(event("good1"), event("poison")))

        assertEquals(setOf("good1"), outcome.uploadedIds)
        assertEquals(setOf("poison"), outcome.rejectedIds)
        assertFalse("a poison row is permanent, not retryable", outcome.retryableFailure)
    }

    /**
     * Ruling Q's `break`. When the network drops mid-fallback every remaining row
     * would burn a full connect-plus-read timeout — 100 rows at 45s is over an
     * hour of a kiosk doing nothing — and stay queued regardless.
     */
    @Test
    fun aRetryableFailureOnTheFirstFallbackRowStopsTheRest() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            when {
                isBatch -> HttpResponse(400, "invalid input")
                body.contains("\"flaky\"") -> HttpResponse(503, "down")
                else -> HttpResponse(201, null)
            }
        }
        val outcome = uploader(poster).upload(listOf(event("flaky"), event("good1"), event("good2")))

        assertEquals("the batch plus one row, then stop", 2, poster.calls.size)
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }

    /** Rejections found mid-fallback are dropped too when the group stalls before
     *  any row succeeds — a stall is not evidence that the rejected row was bad. */
    @Test
    fun aStallAfterARejectionStillReportsNothingRejected() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            when {
                isBatch -> HttpResponse(400, "invalid input")
                body.contains("\"bad\"") -> HttpResponse(400, "invalid input")
                else -> HttpResponse(HttpResponse.TRANSPORT_FAILURE, "no route to host")
            }
        }
        val outcome = uploader(poster).upload(listOf(event("bad"), event("later")))

        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
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

    /**
     * The fallback's rejected branch is the one that ends in a deleted donation,
     * so it is the branch whose reason matters most. The batch answer says
     * nothing about which row was refused; the row's own answer does, so that is
     * what has to survive into [UploadOutcome.lastError].
     */
    @Test
    fun lastErrorInTheFallbackCarriesTheRowsOwnResponseNotTheBatchs() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            when {
                isBatch -> HttpResponse(422, "batch level complaint")
                body.contains("\"poison\"") -> HttpResponse(400, "row level complaint")
                else -> HttpResponse(201, null)
            }
        }
        val outcome = uploader(poster).upload(listOf(event("good1"), event("poison")))

        assertEquals(setOf("poison"), outcome.rejectedIds)
        val lastError = outcome.lastError
        assertNotNull("a dropped row must not be dropped silently", lastError)
        assertTrue(lastError!!, lastError.contains("row level complaint"))
        assertTrue(lastError, lastError.contains("400"))
        assertFalse("the batch answer does not name the refused row",
            lastError.contains("batch level complaint"))
    }

    /**
     * A captive portal answers a POST with a full HTML login page. That body is
     * the operator's only clue, but it reaches the Settings screen and the logs,
     * so it is bounded before it is stored.
     */
    @Test
    fun lastErrorIsTruncatedSoALoginPageCannotFloodIt() {
        // Deliberately word-shaped: a long run of one character would be eaten by
        // the redactor's token pattern and the truncation would prove nothing.
        val page = "<html>" + "the network requires a login. ".repeat(1_000) + "</html>"
        val poster = RecordingPoster { _, _ -> HttpResponse(503, page) }
        val outcome = uploader(poster).upload(listOf(event("e1")))

        val lastError = outcome.lastError!!
        assertTrue("the body arrived intact and unbounded: ${lastError.length}",
            lastError.length < page.length)
        assertTrue(lastError.length <= TelemetryRedactor.MAX_TEXT_BYTES + 64)
        assertTrue("a cut body must say it was cut", lastError.endsWith("… truncated"))
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
