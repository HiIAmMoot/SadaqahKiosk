package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Test

class DiagnosticEventsTest {

    private val appVersion = "1.4.0"

    /** 2023-11-14T22:13:20Z exactly, so the ISO rendering has no fraction. */
    private val atMs = 1_700_000_000_000L

    private val enabled = Settings(
        analyticsEnabled = true,
        kioskCode = "nl-gld-arnhem-nour_al_houda-01",
        installId = "11111111-2222-3333-4444-555555555555"
    )
    private val disabled = enabled.copy(analyticsEnabled = false)

    private fun reported(result: DiagnosticEventResult): TelemetryEvent.Diagnostic =
        (result as DiagnosticEventResult.Report).event

    private fun payload(event: TelemetryEvent.Diagnostic) =
        JsonParser.parseString(event.payloadJson()).asJsonObject

    private fun boom(message: String = "boom"): Throwable =
        IllegalStateException(message).apply { stackTrace = arrayOf() }

    // ── The master switch ────────────────────────────────────────────────────

    @Test
    fun analyticsOffReportsNoCrash() {
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.crash(disabled, appVersion, Thread.currentThread(), boom(), null, atMs)
        )
    }

    @Test
    fun analyticsOffReportsNoRollback() {
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.updateRollback(disabled, appVersion, atMs, "1.4.0")
        )
    }

    @Test
    fun analyticsOffReportsNoUpdateInstalled() {
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.updateInstalled(disabled, appVersion, "1.3.6", atMs).result
        )
    }

    /** Before the first onCreate populates CrashContext there is no identity to
     *  report under — a null must behave like "off", not throw. */
    @Test
    fun nullSettingsReportsNothingRatherThanThrowing() {
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.crash(null, appVersion, Thread.currentThread(), boom(), null, atMs)
        )
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.updateRollback(null, appVersion, atMs, "1.4.0")
        )
    }

    // ── crash ────────────────────────────────────────────────────────────────

    @Test
    fun crashCarriesKindSeverityAndTheStackTrace() {
        val event = reported(
            DiagnosticEvents.crash(enabled, appVersion, Thread.currentThread(), boom(), null, atMs)
        )
        val row = payload(event)
        assertEquals("crash", row["kind"].asString)
        assertEquals("error", row["severity"].asString)
        assertEquals("2023-11-14T22:13:20Z", row["occurred_at"].asString)
        assertEquals(TelemetryTables.DIAGNOSTICS, event.table)
        assertTrue(row["stack_trace"].asString.contains("IllegalStateException"))
    }

    @Test
    fun crashDetailNamesTheThreadAndWhetherItWasMain() {
        val thread = Thread("payment-worker")
        val event = reported(
            DiagnosticEvents.crash(enabled, appVersion, thread, boom(), null, atMs)
        )
        val detail = payload(event)["detail"].asJsonObject
        assertEquals("payment-worker", detail["thread"].asString)
        assertFalse(detail["main"].asBoolean)
    }

    /**
     * The primary protection: an exact match on the affiliate key. Deliberately
     * short — under 32 characters — so the token-shape backstop cannot catch
     * it. A realistic UUID-shaped key is also token-shaped, so it would let
     * the backstop cover for a broken exact match; this key isolates the rule.
     */
    @Test
    fun crashScrubsTheAffiliateKeyOutOfTheStackTrace() {
        val key = "sumup-af-7c2e"
        val event = reported(
            DiagnosticEvents.crash(
                enabled, appVersion, Thread.currentThread(), boom("auth failed for $key"), key, atMs
            )
        )
        assertFalse(payload(event)["stack_trace"].asString.contains(key))
        assertTrue(
            "a key this short is not token-shaped, so only the exact-match rule can have scrubbed it",
            payload(event)["stack_trace"].asString.contains("auth failed for [redacted]")
        )
    }

    /**
     * The backstop, and the case that actually matters: a testMode kiosk never
     * loads the affiliate key, so at crash time the supplier returns blank and
     * the exact-match rule is inert. A UUID-shaped key is 36 chars of
     * [A-Za-z0-9-], so the 32+ token rule is what holds the line here.
     */
    @Test
    fun crashStillScrubsAKeyShapedTokenWhenNoKeyIsKnown() {
        val key = "0d1f4c2e-77aa-4b31-9f6e-2c5b8a1d3e40"
        val event = reported(
            DiagnosticEvents.crash(
                enabled, appVersion, Thread.currentThread(), boom("auth failed for $key"), "", atMs
            )
        )
        assertFalse(payload(event)["stack_trace"].asString.contains(key))
    }

    // ── update_rollback ──────────────────────────────────────────────────────

    @Test
    fun rollbackCarriesOutcomeAndTheVersionItRolledBackFrom() {
        val event = reported(
            DiagnosticEvents.updateRollback(enabled, "1.3.6", atMs, "1.4.0")
        )
        val row = payload(event)
        assertEquals("update_rollback", row["kind"].asString)
        assertEquals("error", row["severity"].asString)
        assertEquals("2023-11-14T22:13:20Z", row["occurred_at"].asString)
        val detail = row["detail"].asJsonObject
        assertEquals("attempted", detail["outcome"].asString)
        assertEquals(
            "without from_version a failed rollback and a successful one are the same row",
            "1.4.0", detail["from_version"].asString
        )
    }

    @Test
    fun rollbackWithNoRecordedVersionStillReports() {
        val detail = payload(
            reported(DiagnosticEvents.updateRollback(enabled, "1.3.6", atMs, null))
        )["detail"].asJsonObject
        assertEquals("attempted", detail["outcome"].asString)
        assertFalse(detail.has("from_version"))
    }

    /** No marker means no rollback happened — the caller must be able to tell
     *  that apart from "analytics is off". */
    @Test
    fun aZeroRollbackMarkerReportsNothing() {
        assertEquals(
            DiagnosticEventResult.NothingToReport,
            DiagnosticEvents.updateRollback(enabled, "1.3.6", 0L, "1.4.0")
        )
    }

    @Test
    fun aNegativeRollbackMarkerReportsNothing() {
        assertEquals(
            DiagnosticEventResult.NothingToReport,
            DiagnosticEvents.updateRollback(enabled, "1.3.6", -1L, "1.4.0")
        )
    }

    /** The analytics gate runs first: an off kiosk reports NotEnabled even with
     *  a valid marker, never NothingToReport — those are different facts. */
    @Test
    fun analyticsOffWithAValidMarkerStillReportsNotEnabled() {
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.updateRollback(disabled, "1.3.6", atMs, "1.4.0")
        )
    }

    // ── update_installed ─────────────────────────────────────────────────────

    @Test
    fun aFirstRunIsAnInstallationNotAnUpdate() {
        val decision = DiagnosticEvents.updateInstalled(enabled, "1.4.0", "", atMs)
        assertEquals(DiagnosticEventResult.NothingToReport, decision.result)
        assertEquals("1.4.0", decision.versionToStore)
    }

    @Test
    fun anUnchangedVersionReportsNothing() {
        assertEquals(
            DiagnosticEventResult.NothingToReport,
            DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.4.0", atMs).result
        )
    }

    @Test
    fun aChangedVersionReportsBothEnds() {
        val detail = payload(
            reported(DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.3.6", atMs).result)
        )["detail"].asJsonObject
        assertEquals("1.3.6", detail["from"].asString)
        assertEquals("1.4.0", detail["to"].asString)
    }

    @Test
    fun updateInstalledIsInfoSeverity() {
        val row = payload(
            reported(DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.3.6", atMs).result)
        )
        assertEquals("update_installed", row["kind"].asString)
        assertEquals("info", row["severity"].asString)
    }

    /**
     * A rollback moves the version backwards. The app does not decide that is a
     * downgrade — it reports the pair and lets the reader infer direction,
     * because versionName has carried a -preview suffix and any in-app
     * comparison rule would be a guess.
     */
    @Test
    fun aBackwardsMoveReportsTheSameWayWithThePairReversed() {
        val detail = payload(
            reported(DiagnosticEvents.updateInstalled(enabled, "1.3.6", "1.4.0", atMs).result)
        )["detail"].asJsonObject
        assertEquals("1.4.0", detail["from"].asString)
        assertEquals("1.3.6", detail["to"].asString)
    }

    // ── The always-advance rule ──────────────────────────────────────────────

    /**
     * The phase's most subtle rule. If the stored version were held back while
     * analytics was off, an operator enabling it months later would get a false
     * "this kiosk just updated" for a build it had been running since spring.
     */
    @Test
    fun theStoredVersionAdvancesEvenWhenNothingIsReported() {
        assertEquals(
            "1.4.0",
            DiagnosticEvents.updateInstalled(disabled, "1.4.0", "1.3.6", atMs).versionToStore
        )
        assertEquals(
            "1.4.0",
            DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.4.0", atMs).versionToStore
        )
        assertEquals(
            "1.4.0",
            DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.3.6", atMs).versionToStore
        )
    }

    // ── forKind: the generic entry point ─────────────────────────────────────

    @Test
    fun analyticsOffReportsNoDiagnosticOfAnyKind() {
        DiagnosticKind.values().forEach { kind ->
            assertEquals(
                "every kind must respect the master switch, not just the ones with bespoke builders",
                DiagnosticEventResult.NotEnabled,
                DiagnosticEvents.forKind(disabled, appVersion, kind, atMs)
            )
        }
    }

    @Test
    fun nullSettingsReportsNoDiagnosticOfAnyKind() {
        DiagnosticKind.values().forEach { kind ->
            assertEquals(
                DiagnosticEventResult.NotEnabled,
                DiagnosticEvents.forKind(null, appVersion, kind, atMs)
            )
        }
    }

    /**
     * Severity comes from the enum, so a call site cannot pair a kind with the
     * wrong one — but a builder that dropped severity entirely would be
     * invisible without this. Table-driven so a twelfth kind is covered the day
     * it is added.
     */
    @Test
    fun everyKindCarriesItsOwnWireStringsAndSeverity() {
        DiagnosticKind.values().forEach { kind ->
            val row = payload(reported(DiagnosticEvents.forKind(enabled, appVersion, kind, atMs)))
            assertEquals(kind.wire, row["kind"].asString)
            assertEquals(kind.severity.wire, row["severity"].asString)
            assertEquals("2023-11-14T22:13:20Z", row["occurred_at"].asString)
        }
    }

    @Test
    fun forKindWritesToTheDiagnosticsTable() {
        val event = reported(
            DiagnosticEvents.forKind(enabled, appVersion, DiagnosticKind.NETWORK_OUTAGE, atMs)
        )
        assertEquals(TelemetryTables.DIAGNOSTICS, event.table)
    }

    @Test
    fun forKindOmitsDetailEntirelyWhenNoneIsGiven() {
        val row = payload(
            reported(DiagnosticEvents.forKind(enabled, appVersion, DiagnosticKind.NETWORK_OUTAGE, atMs))
        )
        assertFalse(row.has("detail"))
    }

    /** The constructor drops unparseable detail rather than throwing. This is
     *  the phase that starts feeding it constructed JSON, so it is pinned here. */
    @Test
    fun malformedDetailIsDroppedRatherThanThrown() {
        val row = payload(
            reported(
                DiagnosticEvents.forKind(
                    enabled, appVersion, DiagnosticKind.NETWORK_OUTAGE, atMs,
                    detailJson = "{not valid json"
                )
            )
        )
        assertFalse(row.has("detail"))
    }

    @Test
    fun forKindScrubsTheAffiliateKeyOutOfDetail() {
        val key = "sumup-af-7c2e"
        val row = payload(
            reported(
                DiagnosticEvents.forKind(
                    enabled, appVersion, DiagnosticKind.UPDATE_INSTALL_FAILED, atMs,
                    detailJson = """{"reason":"failed for $key"}""",
                    affiliateKey = key
                )
            )
        )
        assertEquals("failed for [redacted]", row["detail"].asJsonObject["reason"].asString)
    }

    /** The testMode case: the key is never loaded, so the 32+ token backstop is
     *  the only rule standing. Same pairing as the crash tests. */
    @Test
    fun forKindStillScrubsAKeyShapedTokenWhenNoKeyIsKnown() {
        val token = "0d1f4c2e-77aa-4b31-9f6e-2c5b8a1d3e40"
        val row = payload(
            reported(
                DiagnosticEvents.forKind(
                    enabled, appVersion, DiagnosticKind.UPDATE_INSTALL_FAILED, atMs,
                    detailJson = """{"reason":"failed for $token"}""",
                    affiliateKey = ""
                )
            )
        )
        assertFalse(row["detail"].asJsonObject["reason"].asString.contains(token))
    }

    // ── The detail builders ──────────────────────────────────────────────────

    @Test
    fun networkOutageDetailCarriesBothTheDowntimeAndTheThresholdItCrossed() {
        val detail = JsonParser.parseString(
            DiagnosticEvents.networkOutageDetail(downtimeMs = 900_000L, thresholdMs = 300_000L)
        ).asJsonObject
        assertEquals(900_000L, detail["downtime_ms"].asLong)
        assertEquals(
            "without the threshold, an operator who later changes it makes every old row unreadable",
            300_000L, detail["threshold_ms"].asLong
        )
    }

    @Test
    fun bluetoothWatchdogDetailCarriesHowLongTheRadioWasOff() {
        val detail = JsonParser.parseString(
            DiagnosticEvents.bluetoothWatchdogDetail(offMs = 61_000L)
        ).asJsonObject
        assertEquals(61_000L, detail["off_ms"].asLong)
    }

    @Test
    fun installFailedDetailCarriesTheReason() {
        val detail = JsonParser.parseString(DiagnosticEvents.installFailedDetail("commit_failed"))
            .asJsonObject
        assertEquals("commit_failed", detail["reason"].asString)
    }

    @Test
    fun installFailedDetailOmitsTheReasonWhenThereIsNone() {
        val detail = JsonParser.parseString(DiagnosticEvents.installFailedDetail(null)).asJsonObject
        assertFalse(detail.has("reason"))
    }
}
