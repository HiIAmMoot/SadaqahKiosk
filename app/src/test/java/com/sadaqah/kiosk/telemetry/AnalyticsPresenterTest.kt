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
        val result = view(config = null)
        assertFalse(result.canTestConnection)
        assertEquals(TestUnavailable.NOT_CONFIGURED, result.testUnavailable)
    }

    @Test
    fun aConfiguredKioskCanBeTested() {
        val result = view()
        assertTrue(result.canTestConnection)
        assertEquals(null, result.testUnavailable)
    }

    // ── FIX 2: testing a disabled kiosk cannot report anything useful ──────
    //
    // TelemetryGate refuses a disabled kiosk before anything else runs, so
    // pressing the button here would return Blocked(DISABLED) and tell the
    // operator nothing about whether the destination works. The attempt is
    // not free either: TelemetryManager.activate() resets the recorded error
    // history and appends an activation row *before* the gate is consulted,
    // so a press on a disabled kiosk both erases the diagnostic the operator
    // opened the screen to read and leaves an unsendable row queued forever.
    // Gating the button avoids both costs.

    @Test
    fun aDisabledKioskCannotBeTestedAndReportsAnalyticsOff() {
        val result = view(settings = Settings(analyticsEnabled = false, installId = "i"))
        assertFalse(result.canTestConnection)
        assertEquals(TestUnavailable.ANALYTICS_OFF, result.testUnavailable)
    }

    /** Entering a destination is the step that comes first, so it takes
     *  precedence over the analytics-off reason when both apply. */
    @Test
    fun anUnconfiguredAndDisabledKioskReportsNotConfigured() {
        val result = view(settings = Settings(analyticsEnabled = false, installId = "i"), config = null)
        assertFalse(result.canTestConnection)
        assertEquals(TestUnavailable.NOT_CONFIGURED, result.testUnavailable)
    }

    /**
     * The key is shown so an operator can confirm which one is loaded without it
     * being readable over their shoulder, or in a photograph of the screen.
     */
    @Test
    fun theKeyIsMaskedToAShortSuffix() {
        val masked = view(config = TelemetryConfig("https://abc.supabase.co", "sb_publishable_ABCDEFGHIJKL")).maskedKey
        assertEquals("•".repeat(23) + "IJKL", masked)
    }

    @Test
    fun aShortKeyIsMaskedEntirelyRatherThanMostlyRevealed() {
        val masked = view(config = TelemetryConfig("https://abc.supabase.co", "abcd")).maskedKey
        assertEquals("••••", masked)
    }

    /** Boundary: the longest key that is still masked completely. */
    @Test
    fun anEightCharacterKeyIsMaskedEntirely() {
        val masked = view(config = TelemetryConfig("https://abc.supabase.co", "abcdefgh")).maskedKey
        assertEquals("•".repeat(8), masked)
    }

    /** Boundary: one character past full masking — five bullets, then the
     *  four-character tail. */
    @Test
    fun aNineCharacterKeyRevealsExactlyFourOfNine() {
        val masked = view(config = TelemetryConfig("https://abc.supabase.co", "abcdefghi")).maskedKey
        assertEquals("•".repeat(5) + "fghi", masked)
    }

    @Test
    fun anAbsentKeyShowsNothingRatherThanMaskCharacters() {
        assertEquals("", view(config = null).maskedKey)
    }

    @Test
    fun theUrlIsShownInFullBecauseItIsNotASecret() {
        assertEquals("https://abc.supabase.co", view().baseUrl)
    }

    // ── FIX 6: a mis-pasted service_role secret must be flagged, not blocked ─

    @Test
    fun aNormalPublishableKeyIsNotFlaggedAsUnusual() {
        val result = view(config = TelemetryConfig("https://abc.supabase.co", "sb_publishable_ABCDEFGHIJKL"))
        assertFalse(result.keyLooksUnusual)
    }

    /** The Supabase dashboard shows the publishable key right next to the
     *  service_role secret; this is the shape of that secret. */
    @Test
    fun aServiceRoleShapedKeyIsFlaggedAsUnusual() {
        val result = view(config = TelemetryConfig("https://abc.supabase.co", "sb_secret_ABCDEFGHIJKL"))
        assertTrue(result.keyLooksUnusual)
    }

    @Test
    fun noStoredKeyIsNotFlaggedAsUnusual() {
        assertFalse(view(config = null).keyLooksUnusual)
    }

    @Test
    fun aConventionalKioskCodeIsNotFlagged() {
        val settings = Settings(kioskCode = "nl-gld-arnhem-nour_al_houda-01", installId = "i")
        assertFalse(view(settings = settings).kioskCodeLooksUnusual)
    }

    @Test
    fun anUnconventionalKioskCodeIsFlagged() {
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

    // ── FIX 1: the stale-error rule rests on evidence, not queue depth ──────
    //
    // TelemetryManager.flush can empty the outbox by permanently rejecting rows
    // (deleting the donations) in the very same flush that records a retryable
    // failure — so an empty queue is not proof the error is history. The
    // presenter instead suppresses only once a success has happened *after*
    // the error was recorded.

    /** A flush can empty the outbox by rejecting rows (deleting donations)
     *  while also recording a retryable failure for the same flush. Suppressing
     *  on queue depth would hide the only report the operator would ever get
     *  that rows were discarded. */
    @Test
    fun anErrorSurvivesAnEmptiedQueueWhenNoSuccessFollowedIt() {
        val status = TelemetryStatus(
            queued = 0,
            lastError = "HTTP 503 down",
            lastErrorAtMs = now,
            lastSuccessMs = now - 60_000
        )
        assertEquals("HTTP 503 down", view(status = status).error)
    }

    /** A success genuinely supersedes an error, whatever the queue is doing —
     *  queued is deliberately non-zero here to prove the suppression is not
     *  keyed off queue depth. */
    @Test
    fun anErrorIsSuppressedOnceALaterSuccessSupersedesIt() {
        val status = TelemetryStatus(
            queued = 5,
            lastError = "HTTP 503 down",
            lastErrorAtMs = now - 60_000,
            lastSuccessMs = now
        )
        assertEquals(null, view(status = status).error)
    }

    /** Mirrors what TelemetryManager.activate() leaves behind on a retryable
     *  failure: the activation row stays queued, nothing has ever uploaded,
     *  and the error was just recorded. */
    @Test
    fun aFailedTestConnectionStillShowsItsError() {
        val status = TelemetryStatus(
            queued = 1,
            lastError = "HTTP 401 bad key",
            lastErrorAtMs = now,
            lastSuccessMs = 0L
        )
        assertEquals("HTTP 401 bad key", view(status = status).error)
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

    // ── FIX 4: four view fields that no test asserted ───────────────────────

    @Test
    fun theEnabledFlagIsCarriedThrough() {
        assertTrue(view(settings = Settings(analyticsEnabled = true, installId = "i")).enabled)
        assertFalse(view(settings = Settings(analyticsEnabled = false, installId = "i")).enabled)
    }

    @Test
    fun theConfiguredFlagReflectsWhetherADestinationExists() {
        assertTrue(view(config = TelemetryConfig("https://abc.supabase.co", "k")).configured)
        assertFalse(view(config = null).configured)
    }

    @Test
    fun theKioskCodeIsCarriedThrough() {
        val code = "nl-gld-arnhem-nour_al_houda-01"
        assertEquals(code, view(settings = Settings(kioskCode = code, installId = "i")).kioskCode)
    }

    @Test
    fun theLastSuccessMsIsCarriedThrough() {
        assertEquals(now - 3_600_000, view(status = TelemetryStatus(lastSuccessMs = now - 3_600_000)).lastSuccessMs)
    }

    // ── FIX 5: gaps that would otherwise force computation into the composable ─

    @Test
    fun lastSuccessAgeMsIsNullWhenNeverUploaded() {
        assertEquals(null, view(status = TelemetryStatus(lastSuccessMs = 0L)).lastSuccessAgeMs)
    }

    @Test
    fun lastSuccessAgeMsIsTheElapsedTimeSinceTheLastSuccess() {
        assertEquals(90_000L, view(status = TelemetryStatus(lastSuccessMs = now - 90_000)).lastSuccessAgeMs)
    }

    @Test
    fun backoffRemainingMsIsZeroWhenNotBackingOff() {
        assertEquals(0L, view(status = TelemetryStatus(backoffUntilMs = now - 30_000)).backoffRemainingMs)
    }

    @Test
    fun backoffRemainingMsIsTheTimeUntilTheDeadline() {
        assertEquals(45_000L, view(status = TelemetryStatus(backoffUntilMs = now + 45_000)).backoffRemainingMs)
    }

    @Test
    fun consecutiveFailuresIsCarriedThrough() {
        assertEquals(7, view(status = TelemetryStatus(consecutiveFailures = 7)).consecutiveFailures)
    }

    @Test
    fun policyUrlsAreCarriedThroughFromSettings() {
        val settings = Settings(
            installId = "i",
            analyticsPrivacyPolicyUrl = "https://example.org/privacy",
            analyticsTermsUrl = "https://example.org/terms"
        )
        val result = view(settings = settings)
        assertEquals("https://example.org/privacy", result.privacyPolicyUrl)
        assertEquals("https://example.org/terms", result.termsUrl)
        assertFalse(result.policyUrlsMissing)
    }

    /** TelemetryEvent.Activation records both URLs, so activating with either
     *  blank has a real consequence: an activation row with empty disclosure
     *  links. */
    @Test
    fun policyUrlsMissingIsTrueWhenEitherUrlIsBlank() {
        assertTrue(
            view(
                settings = Settings(
                    installId = "i",
                    analyticsPrivacyPolicyUrl = "",
                    analyticsTermsUrl = "https://example.org/terms"
                )
            ).policyUrlsMissing
        )
        assertTrue(
            view(
                settings = Settings(
                    installId = "i",
                    analyticsPrivacyPolicyUrl = "https://example.org/privacy",
                    analyticsTermsUrl = ""
                )
            ).policyUrlsMissing
        )
    }

    /** With no kiosk code configured, installId is what identifies the device
     *  to a support contact reading it off this screen. */
    @Test
    fun installIdIsCarriedThrough() {
        assertEquals("install-77", view(settings = Settings(installId = "install-77")).installId)
    }
}
