package com.sadaqah.kiosk.telemetry

/**
 * Opaque string storage that is expected to be encrypted at rest.
 *
 * It exists so credential *policy* — what a valid destination is, what counts as
 * configured, what clearing must remove — can be tested without a device. The
 * Keystore is an implementation detail behind this line, and the only class that
 * touches it stays branch-free.
 *
 * Implementations must never throw: a device whose Keystore has been invalidated
 * behaves as though nothing was stored, which reads as "not configured" and stops
 * telemetry rather than crashing a kiosk mid-donation.
 */
interface SecretStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
}

/** For tests, and for a device where no encrypted store can be constructed. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun put(key: String, value: String) { values[key] = value }
    override fun get(key: String): String? = values[key]
    override fun remove(key: String) { values.remove(key) }
}
