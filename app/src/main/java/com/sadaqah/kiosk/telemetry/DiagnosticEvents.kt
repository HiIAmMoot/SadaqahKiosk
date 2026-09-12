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

    private fun identityOf(settings: Settings?, appVersion: String): EventIdentity? {
        if (settings == null || !settings.analyticsEnabled) return null
        return EventIdentity.from(settings, appVersion)
    }
}
