package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TelemetryUploaderTest {

    private val baseUrl = "https://proj.supabase.co"
    private val publishableKey = "publishable-key-123"

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

    private fun uploader(poster: HttpPoster) = TelemetryUploader(baseUrl, publishableKey, poster)

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
        TelemetryUploader("https://proj.supabase.co/", publishableKey, poster).upload(listOf(event("e1")))
        assertEquals("https://proj.supabase.co/rest/v1/donation_events", poster.calls[0].first)
    }

    @Test
    fun sendsThePublishableKeyAsHeadersNeverInTheUrl() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        val (url, headers, _) = poster.calls[0]
        assertEquals(publishableKey, headers["apikey"])
        assertEquals("Bearer $publishableKey", headers["Authorization"])
        assertFalse("the key must not leak into the URL", url.contains(publishableKey))
    }

    /**
     * `resolution=ignore-duplicates` makes PostgREST take its upsert path, which
     * requires SELECT on the table — a grant the device key deliberately never
     * has. Asserting on the exact header value, not just "does not contain the
     * old fragment", so a typo'd replacement (e.g. leaving a stray comma) cannot
     * pass this test by accident.
     */
    @Test
    fun sendsExactlyReturnMinimalAndNothingElse() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        assertEquals("return=minimal", poster.calls[0].second["Prefer"])
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

    // ── Duplicates (409) ─────────────────────────────────────────────────────

    /**
     * Without `resolution=ignore-duplicates`, a 409 on a single-row request whose
     * body confirms SQLSTATE 23505 means the client-generated id — the primary
     * key — already made it to the server, almost always on an earlier attempt at
     * this same row. That is success: the donation is stored, and the id belongs
     * in uploadedIds so the caller deletes it from the outbox, not in rejectedIds.
     */
    @Test
    fun aSingleRow409IsReportedAsUploadedNotRejected() {
        val poster = RecordingPoster { _, _ ->
            HttpResponse(409, "duplicate key value violates unique constraint (SQLSTATE 23505)")
        }
        val outcome = uploader(poster).upload(listOf(event("already-there")))

        assertEquals("one batch request, no per-row fallback for a lone row",
            1, poster.calls.size)
        assertEquals(setOf("already-there"), outcome.uploadedIds)
        assertTrue("a stored duplicate must not be counted as rejected",
            outcome.rejectedIds.isEmpty())
        assertFalse(outcome.retryableFailure)
    }

    /**
     * FIX 1: PostgREST also returns 409 for a foreign-key violation (23503),
     * which means the row was rejected, not stored. It must not be credited to
     * uploadedIds — it must stay queued and retried, exactly like an ordinary
     * retryable failure, and it must not be treated as a permanent rejection
     * either, since we cannot actually prove it is bad.
     */
    @Test
    fun aSingleRow409NamingAForeignKeyViolationIsNeitherUploadedNorRejected() {
        val poster = RecordingPoster { _, _ ->
            HttpResponse(409, "insert or update on table violates foreign key constraint (SQLSTATE 23503)")
        }
        val outcome = uploader(poster).upload(listOf(event("e1")))

        assertTrue("an unconfirmed 409 must not be credited as uploaded", outcome.uploadedIds.isEmpty())
        assertTrue("an unconfirmed 409 must not be treated as a rejection either", outcome.rejectedIds.isEmpty())
        assertTrue("the row stays queued and the flush is retryable", outcome.retryableFailure)
    }

    /** A null body — PostgREST should always send one, but the code must not
     *  assume it — must be treated as retryable, never as a confirmed duplicate. */
    @Test
    fun aSingleRow409WithANullBodyIsRetryableRatherThanStored() {
        val poster = RecordingPoster { _, _ -> HttpResponse(409, null) }
        val outcome = uploader(poster).upload(listOf(event("e1")))

        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }

    /** A body that names no SQLSTATE at all must be treated the same way. */
    @Test
    fun aSingleRow409NamingNoSqlstateIsRetryableRatherThanStored() {
        val poster = RecordingPoster { _, _ -> HttpResponse(409, "duplicate key value violates constraint") }
        val outcome = uploader(poster).upload(listOf(event("e1")))

        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }

    /**
     * PostgREST runs a batch insert as one statement, so a 409 naming the unique
     * violation on a *multi-row* request means one row collided and the whole
     * statement aborted — the other rows never landed either. Treating the batch
     * as wholesale-uploaded would wrongly credit rows the server never received,
     * so this must fall back to sending them individually exactly as a row-level
     * refusal does. Must not regress once isAlreadyStored requires a confirmed
     * body: this still has to route to the fallback, not just fall through.
     */
    @Test
    fun aMultiRow409TriggersThePerRowFallback() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            if (isBatch) HttpResponse(409, "duplicate key (SQLSTATE 23505)") else HttpResponse(201, null)
        }
        val outcome = uploader(poster).upload(listOf(event("e1"), event("e2")))

        assertEquals("the batch request plus one per row", 3, poster.calls.size)
        assertEquals(setOf("e1", "e2"), outcome.uploadedIds)
        assertTrue(outcome.rejectedIds.isEmpty())
    }

    /**
     * The realistic shape of a retried batch: one row already landed from the
     * earlier attempt (409, confirmed 23505), the rest are genuinely new (201).
     * Both outcomes are "on the server", so both ids must come back uploaded.
     */
    @Test
    fun aFallbackMixingSuccessAndDuplicateReportsEveryRowUploaded() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            when {
                isBatch -> HttpResponse(409, "duplicate key (SQLSTATE 23505)")
                body.contains("\"already-there\"") -> HttpResponse(409, "duplicate key (SQLSTATE 23505)")
                else -> HttpResponse(201, null)
            }
        }
        val outcome = uploader(poster).upload(listOf(event("already-there"), event("fresh")))

        assertEquals(setOf("already-there", "fresh"), outcome.uploadedIds)
        assertTrue(outcome.rejectedIds.isEmpty())
        assertFalse(outcome.retryableFailure)
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
        assertTrue("every request, batch or fallback, carries the same Prefer header",
            poster.calls.all { it.second["Prefer"] == "return=minimal" })
    }

    @Test
    fun singleRowRetryDoesNotRunWhenTheBatchSucceeds() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1"), event("e2")))
        assertEquals("one batch request, no per-row fallback", 1, poster.calls.size)
    }

    /**
     * A batch of one needs no fallback — it is already isolated — but it also has
     * no sibling whose success could corroborate the refusal. A stale PostgREST
     * schema cache refuses a lone donation with exactly the same 400 as a genuinely
     * malformed one, and a quiet kiosk flushing a single donation hits this path
     * constantly. So it is kept, not deleted: the age cap retires a row that really
     * is unacceptable, at the cost of one small request per flush until then.
     */
    @Test
    fun aLoneRefusedRowIsKeptBecauseNoSiblingCorroboratesTheRefusal() {
        val poster = RecordingPoster { _, _ -> HttpResponse(400, "invalid") }
        val outcome = uploader(poster).upload(listOf(event("only")))
        assertEquals("one batch request, no per-row fallback", 1, poster.calls.size)
        assertTrue(
            "a lone refusal is indistinguishable from a fleet-wide one and must not delete",
            outcome.rejectedIds.isEmpty()
        )
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue("the row stays queued for the next flush", outcome.retryableFailure)
        assertTrue("the operator still sees why", outcome.lastError!!.contains("400"))
    }

    /**
     * Same guarantee as the 400 poison-row case, for the other two codes in
     * ROW_LEVEL_REFUSALS. Written after the 409 change specifically to prove that
     * removing 409 from the set left 413 and 422 still triggering the fallback
     * (and still honoured once a sibling corroborates them) — a *lone* row can't
     * tell the two paths apart, since the fallback needs size > 1 either way, so
     * this uses the same corroborated-batch shape as aRejectedBatchIsRetriedRowByRow.
     */
    @Test
    fun aRejectedBatchIsRetriedRowByRowFor413And422TooNotJust400() {
        for (code in listOf(413, 422)) {
            val poster = RecordingPoster { _, body ->
                val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
                when {
                    isBatch -> HttpResponse(code, "refused")
                    body.contains("\"poison\"") -> HttpResponse(code, "refused")
                    else -> HttpResponse(201, null)
                }
            }
            val outcome = uploader(poster).upload(listOf(event("good1"), event("poison")))
            assertEquals("HTTP $code: the good row must still upload",
                setOf("good1"), outcome.uploadedIds)
            assertEquals("HTTP $code: a corroborated refusal is still honoured",
                setOf("poison"), outcome.rejectedIds)
            assertFalse("HTTP $code: a row-level refusal is permanent, not retryable",
                outcome.retryableFailure)
        }
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
        val poster = RecordingPoster { _, _ -> HttpResponse(401, "bad key $publishableKey rejected") }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertFalse("an error surfaced in the UI must not expose the key",
            outcome.lastError!!.contains(publishableKey))
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

    // ── The fallback's sweep is bounded (phase 3a) ───────────────────────────

    /** Thirty rows, e01…e30, so a full sweep would be visibly different from a
     *  capped one. */
    private fun thirtyRows() = (1..30).map { event("e%02d".format(it)) }

    private fun isBatchBody(body: String) =
        JsonParser.parseString(body).asJsonArray.size() > 1

    /**
     * Schema drift refuses every row in the fleet. The fallback exists to test
     * one hypothesis — one bad row among many — and uniform refusal refutes it,
     * so the remaining rows teach nothing. Before this cap an unwatched flusher
     * fired 1 + 100 requests per flush, at every trigger, indefinitely.
     */
    @Test
    fun aFallbackRefusingEveryRowStopsAfterTenRequests() {
        val poster = RecordingPoster { _, _ -> HttpResponse(400, "PGRST204 column not in schema cache") }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals("one batch attempt plus ten single rows", 11, poster.calls.size)
        assertTrue("no row may be deleted on a fleet-wide refusal", outcome.rejectedIds.isEmpty())
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue("the batch must stay queued for the next flush", outcome.retryableFailure)
    }

    /**
     * The accepted cost of the cap, pinned rather than pretended away: bad rows
     * at the head of the queue block the good rows behind them until the
     * outbox's 30-day age cap retires them. Ten leaves margin for a scattered
     * handful, and uniform refusal is far likelier than a clustered few because
     * payloads are generated by our own code with a fixed shape.
     */
    @Test
    fun theCapCanHideAGoodRowSittingBehindTenBadOnes() {
        val poster = RecordingPoster { _, body ->
            when {
                isBatchBody(body) -> HttpResponse(400, "invalid input")
                body.contains("\"e11\"") -> HttpResponse(201, null)
                else -> HttpResponse(400, "invalid input")
            }
        }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals(11, poster.calls.size)
        assertTrue("e11 is never reached, and that is the documented cost", outcome.uploadedIds.isEmpty())
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }

    /**
     * Once a row lands, the hypothesis is confirmed and every later refusal is
     * corroborated — removing it drains the queue, so those requests are
     * productive and the sweep must run to completion.
     */
    @Test
    fun aSuccessOnTheFirstFallbackRowLetsTheSweepRunToCompletion() {
        val poster = RecordingPoster { _, body ->
            when {
                isBatchBody(body) -> HttpResponse(400, "invalid input")
                body.contains("\"e01\"") -> HttpResponse(201, null)
                else -> HttpResponse(400, "invalid input")
            }
        }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals("one batch attempt plus all thirty single rows", 31, poster.calls.size)
        assertEquals(setOf("e01"), outcome.uploadedIds)
        assertEquals(29, outcome.rejectedIds.size)
        assertFalse("a corroborated refusal is permanent, not retryable", outcome.retryableFailure)
    }

    /**
     * The counter must stop mattering the moment anything succeeds, not merely
     * be reset. Four refusals then a success then twenty-five more refusals:
     * a counter that kept incrementing would stop at ten and strand fifteen
     * permanently-refused rows in the queue forever.
     */
    @Test
    fun aSuccessPartWayThroughStopsTheCapFromEverFiring() {
        val poster = RecordingPoster { _, body ->
            when {
                isBatchBody(body) -> HttpResponse(400, "invalid input")
                body.contains("\"e05\"") -> HttpResponse(201, null)
                else -> HttpResponse(400, "invalid input")
            }
        }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals(31, poster.calls.size)
        assertEquals(setOf("e05"), outcome.uploadedIds)
        assertEquals(29, outcome.rejectedIds.size)
    }

    /**
     * The pre-existing `break` on a transport failure must still win outright,
     * ahead of the cap: every remaining row would burn a full connect-plus-read
     * timeout and stay queued regardless.
     */
    @Test
    fun aTransportFailureMidFallbackStopsBeforeTheCapEverCounts() {
        val poster = RecordingPoster { _, body ->
            if (isBatchBody(body)) HttpResponse(400, "invalid input")
            else HttpResponse(HttpResponse.TRANSPORT_FAILURE, null)
        }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals("one batch attempt plus exactly one row", 2, poster.calls.size)
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }
}
