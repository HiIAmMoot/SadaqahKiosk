# Telemetry Phase 3d-ii — Queue Work Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An append costs the same whether the queue holds ten rows or five thousand, and eviction never destroys a donation while a diagnostic is available to destroy instead.

**Architecture:** `TelemetryOutbox` stops reading the whole queue on every append. A small per-path state object — row count and last-compaction stamp, guarded by the monitor the class already keys on canonical path — lets `append` decide in O(1) whether a compaction is due. Compaction is triggered by two conditions: the queue crossing its cap, and an interval elapsing. Eviction gains a rule that protects donation rows, with protection injected rather than hardcoded.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, Gson, `java.io.File`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-15-telemetry-phase-3d-ii-queue-work.md`

## Global Constraints

- **The outbox is the only copy of a donation.** Nothing is deleted that the uploader did not name, and eviction never destroys a protected row while an unprotected one remains.
- **The append is on the donation path.** It may get cheaper, never more expensive.
- **Caps are enforced during indefinite uptime.** A trigger that only fires at process start is not a bound; neither is one that only fires under load. This kiosk runs for months without a restart.
- **Counters trigger compaction. They never size an eviction.** Every quantity that decides how many rows to delete comes from the list being filtered, never from a cached number.
- **The outbox keeps knowing nothing about event types** (`TelemetryOutbox.kt:20-22`). Protection is configured in, never hardcoded.
- No new third-party dependency, no gradle file touched, JUnit 4 only — no Mockito, MockK, Robolectric.
- No new `Strings` member.
- Comments explain a non-obvious *why*, never a *what*.
- **No AI attribution in any commit message** — no `Co-Authored-By`, no session link, no "Generated with" footer. The repository is public.
- Run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest` before every commit. Zero failures, zero `@Ignore`.

## Read the test file before you write a test in it

`TelemetryOutboxTest.kt` as it stands at HEAD:

| Thing | Reality |
|---|---|
| `outbox(maxEvents, maxAgeMs, compactSlack)` | A factory **function**, `:18-22`. Used as `val box = outbox()`. **Its `compactSlack` default is `1`, not the production `100`.** It takes **no** `compactIntervalMs`, so Task 1 Step 2a must add one before any test can pass it. |
| `file` | A `lateinit var` field, re-created per test by `@Before` `:25-28`. |
| `now` | A `var` field, `1_000_000L`, reset by `@Before`. |
| `TelemetryOutbox.appendDonation(id)` | An extension helper, `:30-31`. |
| Trailing-lambda construction | Four tests pass `onDropped` as a trailing lambda: `:351`, `:366`, `:377`, and `:140`. **`onDropped` must stay the last constructor parameter.** |

There are **26** `TelemetryOutbox(` construction sites across `app/src`. Every parameter this plan adds is defaulted and inserted *before* `onDropped`, so none of them change.

---

## File Structure

**Modified**
- `telemetry/TelemetryOutbox.kt` — the whole of Parts 1 and 2.
- `MainActivity.kt` — passes `protectedTables` at two construction sites; one stale comment.
- Tests: `TelemetryOutboxTest.kt`.

No file is created. No file is deleted.

---

## Task 1: An append that does not read the queue

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryOutbox.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryOutboxTest.kt`

**Interfaces:**
- Produces: `TelemetryOutbox(… , compactIntervalMs: Long = 24h, readLines: (File) -> List<String> = File::readLines, onDropped: (Int) -> Unit = {})`; private `QueueState`, `ensureLoaded`, `compactIfDue`, `compactNow`.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Reconnaissance**

Read `TelemetryOutbox.kt` and `TelemetryOutboxTest.kt` in full. Write down every test that appends past a cap or advances `now`, because the compaction schedule changes underneath all of them. The two named in Step 9 are the ones expected to need edits — if you find a third, port it and say so in your report.

- [ ] **Step 2: Add the read seam and the interval, before `onDropped`**

```kotlin
class TelemetryOutbox(
    private val file: File,
    private val maxEvents: Int = 5000,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val compactSlack: Int = 100,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long the queue may go uncompacted before an append or a peek sweeps
     *  it anyway. Not housekeeping: two callers depend in writing on the age cap
     *  retiring a row nothing else will — see [compactIfDue]. */
    private val compactIntervalMs: Long = 24L * 60 * 60 * 1000,
    /** Seamed for the same reason [clock] is: the tests that matter here assert
     *  how many times the queue is read, and there is no other way to count. */
    private val readLines: (File) -> List<String> = File::readLines,
    private val onDropped: (Int) -> Unit = {}
) {
```

`readAll` uses it:

```kotlin
    private fun readAll(): List<QueuedEvent> {
        if (!file.exists()) return emptyList()
        return readLines(file).mapNotNull { parseLine(it) }
    }
```

- [ ] **Step 2a: Give the test helper the new parameter**

Several tests below pass `compactIntervalMs` to `outbox(...)`, and the helper at `TelemetryOutboxTest.kt:18-22` does not take it. Add it with the production default so no existing call changes:

```kotlin
    private fun outbox(
        maxEvents: Int = 5000,
        maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
        compactSlack: Int = 1,
        compactIntervalMs: Long = 24L * 60 * 60 * 1000
    ) = TelemetryOutbox(file, maxEvents, maxAgeMs, compactSlack, clock = { now },
        compactIntervalMs = compactIntervalMs)
```

- [ ] **Step 3: Write the failing read-count test**

```kotlin
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
```

- [ ] **Step 4: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryOutboxTest*'`
Expected: FAIL — `readLines` is not a constructor parameter yet if Step 2 is incomplete; once it is, the count grows by one per append.

- [ ] **Step 5: Add the shared per-path state**

Beside `locksByPath` in the companion, keyed the same way and guarded by the same monitor:

```kotlin
        private val statesByPath = java.util.concurrent.ConcurrentHashMap<String, QueueState>()

        private fun stateFor(file: File): QueueState = statesByPath.computeIfAbsent(keyFor(file)) { QueueState() }

        /** Visible for tests: the state outlives every instance, so a test that
         *  reuses a path would otherwise inherit the previous test's counts. */
        internal fun resetStateForTests() {
            statesByPath.clear()
        }
```

Extract the canonical-path derivation `lockFor` already performs into a shared `keyFor(file)` so the lock and the state cannot key differently.

```kotlin
    /** Two fields, not three. An earlier design also cached a per-table count
     *  maintained on the donation path; nothing read it, because eviction sizes
     *  itself from the list it is filtering. A counter kept current on the hot
     *  path and read by nothing reads as load-bearing to whoever comes next. */
    private class QueueState {
        var rows: Int = UNKNOWN
        var lastCompactionMs: Long = 0L
    }
```

`UNKNOWN` is `-1` in the companion: a real count is never negative.

- [ ] **Step 6: Make the first load do the compaction**

```kotlin
    /**
     * A pure read. It sets [QueueState.rows] and writes nothing.
     *
     * It deliberately leaves [QueueState.lastCompactionMs] at zero, which makes
     * the first `append` or `peek` of the process overdue and so buys an early
     * sweep — through the normal trigger, on a path that is allowed to write,
     * rather than from inside a read.
     *
     * Making the load itself compact is a deadlock, and it is worth knowing why
     * so nobody re-derives it: this method skips its work once `rows` is set, so
     * a `writeAll` that threw would leave `rows` at the sentinel and every later
     * append, peek, size and remove would retry the same failing write forever.
     * `writeAll` stages a full second copy of the queue before its atomic move,
     * so on a full disk it is guaranteed to fail — and a full disk is the exact
     * condition the cap exists to prevent.
     */
    private fun ensureLoaded(now: Long) {
        if (state.rows != UNKNOWN) return
        state.rows = readAll().size
    }
```

```kotlin
    /** Returns the rows now on disk, so a caller that was about to read can use
     *  this instead of reading again. Null when no compaction was due. */
    private fun compactNow(now: Long): List<QueuedEvent> {
        try {
            val all = readAll()
            // Set from the read, before any write is attempted: a failed reclaim
            // below must not leave the count unknown.
            state.rows = all.size
            val kept = applyCaps(all, now)
            val discarded = all.size - kept.size
            if (discarded == 0) return all   // no write when nothing is droppable
            return try {
                writeAll(kept)
                state.rows = kept.size
                onDropped(discarded)
                kept
            } catch (_: Throwable) {
                // A failed reclaim is swallowed, and that is not laziness.
                //
                // Propagating it on the append path would be actively wrong:
                // the row has already been written, and MainActivity treats an
                // append throw as a lost event and bumps droppedCount — so a
                // failed *reclaim* would be reported to the operator as a
                // destroyed donation that is in fact safely on disk. It would
                // also make peek and size, pure reads today, able to throw into
                // a flush that does not wrap them (TelemetryManager.kt:270, :283).
                //
                // The queue is unharmed: writeAll stages through a temp file and
                // moves atomically, so a failure leaves the previous contents in
                // place — which is what state.rows above already describes.
                all
            }
        } finally {
            // Stamped on every attempt, including a failed one, and deliberately
            // not sharing the counters' condition. A device with a full disk
            // would otherwise stay permanently overdue and retry a full read and
            // a failed write on every single append, for the life of the
            // deployment, on the donation path.
            state.lastCompactionMs = now
        }
    }
```

- [ ] **Step 7: Write the trigger tests — and expect most of them to start green**

**Only `aCompactionThatDropsNothingStillRestartsTheInterval` is genuinely red before the implementation.** The other four assert behaviour today's per-append sweep already delivers, by a different mechanism — they are regression pins for a schedule that is about to change underneath them, not discriminators.

That is fine, and it is stated so nobody spends an hour hunting a RED that was never there. What makes them worth writing is the named mutation on each: every one of those must fail when its clause is removed, and Step 13 runs them.

```kotlin
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
```

- [ ] **Step 8: Add `compactIfDue` and rewire `append`**

```kotlin
    private fun compactIfDue(now: Long): List<QueuedEvent>? {
        // `>=`, not `>`: the behaviour being preserved is the old
        // `discarded >= compactSlack`, which fired *at* the threshold. A `>`
        // here is an off-by-one that changes when reclamation happens.
        val overCap = state.rows >= maxEvents + compactSlack
        val sinceLast = now - state.lastCompactionMs
        val overdue = sinceLast >= compactIntervalMs || now < state.lastCompactionMs
        return if (overCap || overdue) compactNow(now) else null
    }
```

```kotlin
    fun append(id: String, table: String, payload: String): Unit = synchronized(lock) {
        val now = clock()
        ensureLoaded(now)
        file.parentFile?.mkdirs()
        // A real append never puts existing bytes at risk, so an unclean death
        // costs at most the line being written — which parseLine already skips.
        file.appendText(serialise(QueuedEvent(id, table, payload, now)))
        // After the write, so a throwing append cannot inflate the count.
        state.rows += 1
        compactIfDue(now)
    }
```

Delete `compactIfNeeded`. Correct the KDoc that replaces it: the old text claimed reclamation happened "in batches rather than on every append", which described only the write — the read was on every call, and that mismatch is why this survived three phases.

- [ ] **Step 9: Port the two tests the new schedule breaks**

Both still assert the same behaviour; only what provokes the sweep changes.

- `ageCap_dropsEventsPastTheWindow` (`:158`) and `droppingEventsOverTheAgeCapReportsHowManyWereLost` (`:363`) rely on the age sweep running on the append that crosses the threshold. Under the count-plus-interval triggers, a two-row queue far below `maxEvents` never compacts. Give each an explicit `compactIntervalMs` shorter than the clock advance it already performs — both advance `now` by 20 s, so an interval of 10 s provokes the sweep without changing what is asserted.
- `ageCap_keepsEventsInsideTheWindow` (`:168`) needs no change: it asserts nothing is dropped, which holds whether or not a compaction runs.
- `blankLinesAreIgnored` (`:199-205`) **goes vacuous and must be repointed.** Its only assertion is `box.size()`, which after Step 10 returns the cached count instead of reading — so it would stop exercising `parseLine`'s blank-line skip entirely while still passing. Change it to assert through `peek()`, which still reads, so it keeps testing what its name claims.
- `compactionReclaimsInBatchesRatherThanOnEveryAppend` (`:246`) needs no change, and is the regression test for the `>=` boundary — 12 rows under a slack of 10 stay on disk, 15 reclaim. Confirm it still passes rather than editing it.

- [ ] **Step 10: Maintain the counters in `remove` and `clear`**

```kotlin
    fun remove(ids: Set<String>): Unit = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        // No ensureLoaded: this sets `rows` from the list it writes, so a load
        // would be a wasted read — and with the load pure it could not compact
        // here anyway, which is the property `size` and `remove` are meant to have.
        val kept = readAll().filterNot { it.id in ids }
        writeAll(kept)
        // Not optional: remove runs after every successful flush, and
        // TelemetryManager.status() reports size() straight to the operator's
        // screen. Counters that ignored this would drift upward without limit,
        // showing a growing queue depth that does not exist.
        state.rows = kept.size
    }
```

```kotlin
    fun clear(): Unit = synchronized(lock) {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
        state.rows = 0
        state.lastCompactionMs = clock()
    }
```

`size()` calls `ensureLoaded` and then returns `state.rows` rather than reading. It never calls `compactIfDue`, so it cannot write — and because the load is pure, that is now a property that holds rather than one the design quietly violated. `size()` feeds the flush gate (`TelemetryManager.kt:270`), the operator's screen via `status()` (`:79`) and `activate()` (`:180`), so a display read that could rewrite the queue would have the widest blast radius in this phase.

`peek()` is Task 2's concern.

- [ ] **Step 11: Write the counter-maintenance tests**

```kotlin
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

    @Test
    fun twoInstancesOverOneFileShareTheCount() {
        val a = outbox()
        val b = outbox()
        a.appendDonation("one")
        assertEquals("the second instance must see the first's append", 1, b.size())
    }

    @Test
    fun aFailedAppendDoesNotInflateTheCount() {
        // Use the unwritable-path shape below. Append once successfully against
        // a good file first if you need a loaded state, then assert that an
        // append which throws leaves size() unchanged.
    }
```

**The fixture for "append throws" already exists** — `KioskCrashHandlerTest.kt:42-48`, which documents the technique: there is no mocking library and `TelemetryOutbox` is final, so the failure is produced with the real class over an impossible path.

```kotlin
    private fun unwritableOutbox() =
        TelemetryOutbox(File(temp.newFile("blocker"), "outbox.jsonl"), clock = { now })
```

The parent is a regular file, so `mkdirs()` returns false and `appendText` throws `FileNotFoundException`. Copy that shape rather than inventing one, and do not skip this test — the ordering it pins (count bumped only after the write) is a spec requirement.

- [ ] **Step 11a: Record what has no test, and why**

Two behaviours in `compactNow` are specified and are **not** unit-testable here. Say so in your report rather than leaving a silent gap:

- **Counters are not refreshed when `writeAll` throws.** Forcing a mid-compaction write failure needs a seam over `writeAll`, and adding one to pin a two-line ordering is more surface than the ordering is worth. It is instead guaranteed structurally: both `state.rows` assignments sit *after* `writeAll(kept)` inside the same `try`, so a throw cannot reach them.
- **The crash handler's shortened wait.** A consequence of the append no longer reading, not a branch, and `MainActivity` is unreachable from JVM tests.

Do not write a test that asserts nothing in order to close either gap.

- [ ] **Step 12: Reset shared state between tests**

The state map outlives every instance. `@Before` already creates a fresh `file` per test via `TemporaryFolder`, which gives a distinct path, but add `TelemetryOutbox.resetStateForTests()` to `setUp()` so a test that deliberately reuses a path is not silently inheriting counts.

- [ ] **Step 13: Build, run the whole suite, commit**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`

Then run both named mutations — delete the `overdue` term; change `>=` to `>` — confirm the named test fails each time, restore, and record both results.

```bash
git add -A
git commit -m "Stop reading the whole queue on every append"
```

---

## Task 2: Maintenance that runs when nothing is appending

Task 1's interval is reachable only from `append`. That is not a bound: the stall it exists to clear happens on a quiet kiosk, which by definition is not appending.

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryOutbox.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryOutboxTest.kt`

**Interfaces:**
- Consumes: `compactIfDue`, `ensureLoaded`, the read seam (Task 1).

- [ ] **Step 1: Write the failing test**

```kotlin
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
    }
```

- [ ] **Step 2: Run it and watch it fail**

Expected: FAIL — `peek` returns `["stale"]`, because nothing has swept it.

- [ ] **Step 3: Trigger maintenance from `peek`**

```kotlin
    fun peek(limit: Int = DEFAULT_BATCH, excludeTables: Set<String> = emptySet()): List<QueuedEvent> =
        synchronized(lock) {
            val now = clock()
            ensureLoaded(now)
            // The second trigger site, and the one that makes the interval a
            // real bound. append alone only fires under load, and the stall this
            // clears — a poison row at the head that the uploader refuses —
            // happens on a quiet kiosk that is still flushing and still being
            // refused. peek already reads the whole queue, so applying the caps
            // in the same pass costs a filter and a conditional write.
            // Uses the list the compaction already read, when one ran. Without
            // this, peek would read twice on every sweep — and the spec's claim
            // that applying the caps here costs "a filter, not a second read"
            // would be false from the first commit.
            val rows = compactIfDue(now) ?: readAll()
            rows.asSequence()
                .filterNot { it.table in excludeTables }
                .take(limit)
                .toList()
        }
```

`size` and `remove` deliberately do **not** call `compactIfDue`: `remove` runs right after a successful upload when nothing is owed, and `size` is called from the flush gate and from `status()` for the screen, so a display read must not be able to start an interval sweep.

**State that precisely, because the obvious phrasing is false.** `size` does call `ensureLoaded`, and the first load *is* a compaction — so the very first `size()` of a process can rewrite the file. That is intended: the load has to read anyway, and applying the caps in that pass is what makes the process-start sweep free. What `size` must never do is trigger the *interval* check on subsequent calls. A comment claiming "size never rewrites the queue" would be a lie a reader could check in one minute.

- [ ] **Step 4: Run, mutate, restore**

Confirm green, then remove the `compactIfDue` call from `peek`, confirm `peekSweepsAnOverdueQueueWithoutAnyAppend` fails, restore. Record it.

- [ ] **Step 5: Build, run the whole suite, commit**

```bash
git add -A
git commit -m "Sweep the queue on peek, so a quiet kiosk still sheds"
```

---

## Task 3: Eviction that cannot starve a donation

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryOutbox.kt`, `MainActivity.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryOutboxTest.kt`

**Interfaces:**
- Produces: `TelemetryOutbox(… , protectedTables: Set<String> = emptySet(), …)`.
- Consumes: `applyCaps` (Task 1, unchanged shape).

- [ ] **Step 1: Write the failing tests**

```kotlin
    /**
     * The case the phase exists for, and it is not hypothetical. A kiosk takes
     * donations through the day; the card reader dies in the evening; 3c-ii's
     * checkout_no_reader — deliberately unthrottled, because each row is a donor
     * who tried to give and could not — floods the queue. The donations are now
     * the *oldest* rows in the file, and takeLast(maxEvents) evicts one of them
     * while keeping a diagnostic minutes old.
     *
     * Mutation-check: drop the protection and let applyCaps end in
     * takeLast(maxEvents); this must fail.
     */
    @Test
    fun anOldDonationIsNotEvictedWhileANewerDiagnosticCouldBe() {
        val box = TelemetryOutbox(
            file, maxEvents = 3, compactSlack = 1, clock = { now },
            protectedTables = setOf(TelemetryTables.DONATIONS)
        )
        box.appendDonation("donation")
        repeat(3) { box.append("diag$it", TelemetryTables.DIAGNOSTICS, """{"id":"diag$it"}""") }

        val ids = box.peek().map { it.id }
        assertTrue("the donation must survive", ids.contains("donation"))
        assertEquals(3, ids.size)
    }

    @Test
    fun shedBeyondTheUnprotectedCountFallsThroughToProtectedRows() {
        // maxEvents small, queue mostly donations with one diagnostic.
        // Assert: the diagnostic goes first, then the oldest donations, and the
        // queue ends exactly at the cap — not above it.
    }

    @Test
    fun withOnlyProtectedRowsEvictionIsOldestFirst() {
        // All donations, over cap. Assert the oldest go and order is preserved.
    }

    @Test
    fun theDefaultEmptyProtectionBehavesExactlyAsBefore() {
        // No protectedTables argument. Assert oldest-first across mixed tables,
        // identical to takeLast.
    }

    @Test
    fun activationsAreEvictable() {
        // An activation row is regenerated by pressing Test connection; a
        // donation is not regenerable at all. Assert an activation is shed
        // before a donation.
    }
```

Write the prose bodies against the real fixtures. `TelemetryTables` is already imported in this test file — confirm before relying on it.

- [ ] **Step 2: Run and watch the first one fail**

Expected: FAIL — `takeLast` keeps the three diagnostics and drops the donation.

- [ ] **Step 3: Add the parameter, before `onDropped`**

```kotlin
    /** Tables whose rows are evicted last. Injected rather than named here: this
     *  class knows nothing about event types by design, and a table name inside
     *  it would be the first violation. Empty by default, so every existing
     *  caller keeps today's behaviour. */
    private val protectedTables: Set<String> = emptySet(),
```

- [ ] **Step 4: Size the eviction from the list, never from a counter**

```kotlin
    private fun applyCaps(events: List<QueuedEvent>, now: Long): List<QueuedEvent> {
        val cutoff = now - maxAgeMs
        val aged = events.filter {
            it.queuedAtMs < PLAUSIBLE_EPOCH_FLOOR_MS || it.queuedAtMs >= cutoff
        }
        if (aged.size <= maxEvents) return aged

        // Every quantity comes from `aged`, the list actually being capped —
        // never from the cached row count, which describes the file *before*
        // the age filter above. Sizing a deletion from that counter would
        // over-evict, and the rows it would over-evict are donations.
        val shed = aged.size - maxEvents
        val unprotected = aged.count { it.table !in protectedTables }
        val dropUnprotected = minOf(shed, unprotected)
        // Non-zero only when the queue is all-but-entirely protected rows. That
        // is the stated tie-break: with 5000 undelivered donations and nothing
        // else, oldest-first is the only option and the kiosk has a different
        // emergency. Computing both budgets up front is what makes the result
        // exact — dropping unprotected rows and "falling through" when they run
        // out leaves the queue over its cap.
        val dropProtected = shed - dropUnprotected

        var remainingUnprotected = dropUnprotected
        var remainingProtected = dropProtected
        return aged.filter { event ->
            val isProtected = event.table in protectedTables
            when {
                !isProtected && remainingUnprotected > 0 -> { remainingUnprotected--; false }
                isProtected && remainingProtected > 0 -> { remainingProtected--; false }
                else -> true
            }
        }
    }
```

- [ ] **Step 5: Wire `MainActivity`'s two construction sites**

Both pass `protectedTables = setOf(TelemetryTables.DONATIONS)` — the lazy `telemetryOutbox` (`:145-149`) and `crashOutbox` (`:380-383`). They point at the same file and share one lock and one state, so they must agree; a mismatch would mean eviction behaving differently depending on which instance triggered it.

**Only donations are protected.** An activation row regenerates by pressing "Test connection" and a diagnostic describes a condition that will recur; a donation is gone.

- [ ] **Step 6: Correct the four comments this phase makes untrue**

A comment describing removed or changed behaviour is worse than none, and each of these is checkable in under a minute by whoever reads it next.

- **`MainActivity.kt:78-81`**, the KDoc on `TELEMETRY_FLUSH_TICK_MS`: says a flush "can be refused by a backoff of up to 60 minutes and would then never be retried until 02:00." 3d-i made backoff per-table, so a refusal now blocks only the tables actually backed off — a donation queued behind a refused diagnostics table is not waiting on that ceiling at all.
- **`MainActivity.kt:374-378`**, on `CrashContext.onOutboxDropped`: says the callback is "bounded by compactSlack (only past 100 discards)". **This phase makes that false.** `compactNow` invokes `onDropped` whenever anything was discarded, and it is now reachable from an interval sweep and from `peek`, not only from an append that crossed the slack. The surrounding argument still holds — the work is bounded and `KioskCrashHandler`'s own `catch(Throwable)` still means it can delay the chain but never break it — so correct the bound, keep the conclusion.
- **`TelemetryManager.kt:299-305`**, on the post-exclusion `EMPTY_QUEUE` arm: says that arm "is not unit-reachable: reaching it needs the queue to drain between `outbox.size()` above and this `peek`, a genuine race that a single-threaded suite ... cannot manufacture." **This phase makes it reachable.** `peek` now compacts, so a single thread can have `size()` report a non-zero queue and the following `peek` shed every row — no race required. Correct the comment, and say in your report that the arm now *can* be tested, so a later phase can close the gap that comment was written to excuse.
- **`TelemetryUploader.kt:217-227`**, in the fallback-cap KDoc: says "compaction only rewrites the file once at least `compactSlack` rows are droppable in one pass, so on a low-volume kiosk a stall can outlast 30 days by a wide margin." The interval trigger is what bounds that now. Rewrite it to say the stall is bounded by the age cap plus at most one compaction interval. This comment is the reason the interval exists, so leaving it describing the old unbounded behaviour would hide the fix from the next reader of the code that motivated it.

- [ ] **Step 7: Build, run the whole suite, commit**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`

Then run the named mutation — restore `takeLast(maxEvents)` in place of the protection pass — confirm `anOldDonationIsNotEvictedWhileANewerDiagnosticCouldBe` fails, restore, record it.

```bash
git add -A
git commit -m "Never evict a donation while a diagnostic remains"
```

---

## Self-review against the spec

| Spec requirement | Task |
|---|---|
| Read seam over `readAll` | Task 1 Step 2 |
| Shared per-path state, two fields | Task 1 Step 5 |
| First load *is* the compaction | Task 1 Step 6 |
| Counters after the move, stamp after the attempt | Task 1 Step 6 |
| No `writeAll`, no `onDropped(0)` when nothing droppable | Task 1 Steps 6-7 |
| `>=` not `>` on the cap trigger | Task 1 Step 8, with a mutation |
| Clock-jump-backward clause | Task 1 Steps 7-8 |
| `remove` and `clear` maintain counters | Task 1 Step 10 |
| State resettable for tests | Task 1 Step 12 |
| The interval fires without appends | Task 2, with a mutation |
| `size`/`remove` deliberately do not trigger | Task 2 Step 3 |
| Protection injected, not hardcoded | Task 3 Step 3 |
| Eviction sized from the filtered list | Task 3 Step 4 |
| Both budgets computed up front | Task 3 Step 4 |
| Only donations protected; activations evictable | Task 3 Steps 1, 5 |
| Stale `TELEMETRY_FLUSH_TICK_MS` comment | Task 3 Step 6 |
| Device checks 1-5 | Manual; stay in the spec |

**Ordering is load-bearing.** Task 2 depends on Task 1's `compactIfDue`; Task 3 depends on Task 1's `applyCaps` signature. Task 1 alone leaves the interval reachable only from `append`, which is why Task 2 is not optional and not a nice-to-have — it is the half that makes the bound real.

**Known incompleteness.** Several test bodies in Tasks 1 and 3 are described in prose because the fixtures decide the code. Each states its setup and its assertion; an implementer writes them against the real helpers after the mandatory recon step. The one genuinely uncertain body — the failed-append test — carries explicit permission to report and skip rather than invent something fragile.
