package com.sadaqah.kiosk.recovery

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BluetoothRecoveryManagerTest {

    private var now = 1_000_000L
    private val thresholdMs = 60_000L

    private fun createManager() = BluetoothRecoveryManager(thresholdMs, clock = { now })

    @Before
    fun setUp() {
        now = 1_000_000L
    }

    // ── Tracking state ───────────────────────────────────────────────────────

    @Test
    fun isTrackingOutage_initiallyFalse() {
        assertFalse(createManager().isTrackingOutage)
    }

    @Test
    fun isTrackingOutage_afterOff_true() {
        val mgr = createManager()
        mgr.onBluetoothOff()
        assertTrue(mgr.isTrackingOutage)
    }

    @Test
    fun isTrackingOutage_afterOn_false() {
        val mgr = createManager()
        mgr.onBluetoothOff()
        mgr.onBluetoothOn()
        assertFalse(mgr.isTrackingOutage)
    }

    /** Repeated STATE_OFF broadcasts must not keep pushing the deadline out. */
    @Test
    fun onBluetoothOff_repeated_doesNotResetTheClock() {
        val mgr = createManager()
        mgr.onBluetoothOff()

        now += 55_000L
        mgr.onBluetoothOff() // duplicate broadcast, 55s in

        now += 10_000L // 65s total since the FIRST off
        assertEquals(BluetoothRecoveryAction.ReEnable, mgr.evaluate(cycleInProgress = false))
    }

    // ── evaluate ─────────────────────────────────────────────────────────────

    @Test
    fun evaluate_bluetoothOn_returnsIgnore() {
        val mgr = createManager()
        now += 10 * 60_000L
        assertEquals(BluetoothRecoveryAction.Ignore, mgr.evaluate(cycleInProgress = false))
    }

    @Test
    fun evaluate_offButUnderThreshold_returnsIgnore() {
        val mgr = createManager()
        mgr.onBluetoothOff()

        now += 30_000L // half the threshold
        assertEquals(BluetoothRecoveryAction.Ignore, mgr.evaluate(cycleInProgress = false))
    }

    @Test
    fun evaluate_exactlyAtThreshold_returnsIgnore() {
        val mgr = createManager()
        mgr.onBluetoothOff()

        now += 60_000L // exactly the threshold — NOT over
        assertEquals(BluetoothRecoveryAction.Ignore, mgr.evaluate(cycleInProgress = false))
    }

    @Test
    fun evaluate_oneMilliOverThreshold_returnsReEnable() {
        val mgr = createManager()
        mgr.onBluetoothOff()

        now += 60_001L
        assertEquals(BluetoothRecoveryAction.ReEnable, mgr.evaluate(cycleInProgress = false))
    }

    /** A deliberate disconnect/reconnect cycle holds the radio off for a few
     *  seconds; the watchdog must never race it, however long it takes. */
    @Test
    fun evaluate_cycleInProgress_returnsIgnoreEvenPastThreshold() {
        val mgr = createManager()
        mgr.onBluetoothOff()

        now += 10 * 60_000L
        assertEquals(BluetoothRecoveryAction.Ignore, mgr.evaluate(cycleInProgress = true))
    }

    @Test
    fun evaluate_afterCycleFinishes_stillReEnables() {
        val mgr = createManager()
        mgr.onBluetoothOff()
        now += 90_000L

        assertEquals(BluetoothRecoveryAction.Ignore, mgr.evaluate(cycleInProgress = true))
        assertEquals(BluetoothRecoveryAction.ReEnable, mgr.evaluate(cycleInProgress = false))
    }

    // ── Retry backoff ────────────────────────────────────────────────────────

    /** A radio that refuses to come back on must be retried once per threshold,
     *  not on every poll tick. */
    @Test
    fun evaluate_afterReEnable_waitsAnotherFullThresholdBeforeRetrying() {
        val mgr = createManager()
        mgr.onBluetoothOff()

        now += 61_000L
        assertEquals(BluetoothRecoveryAction.ReEnable, mgr.evaluate(cycleInProgress = false))

        // Radio did not come back — still off, but too soon to retry.
        now += 30_000L
        assertEquals(BluetoothRecoveryAction.Ignore, mgr.evaluate(cycleInProgress = false))

        now += 31_000L // a full threshold since the last attempt
        assertEquals(BluetoothRecoveryAction.ReEnable, mgr.evaluate(cycleInProgress = false))
    }

    @Test
    fun evaluate_stillTrackingAfterReEnable() {
        val mgr = createManager()
        mgr.onBluetoothOff()

        now += 61_000L
        mgr.evaluate(cycleInProgress = false)

        // The radio hasn't confirmed STATE_ON yet, so we're still in an outage.
        assertTrue(mgr.isTrackingOutage)
    }

    @Test
    fun evaluate_afterRadioComesBack_returnsIgnore() {
        val mgr = createManager()
        mgr.onBluetoothOff()

        now += 61_000L
        assertEquals(BluetoothRecoveryAction.ReEnable, mgr.evaluate(cycleInProgress = false))

        mgr.onBluetoothOn()
        now += 10 * 60_000L
        assertEquals(BluetoothRecoveryAction.Ignore, mgr.evaluate(cycleInProgress = false))
    }

    // ── Full outage → recovery → second outage ───────────────────────────────

    @Test
    fun secondOutageAfterRecovery_isTrackedIndependently() {
        val mgr = createManager()
        mgr.onBluetoothOff()
        now += 61_000L
        mgr.evaluate(cycleInProgress = false)
        mgr.onBluetoothOn()

        now += 5 * 60_000L
        mgr.onBluetoothOff()

        now += 30_000L // only 30s into the SECOND outage
        assertEquals(BluetoothRecoveryAction.Ignore, mgr.evaluate(cycleInProgress = false))

        now += 31_000L
        assertEquals(BluetoothRecoveryAction.ReEnable, mgr.evaluate(cycleInProgress = false))
    }
}
