package com.sadaqah.kiosk.donations

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import java.io.File
import java.math.BigDecimal
import java.util.Calendar
import java.util.TimeZone

/**
 * Append-only JSON-lines log of successful donations, sharded by calendar year
 * (local timezone) so kiosks that run for many years without updates don't
 * end up parsing a single massive file every time the History screen opens.
 *
 * Layout (app-private, internal storage):
 *
 *   filesDir/
 *     donations/
 *       donations.2026.jsonl
 *       donations.2027.jsonl
 *       …
 *
 * One entry per line:
 *
 *   { "t": <unix-ms>, "a": "<decimal>" }
 *
 * Currency isn't stored — the kiosk renders amounts under whichever currency
 * is currently configured. We explicitly chose not to do FX conversion.
 */
class DonationHistory(private val filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val dir: File = File(filesDir, "donations").apply { if (!exists()) mkdirs() }

    data class Entry(val timestampMs: Long, val amount: BigDecimal)

    @Synchronized
    fun append(amount: BigDecimal, timestampMs: Long = System.currentTimeMillis()) {
        try {
            val year = yearOf(timestampMs)
            shardFor(year).appendText(serialize(timestampMs, amount))
        } catch (e: Exception) {
            Log.e(TAG, "append failed: ${e.message}")
        }
    }

    /**
     * Reads every entry across every year shard. Returns sorted ascending by
     * timestamp. Bad lines are skipped silently.
     */
    @Synchronized
    fun readAll(): List<Entry> = readShards(allShards())

    /**
     * Same as [readAll] but only touches shards that could contain entries
     * at-or-after [fromMs]. For Today / 7d / 30d / 1y this lets the History
     * screen skip everything older than the current (and previous) year.
     */
    @Synchronized
    fun readSince(fromMs: Long): List<Entry> {
        val cutoffYear = yearOf(fromMs)
        return readShards(allShards().filter { shardYear(it) >= cutoffYear })
    }

    /** Erases the entire history across all year shards. */
    @Synchronized
    fun clearAll() {
        try {
            dir.listFiles { f -> f.name.startsWith("donations.") && f.name.endsWith(".jsonl") }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "clearAll failed: ${e.message}")
        }
    }

    @Synchronized
    fun hasAnyEntries(): Boolean = allShards().any { it.length() > 0 }

    // ── internals ──────────────────────────────────────────────────────────

    private fun shardFor(year: Int) = File(dir, "donations.$year.jsonl")

    private fun allShards(): List<File> =
        dir.listFiles { f -> f.name.matches(SHARD_PATTERN) }?.sortedBy { it.name } ?: emptyList()

    private fun shardYear(file: File): Int =
        SHARD_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun yearOf(timestampMs: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = timestampMs
        return cal.get(Calendar.YEAR)
    }

    private fun serialize(timestampMs: Long, amount: BigDecimal): String =
        """{"t":$timestampMs,"a":"$amount"}""" + "\n"

    private fun readShards(shards: List<File>): List<Entry> {
        if (shards.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        for (shard in shards) {
            try {
                shard.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        parseLine(line)?.let(out::add)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "read shard ${shard.name} failed: ${e.message}")
            }
        }
        out.sortBy { it.timestampMs }
        return out
    }

    private fun parseLine(line: String): Entry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val obj = JsonParser.parseString(trimmed).asJsonObject
            val t = obj.get("t")?.asLong ?: return null
            val a = obj.get("a")?.asString ?: return null
            Entry(t, BigDecimal(a))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "DonationHistory"
        private val SHARD_PATTERN = Regex("""donations\.(\d{4})\.jsonl""")
    }
}
