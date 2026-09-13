# Telemetry Phase 3c-ii — The Last Diagnostic Kinds

**Goal:** The remaining conditions the kiosk detects and recovers from silently now report themselves — including the auto-restart, which until now took the reason for the restart down with it.

This completes `diagnostic_events`. After it, the remaining telemetry work is phases 4 and 5.

## What this phase is not

An earlier draft proposed a new outbox mechanism for writing from a dying process — a `ReentrantLock` with timed acquisition, an `appendUrgent` that skipped compaction, a byte cap, compaction on construction. **Three consecutive spec reviews found Criticals in it**, the last two in the mechanism rather than the drafting.

That design is abandoned. A controlled restart does not need to write from a dying process: `hardRestart` calls `startActivity` and *then* `Runtime.exit(0)` (`MainActivity.kt:1578-1585`), so the app comes back. Phase 3b already ships the pattern for a fact that must outlive a process — a bounded marker, converted to an event at the next startup.

**The crash handler's unbounded append is not fixed here.** Real since 3b, and the marker pattern cannot serve it because a crash is not a controlled restart. Recorded at the end as its own work.

---

## Non-negotiables

1. **The donation flow is not altered.** No new abort condition, no new pre-check, no reordering. Every kind observes something that already happened.
2. **Recording never makes the thing it records worse.**
3. **Nothing is written to the outbox from a thread about to end the process.** That is what markers are for.
4. **The marker store is bounded**, and a restart loop cannot grow it.
5. **Nothing unredacted is written to a file.** `TelemetryRedactor`'s own contract is that an unredacted value must never reach a file *in the first place* — and a marker is a file. Vendor text is scrubbed **before** it is stored, not when it is drained.
6. **A kiosk with `analyticsEnabled` off writes nothing identified to disk.** The marker itself carries no identity; the gate applies at drain.
7. **A kind that repeats on a level, rather than firing on an edge, must be edge-triggered somewhere testable.** This is the rule 3c-i's Bluetooth counter established, and it applies again here.

---

## What already exists and is NOT rebuilt

- `DiagnosticEvents.forKind`, the `identityOf` gate, and 3c-i's three detail builders.
- `DiagnosticReporter.record` — `detail` is a lambda invoked inside the guard.
- `MainActivity.reportDiagnostic` — the asynchronous dispatcher.
- **3b's marker pattern**: `commit()` inside a `try`/`catch`, drained off-main in `drainUpdateDiagnostics`, cleared only after every append returns.
- `DiagnosticKind` — all eleven kinds. **No enum change.**
- `RestartManager` — read, never changed. Note `tryRestart` returns `MAX_RESTARTS` on a **level** test (`RestartManager.kt:75`), and the failure counters clear only on success.

---

## Synthetic activity results: four paths, not one

`onActivityResult` cannot tell a user-driven failure from one the app caused itself, and the app causes four:

| Caller | Call | Lands in | Would report |
|---|---|---|---|
| pairing timeout job (`:1064`) | `finishActivity(2)` | case 2 failure arm | `card_reader_connect_failed` |
| `activateScreensaver` (`:943`) | `finishActivity(2)` | case 2 failure arm | `card_reader_connect_failed` |
| silent-login watchdog (`:1016`) | `finishActivity(1)` | case 1 failure arm | `sumup_reinit_failed` |
| a real user-driven failure | — | either | the same kinds |

So without a discriminator, closing the screensaver reports a reader failure, and a stalled *silent* re-auth reports a `sumup_reinit_failed` carrying `code: -1, message: "Unknown error"` — indistinguishable from a real interactive login failure, and both tick the restart counter.

**The fix is one nullable field**, set immediately before each synthetic `finishActivity`, read and cleared in the arm that receives it:

```kotlin
    /** Set immediately before a finishActivity() the app issues itself, so the
     *  onActivityResult arm that receives it can tell a failure the app caused
     *  from one the operator hit. Read once and cleared. */
    private var syntheticClose: String? = null
```

Values: `"pairing_timeout"`, `"screensaver"`, `"login_watchdog"`. The receiving arm puts it in the diagnostic's detail as `closed_by` and clears the field. Absent `closed_by` therefore means a genuine failure — the discriminator is a *present* field on the synthetic row, never an absent one.

The restart counter still ticks on these paths, and that is correct: a kiosk whose pairing dialog timed out really does have no reader. What changes is that the dashboard can tell why.

`card_reader_page_timeout` still fires as its own row from the timeout job, and the `card_reader_connect_failed` that follows carries `closed_by: pairing_timeout` — so the pair is correlated on the row that would otherwise be double-counted.

---

## The restart path, on markers

Two diagnostics matter at the moment of a restart and both would die with an asynchronous write: `restart_triggered`, and **the failure that crossed the threshold**, which is the more useful of the two.

### `telemetry/PendingDiagnostics.kt` — new, pure

```kotlin
data class PendingDiagnostic(
    val id: String,
    val kind: DiagnosticKind,
    val occurredAtMs: Long,
    val detailJson: String?
)

object PendingDiagnostics {
    const val MAX_ENTRIES = 8
    fun encode(pending: List<PendingDiagnostic>): String
    fun decode(raw: String?): List<PendingDiagnostic>
    fun add(raw: String?, entries: List<PendingDiagnostic>): String
    fun remove(raw: String?, drainedIds: Set<String>): String
}
```

- `add` takes a **list**, so a restart writes both markers in **one** `commit()` rather than two synchronous disk writes on the main thread immediately before a restart.
- `add` keeps the newest `MAX_ENTRIES`. Eight is generous: a restart writes two and the store drains on the very next start.
- **`remove(raw, drainedIds)`, not a blanket clear.** The drain reads, appends, then removes *only the ids it drained*. A blanket `remove` would discard an entry written between the drain's read and its clear — a real window, since a restart can occur while startup is still draining.
- Both the marker write and the drain's clear go through a single `@Synchronized` accessor so the read-modify-write is atomic within the process. Across processes it cannot race: the writer is about to exit and the reader is a fresh start.
- `decode` is **total**: malformed JSON, an unknown `kind` wire string, a missing field, a truncated write — each yields the entries that parse and drops the rest, without throwing. It runs during `onCreate`; a corrupt marker must not stop a kiosk starting.
- The kind is stored by its `wire` string, never its ordinal: an ordinal silently re-maps every stored entry the day a kind is inserted into the enum.
- **`detailJson` is stored already scrubbed** — see non-negotiable #5. The drained event is constructed with `affiliateKey = null`, matching the precedent in `DiagnosticEvents.updateRollback` and `updateInstalled`.

### The decision is a pure unit; `MainActivity` only executes it

The helper must not both decide and write, or its testability — the only reason it exists — is gone. It returns a decision:

```kotlin
data class RestartReport(
    val toMarker: List<PendingDiagnostic>,
    val toReportNow: List<PendingDiagnostic>
)

fun restartReport(
    result: RestartResult,
    causing: PendingDiagnostic,
    reason: String,
    alreadyGaveUp: Boolean,
    nowMs: Long
): RestartReport
```

| `result` | `toMarker` | `toReportNow` |
|---|---|---|
| `RESTART` | causing + `restart_triggered` `{"reason":…, "outcome":"restarted"}` | empty |
| `BELOW_THRESHOLD` | empty | causing |
| `COOLDOWN_ACTIVE` | empty | causing |
| `MAX_RESTARTS`, `alreadyGaveUp = false` | empty | causing + `restart_triggered` `{"reason":…, "outcome":"gave_up"}` |
| `MAX_RESTARTS`, `alreadyGaveUp = true` | empty | causing |

Both `restart_triggered` rows carry an explicit `outcome`, so they differ by a **present** field rather than by one row lacking a key.

### The call sites

**The sample must show the whole edit**, because both sites are the only callers of `handleRestartResult` (`:680`, `:711` → `:1553`) and dropping it deletes auto-restart:

```kotlin
                    val result = restartManager.recordCardReaderFailure()
                    reportRestart(result, DiagnosticKind.CARD_READER_CONNECT_FAILED, detail, "card_reader_failures")
                    handleRestartResult(result, "card_reader_failures")
```

`recordCardReaderFailure()` / `recordReinitFailure()` are called **exactly once**, in the same order, with the same arguments. Hoisting to a local is behaviourally inert — verified, nothing runs between the call and its use — but a double call advances the counter twice and restarts a kiosk early, which is why device check 2 exists.

### `MAX_RESTARTS` must fire on an edge

`tryRestart` returns `MAX_RESTARTS` whenever `restartCount >= maxRestartsBeforeGiveUp`, and the failure counters clear only on success — so **every subsequent failure returns it**, and a naive report emits a row per failure forever. This is exactly the level-versus-edge problem 3c-i's Bluetooth counter solved, and it gets the same treatment.

`RestartManager` gains one key in its existing `KeyValueStore`, `KEY_GAVE_UP_REPORTED`, and one read-only accessor. It is set when a give-up is first reported and cleared by `clearCounters()`, which already runs on a successful payment or connection. The helper receives it as `alreadyGaveUp`; the decision stays in the pure unit.

`COOLDOWN_ACTIVE` stays silent: it is the policy working, and the failure that put the kiosk there is reported through the causing diagnostic.

---

## `checkout_no_reader` ships, on the reader's state rather than the app's belief

An earlier draft dropped this kind. That was right about the predicate it had and wrong about the alternatives.

`isCardReaderConnected` is **inverted in practice**: `disconnectCardReader()` sets it false and runs from the idle screensaver path (`:505`), `activateScreensaver` (`:944`) and the 02:00 reinit (`:1228`), and `prepareCardReader` does not restore it. A kiosk at rest would emit a row on every declined card.

But the app already queries the SDK directly for the reader's real state, in two places (`:686`, `:826`):

```kotlin
ReaderModuleCoreState.Instance()?.mReaderCoreManager?.isCardReaderConnected() == true
```

wrapped in `try`/`catch` returning false. That call is available inside case 3's **already-failed** branch, aborts nothing, and reports the reader rather than the app's belief about it. `checkout_no_reader` is reported when a checkout failed **and** that query says no reader is connected.

It remains an observation, never a gate: `makePayment` gets no reader check, so a stale SDK answer can cost a diagnostic but can never block a donation.

---

## The logcat cleanup

- **`:650`** logs the entire SumUp result `Bundle` (`extras=${data?.extras}`) — every extra, unredacted. `requestCode` and `resultCode` stay.
- **`:678` and `:737`** log the raw `errorMessage`. Two sites, not four; case 2 logs none. They keep `errorCode` and drop the message, which still reaches the operator through the existing Toast.
- **`:717`** logs `Payment successful - TX Code: $txCode` — a SumUp **transaction identifier**. The telemetry design spec promises it is never collected; logcat is not the same as collected, but writing a transaction id to a device log is a gap the disclosure will be read as covering, and nothing needs it.

Logcat only. What telemetry stores is unchanged: vendor text goes into `detail` scrubbed.

---

## What this phase does not change

No `DiagnosticKind` change, no `TelemetryOutbox` change, no `RestartManager` *decision* change (one additive key), no `makePayment` change, no new flush trigger, no change to `DiagnosticReporter.record`.

## The inertness grep after this phase

```bash
grep -rnE '[Oo]utbox\.append\(' app/src/main   # expect 5, unchanged
```

Every kind reaches the outbox through `reportDiagnostic` or the existing drain. `flush()` stays at **1**; `activate()` has **1** real caller plus KDoc mentions at `:1878` and `:1906`. A grep for `DiagnosticKind\.<NAME>` then finds constructions for **all eleven** kinds.

---

## Testing

### JVM-tested

**`PendingDiagnostics`:**
- Round-trip including a null detail; the encoded form contains the kind's **wire** string, so an ordinal implementation fails.
- `add` keeps the newest `MAX_ENTRIES`; `add` of a two-entry list is one operation.
- `remove` drops only the named ids and **preserves an entry added since the read** — the test for the drain race.
- `decode` is total across: malformed JSON, unknown kind, missing field, truncated string, empty, null. None throw.

**`restartReport`:** every row of the table above, including both `MAX_RESTARTS` cases, and that `RESTART` markers carry `outcome: restarted` while the give-up row carries `outcome: gave_up`.

**`RestartManager`:** `KEY_GAVE_UP_REPORTED` is set once, survives further failures, and is cleared by `clearCounters()`. Every existing test passes unmodified.

**The detail builders**, beside 3c-i's: each carries its fields, and `closed_by` appears only when a synthetic close set it.

### Not unit-testable, and labelled as such

The call sites, `syntheticClose`, the marker write, and the drain's new branch. Every decision they rely on is pinned above.

### Device checks

1. Three consecutive card-reader failures → after the restart, **both** a `card_reader_connect_failed` and a `restart_triggered` with `outcome: restarted` have arrived. The markers survived the process exit.
2. **Two** consecutive failures must **not** restart the kiosk. The double-count check; "three → restart" cannot detect one.
3. Pairing page, walk away ten minutes: a `card_reader_page_timeout`, and a `card_reader_connect_failed` carrying `closed_by: pairing_timeout`.
4. Let the screensaver activate while the pairing page is open: `card_reader_connect_failed` with `closed_by: screensaver`, and **no** `card_reader_page_timeout`.
5. Stall a silent re-auth past the watchdog: `sumup_reinit_failed` with `closed_by: login_watchdog`. A real interactive login failure produces one **without** `closed_by`.
6. Exhaust `maxRestartsBeforeGiveUp`: one `restart_triggered` with `outcome: gave_up`. Keep failing: **no further rows** until a success clears the counters.
7. Fail a checkout with the reader physically off: one `checkout_no_reader`. Decline a card with the reader connected: **none**.
8. Take a normal donation with diagnostics queued: it completes normally.
9. With `analyticsEnabled` off, repeat any two: queue depth unchanged.
10. Carried and still unverified: status survives a restart with `droppedCount` intact (2c); a donation appends exactly one row (3a); the disk-full rollback check (3b); the Bluetooth once-per-outage check (3c-i, **without rotating the device**).

---

## Limitations, stated rather than buried

**A marker is lost if the app never comes back.** `hardRestart` starts an activity before exiting, so it normally does.

**`checkout_no_reader` trusts the SDK's answer at one moment.** A stale answer costs a diagnostic, never a donation.

**Two main-thread `commit()` calls become one**, but it is still synchronous disk I/O on the main thread immediately before a restart. Bounded and brief, and the alternative is losing the marker.

**Five call sites beside the payment path**, with only the decisions unit-tested. Mitigated by each site observing values already computed for an existing Toast, and by device check 8.

---

## Decisions

**Markers, not a dying-process write.** Three spec reviews found Criticals in the alternative, two in the mechanism itself.

**`syntheticClose` as a present field.** A discriminator that is an *absent* key cannot be distinguished from an older build that never wrote it.

**`remove(drainedIds)`, not a blanket clear.** A restart during startup drain is reachable, and a blanket clear silently eats it.

**Detail scrubbed before storage.** The redactor's contract is about what reaches a file, and a marker is a file.

**`MAX_RESTARTS` edge-triggered in `RestartManager`.** It is a level test over a counter that only success clears; the same shape as 3c-i's Bluetooth fix, which is precedent worth following rather than re-deriving.

**`checkout_no_reader` reinstated on the SDK query.** Dropping it was right for the predicate it had; the app already had a better one in two other places.

**The sample shows `handleRestartResult`.** Both restart sites are its only callers, and a sample that omits it deletes auto-restart.

---

## Required after this phase

`diagnostic_events` is complete. Remaining: phase 4 (the disclosure screen and eight translations) and phase 5 (documentation). Carried forward:

- **`KioskCrashHandler` uses the ordinary `append`**, reading the whole queue under a shared lock on a dying thread. Real since 3b; the marker pattern cannot serve a crash. It needs the bounded-write work abandoned here, done on its own — the three reviews of that design are on file and name every trap.
- `retryableFailure` is one global boolean; a refused table backs off the healthy ones. Carried from 2a and now the most valuable remaining fix.
- `CrashContext.onOutboxDropped` retains one live Activity until `telemetryStatusStore` stops being Activity-lazy.
- `SettingsBootstrap`'s call site is still untested.
- The 02:00 flush floor is not guaranteed while offline (3a).
