package com.sadaqah.kiosk.telemetry

import android.content.Context

/**
 * Flush bookkeeping that survives a restart.
 *
 * Not a secret — a queue depth and a redacted error string — so it goes in plain
 * SharedPreferences rather than through the encrypted [SecretStore]. `queued` is
 * deliberately not persisted: it is recomputed from the outbox on every read, and
 * a stored copy would be a second source of truth that drifts.
 *
 * The key scheme and every judgement call live in [StatusCodec]; this class is
 * just the SharedPreferences plumbing around it.
 */
class PrefsStatusStore(
    context: Context,
    prefsName: String = "telemetry_status"
) : TelemetryStatusStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Guards [update]'s read-modify-write. SharedPreferences itself is
     *  thread-safe per call, but nothing stops two concurrent [update]s from
     *  interleaving their read and their write without this. */
    private val lock = Any()

    override fun read(): TelemetryStatus = StatusCodec.decode(prefs.all)

    override fun write(status: TelemetryStatus) {
        val editor = prefs.edit()
        for ((key, value) in StatusCodec.encode(status)) {
            when (value) {
                // A recovered table's key must be removed, not left at a stale
                // value, or the next read would resurrect the deadline this
                // write just cleared.
                null -> editor.remove(key)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                // Unreachable: encode produces only the three types above and
                // null. A silent skip would drop a field, so fail loudly — this
                // would be a programming error, not a data error.
                else -> throw IllegalStateException("unsupported status value for $key")
            }
        }
        editor.apply()
    }

    override fun update(transform: (TelemetryStatus) -> TelemetryStatus) {
        synchronized(lock) { write(transform(read())) }
    }
}
