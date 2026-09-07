package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class TelemetryManagerTest {

    @get:Rule val temp = TemporaryFolder()

    private val identity = EventIdentity("SK-0042", "install-1", "1.3.6")
    private var now = 1_000_000L
    private var online = true

    /** Returns a fixed [HttpResponse] for every call, but records what it saw. */
    private class ConstantPoster(private val response: HttpResponse) : HttpPoster {
        var callCount = 0
            private set
        val bodies = mutableListOf<String>()

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            callCount++
            bodies += body
            return response
        }
    }

    /** Returns one response per call, by call index, then repeats its last
     *  response for any call beyond the script. This is what lets a test drive
     *  the uploader's per-row fallback: a real batch refusal followed by
     *  individually-scripted row outcomes. */
    private class ScriptedPoster(private val responses: List<HttpResponse>) : HttpPoster {
        var callCount = 0
            private set
        val bodies = mutableListOf<String>()

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            val response = responses.getOrElse(callCount) { responses.last() }
            callCount++
            bodies += body
            return response
        }
    }

    private fun manager(
        outbox: TelemetryOutbox,
        poster: HttpPoster,
        enabled: Boolean = true,
        activated: Boolean = true,
        credentials: TelemetryCredentials = TelemetryCredentials(InMemorySecretStore())
            .apply { save("https://abc.supabase.co", "publishable-key") },
        statusStore: TelemetryStatusStore = InMemoryStatusStore(),
        upload: ((TelemetryConfig, List<QueuedEvent>) -> UploadOutcome)? = null
    ) = TelemetryManager(
        outbox = outbox,
        credentials = credentials,
        statusStore = statusStore,
        posterFor = { poster },
        runtime = {
            TelemetryRuntime(
                enabled = enabled,
                activated = activated,
                identity = identity,
                privacyPolicyUrl = "https://example.org/privacy",
                termsUrl = "https://example.org/terms"
            )
        },
        networkAvailable = { online },
        clock = { now },
        upload = upload ?: { config, batch ->
            TelemetryUploader(config.baseUrl, config.publishableKey, poster).upload(batch)
        }
    )

    private fun outboxWith(vararg ids: String): TelemetryOutbox {
        val outbox = TelemetryOutbox(temp.newFile())
        ids.forEach { outbox.append(it, TelemetryTables.DONATIONS, """{"id":"$it"}""") }
        return outbox
    }

    @Test
    fun aSuccessfulFlushDeletesTheUploadedRows() {
        val outbox = outboxWith("a", "b")
        val result = manager(outbox, ConstantPoster(HttpResponse(201, null))).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(0, outbox.size())
    }

    /**
     * A single-row 409 whose body confirms SQLSTATE 23505 means the row is
     * already stored under its client-generated id — success, not a refusal. It
     * must be removed from the outbox exactly like a fresh 201 would be, not
     * kept for endless retry.
     */
    @Test
    fun aSingleRow409RemovesTheRowFromTheQueue() {
        val outbox = outboxWith("a")
        val result = manager(
            outbox,
            ConstantPoster(HttpResponse(409, "duplicate key value violates unique constraint (SQLSTATE 23505)"))
        ).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals("a stored duplicate must not be left queued forever", 0, outbox.size())
    }

    /**
     * FIX 1: PostgREST also answers 409 for a foreign-key or exclusion violation,
     * neither of which means the row was stored. Only a body naming SQLSTATE
     * 23505 may be treated as "already stored" — anything else must stay queued
     * exactly like an ordinary retryable failure, never deleted on a guess.
     */
    @Test
    fun aSingleRow409WithoutTheUniqueViolationSqlstateStaysQueued() {
        val outbox = outboxWith("a")
        val result = manager(
            outbox,
            ConstantPoster(HttpResponse(409, "insert or update violates foreign key constraint (SQLSTATE 23503)"))
        ).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals("an unconfirmed 409 must never be deleted from the outbox", 1, outbox.size())
    }

    /**
     * The rows are the only copy. A retryable failure must leave every one of them
     * on disk — this is the assertion that stands between a network blip and a
     * night's donations.
     */
    @Test
    fun aRetryableFailureKeepsEveryRow() {
        val outbox = outboxWith("a", "b")
        val result = manager(outbox, ConstantPoster(HttpResponse(503, "down"))).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(2, outbox.size())
    }

    @Test
    fun aRetryableFailureBacksOff() {
        val store = InMemoryStatusStore()
        manager(outboxWith("a"), ConstantPoster(HttpResponse(503, "down")), statusStore = store).flush()
        val status = store.read()
        assertEquals(1, status.consecutiveFailures)
        assertTrue("a failure must push the next attempt out", status.backoffUntilMs > now)
    }

    /** FIX 1: a stale queue depth is not evidence an error is history — the
     *  presenter instead trusts lastErrorAtMs against lastSuccessMs, so every
     *  site that writes a non-null lastError must also stamp when. */
    @Test
    fun aRetryableFailureRecordsWhenTheErrorWasWritten() {
        val store = InMemoryStatusStore()
        manager(outboxWith("a"), ConstantPoster(HttpResponse(503, "down")), statusStore = store).flush()
        assertEquals(now, store.read().lastErrorAtMs)
    }

    @Test
    fun aSuccessResetsTheFailureCount() {
        val store = InMemoryStatusStore()
        // A non-null lastError is seeded so clearing it below is a genuine
        // assertion rather than one that would pass with the reset deleted —
        // the previous seed left lastError null already, which made
        // assertNull(lastError) vacuous.
        store.write(store.read().copy(consecutiveFailures = 4, backoffUntilMs = 0L, lastError = "HTTP 503 down"))
        manager(outboxWith("a"), ConstantPoster(HttpResponse(201, null)), statusStore = store).flush()
        assertEquals(0, store.read().consecutiveFailures)
        assertEquals(now, store.read().lastSuccessMs)
        assertNull(store.read().lastError)
    }

    @Test
    fun theGateStopsAFlushBeforeAnyRequestIsMade() {
        val poster = ConstantPoster(HttpResponse(201, null))
        val outbox = outboxWith("a")
        val result = manager(outbox, poster, enabled = false).flush()
        assertEquals(FlushBlock.DISABLED, result)
        assertEquals("a disabled kiosk must not touch the network", 0, poster.callCount)
        assertEquals(1, outbox.size())
    }

    @Test
    fun anUnconfiguredKioskIsBlockedRatherThanUploadingToNowhere() {
        val poster = ConstantPoster(HttpResponse(201, null))
        val outbox = outboxWith("a")
        val result = manager(
            outbox,
            poster,
            credentials = TelemetryCredentials(InMemorySecretStore())
        ).flush()
        assertEquals(FlushBlock.NOT_CONFIGURED, result)
        assertEquals("no destination means no request", 0, poster.callCount)
        assertEquals(1, outbox.size())
    }

    @Test
    fun beingOfflineBlocksTheFlush() {
        online = false
        val poster = ConstantPoster(HttpResponse(201, null))
        val outbox = outboxWith("a")
        assertEquals(FlushBlock.NO_NETWORK, manager(outbox, poster).flush())
        assertEquals("no network means no request", 0, poster.callCount)
        assertEquals(1, outbox.size())
    }

    @Test
    fun anEmptyQueueIsNotAFailure() {
        val store = InMemoryStatusStore()
        // Seeded non-zero, per M7 in the review: seeding 0 and asserting 0 passes
        // against a manager that writes no status at all, which proves nothing.
        // Seeding a distinctive value means the assertion below only passes if
        // an empty queue genuinely leaves status untouched.
        store.write(store.read().copy(consecutiveFailures = 5))
        val result = manager(TelemetryOutbox(temp.newFile()), ConstantPoster(HttpResponse(500, null)), statusStore = store).flush()
        assertEquals(FlushBlock.EMPTY_QUEUE, result)
        assertEquals("nothing to send is not a failure", 5, store.read().consecutiveFailures)
    }

    @Test
    fun aBackoffDeadlineInTheFutureBlocksTheFlush() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(backoffUntilMs = now + 30_000))
        val poster = ConstantPoster(HttpResponse(201, null))
        val outbox = outboxWith("a")
        assertEquals(FlushBlock.BACKING_OFF, manager(outbox, poster, statusStore = store).flush())
        assertEquals("a live backoff deadline must not be tested against the network", 0, poster.callCount)
        assertEquals(1, outbox.size())
    }

    @Test
    fun activatedFalseBlocksAPlainFlushWithNotActivated() {
        val poster = ConstantPoster(HttpResponse(201, null))
        val outbox = outboxWith("a")
        val result = manager(outbox, poster, activated = false).flush()
        assertEquals(FlushBlock.NOT_ACTIVATED, result)
        assertEquals(0, poster.callCount)
        assertEquals(1, outbox.size())
    }

    /** Activation is also the configuration smoke test: a mistyped endpoint is
     *  caught at the bench rather than three weeks later. */
    @Test
    fun activateEnqueuesAnActivationRowAndFlushesIt() {
        val outbox = TelemetryOutbox(temp.newFile())
        val poster = ConstantPoster(HttpResponse(201, null))
        val result = manager(outbox, poster).activate()
        assertEquals(ActivationResult.Succeeded, result)
        assertEquals(0, outbox.size())
        assertTrue(poster.bodies.single().contains("install-1"))
    }

    /**
     * This is the deadlock the review flagged: activation is what sets
     * `activated`, so a gate check on `activated` made the button permanently
     * unable to succeed on a fresh kiosk. `activate()` must bypass that one
     * check while every other gate check still applies.
     */
    @Test
    fun activateSucceedsOnAFreshKioskEvenThoughActivatedIsStillFalse() {
        val outbox = TelemetryOutbox(temp.newFile())
        val poster = ConstantPoster(HttpResponse(201, null))
        val result = manager(outbox, poster, activated = false).activate()
        assertEquals(ActivationResult.Succeeded, result)
        assertEquals(0, outbox.size())
    }

    /** Even bypassing NOT_ACTIVATED, every other gate check still blocks
     *  activation — a disabled kiosk must not smoke-test anything.
     *
     *  FIX (I2): the gate is now evaluated before any mutation, so a blocked
     *  press changes nothing at all — this used to assert the opposite
     *  (the row survived because it was appended before the gate ran), which
     *  was precisely the cost I2 closes: an unsendable row queued forever. */
    @Test
    fun activateIsStillBlockedByEveryOtherGateCheck() {
        val outbox = TelemetryOutbox(temp.newFile())
        val poster = ConstantPoster(HttpResponse(201, null))
        val result = manager(outbox, poster, enabled = false, activated = false).activate()
        assertEquals(ActivationResult.Blocked(FlushBlock.DISABLED), result)
        assertEquals(0, poster.callCount)
        assertEquals("a blocked press must not queue anything", 0, outbox.size())
    }

    @Test
    fun aFailedActivationLeavesTheRowQueuedSoTheOperatorCanRetry() {
        val outbox = TelemetryOutbox(temp.newFile())
        val result = manager(outbox, ConstantPoster(HttpResponse(401, "bad key"))).activate()
        assertEquals(1, outbox.size())
        // 401 is retryable (not a row-level refusal), so the flush ran and did
        // not land — this must not be reported the same way as FlushBlock.NONE
        // used to make it look, which was indistinguishable from success.
        assertTrue("a non-landed activation must report Failed, not Succeeded", result is ActivationResult.Failed)
        assertTrue((result as ActivationResult.Failed).error?.contains("401") == true)
    }

    @Test
    fun theStoredErrorNeverContainsThePublishableKey() {
        val store = InMemoryStatusStore()
        manager(outboxWith("a"), ConstantPoster(HttpResponse(500, "rejected key publishable-key")), statusStore = store).flush()
        assertFalse(store.read().lastError!!.contains("publishable-key"))
    }

    @Test
    fun statusReportsTheQueueDepth() {
        val outbox = outboxWith("a", "b", "c")
        val store = InMemoryStatusStore()
        // Every stored field is seeded to a distinctive value so the test fails
        // if status() ever discards one of them in favour of a fresh queue-only
        // TelemetryStatus.
        store.write(
            TelemetryStatus(
                queued = 999,
                lastSuccessMs = 42L,
                lastError = "HTTP 503 down",
                consecutiveFailures = 7,
                backoffUntilMs = 123_456L
            )
        )
        val reported = manager(outbox, ConstantPoster(HttpResponse(201, null)), statusStore = store).status()
        assertEquals("queued must be recomputed from the outbox, not trusted from storage", 3, reported.queued)
        assertEquals(42L, reported.lastSuccessMs)
        assertEquals("HTTP 503 down", reported.lastError)
        assertEquals(7, reported.consecutiveFailures)
        assertEquals(123_456L, reported.backoffUntilMs)
    }

    // ── FIX 2: flush() has no exception handling ───────────────────────────

    @Test
    fun aThrowingUploadBacksOffRatherThanRetryingInATightLoop() {
        val store = InMemoryStatusStore()
        val outbox = outboxWith("a")
        val result = manager(
            outbox,
            ConstantPoster(HttpResponse(201, null)),
            statusStore = store,
            upload = { _, _ -> throw IllegalStateException("row 17: amount_cents=500") }
        ).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals("a throw must not remove anything the uploader never named", 1, outbox.size())
        assertEquals(1, store.read().consecutiveFailures)
        assertTrue("a throwing flush must still back off", store.read().backoffUntilMs > now)
        assertEquals(
            "the error must be the exception's class name, never its message",
            "java.lang.IllegalStateException",
            store.read().lastError
        )
        assertEquals("FIX 1: the throw path must also stamp when it happened", now, store.read().lastErrorAtMs)
    }

    @Test
    fun anAppendFailureInActivateSurfacesAsFailedRatherThanBeingSwallowedOrThrown() {
        // Pointing the outbox at a directory makes File.appendText throw, the
        // same shape TelemetryOutbox.append documents for a real disk failure.
        val outbox = TelemetryOutbox(temp.newFolder())
        val poster = ConstantPoster(HttpResponse(201, null))
        val result = manager(outbox, poster).activate()
        assertTrue("an append failure must surface as Failed, not Succeeded or a thrown exception", result is ActivationResult.Failed)
        assertEquals(0, poster.callCount)
    }

    // ── FIX 3: the success branch must require evidence, not just !retryable ─

    @Test
    fun anOutcomeThatUploadedAndRejectedNothingIsNotRecordedAsASuccess() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(lastError = "HTTP 503 down", consecutiveFailures = 3))
        val outbox = outboxWith("a")
        val result = manager(
            outbox,
            ConstantPoster(HttpResponse(201, null)),
            statusStore = store,
            // This exact shape is unreachable through the real TelemetryUploader
            // for a non-empty batch (see UploadOutcome's contract note) — which
            // is exactly why it needs an explicit test rather than a trust in
            // that reachability argument holding forever.
            upload = { _, _ -> UploadOutcome(emptySet(), emptySet(), false, null) }
        ).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals("nothing named by the outcome must be removed", 1, outbox.size())
        assertEquals("no evidence of success must not clear a real error", "HTTP 503 down", store.read().lastError)
        assertEquals("no evidence of success must not reset the failure count", 3, store.read().consecutiveFailures)
    }

    // ── FIX 4: partial outcomes and transport failures, driven for real ─────

    /**
     * A two-row batch is refused as a batch, then retried row by row: the first
     * row is accepted, the second is permanently refused. This is the exact
     * shape TelemetryUploader.upload uses its per-row fallback for, and it is
     * the only way to make rejectedIds non-empty through the real uploader.
     */
    @Test
    fun aPartialOutcomeRemovesBothTheUploadedAndTheRejectedRow() {
        val outbox = outboxWith("a", "b")
        val poster = ScriptedPoster(
            listOf(
                HttpResponse(400, "batch refused"), // the 2-row batch, refused together
                HttpResponse(201, null),             // row "a" retried alone: accepted
                HttpResponse(400, "row refused")     // row "b" retried alone: refused
            )
        )
        val result = manager(outbox, poster).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals("exactly the batch attempt plus one retry per row", 3, poster.callCount)
        assertEquals(
            "both the uploaded row and the sibling-corroborated rejected row must be removed",
            0,
            outbox.size()
        )
    }

    @Test
    fun aTransportFailureIsTreatedAsRetryableAndKeepsEveryRow() {
        val store = InMemoryStatusStore()
        val outbox = outboxWith("a", "b")
        val poster = ConstantPoster(HttpResponse(HttpResponse.TRANSPORT_FAILURE, null))
        val result = manager(outbox, poster, statusStore = store).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(1, poster.callCount)
        assertEquals("a transport failure must keep every row", 2, outbox.size())
        assertEquals(1, store.read().consecutiveFailures)
    }

    // ── FIX 5: missing coverage ──────────────────────────────────────────────

    @Test
    fun aBatchLargerThanPeeksLimitLeavesTheRemainderQueued() {
        val ids = (1..(TelemetryOutbox.DEFAULT_BATCH + 5)).map { "id-$it" }.toTypedArray()
        val outbox = outboxWith(*ids)
        val poster = ConstantPoster(HttpResponse(201, null))
        val result = manager(outbox, poster).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(1, poster.callCount)
        assertEquals(
            "only the first page is taken; the untaken remainder must survive",
            5,
            outbox.size()
        )
    }

    /**
     * Pins the deliberate decision (commented at the write site in
     * TelemetryManager): a partial success still moves lastSuccessMs, even
     * though the flush also backs off, because rows genuinely did land.
     */
    @Test
    fun aPartialSuccessMovesLastSuccessMsWhileStillBackingOff() {
        val store = InMemoryStatusStore()
        val outbox = outboxWith("a", "b", "c")
        val poster = ScriptedPoster(
            listOf(
                HttpResponse(400, "batch refused"),                    // 3-row batch, refused together
                HttpResponse(201, null),                                // row "a" retried alone: accepted
                HttpResponse(HttpResponse.TRANSPORT_FAILURE, null)      // row "b" retried alone: network dies
                // row "c" is never attempted — the fallback stops on transport failure
            )
        )
        val result = manager(outbox, poster, statusStore = store).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(3, poster.callCount)
        assertEquals("the accepted row must be removed; the untried and unresolved rows must not", 2, outbox.size())
        assertEquals(now, store.read().lastSuccessMs)
        assertTrue("a flush with a genuine failure must still back off", store.read().backoffUntilMs > now)
        assertEquals(1, store.read().consecutiveFailures)
    }

    // ── FIX 3: activate() on a kiosk with a deep backlog ────────────────────

    /**
     * peek() only ever takes DEFAULT_BATCH rows from the head of the queue, and
     * the activation row is appended at the tail. On a kiosk with at least that
     * many rows already queued, this flush's page never reaches the activation
     * row at all, so nothing is known yet about the destination — reporting
     * Failed here would tell the operator a healthy destination is broken.
     */
    @Test
    fun activationBehindADeepBacklogIsQueuedNotFailed() {
        val backlog = (1..TelemetryOutbox.DEFAULT_BATCH).map { "id-$it" }.toTypedArray()
        val outbox = outboxWith(*backlog)
        // 503 rather than 201, so the backlog batch itself is not removed —
        // isolating the assertion to what activate() reports, not queue mechanics.
        val poster = ConstantPoster(HttpResponse(503, "down"))
        val result = manager(outbox, poster).activate()
        assertEquals(
            "a row the page never reached must be Queued, not Failed",
            ActivationResult.Queued,
            result
        )
        assertEquals(
            "the activation row and the entire backlog must still be queued",
            TelemetryOutbox.DEFAULT_BATCH + 1,
            outbox.size()
        )
    }

    // ── FIX (I4): a status write concurrent with an in-flight flush must survive ─

    /**
     * Simulates TelemetryOutbox's onDropped callback landing on another thread
     * while this flush's network call is still in progress — exactly the
     * window flush() leaves open between reading `before` and writing the
     * outcome. The increment happens inside the `upload` seam because that is
     * the one point in this test that runs "during" the network call. Before
     * this fix, flush()'s outcome write copied from the stale `before` it read
     * at the top of the method, silently discarding whatever landed during the
     * upload; a `before.copy(...)` inside the new update{} transform would
     * reintroduce exactly that bug.
     */
    @Test
    fun aDropRecordedWhileAFlushIsInFlightSurvivesTheFlushsOwnStatusWrite() {
        val store = InMemoryStatusStore()
        val outbox = outboxWith("a")
        val result = manager(
            outbox,
            ConstantPoster(HttpResponse(201, null)),
            statusStore = store,
            upload = { _, batch ->
                store.update { it.copy(droppedCount = it.droppedCount + 3) }
                UploadOutcome(
                    uploadedIds = batch.map { it.id }.toSet(),
                    rejectedIds = emptySet(),
                    retryableFailure = false,
                    lastError = null
                )
            }
        ).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(
            "a drop recorded mid-flush must not be clobbered by flush's own outcome write",
            3,
            store.read().droppedCount
        )
    }

    // ── FIX 4: a fresh destination must be testable immediately ────────────

    /**
     * Operator types a wrong URL, activation fails, backoff climbs. Operator
     * fixes the URL and presses "Test connection" again (a fresh save(), then
     * activate()). Before this fix, BACKING_OFF could block that retest for up
     * to 60 minutes even though the destination is now correct.
     */
    @Test
    fun activateResetsAStaleBackoffSoAFreshDestinationIsTestableImmediately() {
        val store = InMemoryStatusStore()
        store.write(
            store.read().copy(
                consecutiveFailures = 6,
                backoffUntilMs = now + 55 * 60 * 1000,
                lastError = "HTTP 000 old destination unreachable"
            )
        )
        val outbox = TelemetryOutbox(temp.newFile())
        val poster = ConstantPoster(HttpResponse(201, null))
        val result = manager(outbox, poster, statusStore = store).activate()
        assertEquals(
            "a freshly-saved destination must not be refused by a stale backoff",
            ActivationResult.Succeeded,
            result
        )
    }

    /**
     * FIX 1: activate()'s pre-flush reset clears lastError to null, and a clean
     * success afterwards also clears it to null — neither writes a non-null
     * lastError, so neither may touch lastErrorAtMs. If either did, a stale
     * timestamp would masquerade as fresh, or a genuinely fresh one could be
     * wiped out from under an error still worth showing.
     */
    // ── FIX (I2): a blocked press must change nothing ───────────────────────

    /**
     * Before this fix, activate() reset lastError/consecutiveFailures/
     * backoffUntilMs and appended the activation row *before* the gate was
     * ever consulted, so an offline press destroyed the diagnostic the
     * operator opened the screen to read and left a row queued forever that
     * nothing would send until the next press. The gate must now be evaluated
     * first, so a press that cannot proceed leaves the outbox and the status
     * store untouched.
     */
    @Test
    fun aTestConnectionPressWithNoNetworkLeavesTheQueueAndTheDiagnosticUntouched() {
        online = false
        val store = InMemoryStatusStore()
        store.write(store.read().copy(lastError = "HTTP 000 old destination unreachable", consecutiveFailures = 3))
        val outbox = TelemetryOutbox(temp.newFile())
        val poster = ConstantPoster(HttpResponse(201, null))
        val result = manager(outbox, poster, statusStore = store).activate()
        assertEquals(ActivationResult.Blocked(FlushBlock.NO_NETWORK), result)
        assertEquals("no network must not queue an unsendable row", 0, outbox.size())
        assertEquals(
            "the diagnostic the operator came to read must survive a blocked press",
            "HTTP 000 old destination unreachable",
            store.read().lastError
        )
        assertEquals(3, store.read().consecutiveFailures)
        assertEquals(0, poster.callCount)
    }

    @Test
    fun activateDoesNotDisturbTheStoredErrorTimestampSinceItOnlyClearsTheErrorItself() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(lastError = "HTTP 000 old destination unreachable", lastErrorAtMs = 555L))
        val outbox = TelemetryOutbox(temp.newFile())
        val poster = ConstantPoster(HttpResponse(201, null))
        manager(outbox, poster, statusStore = store).activate()
        assertEquals(
            "neither the pre-flush reset nor a clean success may touch lastErrorAtMs",
            555L,
            store.read().lastErrorAtMs
        )
    }
}
