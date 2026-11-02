package com.sadaqah.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sadaqah.kiosk.update.UpdateWatchdogReceiver

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Before the launch: re-arming must not depend on MainActivity starting.
            try {
                UpdateWatchdogReceiver.rearmAfterBoot(context)
            } catch (t: Throwable) {
                Log.e("BootReceiver", "Could not re-arm update watchdog: ${t::class.java.name}")
            }
            Log.d("BootReceiver", "Device booted — launching kiosk app")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
