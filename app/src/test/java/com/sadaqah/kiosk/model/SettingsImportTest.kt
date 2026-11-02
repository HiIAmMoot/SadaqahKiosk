package com.sadaqah.kiosk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** The code travels by design, so a tablet swapped into an existing kiosk
     *  keeps that kiosk's printed code. What must not happen silently is a whole
     *  cloned fleet reporting under one code, so the merge records that the value
     *  arrived from a file rather than from this kiosk's operator. */
    @Test
    fun anImportedKioskCodeIsMarkedAsComingFromAFile() {
        val merged = SettingsImport.merge(
            Settings(kioskCode = ""),
            Settings(kioskCode = "nl-gld-arnhem-nour_al_houda-01")
        )
        assertEquals("nl-gld-arnhem-nour_al_houda-01", merged.kioskCode)
        assertTrue(merged.kioskCodeFromImport)
    }

    /** An import carrying no code leaves nothing to warn about. */
    @Test
    fun anImportWithNoKioskCodeIsNotMarked() {
        val merged = SettingsImport.merge(Settings(kioskCode = "mine"), Settings(kioskCode = ""))
        assertFalse(merged.kioskCodeFromImport)
    }

    /** The flag describes THIS import, so a stale one from the source device's
     *  own export must not ride across and warn about a code that did not come
     *  from a file at all. */
    @Test
    fun theFlagIsRecomputedRatherThanInherited() {
        val merged = SettingsImport.merge(
            Settings(),
            Settings(kioskCode = "", kioskCodeFromImport = true)
        )
        assertFalse(merged.kioskCodeFromImport)
    }

    /** kioskName travels the same way kioskCode does, and needs the same
     *  paper trail: a cloned fleet must not silently attribute every payment
     *  to the golden kiosk with nothing on screen saying so. */
    @Test
    fun anImportedKioskNameIsMarkedAsComingFromAFile() {
        val merged = SettingsImport.merge(
            Settings(kioskName = ""),
            Settings(kioskName = "Golden Bench Unit")
        )
        assertEquals("Golden Bench Unit", merged.kioskName)
        assertTrue(merged.kioskNameFromImport)
    }

    /** An import carrying no name leaves nothing to warn about. */
    @Test
    fun anImportWithNoKioskNameIsNotMarked() {
        val merged = SettingsImport.merge(Settings(kioskName = "mine"), Settings(kioskName = ""))
        assertFalse(merged.kioskNameFromImport)
    }

    /** Same reasoning as the kioskCode flag: this describes THIS import, so a
     *  stale flag from the source device's own export must not ride across. */
    @Test
    fun theKioskNameFlagIsRecomputedRatherThanInherited() {
        val merged = SettingsImport.merge(
            Settings(),
            Settings(kioskName = "", kioskNameFromImport = true)
        )
        assertFalse(merged.kioskNameFromImport)
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
