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
            occurredAtIso = "2026-09-05T10:00:00Z")
        assertEquals("diagnostic_events", e.table)
    }

    @Test
    fun diagnostic_serialisesKindAndSeverityAsLowercaseText() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CARD_READER_PAGE_TIMEOUT,
            occurredAtIso = "2026-09-05T10:00:00Z")
        val p = parse(e.payloadJson())
        assertEquals("card_reader_page_timeout", p.get("kind").asString)
        assertEquals("warn", p.get("severity").asString)
    }

    @Test
    fun diagnostic_severityComesFromTheKind() {
        assertEquals(DiagnosticSeverity.ERROR, DiagnosticKind.CRASH.severity)
        assertEquals(DiagnosticSeverity.WARN, DiagnosticKind.BLUETOOTH_WATCHDOG_FIRED.severity)
        assertEquals(DiagnosticSeverity.INFO, DiagnosticKind.UPDATE_INSTALLED.severity)
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
            occurredAtIso = "2026-09-05T10:00:00Z")
        val p = parse(e.payloadJson())
        assertFalse(p.has("detail"))
        assertFalse(p.has("stack_trace"))
    }

    @Test
    fun diagnostic_detailIsEmbeddedAsJsonNotAString() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.UPDATE_INSTALLED,
            occurredAtIso = "2026-09-05T10:00:00Z",
            detailJson = """{"from_version":"1.3.5","to_version":"1.3.6"}""")
        val p = parse(e.payloadJson())
        assertTrue(p.get("detail").isJsonObject)
        assertEquals("1.3.5", p.getAsJsonObject("detail").get("from_version").asString)
    }

    /** Malformed detail must not corrupt the payload or throw at enqueue time. */
    @Test
    fun diagnostic_malformedDetailIsDropped() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z", detailJson = "not json at all")
        val p = parse(e.payloadJson())
        assertFalse(p.has("detail"))
    }

    @Test
    fun diagnostic_redactsTheAffiliateKeyFromStackTrace() {
        val key = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z",
            stackTrace = "java.lang.IllegalStateException: key $key rejected",
            affiliateKey = key)
        val p = parse(e.payloadJson())
        assertFalse(p.get("stack_trace").asString.contains(key))
    }

    @Test
    fun diagnostic_truncatesAnOversizedStackTrace() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z", stackTrace = "x".repeat(50_000))
        val p = parse(e.payloadJson())
        assertTrue(p.get("stack_trace").asString.length < 50_000)
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

    // ── Timestamps ───────────────────────────────────────────────────────────

    @Test
    fun nowIso_isUtcAndParseable() {
        val now = TelemetryEvent.nowIso()
        assertTrue("expected a Z-suffixed UTC instant but was $now", now.endsWith("Z"))
        java.time.Instant.parse(now)
    }
}
