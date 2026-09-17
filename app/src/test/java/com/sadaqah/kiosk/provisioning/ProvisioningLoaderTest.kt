package com.sadaqah.kiosk.provisioning

import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.settingsio.SettingsExportFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningLoaderTest {

    private val fast = 1000

    private val golden = Settings(
        kioskName = "Golden Bench Unit",
        kioskCode = "nl-gld-arnhem-nour_al_houda-01",
        currency = "USD",
        language = "ar",
        analyticsEnabled = true,
        analyticsPrivacyPolicyUrl = "https://example.org/privacy",
        installId = "the-golden-kiosks-id",
        testMode = true
    )

    private val secrets = mapOf(
        SettingsExportFile.KEY_AFFILIATE to "sup_afk_GOLDEN",
        SettingsExportFile.KEY_TELEMETRY_URL to "https://abc.supabase.co",
        SettingsExportFile.KEY_TELEMETRY_KEY to "sb_publishable_GOLDEN"
    )

    private fun payload(password: String? = "hunter2") =
        SettingsExportFile.build(golden, secrets, password, fast)

    private fun decide(
        current: Settings = Settings(installId = "this-device"),
        json: String? = payload(),
        password: String? = "hunter2",
        code: String? = "nl-gld-arnhem-nour_al_houda-07",
        name: String? = "Arnhem — hal"
    ) = ProvisioningLoader.decide(current, json, password, code, name)

    private fun applied(outcome: ProvisioningOutcome): ProvisioningOutcome.Apply {
        assertTrue("expected Apply but was $outcome", outcome is ProvisioningOutcome.Apply)
        return outcome as ProvisioningOutcome.Apply
    }

    private fun failed(outcome: ProvisioningOutcome): String {
        assertTrue("expected Failed but was $outcome", outcome is ProvisioningOutcome.Failed)
        return (outcome as ProvisioningOutcome.Failed).reason
    }

    /** The operator asked for provisioning and there was nothing to apply.
     *  Reporting success here would ship an unconfigured kiosk. */
    @Test
    fun anAbsentPayloadIsAFailureNotANoOp() {
        assertEquals("no_payload", failed(decide(json = null)))
    }

    @Test
    fun aWrongPasswordFails() {
        assertEquals("wrong_password", failed(decide(password = "not-it")))
    }

    @Test
    fun aMissingPasswordFails() {
        assertEquals("password_required", failed(decide(password = null)))
    }

    @Test
    fun malformedJsonFails() {
        assertEquals("malformed", failed(decide(json = "this is not json")))
    }

    @Test
    fun aValidPayloadAppliesItsConfiguration() {
        val result = applied(decide())
        assertEquals("USD", result.settings.currency)
        assertEquals("ar", result.settings.language)
    }

    /** Configuration crosses; device identity does not. */
    @Test
    fun configurationTravelsAndDeviceIdentityDoesNot() {
        val result = applied(decide())
        assertEquals("USD", result.settings.currency)
        assertEquals("ar", result.settings.language)
        assertEquals("https://example.org/privacy", result.settings.analyticsPrivacyPolicyUrl)
        assertEquals("this-device", result.settings.installId)
        assertFalse("a bench device's test mode must never reach a kiosk", result.settings.testMode)
    }

    /** The whole model is one payload cloned across a fleet, so the two fields
     *  that name the physical unit have to be replaced, not inherited. */
    @Test
    fun theOverridesReplaceTheGoldenKiosksIdentity() {
        val result = applied(decide())
        assertEquals("nl-gld-arnhem-nour_al_houda-07", result.settings.kioskCode)
        assertEquals("Arnhem — hal", result.settings.kioskName)
    }

    /** A code supplied per unit at provisioning time is this kiosk's own, so it
     *  must not raise the shared-code warning. */
    @Test
    fun anOverriddenCodeIsNotMarkedAsImported() {
        assertFalse(applied(decide()).settings.kioskCodeFromImport)
    }

    @Test
    fun anOverriddenCodeIsTrimmed() {
        val result = applied(decide(code = "  nl-gld-arnhem-nour_al_houda-07  "))
        assertEquals("nl-gld-arnhem-nour_al_houda-07", result.settings.kioskCode)
    }

    /** Refusing here is what stops a fleet reporting and billing under one
     *  identity. The script also checks, but the app is the last line. */
    @Test
    fun aMissingCodeOverrideFailsWhenThePayloadCarriesOne() {
        assertEquals("kiosk_code_required", failed(decide(code = null)))
    }

    @Test
    fun aMissingNameOverrideFailsWhenThePayloadCarriesOne() {
        assertEquals("kiosk_name_required", failed(decide(name = null)))
    }

    /** A payload with nothing to inherit needs no override. */
    @Test
    fun overridesAreOptionalWhenThePayloadCarriesNeither() {
        val blank = SettingsExportFile.build(
            golden.copy(kioskCode = "", kioskName = ""), secrets, "hunter2", fast
        )
        val result = applied(decide(json = blank, code = null, name = null))
        assertEquals("", result.settings.kioskCode)
        assertEquals("", result.settings.kioskName)
    }

    /** An export taken without ticking "include keys" parses fine and carries
     *  nothing. It must still apply its settings — this is how a golden export
     *  taken without ticking "include keys" behaves, and the script refuses it
     *  downstream on absent credentials, not here. */
    @Test
    fun aPayloadWithNoSecretsBlockStillAppliesItsSettings() {
        val noSecrets = SettingsExportFile.build(golden, emptyMap(), null, fast)
        val result = applied(decide(json = noSecrets, password = "hunter2"))
        assertEquals("USD", result.settings.currency)
    }
}
