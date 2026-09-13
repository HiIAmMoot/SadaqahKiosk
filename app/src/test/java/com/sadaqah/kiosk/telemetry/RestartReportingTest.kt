package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import com.sadaqah.kiosk.recovery.RestartResult
import org.junit.Assert.*
import org.junit.Test

class RestartReportingTest {

    private val atMs = 1_700_000_000_000L
    // Distinct from atMs so a generated row that carried causing's stamp, or
    // 0L, would fail the occurredAtMs assertions below instead of slipping by.
    private val nowMs = 1_700_000_500_000L
    private val causing = PendingDiagnostic(
        "causing", DiagnosticKind.CARD_READER_CONNECT_FAILED, atMs, """{"code":-1}"""
    )

    private fun report(result: RestartResult, alreadyGaveUp: Boolean = false) =
        RestartReporting.restartReport(
            result = result,
            causing = causing,
            reason = "card_reader_failures",
            alreadyGaveUp = alreadyGaveUp,
            nowMs = nowMs,
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
        assertEquals(DiagnosticKind.RESTART_TRIGGERED, r.toMarker[1].kind)
        assertEquals(nowMs, r.toMarker[1].occurredAtMs)
        assertTrue(r.toReportNow.isEmpty())
        assertFalse(r.gaveUpReported)
    }

    /**
     * The latch does not gate restarts, only the give-up row — clearCounters
     * clears it too, so the only way to reach RESTART with it still set is an
     * operator raising maxRestartsBeforeGiveUp on a kiosk that had already
     * given up. That restart must still be marked, and the latch must come
     * back down, or it would give up a second time in silence.
     */
    @Test
    fun aRestartWhileAlreadyGivenUpStillMarksBothAndClearsTheFlag() {
        val r = report(RestartResult.RESTART, alreadyGaveUp = true)
        assertEquals(listOf("causing", "generated"), r.toMarker.map { it.id })
        assertEquals(DiagnosticKind.RESTART_TRIGGERED, r.toMarker[1].kind)
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
        assertFalse(r.gaveUpReported)
    }

    @Test
    fun theFirstGiveUpReportsItAndSaysSo() {
        val r = report(RestartResult.MAX_RESTARTS, alreadyGaveUp = false)
        assertTrue("nothing is restarting, so nothing needs a marker", r.toMarker.isEmpty())
        assertEquals(listOf("causing", "generated"), r.toReportNow.map { it.id })
        assertEquals(DiagnosticKind.RESTART_TRIGGERED, r.toReportNow[1].kind)
        assertEquals(nowMs, r.toReportNow[1].occurredAtMs)
        val detail = detailOf(r.toReportNow[1])
        assertEquals("card_reader_failures", detail["reason"].asString)
        assertEquals("gave_up", detail["outcome"].asString)
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
        assertTrue(r.toMarker.isEmpty())
        assertEquals(listOf("causing"), r.toReportNow.map { it.id })
        assertFalse(r.gaveUpReported)
    }
}
