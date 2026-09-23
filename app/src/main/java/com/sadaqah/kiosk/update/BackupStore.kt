package com.sadaqah.kiosk.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
            // Size cleared before the move and recorded after it: a crash in
            // either gap leaves no size, which isBackupUsable treats leniently,
            // rather than a stale size that would reject a good backup.
            prefs.edit().remove(KEY_BACKUP_APK_SIZE).commit()
            val size = copyAtomically(src, backupApkFile())
            prefs.edit().putLong(KEY_BACKUP_APK_SIZE, size).commit()
            Log.d("BackupStore", "Backed up current APK ($size bytes)")
        } catch (e: Exception) {
            Log.e("BackupStore", "saveCurrentApk failed: ${e.message}")
        }
    }

    /** Never throws: it runs on the rollback path, where an exception loses the rollback. */
    fun isBackupUsable(): Boolean {
        val recorded = runCatching { prefs.getLong(KEY_BACKUP_APK_SIZE, -1L) }.getOrDefault(-1L).takeIf { it >= 0L }
        return UpdateWatchdogDecision.isBackupUsable(backupApkFile().length(), recorded)
    }

    companion object {
        private const val KEY_BACKUP_APK_SIZE = "backup_apk_size"

        /**
         * Copies through a sibling temp file and renames it over [dest], so a
         * power cut mid-copy leaves the old backup in place, not a truncated one.
         * @return the number of bytes now at [dest].
         */
        fun copyAtomically(src: File, dest: File): Long = copyAtomically({ src.inputStream() }, dest)

        /** The sync before the move matters on f2fs, which does not flush a
         *  file's data before a rename over an existing one. */
        fun copyAtomically(open: () -> InputStream, dest: File): Long {
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            try {
                open().use { input ->
                    FileOutputStream(tmp).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                }
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                tmp.delete()
            }
            return dest.length()
        }
    }
}
