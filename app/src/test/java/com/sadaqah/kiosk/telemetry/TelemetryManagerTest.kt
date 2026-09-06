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

    private fun manager(
        outbox: TelemetryOutbox,
        poster: HttpPoster,
        enabled: Boolean = true,
        activated: Boolean = true,
        credentials: TelemetryCredentials = TelemetryCredentials(InMemorySecretStore())
            .apply { save("https://abc.supabase.co", "anon-key") },
        statusStore: TelemetryStatusStore = InMemoryStatusStore()
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
        clock = { now }
    )

    private fun outboxWith(vararg ids: String): TelemetryOutbox {
        val outbox = TelemetryOutbox(temp.newFile())
        ids.forEach { outbox.append(it, TelemetryTables.DONATIONS, """{"id":"$it"}""") }
        return outbox
    }

    @Test
    fun aSuccessfulFlushDeletesTheUploadedRows() {
        val outbox = outboxWith("a", "b")
        val result = manager(outbox, { _, _, _ -> HttpResponse(201, null) }).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(0, outbox.size())
    }

    /**
     * The rows are the only copy. A retryable failure must leave every one of them
     * on disk — this is the assertion that stands between a network blip and a
     * night's donations.
     */
    @Test
    fun aRetryableFailureKeepsEveryRow() {
        val outbox = outboxWith("a", "b")
        val result = manager(outbox, { _, _, _ -> HttpResponse(503, "down") }).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(2, outbox.size())
    }

    @Test
    fun aRetryableFailureBacksOff() {
        val store = InMemoryStatusStore()
        manager(outboxWith("a"), { _, _, _ -> HttpResponse(503, "down") }, statusStore = store).flush()
        val status = store.read()
        assertEquals(1, status.consecutiveFailures)
        assertTrue("a failure must push the next attempt out", status.backoffUntilMs > now)
    }

    @Test
    fun aSuccessResetsTheFailureCount() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(consecutiveFailures = 4, backoffUntilMs = 0L))
        manager(outboxWith("a"), { _, _, _ -> HttpResponse(201, null) }, statusStore = store).flush()
        assertEquals(0, store.read().consecutiveFailures)
        assertEquals(now, store.read().lastSuccessMs)
        assertNull(store.read().lastError)
    }

    @Test
    fun theGateStopsAFlushBeforeAnyRequestIsMade() {
        var called = false
        val outbox = outboxWith("a")
        val result = manager(
            outbox,
            { _, _, _ -> called = true; HttpResponse(201, null) },
            enabled = false
        ).flush()
        assertEquals(FlushBlock.DISABLED, result)
        assertFalse("a disabled kiosk must not touch the network", called)
        assertEquals(1, outbox.size())
    }

    @Test
    fun anUnconfiguredKioskIsBlockedRatherThanUploadingToNowhere() {
        val result = manager(
            outboxWith("a"),
            { _, _, _ -> HttpResponse(201, null) },
            credentials = TelemetryCredentials(InMemorySecretStore())
        ).flush()
        assertEquals(FlushBlock.NOT_CONFIGURED, result)
    }

    @Test
    fun beingOfflineBlocksTheFlush() {
        online = false
        assertEquals(FlushBlock.NO_NETWORK, manager(outboxWith("a"), { _, _, _ -> HttpResponse(201, null) }).flush())
        online = true
    }

    @Test
    fun anEmptyQueueIsNotAFailure() {
        val store = InMemoryStatusStore()
        val result = manager(TelemetryOutbox(temp.newFile()), { _, _, _ -> HttpResponse(500, null) }, statusStore = store).flush()
        assertEquals(FlushBlock.EMPTY_QUEUE, result)
        assertEquals("nothing to send is not a failure", 0, store.read().consecutiveFailures)
    }

    @Test
    fun aBackoffDeadlineInTheFutureBlocksTheFlush() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(backoffUntilMs = now + 30_000))
        assertEquals(
            FlushBlock.BACKING_OFF,
            manager(outboxWith("a"), { _, _, _ -> HttpResponse(201, null) }, statusStore = store).flush()
        )
    }

    /** Activation is also the configuration smoke test: a mistyped endpoint is
     *  caught at the bench rather than three weeks later. */
    @Test
    fun activateEnqueuesAnActivationRowAndFlushesIt() {
        val outbox = TelemetryOutbox(temp.newFile())
        val bodies = mutableListOf<String>()
        val result = manager(outbox, { _, _, body -> bodies += body; HttpResponse(201, null) })
            .activate()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(0, outbox.size())
        assertTrue(bodies.single().contains("install-1"))
    }

    @Test
    fun aFailedActivationLeavesTheRowQueuedSoTheOperatorCanRetry() {
        val outbox = TelemetryOutbox(temp.newFile())
        manager(outbox, { _, _, _ -> HttpResponse(401, "bad key") }).activate()
        assertEquals(1, outbox.size())
    }

    @Test
    fun theStoredErrorNeverContainsTheAnonKey() {
        val store = InMemoryStatusStore()
        manager(outboxWith("a"), { _, _, _ -> HttpResponse(500, "rejected key anon-key") }, statusStore = store).flush()
        assertFalse(store.read().lastError!!.contains("anon-key"))
    }

    @Test
    fun statusReportsTheQueueDepth() {
        val outbox = outboxWith("a", "b", "c")
        assertEquals(3, manager(outbox, { _, _, _ -> HttpResponse(201, null) }).status().queued)
    }
}
