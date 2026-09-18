package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DonationEventsTest {

    private val appVersion = "1.3.6-preview"

    /** 2023-11-14T22:13:20Z exactly, so the ISO rendering has no fraction. */
    private val atMs = 1_700_000_000_000L

    private val enabled = Settings(
        analyticsEnabled = true,
        kioskCode = "nl-gld-arnhem-nour_al_houda-01",
        installId = "11111111-2222-3333-4444-555555555555",
        currency = "EUR"
    )

    private fun result(amount: String, settings: Settings = enabled) =
        DonationEvents.eventFor(settings, appVersion, BigDecimal(amount), atMs)

    private fun cents(amount: String): Int {
        val r = result(amount)
        assertTrue("expected a Report for $amount, got $r", r is DonationEventResult.Report)
        return (r as DonationEventResult.Report).event.amountCents
    }

    // ── The gate ─────────────────────────────────────────────────────────────

    /**
     * The whole reason this function exists. MainActivity cannot be unit-tested,
     * so an `if (settings.analyticsEnabled)` at the call site is a decision
     * nothing checks — which is how phase 2b shipped a mint inside an
     * unreachable branch.
     *
     * Asserting the exact result, not merely "not a Report": NotEnabled and
     * AmountUnrepresentable must stay distinguishable, because the caller
     * records a loss for one and does nothing for the other. A shared nullable
     * return would make every donation on a disabled kiosk a recorded loss.
     * Do not add a separate `assertNotEquals(AmountUnrepresentable, …)` test —
     * this assertion already implies it, and `DonationEventResult` having three
     * distinct members means collapsing them does not even compile.
     */
    @Test
    fun analyticsOffProducesNotEnabled() {
        assertEquals(
            DonationEventResult.NotEnabled,
            result("10.00", Settings())
        )
    }

    @Test
    fun anEnabledKioskProducesAReport() {
        assertTrue(result("10.00") is DonationEventResult.Report)
    }

    // ── The identity and the payload ─────────────────────────────────────────

    @Test
    fun theReportCarriesTheKioskIdentityAndCurrency() {
        val event = (result("10.00") as DonationEventResult.Report).event
        assertEquals("nl-gld-arnhem-nour_al_houda-01", event.identity.code)
        assertEquals("11111111-2222-3333-4444-555555555555", event.identity.installId)
        assertEquals("1.3.6-preview", event.identity.appVersion)
        assertEquals("EUR", event.currency)
        assertEquals(TelemetryTables.DONATIONS, event.table)
    }

    @Test
    fun theOccurredAtIsTheIsoInstantOfThePassedClock() {
        val event = (result("10.00") as DonationEventResult.Report).event
        assertEquals("2023-11-14T22:13:20Z", event.occurredAtIso)
    }

    @Test
    fun thePayloadRendersCentsAndCurrencyForTheWire() {
        val event = (result("12.34") as DonationEventResult.Report).event
        val row = JsonParser.parseString(event.payloadJson()).asJsonObject
        assertEquals(1234, row.get("amount_cents").asInt)
        assertEquals("EUR", row.get("currency").asString)
        assertEquals("2023-11-14T22:13:20Z", row.get("occurred_at").asString)
    }

    @Test
    fun eventIdentityFromReadsTheKioskCodeInstallIdAndPassedAppVersion() {
        val identity = EventIdentity.from(enabled, "9.9.9")
        assertEquals("nl-gld-arnhem-nour_al_houda-01", identity.code)
        assertEquals("11111111-2222-3333-4444-555555555555", identity.installId)
        assertEquals("9.9.9", identity.appVersion)
    }

    // ── Cents ────────────────────────────────────────────────────────────────

    @Test
    fun aWholeNumberAmountBecomesCents() {
        assertEquals(1000, cents("10"))
    }

    @Test
    fun aTwoDecimalAmountBecomesExactCents() {
        assertEquals(1234, cents("12.34"))
    }

    @Test
    fun aOneDecimalAmountBecomesExactCents() {
        assertEquals(1250, cents("12.5"))
    }

    /**
     * Scale 3 can only come from a malformed stored amount, and is rounded
     * rather than rejected: a donation that really happened should be reported
     * approximately rather than not at all. HALF_UP, pinned in both directions
     * so a switch to truncation fails here rather than passing silently.
     */
    @Test
    fun aScaleThreeAmountAtTheBoundaryRoundsUp() {
        assertEquals(1001, cents("10.005"))
    }

    @Test
    fun aScaleThreeAmountBelowTheBoundaryRoundsDown() {
        assertEquals(1000, cents("10.004"))
    }

    @Test
    fun zeroIsReportedAsZeroCents() {
        assertEquals(0, cents("0"))
    }

    /**
     * A negative successful payment is nonsense, but DonationHistory already
     * stores whatever it is handed, and a negative on the dashboard is the
     * anomaly worth seeing rather than hiding.
     */
    @Test
    fun aNegativeAmountIsReportedRatherThanDropped() {
        assertEquals(-1000, cents("-10.00"))
    }

    @Test
    fun anAmountBeyondIntCentsIsUnrepresentable() {
        assertEquals(
            DonationEventResult.AmountUnrepresentable,
            result("100000000000")
        )
    }

    @Test
    fun theLargestRepresentableAmountIsStillReported() {
        // Int.MAX_VALUE cents = 21474836.47
        assertEquals(Int.MAX_VALUE, cents("21474836.47"))
    }
}
