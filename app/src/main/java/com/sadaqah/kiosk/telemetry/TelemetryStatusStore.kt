package com.sadaqah.kiosk.telemetry

/**
 * What the operator sees on the Analytics screen, and what the gate needs to know
 * about the last attempt.
 *
 * [lastError] is already redacted by the uploader before it arrives here.
 * [lastErrorAtMs] is the flush clock at the moment [lastError] was written, kept
 * so a consumer can tell a fresh failure apart from one a later success has
 * already superseded — the queue emptying is not evidence of that: a flush can
 * empty the outbox by permanently rejecting rows (deleting the donations) while
 * also recording a retryable failure for the same flush, so queue depth alone
 * cannot be trusted to mean "this error is history". It is 0 until the first
 * error is ever recorded, which reads as "at the epoch" — always older than any
 * real [lastSuccessMs], so it never accidentally hides an unreported error.
 */
data class TelemetryStatus(
    val queued: Int = 0,
    val lastSuccessMs: Long = 0L,
    val lastError: String? = null,
    val lastErrorAtMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val backoffUntilMs: Long = 0L,
    /** Rows the outbox's count/age caps discarded outright — never sent, never
     *  recoverable. Distinct from a retryable failure: this is loss, not a
     *  pending retry. */
    val droppedCount: Int = 0
)

/**
 * A seam so the manager's bookkeeping is testable without SharedPreferences.
 * The persistent implementation arrives with the settings screen that reads it.
 */
interface TelemetryStatusStore {
    /**
     * [TelemetryStatus.queued] is **not** part of the persisted contract:
     * [InMemoryStatusStore] round-trips whatever was written to it, while
     * [PrefsStatusStore] always returns 0 and relies on a caller to overwrite it
     * with the outbox's real size. [TelemetryManager.status] is the only correct
     * source of a queue depth — reading a store directly for that field silently
     * renders 0 against the persistent implementation.
     */
    fun read(): TelemetryStatus
    fun write(status: TelemetryStatus)

    /**
     * Atomic read-modify-write: [transform] sees the value in the store *at the
     * moment this call runs*, never a snapshot captured earlier by the caller.
     * The default is not atomic on its own — a concurrent [write] between this
     * [read] and this [write] would still be lost — so both real implementations
     * override it under a lock. Exists because [TelemetryManager.flush] reads
     * status before a network call that can run for minutes and writes it after;
     * anything else that wrote to the store during that window (an outbox
     * eviction's drop count, chiefly) must not be clobbered by that stale write.
     */
    fun update(transform: (TelemetryStatus) -> TelemetryStatus) {
        write(transform(read()))
    }
}

class InMemoryStatusStore : TelemetryStatusStore {
    private var status = TelemetryStatus()
    override fun read(): TelemetryStatus = synchronized(this) { status }
    override fun write(status: TelemetryStatus) { synchronized(this) { this.status = status } }
    override fun update(transform: (TelemetryStatus) -> TelemetryStatus) {
        synchronized(this) { status = transform(status) }
    }
}
