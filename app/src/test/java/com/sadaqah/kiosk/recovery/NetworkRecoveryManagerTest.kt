package com.sadaqah.kiosk.recovery

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NetworkRecoveryManagerTest {

    private var now = 1_000_000L
    private val settings = Settings(longDowntimeThresholdSec = 120)

    private fun createManager() = NetworkRecoveryManager(settings, clock = { now })

    // Alias matching the task-2 brief's helper name, kept distinct from
    // createManager() so no existing (passing) test needed to change.
    private fun manager() = createManager()

    @Before
    fun setUp() {
        now = 1_000_000L
    }

    // ── onNetworkLost ────────────────────────────────────────────────────────

    @Test
    fun networkLost_notLoggedIn_returnsIgnore() {
        val mgr = createManager()
        assertEquals(NetworkLostAction.Ignore, mgr.onNetworkLost(isLoggedIn = false, testMode = false))
        assertFalse(mgr.isTrackingOutage)
    }

    @Test
    fun networkLost_testMode_returnsIgnore() {
        val mgr = createManager()
        assertEquals(NetworkLostAction.Ignore, mgr.onNetworkLost(isLoggedIn = true, testMode = true))
        assertFalse(mgr.isTrackingOutage)
    }

    @Test
    fun networkLost_loggedIn_returnsShowMaintenance() {
        val mgr = createManager()
        val action = mgr.onNetworkLost(isLoggedIn = true, testMode = false)
        assertEquals(NetworkLostAction.ShowMaintenanceAndDismissSumUp, action)
        assertTrue(mgr.isTrackingOutage)
    }

    @Test
    fun networkLost_twice_updatesTimestamp() {
        val mgr = createManager()
        now = 1_000_000L
        mgr.onNetworkLost(isLoggedIn = true, testMode = false)

        now = 1_050_000L // 50s later, second "lost" event
        mgr.onNetworkLost(isLoggedIn = true, testMode = false)
        assertTrue(mgr.isTrackingOutage)

        // Restore 60s after the SECOND lost event — short downtime
        now = 1_110_000L
        assertEquals(NetworkRestoredAction.ResumeNormally, mgr.onNetworkRestored(isLoggedIn = true))
    }

    // ── onNetworkRestored ────────────────────────────────────────────────────

    @Test
    fun networkRestored_noOutageTracked_returnsIgnore() {
        val mgr = createManager()
        assertEquals(NetworkRestoredAction.Ignore, mgr.onNetworkRestored(isLoggedIn = true))
    }

    @Test
    fun networkRestored_shortDowntime_returnsResumeNormally() {
        val mgr = createManager()
        mgr.onNetworkLost(isLoggedIn = true, testMode = false)

        now += 60_000L // 60s — well under 120s threshold
        assertEquals(NetworkRestoredAction.ResumeNormally, mgr.onNetworkRestored(isLoggedIn = true))
        assertFalse(mgr.isTrackingOutage)
    }

    @Test
    fun networkRestored_longDowntime_returnsAutoReinit() {
        val mgr = createManager()
        mgr.onNetworkLost(isLoggedIn = true, testMode = false)

        now += 150_000L // 150s — over 120s threshold
        assertEquals(NetworkRestoredAction.AutoReinit, mgr.onNetworkRestored(isLoggedIn = true))
        assertFalse(mgr.isTrackingOutage)
    }

    @Test
    fun networkRestored_exactlyAtThreshold_returnsResumeNormally() {
        val mgr = createManager()
        mgr.onNetworkLost(isLoggedIn = true, testMode = false)

        now += 120_000L // exactly 120s = threshold — NOT over
        assertEquals(NetworkRestoredAction.ResumeNormally, mgr.onNetworkRestored(isLoggedIn = true))
    }

    @Test
    fun networkRestored_oneMilliOverThreshold_returnsAutoReinit() {
        val mgr = createManager()
        mgr.onNetworkLost(isLoggedIn = true, testMode = false)

        now += 120_001L // 1ms over threshold
        assertEquals(NetworkRestoredAction.AutoReinit, mgr.onNetworkRestored(isLoggedIn = true))
    }

    @Test
    fun networkRestored_notLoggedIn_returnsIgnoreAndClearsTracking() {
        val mgr = createManager()
        mgr.onNetworkLost(isLoggedIn = true, testMode = false)
        assertTrue(mgr.isTrackingOutage)

        // User logged out during the outage
        assertEquals(NetworkRestoredAction.Ignore, mgr.onNetworkRestored(isLoggedIn = false))
        assertFalse(mgr.isTrackingOutage)
    }

    // ── isTrackingOutage state ───────────────────────────────────────────────

    @Test
    fun isTrackingOutage_initiallyFalse() {
        assertFalse(createManager().isTrackingOutage)
    }

    @Test
    fun isTrackingOutage_afterLost_true() {
        val mgr = createManager()
        mgr.onNetworkLost(isLoggedIn = true, testMode = false)
        assertTrue(mgr.isTrackingOutage)
    }

    @Test
    fun isTrackingOutage_afterRestore_false() {
        val mgr = createManager()
        mgr.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 10_000L
        mgr.onNetworkRestored(isLoggedIn = true)
        assertFalse(mgr.isTrackingOutage)
    }

    @Test
    fun isTrackingOutage_lostWhileNotLoggedIn_false() {
        val mgr = createManager()
        mgr.onNetworkLost(isLoggedIn = false, testMode = false)
        assertFalse(mgr.isTrackingOutage)
    }

    // ── lastOutageMs ─────────────────────────────────────────────────────────

    @Test
    fun theOutageDurationIsZeroBeforeAnyOutage() {
        assertEquals(0L, manager().lastOutageMs)
    }

    @Test
    fun theOutageDurationMatchesTheDowntimeThatTriggeredAutoReinit() {
        val m = manager()
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 900_000L
        assertEquals(NetworkRestoredAction.AutoReinit, m.onNetworkRestored(isLoggedIn = true))
        assertEquals(
            "the call site cannot re-derive this: the timestamp is cleared before the return",
            900_000L, m.lastOutageMs
        )
    }

    @Test
    fun aShortOutageStillRecordsItsOwnDuration() {
        val m = manager()
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 1_000L
        assertEquals(NetworkRestoredAction.ResumeNormally, m.onNetworkRestored(isLoggedIn = true))
        assertEquals(1_000L, m.lastOutageMs)
    }

    /** A short outage must not leave a long one's number standing, or a
     *  diagnostic would report a downtime that never happened. */
    @Test
    fun aShortOutageDoesNotLeaveThePreviousLongOneStale() {
        val m = manager()
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 900_000L
        m.onNetworkRestored(isLoggedIn = true)
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 1_000L
        m.onNetworkRestored(isLoggedIn = true)
        assertEquals(1_000L, m.lastOutageMs)
    }

    @Test
    fun aRestorationWithNothingTrackedLeavesTheDurationUntouched() {
        val m = manager()
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 900_000L
        m.onNetworkRestored(isLoggedIn = true)
        m.onNetworkRestored(isLoggedIn = true) // nothing tracked — Ignore
        assertEquals(900_000L, m.lastOutageMs)
    }

    /** The spec's other ignore path: logged out during the outage. Distinct
     *  from the "nothing tracked" case above — this one clears the lost
     *  timestamp without touching lastOutageMs, per the lifecycle table. */
    @Test
    fun aRestorationWhileLoggedOutLeavesTheDurationUntouched() {
        val m = manager()
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 900_000L
        m.onNetworkRestored(isLoggedIn = true)
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 1_000L
        assertEquals(NetworkRestoredAction.Ignore, m.onNetworkRestored(isLoggedIn = false))
        assertEquals(900_000L, m.lastOutageMs)
    }

    // ── longDowntimeThresholdMs ──────────────────────────────────────────────

    /** Pins that the exposed threshold is the same number the comparison in
     *  onNetworkRestored actually uses, not a separately-derived one that
     *  could drift from it. */
    @Test
    fun theExposedThresholdMatchesWhatTheComparisonUses() {
        val m = manager()
        assertEquals(settings.longDowntimeThresholdSec * 1000L, m.longDowntimeThresholdMs)

        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += m.longDowntimeThresholdMs + 1 // one ms over the exposed threshold
        assertEquals(NetworkRestoredAction.AutoReinit, m.onNetworkRestored(isLoggedIn = true))
    }
}
