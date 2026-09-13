package com.sadaqah.kiosk.telemetry

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists the bounded list [PendingDiagnostics] encodes, in the same prefs
 * file the update markers live in. Holds no decisions of its own — every one
 * is [PendingDiagnostics]'s, which is unit-tested; this class is not, for the
 * same reason `PrefsStatusStore` is not: `SharedPreferences` is not on the
 * desktop JRE.
 */
class PendingDiagnosticStore(private val prefs: SharedPreferences) {

    fun read(): List<PendingDiagnostic> = synchronized(LOCK) {
        PendingDiagnostics.decode(prefs.getString(KEY_PENDING, null))
    }

    fun add(entries: List<PendingDiagnostic>) = synchronized(LOCK) {
        val current = prefs.getString(KEY_PENDING, null)
        // commit(), not apply(): the caller is about to restart the process
        // (RestartManager.hardRestart), the same reasoning 3b's rollback
        // marker already ships with — an asynchronous write has no guarantee
        // of landing before Runtime.exit(0).
        prefs.edit(commit = true) { putString(KEY_PENDING, PendingDiagnostics.add(current, entries)) }
    }

    fun removeDrained(ids: Set<String>) = synchronized(LOCK) {
        // Re-read inside the lock. Applying to the snapshot the drain opened
        // with would discard an entry a restart wrote in between — which is the
        // one thing remove-by-id exists to prevent.
        val current = prefs.getString(KEY_PENDING, null)
        prefs.edit(commit = true) { putString(KEY_PENDING, PendingDiagnostics.remove(current, ids)) }
    }

    companion object {
        const val KEY_PENDING = "pending_diagnostics"

        // A companion-level lock, not @Synchronized(this): the startup drain
        // and Task 5's restart write each construct their own instance over
        // the same prefs file, and `this` would give each its own monitor —
        // no mutual exclusion at all. One lock shared by every instance is
        // what makes read-modify-write atomic across them. The race this
        // closes is in-process, not cross-process: reportRestart runs on the
        // main thread while drainUpdateDiagnostics runs on Dispatchers.IO,
        // and they overlap whenever a code-1 or code-2 failure lands during
        // the first moments of startup. There is exactly one prefs file in
        // play here, so one lock is enough — no need for TelemetryOutbox's
        // path-keyed map.
        private val LOCK = Any()
    }
}
