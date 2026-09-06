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

    /** The gate allowed a flush and the row was appended, but [TelemetryOutbox.peek]
     *  only ever takes [TelemetryOutbox.DEFAULT_BATCH] rows from the *head* of the
     *  queue, and this kiosk already had at least that many rows ahead of the
     *  activation row. This flush's page never reached it, so nothing is known yet
     *  about whether the destination works — this is **not** a failure of the
     *  destination, and must not be shown to the operator as one. The row stays
     *  queued and a later flush (automatic or another press of the button) will
     *  reach it. Tell the operator the destination is untested because of a
     *  backlog, not that it is broken. */
    object Queued : ActivationResult()
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
        TelemetryUploader(config.baseUrl, config.publishableKey, posterFor(config)).upload(batch)
    }
) {
    /** Set at the end of every [flush] call (and cleared at the start of every
     *  one), so [activate] can tell whether its own row made it without `flush`
     *  having to hand back anything richer than [FlushBlock]. Not meant for
     *  concurrent callers, same as the rest of this class. */
    private var lastUploadOutcome: UploadOutcome? = null

    /** The ids [TelemetryOutbox.peek] actually returned for the most recent
     *  [flush] call (set right after the peek, cleared at the start of every
     *  call, same lifetime as [lastUploadOutcome]). This is what lets [activate]
     *  tell "attempted this flush but not confirmed" (a real failure) apart from
     *  "never got a turn because the page didn't reach it" (FIX 3) — both leave
     *  the activation row absent from [lastUploadOutcome]'s two sets, so that
     *  alone can't distinguish them. */
    private var lastAttemptedIds: Set<String>? = null

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
     * configured, network, backoff) still applies, EXCEPT that a stale backoff
     * is cleared first — see the comment above that line for why.
     */
    fun activate(): ActivationResult {
        val current = runtime()

        // Same failure mode as the append below (a full disk, a broken store):
        // reading the queue depth for the pre-check can throw too, and this is
        // already the caller that owns turning that into Failed rather than an
        // uncaught exception — see the try/catch around outbox.append.
        val queueDepthAfterAppend = try {
            outbox.size() + 1
        } catch (t: Throwable) {
            return ActivationResult.Failed(t::class.java.name)
        }

        // Evaluate the gate against the inputs this press will actually leave
        // behind — activated forced true (this call is what earns it), backoff
        // forced to 0 (the reset a few lines down always clears it for real, so
        // a stale deadline genuinely cannot block this press), and the queue
        // depth counting the row about to be appended below. Doing this before
        // any mutation means a blocked press changes nothing: no reset, no
        // append, no destroyed diagnostic. Reusing TelemetryGate.evaluate rather
        // than re-deriving the precedence here is deliberate — a second copy of
        // that order would drift from the real one.
        val precheck = TelemetryGate.evaluate(
            GateInputs(
                enabled = current.enabled,
                configured = credentials.isConfigured(),
                activated = true,
                networkAvailable = networkAvailable(),
                queueDepth = queueDepthAfterAppend,
                backoffUntilMs = 0L
            ),
            clock()
        )
        if (precheck != FlushBlock.NONE) return ActivationResult.Blocked(precheck)

        // "Test connection" and activation are the same operator action (per the
        // spec), and pressing it is a deliberate, in-person override of an
        // automatic delay — most concretely: the destination was wrong, backoff
        // climbed towards the 60-minute ceiling, and the operator just typed a
        // different URL. Nothing else resets this bookkeeping (TelemetryCredentials
        // must not depend on TelemetryStatusStore, so it can't clear it from
        // save() without breaking that layering), and clearing it only here — as
        // opposed to a separate method the settings screen would have to remember
        // to call — means a corrected destination is testable on the very next
        // press of the button rather than possibly up to an hour later.
        statusStore.update { it.copy(consecutiveFailures = 0, backoffUntilMs = 0L, lastError = null) }

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
            // FIX 3: absence from the outcome is ambiguous by itself — it also
            // describes a row this flush never even sent. lastAttemptedIds
            // disambiguates: if the activation row wasn't in the page peek()
            // returned, nothing was attempted and this is a healthy kiosk with a
            // backlog, not a broken destination.
            lastAttemptedIds?.contains(event.id) != true -> ActivationResult.Queued
            else -> ActivationResult.Failed(statusStore.read().lastError)
        }
    }

    fun flush(): FlushBlock = flush(treatAsActivated = false)

    private fun flush(treatAsActivated: Boolean): FlushBlock {
        lastUploadOutcome = null
        lastAttemptedIds = null

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
        // Recorded regardless of what happens next, so activate() can tell a row
        // this page never reached (FIX 3) apart from one it sent but could not
        // confirm.
        lastAttemptedIds = batch.map { it.id }.toSet()
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
            // FIX (I4): transform against the value the store hands it, not
            // `before` — anything written to the store during the upload above
            // (an outbox eviction's droppedCount, chiefly) must survive this
            // write rather than being overwritten by a copy of a snapshot taken
            // before that write ever happened.
            statusStore.update { fresh ->
                val failures = fresh.consecutiveFailures + 1
                fresh.copy(
                    // Never the exception's message: it could carry a row value
                    // (a donation amount, a stack trace fragment) that never went
                    // through the redactor.
                    lastError = t::class.java.name,
                    lastErrorAtMs = finishedAt,
                    consecutiveFailures = failures,
                    backoffUntilMs = finishedAt + TelemetryGate.backoffDelayMs(failures)
                )
            }
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
                // FIX (I4): as above — transform the fresh value the store hands
                // back, not `before`. A `before.copy(...)` here would silently
                // discard a droppedCount bump (or anything else) written during
                // the network round trip above.
                statusStore.update { fresh ->
                    val failures = fresh.consecutiveFailures + 1
                    fresh.copy(
                        lastError = outcome.lastError,
                        // Only advanced when a non-null error is actually written —
                        // outcome.lastError is nullable in principle even though
                        // every reachable retryableFailure path in TelemetryUploader
                        // sets it alongside retryable = true.
                        lastErrorAtMs = if (outcome.lastError != null) finishedAt else fresh.lastErrorAtMs,
                        consecutiveFailures = failures,
                        backoffUntilMs = finishedAt + TelemetryGate.backoffDelayMs(failures),
                        // A partial success still moves the marker: rows did land, and
                        // an operator reading "last upload: never" while data arrives
                        // would chase a problem that is not there.
                        lastSuccessMs = if (outcome.uploadedIds.isEmpty()) fresh.lastSuccessMs else finishedAt
                    )
                }
            }
            // Evidence of a real outcome, not just the absence of a retryable one:
            // an outcome that uploaded or rejected nothing is not proof of success,
            // whatever the uploader says about retryableFailure. This is currently
            // unreachable for a non-empty batch (see UploadOutcome's own contract
            // note), but this class's whole charter is to never re-derive the
            // uploader's judgement, so it does not assume that shape either.
            outcome.uploadedIds.isNotEmpty() || outcome.rejectedIds.isNotEmpty() -> {
                statusStore.update { fresh ->
                    fresh.copy(
                        lastError = null,
                        consecutiveFailures = 0,
                        backoffUntilMs = 0L,
                        lastSuccessMs = finishedAt
                    )
                }
            }
            else -> Unit // Nothing happened. Status is left exactly as it was.
        }

        lastUploadOutcome = outcome
        return FlushBlock.NONE
    }
}
