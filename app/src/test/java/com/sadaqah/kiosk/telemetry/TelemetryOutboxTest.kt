package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class TelemetryOutboxTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var file: File
    private var now = 1_000_000L

    private fun outbox(
        maxEvents: Int = 5000,
        maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
        compactSlack: Int = 1,
        compactIntervalMs: Long = 24L * 60 * 60 * 1000
    ) = TelemetryOutbox(file, maxEvents, maxAgeMs, compactSlack, clock = { now },
        compactIntervalMs = compactIntervalMs)

    @Before
    fun setUp() {
        file = File(temp.newFolder("telemetry"), "outbox.jsonl")
        now = 1_000_000L
        TelemetryOutbox.resetStateForTests()
    }

    private fun TelemetryOutbox.appendDonation(id: String) =
        append(id, "donation_events", """{"id":"$id","amount_cents":100}""")

    /**
     * There is no mocking library and TelemetryOutbox is final, so "append
     * throws" is produced with the real class over an impossible path: the
     * parent is a regular file, so mkdirs() returns false and appendText
     * throws FileNotFoundException. Same shape as KioskCrashHandlerTest's
     * unwritableOutbox().
     */
    private fun unwritableOutbox() =
        TelemetryOutbox(File(temp.newFile("blocker"), "outbox.jsonl"), clock = { now })

    // ── Append and read ──────────────────────────────────────────────────────

    /**
     * The phase's headline. Every append used to read and JSON-parse the whole
     * queue inside the lock, on the path a donation travels — at the 5000-row
     * cap, 5000 parses to append one row. Asserted on a counting seam rather
     * than on elapsed time, because a timing assertion on a build machine
     * proves nothing.
     */
    @Test
    fun appendingDoesNotReadTheQueueOncePerAppend() {
        var reads = 0
        val box = TelemetryOutbox(
            file, maxEvents = 5000, clock = { now },
            readLines = { reads++; it.readLines() }
        )
        box.appendDonation("first")
        val afterLoad = reads
        repeat(20) { box.appendDonation("e$it") }

        assertEquals("the first append loads once; the rest must not read at all",
            afterLoad, reads)
    }

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
        TelemetryOutbox(nested, 5000, 1000L, clock = { now }).append("x", "t", "{}")
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

    /** `remove()` also calls `writeAll`, exactly like cap-driven compaction does,
     *  but rows it removes are ones the uploader named -- accounted for, not
     *  lost. A callback that fired here too would tell an operator donations
     *  were thrown away when they were in fact successfully uploaded. */
    @Test
    fun removeDoesNotReportAnythingDropped() {
        val dropped = mutableListOf<Int>()
        val box = TelemetryOutbox(file, maxEvents = 10, compactSlack = 0, clock = { now }) { dropped += it }
        box.appendDonation("e1")
        box.appendDonation("e2")
        box.remove(setOf("e1"))
        assertEquals("rows removed by the uploader are not losses", 0, dropped.sum())
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
        now = 1_600_000_000_000L  // Well above PLAUSIBLE_EPOCH_FLOOR_MS
        // A short interval provokes the sweep on the append that crosses the
        // window; the default 24h interval would leave this two-row queue,
        // far below maxEvents, never compacting at all.
        val box = outbox(maxAgeMs = 10_000L, compactIntervalMs = 10_000L)
        box.appendDonation("old")
        now += 20_000L
        box.appendDonation("fresh")
        assertEquals(listOf("fresh"), box.peek().map { it.id })
    }

    @Test
    fun ageCap_keepsEventsInsideTheWindow() {
        now = 1_600_000_000_000L  // Well above PLAUSIBLE_EPOCH_FLOOR_MS
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

    /** The case that matters is a torn line surviving a crash and then being
     *  read — so assert with no intervening append to scrub it away first. */
    @Test
    fun malformedLinesAreSkippedOnRead() {
        val box = outbox()
        box.appendDonation("good1")
        file.appendText("this is not json\n")

        assertEquals(listOf("good1"), box.peek().map { it.id })
        assertEquals(1, box.size())
    }

    @Test
    fun blankLinesAreIgnored() {
        val box = outbox()
        box.appendDonation("e1")
        file.appendText("\n\n")
        assertEquals(listOf("e1"), box.peek().map { it.id })
    }

    @Test
    fun lineMissingRequiredFieldsIsSkippedOnRead() {
        val box = outbox()
        box.appendDonation("good1")
        file.appendText("""{"id":"x"}""" + "\n")

        assertEquals(listOf("good1"), box.peek().map { it.id })
        assertEquals(1, box.size())
    }

    /** And the next append must not be derailed by the bad line already on disk. */
    @Test
    fun appendSucceedsWithAMalformedLineAlreadyPresent() {
        val box = outbox()
        box.appendDonation("good1")
        file.appendText("this is not json\n")
        box.appendDonation("good2")

        assertEquals(listOf("good1", "good2"), box.peek().map { it.id })
    }

    /** A kiosk with a dead RTC stamps events near 1970. Once NTP corrects the
     *  clock, ageing them out would silently delete real donation telemetry. */
    @Test
    fun eventsStampedByAnUnsetClockAreNotAgedOut() {
        now = 5_000L                       // clock never set — near epoch
        val box = outbox(maxAgeMs = 10_000L)
        box.appendDonation("stampedBeforeClockWasSet")

        now = 1_780_000_000_000L           // NTP corrects to 2026
        box.appendDonation("afterCorrection")

        assertEquals(
            listOf("stampedBeforeClockWasSet", "afterCorrection"),
            box.peek().map { it.id })
    }

    /** A saturated queue must not rewrite the whole file on every append. */
    @Test
    fun compactionReclaimsInBatchesRatherThanOnEveryAppend() {
        val box = outbox(maxEvents = 5, compactSlack = 10)
        repeat(12) { box.appendDonation("e$it") }

        // 12 appended, 5 is the cap, but only 7 are over — under the slack of 10,
        // so nothing has been reclaimed yet and every event is still on disk.
        assertEquals(12, file.readLines().count { it.isNotBlank() })

        repeat(3) { box.appendDonation("late$it") }
        // 15 events, 10 over the cap, so one batch reclaim brings it back to 5.
        assertEquals(5, file.readLines().count { it.isNotBlank() })
    }

    @Test
    fun compactionLeavesNoTempFileBehind() {
        val box = outbox(maxEvents = 2)
        repeat(5) { box.appendDonation("e$it") }
        assertEquals(2, box.size())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    // ── Compaction triggers ──────────────────────────────────────────────────

    @Test
    fun aQueueCrossingItsSlackCompacts() {
        val box = outbox(maxEvents = 5, compactSlack = 10)
        repeat(14) { box.appendDonation("e$it") }
        assertEquals("14 rows, 9 over the cap, still under the slack of 10", 14,
            file.readLines().count { it.isNotBlank() })
        box.appendDonation("fifteenth")
        assertEquals("15 rows is exactly cap plus slack, so it reclaims", 5,
            file.readLines().count { it.isNotBlank() })
    }

    /**
     * The poison-row escape, and the reason a count-only trigger is not enough.
     *
     * Two callers depend on the age cap in writing: TelemetryUploader's fallback
     * comment says a bad head row blocks the rows behind it "until the outbox's
     * 30-day age cap retires them", and HttpPoster keeps an unconfirmable 409
     * retryable because "a stall that the 30-day age cap eventually retires is
     * visible and reversible". On a low-volume kiosk that never approaches the
     * count cap, a count-only trigger turns "eventually" into never — and the
     * donations queued behind that row never upload again.
     *
     * Mutation-check: delete the `overdue` term from compactIfDue and this test
     * must fail.
     */
    @Test
    fun anOverdueIntervalCompactsEvenFarBelowTheCap() {
        now = 1_600_000_000_000L
        val box = outbox(maxAgeMs = 10_000L, compactIntervalMs = 60_000L)
        box.appendDonation("stale")
        now += 120_000L
        box.appendDonation("fresh")
        assertEquals(listOf("fresh"), box.peek().map { it.id })
    }

    /**
     * These devices ship with dead RTCs — PLAUSIBLE_EPOCH_FLOOR_MS exists for
     * exactly that. A kiosk that compacts at a wrong-and-high clock value and is
     * then corrected downward would compute a negative interval forever and
     * never sweep again, which is the permanent stall the interval exists to
     * prevent. TelemetryGate uses this same shape for the same reason.
     *
     * **This asserts that a compaction was triggered, not that a row aged out,
     * and the distinction is load-bearing.** A backwards clock jump moves the
     * age cutoff backwards with it, so every existing row looks *newer* than the
     * cutoff and nothing ages out — an assertion on eviction would fail against
     * correct code. Worse, the obvious way to force such an assertion green is
     * to start discarding future-stamped rows, which would delete donations on
     * every forward NTP correction. Assert the trigger, through the read seam.
     *
     * Mutation-check: delete the `now < state.lastCompactionMs` clause and this
     * must fail.
     */
    @Test
    fun aClockCorrectedBackwardsStillTriggersACompaction() {
        now = 1_600_000_000_000L
        var reads = 0
        val box = TelemetryOutbox(
            file, clock = { now }, compactIntervalMs = 60_000L,
            readLines = { reads++; it.readLines() }
        )
        box.appendDonation("first")
        val afterLoad = reads
        now -= 500_000L
        box.appendDonation("second")

        assertTrue("a backwards jump must still count as overdue", reads > afterLoad)
    }

    /**
     * The stamp must be refreshed even when the compaction drops nothing.
     *
     * Skipping it there looks harmless and is not: the queue stays permanently
     * overdue, so every subsequent append performs a full read — restoring, in
     * silence, the exact O(n)-per-append cost this phase exists to remove.
     *
     * Mutation-check: refresh `lastCompactionMs` only on the branch that writes,
     * and this must fail.
     */
    @Test
    fun aCompactionThatDropsNothingStillRestartsTheInterval() {
        var reads = 0
        val box = TelemetryOutbox(
            file, maxEvents = 5000, clock = { now }, compactIntervalMs = 1_000L,
            readLines = { reads++; it.readLines() }
        )
        box.appendDonation("a")
        now += 2_000L
        box.appendDonation("b")          // overdue, compacts, nothing droppable
        val afterSweep = reads
        box.appendDonation("c")          // must no longer be overdue

        assertEquals("a no-drop compaction must still restart the interval",
            afterSweep, reads)
    }

    @Test
    fun aCompactionThatFindsNothingDroppableReportsNoDrops() {
        val dropped = mutableListOf<Int>()
        val box = TelemetryOutbox(file, maxEvents = 5000, compactSlack = 1,
            clock = { now }, compactIntervalMs = 1L) { dropped += it }
        box.appendDonation("a")
        now += 10L
        box.appendDonation("b")
        assertEquals("onDropped(0) must never be called", 0, dropped.size)
    }

    /**
     * A failed reclaim write sets `lastCompactionFailed` so a retry doesn't
     * spin on the same failing write forever, but the flag must not outlive
     * the failure: once the uploader drains the queue below the cap through
     * `remove()`, the next sweep that finds nothing droppable must still
     * clear it. Skipping that clear-path leaves the count trigger permanently
     * suppressed -- the cap then only fires once every `compactIntervalMs`,
     * on a kiosk that runs for months without a restart.
     */
    @Test
    fun aFailedReclaimDoesNotPermanentlySuppressTheCountTrigger() {
        val dropped = mutableListOf<Int>()
        val box = TelemetryOutbox(file, maxEvents = 3, compactSlack = 0, clock = { now },
            compactIntervalMs = 1_000L) { dropped += it }

        box.appendDonation("a")
        box.appendDonation("b")
        box.appendDonation("c")

        // Block the next reclaim's write: writeAll's sibling .tmp path exists
        // as a directory, so tmp.writeText(...) throws before any bytes move
        // -- no permission trickery, and reliable across platforms.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.mkdirs()
        box.appendDonation("d")   // over cap, tries to reclaim, write fails
        tmp.delete()

        // The uploader drains the queue below the cap through the ordinary
        // remove path -- this does not touch the failure flag.
        box.remove(setOf("a", "b"))

        // An interval-driven sweep that finds nothing droppable must still
        // clear the failure flag.
        now += 2_000L
        box.appendDonation("e")

        // Immediately over cap again, same `now`: must reclaim now, not wait
        // out another full interval.
        box.appendDonation("f")

        assertEquals("a stale failure flag must not suppress the count trigger",
            3, file.readLines().count { it.isNotBlank() })
    }

    // ── Counter maintenance ──────────────────────────────────────────────────

    @Test
    fun removeKeepsTheCountAccurateWithoutRereading() {
        var reads = 0
        val box = TelemetryOutbox(file, clock = { now },
            readLines = { reads++; it.readLines() })
        listOf("a", "b", "c").forEach { box.appendDonation(it) }
        box.remove(setOf("b"))
        val before = reads
        assertEquals(2, box.size())
        assertEquals("size() must not read after remove refreshed the count",
            before, reads)
    }

    @Test
    fun clearResetsTheCountWithoutRereading() {
        var reads = 0
        val box = TelemetryOutbox(file, clock = { now },
            readLines = { reads++; it.readLines() })
        box.appendDonation("a")
        box.clear()
        val before = reads
        assertEquals(0, box.size())
        assertEquals(before, reads)
    }

    /**
     * A per-instance count would pass a single-append check trivially --
     * `b.size()` just reads the file itself. The property the design rests on
     * only shows up under interleaving: a stale in-memory count that missed
     * another instance's append would undercount here specifically because
     * `size()` no longer reads.
     */
    @Test
    fun countsStayConsistentAcrossInterleavedAppendsOnTwoInstances() {
        val a = outbox()
        val b = outbox()
        a.appendDonation("one")
        b.appendDonation("two")
        a.appendDonation("three")
        assertEquals("interleaved appends through two instances must share one count",
            3, a.size())
    }

    @Test
    fun aFailedAppendDoesNotInflateTheCount() {
        val box = unwritableOutbox()
        assertThrows(IOException::class.java) { box.append("x", "t", "{}") }
        assertEquals(0, box.size())
    }

    // ── Clear ────────────────────────────────────────────────────────────────

    @Test
    fun clearEmptiesTheQueue() {
        val outbox = outbox()
        outbox.appendDonation("a")
        outbox.appendDonation("b")
        outbox.clear()
        assertEquals(0, outbox.size())
        assertTrue(outbox.peek().isEmpty())
    }

    /**
     * Clearing credentials must not strand identified data on disk: the rows
     * name a kiosk, and an operator who turns telemetry off has withdrawn the basis
     * for holding them. The file itself goes, not just its contents.
     */
    @Test
    fun clearRemovesTheFileRatherThanLeavingAnEmptyOne() {
        val outbox = outbox()
        outbox.appendDonation("a")
        outbox.clear()
        assertFalse(file.exists())
    }

    /** A no-op `clear()`, a `writeText("")` truncate, and a bare `delete()` all
     *  pass "size is 0 on an absent file" trivially — `readAll()` returns empty
     *  for any absent file regardless. What only the real implementation
     *  satisfies is that the outbox is left in the same usable state a genuine
     *  delete-then-recreate leaves it in: the file is gone, and append still
     *  works and brings it back. */
    @Test
    fun clearOnAnAbsentFileIsHarmlessAndLeavesTheOutboxUsable() {
        val box = outbox()
        // Append first. Clearing a file that never existed passes against an empty
        // clear() -- the file is absent either way -- so the queue has to have
        // something to lose before its removal proves anything.
        box.appendDonation("before")
        box.clear()
        assertFalse("clear must delete, not truncate", file.exists())

        // And clearing again, now that it really is absent, must not throw.
        box.clear()

        box.appendDonation("after")
        assertTrue(file.exists())
        assertEquals("the cleared queue keeps none of what preceded it",
            listOf("after"), box.peek().map { it.id })
    }

    /** The temp file `writeAll` stages compaction through must not survive a
     *  clear, or a crash mid-compaction followed by "telemetry just got turned
     *  off" leaves identified rows on disk forever. */
    @Test
    fun clearRemovesAStrandedTempFileToo() {
        val box = outbox()
        box.appendDonation("a")
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText("leftover from a crashed compaction\n")
        assertTrue(tmp.exists())

        box.clear()

        assertFalse(file.exists())
        assertFalse(tmp.exists())
    }

    @Test
    fun theQueueStillWorksAfterBeingCleared() {
        val outbox = outbox()
        outbox.appendDonation("a")
        outbox.clear()
        outbox.appendDonation("b")
        assertEquals(listOf("b"), outbox.peek().map { it.id })
    }

    // ── Dropped reporting ────────────────────────────────────────────────────

    /** Exercises the count cap only — see the name. A fixed clock never ages
     *  anything out, so this says nothing about the age-eviction path; that has
     *  its own test below, [droppingEventsOverTheAgeCapReportsHowManyWereLost]. */
    @Test
    fun droppingEventsOverTheCountCapReportsHowManyWereLost() {
        val dropped = mutableListOf<Int>()
        val box = TelemetryOutbox(file, maxEvents = 2, compactSlack = 0, clock = { now }) { dropped += it }
        box.appendDonation("a")
        box.appendDonation("b")
        box.appendDonation("c")
        assertEquals("the caller must learn a donation was thrown away", 1, dropped.sum())
    }

    /** The operator-facing copy says "too old" first, and on a real kiosk the
     *  age cap is the more likely eviction path — an offline kiosk past
     *  maxAgeMs, not one that queued 5000 rows. That path had no drop-reporting
     *  coverage at all until this test. */
    @Test
    fun droppingEventsOverTheAgeCapReportsHowManyWereLost() {
        now = 1_600_000_000_000L // well above PLAUSIBLE_EPOCH_FLOOR_MS
        val dropped = mutableListOf<Int>()
        // A short interval provokes the sweep on the append that crosses the
        // window; the default 24h interval would leave this queue, far below
        // maxEvents, never compacting at all.
        val box = TelemetryOutbox(file, maxAgeMs = 10_000L, compactSlack = 0, clock = { now },
            compactIntervalMs = 10_000L) { dropped += it }
        box.appendDonation("old1")
        box.appendDonation("old2")
        now += 20_000L // past maxAgeMs: both earlier rows age out
        box.appendDonation("fresh")
        assertEquals("both aged-out rows must be reported, the same way the count cap is", 2, dropped.sum())
    }

    @Test
    fun aQueueUnderItsCapReportsNothingDropped() {
        val dropped = mutableListOf<Int>()
        val box = TelemetryOutbox(file, maxEvents = 10, compactSlack = 0, clock = { now }) { dropped += it }
        box.appendDonation("a")
        assertEquals(0, dropped.sum())
    }

    // ── Per-table exclusion ──────────────────────────────────────────────────

    /**
     * The defect this phase exists to avoid, and the reason exclusion lives
     * inside the read. Filtering a batch already truncated to its first `limit`
     * rows yields nothing at all while the head is excluded — and because peek
     * removes nothing, the head never advances. A bounded delay would have
     * become permanent starvation for every row behind it.
     *
     * Mutation check: move the filter after `take` and this test must fail.
     */
    @Test
    fun excludedRowsAtTheHeadDoNotCrowdOutTheRowsBehindThem() {
        val box = outbox()
        repeat(5) { box.append("d$it", TelemetryTables.DIAGNOSTICS, """{"id":"d$it"}""") }
        box.appendDonation("donation")

        val batch = box.peek(limit = 3, excludeTables = setOf(TelemetryTables.DIAGNOSTICS))

        assertEquals(listOf("donation"), batch.map { it.id })
    }

    @Test
    fun exclusionPreservesQueueOrder() {
        val box = outbox()
        box.appendDonation("a")
        box.append("d", TelemetryTables.DIAGNOSTICS, """{"id":"d"}""")
        box.appendDonation("b")

        val batch = box.peek(excludeTables = setOf(TelemetryTables.DIAGNOSTICS))

        assertEquals(listOf("a", "b"), batch.map { it.id })
    }

    @Test
    fun peekWithNoExclusionsBehavesExactlyAsBefore() {
        val box = outbox()
        box.appendDonation("a")
        box.append("d", TelemetryTables.DIAGNOSTICS, """{"id":"d"}""")
        assertEquals(listOf("a", "d"), box.peek().map { it.id })
    }

    /**
     * A quiet kiosk is the case the interval exists for, and a quiet kiosk does
     * not append. MainActivity retries a flush every TELEMETRY_FLUSH_TICK_MS —
     * 30 minutes — while the screensaver is up, and every one of those peeks.
     * That is the code path a stalled kiosk is already executing.
     *
     * Mutation-check: remove the compactIfDue call from peek and this fails.
     */
    @Test
    fun peekSweepsAnOverdueQueueWithoutAnyAppend() {
        now = 1_600_000_000_000L
        val box = outbox(maxAgeMs = 10_000L, compactIntervalMs = 60_000L)
        box.appendDonation("stale")
        now += 120_000L

        assertEquals("peek alone must retire the aged row",
            emptyList<String>(), box.peek().map { it.id })
        // An in-memory filter would satisfy the assertion above without ever
        // touching disk; the row must actually be gone from the file too.
        assertEquals("the sweep must rewrite the file, not just filter the result",
            0, file.readLines().count { it.isNotBlank() })
    }

    /**
     * The failure mode a naive fix invites: putting `ensureLoaded()` before
     * `compactIfDue` in `peek` makes the very first peek of a process — with
     * rows already on disk from a prior run — read the queue twice, once to
     * load the count and once inside the compaction it then triggers. That is
     * the exact call this task exists to serve, so doubling its cost is the
     * wrong trade.
     */
    @Test
    fun peekReadsTheQueueOnlyOnceWhenSweepingAFreshProcess() {
        outbox().appendDonation("e1")
        TelemetryOutbox.resetStateForTests() // simulate a fresh process re-attaching to the file on disk

        var reads = 0
        val box = TelemetryOutbox(file, clock = { now }, readLines = { reads++; it.readLines() })
        box.peek()

        assertEquals("the boot sweep must read the queue once, not once to load and once to compact",
            1, reads)
    }
}
