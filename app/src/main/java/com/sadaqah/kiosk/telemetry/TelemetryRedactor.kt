package com.sadaqah.kiosk.telemetry

/**
 * Strips secrets out of free text and caps its size.
 *
 * Scrubbing runs on the way to disk rather than on the way to the network: the
 * outbox survives crashes and can be pulled off a device, so an unredacted value
 * must never be written to a file in the first place.
 */
object TelemetryRedactor {
    const val REDACTED = "[redacted]"
    const val MAX_TEXT_BYTES = 8 * 1024

    /**
     * Runs of 32+ token characters. The threshold is a deliberate trade: long
     * enough that package names, file paths and short hashes survive — the
     * redactor runs over stack traces, and over-redacting destroys the reason we
     * collected them — short enough to catch keys, tokens and UUIDs.
     */
    private val TOKEN_SHAPED = Regex("[A-Za-z0-9+/=_-]{32,}")

    fun scrub(text: String?, affiliateKey: String?): String? {
        if (text == null) return null
        val withoutKey =
            if (affiliateKey.isNullOrBlank()) text else text.replace(affiliateKey, REDACTED)
        return TOKEN_SHAPED.replace(withoutKey, REDACTED)
    }

    fun truncate(text: String?): String? {
        if (text == null) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_TEXT_BYTES) return text
        // A cut can land mid-codepoint; the resulting replacement char is
        // harmless in a diagnostic and cheaper than scanning for a boundary.
        return String(bytes, 0, MAX_TEXT_BYTES, Charsets.UTF_8) + "\n… truncated"
    }
}
