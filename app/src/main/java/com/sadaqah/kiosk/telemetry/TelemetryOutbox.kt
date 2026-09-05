package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One line of the outbox: an opaque payload plus what the queue needs to manage it. */
data class QueuedEvent(
    val id: String,
    val table: String,
    val payload: String,
    val queuedAtMs: Long
)

/**
 * Append-only JSONL queue of unsent telemetry.
 *
 * Deliberately knows nothing about event types — it moves `(id, table, payload)`
 * triples — so a new kind of event never touches queue code, and the queue can be
 * tested without constructing one.
 *
 * Storage is separate from [com.sadaqah.kiosk.donations.DonationHistory] on
 * purpose: that file is user-facing and has a Clear button, and an operator
 * clearing their history must not silently destroy unsent telemetry.
 */
class TelemetryOutbox(
    private val file: File,
    private val maxEvents: Int = 5000,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun append(id: String, table: String, payload: String) {
        val now = clock()
        file.parentFile?.mkdirs()
        // A real append never puts existing bytes at risk, so an unclean death
        // costs at most the line being written — which parseLine already skips.
        // Rewriting the file instead would place the entire queue inside a
        // truncate window on every call.
        file.appendText(serialise(QueuedEvent(id, table, payload, now)))
        compactIfNeeded(now)
    }

    @Synchronized
    fun peek(limit: Int = DEFAULT_BATCH): List<QueuedEvent> = readAll().take(limit)

    @Synchronized
    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        writeAll(readAll().filterNot { it.id in ids })
    }

    @Synchronized
    fun size(): Int = readAll().size

    // ── Internals ────────────────────────────────────────────────────────────

    /** Rewrites only when a cap actually binds, so the common path stays a
     *  single-line append. */
    private fun compactIfNeeded(now: Long) {
        val all = readAll()
        val kept = applyCaps(all, now)
        if (kept.size != all.size) writeAll(kept)
    }

    /** Caps are enforced on append so an offline kiosk sheds its oldest
     *  telemetry rather than filling the device's storage.
     *
     *  Events stamped before [PLAUSIBLE_EPOCH_FLOOR_MS] are never aged out: a
     *  kiosk with a dead RTC stamps them near 1970, and once the clock is
     *  corrected the very next append would otherwise silently delete every one
     *  of them. The count cap still bounds them. */
    private fun applyCaps(events: List<QueuedEvent>, now: Long): List<QueuedEvent> {
        val cutoff = now - maxAgeMs
        return events
            .filter { it.queuedAtMs < PLAUSIBLE_EPOCH_FLOOR_MS || it.queuedAtMs >= cutoff }
            .takeLast(maxEvents)
    }

    private fun readAll(): List<QueuedEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { parseLine(it) }
    }

    /** Writes to a sibling temp file and moves it into place, so a crash during
     *  compaction leaves the previous queue intact rather than a partial one. */
    private fun writeAll(events: List<QueuedEvent>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(events.joinToString("") { serialise(it) })
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun serialise(e: QueuedEvent): String = JsonObject().apply {
        addProperty("id", e.id)
        addProperty("table", e.table)
        addProperty("queuedAt", e.queuedAtMs)
        addProperty("payload", e.payload)
    }.toString() + "\n"

    /** A single unreadable line must not cost the whole queue, so anything that
     *  fails to parse is skipped rather than throwing. */
    private fun parseLine(line: String): QueuedEvent? {
        if (line.isBlank()) return null
        return try {
            val o = JsonParser.parseString(line) as? JsonObject ?: return null
            QueuedEvent(
                id = o.get("id")?.asString ?: return null,
                table = o.get("table")?.asString ?: return null,
                payload = o.get("payload")?.asString ?: return null,
                queuedAtMs = o.get("queuedAt")?.asLong ?: return null
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_BATCH = 100

        /** 2020-01-01T00:00:00Z. A timestamp below this came from an unset clock
         *  and its age cannot be trusted. */
        private const val PLAUSIBLE_EPOCH_FLOOR_MS = 1_577_836_800_000L
    }
}
