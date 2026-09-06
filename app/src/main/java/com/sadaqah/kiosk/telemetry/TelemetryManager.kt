package com.sadaqah.kiosk.telemetry

/** The parts of the running configuration the manager reads afresh on every
 *  flush, so a toggle takes effect without reconstructing anything. */
data class TelemetryRuntime(
    val enabled: Boolean,
    val activated: Boolean,
    val identity: EventIdentity,
    val privacyPolicyUrl: String,
    val termsUrl: String
)

/**
 * The order of operations for sending telemetry: ask the gate, take a batch,
 * upload it, delete exactly what was accounted for, record the outcome.
 *
 * Every decision it makes is delegated — the gate decides whether to go, the
 * uploader decides what a response means. What lives here is the sequence, and
 * one rule the sequence must never break: **nothing is removed from the outbox
 * that the uploader did not name.** The queue is the only copy of a donation.
 */
class TelemetryManager(
    private val outbox: TelemetryOutbox,
    private val credentials: TelemetryCredentials,
    private val statusStore: TelemetryStatusStore,
    private val posterFor: (TelemetryConfig) -> HttpPoster,
    private val runtime: () -> TelemetryRuntime,
    private val networkAvailable: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun status(): TelemetryStatus = statusStore.read().copy(queued = outbox.size())

    /**
     * Writes the activation row and sends it immediately. This doubles as the
     * configuration smoke test: a successful insert is the operator's confirmation
     * that the destination is real, so a mistyped endpoint surfaces at the bench.
     * A failure leaves the row queued rather than discarding it, so retrying is
     * just another flush.
     */
    fun activate(): FlushBlock {
        val current = runtime()
        val event = TelemetryEvent.Activation(
            identity = current.identity,
            activatedAtIso = TelemetryEvent.nowIso(),
            privacyPolicyUrl = current.privacyPolicyUrl,
            termsUrl = current.termsUrl
        )
        outbox.append(event.id, event.table, event.payloadJson())
        return flush()
    }

    fun flush(): FlushBlock {
        val current = runtime()
        val config = credentials.load()
        val before = statusStore.read()
        val now = clock()

        val block = TelemetryGate.evaluate(
            GateInputs(
                enabled = current.enabled,
                configured = config != null,
                activated = current.activated,
                networkAvailable = networkAvailable(),
                queueDepth = outbox.size(),
                backoffUntilMs = before.backoffUntilMs
            ),
            now
        )
        if (block != FlushBlock.NONE || config == null) return block

        val batch = outbox.peek()
        if (batch.isEmpty()) return FlushBlock.EMPTY_QUEUE

        val outcome = TelemetryUploader(config.baseUrl, config.anonKey, posterFor(config))
            .upload(batch)

        // Both sets are safe to delete and neither is derived here: uploaded rows
        // reached the server, and a rejected row carries the uploader's own
        // sibling-corroborated proof that it never will. Anything absent from both
        // stays queued by construction.
        outbox.remove(outcome.uploadedIds + outcome.rejectedIds)

        statusStore.write(
            if (outcome.retryableFailure) {
                val failures = before.consecutiveFailures + 1
                before.copy(
                    lastError = outcome.lastError,
                    consecutiveFailures = failures,
                    backoffUntilMs = now + TelemetryGate.backoffDelayMs(failures),
                    // A partial success still moves the marker: rows did land, and
                    // an operator reading "last upload: never" while data arrives
                    // would chase a problem that is not there.
                    lastSuccessMs = if (outcome.uploadedIds.isEmpty()) before.lastSuccessMs else now
                )
            } else {
                before.copy(
                    lastError = null,
                    consecutiveFailures = 0,
                    backoffUntilMs = 0L,
                    lastSuccessMs = now
                )
            }
        )
        return FlushBlock.NONE
    }
}
