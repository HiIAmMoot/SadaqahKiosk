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
    }

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
        return had
    }

    /** Resets just the card reader failure counter (e.g. after a successful connection). */
    fun clearCardReaderFailures() {
        store.putInt(KEY_CARD_READER_FAILURES, 0)
    }

    val restartCount: Int get() = store.getInt(KEY_RESTART_COUNT)
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
        return RestartResult.RESTART
    }
}
