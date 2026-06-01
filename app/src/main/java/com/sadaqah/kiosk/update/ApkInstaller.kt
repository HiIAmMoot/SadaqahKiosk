package com.sadaqah.kiosk.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Silently installs an APK via PackageInstaller. Requires device-owner
 * privileges to actually be silent — UpdateManager enforces that upstream.
 */
class ApkInstaller(private val context: Context) {

    sealed class Result {
        data object Success : Result()
        data class Failed(val status: Int, val message: String) : Result()
    }

    suspend fun install(apkFile: File): Result = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = try { installer.createSession(params) } catch (e: Exception) {
            Log.e("ApkInstaller", "createSession failed: ${e.message}")
            return@withContext Result.Failed(-1, "createSession: ${e.message}")
        }

        val deferred = CompletableDeferred<Result>()
        val action = "com.sadaqah.kiosk.INSTALL_COMPLETE.$sessionId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Log.d("ApkInstaller", "session=$sessionId status=$status msg=$msg")
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> deferred.complete(Result.Success)
                    PackageInstaller.STATUS_PENDING_USER_ACTION ->
                        deferred.complete(Result.Failed(status, "user_action_required"))
                    else -> deferred.complete(Result.Failed(status, msg))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(action))
        }

        try {
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val piFlags =
                    PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(action).setPackage(context.packageName),
                    piFlags
                )
                session.commit(pi.intentSender)
                deferred.await()
            }
        } catch (e: Exception) {
            Log.e("ApkInstaller", "session commit failed: ${e.message}")
            Result.Failed(-1, "commit: ${e.message}")
        } finally {
            // Always remove the receiver, including the exception-before-await
            // path that used to leak it.
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }
}
