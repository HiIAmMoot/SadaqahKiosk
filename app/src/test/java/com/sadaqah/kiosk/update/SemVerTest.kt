package com.sadaqah.kiosk.update

import org.junit.Assert.*
import org.junit.Test

class SemVerTest {

    // ── Parsing ──────────────────────────────────────────────────────────────

    @Test
    fun parse_plainVersion() {
        assertEquals(SemVer(1, 3, 5), SemVer.parse("1.3.5"))
    }

    @Test
    fun parse_stripsLeadingV() {
        assertEquals(SemVer(1, 3, 5), SemVer.parse("v1.3.5"))
        assertEquals(SemVer(1, 3, 5), SemVer.parse("V1.3.5"))
    }

    @Test
    fun parse_preReleaseSuffix() {
        assertEquals(SemVer(1, 3, 5, "preview"), SemVer.parse("1.3.5-preview"))
    }

    @Test
    fun parse_preReleaseSuffixWithLeadingV() {
        assertEquals(SemVer(1, 3, 5, "preview"), SemVer.parse("v1.3.5-preview"))
    }

    @Test
    fun parse_arbitraryPreReleaseLabel() {
        assertEquals(SemVer(2, 0, 0, "rc.1"), SemVer.parse("2.0.0-rc.1"))
        assertEquals(SemVer(1, 4, 0, "beta"), SemVer.parse("1.4.0-beta"))
    }

    @Test
    fun parse_rejectsEmptyPreReleaseLabel() {
        assertNull(SemVer.parse("1.3.5-"))
    }

    @Test
    fun parse_rejectsNonSemver() {
        assertNull(SemVer.parse("1.3"))
        assertNull(SemVer.parse("1.3.5.6"))
        assertNull(SemVer.parse("not-a-version"))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("1.x.5"))
    }

    // ── toString round-trip ──────────────────────────────────────────────────
    // The settings dropdown stores version.toString() into autoUpdateTargetVersion
    // and re-parses it via pinnedTarget(), so this MUST round-trip exactly.

    @Test
    fun toString_plainVersion() {
        assertEquals("1.3.5", SemVer(1, 3, 5).toString())
    }

    @Test
    fun toString_preRelease() {
        assertEquals("1.3.5-preview", SemVer(1, 3, 5, "preview").toString())
    }

    @Test
    fun roundTrip_throughStringAndBack() {
        for (raw in listOf("1.3.5", "1.3.5-preview", "2.0.0-rc.1", "10.20.30")) {
            val parsed = SemVer.parse(raw)
            assertNotNull("failed to parse $raw", parsed)
            assertEquals(raw, parsed.toString())
            assertEquals(parsed, SemVer.parse(parsed.toString()))
        }
    }

    // ── isPreview ────────────────────────────────────────────────────────────

    @Test
    fun isPreview_falseForPlainVersion() {
        assertFalse(SemVer(1, 3, 5).isPreview)
    }

    @Test
    fun isPreview_trueForPreRelease() {
        assertTrue(SemVer(1, 3, 5, "preview").isPreview)
    }

    // ── Ordering ─────────────────────────────────────────────────────────────

    @Test
    fun ordering_byMajorMinorPatch() {
        assertTrue(SemVer(1, 3, 4) < SemVer(1, 3, 5))
        assertTrue(SemVer(1, 3, 9) < SemVer(1, 4, 0))
        assertTrue(SemVer(1, 9, 9) < SemVer(2, 0, 0))
    }

    /** Semver rule: a pre-release ranks below its own release. */
    @Test
    fun ordering_preReleaseIsBelowItsRelease() {
        assertTrue(SemVer(1, 3, 5, "preview") < SemVer(1, 3, 5))
    }

    @Test
    fun ordering_preReleaseIsAbovePreviousPatch() {
        assertTrue(SemVer(1, 3, 4) < SemVer(1, 3, 5, "preview"))
    }

    @Test
    fun ordering_fullChain() {
        val sorted = listOf(
            SemVer(1, 3, 5),
            SemVer(1, 3, 4),
            SemVer(1, 3, 5, "preview"),
            SemVer(1, 4, 0)
        ).sorted()

        assertEquals(
            listOf(
                SemVer(1, 3, 4),
                SemVer(1, 3, 5, "preview"),
                SemVer(1, 3, 5),
                SemVer(1, 4, 0)
            ),
            sorted
        )
    }

    @Test
    fun ordering_twoPreReleasesOfSameVersion() {
        assertTrue(SemVer(1, 3, 5, "alpha") < SemVer(1, 3, 5, "beta"))
    }

    @Test
    fun ordering_equalVersionsCompareZero() {
        assertEquals(0, SemVer(1, 3, 5).compareTo(SemVer(1, 3, 5)))
        assertEquals(0, SemVer(1, 3, 5, "preview").compareTo(SemVer(1, 3, 5, "preview")))
    }

    /** Previews below the auto-update floor must be filtered out like any other old build. */
    @Test
    fun ordering_previewOfMinimumVersionIsBelowMinimum() {
        assertTrue(SemVer(1, 3, 0, "preview") < MIN_AUTO_UPDATE_VERSION)
    }

    // ── Equality ─────────────────────────────────────────────────────────────

    @Test
    fun equality_preReleaseDistinguishesVersions() {
        assertNotEquals(SemVer(1, 3, 5), SemVer(1, 3, 5, "preview"))
    }

    @Test
    fun equality_defaultPreReleaseIsNull() {
        assertEquals(SemVer(1, 3, 5), SemVer(1, 3, 5, null))
    }
}
