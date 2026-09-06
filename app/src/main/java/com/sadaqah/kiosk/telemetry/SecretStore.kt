package com.sadaqah.kiosk.telemetry

/**
 * Opaque string storage that is expected to be encrypted at rest.
 *
 * It exists so credential *policy* — what a valid destination is, what counts as
 * configured, what clearing must remove — can be tested without a device. The
 * Keystore is an implementation detail behind this line, and the only class that
 * touches it stays branch-free.
 *
 * **No method throws.** A device whose Keystore is unusable — invalidated, full,
 * or in a bad secure-hardware state — reads back as though nothing was stored,
 * which the layer above reports as "not configured". A kiosk that asks to be
 * reconfigured is recoverable; one that crashes mid-donation is not.
 *
 * [put] returns whether the value was actually stored, because the alternative is
 * telling an operator their credentials were saved when they were not. That
 * operator is standing at the kiosk and can retry; discovering it three weeks
 * later from a kiosk that never reported is the failure worth avoiding.
 */
interface SecretStore {
    /** @return true if the value is now readable by [get]. */
    fun put(key: String, value: String): Boolean
    fun get(key: String): String?
    fun remove(key: String)
}

/** The in-memory implementation, used by tests. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun put(key: String, value: String): Boolean { values[key] = value; return true }
    override fun get(key: String): String? = values[key]
    override fun remove(key: String) { values.remove(key) }
}

/**
 * A store whose writes always fail, for exercising the "storage is unavailable"
 * path that [InMemorySecretStore] cannot reach.
 *
 * The state it simulates is the real adapter's most important one and the least
 * reachable: a Keystore that accepts nothing, so every read comes back empty and
 * the kiosk must report itself unconfigured rather than pretend otherwise.
 */
class UnwritableSecretStore : SecretStore {
    override fun put(key: String, value: String): Boolean = false
    override fun get(key: String): String? = null
    override fun remove(key: String) = Unit
}
