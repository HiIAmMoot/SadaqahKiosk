package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Test

class TelemetryRedactorTest {

    private val affiliateKey = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

    // ── Affiliate key ────────────────────────────────────────────────────────

    @Test
    fun scrub_removesAffiliateKey() {
        val text = "login failed for key $affiliateKey after retry"
        val out = TelemetryRedactor.scrub(text, affiliateKey)!!
        assertFalse(out.contains(affiliateKey))
        assertTrue(out.contains(TelemetryRedactor.REDACTED))
    }

    @Test
    fun scrub_removesEveryOccurrenceOfTheKey() {
        val text = "$affiliateKey and again $affiliateKey"
        val out = TelemetryRedactor.scrub(text, affiliateKey)!!
        assertFalse(out.contains(affiliateKey))
    }

    @Test
    fun scrub_removesTheAffiliateKeyRegardlessOfCase() {
        val text = "rejected key ${affiliateKey.uppercase()} at login"
        val out = TelemetryRedactor.scrub(text, affiliateKey)!!
        assertFalse(out.contains(affiliateKey.uppercase()))
        assertTrue(out.contains(TelemetryRedactor.REDACTED))
    }

    @Test
    fun scrub_toleratesNullOrBlankKey() {
        assertEquals("nothing secret here", TelemetryRedactor.scrub("nothing secret here", null))
        assertEquals("nothing secret here", TelemetryRedactor.scrub("nothing secret here", ""))
    }

    @Test
    fun scrub_nullTextStaysNull() {
        assertNull(TelemetryRedactor.scrub(null, affiliateKey))
    }

    // ── Token-shaped strings ─────────────────────────────────────────────────

    @Test
    fun scrub_removesTokenShapedRuns() {
        val token = "sbp_0123456789abcdef0123456789abcdef0123"
        val out = TelemetryRedactor.scrub("key=$token", null)!!
        assertFalse(out.contains(token))
        assertTrue(out.contains(TelemetryRedactor.REDACTED))
    }

    @Test
    fun scrub_removesUuidShapedStrings() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertFalse(TelemetryRedactor.scrub(uuid, null)!!.contains(uuid))
    }

    /**
     * The redactor runs over stack traces. Over-redacting destroys the thing we
     * collected the trace for, so ordinary identifiers must survive.
     */
    @Test
    fun scrub_keepsFullyQualifiedClassNames() {
        val trace = "at com.sadaqah.kiosk.update.UpdateWatchdogReceiver.onReceive(UpdateWatchdogReceiver.kt:47)"
        assertEquals(trace, TelemetryRedactor.scrub(trace, null))
    }

    @Test
    fun scrub_keepsFilePaths() {
        val path = "/data/user/0/com.sadaqah.kiosk/files/telemetry/outbox.jsonl"
        assertEquals(path, TelemetryRedactor.scrub(path, null))
    }

    @Test
    fun scrub_keepsOrdinaryProse() {
        val text = "Card reader connect failed after 3 attempts"
        assertEquals(text, TelemetryRedactor.scrub(text, null))
    }

    @Test
    fun scrub_keepsShortHexRuns() {
        // A 16-char run is under the threshold; commit SHAs and short ids survive.
        val text = "commit 0123456789abcdef"
        assertEquals(text, TelemetryRedactor.scrub(text, null))
    }

    /**
     * The original fixture cleared the threshold by only four characters.
     * A deeper path is the realistic case and must also survive.
     */
    @Test
    fun scrub_keepsDeeplyNestedFilePaths() {
        val path = "/data/user/0/com.sadaqah.kiosk/files/telemetry/outbox_events_archive_2026.jsonl"
        assertEquals(path, TelemetryRedactor.scrub(path, null))
    }

    // ── Truncation ───────────────────────────────────────────────────────────

    @Test
    fun truncate_leavesShortTextAlone() {
        assertEquals("short", TelemetryRedactor.truncate("short"))
    }

    @Test
    fun truncate_nullStaysNull() {
        assertNull(TelemetryRedactor.truncate(null))
    }

    @Test
    fun truncate_capsLongText() {
        val long = "x".repeat(20_000)
        val out = TelemetryRedactor.truncate(long)!!
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= TelemetryRedactor.MAX_TEXT_BYTES + 32)
        assertTrue(out.length < long.length)
    }

    @Test
    fun truncate_marksThatItTruncated() {
        val out = TelemetryRedactor.truncate("y".repeat(20_000))!!
        assertTrue(out.endsWith("… truncated"))
    }

    @Test
    fun truncate_exactlyAtLimitIsUntouched() {
        val exact = "z".repeat(TelemetryRedactor.MAX_TEXT_BYTES)
        assertEquals(exact, TelemetryRedactor.truncate(exact))
    }

    /**
     * The byte cut can land inside a multi-byte character. That must degrade to a
     * replacement character, not an exception — this runs on the crash path.
     */
    @Test
    fun truncate_handlesACutInsideAMultiByteCharacter() {
        // "€" is three UTF-8 bytes, so repeating it guarantees the 8192-byte
        // boundary falls inside one of them.
        val text = "€".repeat(10_000)
        val out = TelemetryRedactor.truncate(text)!!
        assertTrue(out.endsWith("… truncated"))
        assertTrue(out.length < text.length)
    }
}
