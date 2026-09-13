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
        alreadyGaveUp: Boolean,
        nowMs: Long,
        idFor: () -> String
    ): RestartReport = when (result) {
        RestartResult.RESTART -> RestartReport(
            toMarker = listOf(causing, restartTriggeredRow(idFor(), nowMs, reason, outcome = "restarted")),
            toReportNow = emptyList(),
            gaveUpReported = false
        )

        RestartResult.BELOW_THRESHOLD, RestartResult.COOLDOWN_ACTIVE -> RestartReport(
            toMarker = emptyList(),
            toReportNow = listOf(causing),
            gaveUpReported = false
        )

        // A level, not an edge: this branch fires on every failure once the
        // kiosk has given up, forever, so alreadyGaveUp gates the extra row.
        RestartResult.MAX_RESTARTS -> if (alreadyGaveUp) {
            RestartReport(toMarker = emptyList(), toReportNow = listOf(causing), gaveUpReported = false)
        } else {
            RestartReport(
                toMarker = emptyList(),
                toReportNow = listOf(causing, restartTriggeredRow(idFor(), nowMs, reason, outcome = "gave_up")),
                gaveUpReported = true
            )
        }
    }

    private fun restartTriggeredRow(id: String, atMs: Long, reason: String, outcome: String): PendingDiagnostic {
        val detail = JsonObject()
        detail.addProperty("reason", reason)
        detail.addProperty("outcome", outcome)
        return PendingDiagnostic(id, DiagnosticKind.RESTART_TRIGGERED, atMs, detail.toString())
    }
}
