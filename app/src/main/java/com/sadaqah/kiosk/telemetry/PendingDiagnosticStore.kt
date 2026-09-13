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

    @Synchronized
    fun read(): List<PendingDiagnostic> = PendingDiagnostics.decode(prefs.getString(KEY_PENDING, null))

    @Synchronized
    fun add(entries: List<PendingDiagnostic>) {
        val current = prefs.getString(KEY_PENDING, null)
        // commit(), not apply(): the caller is about to restart the process
        // (RestartManager.hardRestart), the same reasoning 3b's rollback
        // marker already ships with — an asynchronous write has no guarantee
        // of landing before Runtime.exit(0).
        prefs.edit(commit = true) { putString(KEY_PENDING, PendingDiagnostics.add(current, entries)) }
    }

    @Synchronized
    fun removeDrained(ids: Set<String>) {
        // Re-read inside the lock. Applying to the snapshot the drain opened
        // with would discard an entry a restart wrote in between — which is the
        // one thing remove-by-id exists to prevent.
        val current = prefs.getString(KEY_PENDING, null)
        prefs.edit(commit = true) { putString(KEY_PENDING, PendingDiagnostics.remove(current, ids)) }
    }

    companion object {
        const val KEY_PENDING = "pending_diagnostics"
    }
}
