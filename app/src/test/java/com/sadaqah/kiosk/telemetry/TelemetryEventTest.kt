package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TelemetryEventTest {

    private val identity = EventIdentity(
        code = "nl-gld-arnhem-nour_al_houda-01",
        installId = "11111111-2222-3333-4444-555555555555",
        appVersion = "1.3.6-preview"
    )

    /** Under 32 chars, so TOKEN_SHAPED cannot reach it and only the
     *  affiliateKey parameter can cause redaction. */
    private val shortKey = "sup_af_9f3a2b"

    private fun parse(json: String) = JsonParser.parseString(json).asJsonObject

    // ── Donation ─────────────────────────────────────────────────────────────

    @Test
    fun donation_targetsTheDonationTable() {
        val e = TelemetryEvent.Donation(identity, amountCents = 2500, currency = "EUR",
            occurredAtIso = "2026-09-05T10:00:00Z")
        assertEquals("donation_events", e.table)
    }

    @Test
    fun donation_payloadCarriesEveryField() {
        val e = TelemetryEvent.Donation(identity, amountCents = 2500, currency = "EUR",
            occurredAtIso = "2026-09-05T10:00:00Z")
        val p = parse(e.payloadJson())
        assertEquals(e.id, p.get("id").asString)
        assertEquals("nl-gld-arnhem-nour_al_houda-01", p.get("code").asString)
        assertEquals("11111111-2222-3333-4444-555555555555", p.get("install_id").asString)
        assertEquals(2500, p.get("amount_cents").asInt)
        assertEquals("EUR", p.get("currency").asString)
        assertEquals("2026-09-05T10:00:00Z", p.get("occurred_at").asString)
        assertEquals("1.3.6-preview", p.get("app_version").asString)
    }

    @Test
    fun donation_neverCarriesDonorOrCardFields() {
        val e = TelemetryEvent.Donation(identity, 2500, "EUR", "2026-09-05T10:00:00Z")
        val p = parse(e.payloadJson())
        for (forbidden in listOf("tx_code", "card", "donor", "pan", "name")) {
            assertFalse("payload must not contain $forbidden", p.has(forbidden))
        }
    }

    @Test
    fun donation_payloadHasExactlyTheExpectedFields() {
        val p = parse(TelemetryEvent.Donation(identity, 2500, "EUR", "2026-09-05T10:00:00Z").payloadJson())
        assertEquals(
            setOf("id", "code", "install_id", "app_version", "amount_cents", "currency", "occurred_at"),
            p.keySet())
    }

    /** A fork with no kiosk-code scheme is a supported deployment. */
    @Test
    fun donation_acceptsAnEmptyCode() {
        val anon = identity.copy(code = "")
        val p = parse(TelemetryEvent.Donation(anon, 100, "EUR", "2026-09-05T10:00:00Z").payloadJson())
        assertEquals("", p.get("code").asString)
    }

    @Test
    fun donation_idsAreUniquePerEvent() {
        val a = TelemetryEvent.Donation(identity, 100, "EUR", "2026-09-05T10:00:00Z")
        val b = TelemetryEvent.Donation(identity, 100, "EUR", "2026-09-05T10:00:00Z")
        assertNotEquals(a.id, b.id)
    }

    // ── Diagnostic ───────────────────────────────────────────────────────────

    @Test
    fun diagnostic_targetsTheDiagnosticTable() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.NETWORK_OUTAGE,
            occurredAtIso = "2026-09-05T10:00:00Z", affiliateKey = null)
        assertEquals("diagnostic_events", e.table)
    }

    @Test
    fun diagnostic_serialisesKindAndSeverityAsLowercaseText() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CARD_READER_PAGE_TIMEOUT,
            occurredAtIso = "2026-09-05T10:00:00Z", affiliateKey = null)
        val p = parse(e.payloadJson())
        assertEquals("card_reader_page_timeout", p.get("kind").asString)
        assertEquals("warn", p.get("severity").asString)
    }

    @Test
    fun diagnostic_everyKindHasTheExpectedWireNameAndSeverity() {
        val expected = mapOf(
            DiagnosticKind.CRASH to ("crash" to DiagnosticSeverity.ERROR),
            DiagnosticKind.RESTART_TRIGGERED to ("restart_triggered" to DiagnosticSeverity.ERROR),
            DiagnosticKind.SUMUP_REINIT_FAILED to ("sumup_reinit_failed" to DiagnosticSeverity.ERROR),
            DiagnosticKind.CARD_READER_CONNECT_FAILED to ("card_reader_connect_failed" to DiagnosticSeverity.WARN),
            DiagnosticKind.CARD_READER_PAGE_TIMEOUT to ("card_reader_page_timeout" to DiagnosticSeverity.WARN),
            DiagnosticKind.CHECKOUT_NO_READER to ("checkout_no_reader" to DiagnosticSeverity.WARN),
            DiagnosticKind.BLUETOOTH_WATCHDOG_FIRED to ("bluetooth_watchdog_fired" to DiagnosticSeverity.WARN),
            DiagnosticKind.NETWORK_OUTAGE to ("network_outage" to DiagnosticSeverity.WARN),
            DiagnosticKind.UPDATE_INSTALLED to ("update_installed" to DiagnosticSeverity.INFO),
            DiagnosticKind.UPDATE_INSTALL_FAILED to ("update_install_failed" to DiagnosticSeverity.ERROR),
            DiagnosticKind.UPDATE_ROLLBACK to ("update_rollback" to DiagnosticSeverity.ERROR)
        )
        assertEquals(DiagnosticKind.entries.size, expected.size)
        for ((kind, spec) in expected) {
            assertEquals(spec.first, kind.wire)
            assertEquals(spec.second, kind.severity)
        }
    }

    @Test
    fun diagnostic_hasElevenKinds() {
        assertEquals(11, DiagnosticKind.values().size)
    }

    @Test
    fun diagnostic_wireNamesAreUnique() {
        val wires = DiagnosticKind.values().map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
    }

    @Test
    fun diagnostic_omitsAbsentDetailAndStackTrace() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.NETWORK_OUTAGE,
            occurredAtIso = "2026-09-05T10:00:00Z", affiliateKey = null)
        val p = parse(e.payloadJson())
        assertFalse(p.has("detail"))
        assertFalse(p.has("stack_trace"))
    }

    @Test
    fun diagnostic_detailIsEmbeddedAsJsonNotAString() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.UPDATE_INSTALLED,
            occurredAtIso = "2026-09-05T10:00:00Z",
            detailJson = """{"from_version":"1.3.5","to_version":"1.3.6"}""",
            affiliateKey = null)
        val p = parse(e.payloadJson())
        assertTrue(p.get("detail").isJsonObject)
        assertEquals("1.3.5", p.getAsJsonObject("detail").get("from_version").asString)
    }

    /** Malformed detail must not corrupt the payload or throw at enqueue time. */
    @Test
    fun diagnostic_malformedDetailIsDropped() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z", detailJson = "not json at all",
            affiliateKey = null)
        val p = parse(e.payloadJson())
        assertFalse(p.has("detail"))
    }

    @Test
    fun diagnostic_redactsTheAffiliateKeyFromStackTrace() {
        val p = parse(TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z",
            stackTrace = "java.lang.IllegalStateException: key $shortKey rejected",
            affiliateKey = shortKey).payloadJson())
        assertFalse(p.get("stack_trace").asString.contains(shortKey))
    }

    /** Proves the test above exercises the parameter rather than the heuristic:
     *  with no key supplied, a short secret survives. */
    @Test
    fun diagnostic_withoutAKeyAShortSecretSurvives() {
        val p = parse(TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z",
            stackTrace = "java.lang.IllegalStateException: key $shortKey rejected",
            affiliateKey = null).payloadJson())
        assertTrue(p.get("stack_trace").asString.contains(shortKey))
    }

    @Test
    fun diagnostic_truncatesAnOversizedStackTrace() {
        // Filler the token pattern cannot match — `.`, `(`, `:` and whitespace all
        // break a run — so truncation actually fires instead of scrub eating it.
        val trace = "\tat com.sadaqah.kiosk.Foo.bar(Foo.kt:1)\n".repeat(500)
        val p = parse(TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z", stackTrace = trace,
            affiliateKey = null).payloadJson())
        val out = p.get("stack_trace").asString
        assertTrue(out.endsWith("truncated"))
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= TelemetryRedactor.MAX_TEXT_BYTES + 32)
    }

    @Test
    fun diagnostic_redactsTheAffiliateKeyFromDetail() {
        val p = parse(TelemetryEvent.Diagnostic(identity, DiagnosticKind.SUMUP_REINIT_FAILED,
            occurredAtIso = "2026-09-05T10:00:00Z",
            detailJson = """{"message":"login rejected for $shortKey"}""",
            affiliateKey = shortKey).payloadJson())
        assertFalse(p.getAsJsonObject("detail").get("message").asString.contains(shortKey))
    }

    @Test
    fun diagnostic_dropsOversizedDetail() {
        val huge = """{"blob":"${"." .repeat(20_000)}"}"""
        val p = parse(TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z", detailJson = huge,
            affiliateKey = null).payloadJson())
        assertFalse(p.has("detail"))
    }

    @Test
    fun diagnostic_scrubsBeforeTruncatingSoNoKeyStraddlesTheCut() {
        val filler = "\tat com.sadaqah.kiosk.Foo.bar(Foo.kt:1)\n".repeat(220)
        val trace = filler.substring(0, TelemetryRedactor.MAX_TEXT_BYTES - 6) + shortKey + filler
        val p = parse(TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z",
            stackTrace = trace, affiliateKey = shortKey).payloadJson())
        assertFalse(p.get("stack_trace").asString.contains("sup_af"))
    }

    @Test
    fun diagnostic_payloadHasExactlyTheExpectedFields() {
        val p = parse(TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z",
            detailJson = """{"a":1}""", stackTrace = "boom",
            affiliateKey = null).payloadJson())
        assertEquals(
            setOf("id", "code", "install_id", "app_version", "occurred_at",
                  "kind", "severity", "detail", "stack_trace"),
            p.keySet())
    }

    @Test
    fun diagnostic_toStringDoesNotLeakTheKeyOrTheRawTrace() {
        val event = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z",
            stackTrace = "boom for key $shortKey",
            affiliateKey = shortKey)
        val printed = event.toString()
        assertFalse(printed.contains(shortKey))
        assertFalse(printed.contains("boom"))
    }

    // ── Activation ───────────────────────────────────────────────────────────

    @Test
    fun activation_targetsTheActivationTable() {
        val e = TelemetryEvent.Activation(identity, activatedAtIso = "2026-09-05T10:00:00Z",
            privacyPolicyUrl = "https://example.invalid/privacy",
            termsUrl = "https://example.invalid/terms")
        assertEquals("telemetry_activations", e.table)
    }

    @Test
    fun activation_payloadUsesActivatedAtNotAgreedAt() {
        val e = TelemetryEvent.Activation(identity, activatedAtIso = "2026-09-05T10:00:00Z",
            privacyPolicyUrl = "https://example.invalid/privacy",
            termsUrl = "https://example.invalid/terms")
        val p = parse(e.payloadJson())
        assertEquals("2026-09-05T10:00:00Z", p.get("activated_at").asString)
        assertFalse("this is an activation record, not a consent record", p.has("agreed_at"))
        assertEquals("https://example.invalid/privacy", p.get("privacy_policy_url").asString)
    }

    @Test
    fun activation_payloadHasExactlyTheExpectedFields() {
        val p = parse(TelemetryEvent.Activation(identity, "2026-09-05T10:00:00Z",
            "https://example.invalid/privacy", "https://example.invalid/terms").payloadJson())
        assertEquals(
            setOf("id", "code", "install_id", "app_version", "activated_at",
                  "privacy_policy_url", "terms_url"),
            p.keySet())
    }

    // ── Timestamps ───────────────────────────────────────────────────────────

    @Test
    fun nowIso_isUtcAndParseable() {
        val now = TelemetryEvent.nowIso()
        assertTrue("expected a Z-suffixed UTC instant but was $now", now.endsWith("Z"))
        java.time.Instant.parse(now)
    }
}
