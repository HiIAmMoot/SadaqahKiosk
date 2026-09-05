package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

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
    fun append(id: String, table: String, payload: String) {
        val queued = QueuedEvent(id, table, payload, clock())
        writeAll(applyCaps(readAll() + queued))
    }

    fun peek(limit: Int = DEFAULT_BATCH): List<QueuedEvent> = readAll().take(limit)

    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        writeAll(readAll().filterNot { it.id in ids })
    }

    fun size(): Int = readAll().size

    // ── Internals ────────────────────────────────────────────────────────────

    /** Caps are enforced here, on write, so an offline kiosk sheds its oldest
     *  telemetry rather than filling the device's storage. */
    private fun applyCaps(events: List<QueuedEvent>): List<QueuedEvent> {
        val cutoff = clock() - maxAgeMs
        return events.filter { it.queuedAtMs >= cutoff }.takeLast(maxEvents)
    }

    private fun readAll(): List<QueuedEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { parseLine(it) }
    }

    private fun writeAll(events: List<QueuedEvent>) {
        file.parentFile?.mkdirs()
        file.writeText(events.joinToString("") { serialise(it) })
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
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_BATCH = 100
    }
}
