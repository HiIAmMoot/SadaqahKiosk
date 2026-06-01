package com.sadaqah.kiosk.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
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
        val healthyStart = lastStart >= installAttemptedAt
        Log.d("UpdateWatchdog",
            "installedAt=$installAttemptedAt lastStart=$lastStart healthyStart=$healthyStart")

        // Clear marker so we don't loop on the next boot.
        prefs(context).edit { remove(KEY_INSTALL_ATTEMPTED_AT) }

        if (healthyStart) {
            Log.d("UpdateWatchdog", "Install succeeded — app reported a fresh startup")
            return
        }

        val backupApk = BackupStore(context).backupApkFile()
        if (!backupApk.exists()) {
            Log.w("UpdateWatchdog", "No backup APK to roll back to — giving up")
            return
        }
        Log.w("UpdateWatchdog", "Rolling back to ${backupApk.absolutePath}")

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

    companion object {
        const val ACTION = "com.sadaqah.kiosk.UPDATE_WATCHDOG"
        const val PREFS = "update_state"
        const val KEY_LAST_STARTUP_MS = "update_last_startup_ms"
        const val KEY_INSTALL_ATTEMPTED_AT = "update_install_attempted_at"

        fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Called by MainActivity.onCreate on every startup. */
        fun recordHeartbeat(ctx: Context) {
            prefs(ctx).edit { putLong(KEY_LAST_STARTUP_MS, System.currentTimeMillis()) }
        }

        /** Schedules the watchdog to fire [delayMs] from now. */
        fun arm(ctx: Context, delayMs: Long = 60_000L) {
            prefs(ctx).edit { putLong(KEY_INSTALL_ATTEMPTED_AT, System.currentTimeMillis()) }
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, UpdateWatchdogReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 0xAD, intent, flags)
            val triggerAt = System.currentTimeMillis() + delayMs
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d("UpdateWatchdog", "Armed for +${delayMs}ms")
        }

        fun disarm(ctx: Context) {
            prefs(ctx).edit { remove(KEY_INSTALL_ATTEMPTED_AT) }
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, UpdateWatchdogReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_NO_CREATE or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            PendingIntent.getBroadcast(ctx, 0xAD, intent, flags)?.let { am.cancel(it) }
        }
    }
}
