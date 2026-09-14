# Telemetry Phase 3d-i — A Refused Table Backs Off Only Itself

**Goal:** A diagnostics table the backend refuses stops delaying donations that would upload fine.

Plus four small carried items on the same send path. No new events, no new kinds, no new screen copy.

## Why this is split from the queue work

A single 3d covering the send path and the queue failed review with two Criticals, one in each half:

- **Filtering backed-off rows after `peek` causes permanent head-of-line blocking.** `peek` is `readAll().take(100)` (`TelemetryOutbox.kt:61-63`). A hundred backed-off rows at the head produce an empty batch, and because nothing is removed the head never advances — donations behind them would never send **at all**, strictly worse than today's one-hour ceiling. Fixed here by excluding inside the read.
- **Table-aware eviction preferring the largest table deletes donations while older diagnostics survive.** That belongs to the queue half and is redesigned there.

The seam is `TelemetryOutbox`: this phase touches it only to add one defaulted parameter to the read path. **3d-ii** covers eviction, the crash handler's bounded write, and the retained-Activity item; its requirements are recorded at the end.

---

## Non-negotiables

1. **A donation is never delayed indefinitely by another table's failure.** The first design inverted this; the bound stays what it is today — at most one backoff ceiling.
2. **The donation flow is not altered.** No change to `makePayment` or any path a payment travels.
3. **No new `Strings` member**, and therefore no copy in eight languages.
4. **A kiosk with `analyticsEnabled` off writes nothing identified to disk.**
5. **Every deletion decision still comes from the uploader**, never re-derived. `TelemetryManager` deletes exactly the ids the uploader names.
6. **One corrupt prefs key costs one field, not the whole status.** `droppedCount` is an operator's only view of telemetry loss; it must not be zeroed by an unrelated key going bad.

---

## What already exists and is NOT rebuilt

- `TelemetryUploader` sends **one request per table** already (`groupBy { it.table }`, `:63`), so `retryableTables` narrows information it already has.
- `TelemetryTables` is a closed set of three.
- `TelemetryGate`'s precedence order, and its fail-open guard for a deadline beyond the ceiling.
- `TelemetryOutbox`'s caps, eviction, lock and `append` — untouched beyond the read-path parameter.

---

## Part 1: per-table backoff

### `UploadOutcome`

`retryableFailure: Boolean` becomes **`retryableTables: Set<String>`**, and the outcome also carries **`succeededTables: Set<String>`**. The success side needs the same treatment: `uploadedIds` is flat, so attributing a success to a table means re-deriving it from the batch, which non-negotiable #5 forbids.

**A table can legitimately appear in both.** In the per-row fallback (`TelemetryUploader.kt:118-126`) some rows can upload and the network can then drop mid-sweep, setting `retryable` while `uploadedHere` is non-empty. So the manager states precedence explicitly:

> **Failure wins.** A table in both sets backs off. It has rows that did not send, and treating it as healthy would retry them on every flush — a tight loop against a broken link.

### `TelemetryStatus`

- `backoffUntilMs: Long` → `backoffUntilMsByTable: Map<String, Long>`
- `consecutiveFailures: Int` → `consecutiveFailuresByTable: Map<String, Int>`

`TelemetryStatus` also gains one **pure helper**, because three call sites need the same aggregate and two of them are outside the presenter:

```kotlin
    /** The latest deadline any table is waiting on, with the gate's fail-open
     *  guard applied: a deadline further out than the ceiling cannot have come
     *  from backoffDelayMs, so it is ignored rather than trusted. */
    fun effectiveBackoffUntilMs(nowMs: Long): Long
```

Without the guard here, a single corrupt deadline would dominate a "longest remaining" aggregate and freeze the screen indefinitely — the guard exists in the gate precisely to stop that, and an aggregate that skips it reintroduces the bug one layer up.

### The global fields, which are not per-table

`lastError`, `lastErrorAtMs`, `lastSuccessMs` and `droppedCount` stay global, and the per-table pass needs an explicit rule or a healthy sibling erases the only signal an operator has that a table is broken:

- **`lastError` / `lastErrorAtMs`** — written whenever any table fails retryably. Cleared **only when no table is backed off**. A donations success must not wipe the schema error that explains why diagnostics are stuck.
- **`lastSuccessMs`** — advanced whenever any table succeeds. "Something reached the backend" is true, and it is what the field means.

**Keeping the error in the store is not enough, and this is the trap.** `AnalyticsPresenter` suppresses a stale error with `takeIf { lastSuccessMs <= lastErrorAtMs }` (`:111`). Today a global backoff freezes `lastSuccessMs`, so the suppression never fires while a failure stands. This phase removes that freeze — a sibling's success now advances `lastSuccessMs` past the retained `lastErrorAtMs`, and the screen renders **nothing** beside a non-zero failure count. The store would be right and the operator would see a blank.

So the presenter's rule changes too: the error is shown while **any table is backed off**, and the timestamp comparison only decides the case where none is. `AnalyticsView.error` is therefore a **fourth** changed presenter field, and its test asserts on the view, not on the store — a store-level assertion passes while the screen is blank.

### The flush's accounting

Each table in `retryableTables` gets its own count incremented and its own deadline computed from it. Each table in `succeededTables` **and not in `retryableTables`** has its count and deadline cleared — including in a flush where a sibling failed. The current exclusive `when` cannot express that; outcome handling becomes a per-table pass.

**A throw from `upload` has no table attribution.** The existing catch converts an exception into a retryable failure (`TelemetryManager.kt:207-235`). A transport-level throw is table-agnostic, so it backs off **every table in the batch** — the tables actually attempted, not all three.

### Excluding backed-off tables — inside the read

```kotlin
    fun peek(limit: Int = DEFAULT_BATCH, excludeTables: Set<String> = emptySet()): List<QueuedEvent>
```

Filters **before** taking `limit`, so backed-off rows at the head can never crowd out the rows behind them. Default-empty, so no existing caller changes and every existing `TelemetryOutboxTest` passes unmodified. Queue order is preserved; nothing groups or sorts by table.

`TelemetryManager.flush` computes the backed-off set from status and the clock, passes it to `peek`, and derives `lastAttemptedIds` from the returned batch — which it already does, and which is now automatically correct because excluded rows were never in the batch.

If every queued row belongs to a backed-off table the batch is empty and the flush reports **`BACKING_OFF`**; a genuinely empty queue still reports `EMPTY_QUEUE`. Note this distinction reaches logcat and the flush's return value, not the screen — the screen shows backoff through `backingOff`.

This costs one extra `readAll` on a flush that ends up fully excluded. Acceptable: that flush does no network work at all.

### The gate

`TelemetryGate` keeps its global checks and precedence. `GateInputs.backoffUntilMs` is **removed** — it has exactly two suppliers (`TelemetryManager.kt:123`, `:190`), both in this phase's scope, and with per-table state there is no single correct value to feed it.

The gate gains one pure predicate:

```kotlin
    fun isTableBackedOff(backoffUntilMs: Long, nowMs: Long): Boolean
```

carrying the fail-open guard. **`TelemetryStatus.effectiveBackoffUntilMs` is built on this predicate** rather than repeating the comparison — a second copy of the guard is the duplication the Decisions section warns about. `BACKING_OFF` now originates from the flush finding every candidate excluded rather than from the gate's own branch.

### `activate()` clears every table

`activate()` resets failure state so a corrected destination is testable on the next press. That reset must clear **all** tables; clearing one would leave an operator who has just fixed the URL still backing off donations for up to an hour.

### `PrefsStatusStore`

One flat key per table, named by a stated scheme: the existing key plus `.` plus the table name — `backoff_until_ms.donation_events`. Tables are a closed set in code; a flat key needs no parser. `TelemetryTables` currently declares three constants but exposes no list, so it gains one — `read()` and `write()` both enumerate from it, and a fourth table added later cannot be persisted by one and forgotten by the other.

**`read()` becomes total per key, not per status.** Today one `ClassCastException` anywhere yields a wholly default `TelemetryStatus`, which silently zeroes `droppedCount`. Going from six keys to ten multiplies the ways that happens. Each key is read defensively so a corrupt key costs its own field and nothing else.

**Existing installs carry `consecutive_failures` and `backoff_until_ms`.** They are read once as the value for every table, then superseded by the per-table keys on the next write. A kiosk mid-backoff at upgrade keeps backing off rather than resetting to zero.

### What the screen sees

`AnalyticsPresenter`'s output shape does not change. Four fields change how they are derived — the two backoff fields through `effectiveBackoffUntilMs`, `consecutiveFailures` by taking the maximum directly, and `error` by the rule above:

- `backingOff` — any table is backed off.
- `backoffRemainingSeconds` — longest remaining, so the countdown ends when the kiosk is fully unblocked.
- `consecutiveFailures` — the highest across tables, which is what drives an operator's sense of how bad it is.

**`MainActivity.startAnalyticsBackoffTickerIfNeeded` reads `status.backoffUntilMs` directly** (`:2001`, `:2008`), outside the presenter. It moves to the same helper. Missing it would leave the countdown frozen or the ticker never starting.

---

## Part 2: four carried items on this path

- **The silent-login watchdog can arm a stale label.** If `openLoginActivity` ever returns without launching, the watchdog arms `syntheticCloseLogin`, `finishActivity` is a no-op, and the next genuine login failure reports as self-inflicted. Unknowable from this repo, and the fix does not need to know: **clear the slot immediately before `openLoginActivity`**. Any genuine code-1 result comes from a launch, and every launch then clears first.

  One case it does not cover: a re-entrant `authenticate` while a login is still outstanding erases a legitimately armed label, so a synthetic close reads as genuine. The safe direction — a missing discriminator, not a false one.

- **`TelemetryRedactor.truncate`'s `maxBytes` parameter** has no non-default caller anywhere. Remove the parameter.

- **A truncation-suffix literal is duplicated** between `DiagnosticEvents` and `TelemetryRedactor`. One source. The literal only — the two truncation *functions* are different and both stay.

- **`formatTimestamp` in `AnalyticsSettingsScreen`** is the one computation in a file no unit test reaches. Move the formatting behind the presenter, which adds an `AnalyticsView` field rather than a `Strings` member. Two constraints: the presenter must take the zone and locale as parameters rather than reading platform defaults, or its test is machine-dependent; and the `neverUploaded` branch stays a screen concern, since rendering it needs a `Strings` member the presenter must not reach for.

- **Two comments this phase makes untrue.** `TelemetryManager.kt:91-92` and `:107-115` describe backoff as one global deadline, and `:107-115` explains a line this phase deletes. Both are corrected here, not deferred — a comment describing removed code is worse than none.

**Dropped from this phase, and why.** `RestartManager.cardReaderFailures` and `reinitFailures` were listed as dead getters to remove. They are not: **six** tests read them, and **two** assert counter *isolation* — that a reader failure does not move the reinit count — which `RestartResult` alone cannot express. Removing them nets zero deletions and costs real coverage. They stay, with a KDoc saying they exist for isolation assertions so the next reader does not retry this.

---

## Testing

### JVM-tested

**Per-table backoff:**
- A retryable failure on one table backs off that table and leaves the others clear.
- A success on one table clears its state **in the same flush** where a sibling fails.
- A table in **both** sets backs off — failure wins.
- A throw from `upload` backs off every table in the batch, and no table absent from it.
- Per-table counts drive per-table delays independently.
- `activate()` clears every table.
- `effectiveBackoffUntilMs` ignores a deadline beyond the ceiling — **mutation-check**: drop the guard and confirm the test fails, since without it a corrupt deadline freezes the screen.

**`lastError` lifetime:**
- A sibling's success does **not** clear `lastError` while another table is backed off.
- It clears once no table is backed off.

**Exclusion inside `peek` — the Critical this phase exists to avoid:**
- With more than `limit` backed-off rows at the head, `peek` still returns the rows behind them. **Mutation-check**: filter after `take` instead of before, and confirm this test fails. That mutation is the original defect.
- Excluded rows stay queued; order is preserved; `peek` with no exclusions behaves exactly as today.

**The flush:** a batch emptied by exclusion reports `BACKING_OFF`, a genuinely empty queue reports `EMPTY_QUEUE`; `lastAttemptedIds` contains only rows actually sent; deletion still uses only uploader-named ids.

**`PrefsStatusStore`:** per-table round trip; **one corrupt key costs one field** and `droppedCount` survives; legacy single-value keys are read as the value for every table on first upgrade.

**Part 2:** the suffix literal has one definition; `truncate` has one arity; the presenter formats the timestamp with an injected zone and locale, and the screen's logic grep stays empty.

**Tests that must be updated, not deleted:** `TelemetryGateTest` cases covering the removed `GateInputs` field, and `TelemetryManagerTest`'s global-backoff cases. Each has a per-table equivalent; porting them is part of the work.

### Not unit-testable, and labelled as such

The watchdog clear, and the ticker's move to the shared helper.

### Device checks

1. Break one table at the backend (rename a column) and take a donation: the donation arrives, the broken table's rows stay queued, the screen shows a backoff.
2. Let the broken table's rows exceed a batch and sit at the head, then take a donation: **the donation still uploads.** The head-of-line check, and the reason this phase exists.
3. Fix the backend and press Test connection: backoff clears for every table at once.
4. Upgrade a kiosk that is mid-backoff: it keeps backing off rather than resetting.
5. Carried and unverified: status survives a restart with `droppedCount` intact (2c); a donation appends exactly one row (3a); the disk-full rollback check (3b); the Bluetooth once-per-outage check (3c-i, **without rotating the device**); 3c-ii's restart, synthetic-close and checkout checks.

---

## Limitations, stated rather than buried

**A uniformly refused table still accumulates rows that never delete.** This phase stops it delaying other tables; it does not stop it filling the queue. That is 3d-ii's, and until then the caps bound it.

**The screen's numbers are aggregates.** "3 consecutive failures" is the worst table, not a total.

---

## Decisions

**Exclude inside `peek`, not after it.** Filtering a batch already truncated to its first hundred rows turns a fix for delay into permanent starvation.

**Failure wins when a table is in both sets.** Reachable in the per-row fallback, and treating such a table as healthy is a tight retry loop.

**A throw backs off the batch's tables, not all three.** The exception is table-agnostic; the batch is not.

**`lastError` survives a sibling's success.** Otherwise a healthy donations table erases the only evidence that diagnostics are broken.

**One helper for the aggregate.** Three call sites need it and two are outside the presenter; a second derivation is a second place to forget the fail-open guard.

**Per-key totality.** `droppedCount` is an operator's only view of loss and must not be collateral damage from an unrelated key.

**Keep the `RestartManager` getters.** The item was based on a wrong count; removing them costs isolation coverage and deletes nothing.

---

## Required of 3d-ii

- **Eviction must not starve donations, and the obvious rule does not work.** Preferring the largest table evicts a donation while older diagnostics survive — 3,000 donations against 2,000 older diagnostics at a 5,000 cap drops the oldest donation where today's `takeLast` drops the diagnostic. Realistic, since 3c-ii ships `checkout_no_reader` unthrottled. The rule must encode the invariant directly: nothing evicts a donation while a non-donation row is available to evict. **State the tie-break**, because under any balancing rule the tie is the steady state.
- **`applyCaps` runs on every append, inside the lock, on the donation path.** Any new rule must be a single pass over counts, not a rescan.
- **The crash handler's bounded write**, with the four traps three earlier reviews named: `synchronized(aReentrantLock)` compiles and guards nothing, so the lock field is renamed and every site must use `withLock` — including `lockFor` and the path-keyed map, typed `Any` today; the byte ceiling was not derivable and must not return in another form, **including as an unjustified lock timeout**; the heal must be neither slack-gated nor **lifecycle-gated** — `drainUpdateDiagnostics` launches after `onCreate` returns, so a crash inside `onCreate` never reaches it; and compaction stays off the main thread. **`compactNow` should skip `writeAll` when nothing is droppable**, or every boot rewrites the whole queue — and state whether it calls `onDropped`.
- **`tryLock(timeout)` clears the interrupt flag when it throws**, so restoring it needs `Thread.currentThread().interrupt()`. A test pinning only the return value will not catch its absence.
- **`CrashContext.onOutboxDropped` retains one live Activity.** Moving the status store to process scope needs a named holder; this app has no `Application` subclass, and a drop before the holder is initialised loses `droppedCount` silently.
- **Stale comments** left by this phase family: `MainActivity.kt:2055-2068` describes backoff as a single global deadline.
