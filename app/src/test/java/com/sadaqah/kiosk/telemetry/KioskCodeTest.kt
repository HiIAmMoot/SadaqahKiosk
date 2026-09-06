package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskCodeTest {
    @Test
    fun aConventionalCodeIsRecognised() {
        assertTrue(KioskCode.looksConventional("SK-0042"))
    }

    @Test
    fun lowercaseIsAcceptedBecauseOperatorsType() {
        assertTrue(KioskCode.looksConventional("sk-0042"))
    }

    @Test
    fun anUnconventionalCodeIsFlaggedButThisIsAdvisoryOnly() {
        assertFalse(KioskCode.looksConventional("mosque-front-door"))
    }

    /** A fork with no code scheme is a supported deployment. Blank must not be
     *  reported as a problem, or the warning fires on every such kiosk forever. */
    @Test
    fun blankIsNotFlagged() {
        assertTrue(KioskCode.looksConventional(""))
    }

    /** A code that validates as conventional only because validation trims it
     *  must not be stored with the whitespace that made that necessary. */
    @Test
    fun normalizeStripsWhitespaceSoTheStoredValueMatchesWhatWasValidated() {
        assertEquals("SK-0042", KioskCode.normalize(" SK-0042 "))
    }
}
