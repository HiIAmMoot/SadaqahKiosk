package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One diagnostic a restart could not report before `Runtime.exit(0)` cut it
 * off. [detailJson] is carried opaquely — this layer never parses it — so a
 * caller's detail shape can change without this file changing too.
 */
data class PendingDiagnostic(
    val id: String,
    val kind: DiagnosticKind,
    val occurredAtMs: Long,
    val detailJson: String?
)

/**
 * A bounded, crash-proof list of [PendingDiagnostic] flattened to one prefs
 * string. Pure — no Android, no prefs, no lock — persistence is a later
 * task's job. [decode] runs during onCreate, so it must never throw: a
 * corrupt marker is worth losing, a kiosk that won't start is not.
 */
object PendingDiagnostics {

    /** The store is drained on the very next start, so once it is over cap
     *  the entries worth keeping are the newest, not the oldest. */
    const val MAX_ENTRIES = 8

    fun encode(pending: List<PendingDiagnostic>): String {
        val array = JsonArray()
        pending.forEach { entry ->
            val obj = JsonObject()
            obj.addProperty("id", entry.id)
            // Stored by wire string, never ordinal: an ordinal would silently
            // re-map every stored entry the day a kind is inserted mid-enum.
            obj.addProperty("kind", entry.kind.wire)
            obj.addProperty("at", entry.occurredAtMs)
            if (entry.detailJson != null) obj.addProperty("detail", entry.detailJson)
            array.add(obj)
        }
        return array.toString()
    }

    /** Total by construction: a malformed document fails the whole parse and
     *  yields nothing, while an unknown kind or a missing field fails only
     *  the one entry that has it. */
    fun decode(raw: String?): List<PendingDiagnostic> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            JsonParser.parseString(raw).asJsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        return array.mapNotNull(::decodeEntry)
    }

    private fun decodeEntry(element: JsonElement): PendingDiagnostic? = try {
        val obj = element.asJsonObject
        val wire = obj.get("kind").asString
        // Dropped, not defaulted: a kind this build doesn't recognise is not
        // safely coercible to any other kind.
        val kind = DiagnosticKind.entries.firstOrNull { it.wire == wire } ?: return null
        PendingDiagnostic(
            id = obj.get("id").asString,
            kind = kind,
            occurredAtMs = obj.get("at").asLong,
            detailJson = if (obj.has("detail")) obj.get("detail").asString else null
        )
    } catch (_: Exception) {
        null
    }

    fun add(raw: String?, entries: List<PendingDiagnostic>): String =
        encode((decode(raw) + entries).takeLast(MAX_ENTRIES))

    /** Filters by id and re-encodes, rather than clearing the key outright,
     *  so an entry written between a drain's read and this call survives it.
     *  Re-encoding is also what lets a partially corrupt store heal itself. */
    fun remove(raw: String?, drainedIds: Set<String>): String =
        encode(decode(raw).filterNot { it.id in drainedIds })
}
