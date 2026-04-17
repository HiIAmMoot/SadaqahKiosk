package com.sadaqah.kiosk.recovery

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RestartManagerTest {

    private lateinit var store: InMemoryStore
    private var now = 1_000_000L
    private val settings = Settings(
        maxConsecutiveFailures = 3,
        restartCooldownSec = 300,
        maxRestartsBeforeGiveUp = 3
    )

    private fun createManager() = RestartManager(store, settings, clock = { now })

    @Before
    fun setUp() {
        store = InMemoryStore()
        now = 1_000_000L
    }

    // ── Card reader failure tracking ─────────────────────────────────────────

    @Test
    fun cardReaderFailure_belowThreshold_returnsBelowThreshold() {
        val mgr = createManager()
        assertEquals(RestartResult.BELOW_THRESHOLD, mgr.recordCardReaderFailure())
        assertEquals(RestartResult.BELOW_THRESHOLD, mgr.recordCardReaderFailure())
        assertEquals(2, mgr.cardReaderFailures)
    }

    @Test
    fun cardReaderFailure_atThreshold_returnsRestart() {
        val mgr = createManager()
        mgr.recordCardReaderFailure()
        mgr.recordCardReaderFailure()
        val result = mgr.recordCardReaderFailure() // 3rd = threshold
        assertEquals(RestartResult.RESTART, result)
        assertEquals(1, mgr.restartCount)
    }

    @Test
    fun cardReaderFailure_pastThreshold_alsoTriggersRestart() {
        val mgr = createManager()
        repeat(3) { mgr.recordCardReaderFailure() } // triggers restart #1
        now += 301_000L // past cooldown
        val result = mgr.recordCardReaderFailure() // 4th failure, still >= threshold
        assertEquals(RestartResult.RESTART, result)
        assertEquals(2, mgr.restartCount)
    }

    // ── Reinit failure tracking ──────────────────────────────────────────────

    @Test
    fun reinitFailure_belowThreshold_returnsBelowThreshold() {
        val mgr = createManager()
        assertEquals(RestartResult.BELOW_THRESHOLD, mgr.recordReinitFailure())
        assertEquals(1, mgr.reinitFailures)
    }

    @Test
    fun reinitFailure_atThreshold_returnsRestart() {
        val mgr = createManager()
        mgr.recordReinitFailure()
        mgr.recordReinitFailure()
        assertEquals(RestartResult.RESTART, mgr.recordReinitFailure())
        assertEquals(1, mgr.restartCount)
    }

    // ── Failure types are independent ────────────────────────────────────────

    @Test
    fun failureTypes_independent_cardReaderDoesNotAffectReinit() {
        val mgr = createManager()
        mgr.recordCardReaderFailure()
        mgr.recordCardReaderFailure()
        // 2 card reader failures, 0 reinit failures
        assertEquals(RestartResult.BELOW_THRESHOLD, mgr.recordReinitFailure())
        assertEquals(2, mgr.cardReaderFailures)
        assertEquals(1, mgr.reinitFailures)
    }

    // ── Cooldown guard ───────────────────────────────────────────────────────

    @Test
    fun cooldown_preventsRestartWithinWindow() {
        val mgr = createManager()
        repeat(3) { mgr.recordCardReaderFailure() } // restart #1 at now=1_000_000
        assertEquals(1, mgr.restartCount)

        now += 100_000L // 100s — still within 300s cooldown
        // Push failures past threshold again
        val result = mgr.recordCardReaderFailure()
        assertEquals(RestartResult.COOLDOWN_ACTIVE, result)
        assertEquals(1, mgr.restartCount) // unchanged
    }

    @Test
    fun cooldown_allowsRestartAfterExpiry() {
        val mgr = createManager()
        repeat(3) { mgr.recordCardReaderFailure() } // restart #1
        assertEquals(1, mgr.restartCount)

        now += 301_000L // past 300s cooldown
        val result = mgr.recordCardReaderFailure()
        assertEquals(RestartResult.RESTART, result)
        assertEquals(2, mgr.restartCount)
    }

    // ── Max restarts guard ───────────────────────────────────────────────────

    @Test
    fun maxRestarts_preventsRestart() {
        val mgr = createManager()

        // Trigger 3 restarts (the max)
        repeat(3) {
            repeat(3) { mgr.recordCardReaderFailure() }
            now += 301_000L // skip cooldown between each
        }
        assertEquals(3, mgr.restartCount)

        // Next failure at threshold should be blocked
        now += 301_000L
        val result = mgr.recordCardReaderFailure()
        assertEquals(RestartResult.MAX_RESTARTS, result)
        assertEquals(3, mgr.restartCount) // unchanged
    }

    // ── canRestart ───────────────────────────────────────────────────────────

    @Test
    fun canRestart_freshState_returnsTrue() {
        assertTrue(createManager().canRestart())
    }

    @Test
    fun canRestart_duringCooldown_returnsFalse() {
        val mgr = createManager()
        repeat(3) { mgr.recordCardReaderFailure() } // triggers restart, sets timestamp
        assertFalse(mgr.canRestart())
    }

    @Test
    fun canRestart_atMaxRestarts_returnsFalse() {
        val mgr = createManager()
        repeat(3) {
            repeat(3) { mgr.recordCardReaderFailure() }
            now += 301_000L
        }
        assertFalse(mgr.canRestart())
    }

    // ── Clearing counters ────────────────────────────────────────────────────

    @Test
    fun clearCounters_resetsAll_returnsTrue() {
        val mgr = createManager()
        repeat(3) { mgr.recordCardReaderFailure() } // restart #1
        mgr.recordReinitFailure()

        assertTrue(mgr.clearCounters())
        assertEquals(0, mgr.restartCount)
        assertEquals(0, mgr.cardReaderFailures)
        assertEquals(0, mgr.reinitFailures)
    }

    @Test
    fun clearCounters_noPrior_returnsFalse() {
        assertFalse(createManager().clearCounters())
    }

    @Test
    fun clearCardReaderFailures_onlyResetsCardReader() {
        val mgr = createManager()
        mgr.recordCardReaderFailure()
        mgr.recordCardReaderFailure()
        mgr.recordReinitFailure()

        mgr.clearCardReaderFailures()
        assertEquals(0, mgr.cardReaderFailures)
        assertEquals(1, mgr.reinitFailures)
    }

    @Test
    fun clearCounters_thenFailAgain_startsFromZero() {
        val mgr = createManager()
        mgr.recordCardReaderFailure()
        mgr.recordCardReaderFailure()
        mgr.clearCounters()

        // First failure after clear — should be below threshold
        assertEquals(RestartResult.BELOW_THRESHOLD, mgr.recordCardReaderFailure())
        assertEquals(1, mgr.cardReaderFailures)
    }

    // ── First restart has no spurious cooldown ───────────────────────────────

    @Test
    fun firstRestart_noPriorTimestamp_notBlockedByCooldown() {
        val mgr = createManager()
        repeat(3) { mgr.recordCardReaderFailure() }
        // Should succeed even though lastRestart defaults to 0
        assertEquals(1, mgr.restartCount)
    }
}
