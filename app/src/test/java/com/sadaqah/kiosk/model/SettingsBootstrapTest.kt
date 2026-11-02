package com.sadaqah.kiosk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBootstrapTest {

    private val now = 1_700_000_000_000L

    @Test
    fun aFreshInstallGetsBothAndReportsChanged() {
        val result = SettingsBootstrap.apply(Settings(installId = "", donationStatsStartedAtMs = 0L), now) { "new-id" }
        assertEquals("new-id", result.settings.installId)
        assertEquals(now, result.settings.donationStatsStartedAtMs)
        assertTrue(result.changed)
    }

    @Test
    fun anAlreadyBootstrappedDeviceIsReturnedUntouched() {
        val current = Settings(installId = "existing-id", donationStatsStartedAtMs = 42L)
        val result = SettingsBootstrap.apply(current, now) { "should-not-be-called" }
        assertEquals("existing-id", result.settings.installId)
        assertEquals(42L, result.settings.donationStatsStartedAtMs)
        assertFalse(result.changed)
    }

    @Test
    fun aWhitespaceOnlyInstallIdIsTreatedAsAbsent() {
        val result = SettingsBootstrap.apply(
            Settings(installId = "   ", donationStatsStartedAtMs = 1L), now
        ) { "minted" }
        assertEquals("minted", result.settings.installId)
        assertTrue(result.changed)
    }

    @Test
    fun theIdIsNotRegeneratedAcrossTwoConsecutiveApplyCalls() {
        val first = SettingsBootstrap.apply(Settings(installId = ""), now) { "first-id" }
        val second = SettingsBootstrap.apply(first.settings, now + 1000) { "second-id" }
        assertEquals("first-id", second.settings.installId)
        assertFalse(second.changed)
    }

    @Test
    fun aDeviceWithAnInstallIdButAZeroAnchorGetsOnlyTheAnchor() {
        val current = Settings(installId = "existing-id", donationStatsStartedAtMs = 0L)
        val result = SettingsBootstrap.apply(current, now) { "should-not-be-called" }
        assertEquals("existing-id", result.settings.installId)
        assertEquals(now, result.settings.donationStatsStartedAtMs)
        assertTrue(result.changed)
    }
}
