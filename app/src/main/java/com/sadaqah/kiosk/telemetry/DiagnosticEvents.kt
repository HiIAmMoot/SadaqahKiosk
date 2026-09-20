package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.sadaqah.kiosk.model.Settings
import java.time.Instant

/**
 * A closed, fixed set of causes a card-reader or checkout failure can be
 * classified into. Every one of these three failure kinds is fed a vendor
 * SDK's own free-text status message, and [DiagnosticEvents.checkoutNoReaderDetail]
 * fires on a FAILED PAYMENT — exactly where that vendor is most likely to
 * name the transaction. The donor-facing disclosure states in every language
 * it ships that no transaction identifier is ever sent, so nothing narrower
 * than membership in this list may reach an event payload for these kinds.
 */
enum class SumUpFailureCause(val wire: String) {
    TIMEOUT("timeout"),
    READER_NOT_FOUND("reader_not_found"),
    NO_CONNECTIVITY("no_connectivity"),
    CANCELLED("cancelled"),
    DECLINED("declined"),
    /** Every message that does not match one of the phrases above, including
     *  one that happens to look like a transaction code — there is no
     *  narrower bucket to fall back to, and that is deliberate. */
    UNKNOWN("unknown")
}

sealed class DiagnosticEventResult {
    /** The operator declined analytics, or no identity is loaded yet. */
    object NotEnabled : DiagnosticEventResult()

    /** Analytics is on and there is simply nothing worth reporting. Distinct
     *  from [NotEnabled] because the version tracker must still advance. */
    object NothingToReport : DiagnosticEventResult()

    data class Report(val event: TelemetryEvent.Diagnostic) : DiagnosticEventResult()
}

/**
 * The report decision and the value the caller must persist, together. Returned
 * as one value so the always-advance rule is pinned by a test rather than by a
 * comment at a call site no test can reach.
 */
data class UpdateInstalledDecision(
    val result: DiagnosticEventResult,
    val versionToStore: String
)

/**
 * Decides whether a diagnostic is reported and in what shape.
 *
 * Pure, and that is the point: the callers are `MainActivity` and a
 * `BroadcastReceiver`, and no unit test can reach either. Same reasoning as
 * [DonationEvents], and the same bug it was written to avoid.
 */
object DiagnosticEvents {

    fun crash(
        settings: Settings?,
        appVersion: String,
        thread: Thread,
        throwable: Throwable,
        affiliateKey: String?,
        occurredAtMs: Long
    ): DiagnosticEventResult {
        val identity = identityOf(settings, appVersion) ?: return DiagnosticEventResult.NotEnabled
        val detail = JsonObject().apply {
            addProperty("thread", thread.name)
            addProperty("main", thread.name == "main")
        }
        return DiagnosticEventResult.Report(
            TelemetryEvent.Diagnostic(
                identity = identity,
                kind = DiagnosticKind.CRASH,
                occurredAtIso = Instant.ofEpochMilli(occurredAtMs).toString(),
                detailJson = detail.toString(),
                // Rendered here rather than at the call site so the rendering is
                // covered; Diagnostic's constructor scrubs and truncates it.
                stackTrace = classAndFrameTrace(throwable),
                affiliateKey = affiliateKey
            )
        )
    }

    fun updateRollback(
        settings: Settings?,
        appVersion: String,
        rollbackAtMs: Long,
        fromVersion: String?
    ): DiagnosticEventResult {
        val identity = identityOf(settings, appVersion) ?: return DiagnosticEventResult.NotEnabled
        // No marker means no rollback happened, which is a fact distinct from
        // "analytics is off" — the caller must be able to tell them apart.
        if (rollbackAtMs <= 0L) return DiagnosticEventResult.NothingToReport
        val detail = JsonObject().apply {
            // "attempted" is the honest word: the install being waited on
            // replaces the process waiting for it, so no outcome is knowable
            // here. from_version is what makes the outcome recoverable —
            // equal to the row's app_version means the rollback did not take.
            addProperty("outcome", "attempted")
            if (!fromVersion.isNullOrBlank()) addProperty("from_version", fromVersion)
        }
        return DiagnosticEventResult.Report(
            TelemetryEvent.Diagnostic(
                identity = identity,
                kind = DiagnosticKind.UPDATE_ROLLBACK,
                occurredAtIso = Instant.ofEpochMilli(rollbackAtMs).toString(),
                detailJson = detail.toString(),
                affiliateKey = null
            )
        )
    }

    fun updateInstalled(
        settings: Settings?,
        appVersion: String,
        storedVersion: String,
        occurredAtMs: Long
    ): UpdateInstalledDecision {
        // Computed before any gate: the stored version advances whatever the
        // outcome, or enabling analytics later manufactures a false update.
        val decision = { result: DiagnosticEventResult ->
            UpdateInstalledDecision(result, appVersion)
        }
        val identity = identityOf(settings, appVersion)
            ?: return decision(DiagnosticEventResult.NotEnabled)
        // An empty stored version is a first run, which is an installation
        // rather than an update, so it seeds and reports nothing.
        if (storedVersion.isBlank() || storedVersion == appVersion) {
            return decision(DiagnosticEventResult.NothingToReport)
        }
        val detail = JsonObject().apply {
            addProperty("from", storedVersion)
            addProperty("to", appVersion)
        }
        return decision(
            DiagnosticEventResult.Report(
                TelemetryEvent.Diagnostic(
                    identity = identity,
                    kind = DiagnosticKind.UPDATE_INSTALLED,
                    occurredAtIso = Instant.ofEpochMilli(occurredAtMs).toString(),
                    detailJson = detail.toString(),
                    affiliateKey = null
                )
            )
        )
    }

    /**
     * The generic entry point, for kinds whose detail is assembled by a builder
     * rather than derived from a decision. The gate is the same one every other
     * function here uses — there is no kind that bypasses the master switch.
     */
    fun forKind(
        settings: Settings?,
        appVersion: String,
        kind: DiagnosticKind,
        occurredAtMs: Long,
        detailJson: String? = null,
        affiliateKey: String? = null,
        // Defaults to a fresh id for every other caller; a drained
        // PendingDiagnostic passes its own id so a re-drain after a failed
        // removeDrained() reports the same event id instead of a new one.
        id: String = java.util.UUID.randomUUID().toString()
    ): DiagnosticEventResult {
        val identity = identityOf(settings, appVersion) ?: return DiagnosticEventResult.NotEnabled
        return DiagnosticEventResult.Report(
            TelemetryEvent.Diagnostic(
                identity = identity,
                kind = kind,
                occurredAtIso = Instant.ofEpochMilli(occurredAtMs).toString(),
                detailJson = detailJson,
                affiliateKey = affiliateKey,
                id = id
            )
        )
    }

    /**
     * Classifies a vendor status message into [SumUpFailureCause] by matching
     * a small set of fixed English phrases SumUp's SDK is documented to use
     * for these outcomes — never by forwarding any part of the message
     * itself. The message is discarded the instant this returns; the caller
     * never has the original text in hand to put on an event.
     *
     * This is deliberately not combined with the numeric result code: the
     * code is already a closed, small integer enum from the SDK and was
     * never the leak vector (it already travels unfiltered in the `code`
     * field), and folding SumUp's constants in here would tie this pure,
     * testable module to the SumUp SDK for no gain. Matching stays purely
     * text-based, which is also what makes it exhaustively testable against
     * adversarial input — including a message shaped like a transaction code,
     * which must fall through every branch to [SumUpFailureCause.UNKNOWN].
     */
    fun classifySumUpFailure(message: String?): SumUpFailureCause {
        val text = message.orEmpty()
        return when {
            text.contains("timeout", ignoreCase = true) -> SumUpFailureCause.TIMEOUT
            text.contains("not found", ignoreCase = true) -> SumUpFailureCause.READER_NOT_FOUND
            text.contains("connectivity", ignoreCase = true) ||
                text.contains("no connection", ignoreCase = true) -> SumUpFailureCause.NO_CONNECTIVITY
            text.contains("cancel", ignoreCase = true) -> SumUpFailureCause.CANCELLED
            text.contains("declin", ignoreCase = true) -> SumUpFailureCause.DECLINED
            else -> SumUpFailureCause.UNKNOWN
        }
    }

    /** Both numbers, because an operator who later changes the threshold would
     *  otherwise make every stored row uninterpretable. */
    fun networkOutageDetail(downtimeMs: Long, thresholdMs: Long): String =
        JsonObject().apply {
            addProperty("downtime_ms", downtimeMs)
            addProperty("threshold_ms", thresholdMs)
        }.toString()

    fun bluetoothWatchdogDetail(offMs: Long): String =
        JsonObject().apply { addProperty("off_ms", offMs) }.toString()

    /** A short fixed constant chosen at the fire site, never PackageInstaller's
     *  own status text: that is vendor-supplied and would make a poor grouping
     *  key on a dashboard. */
    fun installFailedDetail(reason: String?): String =
        JsonObject().apply {
            if (!reason.isNullOrBlank()) addProperty("reason", reason)
        }.toString()

    /** Shared by `sumup_reinit_failed` and `card_reader_connect_failed`. `closedBy`
     *  is present only for a close the app itself performed — a screensaver or a
     *  watchdog dismissing a stuck page — never for an operator-hit failure. Its
     *  absence, not a null or false value, is what a dashboard reads as genuine.
     *
     *  [cause] must already be one of [SumUpFailureCause]'s wire values — see
     *  [classifySumUpFailure]. There is deliberately no parameter here that
     *  accepts the vendor's own message text: `checkout_no_reader` fires on a
     *  failed *payment*, exactly the moment SumUp's SDK is most likely to name
     *  the transaction, and the donor-facing disclosure states in every
     *  language it ships that no transaction identifier is ever sent. A field
     *  that only ever holds membership in a closed, fixed set cannot violate
     *  that, no matter what the vendor's text looks like. */
    fun sumUpFailureDetail(code: Int, cause: SumUpFailureCause, closedBy: String?): String =
        JsonObject().apply {
            addProperty("code", code)
            addProperty("cause", cause.wire)
            if (!closedBy.isNullOrBlank()) addProperty("closed_by", closedBy)
        }.toString()

    /** "pairing_timeout", not "timeout" — matches the label the finishActivity(2)
     *  site itself arms with, so the two rows this cause produces (this one and the
     *  card_reader_connect_failed it triggers) correlate on the same string. */
    fun pageTimeoutDetail(): String =
        JsonObject().apply { addProperty("closed_by", "pairing_timeout") }.toString()

    /** See [sumUpFailureDetail] for why this takes a classified [cause] and
     *  not the vendor's message. */
    fun checkoutNoReaderDetail(code: Int, cause: SumUpFailureCause): String =
        JsonObject().apply {
            addProperty("code", code)
            addProperty("cause", cause.wire)
        }.toString()

    /**
     * Renders a throwable's class name and frame list, walking the cause
     * chain, but never `getMessage()`. A SumUp SDK exception has named a live
     * transaction in its message text — the same disclosure violation IM-12
     * covers on the checkout-failure path — and `stackTraceToString()` forwards
     * that message under the same 32-character scrub IM-12 was raised about,
     * which does not catch a short message ("TXN-48213" is 9 characters). The
     * class name and frame list are what an operator actually triages a crash
     * with — which type, in which file, reached from where — so this loses
     * only the one field that can carry donor-identifying vendor text. The
     * cause chain gets the same treatment because SumUp's own exceptions are
     * commonly wrapped by a plain RuntimeException/IOException whose message is
     * exactly where an SDK's text tends to end up. `seen` guards against a
     * throwable that is (directly or indirectly) its own cause, which the JVM
     * does not itself forbid.
     */
    private fun classAndFrameTrace(throwable: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = throwable
        val seen = mutableSetOf<Throwable>()
        var first = true
        while (current != null && seen.add(current)) {
            if (!first) out.append("Caused by: ")
            out.append(current::class.java.name).append('\n')
            for (frame in current.stackTrace) {
                out.append("\tat ").append(frame).append('\n')
            }
            current = current.cause
            first = false
        }
        return out.toString().trimEnd('\n')
    }

    private fun identityOf(settings: Settings?, appVersion: String): EventIdentity? {
        if (settings == null || !settings.analyticsEnabled) return null
        return EventIdentity.from(settings, appVersion)
    }
}
