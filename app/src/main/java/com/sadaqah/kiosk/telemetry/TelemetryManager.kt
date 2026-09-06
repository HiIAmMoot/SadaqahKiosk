package com.sadaqah.kiosk.telemetry

import java.time.Instant

/** The parts of the running configuration the manager reads afresh on every
 *  flush, so a toggle takes effect without reconstructing anything. */
data class TelemetryRuntime(
    val enabled: Boolean,
    val activated: Boolean,
    val identity: EventIdentity,
    val privacyPolicyUrl: String,
    val termsUrl: String
)

/** What the operator sees after pressing "test connection". */
sealed class ActivationResult {
    /** The activation row reached the server. The destination is real. */
    object Succeeded : ActivationResult()

    /** Never attempted — the gate refused for [reason]. The row stays queued. */
    data class Blocked(val reason: FlushBlock) : ActivationResult()

    /** Attempted and did not land. [error] is already redacted. Row stays queued. */
    data class Failed(val error: String?) : ActivationResult()
}

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
    private val clock: () -> Long = System::currentTimeMillis,
    /** Seam over the uploader itself, not just the transport: it lets a test
     *  drive an [UploadOutcome] the real [TelemetryUploader] cannot produce for
     *  a non-empty batch (all-empty, non-retryable), which is exactly the shape
     *  the success branch below must not be fooled by. Production wiring never
     *  overrides this. */
    private val upload: (TelemetryConfig, List<QueuedEvent>) -> UploadOutcome = { config, batch ->
        TelemetryUploader(config.baseUrl, config.anonKey, posterFor(config)).upload(batch)
    }
) {
    /** Set at the end of every [flush] call (and cleared at the start of every
     *  one), so [activate] can tell whether its own row made it without `flush`
     *  having to hand back anything richer than [FlushBlock]. Not meant for
     *  concurrent callers, same as the rest of this class. */
    private var lastUploadOutcome: UploadOutcome? = null

    fun status(): TelemetryStatus = statusStore.read().copy(queued = outbox.size())

    /**
     * Writes the activation row and sends it immediately. This doubles as the
     * configuration smoke test: a successful insert is the operator's confirmation
     * that the destination is real, so a mistyped endpoint surfaces at the bench.
     * A failure leaves the row queued rather than discarding it, so retrying is
     * just another flush.
     *
     * Activation bypasses the gate's own `activated` check — that flag is what
     * this method exists to earn, so gating on it would make activation
     * permanently impossible on a fresh kiosk. Every other gate check (enabled,
     * configured, network, backoff) still applies.
     */
    fun activate(): ActivationResult {
        val current = runtime()
        val event = TelemetryEvent.Activation(
            identity = current.identity,
            activatedAtIso = Instant.ofEpochMilli(clock()).toString(),
            privacyPolicyUrl = current.privacyPolicyUrl,
            termsUrl = current.termsUrl
        )
        // A failure here must surface to the operator, not be folded into the
        // flush's generic retryable-failure handling below: this append happens
        // before flush() is even called, and TelemetryOutbox.append documents
        // that it throws on a full disk or a failed mkdirs — this is the caller
        // that owns that decision.
        try {
            outbox.append(event.id, event.table, event.payloadJson())
        } catch (t: Throwable) {
            return ActivationResult.Failed(t::class.java.name)
        }

        val block = flush(treatAsActivated = true)
        return when {
            block != FlushBlock.NONE -> ActivationResult.Blocked(block)
            lastUploadOutcome?.uploadedIds?.contains(event.id) == true -> ActivationResult.Succeeded
            else -> ActivationResult.Failed(statusStore.read().lastError)
        }
    }

    fun flush(): FlushBlock = flush(treatAsActivated = false)

    private fun flush(treatAsActivated: Boolean): FlushBlock {
        lastUploadOutcome = null

        val current = runtime()
        val config = credentials.load()
        val before = statusStore.read()
        val now = clock()

        val block = TelemetryGate.evaluate(
            GateInputs(
                enabled = current.enabled,
                configured = config != null,
                activated = current.activated || treatAsActivated,
                networkAvailable = networkAvailable(),
                queueDepth = outbox.size(),
                backoffUntilMs = before.backoffUntilMs
            ),
            now
        )
        if (block != FlushBlock.NONE) return block
        // Unreachable today — the gate already reports NOT_CONFIGURED whenever
        // config is null — but written so a null config can never fall through
        // as a false NONE if that ever changes.
        val cfg = config ?: return FlushBlock.NOT_CONFIGURED

        val batch = outbox.peek()
        if (batch.isEmpty()) return FlushBlock.EMPTY_QUEUE

        val outcome = try {
            upload(cfg, batch)
        } catch (t: Throwable) {
            // HttpPoster's contract says implementations must never throw, but a
            // future one could break that contract, and TelemetryOutbox.append
            // documents its own throw too. Either way: nothing was removed above
            // this line, so the queue is intact — but without this, a throwing
            // flush would leave consecutiveFailures and backoffUntilMs untouched,
            // and a failing kiosk would retry in a tight loop forever.
            val finishedAt = clock()
            val failures = before.consecutiveFailures + 1
            statusStore.write(
                before.copy(
                    // Never the exception's message: it could carry a row value
                    // (a donation amount, a stack trace fragment) that never went
                    // through the redactor.
                    lastError = t::class.java.name,
                    consecutiveFailures = failures,
                    backoffUntilMs = finishedAt + TelemetryGate.backoffDelayMs(failures)
                )
            )
            return FlushBlock.NONE
        }

        // Both sets are safe to delete and neither is derived here: uploaded rows
        // reached the server, and a rejected row carries the uploader's own
        // sibling-corroborated proof that it never will. Anything absent from both
        // stays queued by construction.
        outbox.remove(outcome.uploadedIds + outcome.rejectedIds)

        // Read fresh: the upload above may have issued up to one request per row
        // in the per-row fallback, so the pre-network `now` can be many seconds —
        // or on a slow link, minutes — stale by the time a backoff deadline is
        // computed from it.
        val finishedAt = clock()
        when {
            outcome.retryableFailure -> {
                val failures = before.consecutiveFailures + 1
                statusStore.write(
                    before.copy(
                        lastError = outcome.lastError,
                        consecutiveFailures = failures,
                        backoffUntilMs = finishedAt + TelemetryGate.backoffDelayMs(failures),
                        // A partial success still moves the marker: rows did land, and
                        // an operator reading "last upload: never" while data arrives
                        // would chase a problem that is not there.
                        lastSuccessMs = if (outcome.uploadedIds.isEmpty()) before.lastSuccessMs else finishedAt
                    )
                )
            }
            // Evidence of a real outcome, not just the absence of a retryable one:
            // an outcome that uploaded or rejected nothing is not proof of success,
            // whatever the uploader says about retryableFailure. This is currently
            // unreachable for a non-empty batch (see UploadOutcome's own contract
            // note), but this class's whole charter is to never re-derive the
            // uploader's judgement, so it does not assume that shape either.
            outcome.uploadedIds.isNotEmpty() || outcome.rejectedIds.isNotEmpty() -> {
                statusStore.write(
                    before.copy(
                        lastError = null,
                        consecutiveFailures = 0,
                        backoffUntilMs = 0L,
                        lastSuccessMs = finishedAt
                    )
                )
            }
            else -> Unit // Nothing happened. Status is left exactly as it was.
        }

        lastUploadOutcome = outcome
        return FlushBlock.NONE
    }
}
