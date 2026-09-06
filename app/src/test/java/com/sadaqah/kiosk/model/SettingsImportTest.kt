package com.sadaqah.kiosk.model

import org.junit.Assert.assertEquals
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

    /** Everything that is genuinely configuration must still come across, or the
     *  guard has quietly become a block. */
    @Test
    fun ordinaryConfigurationIsTakenFromTheImport() {
        val imported = Settings(
            installId = "other",
            kioskCode = "SK-0042",
            language = "ar",
            analyticsEnabled = true,
            analyticsPrivacyPolicyUrl = "https://example.org/privacy"
        )
        val merged = SettingsImport.merge(Settings(installId = "mine"), imported)
        assertEquals("SK-0042", merged.kioskCode)
        assertEquals("ar", merged.language)
        assertEquals(true, merged.analyticsEnabled)
        assertEquals("https://example.org/privacy", merged.analyticsPrivacyPolicyUrl)
    }
}
