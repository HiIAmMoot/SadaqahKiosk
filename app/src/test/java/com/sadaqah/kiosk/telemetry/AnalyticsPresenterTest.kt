package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsPresenterTest {

    private val now = 1_800_000_000_000L

    private fun view(
        settings: Settings = Settings(analyticsEnabled = true, installId = "install-1"),
        config: TelemetryConfig? = TelemetryConfig("https://abc.supabase.co", "publishable-key"),
        status: TelemetryStatus = TelemetryStatus()
    ) = AnalyticsPresenter.view(settings, config, status, now)

    @Test
    fun anUnconfiguredKioskCannotBeTested() {
        assertFalse(view(config = null).canTestConnection)
    }

    @Test
    fun aConfiguredKioskCanBeTested() {
        assertTrue(view().canTestConnection)
    }

    /** Disabling telemetry must not also disable the way to fix a bad destination —
     *  but there is nothing to test if no destination exists. */
    @Test
    fun aDisabledButConfiguredKioskCanStillBeTested() {
        assertTrue(view(settings = Settings(analyticsEnabled = false, installId = "i")).canTestConnection)
    }

    /**
     * The key is shown so an operator can confirm which one is loaded without it
     * being readable over their shoulder, or in a photograph of the screen.
     */
    @Test
    fun theKeyIsMaskedToAShortSuffix() {
        val masked = view(config = TelemetryConfig("https://abc.supabase.co", "sb_publishable_ABCDEFGHIJKL")).maskedKey
        assertTrue("the tail identifies which key it is", masked.endsWith("IJKL"))
        assertFalse("the body must not be readable", masked.contains("ABCDEFGH"))
    }

    @Test
    fun aShortKeyIsMaskedEntirelyRatherThanMostlyRevealed() {
        val masked = view(config = TelemetryConfig("https://abc.supabase.co", "abcd")).maskedKey
        assertFalse(masked.contains("abcd"))
    }

    @Test
    fun anAbsentKeyShowsNothingRatherThanMaskCharacters() {
        assertEquals("", view(config = null).maskedKey)
    }

    @Test
    fun theUrlIsShownInFullBecauseItIsNotASecret() {
        assertEquals("https://abc.supabase.co", view().baseUrl)
    }

    @Test
    fun aConventionalKioskCodeIsNotFlagged() {
        val settings = Settings(kioskCode = "nl-gld-arnhem-nour_al_houda-01", installId = "i")
        assertFalse(view(settings = settings).kioskCodeLooksUnusual)
    }

    @Test
    fun anUnconventionalKioskCodeIsFlaggedButNotBlocking() {
        val settings = Settings(kioskCode = "front door", installId = "i")
        assertTrue(view(settings = settings).kioskCodeLooksUnusual)
    }

    /** A deployment with no code scheme is supported, so blank must never warn. */
    @Test
    fun aBlankKioskCodeIsNotFlagged() {
        assertFalse(view(settings = Settings(kioskCode = "", installId = "i")).kioskCodeLooksUnusual)
    }

    @Test
    fun neverUploadedIsDistinctFromUploadedLongAgo() {
        assertTrue(view(status = TelemetryStatus(lastSuccessMs = 0L)).neverUploaded)
        assertFalse(view(status = TelemetryStatus(lastSuccessMs = now - 60_000)).neverUploaded)
    }

    @Test
    fun theQueueDepthIsCarriedThrough() {
        assertEquals(42, view(status = TelemetryStatus(queued = 42)).queued)
    }

    /**
     * A queue that has drained while an old error is still stored reads as
     * "everything failed and the data is gone" unless the error is cleared from
     * the view once it no longer describes anything pending.
     */
    @Test
    fun aStaleErrorIsNotShownBesideAnEmptyQueueAfterASuccess() {
        val status = TelemetryStatus(queued = 0, lastError = "HTTP 503 down", lastSuccessMs = now - 1000)
        assertEquals(null, view(status = status).error)
    }

    @Test
    fun anErrorIsShownWhileRowsAreStillWaiting() {
        val status = TelemetryStatus(queued = 5, lastError = "HTTP 503 down", lastSuccessMs = now - 1000)
        assertEquals("HTTP 503 down", view(status = status).error)
    }

    @Test
    fun backoffIsReportedOnlyWhileItIsInTheFuture() {
        assertTrue(view(status = TelemetryStatus(backoffUntilMs = now + 30_000)).backingOff)
        assertFalse(view(status = TelemetryStatus(backoffUntilMs = now - 30_000)).backingOff)
    }

    @Test
    fun activationIsReadFromTheSettingsTimestamp() {
        assertFalse(view(settings = Settings(installId = "i", analyticsActivatedAtMs = 0L)).activated)
        assertTrue(view(settings = Settings(installId = "i", analyticsActivatedAtMs = now)).activated)
    }
}
