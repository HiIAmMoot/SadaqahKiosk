package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Test

class PendingDiagnosticsTest {

    private fun entry(
        id: String,
        kind: DiagnosticKind = DiagnosticKind.RESTART_TRIGGERED,
        atMs: Long = 1_700_000_000_000L,
        detail: String? = null
    ) = PendingDiagnostic(id, kind, atMs, detail)

    @Test
    fun anEntryRoundTrips() {
        val entries = listOf(entry("a", detail = """{"reason":"card_reader_failures"}"""))
        val decoded = PendingDiagnostics.decode(PendingDiagnostics.encode(entries))
        assertEquals(1, decoded.size)
        assertEquals("a", decoded[0].id)
        assertEquals(DiagnosticKind.RESTART_TRIGGERED, decoded[0].kind)
        assertEquals(1_700_000_000_000L, decoded[0].occurredAtMs)
        assertEquals("""{"reason":"card_reader_failures"}""", decoded[0].detailJson)
    }

    @Test
    fun anEntryWithNoDetailRoundTrips() {
        val decoded = PendingDiagnostics.decode(
            PendingDiagnostics.encode(listOf(entry("a", detail = null)))
        )
        assertNull(decoded[0].detailJson)
    }

    /**
     * The kind is stored by its wire string, never its ordinal: an ordinal
     * would silently re-map every stored entry the day a kind is inserted into
     * the middle of the enum.
     */
    @Test
    fun theKindIsStoredByWireStringNotOrdinal() {
        val encoded = PendingDiagnostics.encode(
            listOf(entry("a", kind = DiagnosticKind.CARD_READER_PAGE_TIMEOUT))
        )
        assertTrue(encoded.contains(DiagnosticKind.CARD_READER_PAGE_TIMEOUT.wire))
    }

    @Test
    fun addAppendsToAnExistingList() {
        val first = PendingDiagnostics.add(null, listOf(entry("a")))
        val both = PendingDiagnostics.add(first, listOf(entry("b")))
        assertEquals(listOf("a", "b"), PendingDiagnostics.decode(both).map { it.id })
    }

    /** A restart writes both its markers in one commit. */
    @Test
    fun addTakesSeveralEntriesAtOnce() {
        val raw = PendingDiagnostics.add(null, listOf(entry("a"), entry("b")))
        assertEquals(listOf("a", "b"), PendingDiagnostics.decode(raw).map { it.id })
    }

    @Test
    fun addKeepsOnlyTheNewestEntriesWhenTheCapIsExceeded() {
        var raw: String? = null
        repeat(PendingDiagnostics.MAX_ENTRIES + 3) { i ->
            raw = PendingDiagnostics.add(raw, listOf(entry("id$i")))
        }
        val ids = PendingDiagnostics.decode(raw).map { it.id }
        assertEquals(PendingDiagnostics.MAX_ENTRIES, ids.size)
        assertEquals("id3", ids.first())
        assertEquals("id${PendingDiagnostics.MAX_ENTRIES + 2}", ids.last())
    }

    @Test
    fun removeDropsOnlyTheNamedIds() {
        val raw = PendingDiagnostics.add(null, listOf(entry("a"), entry("b"), entry("c")))
        val left = PendingDiagnostics.remove(raw, setOf("a", "c"))
        assertEquals(listOf("b"), PendingDiagnostics.decode(left).map { it.id })
    }

    /**
     * The drain race. An entry written between the drain's read and its clear
     * must survive — which is the whole reason remove takes ids rather than
     * clearing the key.
     */
    @Test
    fun removePreservesAnEntryAddedSinceTheDrainRead() {
        val atDrainRead = PendingDiagnostics.add(null, listOf(entry("a")))
        val afterRestart = PendingDiagnostics.add(atDrainRead, listOf(entry("b")))
        val left = PendingDiagnostics.remove(afterRestart, setOf("a"))
        assertEquals(listOf("b"), PendingDiagnostics.decode(left).map { it.id })
    }

    // ── decode is total: it runs during onCreate ─────────────────────────────

    @Test
    fun decodeOfNullIsEmpty() = assertTrue(PendingDiagnostics.decode(null).isEmpty())

    @Test
    fun decodeOfBlankIsEmpty() = assertTrue(PendingDiagnostics.decode("  ").isEmpty())

    @Test
    fun decodeOfMalformedJsonIsEmpty() =
        assertTrue(PendingDiagnostics.decode("{not json").isEmpty())

    @Test
    fun decodeOfATruncatedListIsEmpty() {
        val raw = PendingDiagnostics.encode(listOf(entry("a")))
        assertTrue(PendingDiagnostics.decode(raw.substring(0, raw.length / 2)).isEmpty())
    }

    /** One bad entry must not cost the others — a corrupt marker cannot be
     *  allowed to stop a kiosk reporting anything at all. */
    @Test
    fun decodeKeepsTheEntriesThatParseAndDropsTheRest() {
        val good = PendingDiagnostics.encode(listOf(entry("a"), entry("b")))
        val corrupted = good.replace(DiagnosticKind.RESTART_TRIGGERED.wire, "not_a_kind", ignoreCase = false)
        // Both entries used the same kind, so corrupting the wire string drops
        // both; re-encode one good entry alongside to prove partial survival.
        val mixed = PendingDiagnostics.add(corrupted, listOf(entry("c")))
        assertEquals(listOf("c"), PendingDiagnostics.decode(mixed).map { it.id })
    }

    @Test
    fun decodeDropsAnEntryWithAnUnknownKind() {
        val raw = PendingDiagnostics.encode(listOf(entry("a")))
            .replace(DiagnosticKind.RESTART_TRIGGERED.wire, "invented_kind")
        assertTrue(PendingDiagnostics.decode(raw).isEmpty())
    }
}
