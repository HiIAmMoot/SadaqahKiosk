package com.sadaqah.kiosk.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.sadaqah.kiosk.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watchdog: after an install commits, MainActivity arms an AlarmManager pointing
 * here to fire ~60s later. If by then the new app has NOT written a heartbeat
 * (proving it launched successfully), we attempt to re-install the backup APK.
 *
 * Heartbeat key: "update_last_startup_ms" in shared_prefs/update_state.xml,
 * written by MainActivity.onCreate.
 */
class UpdateWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val installAttemptedAt = prefs(context).getLong(KEY_INSTALL_ATTEMPTED_AT, 0L)
        if (installAttemptedAt == 0L) {
            Log.d("UpdateWatchdog", "No pending install marker — nothing to do")
            return
        }
        val lastStart = prefs(context).getLong(KEY_LAST_STARTUP_MS, 0L)
        val installSeq = UpdateWatchdogDecision.seqFor(
            prefs(context).getString(KEY_INSTALL_SEQ, null), installAttemptedAt
        )
        val heartbeatSeq = UpdateWatchdogDecision.seqFor(
            prefs(context).getString(KEY_LAST_STARTUP_SEQ, null), lastStart
        )
        // Deferred, not computed here: BackupStore's lazy backupDir calls
        // mkdirs(), so evaluating this on a healthy start would create an empty
        // backup directory on a path that has no business touching one.
        val decision = UpdateWatchdogDecision.decide(
            installAttemptedAt = installAttemptedAt,
            lastStartupMs = lastStart,
            installSeq = installSeq,
            heartbeatSeq = heartbeatSeq,
            backupApkUsable = { BackupStore(context).isBackupUsable() }
        )
        Log.d(
            "UpdateWatchdog",
            "installedAt=$installAttemptedAt lastStart=$lastStart installSeq=$installSeq " +
                "heartbeatSeq=$heartbeatSeq decision=$decision"
        )

        // Clear marker so we don't loop on the next boot. commit(): a rollback
        // replaces this process next, and a marker that survives it would be
        // re-armed by BootReceiver on some later, unrelated boot.
        prefs(context).edit().remove(KEY_INSTALL_ATTEMPTED_AT).remove(KEY_INSTALL_SEQ).commit()

        when (decision) {
            is UpdateWatchdogDecision.Decision.NoPendingInstall -> {
                // Unreachable: installAttemptedAt == 0L already returned above.
            }
            is UpdateWatchdogDecision.Decision.HealthyStart -> {
                Log.d("UpdateWatchdog", "Install succeeded — app reported a fresh startup")
            }
            is UpdateWatchdogDecision.Decision.NoBackupToRollBackTo -> {
                Log.w("UpdateWatchdog", "No backup APK to roll back to — giving up")
            }
            is UpdateWatchdogDecision.Decision.RollBack -> {
                // Resolved here rather than above: this is the only branch that
                // needs it, and touching BackupStore creates its directory.
                val backupApk = BackupStore(context).backupApkFile()
                Log.w("UpdateWatchdog", "Rolling back to ${backupApk.absolutePath}")

                // Before the install, so a process that dies mid-install still leaves
                // the fact behind — and commit() rather than apply(), because the very
                // next thing that happens is an installer replacing this process, which
                // an asynchronous write has no guarantee of beating.
                //
                // Wrapped because nothing else in onReceive is: a throw here would
                // abort the receiver before goAsync() and the bad build would never be
                // rolled back. Telemetry must never be the reason a rollback is lost.
                try {
                    prefs(context).edit()
                        .putLong(KEY_ROLLBACK_AT, System.currentTimeMillis())
                        .putString(KEY_ROLLBACK_FROM_VERSION, BuildConfig.VERSION_NAME)
                        .commit()
                } catch (t: Throwable) {
                    Log.e("UpdateWatchdog", "Could not record rollback marker: ${t::class.java.name}")
                }

                // Receivers must return quickly. goAsync() lets us run the install
                // async, calling finish() once we're done so the system can release us.
                val pending = goAsync()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope.launch {
                    try {
                        val result = ApkInstaller(context).install(backupApk)
                        Log.d("UpdateWatchdog", "Rollback install result: $result")
                    } catch (e: Exception) {
                        Log.e("UpdateWatchdog", "Rollback failed: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION = "com.sadaqah.kiosk.UPDATE_WATCHDOG"
        const val PREFS = "update_state"
        const val KEY_LAST_STARTUP_MS = "update_last_startup_ms"
        const val KEY_INSTALL_ATTEMPTED_AT = "update_install_attempted_at"

        /** Set when a rollback is about to be attempted, and read once at the
         *  next startup. Not a queue: these do not survive being read. */
        const val KEY_ROLLBACK_AT = "update_rollback_at_ms"
        const val KEY_ROLLBACK_FROM_VERSION = "update_rollback_from_version"

        /** The versionName this kiosk last started under. Always advanced, even
         *  when analytics is off — see DiagnosticEvents.updateInstalled. */
        const val KEY_REPORTED_VERSION = "update_reported_version"

        /** The last value drawn from the counter both stamps share. */
        private const val KEY_SEQ = "update_seq"
        const val KEY_INSTALL_SEQ = "update_install_seq"
        const val KEY_LAST_STARTUP_SEQ = "update_last_startup_seq"

        /**
         * Draws are read-increment-write on the in-memory prefs, which apply()
         * and commit() both update before returning, so a lock makes them
         * atomic. The heartbeat (main thread) and arm (IO) can run together.
         */
        private val SEQ_LOCK = Any()

        fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Called by MainActivity.onCreate on every startup. */
        fun recordHeartbeat(ctx: Context) {
            synchronized(SEQ_LOCK) {
                val p = prefs(ctx)
                val now = System.currentTimeMillis()
                val seq = p.getLong(KEY_SEQ, 0L) + 1
                p.edit {
                    putLong(KEY_SEQ, seq)
                    putLong(KEY_LAST_STARTUP_MS, now)
                    putString(KEY_LAST_STARTUP_SEQ, UpdateWatchdogDecision.seqTag(now, seq))
                }
            }
        }

        /** Schedules the watchdog to fire [delayMs] from now. */
        fun arm(ctx: Context, delayMs: Long = 60_000L) {
            // commit() rather than apply(), same reason as the rollback marker
            // below: the very next thing that happens after this call is
            // UpdateManager handing the APK to an installer that replaces this
            // process, which an asynchronous write has no guarantee of beating.
            // A lost write here means "no pending install marker — nothing to
            // do", which is a bricked build never rolled back.
            synchronized(SEQ_LOCK) {
                val p = prefs(ctx)
                val now = System.currentTimeMillis()
                val seq = p.getLong(KEY_SEQ, 0L) + 1
                p.edit()
                    .putLong(KEY_SEQ, seq)
                    .putLong(KEY_INSTALL_ATTEMPTED_AT, now)
                    .putString(KEY_INSTALL_SEQ, UpdateWatchdogDecision.seqTag(now, seq))
                    .commit()
            }
            schedule(ctx, delayMs)
        }

        /**
         * Re-schedules a watchdog the reboot swallowed. Longer than [arm]'s delay
         * because a cold boot is slower to reach the first frame than a restart
         * after an install, and a false rollback downgrades a healthy kiosk.
         */
        fun rearmAfterBoot(ctx: Context) {
            val marker = prefs(ctx).getLong(KEY_INSTALL_ATTEMPTED_AT, 0L)
            when (val rearm = UpdateWatchdogDecision.bootRearm(marker, System.currentTimeMillis())) {
                is UpdateWatchdogDecision.BootRearm.None -> return
                is UpdateWatchdogDecision.BootRearm.KeepMarker -> Unit
                is UpdateWatchdogDecision.BootRearm.RestartCheckAt -> {
                    // The pre-install heartbeat was stamped on the correct clock
                    // and would read as newer than the reset marker, passing a
                    // build that never started.
                    val p = prefs(ctx)
                    val retagged = UpdateWatchdogDecision.retag(
                        p.getString(KEY_INSTALL_SEQ, null), oldWallMs = marker, newWallMs = rearm.markerMs
                    )
                    p.edit()
                        .putLong(KEY_INSTALL_ATTEMPTED_AT, rearm.markerMs)
                        .putString(KEY_INSTALL_SEQ, retagged)
                        .remove(KEY_LAST_STARTUP_MS)
                        .commit()
                }
            }
            schedule(ctx, BOOT_REARM_DELAY_MS)
        }

        private const val BOOT_REARM_DELAY_MS = 120_000L

        private fun schedule(ctx: Context, delayMs: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, UpdateWatchdogReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 0xAD, intent, flags)
            // Elapsed time, not wall clock: network time correcting a clock that
            // reset at boot would otherwise fire this early, before the first frame.
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } catch (e: SecurityException) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Log.d("UpdateWatchdog", "Armed for +${delayMs}ms")
        }

        fun disarm(ctx: Context) {
            prefs(ctx).edit { remove(KEY_INSTALL_ATTEMPTED_AT).remove(KEY_INSTALL_SEQ) }
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, UpdateWatchdogReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_NO_CREATE or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            PendingIntent.getBroadcast(ctx, 0xAD, intent, flags)?.let { am.cancel(it) }
        }
    }
}
