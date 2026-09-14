# Telemetry Phase 3d — Clearing the Debt

**Goal:** A refused table stops punishing the healthy ones, a poisoned table stops crowding out donations, a crash handler stops doing unbounded work on a dying thread, and the small things left behind by five phases get finished.

No new events, no new kinds, no new screens. Every change here closes something a previous phase recorded and deferred.

## What is deliberately NOT here

Two items stay open and are recorded in `docs/known-debt.md` rather than fixed:

- **The 02:00 flush floor while offline.** `scheduleDailyLoginReset` does not re-arm itself. That scheduler is the card-payment recovery path, and a telemetry gap is a poor reason to change code where a mistake costs donations. Declined on this reasoning twice already.
- **`SettingsBootstrap`'s untested call site.** It needs instrumented coverage, and `app/src/androidTest` holds only the generated example. That means standing up an instrumented harness on hardware, which is held until the telemetry phases are done.

---

## Non-negotiables

1. **A donation is never deleted to make room for a diagnostic.** The outbox is one queue shared by three tables and it is the only copy of a donation. Everything in this phase that touches eviction is judged against that sentence first.
2. **The donation flow is not altered.** No change to `makePayment` or any path a payment travels.
3. **Recording never makes the thing it records worse.** Unchanged from 3b and 3c.
4. **No behaviour change reaches the operator's screen.** `AnalyticsPresenter`'s output shape stays as it is: this phase adds no `Strings` member and therefore no copy in eight languages.
5. **A kiosk with `analyticsEnabled` off writes nothing identified to disk.**
6. **Nothing unredacted reaches a file.**

---

## What already exists and is NOT rebuilt

- `TelemetryUploader` — **already sends one request per table** (`groupBy { it.table }`, `:63`), with the comment that a failure in one table must not discard another's success. Tables are already separated for *deletion*; only `retryableFailure` and a single `backoffUntilMs` collapse them again.
- `TelemetryTables` — a closed set of three: `donation_events`, `diagnostic_events`, `telemetry_activations`.
- `TelemetryOutbox.applyCaps` — a count cap and an age cap, with an epoch-floor exemption for rows stamped before a plausible date.
- `TelemetryGate.evaluate` — precedence-ordered global checks, plus `BACKING_OFF`.
- `PrefsStatusStore` — flat keys, `read()` already total against a malformed store.
- `DiagnosticReporter`, `PendingDiagnostics`, `RestartReporting` — untouched.

---

## Part 1: a refused table backs off only itself

### The failure being fixed

`UploadOutcome.retryableFailure` is one Boolean. A diagnostics table refused for a schema reason — the realistic cause, a kind shipped before the column exists — sets it, and `TelemetryManager.flush` then applies a global backoff of up to an hour. Donations that would have uploaded fine wait behind a table that is broken for reasons that have nothing to do with them.

### The change

`UploadOutcome.retryableFailure: Boolean` becomes **`retryableTables: Set<String>`** — the tables whose upload failed in a way worth retrying. The uploader already knows this per table; it currently discards the distinction on the way out.

`TelemetryStatus` carries backoff **per table**:

- `backoffUntilMs: Long` → `backoffUntilMsByTable: Map<String, Long>`
- `consecutiveFailures: Int` → `consecutiveFailuresByTable: Map<String, Int>`

`PrefsStatusStore` persists these as **one flat key per table**, derived from the table name — not a serialised map. Tables are a closed set in code, a flat key needs no parser, and this phase should not add a second thing that has to be total against corruption. `read()` stays total: an unreadable key yields an absent entry, never a throw.

### Where the decision lives

`TelemetryGate` keeps its global checks unchanged. It gains **one pure function** that answers, for a given table and clock, whether that table is backed off — including the existing guard that a deadline further out than the ceiling cannot have come from `backoffDelayMs` and is therefore failing open.

`TelemetryManager.flush` filters the peeked batch: rows whose table is currently backed off are left in the queue and not sent. If filtering empties the batch, the flush reports `BACKING_OFF` exactly as it does today — the operator-visible outcome for "everything is waiting" does not change.

### What the screen sees

`AnalyticsPresenter` aggregates, and its output shape does not change:

- `backingOff` — true when **any** table is backed off.
- `backoffRemainingSeconds` — the **longest** remaining, so the number counts down to the moment the kiosk is fully unblocked.

This is deliberate. Per-table backoff on screen would need at least one new `Strings` member and therefore copy in eight languages, for a distinction an operator cannot act on. The dashboard can see per-table detail; the kiosk screen does not need to.

---

## Part 2: a poisoned table stops crowding out donations

### The failure being fixed

This is not on the debt list, and it is worse than the item that is.

A table refused **uniformly** — every row, every time — is treated as retryable, so nothing deletes its rows. They accumulate. `applyCaps` then evicts the globally oldest rows to stay under the count cap, and the oldest rows are the ones that have been waiting longest — which on an offline kiosk are **undelivered donations**. A diagnostics table the backend will never accept can therefore delete donations.

### The change

Eviction becomes **table-aware**. When the queue is over its count cap, drop the oldest row belonging to whichever table currently holds the **most** rows, and repeat until the queue fits.

A table that balloons therefore eats its own rows first. Donations are evicted only when donations are genuinely the largest table — which is the case the count cap is legitimately for.

Chosen over the alternatives on purpose:

- **Per-table budgets** would waste the queue whenever a table is idle, and would need numbers nobody can derive.
- **A byte ceiling** was tried in 3c-ii and failed review: the caps permit rows of wildly different sizes, so any number small enough to bound the disk is reachable by a legal queue.
- **Deleting a poisoned table's rows outright** is unacceptable when the poisoned table might be `donation_events`.

The age cap and the epoch-floor exemption are unchanged. `onDropped` reports table-aware evictions exactly as it reports the others — an operator sees loss the same way whatever chose the row.

---

## Part 3: the crash handler stops doing unbounded work

### The failure being fixed

`KioskCrashHandler` appends through the ordinary path, which reads the entire queue to decide about compaction and takes a lock another thread may hold across a full rewrite. On a dying thread that is unbounded work behind an unbounded wait — and a blocked crash handler is exactly the hung kiosk that chaining to the previous handler exists to prevent. Real since 3b.

### The change, and the four traps three earlier reviews found

An earlier attempt at this failed three spec reviews. Each trap is named here so the same ground is not lost twice:

**The lock becomes a `ReentrantLock`** so a timed acquisition is expressible at all — a monitor has no such thing. **`synchronized(aReentrantLock)` compiles** and takes that object's monitor instead of the lock, so a half-finished migration silently guards nothing and no single-threaded test can see it. Therefore: rename the field, so every stale call site is a compile error, and require `withLock` at each; the plan greps the file for `synchronized(` and requires zero hits.

**`appendUrgent(id, table, payload): Boolean`** takes the lock with a timeout, appends one line, does **not** compact, and returns false when it wrote nothing — the lock was not acquired, the calling thread's interrupt flag was already set (`tryLock` throws immediately in that case rather than waiting), or the write threw. Giving up is a correct outcome: a diagnostic is not worth delaying a restart.

**Skipping compaction forever would be unbounded**, and the earlier design's answer — "the next ordinary append compacts" — is false for a crash-looping kiosk, which performs none. So compaction gets an **explicit, unconditional** `compactNow()`, called from the startup path that already runs off the main thread. Not from the constructor: putting a full queue read in `onCreate` is an ANR, which is how the previous attempt failed its third review.

`append`'s own signature does not change, and no existing caller is touched. A defaulted `compact` flag on the existing function would let an ordinary caller opt out of cap enforcement by accident, and the two operations have genuinely different contracts — one enforces the caps and cannot fail, the other enforces nothing and may do nothing.

---

## Part 4: the small things

Each is listed in a previous phase's carried debt.

- **The silent-login watchdog can arm a stale label.** If `openLoginActivity` ever returns without launching, the watchdog arms `syntheticCloseLogin`, `finishActivity` is a no-op, and the next genuine login failure consumes the stale label and reports as self-inflicted. Whether the SDK can do that is unknowable from this repo — the fix does not need to know. **Clear the slot immediately before `openLoginActivity`**: any genuine code-1 result must come from a launch, and every launch then clears first, so a stale label can never survive into the failure that would consume it.
- **`CrashContext.onOutboxDropped` retains one live Activity.** The slot holds a bound reference to an Activity method because the status store it needs is an Activity-lazy field. Make the status store process-scoped so the handler can read it without an Activity in the chain, and retire the slot.
- **`RestartManager.cardReaderFailures` and `reinitFailures`** have no main-source readers since the throttle was removed. Remove them; the tests that read them assert through `RestartResult` instead, which is the behaviour that matters.
- **`TelemetryRedactor.truncate`'s `maxBytes` parameter** has no non-default caller. Remove the parameter.
- **A truncation-suffix literal is duplicated** between `DiagnosticEvents` and `TelemetryRedactor`. One source.
- **`formatTimestamp` in `AnalyticsSettingsScreen`** is the one computation left in a file no unit test can reach, and phase 2c's whole design is that the screen computes nothing. Move the formatting behind the presenter so the invariant holds without an exception.

---

## Testing

### JVM-tested

**Per-table backoff:**
- A retryable failure on one table backs off that table and leaves the others clear.
- A flush filters backed-off tables out of the batch and leaves their rows queued.
- A flush whose batch is entirely backed off reports `BACKING_OFF`.
- The failing-open guard still applies per table: a deadline beyond the ceiling does not block.
- Per-table failure counts drive per-table delays independently.
- `PrefsStatusStore` round-trips a per-table map, and `read()` stays total when one table's key is corrupt.

**Table-aware eviction:**
- Over the cap with a lopsided queue, the largest table loses its oldest row and the others are untouched.
- A queue of only donations evicts donations — the cap still works when there is nothing to prefer.
- `onDropped` reports table-aware evictions.
- The age cap and epoch-floor exemption are unchanged: every existing `TelemetryOutboxTest` passes unmodified.

**`appendUrgent`:**
- Writes a row a subsequent `peek` returns, and does not compact — exceed the cap with urgent appends only and assert nothing was evicted.
- Returns false rather than blocking when the lock is held; the test blocks a holder deterministically through the injectable clock rather than sleeping, and carries an explicit timeout because the mutation below hangs without one.
- Returns false when the calling thread is already interrupted, leaving the flag set.
- Returns false rather than throwing when the write fails.
- **Mutation-check:** replace the timed acquisition with an unconditional one and confirm the contention test fails by timing out.

**`compactNow`:** compacts unconditionally — a queue one row over its cap is trimmed, which a slack-gated implementation would not do. That is the specific defect that failed the previous attempt's third review.

**The small items:** the suffix literal has one definition; `truncate` has one arity; the presenter formats the timestamp and the screen's logic grep stays empty.

### Not unit-testable, and labelled as such

The watchdog clear, the status store's move to process scope, and `compactNow`'s call site. Each is wiring over decisions tested elsewhere.

### Device checks

1. Break one table at the backend (rename a column) and take a donation: the donation arrives, the broken table's rows stay queued, and the analytics screen shows a backoff.
2. Leave that table broken and keep the kiosk busy: donation rows are **not** evicted as the broken table accumulates.
3. Force an uncaught exception: the app restarts exactly as today and one `crash` row arrives.
4. Carried and still unverified: status survives a restart with `droppedCount` intact (2c); a donation appends exactly one row (3a); the disk-full rollback check (3b); the Bluetooth once-per-outage check (3c-i, **without rotating the device**); and 3c-ii's restart, synthetic-close and checkout checks.

---

## Limitations, stated rather than buried

**A refused write from the crash handler is a lost diagnostic, silently.** Counting it would mean work on the thread this exists to keep free.

**Table-aware eviction does not rescue a poisoned `donation_events`.** If the backend refuses donations themselves, this phase bounds nothing that matters — the operator sees a growing queue and a persistent error, and that is the signal. A backend that refuses donations is a configuration emergency, not a case to engineer around.

**The timeout bounds contention, not I/O.** A wedged filesystem can still block the write itself.

---

## Decisions

**Per-table keys, not a serialised map.** Tables are a closed set; a flat key needs no parser, and this phase should not add a second structure that must be total against corruption.

**The screen aggregates.** Per-table backoff on screen buys an operator nothing they can act on and costs copy in eight languages.

**Evict from the largest table, not by budget or bytes.** Budgets waste an idle table's share and need numbers nobody can derive; a byte ceiling failed review in 3c-ii for reasons that have not changed.

**`compactNow` is explicit and off-main.** The previous attempt put compaction in the constructor and failed review on the ANR.

**Rename the lock field.** The migration's failure mode is a silent no-op that compiles and that no single-threaded test can catch; a rename turns it into a compile error.

**The watchdog fix does not need the SDK's behaviour.** Clearing before each launch contains the stale label whatever the SDK does.

---

## Required after this phase

Telemetry carries no further debt of its own. Remaining: phase 4 (the disclosure screen and its eight translations) and phase 5 (documentation), plus the two entries standing in `docs/known-debt.md`.
