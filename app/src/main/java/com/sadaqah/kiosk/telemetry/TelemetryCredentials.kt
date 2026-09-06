package com.sadaqah.kiosk.telemetry

import java.net.URI

/**
 * Where telemetry goes and what authenticates it.
 *
 * Deliberately not a data class: a generated toString would print the anon key,
 * and one `Log.d(TAG, "$config")` is all it takes to put a credential in logcat.
 */
class TelemetryConfig(val baseUrl: String, val anonKey: String) {
    override fun toString(): String = "TelemetryConfig(baseUrl=$baseUrl, anonKey=[redacted])"
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

        // Cleartext would put the anon key on the wire in the clear, and a mosque's
        // wifi is not a controlled network. Checked here, at entry, so the operator
        // learns at the bench rather than from a kiosk that never reported.
        if (!"https".equals(uri.scheme, ignoreCase = true)) {
            return UrlVerdict.Invalid("The URL must start with https://")
        }
        if (uri.host.isNullOrBlank()) return UrlVerdict.Invalid("That URL has no host.")

        return UrlVerdict.Valid(trimmed)
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
        val url = store.get(KEY_URL)?.takeIf { it.isNotBlank() } ?: return null
        val key = store.get(KEY_ANON)?.takeIf { it.isNotBlank() } ?: return null
        return TelemetryConfig(url, key)
    }

    /** Validates before writing anything, so a rejected destination leaves the
     *  previous one intact rather than half-replacing it. */
    fun save(baseUrl: String, anonKey: String): UrlVerdict {
        val key = anonKey.trim()
        if (key.isBlank()) return UrlVerdict.Invalid("Enter the anon key.")
        val verdict = TelemetryUrl.check(baseUrl)
        if (verdict !is UrlVerdict.Valid) return verdict

        // A store that cannot write must not be reported as configured. Both halves
        // are attempted, then checked: a half-written destination would read as
        // configured and fail every flush afterwards.
        val stored = store.put(KEY_URL, verdict.normalised) && store.put(KEY_ANON, key)
        if (!stored) {
            clear()
            return UrlVerdict.Invalid("This device could not store the credentials securely.")
        }
        return verdict
    }

    fun clear() {
        store.remove(KEY_URL)
        store.remove(KEY_ANON)
    }

    fun isConfigured(): Boolean = load() != null

    private companion object {
        const val KEY_URL = "telemetry_base_url"
        const val KEY_ANON = "telemetry_anon_key"
    }
}
