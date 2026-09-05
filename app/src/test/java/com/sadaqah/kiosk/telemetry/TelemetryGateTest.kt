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
        queueDepth = 5,
        backoffUntilMs = 0L
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

    @Test
    fun withinBackoffWindow_blocks() {
        assertEquals(FlushBlock.BACKING_OFF,
            TelemetryGate.evaluate(ready().copy(backoffUntilMs = now + 1), now))
    }

    @Test
    fun backoffDeadlinePassed_allowsFlush() {
        assertEquals(FlushBlock.NONE,
            TelemetryGate.evaluate(ready().copy(backoffUntilMs = now), now))
    }

    /** Reported reasons are for logs; the most fundamental one should win so a
     *  log line says "disabled" rather than "no network" on a disabled kiosk. */
    @Test
    fun disabledOutranksEveryOtherBlock() {
        val everythingWrong = GateInputs(
            enabled = false, configured = false, activated = false,
            networkAvailable = false, queueDepth = 0, backoffUntilMs = now + 10_000
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
}
