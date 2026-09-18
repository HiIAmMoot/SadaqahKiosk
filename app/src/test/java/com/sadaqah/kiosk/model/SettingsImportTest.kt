package com.sadaqah.kiosk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsImportTest {

    /** The whole point: provisioning a fleet from one export must not give every
     *  kiosk the same identity. */
    @Test
    fun theDevicesOwnInstallIdSurvivesAnImport() {
        val current = Settings(installId = "this-device")
        val imported = Settings(installId = "the-machine-the-export-came-from")
        assertEquals("this-device", SettingsImport.merge(current, imported).installId)
    }

    @Test
    fun aBlankLocalInstallIdIsNotFilledFromTheFile() {
        val merged = SettingsImport.merge(Settings(installId = ""), Settings(installId = "from-file"))
        assertEquals("", merged.installId)
    }

    @Test
    fun theLogoIsNotCarriedAcrossDevices() {
        assertNull(SettingsImport.merge(Settings(), Settings(logoUri = "file:///data/logo.png")).logoUri)
    }

    /** The importing kiosk must keep its own measurement anchor, or "Measuring
     *  since" and the throughput averages silently adopt the source device's
     *  history. */
    @Test
    fun theDonationStatsAnchorIsNotCarriedAcrossDevices() {
        val current = Settings(donationStatsStartedAtMs = 111L)
        val imported = Settings(donationStatsStartedAtMs = 999L)
        assertEquals(111L, SettingsImport.merge(current, imported).donationStatsStartedAtMs)
    }

    /** Even a zero (uninitialised) current anchor must not be overwritten by the
     *  import — that zero is exactly what lets SettingsBootstrap give this
     *  device its own anchor afterwards. */
    @Test
    fun aZeroDonationStatsAnchorIsPreservedRatherThanFilledFromTheImport() {
        val current = Settings(donationStatsStartedAtMs = 0L)
        val imported = Settings(donationStatsStartedAtMs = 999L)
        assertEquals(0L, SettingsImport.merge(current, imported).donationStatsStartedAtMs)
    }

    /** The disclosure-shown timestamp is a per-device consent record. Inheriting
     *  it would make a kiosk that never showed the disclosure look like it had. */
    @Test
    fun theAnalyticsActivationTimestampIsNotCarriedAcrossDevices() {
        val current = Settings(analyticsActivatedAtMs = 0L)
        val imported = Settings(analyticsActivatedAtMs = 555L)
        assertEquals(0L, SettingsImport.merge(current, imported).analyticsActivatedAtMs)
    }

    /** A one-shot signature-check bypass must never ride an export onto a whole
     *  fleet, so it is forced off on import regardless of either side's value. */
    @Test
    fun theSignatureCheckBypassIsAlwaysClearedOnImport() {
        val current = Settings(skipApkSignatureCheckOnce = true)
        val imported = Settings(skipApkSignatureCheckOnce = true)
        assertEquals(false, SettingsImport.merge(current, imported).skipApkSignatureCheckOnce)
    }

    /** Everything that is genuinely configuration must still come across, or the
     *  guard has quietly become a block. */
    @Test
    fun ordinaryConfigurationIsTakenFromTheImport() {
        val imported = Settings(
            installId = "other",
            kioskCode = "SK-0042",
            language = "ar",
            currency = "USD",
            analyticsEnabled = true,
            analyticsPrivacyPolicyUrl = "https://example.org/privacy",
            autoUpdateEnabled = false,
            kioskName = "Front Door"
        )
        val merged = SettingsImport.merge(Settings(installId = "mine"), imported)
        assertEquals("SK-0042", merged.kioskCode)
        assertEquals("ar", merged.language)
        assertEquals("USD", merged.currency)
        assertEquals(true, merged.analyticsEnabled)
        assertEquals("https://example.org/privacy", merged.analyticsPrivacyPolicyUrl)
        assertEquals(false, merged.autoUpdateEnabled)
        assertEquals("Front Door", merged.kioskName)
    }

    /**
     * Test mode bypasses the biometric gate and forces the logged-in and
     * reader-connected states. A bench device's export must not unlock the
     * settings screen on every kiosk that imports it.
     */
    @Test
    fun testModeIsNeverInherited() {
        val merged = SettingsImport.merge(Settings(testMode = false), Settings(testMode = true))
        assertFalse("a security relaxation is chosen on the device, never imported", merged.testMode)
    }

    /** And it is forced off, not merely preserved — a bench device importing a
     *  production export should also come back to a locked state. */
    @Test
    fun testModeIsForcedOffEvenWhenTheDeviceHadItOn() {
        val merged = SettingsImport.merge(Settings(testMode = true), Settings(testMode = false))
        assertFalse(merged.testMode)
    }
}
