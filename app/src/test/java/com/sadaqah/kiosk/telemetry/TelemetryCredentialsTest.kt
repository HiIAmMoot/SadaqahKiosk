package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCredentialsTest {

    private fun credentials() = TelemetryCredentials(InMemorySecretStore())

    @Test
    fun anHttpsUrlIsAccepted() {
        val verdict = TelemetryUrl.check("https://abc.supabase.co")
        assertTrue(verdict is UrlVerdict.Valid)
        assertEquals("https://abc.supabase.co", (verdict as UrlVerdict.Valid).normalised)
    }

    /**
     * The anon key travels as a request header. Over cleartext it is readable by
     * anything between the kiosk and the server, and a mosque's wifi is not a
     * controlled network. Rejecting at entry means the operator finds out at the
     * bench rather than never.
     */
    @Test
    fun anHttpUrlIsRejected() {
        val verdict = TelemetryUrl.check("http://abc.supabase.co")
        assertTrue(verdict is UrlVerdict.Invalid)
        assertTrue((verdict as UrlVerdict.Invalid).reason.contains("https", ignoreCase = true))
    }

    @Test
    fun aTrailingSlashIsNormalisedAway() {
        val verdict = TelemetryUrl.check("https://abc.supabase.co/")
        assertEquals("https://abc.supabase.co", (verdict as UrlVerdict.Valid).normalised)
    }

    @Test
    fun surroundingWhitespaceIsTrimmedBecauseOperatorsPaste() {
        val verdict = TelemetryUrl.check("  https://abc.supabase.co  ")
        assertEquals("https://abc.supabase.co", (verdict as UrlVerdict.Valid).normalised)
    }

    @Test
    fun aUrlWithNoHostIsRejected() {
        assertTrue(TelemetryUrl.check("https://") is UrlVerdict.Invalid)
    }

    @Test
    fun blankIsRejected() {
        assertTrue(TelemetryUrl.check("   ") is UrlVerdict.Invalid)
    }

    @Test
    fun somethingThatIsNotAUrlIsRejectedRatherThanThrowing() {
        assertTrue(TelemetryUrl.check("not a url at all") is UrlVerdict.Invalid)
    }

    @Test
    fun savingThenLoadingReturnsTheNormalisedDestination() {
        val creds = credentials()
        assertTrue(creds.save("https://abc.supabase.co/", " anon-key ") is UrlVerdict.Valid)
        val config = creds.load()!!
        assertEquals("https://abc.supabase.co", config.baseUrl)
        assertEquals("anon-key", config.anonKey)
    }

    @Test
    fun anInvalidUrlIsNotStored() {
        val creds = credentials()
        assertTrue(creds.save("http://abc.supabase.co", "anon-key") is UrlVerdict.Invalid)
        assertNull("a rejected destination must not be half-written", creds.load())
        assertFalse(creds.isConfigured())
    }

    @Test
    fun aBlankKeyIsRejectedAndNotStored() {
        val creds = credentials()
        assertTrue(creds.save("https://abc.supabase.co", "  ") is UrlVerdict.Invalid)
        assertNull(creds.load())
    }

    @Test
    fun loadIsNullWhenOnlyOneHalfIsPresent() {
        val store = InMemorySecretStore()
        store.put("telemetry_base_url", "https://abc.supabase.co")
        assertNull("half a destination is not a destination", TelemetryCredentials(store).load())
    }

    @Test
    fun clearRemovesBothHalves() {
        val store = InMemorySecretStore()
        val creds = TelemetryCredentials(store)
        creds.save("https://abc.supabase.co", "anon-key")
        creds.clear()
        assertNull(creds.load())
        assertFalse(creds.isConfigured())
        assertNull("the key must not outlive the url", store.get("telemetry_anon_key"))
    }

    /**
     * A kiosk whose Keystore is unusable must say so, not accept the credentials and
     * silently never report. The operator is standing at the machine and can retry.
     */
    @Test
    fun aStoreThatCannotWriteIsReportedRatherThanAccepted() {
        val creds = TelemetryCredentials(UnwritableSecretStore())
        val verdict = creds.save("https://abc.supabase.co", "anon-key")
        assertTrue(verdict is UrlVerdict.Invalid)
        assertFalse("a failed save must not look configured", creds.isConfigured())
    }

    /** A generated toString on a credential holder is how keys reach logcat. */
    @Test
    fun theConfigDoesNotPrintItsKey() {
        val config = TelemetryConfig("https://abc.supabase.co", "super-secret-anon-key")
        assertFalse(config.toString().contains("super-secret-anon-key"))
    }
}
