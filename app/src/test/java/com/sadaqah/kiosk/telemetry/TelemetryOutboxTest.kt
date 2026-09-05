package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelemetryOutboxTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var file: File
    private var now = 1_000_000L

    private fun outbox(maxEvents: Int = 5000, maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000) =
        TelemetryOutbox(file, maxEvents, maxAgeMs) { now }

    @Before
    fun setUp() {
        file = File(temp.newFolder("telemetry"), "outbox.jsonl")
        now = 1_000_000L
    }

    private fun TelemetryOutbox.appendDonation(id: String) =
        append(id, "donation_events", """{"id":"$id","amount_cents":100}""")

    // ── Append and read ──────────────────────────────────────────────────────

    @Test
    fun emptyOutbox_readsAsEmpty() {
        assertEquals(0, outbox().size())
        assertTrue(outbox().peek().isEmpty())
    }

    @Test
    fun append_thenPeek_returnsTheEvent() {
        val box = outbox()
        box.appendDonation("e1")
        val got = box.peek()
        assertEquals(1, got.size)
        assertEquals("e1", got[0].id)
        assertEquals("donation_events", got[0].table)
        assertTrue(got[0].payload.contains("amount_cents"))
    }

    @Test
    fun append_createsParentDirectories() {
        val nested = File(temp.newFolder("a"), "b/c/outbox.jsonl")
        TelemetryOutbox(nested, 5000, 1000L) { now }.append("x", "t", "{}")
        assertTrue(nested.exists())
    }

    @Test
    fun append_stampsQueuedAtFromTheClock() {
        now = 12345L
        val box = outbox()
        box.appendDonation("e1")
        assertEquals(12345L, box.peek()[0].queuedAtMs)
    }

    @Test
    fun peek_preservesInsertionOrder() {
        val box = outbox()
        listOf("e1", "e2", "e3").forEach { box.appendDonation(it) }
        assertEquals(listOf("e1", "e2", "e3"), box.peek().map { it.id })
    }

    @Test
    fun peek_honoursTheLimit() {
        val box = outbox()
        repeat(10) { box.appendDonation("e$it") }
        assertEquals(3, box.peek(3).size)
        assertEquals(listOf("e0", "e1", "e2"), box.peek(3).map { it.id })
    }

    @Test
    fun peek_defaultBatchIsOneHundred() {
        val box = outbox()
        repeat(150) { box.appendDonation("e$it") }
        assertEquals(100, box.peek().size)
    }

    /**
     * A stack trace contains real newlines. One JSON object per line only holds
     * if the payload is escaped on the way in, so this uses a genuine newline
     * rather than an already-escaped one.
     */
    @Test
    fun payloadWithNewlinesDoesNotCorruptTheFile() {
        val box = outbox()
        val multiline = "{\"stack_trace\":\"line1\nline2\nline3\"}"
        box.append("e1", "diagnostic_events", multiline)
        box.appendDonation("e2")

        assertEquals(2, box.size())
        assertEquals(2, file.readLines().count { it.isNotBlank() })
        assertEquals(multiline, box.peek()[0].payload)
    }

    // ── Removal ──────────────────────────────────────────────────────────────

    @Test
    fun remove_dropsOnlyTheNamedIds() {
        val box = outbox()
        listOf("e1", "e2", "e3").forEach { box.appendDonation(it) }
        box.remove(setOf("e1", "e3"))
        assertEquals(listOf("e2"), box.peek().map { it.id })
    }

    @Test
    fun remove_unknownIdIsHarmless() {
        val box = outbox()
        box.appendDonation("e1")
        box.remove(setOf("nope"))
        assertEquals(1, box.size())
    }

    @Test
    fun remove_everything_leavesAnEmptyOutbox() {
        val box = outbox()
        listOf("e1", "e2").forEach { box.appendDonation(it) }
        box.remove(setOf("e1", "e2"))
        assertEquals(0, box.size())
        assertTrue(box.peek().isEmpty())
    }

    // ── Caps ─────────────────────────────────────────────────────────────────

    @Test
    fun countCap_dropsOldestFirst() {
        val box = outbox(maxEvents = 3)
        listOf("e1", "e2", "e3", "e4").forEach { box.appendDonation(it) }
        assertEquals(3, box.size())
        assertEquals(listOf("e2", "e3", "e4"), box.peek().map { it.id })
    }

    @Test
    fun ageCap_dropsEventsPastTheWindow() {
        val box = outbox(maxAgeMs = 10_000L)
        box.appendDonation("old")
        now += 20_000L
        box.appendDonation("fresh")
        assertEquals(listOf("fresh"), box.peek().map { it.id })
    }

    @Test
    fun ageCap_keepsEventsInsideTheWindow() {
        val box = outbox(maxAgeMs = 10_000L)
        box.appendDonation("first")
        now += 5_000L
        box.appendDonation("second")
        assertEquals(listOf("first", "second"), box.peek().map { it.id })
    }

    /** An offline kiosk must lose its oldest telemetry, never its disk. */
    @Test
    fun capsApplyOnAppendNotOnRead() {
        val box = outbox(maxEvents = 2)
        repeat(50) { box.appendDonation("e$it") }
        assertEquals(2, file.readLines().count { it.isNotBlank() })
    }

    // ── Corruption tolerance ─────────────────────────────────────────────────

    @Test
    fun malformedLinesAreSkipped() {
        val box = outbox()
        box.appendDonation("good1")
        file.appendText("this is not json\n")
        box.appendDonation("good2")
        assertEquals(listOf("good1", "good2"), box.peek().map { it.id })
    }

    @Test
    fun blankLinesAreIgnored() {
        val box = outbox()
        box.appendDonation("e1")
        file.appendText("\n\n")
        assertEquals(1, box.size())
    }

    @Test
    fun lineMissingRequiredFieldsIsSkipped() {
        val box = outbox()
        file.appendText("""{"id":"x"}""" + "\n")
        box.appendDonation("e1")
        assertEquals(listOf("e1"), box.peek().map { it.id })
    }
}
