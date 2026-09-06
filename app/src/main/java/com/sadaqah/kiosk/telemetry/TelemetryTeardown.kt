package com.sadaqah.kiosk.telemetry

/**
 * Clearing the destination is three deletions, and leaving any one of them out is
 * a quiet failure rather than a loud one — which is why it is one named operation
 * with a test rather than three calls at a UI call site.
 */
object TelemetryTeardown {
    fun clearEverything(
        credentials: TelemetryCredentials,
        outbox: TelemetryOutbox,
        statusStore: TelemetryStatusStore
    ) {
        credentials.clear()
        // The rows identify a kiosk and nothing remains that could send them.
        outbox.clear()
        // A stored error would describe a destination that no longer exists.
        statusStore.write(TelemetryStatus())
    }
}
