package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import com.sadaqah.kiosk.recovery.RestartResult
import org.junit.Assert.*
import org.junit.Test

class RestartReportingTest {

    private val atMs = 1_700_000_000_000L
    private val causing = PendingDiagnostic(
        "causing", DiagnosticKind.CARD_READER_CONNECT_FAILED, atMs, """{"code":-1}"""
    )

    private fun report(result: RestartResult, alreadyGaveUp: Boolean = false) =
        RestartReporting.restartReport(
            result = result,
            causing = causing,
            reason = "card_reader_failures",
            alreadyGaveUp = alreadyGaveUp,
            nowMs = atMs,
            idFor = { "generated" }
        )

    private fun detailOf(entry: PendingDiagnostic) =
        JsonParser.parseString(entry.detailJson).asJsonObject

    /**
     * A restart is the case the markers exist for: hardRestart calls exit(0)
     * milliseconds later, so an asynchronous write of either row would be lost.
     */
    @Test
    fun aRestartMarksBothTheCausingFailureAndTheRestart() {
        val r = report(RestartResult.RESTART)
        assertEquals(listOf("causing", "generated"), r.toMarker.map { it.id })
        assertTrue(r.toReportNow.isEmpty())
        assertFalse(r.gaveUpReported)
    }

    @Test
    fun aRestartRowSaysItRestarted() {
        val detail = detailOf(report(RestartResult.RESTART).toMarker[1])
        assertEquals("card_reader_failures", detail["reason"].asString)
        assertEquals(
            "both restart_triggered rows carry an outcome, so they differ by a present field",
            "restarted", detail["outcome"].asString
        )
    }

    @Test
    fun belowThresholdReportsOnlyTheCausingFailureAndMarksNothing() {
        val r = report(RestartResult.BELOW_THRESHOLD)
        assertTrue(r.toMarker.isEmpty())
        assertEquals(listOf("causing"), r.toReportNow.map { it.id })
        assertFalse(r.gaveUpReported)
    }

    /** Cooldown is the policy working; the failure that put the kiosk there is
     *  reported through the causing diagnostic. */
    @Test
    fun cooldownReportsOnlyTheCausingFailure() {
        val r = report(RestartResult.COOLDOWN_ACTIVE)
        assertTrue(r.toMarker.isEmpty())
        assertEquals(listOf("causing"), r.toReportNow.map { it.id })
    }

    @Test
    fun theFirstGiveUpReportsItAndSaysSo() {
        val r = report(RestartResult.MAX_RESTARTS, alreadyGaveUp = false)
        assertTrue("nothing is restarting, so nothing needs a marker", r.toMarker.isEmpty())
        assertEquals(listOf("causing", "generated"), r.toReportNow.map { it.id })
        assertEquals("gave_up", detailOf(r.toReportNow[1])["outcome"].asString)
        assertTrue(r.gaveUpReported)
    }

    /**
     * MAX_RESTARTS is a level, not an edge: tryRestart returns it on every
     * failure once the cap is reached, and the failure counters clear only on
     * success. Without the latch this row would repeat forever.
     */
    @Test
    fun aSecondGiveUpReportsOnlyTheCausingFailure() {
        val r = report(RestartResult.MAX_RESTARTS, alreadyGaveUp = true)
        assertEquals(listOf("causing"), r.toReportNow.map { it.id })
        assertFalse(r.gaveUpReported)
    }
}
