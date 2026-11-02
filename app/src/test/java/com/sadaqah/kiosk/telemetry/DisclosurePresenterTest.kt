package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisclosurePresenterTest {

    private fun settings(
        privacy: String = "https://example.org/privacy",
        terms: String = "https://example.org/terms",
        kioskCode: String = "SK-0042"
    ) = Settings(
        analyticsPrivacyPolicyUrl = privacy,
        analyticsTermsUrl = terms,
        kioskCode = kioskCode
    )

    /**
     * The founder's rule: nothing to point at, no screen. This screen exists to
     * direct an operator to the published document that actually discharges the
     * disclosure obligation — with no document there is nothing to direct them
     * to, and a screen rendering everything *except* the policy section would
     * read as complete when it is not.
     */
    @Test
    fun `no privacy url means no screen at all`() {
        assertNull(DisclosurePresenter.view(settings(privacy = ""), "https://abc.supabase.co"))
    }

    @Test
    fun `a blank destination means no screen`() {
        assertNull(DisclosurePresenter.view(settings(), ""))
    }

    /**
     * policyUrlsMissing on AnalyticsPresenter is an OR over both URLs, so it
     * cannot govern this: a kiosk with a policy but no terms would lose the
     * policy block too. The terms block is independently optional.
     */
    @Test
    fun `a missing terms url does not suppress the screen or the policy block`() {
        val view = DisclosurePresenter.view(settings(terms = ""), "https://abc.supabase.co")!!
        assertEquals("https://example.org/privacy", view.privacyUrl)
        assertNull(view.termsUrl)
    }

    @Test
    fun `the destination shown is the one passed in`() {
        val view = DisclosurePresenter.view(settings(), "https://abc.supabase.co")!!
        assertEquals("https://abc.supabase.co", view.destinationUrl)
    }

    /** Pins the present case: a set terms URL must reach the view, not just be
     *  nulled out when absent. Without this, hardcoding `termsUrl = null` in
     *  the presenter still passed every other test here. */
    @Test
    fun `a set terms url reaches the view`() {
        val view = DisclosurePresenter.view(settings(), "https://abc.supabase.co")!!
        assertEquals("https://example.org/terms", view.termsUrl)
    }

}
