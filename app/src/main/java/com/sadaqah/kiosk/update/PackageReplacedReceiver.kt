package com.sadaqah.kiosk.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sadaqah.kiosk.MainActivity

/**
 * Fires after a self-update installs and the system has swapped our APK.
 * Without this, the package replace leaves the app stopped — the user only
 * sees the "your organisation updated this app" notification. We immediately
 * relaunch MainActivity so the kiosk comes back up unattended.
 *
 * MainActivity.onCreate writes the update watchdog heartbeat, which is also
 * what tells the watchdog rollback the install succeeded.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.d("PackageReplaced", "Self-update completed — launching MainActivity")
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.e("PackageReplaced", "startActivity failed: ${e.message}")
        }
    }
}
