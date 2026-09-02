package com.sadaqah.kiosk.recovery

sealed class BluetoothRecoveryAction {
    data object Ignore : BluetoothRecoveryAction()
    data object ReEnable : BluetoothRecoveryAction()
}

/**
 * Decides when a Bluetooth radio that has gone dark should be switched back on.
 *
 * The kiosk cannot take a single donation without Bluetooth — the card reader
 * talks over BLE — so an off radio is never a valid resting state, no matter
 * who turned it off. Once it has been off longer than [offThresholdMs] the
 * caller is told to re-enable it.
 *
 * Timing lives here rather than in the caller so it can be tested without a
 * real adapter; the caller owns the polling loop and the actual radio call.
 */
class BluetoothRecoveryManager(
    private val offThresholdMs: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Start of the current outage, or 0 when the radio is believed to be on. */
    private var offSinceTimestamp: Long = 0L

    /** Whether the radio is currently believed to be off. */
    val isTrackingOutage: Boolean get() = offSinceTimestamp > 0L

    /** Called on STATE_OFF, and at startup if the radio is already off. */
    fun onBluetoothOff() {
        // Duplicate broadcasts must not push the deadline out, or a radio that
        // keeps re-announcing STATE_OFF would never reach the threshold.
        if (offSinceTimestamp == 0L) offSinceTimestamp = clock()
    }

    /** Called on STATE_ON. */
    fun onBluetoothOn() {
        offSinceTimestamp = 0L
    }

    /**
     * Polled by the caller. [cycleInProgress] is true while a deliberate
     * off → on cycle is running (see MainActivity.cycleBluetoothAdapter), which
     * we must never race — that path turns the radio back on by itself.
     *
     * Returning [BluetoothRecoveryAction.ReEnable] restarts the clock, so a
     * radio that refuses to come back is retried once per threshold rather than
     * on every tick. Tracking continues until a real STATE_ON arrives.
     */
    fun evaluate(cycleInProgress: Boolean): BluetoothRecoveryAction {
        if (offSinceTimestamp == 0L) return BluetoothRecoveryAction.Ignore
        if (cycleInProgress) return BluetoothRecoveryAction.Ignore
        if (clock() - offSinceTimestamp <= offThresholdMs) return BluetoothRecoveryAction.Ignore

        offSinceTimestamp = clock()
        return BluetoothRecoveryAction.ReEnable
    }
}
