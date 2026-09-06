package com.sadaqah.kiosk.telemetry

/**
 * What the operator sees on the Analytics screen, and what the gate needs to know
 * about the last attempt.
 *
 * [lastError] is already redacted by the uploader before it arrives here.
 */
data class TelemetryStatus(
    val queued: Int = 0,
    val lastSuccessMs: Long = 0L,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val backoffUntilMs: Long = 0L
)

/**
 * A seam so the manager's bookkeeping is testable without SharedPreferences.
 * The persistent implementation arrives with the settings screen that reads it.
 */
interface TelemetryStatusStore {
    fun read(): TelemetryStatus
    fun write(status: TelemetryStatus)
}

class InMemoryStatusStore : TelemetryStatusStore {
    private var status = TelemetryStatus()
    override fun read(): TelemetryStatus = status
    override fun write(status: TelemetryStatus) { this.status = status }
}
