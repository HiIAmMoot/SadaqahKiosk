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
     * The publishable key travels as a request header. Over cleartext it is readable by
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

    /**
     * "https://" itself trims to "https:" and fails to parse before the host
     * guard is ever reached (see the "That is not a valid URL" branch). This
     * input parses cleanly with scheme "https" and a null host, so the host
     * guard is what actually rejects it.
     */
    @Test
    fun aUrlWithNoHostIsRejected() {
        assertTrue(TelemetryUrl.check("https:/only-a-path") is UrlVerdict.Invalid)
    }

    @Test
    fun blankIsRejected() {
        val verdict = TelemetryUrl.check("   ")
        assertTrue(verdict is UrlVerdict.Invalid)
        assertEquals("Enter the Supabase project URL.", (verdict as UrlVerdict.Invalid).reason)
    }

    @Test
    fun somethingThatIsNotAUrlIsRejectedRatherThanThrowing() {
        assertTrue(TelemetryUrl.check("not a url at all") is UrlVerdict.Invalid)
    }

    /**
     * A base URL carrying credentials is stored verbatim under baseUrl, and
     * TelemetryConfig.toString() prints baseUrl in full — so a userinfo component
     * would defeat the hand-written redaction the moment an operator pastes one.
     */
    @Test
    fun aUrlWithUserinfoIsRejected() {
        val verdict = TelemetryUrl.check("https://user:pass@abc.supabase.co")
        assertTrue(verdict is UrlVerdict.Invalid)
        assertEquals(
            "Remove the username and password from the URL.",
            (verdict as UrlVerdict.Invalid).reason
        )
    }

    /** Supabase hands out `?apikey=...` URLs; that query must not become baseUrl. */
    @Test
    fun aUrlWithAQueryIsRejected() {
        val verdict = TelemetryUrl.check("https://abc.supabase.co?apikey=secret")
        assertTrue(verdict is UrlVerdict.Invalid)
        assertEquals(
            "Remove everything from the ? onwards.",
            (verdict as UrlVerdict.Invalid).reason
        )
    }

    @Test
    fun aUrlWithAFragmentIsRejected() {
        val verdict = TelemetryUrl.check("https://abc.supabase.co#section")
        assertTrue(verdict is UrlVerdict.Invalid)
        assertEquals(
            "Remove everything from the # onwards.",
            (verdict as UrlVerdict.Invalid).reason
        )
    }

    /**
     * java.net.URI treats an authority it can't parse as a hostname as
     * "registry-based" and returns a null host, even though a perfectly good host
     * is present. An underscore in a self-hosted project name is legal DNS.
     */
    @Test
    fun aRegistryBasedHostIsAccepted() {
        val verdict = TelemetryUrl.check("https://my_project.supabase.co")
        assertTrue(verdict is UrlVerdict.Valid)
        assertEquals("https://my_project.supabase.co", (verdict as UrlVerdict.Valid).normalised)
    }

    /** Schemes are case-insensitive (RFC 3986 3.1) but must be stored consistently. */
    @Test
    fun aMixedCaseSchemeNormalisesToLowercase() {
        val verdict = TelemetryUrl.check("HtTpS://abc.supabase.co")
        assertTrue(verdict is UrlVerdict.Valid)
        assertTrue((verdict as UrlVerdict.Valid).normalised.startsWith("https://"))
    }

    @Test
    fun savingThenLoadingReturnsTheNormalisedDestination() {
        val creds = credentials()
        assertTrue(creds.save("https://abc.supabase.co/", " publishable-key ") is UrlVerdict.Valid)
        val config = creds.load()!!
        assertEquals("https://abc.supabase.co", config.baseUrl)
        assertEquals("publishable-key", config.publishableKey)
    }

    @Test
    fun anInvalidUrlIsNotStored() {
        val creds = credentials()
        assertTrue(creds.save("http://abc.supabase.co", "publishable-key") is UrlVerdict.Invalid)
        assertNull("a rejected destination must not be half-written", creds.load())
        assertFalse(creds.isConfigured())
    }

    @Test
    fun aBlankKeyIsRejectedAndNotStored() {
        val store = InMemorySecretStore()
        val creds = TelemetryCredentials(store)
        assertTrue(creds.save("https://abc.supabase.co", "  ") is UrlVerdict.Invalid)
        assertNull(creds.load())
        assertNull(
            "the url must never be written when the key is blank",
            store.get("telemetry_base_url")
        )
    }

    @Test
    fun loadIsNullWhenOnlyOneHalfIsPresent() {
        val store = InMemorySecretStore()
        store.put("telemetry_base_url", "https://abc.supabase.co")
        assertNull("half a destination is not a destination", TelemetryCredentials(store).load())
    }

    /**
     * The scheme is enforced when a destination is saved, but load() is the path
     * every flush actually reads from. A stored http:// URL — from a downgrade, a
     * migration, or any other writer touching the same keys — must not load as a
     * working destination just because both halves are present.
     */
    @Test
    fun aStoredHttpUrlMakesLoadReturnNull() {
        val store = InMemorySecretStore()
        store.put("telemetry_base_url", "http://abc.supabase.co")
        store.put("telemetry_publishable_key", "publishable-key")
        assertNull(TelemetryCredentials(store).load())
    }

    @Test
    fun clearRemovesBothHalves() {
        val store = InMemorySecretStore()
        val creds = TelemetryCredentials(store)
        creds.save("https://abc.supabase.co", "publishable-key")
        creds.clear()
        assertNull(creds.load())
        assertFalse(creds.isConfigured())
        assertNull("the url must not outlive clear()", store.get("telemetry_base_url"))
        assertNull("the key must not outlive clear()", store.get("telemetry_publishable_key"))
    }

    /**
     * A kiosk whose Keystore is unusable must say so, not accept the credentials and
     * silently never report. The operator is standing at the machine and can retry.
     *
     * Note: UnwritableSecretStore.get() always returns null, so
     * assertFalse(isConfigured()) alone would pass for any TelemetryCredentials
     * implementation and proves nothing about the rollback specifically. The real
     * coverage for the rollback is aFailedSecondWriteRollsBackTheFirst below, which
     * uses a store where the first put succeeds.
     */
    @Test
    fun aStoreThatCannotWriteIsReportedRatherThanAccepted() {
        val creds = TelemetryCredentials(UnwritableSecretStore())
        val verdict = creds.save("https://abc.supabase.co", "publishable-key")
        assertTrue(verdict is UrlVerdict.Invalid)
        assertFalse("a failed save must not look configured", creds.isConfigured())
    }

    /**
     * UnwritableSecretStore fails both puts, so `&&` short-circuits and the second
     * put never runs: the rollback in save() is never exercised by any other test.
     * This store succeeds on the first put and fails on the second, so the
     * half-written state save() must clean up is actually reached.
     */
    @Test
    fun aFailedSecondWriteRollsBackTheFirst() {
        val store = FailsNthWriteSecretStore(failOn = 2)
        val creds = TelemetryCredentials(store)
        val verdict = creds.save("https://abc.supabase.co", "publishable-key")
        assertTrue(verdict is UrlVerdict.Invalid)
        assertNull(creds.load())
        assertFalse(creds.isConfigured())
        assertNull(
            "a half-written url must not survive a failed save",
            store.get("telemetry_base_url")
        )
        assertNull(
            "a half-written key must not survive a failed save",
            store.get("telemetry_publishable_key")
        )
    }

    @Test
    fun isConfiguredIsTrueAfterASuccessfulSave() {
        val creds = credentials()
        creds.save("https://abc.supabase.co", "publishable-key")
        assertTrue(creds.isConfigured())
    }

    @Test
    fun savingASecondDestinationReplacesTheFirst() {
        val creds = credentials()
        creds.save("https://first.supabase.co", "first-key")
        creds.save("https://second.supabase.co", "second-key")
        val config = creds.load()!!
        assertEquals("https://second.supabase.co", config.baseUrl)
        assertEquals("second-key", config.publishableKey)
    }

    /** A generated toString on a credential holder is how keys reach logcat. */
    @Test
    fun theConfigDoesNotPrintItsKey() {
        val config = TelemetryConfig("https://abc.supabase.co", "super-secret-publishable-key")
        assertFalse(config.toString().contains("super-secret-publishable-key"))
    }

    /**
     * Backed by an in-memory map so reads reflect what was actually written, but
     * the Nth call to put() reports failure without writing — the only way to
     * reach save()'s rollback, since UnwritableSecretStore fails every put and
     * short-circuits before the second one ever runs.
     */
    private class FailsNthWriteSecretStore(private val failOn: Int) : SecretStore {
        private val delegate = InMemorySecretStore()
        private var calls = 0

        override fun put(key: String, value: String): Boolean {
            calls++
            if (calls == failOn) return false
            return delegate.put(key, value)
        }

        override fun get(key: String): String? = delegate.get(key)
        override fun remove(key: String) = delegate.remove(key)
    }

    /**
     * The raw-authority fallback that lets underscore and IDN hosts through is
     * unparsed, so percent-encoding can smuggle back the delimiters rejected
     * above: %3F is a query, %40 is userinfo, %23 is a fragment. All three were
     * rejected before that fallback existed, and a secret in the query lands in
     * baseUrl, which TelemetryConfig prints in full.
     */
    @Test
    fun percentEncodedDelimitersCannotSmuggleASecretIntoTheHost() {
        assertTrue(TelemetryUrl.check("https://abc.supabase.co%3Fapikey=SECRET") is UrlVerdict.Invalid)
        assertTrue(TelemetryUrl.check("https://user:pass%40abc.supabase.co") is UrlVerdict.Invalid)
        assertTrue(TelemetryUrl.check("https://abc.supabase.co%23tok=SECRET") is UrlVerdict.Invalid)
    }

    /** ":8443" is a non-blank authority that names no host. */
    @Test
    fun aBarePortIsRejected() {
        assertTrue(TelemetryUrl.check("https://:8443") is UrlVerdict.Invalid)
    }

    /** The tightening must not cost the hosts the fallback exists to serve. */
    @Test
    fun theFallbackStillAcceptsTheHostsItWasAddedFor() {
        assertTrue(TelemetryUrl.check("https://my_project.supabase.co") is UrlVerdict.Valid)
        assertTrue(TelemetryUrl.check("https://abc.supabase.co:8443") is UrlVerdict.Valid)
    }
}
