package com.sadaqah.kiosk.recovery

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NetworkRecoveryManagerTest {

    private var now = 1_000_000L
    private val settings = Settings(longDowntimeThresholdSec = 120)

    private fun createManager() = NetworkRecoveryManager(settings, clock = { now })

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
}
