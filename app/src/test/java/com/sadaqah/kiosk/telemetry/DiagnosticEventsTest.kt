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
     * The affiliate key can only reach the stack trace text via a frame's own
     * class/method name, now that the message is never forwarded (IM-18) — an
     * attacker-influenced class name is far-fetched, but the scrub is
     * belt-and-braces and this pins that it still runs over whatever text
     * classAndFrameTrace does produce.
     */
    @Test
    fun crashScrubsTheAffiliateKeyOutOfAFrame() {
        val key = "sumup-af-7c2e"
        val thrown = boom("irrelevant since the message is dropped").apply {
            stackTrace = arrayOf(StackTraceElement("Reader", key, "Reader.java", 10))
        }
        val event = reported(
            DiagnosticEvents.crash(enabled, appVersion, Thread.currentThread(), thrown, key, atMs)
        )
        assertFalse(payload(event)["stack_trace"].asString.contains(key))
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

    /**
     * IM-18: neither scrubbing rule catches a short vendor message that names a
     * transaction without looking like a secret — "TXN-48213" is 9 characters,
     * far under the 32-char token threshold, and matches no affiliate key. The
     * only remedy that closes this is never forwarding `getMessage()` at all.
     */
    @Test
    fun crashStackTraceNeverIncludesTheExceptionMessage() {
        val event = reported(
            DiagnosticEvents.crash(
                enabled, appVersion, Thread.currentThread(),
                boom("card declined for order TXN-48213"), null, atMs
            )
        )
        val stackTrace = payload(event)["stack_trace"].asString
        assertFalse(stackTrace.contains("TXN-48213"))
        assertFalse(stackTrace.contains("card declined"))
    }

    /**
     * The class name and frame list are what an operator actually triages a
     * crash with — which line, in which file, reached from where. This pins
     * that dropping the message does not also drop the part that matters.
     */
    @Test
    fun crashStackTraceStillIdentifiesTheThrowSiteWithoutAMessage() {
        val thrown = try {
            throw IllegalStateException("card declined for order TXN-48213")
        } catch (t: Throwable) {
            t
        }
        val event = reported(
            DiagnosticEvents.crash(enabled, appVersion, Thread.currentThread(), thrown, null, atMs)
        )
        val stackTrace = payload(event)["stack_trace"].asString
        assertTrue(stackTrace.contains("IllegalStateException"))
        assertTrue(stackTrace.contains("DiagnosticEventsTest"))
    }

    /** SumUp SDK exceptions are frequently wrapped by a runtime exception whose
     *  own message is where an SDK's text tends to end up; the cause chain must
     *  get the same message-free treatment as the top-level throwable. */
    @Test
    fun crashStackTraceOmitsTheCauseMessageButKeepsItsClassName() {
        val cause = IllegalStateException("order TXN-48213 failed")
        val wrapper = RuntimeException("wrapped", cause)
        val event = reported(
            DiagnosticEvents.crash(enabled, appVersion, Thread.currentThread(), wrapper, null, atMs)
        )
        val stackTrace = payload(event)["stack_trace"].asString
        assertFalse(stackTrace.contains("TXN-48213"))
        assertFalse(stackTrace.contains("wrapped"))
        assertTrue(stackTrace.contains("IllegalStateException"))
        assertTrue(stackTrace.contains("RuntimeException"))
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

    /** A drained PendingDiagnostic passes its own id so a re-drain after a
     *  failed removeDrained() reports the same event id rather than a fresh
     *  one, letting a downstream consumer collapse the duplicate. */
    @Test
    fun forKindUsesTheGivenIdInsteadOfGeneratingOne() {
        val event = reported(
            DiagnosticEvents.forKind(
                enabled, appVersion, DiagnosticKind.NETWORK_OUTAGE, atMs, id = "pending-entry-id"
            )
        )
        assertEquals("pending-entry-id", event.id)
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

    @Test
    fun aSumUpFailureDetailCarriesTheCodeAndClassifiedCause() {
        val d = JsonParser.parseString(
            DiagnosticEvents.sumUpFailureDetail(
                code = 7,
                cause = DiagnosticEvents.classifySumUpFailure("Card reader not found"),
                closedBy = null
            )
        ).asJsonObject
        assertEquals(7, d["code"].asInt)
        assertEquals("reader_not_found", d["cause"].asString)
        assertFalse("the vendor's own wording must never reach the payload", d.has("message"))
        assertFalse("absent closed_by is what marks a genuine failure", d.has("closed_by"))
    }

    @Test
    fun aSyntheticCloseIsNamedOnTheRow() {
        val d = JsonParser.parseString(
            DiagnosticEvents.sumUpFailureDetail(
                code = -1,
                cause = DiagnosticEvents.classifySumUpFailure(null),
                closedBy = "pairing_timeout"
            )
        ).asJsonObject
        // Asserted explicitly rather than left to the deref below: a missing
        // key must fail this test on its own terms, not via a stray NPE.
        assertTrue(d.has("closed_by"))
        assertEquals("pairing_timeout", d["closed_by"].asString)
        assertFalse(d.has("message"))
    }

    /** Blank collapses to absent exactly like null does — the field's presence,
     *  not its nullness, is the discriminator a dashboard reads. */
    @Test
    fun aBlankOrWhitespaceClosedByIsAbsentJustLikeNull() {
        val cause = DiagnosticEvents.classifySumUpFailure(null)
        val blank = JsonParser.parseString(
            DiagnosticEvents.sumUpFailureDetail(code = 1, cause = cause, closedBy = "")
        ).asJsonObject
        assertFalse(blank.has("closed_by"))

        val whitespace = JsonParser.parseString(
            DiagnosticEvents.sumUpFailureDetail(code = 1, cause = cause, closedBy = "   ")
        ).asJsonObject
        assertFalse(whitespace.has("closed_by"))
    }

    @Test
    fun aPageTimeoutSaysWhatClosedIt() {
        val d = JsonParser.parseString(DiagnosticEvents.pageTimeoutDetail()).asJsonObject
        assertTrue(d.has("closed_by"))
        assertEquals("pairing_timeout", d["closed_by"].asString)
    }

    @Test
    fun aCheckoutWithNoReaderCarriesTheClassifiedCause() {
        val d = JsonParser.parseString(
            DiagnosticEvents.checkoutNoReaderDetail(
                code = 3,
                cause = DiagnosticEvents.classifySumUpFailure("Payment declined by issuer")
            )
        ).asJsonObject
        assertEquals(3, d["code"].asInt)
        assertEquals("declined", d["cause"].asString)
        assertFalse(d.has("message"))
    }

    @Test
    fun checkoutNoReaderDetailAlwaysCarriesACause() {
        val d = JsonParser.parseString(
            DiagnosticEvents.checkoutNoReaderDetail(code = 3, cause = DiagnosticEvents.classifySumUpFailure(null))
        ).asJsonObject
        assertEquals(3, d["code"].asInt)
        assertEquals("unknown", d["cause"].asString)
    }

    // ── IM-12: the vendor message itself must never reach an event ─────────────

    /**
     * `checkout_no_reader` fires on a FAILED PAYMENT — precisely the moment a
     * vendor SDK is most likely to name the transaction — and the old filter
     * (an exact affiliate-key match plus a 32+ character token regex) let a
     * ~10 character SumUp transaction code straight through. A classifier
     * that only ever emits membership in a closed, fixed set cannot leak one,
     * no matter what shape the vendor text takes: this asserts the exact
     * failure shape the finding named, end to end through the wire JSON.
     */
    @Test
    fun aVendorMessageShapedLikeATransactionCodeNeverReachesTheDetail() {
        val vendorMessage = "Transaction TX4F92K1QZ could not be completed"
        val cause = DiagnosticEvents.classifySumUpFailure(vendorMessage)
        val wire = DiagnosticEvents.checkoutNoReaderDetail(code = 3, cause = cause)

        assertFalse(
            "the classified cause must be one of the fixed constants, never the vendor text",
            wire.contains("TX4F92K1QZ")
        )
        assertFalse(wire.contains("Transaction"))
        assertEquals(SumUpFailureCause.UNKNOWN, cause)
        assertEquals("unknown", JsonParser.parseString(wire).asJsonObject["cause"].asString)
    }

    @Test
    fun classifySumUpFailureRecognisesTimeout() {
        assertEquals(
            SumUpFailureCause.TIMEOUT,
            DiagnosticEvents.classifySumUpFailure("Connection timeout while pairing")
        )
    }

    @Test
    fun classifySumUpFailureRecognisesReaderNotFound() {
        assertEquals(
            SumUpFailureCause.READER_NOT_FOUND,
            DiagnosticEvents.classifySumUpFailure("Card reader not found")
        )
    }

    @Test
    fun classifySumUpFailureRecognisesNoConnectivity() {
        assertEquals(
            SumUpFailureCause.NO_CONNECTIVITY,
            DiagnosticEvents.classifySumUpFailure("No connectivity to SumUp servers")
        )
    }

    @Test
    fun classifySumUpFailureRecognisesCancelled() {
        assertEquals(
            SumUpFailureCause.CANCELLED,
            DiagnosticEvents.classifySumUpFailure("User cancelled the transaction")
        )
    }

    @Test
    fun classifySumUpFailureRecognisesDeclined() {
        assertEquals(
            SumUpFailureCause.DECLINED,
            DiagnosticEvents.classifySumUpFailure("Payment declined by issuer")
        )
    }

    @Test
    fun classifySumUpFailureFallsBackToUnknownForANullMessage() {
        assertEquals(SumUpFailureCause.UNKNOWN, DiagnosticEvents.classifySumUpFailure(null))
    }

    /** The wordings that matter are the SDK's, not this codebase's. Every one of
     *  these landed in UNKNOWN until the rules were widened, and between them
     *  they are the commonest decline, the commonest reinit failure and the
     *  commonest pairing failure a kiosk actually sees. */
    @Test
    fun classifySumUpFailureRecognisesTheSdkOwnWordings() {
        assertEquals(SumUpFailureCause.DECLINED, DiagnosticEvents.classifySumUpFailure("Transaction failed"))
        assertEquals(SumUpFailureCause.NOT_LOGGED_IN, DiagnosticEvents.classifySumUpFailure("Not logged in"))
        assertEquals(SumUpFailureCause.NOT_LOGGED_IN, DiagnosticEvents.classifySumUpFailure("Invalid affiliate key"))
        assertEquals(SumUpFailureCause.READER_NOT_FOUND, DiagnosticEvents.classifySumUpFailure("Card reader not connected"))
        assertEquals(SumUpFailureCause.BLUETOOTH_OFF, DiagnosticEvents.classifySumUpFailure("Bluetooth is turned off"))
        assertEquals(SumUpFailureCause.NO_CONNECTIVITY, DiagnosticEvents.classifySumUpFailure("No internet connection"))
    }

    /** "not connected" and "no connection" both contain "connect", so the order
     *  of the branches decides which one wins. Getting it backwards sends an
     *  operator to the router for a reader fault. */
    @Test
    fun aDisconnectedReaderIsNotReportedAsANetworkFailure() {
        assertEquals(
            SumUpFailureCause.READER_NOT_FOUND,
            DiagnosticEvents.classifySumUpFailure("Card reader not connected")
        )
        assertEquals(
            SumUpFailureCause.NO_CONNECTIVITY,
            DiagnosticEvents.classifySumUpFailure("No connection to the server")
        )
    }

    /** The widened rules must not have widened the one thing that matters: a
     *  message shaped like a transaction identifier still has to fall through
     *  every branch, because the cause is all that leaves the device. */
    @Test
    fun aTransactionCodeStillClassifiesAsUnknownAndCarriesNoText() {
        val cause = DiagnosticEvents.classifySumUpFailure("TX-8837120K")
        assertEquals(SumUpFailureCause.UNKNOWN, cause)
        assertEquals("unknown", cause.wire)
        val detail = DiagnosticEvents.checkoutNoReaderDetail(code = 3, cause = cause)
        assertFalse(detail.contains("8837120K"))
    }

    /** SumUp's readers ARE Bluetooth devices, so the word appears in messages
     *  that are really pairing failures. A bare "bluetooth" rule placed above
     *  the reader rule swallowed them and pointed the operator — and
     *  BluetoothRecoveryManager — at the radio for a reader fault. */
    @Test
    fun aBluetoothReaderFaultIsAReaderFaultNotARadioFault() {
        assertEquals(
            SumUpFailureCause.READER_NOT_FOUND,
            DiagnosticEvents.classifySumUpFailure("Bluetooth reader not found")
        )
        assertEquals(
            SumUpFailureCause.READER_NOT_FOUND,
            DiagnosticEvents.classifySumUpFailure("No Bluetooth device connected")
        )
        assertEquals(
            SumUpFailureCause.BLUETOOTH_OFF,
            DiagnosticEvents.classifySumUpFailure("Bluetooth is off")
        )
    }

    /** "log in" as a bare substring matches inside ordinary words — "Dialog
     *  initialization", "catalog info" — and that branch runs before the reader,
     *  connectivity and declined ones, so a false positive wins outright. */
    @Test
    fun anIncidentalLogSubstringIsNotALoginFailure() {
        assertEquals(
            SumUpFailureCause.UNKNOWN,
            DiagnosticEvents.classifySumUpFailure("Dialog initialization failed")
        )
        assertEquals(
            SumUpFailureCause.NOT_LOGGED_IN,
            DiagnosticEvents.classifySumUpFailure("Not logged in")
        )
    }
}
