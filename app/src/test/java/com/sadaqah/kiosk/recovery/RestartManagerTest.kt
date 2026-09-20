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
    fun clearCardReaderFailures_leavesReinitFailuresAlone() {
        val mgr = createManager()
        mgr.recordCardReaderFailure()
        mgr.recordCardReaderFailure()
        mgr.recordReinitFailure()

        mgr.clearCardReaderFailures()
        assertEquals(0, mgr.cardReaderFailures)
        assertEquals(1, mgr.reinitFailures)
    }

    /** restartCount survives clearCardReaderFailures — only clearCounters()
     *  resets it — so the give-up latch must come down here too, or a kiosk
     *  that recovers and re-fails before restartCountResetSec elapses hits
     *  MAX_RESTARTS again with the latch already up and gives up a second
     *  time with no restart_triggered row. */
    @Test
    fun clearCardReaderFailures_alsoClearsTheGiveUpLatch() {
        val mgr = createManager()
        mgr.markGaveUpReported()
        mgr.clearCardReaderFailures()
        assertFalse(mgr.gaveUpReported)
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

    // ── Give-up latch ─────────────────────────────────────────────────────────

    @Test
    fun theGiveUpLatchStartsUnset() {
        assertFalse(createManager().gaveUpReported)
    }

    @Test
    fun markingTheGiveUpLatchSticksAcrossFurtherFailures() {
        val m = createManager()
        m.markGaveUpReported()
        m.recordCardReaderFailure()
        assertTrue(m.gaveUpReported)
    }

    @Test
    fun clearingTheCountersClearsTheGiveUpLatch() {
        val m = createManager()
        m.markGaveUpReported()
        m.clearCounters()
        assertFalse(m.gaveUpReported)
    }

    /**
     * Without this, raising maxRestartsBeforeGiveUp on a kiosk that had already
     * given up would let it restart and then give up a second time in silence.
     */
    @Test
    fun anActualRestartClearsTheGiveUpLatch() {
        val m = createManager()
        m.markGaveUpReported()
        // maxConsecutiveFailures is 3 and restartCount starts at 0, so this
        // reaches tryRestart's RESTART branch, not MAX_RESTARTS.
        val results = (1..3).map { m.recordCardReaderFailure() }
        assertEquals(RestartResult.RESTART, results.last())
        assertFalse(m.gaveUpReported)
    }

    /**
     * The mechanism Important-2 of the independent review flagged as untested:
     * tryRestart's MAX_RESTARTS return at line 85 happens *before* the latch
     * reset at line 97, so a give-up that is already latched must stay latched
     * through every failure that follows. Get this ordering wrong — move the
     * reset above the early return — and gave_up would repeat forever, because
     * reportRestart re-derives nothing; it trusts this getter. Reaches
     * MAX_RESTARTS for real (three genuine restarts, each past its own
     * cooldown) rather than asserting on a manually-set restartCount, so the
     * test exercises the exact path a regression would break.
     */
    @Test
    fun theGiveUpLatchSurvivesFailuresAfterMaxRestartsIsReached() {
        val m = createManager()
        repeat(3) {
            repeat(3) { m.recordCardReaderFailure() }
            now += 301_000L // past cooldown, so each group is a genuine restart
        }
        assertEquals(3, m.restartCount)

        // The failure that first hits the cap — this is what reportRestart
        // would see as the give-up edge, and what sets the latch for real.
        val firstGiveUp = m.recordCardReaderFailure()
        assertEquals(RestartResult.MAX_RESTARTS, firstGiveUp)
        m.markGaveUpReported()

        // Every failure after that must still see MAX_RESTARTS with the latch
        // still up, or the give-up row reportRestart derives from it repeats.
        val laterFailure = m.recordCardReaderFailure()
        assertEquals(RestartResult.MAX_RESTARTS, laterFailure)
        assertTrue(m.gaveUpReported)
    }

    /** Same guarantee on the other branch that returns without touching the
     *  latch — COOLDOWN_ACTIVE must not clear it either. */
    @Test
    fun theGiveUpLatchSurvivesACooldownActiveResult() {
        val m = createManager()
        repeat(3) { m.recordCardReaderFailure() } // restart #1, sets lastRestart
        m.markGaveUpReported()

        now += 100_000L // still within the 300s cooldown
        val result = m.recordCardReaderFailure() // over threshold again -> COOLDOWN_ACTIVE
        assertEquals(RestartResult.COOLDOWN_ACTIVE, result)
        assertTrue(m.gaveUpReported)
    }

    // ── CR-2: the login-success clear must not reset restart_count ──────────

    /**
     * MainActivity's login-success handler (`onActivityResult` request code 1)
     * calls `clearReinitFailures()` — not `clearCounters()` — on every
     * successful login, including the login that follows a restart triggered
     * by a reinit-failure streak. Clearing the full counters there would zero
     * restart_count, the very budget the streak is supposed to exhaust, and
     * MAX_RESTARTS would be unreachable for any fault that leaves login
     * working (every card-reader fault, the dominant driver). This drives
     * three full restart cycles, each followed by a successful login, and
     * pins that the fourth fault streak — not the first — is what finally
     * trips MAX_RESTARTS.
     */
    @Test
    fun loginSuccessBetweenCycles_doesNotResetRestartBudget_reachesMaxRestarts() {
        val m = createManager()
        repeat(3) {
            repeat(3) { m.recordReinitFailure() } // one full restart cycle
            now += 301_000L // past cooldown before the next fault streak
            m.clearReinitFailures() // the login-success handler, MainActivity:880
        }
        assertEquals(3, m.restartCount)

        now += 301_000L
        val result = (1..3).map { m.recordReinitFailure() }.last()
        assertEquals(RestartResult.MAX_RESTARTS, result)
    }
}
