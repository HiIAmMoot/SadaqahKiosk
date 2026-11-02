package com.sadaqah.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All fifteen disclosure members, listed once so both tests below walk the same set. */
private fun disclosureMembers(s: Strings): List<String> = listOf(
    s.disclosureTitle,
    s.disclosureIntro,
    s.disclosureSendsHeading,
    s.disclosureSendsAmount,
    s.disclosureSendsIdentity,
    s.disclosureSendsHealth,
    s.disclosureIdentified,
    s.disclosureNeverHeading,
    s.disclosureNeverBody,
    s.disclosureDestinationHeading,
    s.disclosureOffBody,
    s.disclosurePrivacyLabel,
    s.disclosureTermsLabel,
    s.disclosureDismiss,
    s.disclosureReopen,
)

class TranslationsTest {

    /**
     * A member present but empty compiles and ships a blank screen. The
     * interface catches a *missing* override; nothing catches an empty one.
     */
    @Test
    fun `every language defines every disclosure member`() {
        Language.entries.forEach { language ->
            val s = TranslationManager.stringsFor(language)
            disclosureMembers(s).forEachIndexed { i, value ->
                assertTrue("${language.code} member $i is blank", value.isNotBlank())
            }
        }
    }

    /**
     * URLs come from settings and differ per deployment. A hardcoded one in
     * copy would be wrong in every fork, and this phase's whole posture is that
     * the recipient is the endpoint the operator typed.
     */
    @Test
    fun `no translated disclosure copy contains a url`() {
        Language.entries.forEach { language ->
            val s = TranslationManager.stringsFor(language)
            disclosureMembers(s).forEach {
                assertFalse("${language.code}: copy must not embed a URL", it.contains("http"))
            }
        }
    }
}
