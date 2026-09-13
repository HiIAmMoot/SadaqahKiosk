package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.sadaqah.kiosk.recovery.RestartResult

/**
 * The decision, not the write. [toMarker] rows must survive a `Runtime.exit(0)`
 * a caller schedules milliseconds after a real restart; [toReportNow] rows are
 * for outcomes where nothing is about to kill the process. [gaveUpReported]
 * tells the caller whether to set [com.sadaqah.kiosk.recovery.RestartManager]'s
 * give-up latch, so the edge condition lives in one testable place instead of
 * being re-derived at the call site.
 */
data class RestartReport(
    val toMarker: List<PendingDiagnostic>,
    val toReportNow: List<PendingDiagnostic>,
    val gaveUpReported: Boolean
)

/** Pure: builds [PendingDiagnostic] values only. Never writes prefs, never
 *  calls [com.sadaqah.kiosk.recovery.RestartManager] — executing the decision
 *  is a later task's job. */
object RestartReporting {

    fun restartReport(
        result: RestartResult,
        causing: PendingDiagnostic,
        reason: String,
        failureCount: Int,
        alreadyGaveUp: Boolean,
        nowMs: Long,
        idFor: () -> String
    ): RestartReport {
        // The causing diagnostic earns its row only when it says something the
        // last one didn't: the first failure of an episode, the failure that
        // crossed the restart threshold, or the failure that triggers the
        // give-up row. Failures 2..N of an episode repeat what failure 1 (or a
        // prior give-up) already said — and on a kiosk whose reader never
        // recovers, that repetition is what evicts real donation rows from a
        // shared, capped outbox. clearCounters() ends an episode on any
        // success, so a kiosk that recovers starts counting from 1 again.
        val reportCausing = failureCount == 1 ||
            result == RestartResult.RESTART ||
            (result == RestartResult.MAX_RESTARTS && !alreadyGaveUp)
        val causingRows = if (reportCausing) listOf(causing) else emptyList()

        return when (result) {
            RestartResult.RESTART -> RestartReport(
                toMarker = causingRows + restartTriggeredRow(idFor(), nowMs, reason, outcome = "restarted"),
                toReportNow = emptyList(),
                gaveUpReported = false
            )

            RestartResult.BELOW_THRESHOLD, RestartResult.COOLDOWN_ACTIVE -> RestartReport(
                toMarker = emptyList(),
                toReportNow = causingRows,
                gaveUpReported = false
            )

            // A level, not an edge: this branch fires on every failure once the
            // kiosk has given up, forever, so alreadyGaveUp gates the extra row.
            RestartResult.MAX_RESTARTS -> if (alreadyGaveUp) {
                RestartReport(toMarker = emptyList(), toReportNow = causingRows, gaveUpReported = false)
            } else {
                RestartReport(
                    toMarker = emptyList(),
                    toReportNow = causingRows + restartTriggeredRow(idFor(), nowMs, reason, outcome = "gave_up"),
                    gaveUpReported = true
                )
            }
        }
    }

    private fun restartTriggeredRow(id: String, atMs: Long, reason: String, outcome: String): PendingDiagnostic {
        val detail = JsonObject()
        detail.addProperty("reason", reason)
        detail.addProperty("outcome", outcome)
        return PendingDiagnostic(id, DiagnosticKind.RESTART_TRIGGERED, atMs, detail.toString())
    }
}
