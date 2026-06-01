package com.sadaqah.kiosk.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads release APKs to the app's private files dir. Avoids DownloadManager
 * so we don't need WRITE_EXTERNAL_STORAGE or an externally-visible notification.
 */
class ApkDownloader(private val context: Context) {

    private val updateDir: File by lazy {
        File(context.filesDir, "updates").apply { mkdirs() }
    }

    fun pendingApkFor(release: ReleaseInfo): File =
        File(updateDir, "SadaqahKiosk_v${release.version}.apk")

    fun isAlreadyDownloaded(release: ReleaseInfo): Boolean {
        val f = pendingApkFor(release)
        return f.exists() && (release.apkSizeBytes == 0L || f.length() == release.apkSizeBytes)
    }

    /**
     * Downloads the APK. Reports progress through [onProgress]. Returns the
     * downloaded file path on success, or null on failure. Idempotent — if a
     * complete file already exists for [release], returns immediately.
     */
    suspend fun download(release: ReleaseInfo, onProgress: (Int) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        if (isAlreadyDownloaded(release)) {
            onProgress(100)
            return@withContext pendingApkFor(release)
        }
        cleanupExcept(release.version) // drop older pending APKs before fetching a new one

        val target = pendingApkFor(release)
        val tmp = File(updateDir, target.name + ".part")
        tmp.delete()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SadaqahKiosk-Updater")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e("ApkDownloader", "HTTP $code for ${release.apkUrl}")
                return@withContext null
            }
            val total = if (release.apkSizeBytes > 0) release.apkSizeBytes else conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { onProgress(pct); lastPct = pct }
                        }
                        // brief yield so UI gets ticks
                        if (written and 0xFFFFF == 0L) delay(1)
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                Log.e("ApkDownloader", "Rename failed: $tmp → $target")
                tmp.delete()
                return@withContext null
            }
            onProgress(100)
            target
        } catch (e: Exception) {
            Log.e("ApkDownloader", "Download failed: ${e.message}")
            tmp.delete()
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Remove any pending/stale APKs that aren't [keepVersion]. */
    fun cleanupExcept(keepVersion: SemVer) {
        updateDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".apk") || f.name.endsWith(".apk.part")) {
                val keepName = "SadaqahKiosk_v$keepVersion.apk"
                if (f.name != keepName) {
                    if (f.delete()) Log.d("ApkDownloader", "Removed stale ${f.name}")
                }
            }
        }
    }

    /** Drop all downloaded APKs. Called after a successful install completes. */
    fun cleanupAll() {
        updateDir.listFiles()?.forEach { it.delete() }
    }
}
