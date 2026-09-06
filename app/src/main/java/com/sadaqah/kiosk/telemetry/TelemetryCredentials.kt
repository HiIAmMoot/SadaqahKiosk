package com.sadaqah.kiosk.telemetry

import java.net.URI

/**
 * Where telemetry goes and what authenticates it.
 *
 * Deliberately not a data class: a generated toString would print the
 * publishable key, and one `Log.d(TAG, "$config")` is all it takes to put a
 * credential in logcat.
 *
 * Named `publishableKey` because that is what Supabase calls it: the anon key
 * it replaces has been retired. The Postgres role it authenticates as is still
 * called `anon` (that did not change), so a `42501` permission-denied hint
 * mentioning `anon` is naming the role, not a stale key type.
 */
class TelemetryConfig(val baseUrl: String, val publishableKey: String) {
    override fun toString(): String = "TelemetryConfig(baseUrl=$baseUrl, publishableKey=[redacted])"
}

sealed class UrlVerdict {
    data class Valid(val normalised: String) : UrlVerdict()
    data class Invalid(val reason: String) : UrlVerdict()
}

/**
 * Validation for the destination the operator types.
 *
 * The reason strings are operator-facing: they are shown on the settings screen,
 * so they name what to fix rather than what failed internally.
 */
object TelemetryUrl {
    fun check(raw: String): UrlVerdict {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return UrlVerdict.Invalid("Enter the Supabase project URL.")

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return UrlVerdict.Invalid("That is not a valid URL.")
        }

        // Cleartext would put the publishable key on the wire in the clear, and a mosque's
        // wifi is not a controlled network. Checked here, at entry, so the operator
        // learns at the bench rather than from a kiosk that never reported.
        if (!"https".equals(uri.scheme, ignoreCase = true)) {
            return UrlVerdict.Invalid("The URL must start with https://")
        }

        // A base URL has no business carrying credentials, a query or a fragment:
        // TelemetryConfig.toString() prints baseUrl in full, so any of these would
        // defeat the hand-written redaction the moment an operator pastes a URL
        // that already has a token in it (Supabase hands those out as
        // `?apikey=...` URLs). uri.userInfo alone is not enough to catch it: a
        // registry-based authority (see the host fallback below) leaves userInfo
        // null even when the raw authority contains "user:pass@", so the raw
        // authority is checked too.
        val authorityHasUserinfo = uri.rawAuthority?.contains('@') == true
        if (uri.userInfo != null || authorityHasUserinfo) {
            return UrlVerdict.Invalid("Remove the username and password from the URL.")
        }
        if (uri.query != null) return UrlVerdict.Invalid("Remove everything from the ? onwards.")
        if (uri.fragment != null) return UrlVerdict.Invalid("Remove everything from the # onwards.")

        // uri.host is null for a registry-based authority (URI can't parse it as a
        // hostname) even though a perfectly good host is present — e.g. an
        // underscore in `my_project.supabase.co` or a raw IDN like `münchen.de`.
        // Falling back to the raw authority is safe only because the check above
        // has already rejected any authority containing '@'.
        // Two extra conditions on the fallback, because a raw authority is
        // unparsed. Percent-encoding hides the very delimiters rejected above —
        // "%3F" is a query and "%40" is userinfo, and both were rejected before
        // this fallback existed, so accepting them here would be a regression that
        // puts a secret back into baseUrl. And a bare ":8443" is non-blank while
        // naming no host at all.
        val host = uri.host ?: uri.rawAuthority?.takeIf { raw ->
            !raw.contains('%') && raw.substringBefore(':').isNotBlank()
        }
        if (host.isNullOrBlank()) return UrlVerdict.Invalid("That URL has no host.")

        // The scheme is lowercased explicitly because HtTpS:// is accepted (schemes
        // are case-insensitive per RFC 3986 §3.1) but must not be stored verbatim
        // under a name ("normalised") that implies it isn't. The authority and path
        // are carried through unchanged.
        val normalised = "https://" + uri.rawAuthority + (uri.rawPath ?: "")
        return UrlVerdict.Valid(normalised.trimEnd('/'))
    }
}

/**
 * Reads and writes the destination through a [SecretStore].
 *
 * Both halves are written together or not at all: a destination with a URL and no
 * key is indistinguishable from a misconfiguration, and the gate would report it
 * as configured and then fail every flush.
 */
class TelemetryCredentials(private val store: SecretStore) {

    fun load(): TelemetryConfig? {
        val storedUrl = store.get(KEY_URL)?.takeIf { it.isNotBlank() } ?: return null
        val key = store.get(KEY_PUBLISHABLE)?.takeIf { it.isNotBlank() } ?: return null
        // The scheme is enforced at save() time, but that is only where credentials
        // enter, not where they're used. A stored http:// URL — from a downgrade, a
        // migration, or any other writer touching the same keys — must not load as
        // a working destination just because it's non-blank: re-run the same check
        // the flush path relies on, and use its normalised form.
        val url = (TelemetryUrl.check(storedUrl) as? UrlVerdict.Valid)?.normalised ?: return null
        return TelemetryConfig(url, key)
    }

    /** Validates before writing anything, so a *validation* failure leaves the
     *  previous destination intact — nothing is written. A *storage* failure is
     *  handled differently, by [clear]: if the first `put` succeeded and the
     *  second failed, the old key would survive paired with the new URL, and that
     *  mismatched pair would load as a valid-looking destination that then fails
     *  every flush. Wiping is the only outcome that cannot silently lie about
     *  being configured, so a storage failure clears both halves rather than
     *  leaving that stale pairing behind. */
    fun save(baseUrl: String, publishableKey: String): UrlVerdict {
        val key = publishableKey.trim()
        if (key.isBlank()) return UrlVerdict.Invalid("Enter the publishable key.")
        val verdict = TelemetryUrl.check(baseUrl)
        if (verdict !is UrlVerdict.Valid) return verdict

        // A store that cannot write must not be reported as configured. Both halves
        // are attempted, then checked: a half-written destination would read as
        // configured and fail every flush afterwards.
        val stored = store.put(KEY_URL, verdict.normalised) && store.put(KEY_PUBLISHABLE, key)
        if (!stored) {
            clear()
            return UrlVerdict.Invalid("This device could not store the credentials securely.")
        }
        return verdict
    }

    fun clear() {
        store.remove(KEY_URL)
        store.remove(KEY_PUBLISHABLE)
    }

    fun isConfigured(): Boolean = load() != null

    private companion object {
        const val KEY_URL = "telemetry_base_url"

        // Renamed from "telemetry_anon_key" when the anon key was replaced by a
        // publishable key. No migration reads the old name: the settings screen
        // that would call save() does not exist yet, so nothing has ever stored
        // credentials under either name on a real device.
        const val KEY_PUBLISHABLE = "telemetry_publishable_key"
    }
}
