package com.sadaqah.kiosk.recovery

import com.sadaqah.kiosk.model.Settings

enum class RestartResult {
    BELOW_THRESHOLD,
    RESTART,
    COOLDOWN_ACTIVE,
    MAX_RESTARTS
}

class RestartManager(
    private val store: KeyValueStore,
    private val settings: Settings,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val KEY_RESTART_COUNT = "restart_count"
        const val KEY_LAST_RESTART = "last_restart_timestamp"
        const val KEY_CARD_READER_FAILURES = "consecutive_card_reader_failures"
        const val KEY_REINIT_FAILURES = "consecutive_reinit_failures"
        const val KEY_GAVE_UP_REPORTED = "gave_up_reported"
    }

    /** Int 0/1 rather than a boolean: KeyValueStore exposes only int and long
     *  accessors, and widening it would drag the SharedPreferences
     *  implementation and the in-memory test fake along for one flag. */
    val gaveUpReported: Boolean get() = store.getInt(KEY_GAVE_UP_REPORTED) == 1

    fun markGaveUpReported() { store.putInt(KEY_GAVE_UP_REPORTED, 1) }

    /** Records a card reader connection failure.
     *  If the failure count reaches the threshold and restart guards allow it,
     *  atomically records the restart and returns [RestartResult.RESTART]. */
    fun recordCardReaderFailure(): RestartResult {
        val failures = store.getInt(KEY_CARD_READER_FAILURES) + 1
        store.putInt(KEY_CARD_READER_FAILURES, failures)
        if (failures < settings.maxConsecutiveFailures) return RestartResult.BELOW_THRESHOLD
        return tryRestart()
    }

    /** Records a SumUp reinit failure (login failed after reinit).
     *  Same threshold/guard logic as [recordCardReaderFailure]. */
    fun recordReinitFailure(): RestartResult {
        val failures = store.getInt(KEY_REINIT_FAILURES) + 1
        store.putInt(KEY_REINIT_FAILURES, failures)
        if (failures < settings.maxConsecutiveFailures) return RestartResult.BELOW_THRESHOLD
        return tryRestart()
    }

    /** Checks whether a restart would be allowed right now (guards only, ignores failure counts). */
    fun canRestart(): Boolean {
        val restartCount = store.getInt(KEY_RESTART_COUNT)
        if (restartCount >= settings.maxRestartsBeforeGiveUp) return false
        val lastRestart = store.getLong(KEY_LAST_RESTART)
        val now = clock()
        if (lastRestart > 0L && now - lastRestart < settings.restartCooldownSec * 1000L) return false
        return true
    }

    /** Clears all failure and restart counters. Returns true if any were non-zero. */
    fun clearCounters(): Boolean {
        val had = store.getInt(KEY_RESTART_COUNT) > 0 ||
                  store.getInt(KEY_CARD_READER_FAILURES) > 0 ||
                  store.getInt(KEY_REINIT_FAILURES) > 0
        store.putInt(KEY_RESTART_COUNT, 0)
        store.putInt(KEY_CARD_READER_FAILURES, 0)
        store.putInt(KEY_REINIT_FAILURES, 0)
        store.putInt(KEY_GAVE_UP_REPORTED, 0)
        return had
    }

    /** Resets the card reader failure counter and the give-up latch (e.g.
     *  after a successful connection). The latch has to come down here too:
     *  restartCount is untouched by this call and only clearCounters() resets
     *  it, so leaving the latch up lets a kiosk that recovers and re-fails
     *  inside restartCountResetSec hit MAX_RESTARTS again with alreadyGaveUp
     *  already true — a second give-up with no restart_triggered row, the
     *  exact silent failure anActualRestartClearsTheGiveUpLatch prevents on
     *  the RESTART path. */
    fun clearCardReaderFailures() {
        store.putInt(KEY_CARD_READER_FAILURES, 0)
        store.putInt(KEY_GAVE_UP_REPORTED, 0)
    }

    val restartCount: Int get() = store.getInt(KEY_RESTART_COUNT)
    // Read by tests only, and deliberately kept: nine reads in RestartManagerTest,
    // two of them asserting counter isolation — that a card-reader failure does not
    // move the reinit count, and that clearCardReaderFailures leaves it alone. The
    // second has no RestartResult of its own to lean on, and both read more directly
    // than inferring the counts from a threshold crossing.
    val cardReaderFailures: Int get() = store.getInt(KEY_CARD_READER_FAILURES)
    val reinitFailures: Int get() = store.getInt(KEY_REINIT_FAILURES)

    /** Checks guards, records the restart if allowed, returns the appropriate result. */
    private fun tryRestart(): RestartResult {
        val restartCount = store.getInt(KEY_RESTART_COUNT)
        if (restartCount >= settings.maxRestartsBeforeGiveUp) return RestartResult.MAX_RESTARTS

        val lastRestart = store.getLong(KEY_LAST_RESTART)
        val now = clock()
        if (lastRestart > 0L && now - lastRestart < settings.restartCooldownSec * 1000L) {
            return RestartResult.COOLDOWN_ACTIVE
        }

        store.putInt(KEY_RESTART_COUNT, restartCount + 1)
        store.putLong(KEY_LAST_RESTART, now)
        // Otherwise raising maxRestartsBeforeGiveUp on a kiosk that already
        // gave up lets it restart, then give up a second time in silence.
        store.putInt(KEY_GAVE_UP_REPORTED, 0)
        return RestartResult.RESTART
    }
}
