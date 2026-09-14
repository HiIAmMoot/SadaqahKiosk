# Telemetry Phase 3d-i — A Refused Table Backs Off Only Itself

**Goal:** A diagnostics table the backend refuses stops delaying donations that would upload fine.

Plus five small carried items that live on the same send path. No new events, no new kinds, no new screen copy.

## Why this is split from the queue work

A single 3d covering the send path and the queue failed review with two Criticals, one in each half:

- **Filtering backed-off rows after `peek` causes permanent head-of-line blocking.** `peek` is `readAll().take(100)` (`TelemetryOutbox.kt:61-63`). A hundred backed-off rows at the head produce an empty batch, the flush reports `BACKING_OFF`, and because nothing is removed the head never advances. Donations behind them would never send **at all** — strictly worse than today's one-hour ceiling. Fixed here by excluding inside the read.
- **Table-aware eviction that prefers the largest table deletes donations while older diagnostics survive.** That belongs to the queue half and is redesigned there, not here.

Putting them in one phase would have let a queue redesign hold up a send-path fix that is ready. The seam is `TelemetryOutbox`: this phase does not change it except to let a reader exclude tables.

**3d-ii** covers the queue: eviction that cannot starve donations, the crash handler's bounded write, and the retained-Activity item. Its requirements are recorded at the end.

---

## Non-negotiables

1. **A donation is never delayed indefinitely by another table's failure.** That is the entire point of this phase, and the first design got it backwards.
2. **The donation flow is not altered.** No change to `makePayment` or any path a payment travels.
3. **No new `Strings` member**, and therefore no copy in eight languages.
4. **A kiosk with `analyticsEnabled` off writes nothing identified to disk.**
5. **Every deletion decision still comes from the uploader**, never re-derived. `TelemetryManager` deletes exactly the ids the uploader names.

---

## What already exists and is NOT rebuilt

- `TelemetryUploader` sends **one request per table** already (`groupBy { it.table }`, `:63`), so `retryableTables` narrows information the uploader already has rather than adding machinery.
- `TelemetryTables` is a closed set of three.
- `PrefsStatusStore.read()` is already total against a malformed store.
- `TelemetryGate`'s precedence order and its fail-open guard for a deadline beyond the ceiling.
- `TelemetryOutbox`'s caps, eviction, lock and `append` — **untouched by this phase** beyond adding an exclusion parameter to the read path.

---

## Part 1: per-table backoff

### `UploadOutcome`

`retryableFailure: Boolean` becomes **`retryableTables: Set<String>`**.

The success side needs the same treatment, and the first draft missed it: `uploadedIds` is flat, so a success cannot be attributed to a table without re-deriving it from the batch — and re-deriving the uploader's judgement is what non-negotiable #5 forbids. `UploadOutcome` therefore also carries **`succeededTables: Set<String>`**.

### `TelemetryStatus`

- `backoffUntilMs: Long` → `backoffUntilMsByTable: Map<String, Long>`
- `consecutiveFailures: Int` → `consecutiveFailuresByTable: Map<String, Int>`

`PrefsStatusStore` persists one flat key per table, derived from the table name — not a serialised map. Tables are a closed set in code, a flat key needs no parser, and this phase must not add a structure that has to be total against corruption. An unreadable key yields an absent entry, never a throw.

### The flush's accounting

Each table named in `retryableTables` has its own count incremented and its own deadline computed from it. **Each table named in `succeededTables` has its count and deadline cleared** — including a healthy table succeeding in the same flush where a sibling failed. The current `when` is exclusive and cannot express that; the outcome handling becomes per-table rather than one branch for the whole flush.

`lastError`, `lastErrorAtMs`, `lastSuccessMs` and `droppedCount` stay global. They describe the subsystem, not a table, and nothing reads them per table.

### Excluding backed-off tables — inside the read

`TelemetryOutbox` gains one parameter on its read path:

```kotlin
    fun peek(limit: Int = DEFAULT_BATCH, excludeTables: Set<String> = emptySet()): List<QueuedEvent>
```

It filters **before** taking `limit`, so a hundred backed-off rows at the head can never crowd out the rows behind them. Default-empty, so no existing caller changes.

`TelemetryManager.flush` computes the backed-off set from status and the clock, passes it to `peek`, and **derives `lastAttemptedIds` from the returned batch** — which it already does, and which is now automatically correct, because the excluded rows were never in the batch. Getting that wrong would make `activate()` report a working destination as `Failed`.

Row ordering within the file is unchanged: `peek` still returns rows in queue order, merely skipping some. Nothing groups or sorts the queue by table.

If every row in the queue belongs to a backed-off table, the batch is empty and the flush reports **`BACKING_OFF`**, not `EMPTY_QUEUE` — the operator-visible outcome for "everything is waiting" is what it is today. A genuinely empty queue still reports `EMPTY_QUEUE`.

### The gate

`TelemetryGate` keeps its global checks and its precedence unchanged. `GateInputs.backoffUntilMs` is **removed** — with per-table state there is no single global deadline, and leaving the field would invite a caller to feed it something arbitrary.

The gate gains one pure function answering whether a given table is backed off at a given clock, carrying the existing fail-open guard: a deadline further out than the ceiling cannot have come from `backoffDelayMs`, so it is ignored rather than trusted.

`BACKING_OFF` now comes from the flush finding every candidate row excluded, rather than from the gate.

### `activate()` clears every table

`activate()` resets failure state so a corrected destination is testable on the very next press. With per-table state that reset must clear **all** tables. Clearing only one would leave a kiosk whose operator has just fixed the URL still backing off donations for up to an hour.

### What the screen sees

`AnalyticsPresenter`'s output shape does not change. Three fields aggregate:

- `backingOff` — any table is backed off.
- `backoffRemainingSeconds` — the **longest** remaining, so the countdown ends when the kiosk is fully unblocked.
- `consecutiveFailures` — the **highest** across tables, which is what drives the operator's sense of "how bad is this".

Per-table detail on the kiosk screen would need new copy in eight languages for a distinction an operator cannot act on. The dashboard has the per-table view.

---

## Part 2: five carried items on this path

- **The silent-login watchdog can arm a stale label.** If `openLoginActivity` ever returns without launching, the watchdog arms `syntheticCloseLogin`, `finishActivity` is a no-op, and the next genuine login failure consumes the stale label and reports as self-inflicted. Whether the SDK can do that is unknowable from this repo, and the fix does not need to know: **clear the slot immediately before `openLoginActivity`**. Any genuine code-1 result comes from a launch, and every launch then clears first.

  One case the clear does not cover, stated rather than claimed away: a re-entrant `authenticate` while a login is still outstanding erases a label that was legitimately armed, so a synthetic close reads as genuine. That is the safe direction — a missing discriminator, not a false one.

- **`RestartManager.cardReaderFailures` and `reinitFailures`** have no main-source readers since 3c-ii removed the throttle. Removing them is larger than "delete two getters": three tests read them, and one asserts counter *isolation* — that a reader failure does not move the reinit count — which `RestartResult` alone cannot express. Keep whichever accessor that test genuinely needs and remove the rest, saying which and why. `restartCount` stays; it has a real reader.

- **`TelemetryRedactor.truncate`'s `maxBytes` parameter** has no non-default caller anywhere. Remove the parameter.

- **A truncation-suffix literal is duplicated** between `DiagnosticEvents` and `TelemetryRedactor`. One source. This is the literal only — the two truncation *functions* are different and both stay.

- **`formatTimestamp` in `AnalyticsSettingsScreen`** is the one computation left in a file no unit test can reach, and phase 2c's design is that the screen computes nothing. Move the formatting behind the presenter. This adds an `AnalyticsView` field, not a `Strings` member, so non-negotiable #3 holds.

---

## Testing

### JVM-tested

**Per-table backoff:**
- A retryable failure on one table backs off that table and leaves the others clear.
- A success on one table clears its count and deadline **in the same flush** where a sibling fails.
- Per-table counts drive per-table delays independently.
- `activate()` clears every table's backoff, not just one.
- The fail-open guard applies per table: a deadline beyond the ceiling does not block.
- `PrefsStatusStore` round-trips per-table state and `read()` stays total when one table's key is corrupt.

**Exclusion inside `peek` — the Critical this phase exists to avoid:**
- With more than `limit` backed-off rows at the head of the queue, `peek` still returns the rows behind them. **Mutation-check it**: filter after `take` instead of before, and confirm this test fails. That mutation is the original defect.
- Excluded rows stay in the queue.
- `peek` with no exclusions behaves exactly as today; every existing `TelemetryOutboxTest` passes unmodified.
- Queue order is preserved across an exclusion.

**The flush:**
- A batch emptied entirely by exclusion reports `BACKING_OFF`; a genuinely empty queue reports `EMPTY_QUEUE`.
- `lastAttemptedIds` contains only rows actually sent, so `activate()` does not report a working destination as `Failed`.
- Deletion still uses only uploader-named ids.

**Part 2:** the suffix literal has one definition; `truncate` has one arity; the presenter formats the timestamp and the screen's logic grep stays empty.

### Not unit-testable, and labelled as such

The watchdog clear. Wiring over a decision tested elsewhere.

### Device checks

1. Break one table at the backend (rename a column) and take a donation: the donation arrives, the broken table's rows stay queued, the screen shows a backoff.
2. Let the broken table's rows exceed a batch and sit at the head of the queue, then take a donation: **the donation still uploads.** This is the head-of-line check and the reason this phase exists.
3. Fix the backend and press Test connection: the backoff clears for every table at once.
4. Carried and unverified: status survives a restart with `droppedCount` intact (2c); a donation appends exactly one row (3a); the disk-full rollback check (3b); the Bluetooth once-per-outage check (3c-i, **without rotating the device**); 3c-ii's restart, synthetic-close and checkout checks.

---

## Limitations, stated rather than buried

**A uniformly refused table still accumulates rows that never delete.** This phase stops it delaying other tables; it does not stop it filling the queue. That is 3d-ii's, and until then the caps are what bound it.

**Per-table state makes the screen's numbers aggregates.** An operator reading "3 consecutive failures" is reading the worst table, not a total.

---

## Decisions

**Exclude inside `peek`, not after it.** Filtering a batch that was already truncated to its first hundred rows is how a fix for delay becomes a cause of permanent starvation.

**`succeededTables`, not a derived success.** Attribution belongs to the uploader; re-deriving it in the manager is the thing this subsystem's charter forbids.

**Remove `GateInputs.backoffUntilMs` rather than leave it.** A field with no single correct value is a field a caller will fill in wrongly.

**Flat per-table keys.** A closed set of tables needs no parser, and a parser is one more thing that must be total against corruption.

**The screen aggregates.** Per-table detail costs eight languages and buys an operator nothing they can act on.

---

## Required of 3d-ii

- **Eviction must not starve donations, and the obvious rule does not work.** Preferring the largest table evicts a donation while older diagnostics survive — 3,000 donations against 2,000 older diagnostics at a 5,000 cap drops the oldest donation, where today's `takeLast` drops the diagnostic. A realistic shape, since 3c-ii ships `checkout_no_reader` unthrottled. The rule must encode the invariant directly: nothing evicts a donation while a non-donation row is available to evict.
- **`applyCaps` runs on every append, inside the lock, on the donation path.** Any new eviction rule must be a single pass over counts, not a loop that rescans.
- **The crash handler's bounded write**, with the four traps three earlier reviews named: `synchronized(aReentrantLock)` compiles and guards nothing, so the lock field is renamed and every site must use `withLock` — including `lockFor` and the path-keyed map, which are typed `Any` today; the byte ceiling was not derivable and must not return in another form, including as an unjustified lock timeout; the heal must be neither slack-gated nor **lifecycle-gated** — `drainUpdateDiagnostics` launches after `onCreate` returns, so a crash inside `onCreate` never reaches it, which is the same unboundedness in a new gate; and compaction must stay off the main thread.
- **`tryLock(timeout)` clears the interrupt flag when it throws**, so a handler that restores it must call `Thread.currentThread().interrupt()` — a test that only pins the return value will not catch its absence.
- **`CrashContext.onOutboxDropped` retains one live Activity.** Moving the status store to process scope needs a named holder; this app has no `Application` subclass, and a drop before the holder is initialised loses `droppedCount` silently.
