# Telemetry Phase 3d-ii — The Queue Half

**Goal:** An append costs the same whether the queue holds ten rows or five thousand, and eviction never destroys a donation while a diagnostic is available to destroy instead.

This is the queue half of 3d. The send half shipped as 3d-i.

## What recon changed about this phase's scope

The phase was inherited from 3d-i's "Required of 3d-ii" list, written before anyone had read this code closely. Two of its four items do not survive contact with the source, and a third problem it never named is larger than any of them.

### The finding that reorders the phase: every append is O(queue)

`TelemetryOutbox.append` is `synchronized(lock)` over a body ending in `compactIfNeeded` (`:58`), and `compactIfNeeded` opens with an unconditional `readAll()` (`:109`). `readAll` reads every line and JSON-parses each one (`:132-137`).

So **every append reads and parses the entire queue**, holding the lock, on the path a donation travels. At the 5000-row cap that is 5000 `JsonParser.parseString` calls to append one row.

The method's KDoc (`:102-107`) says it "reclaims in batches rather than on every append" and that rewriting each time "would put a full read-and-write on the donation path". Only the *write* was ever batched. The read is on every call, and the comment reads as though it is not — which is why this survived three phases.

**A kiosk runs unattended for days, months, or years without a restart.** Anything O(n) per append with a growing n compounds for the life of the deployment, and anything whose bound depends on a process restart does not have a bound. That lens governs every decision below.

### Re-scoped: the crash handler's "bounded write"

The inherited requirement was a bounded write with a lock timeout, after three earlier spec reviews found Criticals in that design. **Declined**, but not for the reason first drafted.

The first draft argued that once appends stop reading, "the lock is held for an `appendText` and nothing else." **That is false.** `peek`, `remove` and `size` all still call `readAll` under the same monitor (`:73-79`, `:81-84`, `:86`), and `remove` writes the whole file. A crash handler can still arrive while a flush holds the lock across a full read.

The argument that does hold: **no network call ever happens inside the lock.** `TelemetryManager` peeks, releases, uploads, and only then re-acquires to remove. So the longest any thread can hold the monitor is one local file read-and-write over a queue the count cap bounds at 5000 rows — sub-second on device storage, and bounded by construction rather than by hope. `MainActivity.kt:377` already reasons this way: the drop callback "can delay the chain but never break it."

A `tryLock` timeout would add a mechanism three reviews found dangerous, to shorten a wait that is already bounded. It stays declined. This also disposes of three of the four inherited traps — no byte ceiling to justify, no interrupt-flag restore, no lock-field rename to force `withLock` — because no timed acquisition is introduced.

### Declined: `CrashContext.onOutboxDropped` retaining an Activity

The inherited requirement was to move the status store to process scope so the slot need not hold a bound Activity method. **Declined**, on one argument rather than the two first drafted.

The argument that holds: it does not accumulate. The slot is repointed on every `onCreate` (`MainActivity.kt:379`), so it always holds the newest instance and older ones become collectable; `CrashContext.kt:21-27` documents why it must not be cleared in `onDestroy`. The retention is one instance, fixed, for the life of the process — which is the only question the long-uptime lens asks.

The argument that does **not** hold, and is withdrawn: the first draft claimed the proposed fix would introduce a silent-loss window that the status quo lacks. The status quo has the same window — `MainActivity.kt:382` invokes through `CrashContext.onOutboxDropped?.invoke(it)`, a null-safe call that silently drops the count if the slot is unset. It is closed only by the construction order at `:379-383`. A process-scope holder would need the same care, no more.

**Closed, not deferred**, so it is not rediscovered a fourth time.

---

## Non-negotiables

1. **The outbox is the only copy of a donation.** Nothing is deleted that the uploader did not name, and eviction never destroys a protected row while an unprotected one remains.
2. **The donation flow is not altered.** Appending telemetry *is* on that path, so the append must get cheaper, never more expensive.
3. **Caps are enforced during indefinite uptime.** Any trigger that only fires at process start is not a bound. So is any trigger that only fires under load — see the age-cap dependents below.
4. **No new third-party dependency**, no gradle file touched, JUnit 4 only.
5. **No new `Strings` member**, and therefore no copy in eight languages.
6. **A kiosk with `analyticsEnabled` off writes nothing identified to disk.**
7. **Nothing is held in memory that the queue cap does not bound.** Replacing an O(n) read with an O(n) cache moves the cost, it does not remove it.
8. **The outbox keeps knowing nothing about event types.** `TelemetryOutbox.kt:20-22` states this as a design property. Protection is configured into it, never hardcoded inside it.

---

## What already exists and is NOT rebuilt

- `writeAll`'s temp-file-plus-atomic-move (`:137-144`).
- `parseLine`'s skip-on-failure (`:146-163`).
- The `PLAUSIBLE_EPOCH_FLOOR_MS` guard, which stops a dead-RTC kiosk aging out its own queue once the clock is corrected (`:118-130`).
- The path-keyed lock (`:180-189`). This phase extends its idiom.
- Everything 3d-i shipped on the send path.

---

## Part 1: an append that does not read the queue

### Shared, path-keyed queue state

An in-memory count cannot live on the instance: two `TelemetryOutbox` objects exist over the same file (`MainActivity.kt:146`, `:381`), and per-instance counts would diverge the moment either appended. They already share a monitor keyed on canonical path, so the state is keyed the same way and guarded by that same monitor.

```kotlin
    private class QueueState {
        var rows: Int = UNKNOWN          // sentinel; a real count is never negative
        var lastCompactionMs: Long = 0L
    }
```

Read and written only under the path-keyed monitor, so no field needs volatility of its own.

**Two fields, not three.** An earlier draft also cached `unprotectedRows`, maintained on the donation path. Nothing reads it: once eviction sizes itself from the filtered list (Part 2), every quantity it needs comes from that list. A counter kept current on the hot path and read by nothing is worse than no counter — it looks load-bearing to the next reader and costs a branch per append.

**`lastCompactionMs` has no value in the file**, because the file does not record when it was last compacted. Neither obvious default works: leaving it `0` makes the first append of every process overdue, and stamping it `now` at load makes the age sweep depend on a process restart, which Non-negotiable 3 forbids. See the load rule below.

**Every entry point loads before it uses.** `append`, `size`, `peek` and `remove` each begin with the same guard: if `rows == UNKNOWN`, load. This is not optional and not implicit — the first draft described the load in prose and then omitted it from the `append` code block, which would have left `rows` incrementing from the sentinel, `size()` reporting near-zero while donations sat on disk, and `TelemetryGate` reporting `EMPTY_QUEUE` forever.

**The first load *is* a compaction.** It already reads the whole file, so it applies the caps in the same pass, writes back only if something was droppable, sets `rows` from the surviving list, and stamps `lastCompactionMs = now`. That resolves the `lastCompactionMs` problem exactly: the stamp is real rather than invented, the process start gets a free age sweep, and the first append does **one** read rather than the two a load-then-compact sequence would cost.

That one read per process is unavoidable: the file outlives the process.

### `append`

```kotlin
    fun append(id: String, table: String, payload: String): Unit = synchronized(lock) {
        val now = clock()
        loadStateIfUnknown()
        file.parentFile?.mkdirs()
        file.appendText(serialise(QueuedEvent(id, table, payload, now)))
        state.rows += 1
        compactIfDue(now)
    }
```

If `appendText` throws, the counts are not bumped — the increments follow the write, so a failed append cannot inflate them. The file and the count stay consistent.

### `compactIfDue` — two O(1) triggers, and why the second is not optional

```kotlin
    private fun compactIfDue(now: Long) {
        val overCap = state.rows >= maxEvents + compactSlack
        val sinceLast = now - state.lastCompactionMs
        val overdue = sinceLast >= compactIntervalMs || now < state.lastCompactionMs
        if (overCap || overdue) compactNow(now)
    }
```

**`>=`, not `>`.** The behaviour being preserved is `discarded >= compactSlack` (`TelemetryOutbox.kt:112`), which fires when the queue reaches `maxEvents + compactSlack`, not one row past it. A `>` here is an off-by-one that changes when compaction happens and breaks `countCap_dropsOldestFirst` (`TelemetryOutboxTest.kt:150`) and `compactionReclaimsInBatches` (`:246`).

**`now < state.lastCompactionMs` is the clock-correction clause**, and it is not defensive padding. This queue already carries `PLAUSIBLE_EPOCH_FLOOR_MS` precisely because these devices ship with dead RTCs that read near 1970 until corrected. A kiosk that compacts at a wrong-and-high clock value, then has its clock corrected downward, would otherwise compute a negative `sinceLast` forever and never sweep again — the exact permanent stall the time trigger exists to prevent. `TelemetryGate.kt:47-51` already uses this shape for the same reason.

The count trigger is the obvious one. **The time trigger is load-bearing, and the first draft of this spec omitted it — which would have shipped a data-stranding bug.**

Two places in this codebase depend, in writing, on the age cap retiring a row that nothing else will:

- `TelemetryUploader.kt:217-227` — a genuinely bad row at the head of the queue "block[s] the rows behind them until the outbox's 30-day age cap retires them." The same comment already notes the weakness: "on a low-volume kiosk a stall can outlast 30 days by a wide margin."
- `HttpPoster.kt:48-50` — an unconfirmable 409 is deliberately kept retryable rather than deleted, because "a stall that the 30-day age cap eventually retires is visible and reversible; a wrongful delete is neither."

A count-only trigger turns "outlasts 30 days by a wide margin" into **never**. A low-volume kiosk with one poison row at the head would stop uploading permanently, donations included, with no mechanism left to clear it. The age cap is not a housekeeping nicety; it is the only automatic escape from a stalled head.

### The trigger must fire when nothing is appending

`compactIfDue` is reached from `append`. **That alone is not a bound**, and Non-negotiable 3 says so in this document: a trigger that only fires under load is not a bound. The stall this mechanism exists to clear is precisely the case where the kiosk is quiet — a low-volume site with one poison row at the head, appending rarely or not at all, would never reach the check.

So `peek` calls `compactIfDue` as well. Three reasons it is the right second site:

- **It runs without appends.** `MainActivity` retries a flush every `TELEMETRY_FLUSH_TICK_MS` — 30 minutes (`MainActivity.kt:82`) — while the screensaver is up, which is the state a quiet kiosk sits in. The flush peeks; the queue gets maintained.
- **It already reads the whole queue**, so the marginal cost of applying the caps in the same pass is the filter and a conditional write, not a second read.
- **It is the stalled path itself.** A kiosk with a poison head row is, by definition, still flushing and still being refused. The mechanism fires on exactly the code path the failure runs through.

`size` and `remove` deliberately do **not** trigger it. `remove` runs immediately after a successful upload, when the queue has just shrunk and nothing is owed; `size` is called from the gate on every flush evaluation and from `status()` for the screen, and a display read must not be able to rewrite the queue.

**One consequence to state, because it touches a dying thread.** `crashOutbox.append` (`MainActivity.kt:2344`, and the crash handler itself) can now be the append that finds the interval overdue and performs a compaction. That is strictly better than today, where *every* append pays a full read — but it means the crash path's worst case is still one read-and-write, not zero. The bounded-write decline above rests on that being bounded by a local file operation over a capped queue, which remains true.

`compactIntervalMs` defaults to 24 hours. The age cap's precision is 30 days, so a day's granularity is immaterial, and it bounds the cost of the age sweep to one queue read per day rather than one per append — which is the entire point of this phase. `lastCompactionMs` is part of the shared state, so the interval is per-file, not per-instance.

### `compactNow`

Does the existing `readAll` / `applyCaps` / `writeAll`, then refreshes all three counters **from the list it just computed**, and stamps `lastCompactionMs`.

Three rules the first draft got wrong or left unsaid:

- **Refresh the counters on the no-write path too.** When nothing is droppable, `writeAll` is skipped — but the counters and `lastCompactionMs` must still be refreshed from the read. Otherwise an overdue compaction that finds nothing to drop leaves `lastCompactionMs` stale, re-fires on the very next append, and the O(n) read returns permanently and silently.
- **Refresh the counters only after `Files.move` succeeds — but stamp `lastCompactionMs` on every attempt.** These two must not share a condition. If the move throws, the file still holds its pre-compaction contents, so the counters must describe *that*; but if the stamp is also skipped, a device with a full disk stays permanently overdue and every subsequent append retries a full read *and* a failed write. A disk-full kiosk would have every donation append paying the cost this phase exists to remove, forever. The stamp records that an attempt was made; the counters record what is actually on disk.
- **No `writeAll` and no `onDropped(0)` when nothing is droppable.** Otherwise every overdue compaction rewrites the whole queue for nothing.

### `remove` and `clear` maintain the counts

`remove` currently reads, filters and writes (`:81-84`). It must refresh the counters from the list it writes. **This is not optional:** `remove` runs on every successful flush, so counters that ignore it drift upward without limit, and `TelemetryManager.status()` reports `queued` straight from `outbox.size()` (`TelemetryManager.kt:79`) to the operator's screen. A kiosk uploading normally for months would show a growing queue depth that does not exist.

`clear` resets `rows` and `unprotectedRows` to zero and leaves the state loaded, rather than returning it to the sentinel.

### The counters trigger compaction. They never size an eviction.

This separation is the rule that keeps the cache safe, and stating it is what stops the whole class of bug where a cached number decides how many rows to delete.

`applyCaps` receives a list and computes everything it needs from that list. The counters are consulted only by `compactIfDue`, to decide *whether* to read. A counter that has drifted can therefore cause a redundant compaction or a slightly delayed one — never a wrong deletion.

### What this costs, stated rather than buried

**A queue mutated outside this process would desynchronise the counters.** Nothing does that today; the file is in app-private storage. Every compaction refreshes from a real read, so the state is self-healing at the only points it could matter.

**Two instances constructed with different caps over the same path would share one `lastCompactionMs` and one count.** Today both are constructed with the defaults (`MainActivity.kt:146`, `:381`), so this is theoretical — but the state is keyed by path, so a future caller passing a different `maxEvents` would get the other instance's schedule. Noted rather than guarded, because guarding it costs more than the case is worth.

---

## Part 2: eviction that cannot starve a donation

### The failure case, and why it is not hypothetical

`applyCaps` ends in `takeLast(maxEvents)` (`:129`), which keeps the newest rows regardless of table. That destroys a donation whenever donations are **older** than the diagnostics behind them.

That ordering is exactly what 3c-ii created. A kiosk takes donations through the day; the card reader dies in the evening; `checkout_no_reader` — deliberately unthrottled, because each row is a donor who tried to give and could not — floods the queue. The donations are now the oldest rows in the file, and the next eviction takes one while keeping a diagnostic minutes old.

### The rule

> **No protected row is evicted while any unprotected row remains.**

Stated as an invariant rather than a preference, because every "prefer the larger table" formulation fails the case above — that was the Critical that split 3d in the first place.

### Protection is injected, not hardcoded

`TelemetryOutbox` keeps knowing nothing about event types (`:20-22`), so it gains a constructor parameter:

```kotlin
    private val protectedTables: Set<String> = emptySet(),
```

**Position matters.** Four existing tests pass `onDropped` as a trailing lambda (`TelemetryOutboxTest.kt:140`, `:351`, `:366`, `:377`), so `onDropped` must remain the **last** parameter. Every parameter this phase adds — `protectedTables`, `compactIntervalMs`, `readLines` — is inserted before it, and all three are defaulted, so no existing construction site changes. There are 21 of them.

```kotlin
```

`MainActivity` passes `setOf(TelemetryTables.DONATIONS)` at both construction sites. Defaulting to empty means every existing test keeps today's behaviour unless it opts in.

**`telemetry_activations` is deliberately not protected.** An activation row is regenerated by pressing "Test connection"; a donation is not regenerable at all. Diagnostics and activations are both evictable, and only donations are not.

### Sizing the eviction, from the list and not from a counter

```
aged     = events filtered by the age rule (epoch-floor guard unchanged)
if aged.size <= maxEvents: return aged            // nothing to shed
shed     = aged.size - maxEvents
unprot   = count of aged rows whose table is not protected
dropUnprotected = min(shed, unprot)
dropProtected   = shed - dropUnprotected
```

Every quantity comes from `aged`, the list actually being capped. The first draft computed `shed` as `rows - maxEvents` from the cached counters, which describe the file *before* the age filter — so on any queue with aged-out rows it would have over-evicted, deleting donations nobody named. That is the deletion the outbox exists to prevent.

`dropProtected` is non-zero only when the queue is entirely, or almost entirely, protected rows. That is the stated tie-break: with 5000 undelivered donations and nothing else, oldest-first is the only option, and the kiosk has a different emergency.

Then one forward pass over `aged`, dropping the first `dropUnprotected` unprotected rows and the first `dropProtected` protected rows it meets, keeping everything else in order. Computing the two budgets up front is what makes the result exact — a "drop unprotected, fall through when exhausted" pass leaves the queue over cap when `shed` exceeds `unprot`.

Two passes over `aged` — one to count, one to filter — are fine. The single-pass constraint in the inherited requirements existed because `applyCaps` ran on every append. It no longer does.

---

## Part 3: the stale comment

`MainActivity.kt:78-81`, the KDoc on `TELEMETRY_FLUSH_TICK_MS`, says a flush "can be refused by a backoff of up to 60 minutes and would then never be retried until 02:00." 3d-i made backoff per-table, so a refusal now blocks only the tables actually backed off — a donation behind a refused diagnostics table is no longer waiting on that ceiling at all. One comment, corrected.

The inherited requirement named `MainActivity.kt:2055-2068` instead. That citation is wrong twice over: it is not where the stale text is, and the comment it does point at was already corrected during 3d-i. Verified against the file rather than carried forward.

---

## Testing

### The read-counting seam

The tests below assert how many times the queue is read, and no such seam exists today. `TelemetryOutbox` seams `clock` and `onDropped` the same way, so the read joins them:

```kotlin
    private val readLines: (File) -> List<String> = File::readLines,
```

`readAll` calls it instead of `file.readLines()`. No dependency, no production behaviour change, and a test can count invocations with a plain counter.

**The shared state must be resettable for tests.** It is keyed by canonical path in a companion map that outlives any instance, so a test that seeds a file and constructs a fresh outbox would inherit the previous test's counts. `TemporaryFolder` gives each test a distinct path, which covers most cases, but an explicit internal reset is specified so that a test reusing a path is not silently wrong.

### JVM-tested

**The append cost:**
- Appending N rows to a loaded queue performs **one** read in total, not N. This is the phase's headline; assert on the seam, never on wall-clock time.
- The count survives across two instances over the same file: append through one, and the other's `size()` is right without a second read.
- A failed `appendText` does not bump the counters.

**The triggers:**
- Crossing `maxEvents + compactSlack` compacts.
- **An overdue interval compacts even when the queue is far below the cap.** This is the poison-row escape; without it a low-volume kiosk never sheds an aged row. **Mutation-check**: delete the `overdue` term and confirm this test fails.
- A compaction that finds nothing droppable still refreshes `lastCompactionMs`, so the next append does not immediately re-read. **Mutation-check**: skip the refresh on the no-write path and confirm the read-count test fails.
- Counters are not refreshed when `writeAll` throws.

**Eviction:**
- **A donation older than every diagnostic is not the row evicted.** The case that motivates the phase. **Mutation-check**: restore `takeLast(maxEvents)` and confirm this fails.
- `shed` exceeding the unprotected count evicts the remainder from protected rows and leaves the queue exactly at the cap — not over it.
- With only protected rows queued, eviction is oldest-first.
- With `protectedTables` empty — the default — behaviour is exactly today's.
- Queue order is preserved among survivors, and `onDropped` reports exactly the number removed.
- Activations are evictable.

**`remove` and `clear`:**
- `remove` refreshes the counters, and a following `size()` is right without a read. **Mutation-check**: omit the refresh and confirm `size()` drifts.
- `clear` zeroes the counters without a re-read.

**Existing tests that change, and must be updated rather than deleted:** `ageCap_dropsEventsPastTheWindow` (`TelemetryOutboxTest.kt:158`) and `droppingEventsOverTheAgeCapReportsHowManyWereLost` (`:363`) both rely on the age sweep running on the append that crosses the threshold. Under the new triggers they must drive a compaction explicitly — by crossing the cap, or by advancing the injected clock past the interval. Both still assert the same behaviour; only what provokes it changes.

### Not unit-testable, and labelled as such

The crash handler's shortened wait. It is a consequence of the append no longer reading, not a branch, and `MainActivity` is unreachable from JVM tests.

### Device checks

1. Fill the queue past the cap with old donations under newer diagnostics, then take a donation: the donation is still queued afterwards and the diagnostics count has fallen.
2. Run with analytics pointed at a dead endpoint until the queue saturates: donation throughput does not degrade as the queue grows. The O(n) fix, observable only at scale.
3. Leave a low-volume kiosk running for longer than the compaction interval with a poison row at the head: the row is retired and the queue behind it drains.
4. Crash the app with a saturated queue: the crash row is written and the process still dies promptly.
5. Carried and still unverified: everything on 3d-i's list, plus 3c-ii's restart, synthetic-close and checkout checks.

---

## Limitations, stated rather than buried

**The age sweep runs on a schedule rather than continuously.** A row crosses 30 days and survives until the next compaction — at most `compactIntervalMs` later, one day by default. The uploader's stall comment should be updated to say so, since it currently describes an unbounded wait that this phase bounds.

**The counters are process-local state over a file.** Reconstructed on first use, refreshed by every compaction and every `remove`, and never used to size a deletion. The alternative — reading the file to learn its length — is the cost this phase exists to remove.

**Two instances with different caps over one path share one schedule.** Theoretical today; noted rather than guarded.

---

## Decisions

**Fix the append's cost, not the crash handler's wait.** The wait is bounded by construction because no network call happens inside the lock. A timeout would add a mechanism three reviews found dangerous to shorten a bounded wait.

**Two triggers, not one.** The count trigger bounds storage. The time trigger is the only automatic escape from a stalled queue head, and two files depend on it in writing.

**Counters trigger compaction; they never size an eviction.** A drifted counter can cause a redundant read. It can never cause a wrong deletion.

**Protection is injected.** The outbox's stated design property is that it knows nothing about event types, and a hardcoded table name inside it would be the first violation.

**Only donations are protected.** An activation is regenerated by pressing a button; a donation is gone.

**The retained-Activity item is closed, not deferred** — on the accumulation argument alone. The silent-loss argument was wrong and is withdrawn.

---

## Required of a later phase

- **The truncation-budget gap.** A drifted copy of the suffix kills no test, because the 512-byte reserve absorbs it, so a grep is the only guard. Recorded in `docs/known-debt.md`.
- `SettingsBootstrap`'s call site is still untested, and the 02:00 flush floor is not guaranteed while offline.
