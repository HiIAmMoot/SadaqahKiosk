package com.sadaqah.kiosk.donations

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the full donation history to a CSV file in the public Downloads
 * folder so it can be opened in a spreadsheet without root or USB access.
 */
object DonationCsvExporter {

    private const val TAG = "DonationCsvExport"

    /**
     * Returns the public URL / display path on success, null on failure.
     */
    fun export(context: Context, entries: List<DonationHistory.Entry>, currencyCode: String): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "SadaqahKiosk_donations_$stamp.csv"
        val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val csv = buildString {
            append("timestamp,amount,currency\n")
            for (e in entries) {
                append(isoFormat.format(Date(e.timestampMs)))
                append(',')
                append(e.amount.toPlainString())
                append(',')
                append(currencyCode)
                append('\n')
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, filename, csv)
            } else {
                writeLegacy(filename, csv)
            }
        } catch (e: Exception) {
            Log.e(TAG, "export failed: ${e.message}")
            null
        }
    }

    private fun writeViaMediaStore(context: Context, filename: String, csv: String): String? {
        val resolver = context.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SadaqahKiosk")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
        return "Downloads/SadaqahKiosk/$filename"
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(filename: String, csv: String): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SadaqahKiosk")
        if (!dir.exists() && !dir.mkdirs()) return null
        val out = File(dir, filename)
        out.writeText(csv)
        return out.absolutePath
    }
}
