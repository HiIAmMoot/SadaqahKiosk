# Telemetry Phase 3d-ii — The Queue Half

**Goal:** An append costs the same whether the queue holds ten rows or five thousand, and eviction never destroys a donation while a diagnostic is available to destroy instead.

This is the queue half of 3d. The send half shipped as 3d-i.

## What recon changed about this phase's scope

The phase was inherited from 3d-i's "Required of 3d-ii" list, written before anyone had read this code closely. Two of its four items do not survive contact with the source, and a third problem it never named is larger than any of them. This section is the argument; the requirements follow.

### The finding that reorders the phase: every append is O(queue)

`TelemetryOutbox.append` is `synchronized(lock)` over a body that ends in `compactIfNeeded`, and `compactIfNeeded` opens with an unconditional `readAll()` (`TelemetryOutbox.kt:97`). `readAll` reads every line of the file and JSON-parses each one (`:118-121`).

So **every append reads and parses the entire queue**, holding the lock, on the path a donation travels. At the 5000-row cap that is 5000 `JsonParser.parseString` calls to append one row.

The method's own KDoc says it "reclaims in batches rather than on every append" and that rewriting each time "would put a full read-and-write on the donation path". Only the *write* was ever batched. The read is on every call, and the comment reads as though it is not — which is why this survived three phases.

This matters more on this app than the complexity would suggest: **a kiosk runs unattended for days, months, or years without a restart.** Anything O(n) per append with a growing n is a cost that compounds for the life of the deployment, and anything whose bound depends on a process restart does not have a bound.

### Re-scoped: the crash handler's "bounded write"

The inherited requirement was a bounded write with a lock timeout, after three earlier spec reviews found Criticals in that design.

The premise does not hold as stated. Both `TelemetryOutbox` instances point at the same file (`MainActivity.kt:146`, `:381`) and `lockFor` keys the monitor on the canonical path (`:182-188`), so they share one monitor. The longest anyone holds it is one compaction over a queue capped at 5000 rows — sub-second, not unbounded. `MainActivity.kt:377` already reasons exactly this way: the drop callback "can delay the chain but never break it."

A `tryLock` timeout would be a mechanism guarding against a wait whose real cause is the O(n) read above. **Fix the cause.** Once an append no longer reads the queue, the lock is held for an `appendText` and nothing else, and the crash handler's worst wait collapses on its own — for every caller, not just the dying one.

This also disposes of three of the four named traps: no byte ceiling to justify, no `tryLock` interrupt-flag restore, no lock-field rename to force `withLock`, because no timed acquisition is introduced. The fourth trap — compaction must not run on the main thread — is absorbed by the design below rather than needing a separate rule.

### Declined: `CrashContext.onOutboxDropped` retaining an Activity

The inherited requirement was to move the status store to process scope so the slot need not hold a bound Activity method.

Under the long-uptime lens that governs this phase, the question is whether anything *accumulates*. This does not. The slot is repointed on every `onCreate` (`MainActivity.kt:379`), so it always holds the newest instance and older ones become collectable; `CrashContext.kt:21-27` documents why it must not be cleared in `onDestroy`. The retention is one instance, fixed, for the life of the process.

The proposed fix introduces a failure mode the status quo does not have: a named process-scope holder has an initialisation window, and a drop landing before it is initialised loses `droppedCount` silently — the operator's only view of telemetry loss. Trading a bounded non-growing reference for a silent-loss window is the wrong direction.

**Closed, not deferred.** It comes off the debt register with this reasoning attached, so it is not rediscovered a fourth time.

---

## Non-negotiables

1. **The outbox is the only copy of a donation.** Nothing is deleted that the uploader did not name, and eviction never destroys a donation while a non-donation row is available to destroy instead.
2. **The donation flow is not altered.** No change to `makePayment` or any path a payment travels. Appending telemetry *is* on that path, so the append must get cheaper, never more expensive.
3. **Caps are enforced during indefinite uptime.** Any trigger that only fires at process start is not a bound. A kiosk that runs for a year must still shed rows.
4. **No new third-party dependency**, no gradle file touched, JUnit 4 only.
5. **No new `Strings` member**, and therefore no copy in eight languages.
6. **A kiosk with `analyticsEnabled` off writes nothing identified to disk.**
7. **Nothing may be read back into memory that the queue cap does not bound.** A fix that replaces an O(n) read with an O(n) cache has moved the cost, not removed it.

---

## What already exists and is NOT rebuilt

- `writeAll`'s temp-file-plus-atomic-move, so a crash mid-compaction leaves the previous queue intact (`:124-130`).
- `parseLine`'s skip-on-failure, so one unreadable line never costs the queue (`:133-149`).
- The `PLAUSIBLE_EPOCH_FLOOR_MS` guard, which stops a dead-RTC kiosk aging out its own queue once the clock is corrected (`:104-113`).
- The path-keyed lock (`:182-188`). This phase extends its idiom; it does not replace it.
- `peek`'s exclusion parameter and everything 3d-i shipped on the send path.

---

## Part 1: an append that does not read the queue

### Shared, path-keyed queue state

An in-memory count cannot live on the instance: two `TelemetryOutbox` objects exist over the same file, and per-instance counts would diverge the moment either appended. They already share a monitor keyed on canonical path, so the count is keyed the same way and guarded by that same monitor.

```kotlin
    private class QueueState {
        var rows: Int = -1          // -1 = not yet known, load on first use
        var nonDonationRows: Int = 0
    }
```

`rows` is the total line count. `nonDonationRows` is how many of them belong to a table other than `TelemetryTables.DONATIONS` — enough to answer "is there something evictable that is not a donation?" without a scan, which is what Part 2 needs.

Both fields are read and written only under the path-keyed monitor, so they need no volatility of their own.

**First use loads once.** The first `append`, `size`, `peek` or `remove` after process start finds `rows == -1` and does a single `readAll` to initialise. Every subsequent append is a counter increment. That one read is unavoidable — the file outlives the process — but it happens once per process rather than once per append.

### `append` becomes O(1) plus the write

```kotlin
    fun append(id: String, table: String, payload: String): Unit = synchronized(lock) {
        val now = clock()
        file.parentFile?.mkdirs()
        file.appendText(serialise(QueuedEvent(id, table, payload, now)))
        state.rows += 1
        if (table != TelemetryTables.DONATIONS) state.nonDonationRows += 1
        compactIfOverCap(now)
    }
```

`compactIfOverCap` compares `state.rows` against `maxEvents + compactSlack` — an integer comparison, no read — and returns immediately in the overwhelmingly common case. When it does fire it performs the existing `readAll` / `applyCaps` / `writeAll` and refreshes the counts from the result.

The existing comment about batching is corrected to describe what the code now actually does.

### What this costs, stated rather than buried

**Age-based eviction becomes opportunistic.** Today the 30-day cap is evaluated on every append, because every append reads the queue. After this change it is evaluated only when a compaction runs, and compactions are triggered by the count. On a low-volume kiosk that never approaches 5000 rows, rows older than 30 days persist.

This is acceptable and the reason is that the age cap was never the binding constraint: the count cap bounds the queue either way, rows are deleted when they upload, and 3d-i's per-table backoff means a table the backend refuses no longer holds the whole queue hostage. What the age cap actually protects against — a permanently misconfigured kiosk hoarding year-old rows — is bounded by the count cap at 5000 rows regardless.

`applyCaps` still applies both rules whenever it runs, so an age sweep happens on every compaction. Nothing weakens the rule; only its schedule changes.

**A queue mutated outside this process would desynchronise the count.** Nothing does that today — the file lives in app-private storage. `clear()` resets the counts, and any compaction refreshes them from a real read, so the state is self-healing at every point it could matter.

---

## Part 2: eviction that cannot starve a donation

### The failure case, and why it is not hypothetical

`applyCaps` ends in `takeLast(maxEvents)` (`:113`), which keeps the newest rows regardless of table. That destroys a donation whenever donations are **older** than the diagnostics behind them.

That ordering is exactly what 3c-ii created. A kiosk takes donations through the day; the card reader then dies in the evening and `checkout_no_reader` — deliberately unthrottled, because each row is a donor who tried to give and could not — floods the queue. The donations are now the oldest rows in the file, and the next append evicts one of them while keeping a diagnostic minutes old.

### The rule

> **No donation is evicted while any non-donation row remains.**

Stated as an invariant rather than a heuristic, because every "prefer the bigger table" formulation fails this case — that was the Critical that split 3d in the first place.

### The tie-break, stated because it is the steady state

When the cap is exceeded and **every** row is a donation, there is no alternative: evict oldest-first, exactly as today. That is the only case where a donation is destroyed, and it means the kiosk has 5000 undelivered donations, which is a different emergency.

When non-donation rows exist, evict oldest-first **among non-donations only**, until either the queue is under the cap or no non-donation rows remain — at which point the first rule takes over.

### Single pass, because this runs inside the lock

`applyCaps` must not sort, group, or make a second pass. It already knows `nonDonationRows`; the number of rows to shed is `rows - maxEvents`. One forward pass dropping the oldest non-donations up to that number, falling through to oldest-first if it runs out, satisfies both rules and preserves queue order for everything kept.

### `compactNow` skips a write it does not need

The inherited note stands: a compaction that finds nothing droppable must not call `writeAll`, or a boot-time sweep rewrites the whole queue for no reason. It must also not call `onDropped` with zero.

---

## Part 3: the stale comment

`MainActivity.kt:2055-2068` describes backoff as a single global deadline. 3d-i made it per-table. One comment, corrected.

---

## Testing

### JVM-tested

**The append cost:**
- Appending to a queue of N rows performs **one** file read across the whole sequence, not N. Assert on a counting seam over the read, not on wall-clock time — a timing test on a build machine proves nothing.
- The count survives across two `TelemetryOutbox` instances over the same file: append through one, and the other's `size()` is correct without a re-read.
- First use on an existing file loads the count once; a second append does not re-read.

**Eviction:**
- **A donation older than every diagnostic is not the row evicted.** The case that motivates the phase. **Mutation-check**: restore `takeLast(maxEvents)` and confirm this test fails.
- With only donations queued, eviction is oldest-first.
- Mixed queue: non-donations are shed oldest-first until the queue is under the cap, and queue order is preserved among survivors.
- Shedding stops at the cap rather than draining every non-donation.
- `onDropped` reports exactly the number removed.

**Compaction:**
- Nothing droppable ⇒ no `writeAll` and no `onDropped`. **Mutation-check**: remove the guard and confirm the test fails.
- An age sweep still happens when a compaction runs for a count reason.
- `clear()` resets the counts, and a following `size()` is 0 without a read.

**Unchanged behaviour that must stay unchanged:** `parseLine` still skips an unreadable line without losing the queue; the epoch-floor guard still protects a dead-RTC kiosk; `writeAll` still stages through the temp file.

### Not unit-testable, and labelled as such

The crash handler's shortened wait. It is a consequence of the append no longer reading, not a branch, and `MainActivity` is unreachable from JVM tests.

### Device checks

1. Fill the queue past the cap with a mix of old donations and newer diagnostics, then take a donation: the donation is still in the queue afterwards, and the diagnostics count has dropped.
2. Leave a kiosk running with analytics pointed at a dead endpoint until the queue saturates: donation throughput does not degrade as the queue grows. This is the O(n) fix, and it is only observable at scale.
3. Crash the app with a saturated queue: the crash row is written and the process still dies promptly.
4. Carried and still unverified: everything on 3d-i's list, plus 3c-ii's restart, synthetic-close and checkout checks.

---

## Limitations, stated rather than buried

**Age eviction is opportunistic**, as argued in Part 1. A low-volume kiosk holds rows past 30 days until a count-triggered compaction sweeps them.

**The count is process-local state over a file.** It is reconstructed on first use and refreshed by every compaction, so it cannot drift far, but it is a second representation of something the file already knows. The alternative — reading the file to learn its length — is the cost this phase exists to remove.

---

## Decisions

**Fix the append's cost, not the crash handler's wait.** The wait is a symptom. A lock timeout would have added a mechanism that three reviews found dangerous, and left every other caller paying the same O(n).

**Counts are keyed by path, not held per instance.** Two instances exist over one file. The lock already solves this problem the same way.

**Eviction is an invariant, not a preference.** "Never a donation while a non-donation remains" is checkable; "prefer the larger table" is a heuristic that fails the realistic case.

**The retained-Activity item is closed, not deferred.** It does not accumulate, and the proposed fix introduces a silent-loss window.

---

## Required of a later phase

- **The truncation-budget gap.** A drifted copy of the suffix kills no test, because the 512-byte reserve absorbs it, so a grep is the only guard. Recorded in `docs/known-debt.md`.
- `SettingsBootstrap`'s call site is still untested, and the 02:00 flush floor is not guaranteed while offline.
