package com.sadaqah.kiosk.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.io.File

/**
 * Backs up the currently-installed APK so the watchdog can re-install it if a
 * freshly-installed update fails to launch.
 *
 * Only ever keeps ONE backup APK (the previous version), per the spec.
 */
class BackupStore(private val context: Context) {

    private val backupDir: File by lazy {
        File(context.filesDir, "backup").apply { mkdirs() }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("update_backup", Context.MODE_PRIVATE)
    }

    fun backupApkFile(): File = File(backupDir, "previous.apk")

    fun saveCurrentApk() {
        try {
            val srcPath = context.applicationInfo.sourceDir ?: return
            val src = File(srcPath)
            if (!src.exists()) return
            src.copyTo(backupApkFile(), overwrite = true)
            prefs.edit { putLong("backup_apk_size", src.length()) }
            Log.d("BackupStore", "Backed up current APK (${src.length()} bytes)")
        } catch (e: Exception) {
            Log.e("BackupStore", "saveCurrentApk failed: ${e.message}")
        }
    }
}
