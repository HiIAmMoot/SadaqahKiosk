package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Test

class TelemetryGateTest {

    private val now = 1_000_000L

    private fun ready() = GateInputs(
        enabled = true,
        configured = true,
        activated = true,
        networkAvailable = true,
        queueDepth = 5
    )

    // ── Blocking conditions ──────────────────────────────────────────────────

    @Test
    fun allConditionsMet_allowsFlush() {
        assertEquals(FlushBlock.NONE, TelemetryGate.evaluate(ready(), now))
    }

    @Test
    fun disabled_blocks() {
        assertEquals(FlushBlock.DISABLED,
            TelemetryGate.evaluate(ready().copy(enabled = false), now))
    }

    @Test
    fun notConfigured_blocks() {
        assertEquals(FlushBlock.NOT_CONFIGURED,
            TelemetryGate.evaluate(ready().copy(configured = false), now))
    }

    @Test
    fun notActivated_blocks() {
        assertEquals(FlushBlock.NOT_ACTIVATED,
            TelemetryGate.evaluate(ready().copy(activated = false), now))
    }

    @Test
    fun noNetwork_blocks() {
        assertEquals(FlushBlock.NO_NETWORK,
            TelemetryGate.evaluate(ready().copy(networkAvailable = false), now))
    }

    @Test
    fun emptyQueue_blocks() {
        assertEquals(FlushBlock.EMPTY_QUEUE,
            TelemetryGate.evaluate(ready().copy(queueDepth = 0), now))
    }

    /** Reported reasons are for logs; the most fundamental one should win so a
     *  log line says "disabled" rather than "no network" on a disabled kiosk. */
    @Test
    fun disabledOutranksEveryOtherBlock() {
        val everythingWrong = GateInputs(
            enabled = false, configured = false, activated = false,
            networkAvailable = false, queueDepth = 0
        )
        assertEquals(FlushBlock.DISABLED, TelemetryGate.evaluate(everythingWrong, now))
    }

    @Test
    fun configurationOutranksNetwork() {
        val inputs = ready().copy(configured = false, networkAvailable = false)
        assertEquals(FlushBlock.NOT_CONFIGURED, TelemetryGate.evaluate(inputs, now))
    }

    // ── Backoff schedule ─────────────────────────────────────────────────────

    @Test
    fun backoff_firstFailureIsShort() {
        assertEquals(60_000L, TelemetryGate.backoffDelayMs(1))
    }

    @Test
    fun backoff_doublesPerFailure() {
        assertEquals(60_000L, TelemetryGate.backoffDelayMs(1))
        assertEquals(120_000L, TelemetryGate.backoffDelayMs(2))
        assertEquals(240_000L, TelemetryGate.backoffDelayMs(3))
    }

    @Test
    fun backoff_capsAtOneHour() {
        assertEquals(TelemetryGate.MAX_BACKOFF_MS, TelemetryGate.backoffDelayMs(20))
        assertEquals(3_600_000L, TelemetryGate.MAX_BACKOFF_MS)
    }

    @Test
    fun backoff_neverOverflowsOnAbsurdFailureCounts() {
        assertEquals(TelemetryGate.MAX_BACKOFF_MS, TelemetryGate.backoffDelayMs(1_000_000))
    }

    @Test
    fun backoff_zeroOrNegativeFailuresMeansNoDelay() {
        assertEquals(0L, TelemetryGate.backoffDelayMs(0))
        assertEquals(0L, TelemetryGate.backoffDelayMs(-1))
    }

    // ── Per-table status helper ──────────────────────────────────────────────

    @Test
    fun `effectiveBackoffUntilMs takes the latest live deadline`() {
        val now = 10_000L
        val status = TelemetryStatus(
            backoffUntilMsByTable = mapOf(
                TelemetryTables.DONATIONS to now + 5_000,
                TelemetryTables.DIAGNOSTICS to now + 30_000
            )
        )
        assertEquals(now + 30_000, status.effectiveBackoffUntilMs(now))
    }

    @Test
    fun `effectiveBackoffUntilMs ignores an elapsed deadline`() {
        val now = 10_000L
        val status = TelemetryStatus(
            backoffUntilMsByTable = mapOf(TelemetryTables.DONATIONS to now - 1)
        )
        assertEquals(0L, status.effectiveBackoffUntilMs(now))
    }

    /**
     * The aggregate is what makes a corrupt deadline dangerous: taking a
     * maximum, one entry beyond the ceiling dominates every healthy one and
     * freezes the screen for as long as the bad value says.
     *
     * Mutation check: delete the isTableBackedOff filter from
     * effectiveBackoffUntilMs and this test must fail.
     */
    @Test
    fun `effectiveBackoffUntilMs ignores a deadline beyond the ceiling`() {
        val now = 10_000L
        val status = TelemetryStatus(
            backoffUntilMsByTable = mapOf(
                TelemetryTables.DONATIONS to now + 5_000,
                TelemetryTables.DIAGNOSTICS to now + TelemetryGate.MAX_BACKOFF_MS + 1
            )
        )
        assertEquals(now + 5_000, status.effectiveBackoffUntilMs(now))
    }

    @Test
    fun `isTableBackedOff is false at the instant the deadline is reached`() {
        val now = 10_000L
        assertFalse(TelemetryGate.isTableBackedOff(now, now))
        assertTrue(TelemetryGate.isTableBackedOff(now + 1, now))
    }

    @Test
    fun `a deadline exactly at the ceiling still counts`() {
        val now = 10_000L
        assertTrue(TelemetryGate.isTableBackedOff(now + TelemetryGate.MAX_BACKOFF_MS, now))
    }

    @Test
    fun `a deadline past the ceiling fails open`() {
        val now = 10_000L
        assertFalse(
            "a corrected clock must not silence a kiosk for years",
            TelemetryGate.isTableBackedOff(now + TelemetryGate.MAX_BACKOFF_MS + 1, now)
        )
    }
}
