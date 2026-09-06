package com.sadaqah.kiosk.telemetry

import android.content.Context

/**
 * Flush bookkeeping that survives a restart.
 *
 * Not a secret — a queue depth and a redacted error string — so it goes in plain
 * SharedPreferences rather than through the encrypted [SecretStore]. `queued` is
 * deliberately not persisted: it is recomputed from the outbox on every read, and
 * a stored copy would be a second source of truth that drifts.
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

    override fun read(): TelemetryStatus = try {
        TelemetryStatus(
            queued = 0,
            lastSuccessMs = prefs.getLong(KEY_LAST_SUCCESS, 0L),
            lastError = prefs.getString(KEY_LAST_ERROR, null),
            lastErrorAtMs = prefs.getLong(KEY_LAST_ERROR_AT, 0L),
            consecutiveFailures = prefs.getInt(KEY_FAILURES, 0),
            backoffUntilMs = prefs.getLong(KEY_BACKOFF_UNTIL, 0L),
            droppedCount = prefs.getInt(KEY_DROPPED, 0)
        )
    } catch (_: ClassCastException) {
        // A key holding a value of the wrong type (a hand-edited or malformed
        // adb-pushed prefs file — this project provisions settings that way)
        // would otherwise throw out of every getLong/getInt above and crash the
        // whole kiosk, not just this screen. Ruling AN already accepted "status
        // displays as zeroes" as this class's failure mode; this is that
        // fallback, not a new decision.
        TelemetryStatus()
    }

    override fun write(status: TelemetryStatus) {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS, status.lastSuccessMs)
            .putString(KEY_LAST_ERROR, status.lastError)
            .putLong(KEY_LAST_ERROR_AT, status.lastErrorAtMs)
            .putInt(KEY_FAILURES, status.consecutiveFailures)
            .putLong(KEY_BACKOFF_UNTIL, status.backoffUntilMs)
            .putInt(KEY_DROPPED, status.droppedCount)
            .apply()
    }

    override fun update(transform: (TelemetryStatus) -> TelemetryStatus) {
        synchronized(lock) { write(transform(read())) }
    }

    private companion object {
        const val KEY_LAST_SUCCESS = "last_success_ms"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_ERROR_AT = "last_error_at_ms"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_BACKOFF_UNTIL = "backoff_until_ms"
        const val KEY_DROPPED = "dropped_count"
    }
}
