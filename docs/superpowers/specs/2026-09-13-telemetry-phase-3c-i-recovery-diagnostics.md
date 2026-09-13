# Telemetry Phase 3c-i — Recovery Diagnostics, Off the Payment Path

**Goal:** Three conditions the kiosk already detects and recovers from silently now say so — and the reporting mechanism the remaining kinds will use gets proven on sites where a mistake costs nothing.

Phase 3b added `crash`, `update_rollback` and `update_installed`. The master spec has eight kinds left. **This phase ships three of them.**

## Why this is split

The first draft of this spec shipped all eight. Its review found four Criticals, and the pattern behind them is that the eight sites are not alike: three sit on loops and callbacks where a mistake is visible and cheap, and five sit beside the card-payment path where a mistake costs donations. Proving the helper on the cheap sites first is worth one extra phase.

**In 3c-i:** `bluetooth_watchdog_fired`, `network_outage`, `update_install_failed`. All three are off the payment path, all three are reachable on a bench with no card reader, and all three carry the decisions worth unit-testing.

**Deferred to 3c-ii:** `restart_triggered`, `sumup_reinit_failed`, `card_reader_connect_failed`, `card_reader_page_timeout`, `checkout_no_reader`. Their requirements are recorded at the end of this document so the split does not lose them.

---

## Non-negotiables

1. **Recording never makes the thing it records worse.** Every site here is a recovery path — code that runs when something has already gone wrong. Two of the three sit inside `while (true)` loops on `lifecycleScope`, which installs no `CoroutineExceptionHandler`: an escaping throwable there kills the recovery loop *and* the process.

2. **The guard covers argument construction, not just the append.** This is the correction the review forced, and it is the whole reason the helper is shaped the way it is. A helper taking `detail: JsonObject?` evaluates that argument **before** the guard is entered, so building the JSON, reading a vendor field or computing a duration all run unprotected. `detail` is therefore a **lambda**, invoked inside the try. 3b required the crash handler's suppliers be called inside its guard for exactly this reason; dropping that here would have reintroduced the hole one layer up.

3. **A diagnostic must never evict a donation.** The outbox is one queue shared with donation rows, capped at 5,000, and `applyCaps` keeps the *newest* (`takeLast`) — so a chatty diagnostic pushes the oldest rows out, and the oldest rows are donations that have not uploaded yet. Any kind that can repeat on a timer needs a once-per-episode rule, enforced where it can be tested.

4. **A kiosk with `analyticsEnabled` off writes nothing identified to disk.** Unchanged from 3b.

5. **No *known* secret reaches disk.** `TelemetryEvent.Diagnostic` scrubs `detailJson` in its constructor. **This phase does not claim anything about logcat** — see "What this phase does not fix".

6. **The donation flow is untouched.** No site in this phase is on it.

---

## What already exists and is NOT rebuilt

- `TelemetryEvent.Diagnostic` — scrubs and truncates `detailJson` in its constructor.
- `DiagnosticKind` — all eleven kinds with severities. **No enum change.**
- `DiagnosticEvents` — 3b's pure unit: `crash`, `updateRollback`, `updateInstalled`, and the shared `identityOf` gate.
- `CrashContext` — the process-scoped holder, seeded inside `saveSettings`.
- `TelemetryOutbox` — and 3b's **eagerly constructed** instance, built in `onCreate` for the crash handler.
- `NetworkRecoveryManager`, `BluetoothRecoveryManager` — the recovery decisions. This phase reads what they already computed; it changes no decision either makes.

---

## The helper

One function in `MainActivity`, called by every site:

```kotlin
    /**
     * Records a diagnostic and returns. Never throws, whatever happens inside —
     * including while building [detail], which is why that is a lambda rather
     * than a value: an eager argument would be evaluated before this guard is
     * entered, and two of these call sites are inside while(true) loops whose
     * scope installs no CoroutineExceptionHandler.
     */
    private fun reportDiagnostic(
        kind: DiagnosticKind,
        detail: (() -> JsonObject?)? = null,
        occurredAtMs: Long = System.currentTimeMillis()
    )
```

All three callers in this phase are asynchronous: the helper dispatches to `Dispatchers.IO`, because `TelemetryOutbox.append` reads the whole queue to decide about compaction and none of these paths should pay that on the main thread. The synchronous variant 3c-ii needs for `restart_triggered` is **not** built here — building it without its caller would be untested scaffolding.

**The helper uses 3b's eagerly-constructed outbox instance, not the `by lazy` `telemetryOutbox` field.** The reason is 3b's, restated: `lazy`'s default `SYNCHRONIZED` mode can block if another thread is mid-initialisation, and a diagnostic is not worth a stall on a recovery path.

### The generic entry point

`DiagnosticEvents` gains one function beside 3b's three:

```kotlin
    fun forKind(
        settings: Settings?,
        appVersion: String,
        kind: DiagnosticKind,
        occurredAtMs: Long,
        detailJson: String? = null,
        affiliateKey: String? = null
    ): DiagnosticEventResult
```

Same `identityOf` gate, then a `Diagnostic`. 3b's three functions stay as they are — their detail logic does not generalise and rewriting tested code for symmetry is churn.

Collapsing kinds into one function loses nothing typed: `DiagnosticKind` welds each kind to its severity, so no call site can pair them wrongly. What it does not cover is detail *shape*, which is why each kind below gets a small pure builder in `DiagnosticEvents` rather than a `JsonObject` assembled at the call site.

---

## The three kinds

### `network_outage` — warn

`NetworkRecoveryManager.onNetworkRestored` computes `downtime`, compares it against `settings.longDowntimeThresholdSec * 1000L`, returns `AutoReinit` when exceeded, and then discards the number. The call site cannot re-derive it: `networkLostTimestamp` is cleared before the return.

The manager gains **one read-only property** holding the duration of the outage it last reported, assigned immediately before the timestamp is cleared. No decision moves; the threshold comparison stays exactly where it is.

Its lifecycle must be defined, because "last outage" is ambiguous:

| When | Value |
|---|---|
| Before any outage | `0L` |
| After a restored outage, long or short | That outage's duration |
| After an outage that was ignored (not logged in, or `testMode`) | Unchanged from before |
| During a second outage, before it is restored | Still the first outage's duration |

Reported from the `AutoReinit` arm of the existing `when`. Detail: `{"downtime_ms": …, "threshold_ms": …}` — both, because an operator who later changes the threshold would otherwise make old rows uninterpretable.

### `bluetooth_watchdog_fired` — warn

Two problems the first draft missed, both found by review, both verified against the code:

**The duration is destroyed before it can be read.** `BluetoothRecoveryManager.evaluate` sets `offSinceTimestamp = clock()` *before* returning `ReEnable` — deliberately, so a radio that refuses to come back is retried once per threshold rather than every tick. The original off-duration is gone, and the field is private. The first draft forbade changing this class while requiring a number only this class can produce.

**It repeats.** The threshold is 60 seconds and the poll is every 10 seconds, so a radio that stays dead produces a `ReEnable` every minute — roughly 1,400 warn rows a day. Against a 5,000-row queue whose eviction keeps the newest, that silently deletes undelivered **donation** rows. A diagnostic that destroys the data the subsystem exists to carry is worse than no diagnostic.

So `BluetoothRecoveryManager` gains two additive, read-only members, both set as part of the existing `ReEnable` branch:

- the off-duration measured at the moment `ReEnable` was decided, before the clock restarts;
- a count of how many `ReEnable` decisions the **current** outage has produced, reset when tracking ends at a real `STATE_ON`.

The call site reports **only when that count is 1** — the first re-enable of an outage. A radio dead for a week yields one row, not ten thousand. The decision lives in the manager, where it is unit-testable; the call site reads a number.

Detail: `{"off_ms": …}`.

### `update_install_failed` — error

`UpdateManager` fires `onNotification(UpdateNotification.InstallFailed)` from four sites (`:334`, `:347`, `:364`, `:403`), and `MainActivity` already handles that notification at `:2164`. Reporting there covers all four with one edit.

The first draft accepted losing which of the four failed, on the grounds that distinguishing them would change a type the update flow depends on. The review showed that reasoning was wrong on the facts: `UpdateNotification.InstallFailed` is a `data object`, and changing it to `data class InstallFailed(val reason: String? = null)` leaves every `is InstallFailed` arm compiling and costs four token-level edits at the fire sites. A signal worth having for four trivial edits should be had.

Detail: `{"reason": <one of four short constants>}`. The constants are fixed strings chosen at the fire sites — not `PackageInstaller` status text, which is vendor-supplied and belongs nowhere near a dashboard's grouping key.

---

## What this phase does not fix

**Vendor text is already logged raw, and this phase does not change that.** `MainActivity:618` logs the entire SumUp result `Bundle` (`extras=${data?.extras}`), and the four SumUp branches log their error messages. That is a pre-existing logcat exposure, it predates telemetry, and the first draft of this spec wrongly claimed the phase's non-negotiable covered it. It does not: this phase's non-negotiable is about what reaches **disk via the outbox**, which the constructor's scrubbing does cover.

Cleaning up that logging belongs with 3c-ii, which touches all four of those sites anyway. Recorded here so the claim is not quietly dropped.

**No new flush trigger.** 3a's four triggers drain the queue.

**No change to any recovery decision.** Both managers gain read-only members only.

---

## The inertness grep after this phase

```bash
grep -rn "[Oo]utbox\.append(" app/src/main
```

Expected **5**: `TelemetryManager`'s activation row, the donation path, 3b's startup drain, 3b's crash handler, and this phase's single `reportDiagnostic`. `flush()` callers stay at **1**, `activate()` at **1**.

A grep for `DiagnosticKind\.<NAME>` must find **zero** constructions of the five kinds deferred to 3c-ii.

---

## Testing

### JVM-tested

**`DiagnosticEvents.forKind` and the three detail builders:**
- Analytics off, and a null `Settings`, give `NotEnabled` for every kind.
- Each kind's constructed event carries the correct `kind` and `severity` wire strings — table-driven, since severity comes from the enum and a wrong pairing is otherwise invisible.
- Vendor-shaped text containing the affiliate key comes back scrubbed, **and** the same text with a blank affiliate key comes back scrubbed by the 32+ token rule. Both, for 3b's reason: in `testMode` the key is never loaded and only the backstop applies.
- Malformed `detailJson` is dropped rather than thrown — the constructor already does this, and this is the phase that starts feeding it constructed JSON, so it gets a test here.
- `network_outage`'s builder carries both `downtime_ms` and `threshold_ms`.
- `bluetooth_watchdog_fired`'s builder carries `off_ms`.

**`NetworkRecoveryManager`:**
- The exposed duration matches the downtime that triggered `AutoReinit`.
- It is not left stale from a previous outage after a short one that did not trigger.
- It reads `0L` before any outage.
- Every existing behavioural test passes unchanged — the property is additive.

**`BluetoothRecoveryManager`:**
- The exposed off-duration is the duration measured **before** the clock restarts, not after — mutation-check this by reversing the two statements; a test that passes either way is not pinning it.
- The re-enable count is 1 on the first `ReEnable` of an outage and 2 on the second.
- It resets when tracking ends at a real `STATE_ON`, so a later outage starts at 1 again.
- `cycleInProgress` and below-threshold ticks do not increment it.
- Every existing behavioural test passes unchanged.

### Not unit-testable, and labelled as such

The three call sites and the helper's dispatch. `MainActivity` needs the Android lifecycle and Robolectric would breach the no-new-dependencies constraint. Each call site is one line beside an existing log statement.

### Device checks

1. Turn Bluetooth off and leave it off for five minutes on a device-owner install: **exactly one** `bluetooth_watchdog_fired`, not five. This is the check for non-negotiable #3.
2. Turn it back on, then off again past the threshold: a second row, with its own `off_ms`.
3. Pull the network for longer than `longDowntimeThresholdSec`, restore: one `network_outage` whose `downtime_ms` matches the real outage.
4. Pull it for less than the threshold: no row.
5. Install a deliberately bad APK: one `update_install_failed` with a `reason`.
6. With `analyticsEnabled` off, repeat any two: queue depth unchanged.
7. Carried and still unverified: status survives a process restart with `droppedCount` intact (2c); a donation appends exactly one row (3a); the disk-full rollback check (3b).

---

## Decisions

**Split the phase.** Eight sites in one pass, five of them beside the payment path, with a helper that had never run anywhere. The three kinds here exercise every part of that helper — the lambda guard, the IO dispatch, the gate, the scrubbing — on sites where being wrong costs a log line.

**`detail` is a lambda.** The guard that does not cover argument construction is not a guard. This is the correction that matters most in this revision.

**The once-per-outage rule lives in the manager.** A call-site `if` would be a decision in the one file no test reaches — the same mistake 3b's final review found and fixed.

**Both managers expose, they do not decide.** Additive read-only members. Every threshold comparison stays where it is.

**`InstallFailed` carries a reason.** Four token-level edits for a signal that distinguishes four failure modes.

**No synchronous helper variant yet.** `restart_triggered` needs one; it arrives with that caller in 3c-ii rather than as untested scaffolding here.

---

## Required of 3c-ii

The five deferred kinds, all beside the payment path. What that phase must settle, carried forward so the split loses nothing:

- **`restart_triggered`** must be written **synchronously** before `hardRestart` calls `Runtime.exit(0)`; a dispatched write never runs. It also needs the synchronous path to use the eagerly-constructed outbox, since a first touch of the `by lazy` field on that thread can block.
- **The diagnostic that *causes* the restart is currently lost.** `card_reader_connect_failed` would dispatch to IO, and `handleRestartResult` calls `exit(0)` milliseconds later. The failure that crossed the threshold is exactly the one worth having, so the causing diagnostic must be written synchronously too when the result is `RESTART` — which means computing the `RestartResult` before reporting, then passing it on.
- **`checkout_no_reader` needs a written predicate.** "Checkout failed" is not it: the branch's error codes include none that mean "no reader", so the default reading turns every declined card into a warn row. The workable predicate is the existing `isCardReaderConnected` flag read inside the already-failed branch — a state read, not a new check on the payment path, and consistent with the owner's decision that this kind observes rather than gates.
- **The raw vendor logging at those four sites**, including the full `Bundle` dump at `MainActivity:618`.
