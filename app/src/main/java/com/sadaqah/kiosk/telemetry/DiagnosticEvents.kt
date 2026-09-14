package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.sadaqah.kiosk.model.Settings
import java.time.Instant

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
                stackTrace = throwable.stackTraceToString(),
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
     *  absence, not a null or false value, is what a dashboard reads as genuine. */
    fun sumUpFailureDetail(code: Int, message: String?, closedBy: String?): String =
        JsonObject().apply {
            addProperty("code", code)
            if (!message.isNullOrBlank()) addProperty("message", message)
            if (!closedBy.isNullOrBlank()) addProperty("closed_by", closedBy)
        }.toString()

    /** "pairing_timeout", not "timeout" — matches the label the finishActivity(2)
     *  site itself arms with, so the two rows this cause produces (this one and the
     *  card_reader_connect_failed it triggers) correlate on the same string. */
    fun pageTimeoutDetail(): String =
        JsonObject().apply { addProperty("closed_by", "pairing_timeout") }.toString()

    fun checkoutNoReaderDetail(code: Int, message: String?): String =
        JsonObject().apply {
            addProperty("code", code)
            if (!message.isNullOrBlank()) addProperty("message", message)
        }.toString()

    /** Reserved below [TelemetryRedactor.MAX_TEXT_BYTES] for the JSON wrapper
     *  [sumUpFailureDetail] and [checkoutNoReaderDetail] add around a message —
     *  braces, field names and quoting for the other fields (the longest
     *  wrapper is under 60 bytes) — so a message whose *escaped* form is
     *  truncated to this budget can never push the assembled detail over the
     *  cap and lose the whole row to [TelemetryEvent.Diagnostic]'s oversize
     *  drop. It does not need to budget for the message's own escaping —
     *  [truncateWrappedMessage] measures that directly instead of guessing at
     *  it, since a message that is mostly quotes, backslashes or control
     *  characters (a vendor error embedding a JSON blob, say) can nearly
     *  double in size once escaped, and a flat reserve sized for the common
     *  case silently stopped bounding the uncommon one. */
    private const val WRAPPED_MESSAGE_RESERVE_BYTES = 512

    private const val TRUNCATION_SUFFIX = "\n… truncated"

    /** Truncates a message that is about to be wrapped in [sumUpFailureDetail]
     *  or [checkoutNoReaderDetail], not the assembled JSON — truncating after
     *  wrapping can cut mid-document and lose the whole detail the same way
     *  an oversized one already does.
     *
     *  Bounds the message's *JSON-escaped* byte length, not its raw one:
     *  [TelemetryRedactor.truncate] cuts raw bytes, so a message built almost
     *  entirely of characters JSON escapes (quotes, backslashes, control
     *  characters) can pass that cut and still expand past the reserve once
     *  wrapped — the exact case `"x".repeat(n)` (no escapable character) can't
     *  exercise. JSON string escaping has no cross-character interaction, so
     *  the escaped length of a prefix is monotonic in the prefix's length —
     *  binary search finds the longest prefix whose escaped form still fits. */
    fun truncateWrappedMessage(message: String?): String? {
        if (message == null) return null
        val budget = TelemetryRedactor.MAX_TEXT_BYTES - WRAPPED_MESSAGE_RESERVE_BYTES
        if (jsonEscapedByteLength(message) <= budget) return message
        val contentBudget = (budget - jsonEscapedByteLength(TRUNCATION_SUFFIX)).coerceAtLeast(0)
        var lo = 0
        var hi = message.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (jsonEscapedByteLength(message.substring(0, mid)) <= contentBudget) lo = mid else hi = mid - 1
        }
        return message.substring(0, lo) + TRUNCATION_SUFFIX
    }

    /** The byte length a string contributes as a JSON string *value* — i.e.
     *  excluding the two quote characters [com.google.gson.JsonPrimitive]
     *  wraps every string in — computed the same way [sumUpFailureDetail] and
     *  [checkoutNoReaderDetail] actually encode it (via `JsonObject.
     *  addProperty`), so this measurement can't drift from what gets wrapped. */
    private fun jsonEscapedByteLength(s: String): Int {
        val quoted = com.google.gson.JsonPrimitive(s).toString()
        return quoted.toByteArray(Charsets.UTF_8).size - 2
    }

    private fun identityOf(settings: Settings?, appVersion: String): EventIdentity? {
        if (settings == null || !settings.analyticsEnabled) return null
        return EventIdentity.from(settings, appVersion)
    }
}
