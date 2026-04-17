package com.sadaqah.kiosk.recovery

import com.sadaqah.kiosk.model.Settings

sealed class NetworkLostAction {
    data object Ignore : NetworkLostAction()
    data object ShowMaintenanceAndDismissSumUp : NetworkLostAction()
}

sealed class NetworkRestoredAction {
    data object Ignore : NetworkRestoredAction()
    data object ResumeNormally : NetworkRestoredAction()
    data object AutoReinit : NetworkRestoredAction()
}

class NetworkRecoveryManager(
    private val settings: Settings,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var networkLostTimestamp: Long = 0L

    /** Whether an outage is currently being tracked. */
    val isTrackingOutage: Boolean get() = networkLostTimestamp > 0L

    /** Called when the device truly loses internet. Returns the action the caller should take. */
    fun onNetworkLost(isLoggedIn: Boolean, testMode: Boolean): NetworkLostAction {
        if (!isLoggedIn || testMode) return NetworkLostAction.Ignore
        networkLostTimestamp = clock()
        return NetworkLostAction.ShowMaintenanceAndDismissSumUp
    }

    /** Called when internet is restored. Returns the action the caller should take.
     *  Clears internal outage tracking regardless of the result. */
    fun onNetworkRestored(isLoggedIn: Boolean): NetworkRestoredAction {
        if (networkLostTimestamp == 0L) return NetworkRestoredAction.Ignore
        if (!isLoggedIn) {
            networkLostTimestamp = 0L
            return NetworkRestoredAction.Ignore
        }

        val downtime = clock() - networkLostTimestamp
        networkLostTimestamp = 0L

        return if (downtime > settings.longDowntimeThresholdSec * 1000L) {
            NetworkRestoredAction.AutoReinit
        } else {
            NetworkRestoredAction.ResumeNormally
        }
    }
}
