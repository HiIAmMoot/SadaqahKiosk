package com.sadaqah.kiosk.update

import org.junit.Assert.*
import org.junit.Test

/**
 * The rules that keep preview builds off the fleet: previews are selectable by
 * hand, never resolve as the automatic target, and a preview pin expires by
 * itself once the release it was testing has shipped.
 */
class UpdateTargetResolutionTest {

    private fun release(version: String, preview: Boolean = false) = ReleaseInfo(
        tag = "v$version",
        version = SemVer.parse(version)!!,
        name = version,
        body = "",
        publishedAtIso = "2026-09-01T00:00:00Z",
        apkUrl = "https://example.invalid/$version.apk",
        apkSizeBytes = 1234L,
        isPreview = preview
    )

    /** Eligible releases arrive sorted newest-first, as checkForUpdate builds them. */
    private val stable136 = release("1.3.6")
    private val preview137 = release("1.3.7-preview", preview = true)
    private val stable135 = release("1.3.5")

    // ── Unpinned ("latest") ──────────────────────────────────────────────────

    @Test
    fun unpinned_skipsPreviewAndTakesNewestStable() {
        val eligible = listOf(preview137, stable136, stable135)
        val result = resolveUpdateTarget(eligible, pinned = null)
        assertEquals(stable136, result.target)
        assertFalse(result.pinExpired)
    }

    @Test
    fun unpinned_takesNewestWhenNoPreviewsPresent() {
        assertEquals(stable136, resolveUpdateTarget(listOf(stable136, stable135), null).target)
    }

    @Test
    fun unpinned_returnsNullWhenOnlyPreviewsAvailable() {
        assertNull(resolveUpdateTarget(listOf(preview137), null).target)
    }

    @Test
    fun unpinned_returnsNullWhenNothingEligible() {
        assertNull(resolveUpdateTarget(emptyList(), null).target)
    }

    /** A release flagged pre-release on GitHub is skipped even with a plain tag. */
    @Test
    fun unpinned_skipsGitHubFlaggedPreviewWithPlainVersion() {
        val flagged = release("1.3.7", preview = true)
        assertEquals(stable136, resolveUpdateTarget(listOf(flagged, stable136), null).target)
    }

    // ── Pinned to a stable version ───────────────────────────────────────────

    @Test
    fun pinned_selectsExactStableVersion() {
        val eligible = listOf(preview137, stable136, stable135)
        val result = resolveUpdateTarget(eligible, SemVer.parse("1.3.5"))
        assertEquals(stable135, result.target)
        assertFalse(result.pinExpired)
    }

    @Test
    fun pinned_stableNeverExpiresEvenWhenNewerStableExists() {
        val eligible = listOf(stable136, stable135)
        val result = resolveUpdateTarget(eligible, SemVer.parse("1.3.5"))
        assertEquals(stable135, result.target)
        assertFalse(result.pinExpired)
    }

    @Test
    fun pinned_returnsNullWhenPinnedVersionIsGone() {
        assertNull(resolveUpdateTarget(listOf(stable136, stable135), SemVer.parse("1.3.1")).target)
    }

    /** Pinning to 1.3.7 must not match the 1.3.7-preview release. */
    @Test
    fun pinned_stableDoesNotMatchPreviewOfSameNumber() {
        assertNull(resolveUpdateTarget(listOf(preview137), SemVer.parse("1.3.7")).target)
    }

    // ── Pinned to a preview, still current ───────────────────────────────────

    @Test
    fun pinnedPreview_selectedWhileNothingNewerIsStable() {
        val eligible = listOf(preview137, stable136, stable135)
        val result = resolveUpdateTarget(eligible, SemVer.parse("1.3.7-preview"))
        assertEquals(preview137, result.target)
        assertFalse(result.pinExpired)
    }

    /**
     * The important one: pinning a kiosk to a preview that is AHEAD of the
     * current stable must not immediately undo itself, or the preview could
     * never be tested at all.
     */
    @Test
    fun pinnedPreview_doesNotExpireForAnOlderStable() {
        val eligible = listOf(preview137, stable136)
        val result = resolveUpdateTarget(eligible, SemVer.parse("1.3.7-preview"))
        assertEquals(preview137, result.target)
        assertFalse(result.pinExpired)
    }

    @Test
    fun pinnedPreview_returnsNullWhenPreviewIsGoneAndNothingSupersedesIt() {
        val result = resolveUpdateTarget(listOf(stable136), SemVer.parse("1.4.9-preview"))
        assertNull(result.target)
        assertFalse(result.pinExpired)
    }

    // ── Pinned to a preview, superseded ──────────────────────────────────────

    @Test
    fun pinnedPreview_expiresWhenItsOwnStableShips() {
        val stable137 = release("1.3.7")
        val eligible = listOf(stable137, preview137, stable136)
        val result = resolveUpdateTarget(eligible, SemVer.parse("1.3.7-preview"))
        assertEquals(stable137, result.target)
        assertTrue(result.pinExpired)
    }

    @Test
    fun pinnedPreview_expiresWhenAHigherTrackStableShips() {
        val stable140 = release("1.4.0")
        val eligible = listOf(stable140, preview137, stable136)
        val result = resolveUpdateTarget(eligible, SemVer.parse("1.3.7-preview"))
        assertEquals(stable140, result.target)
        assertTrue(result.pinExpired)
    }

    @Test
    fun pinnedPreview_expiresToNewestStableNotJustTheFirstAboveIt() {
        val stable137 = release("1.3.7")
        val stable138 = release("1.3.8")
        val eligible = listOf(stable138, stable137, preview137)
        val result = resolveUpdateTarget(eligible, SemVer.parse("1.3.7-preview"))
        assertEquals(stable138, result.target)
        assertTrue(result.pinExpired)
    }

    /** A newer PREVIEW must not expire the pin — only a stable release does. */
    @Test
    fun pinnedPreview_doesNotExpireForANewerPreview() {
        val preview138 = release("1.3.8-preview", preview = true)
        val eligible = listOf(preview138, preview137)
        val result = resolveUpdateTarget(eligible, SemVer.parse("1.3.7-preview"))
        assertEquals(preview137, result.target)
        assertFalse(result.pinExpired)
    }

    /**
     * Guards the oscillation bug: once the pin is cleared to "latest", the very
     * next resolution must settle on the stable and stay there, never swinging
     * back to the preview.
     */
    @Test
    fun pinnedPreview_afterExpiryUnpinnedResolutionStaysOnStable() {
        val stable137 = release("1.3.7")
        val eligible = listOf(stable137, preview137, stable136)

        val expiry = resolveUpdateTarget(eligible, SemVer.parse("1.3.7-preview"))
        assertTrue(expiry.pinExpired)

        // Caller has now written autoUpdateTargetVersion = "latest".
        val afterUnpin = resolveUpdateTarget(eligible, pinned = null)
        assertEquals(stable137, afterUnpin.target)
        assertFalse(afterUnpin.pinExpired)
    }
}
