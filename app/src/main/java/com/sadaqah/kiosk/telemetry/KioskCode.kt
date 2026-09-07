package com.sadaqah.kiosk.telemetry

/**
 * The printed panel code, and the shape this vendor's codes happen to take.
 *
 * Validation is **advisory**. A fork of this app has no kiosk-code scheme and
 * never will, so rejecting a non-conforming code would mean the software only
 * runs for one vendor. With no code configured at all, `installId` identifies the
 * device, which is everything a single-site operator needs.
 *
 * The convention's source of truth is the sadaqahkiosk.nl site repository; the two
 * cannot import from each other, so it is restated here rather than shared.
 *
 * The real shape looks like `nl-gld-arnhem-nour_al_houda-01`: lowercase
 * hyphen-separated segments — country, region, city, mosque slug — ending in a
 * numeric suffix. The exact segment count and meaning are the site repository's
 * business, not this app's, so [CONVENTION] deliberately does not pin either:
 * it only requires hyphen-separated alphanumeric/underscore segments ending in
 * digits. **When in doubt, be permissive** — this validation never blocks
 * anything, and a warning that fires on every correctly-provisioned kiosk
 * trains operators to ignore it, which is worse than not having it at all.
 */
object KioskCode {
    /** One or more hyphen-separated segments of letters, digits and
     *  underscores, the last of which is a run of digits — e.g.
     *  `nl-gld-arnhem-nour_al_houda-01`. Not `const` — a `Regex` is not a
     *  compile-time constant, so it cannot be one. Non-private so the settings
     *  screen in a later phase can show the expected shape. */
    val CONVENTION = Regex("^[A-Za-z0-9_]+(-[A-Za-z0-9_]+)*-\\d+$")

    /** Blank is conventional: it means "this deployment has no code scheme",
     *  which is supported, not a mistake to warn about on every screen. */
    fun looksConventional(code: String): Boolean =
        code.isBlank() || CONVENTION.matches(code.trim())

    /** The value a caller should actually persist. Validation trims before
     *  matching, so without this a code that "looks conventional" could still
     *  be stored with the whitespace that trimming looked past. */
    fun normalize(code: String): String = code.trim()
}
