# Telemetry Phase 3c-ii — The Last Diagnostic Kinds

**Goal:** The remaining conditions the kiosk detects and recovers from silently now report themselves — including the auto-restart, which until now took the reason for the restart down with it.

This completes `diagnostic_events`. After it, the remaining telemetry work is phases 4 and 5.

## What this phase is not

An earlier draft of 3c-ii proposed a new outbox mechanism for writing from a dying process: a `ReentrantLock` with timed acquisition, an `appendUrgent` that skipped compaction, a byte cap, and compaction on construction. **Three consecutive spec reviews found Criticals in it**, the last two in the mechanism itself — a slack-gated heal that could never run, and an 8 MB read landing on the main thread in `onCreate`.

That design is abandoned. A controlled restart does not need to write from a dying process at all: `hardRestart` calls `startActivity` and *then* `Runtime.exit(0)`, so the app comes back. Phase 3b already established the pattern for a fact that must outlive a process — record a cheap bounded marker, convert it to an event at the next startup — and `update_rollback` has shipped on it.

**The crash handler's unbounded append is not fixed here.** It has had that cost since 3b, it is real, and the marker pattern cannot serve it because a crash is not a controlled restart. It is recorded at the end as its own future work rather than bolted onto a phase that touches the payment path.

---

## Non-negotiables

1. **The donation flow is not altered.** No new abort condition, no new pre-check, no reordering. Every kind here observes something that already happened. A kiosk with this installed takes payments identically to one without it.

2. **Recording never makes the thing it records worse.** Same rule as 3b and 3c-i, now on paths where being wrong costs a donation.

3. **Nothing is written to the outbox from a thread that is about to end the process.** That is what the markers are for.

4. **The marker store is bounded.** A restart loop must not grow it.

5. **No *known* secret reaches disk**, and **no transaction identifier reaches logcat** — see the cleanup section.

6. **A kiosk with `analyticsEnabled` off writes nothing identified to disk.**

---

## What already exists and is NOT rebuilt

- `DiagnosticEvents.forKind` + the `identityOf` gate + the three detail builders from 3c-i.
- `DiagnosticReporter.record` — the guarded body; `detail` is a lambda invoked **inside** the guard.
- `MainActivity.reportDiagnostic` — the asynchronous dispatcher over `record`.
- **The 3b marker pattern**: `UpdateWatchdogReceiver` writes `KEY_ROLLBACK_AT` with `commit()` inside a `try`/`catch`, and `MainActivity.drainUpdateDiagnostics` converts it at startup, off the main thread, clearing the marker only after every append returns.
- `DiagnosticKind` — all eleven kinds with severities. **No enum change.**
- `RestartManager` — `recordCardReaderFailure()`, `recordReinitFailure()`, `RestartResult`. **Read, never changed.**

---

## The restart path, on markers

`handleRestartResult`'s `RESTART` arm calls `hardRestart`, which calls `Runtime.exit(0)` immediately. Two diagnostics matter at that moment and both would be lost to an asynchronous write:

- `restart_triggered` itself;
- **the failure that crossed the threshold** — the `card_reader_connect_failed` or `sumup_reinit_failed` that caused it. That one is the more useful of the two, and the earlier draft would have dropped it.

Both are written as markers before `hardRestart`, and drained at the next startup.

### `telemetry/PendingDiagnostics.kt` — new, pure

A small list of diagnostics owed, encoded as one string in the prefs file `UpdateWatchdogReceiver` already owns.

```kotlin
data class PendingDiagnostic(
    val kind: DiagnosticKind,
    val occurredAtMs: Long,
    val detailJson: String?
)

object PendingDiagnostics {
    const val MAX_ENTRIES = 8
    fun encode(pending: List<PendingDiagnostic>): String
    fun decode(raw: String?): List<PendingDiagnostic>
    fun add(raw: String?, entry: PendingDiagnostic): String
}
```

- `add` appends and **keeps the newest `MAX_ENTRIES`**, so a restart loop cannot grow the store. Eight is generous: a restart writes at most two, and the store is drained on the very next start.
- `decode` is **total**: malformed JSON, an unknown `kind` string, a missing field, or a truncated write all yield the entries that do parse and silently drop the rest. A corrupt marker must never stop the app from starting, and must never throw on a path that runs during `onCreate`.
- `encode`/`decode` round-trip. The kind is stored by its `wire` string, not its ordinal — an ordinal would silently re-map every stored entry the day a kind is inserted into the enum.

This is pure and JVM-tested, which is the whole reason it is a separate unit rather than JSON assembled at the call site.

### Writing the markers

Both restart sites become:

```kotlin
val result = restartManager.recordCardReaderFailure()
reportRestartDiagnostics(result, DiagnosticKind.CARD_READER_CONNECT_FAILED, detail, "card_reader_failures")
```

where the helper writes markers when `result == RestartResult.RESTART` and otherwise reports the causing diagnostic in the ordinary asynchronous way.

**`recordCardReaderFailure()` and `recordReinitFailure()` must still be called exactly once per failure.** Hoisting the call into a local is behaviourally inert — verified, nothing runs between the call and its use — but a double call would advance the counter twice and restart a kiosk early. The hoist is therefore not a free refactor and needs a test, which is why the decision lives in a helper rather than inline: a pure unit can be handed a `RestartResult` and asked what to do with it.

The marker write is `commit()` inside a `try`/`catch (Throwable)`, for the reason 3b's rollback marker is: this runs immediately before a restart, and a throw must not be what prevents it.

### Draining them

`drainUpdateDiagnostics` gains the pending list alongside the two update markers it already handles — same method, same off-main dispatch, same catch-all, same rule that the marker is cleared only after every append returns.

Order: pending diagnostics first, then the update markers, so a reader scanning by insertion order sees the restart before whatever the restarted build reports.

---

## The other kinds

| Kind | Severity | Site | Detail |
|---|---|---|---|
| `restart_triggered` | error | marker, written in `handleRestartResult`'s `RESTART` arm before `hardRestart` | `{"reason": <reason>}` |
| `sumup_reinit_failed` | error | `onActivityResult` case 1 login-failed branch — marker when it triggers a restart, async otherwise | `{"code": <int>, "message": <vendor text>}` |
| `card_reader_connect_failed` | warn | `onActivityResult` case 2 not-connected branch — same rule | `{"code": <int>, "message": <vendor text>}` |
| `card_reader_page_timeout` | warn | the 10-minute pairing job — **see below** | `null` |
| `restart_triggered` (gave up) | error | `MAX_RESTARTS` arm | `{"reason": <reason>, "outcome": "gave_up"}` |

Each detail shape gets a builder in `DiagnosticEvents`, beside 3c-i's three. No `JsonObject` is assembled in `MainActivity` — that is the rule 3c-i established and the reason its builders exist.

### `card_reader_page_timeout` fires *through* another kind

The timeout job calls `finishActivity(2)`, which lands in `onActivityResult` case 2's failure arm. So a timeout would emit `card_reader_page_timeout` **and** `card_reader_connect_failed`, and advance the restart counter — two rows and a counter tick where the earlier draft claimed one row.

That is not a bug to suppress. The reader genuinely did fail to connect, and the counter genuinely should advance: an operator who walks away from the pairing dialog has left a kiosk with no reader. But the two rows must be **correlated**, or a dashboard counts one incident twice. The timeout's detail carries nothing today; it gains `{"closed_by": "timeout"}`, and the `card_reader_connect_failed` that follows within the same interaction is expected. The spec says so rather than leaving a reader to guess.

### `MAX_RESTARTS` and `COOLDOWN_ACTIVE`

`handleRestartResult` has four arms and the earlier draft reported from one. `MAX_RESTARTS` is the worst state a kiosk reaches — it has given up restarting itself and is sitting there broken — and it reported nothing at all. It now reports, as an ordinary asynchronous write since nothing is about to exit.

`COOLDOWN_ACTIVE` stays silent: it is a normal, transient part of the restart policy, and a kiosk in cooldown reports the failure that put it there through the causing diagnostic.

### `checkout_no_reader` is dropped

The master spec lists it. It does not ship, and this is a deliberate reversal of an earlier decision.

The predicate proposed for it — `isCardReaderConnected` false at checkout failure — is **inverted in practice**. `disconnectCardReader()` sets that flag false and runs from the idle screensaver path (`MainActivity.kt:505`), from `activateScreensaver` (`:944`) and from the 02:00 reinit (`:1228`), and `prepareCardReader` does not restore it. So a kiosk at rest has the flag false and would emit a warn row on **every declined card**, while a reader genuinely lost shortly after pairing would emit none. The device check written for it would have passed, because it ran straight after pairing.

The alternatives are worse: keying off SumUp's error codes means none of them say "no reader", and adding a real reader check to `makePayment` puts a new abort condition on the donation path, which non-negotiable #1 forbids and which the repo owner already declined.

A kind that fires on the wrong events is worse than a missing kind, because a dashboard cannot tell it is wrong. If a reliable signal appears later — a reader-state callback from the SDK, say — this is a one-line addition on the mechanism this phase leaves in place.

---

## The logcat cleanup

This phase is in these branches anyway, and there is a real exposure in them.

- **`MainActivity.kt:650`** logs the entire SumUp result `Bundle`: `extras=${data?.extras}`. Every extra the SDK returned, unredacted. `requestCode` and `resultCode` stay — that is the part anyone debugging reads, and neither is sensitive.
- **`:678` and `:737`** log the raw `errorMessage`. Two sites, not four: case 2 logs none. They keep `errorCode` and drop the raw message, which still reaches the operator through the existing Toast.
- **`:716`** logs `Payment successful - TX Code: $txCode`. A SumUp **transaction identifier**, which the master spec and the customer disclosure both promise is never collected. It goes.

This is a logcat change only. What telemetry stores is unchanged: vendor text still goes into `detail`, where `TelemetryEvent.Diagnostic`'s constructor scrubs it.

---

## What this phase does not change

- No `DiagnosticKind` enum change, no new outbox mechanism, no `TelemetryOutbox` change at all.
- No change to `RestartManager` or to any recovery decision.
- No change to `makePayment` or any donation path.
- No new flush trigger.
- `DiagnosticReporter.record` keeps its current contract, including rethrowing `CancellationException`.

## The inertness grep after this phase

```bash
grep -rnE '[Oo]utbox\.append\(' app/src/main   # expect 5, unchanged
```

Every new kind reaches the outbox through `reportDiagnostic` or through the existing startup drain. `flush()` callers stay at **1**; `activate()` has **1** real caller plus a KDoc mention. After this phase a grep for `DiagnosticKind\.<NAME>` finds constructions for **ten** of eleven kinds — `CHECKOUT_NO_READER` is the deliberate absence above.

---

## Testing

### JVM-tested

**`PendingDiagnostics`:**
- `encode`/`decode` round-trip, including a null detail.
- `add` keeps the newest `MAX_ENTRIES` and drops the oldest — pin it by adding more than the cap.
- `decode` is total: malformed JSON, an unknown `kind` wire string, a missing field, a truncated string, and an empty/null input each return what parses and drop the rest, **without throwing**.
- The kind round-trips by `wire` string, not ordinal: a test asserts the encoded form contains the wire name, so an ordinal-based implementation fails it.

**The restart decision helper:**
- `RESTART` yields markers for both the causing kind and `restart_triggered`.
- `BELOW_THRESHOLD` and `COOLDOWN_ACTIVE` yield an ordinary report of the causing kind and no marker.
- `MAX_RESTARTS` yields an ordinary report of the causing kind **and** of `restart_triggered` with `outcome: gave_up`.
- The helper is handed a `RestartResult` and never calls `RestartManager` itself — so it cannot be the thing that double-counts.

**The detail builders**, beside 3c-i's: each carries its fields, and `card_reader_page_timeout` carries `closed_by: timeout`.

**`DiagnosticEvents.forKind`** already covers every kind through its table-driven tests; no new gate test is needed.

### Not unit-testable, and labelled as such

The call sites, the marker write, and the drain's new branch. `MainActivity` needs the Android lifecycle. What compensates: every decision is in `PendingDiagnostics` or the restart helper, and the sites are one line plus a hoisted local at two of them.

**The hoist is the one risky edit**, because a double call to `recordCardReaderFailure()` restarts a kiosk early and no unit test can see `MainActivity`. Device check 2 exists for it.

### Device checks

1. Force three consecutive card-reader failures to trigger an auto-restart. After the restart, **both** a `card_reader_connect_failed` and a `restart_triggered` must have arrived — the check that the markers survived the process exit.
2. **Two** consecutive failures must **not** restart the kiosk. This is the double-count check; "three failures → restart" cannot detect one.
3. Open the pairing page and walk away for ten minutes: a `card_reader_page_timeout` with `closed_by: timeout`, followed by a `card_reader_connect_failed` — two rows, expected, correlated.
4. Fail a SumUp login after a reinit without reaching the threshold: one `sumup_reinit_failed`, no restart, no marker left behind.
5. Exhaust `maxRestartsBeforeGiveUp`: a `restart_triggered` with `outcome: gave_up`.
6. Take a normal donation with diagnostics queued: it completes normally. Regression check for non-negotiable #1.
7. With `analyticsEnabled` off, repeat any two: queue depth unchanged.
8. Carried and still unverified: status survives a restart with `droppedCount` intact (2c); a donation appends exactly one row (3a); the disk-full rollback check (3b); the Bluetooth once-per-outage check (3c-i, **without rotating the device**).

---

## Limitations, stated rather than buried

**A marker is lost if the app never comes back.** `hardRestart` starts an activity before exiting, so it normally does; a kiosk that dies for good reports nothing. Accepted — the alternative is the dying-process write this phase deliberately abandoned.

**`checkout_no_reader` does not ship.** Reasoned above.

**A pairing timeout produces two rows.** By design, correlated by `closed_by`.

**Five new call sites beside the payment path, and only the decisions are unit-tested.** Mitigated by each site observing values already computed for an existing Toast, and by device check 6.

---

## Decisions

**Markers, not a dying-process write.** A controlled restart comes back, and 3b already ships this pattern. Three spec reviews found Criticals in the alternative, two of them in the mechanism rather than the drafting.

**The kind is stored by wire string.** An ordinal would silently re-map every stored entry the day a kind is inserted into the enum.

**`decode` is total.** It runs during `onCreate`; a corrupt marker must not stop a kiosk from starting.

**The restart decision goes in a pure helper.** It is the only way a test can see that `recordCardReaderFailure()` is called once, and a double call restarts a kiosk early.

**`MAX_RESTARTS` reports; `COOLDOWN_ACTIVE` does not.** One is a kiosk that has given up; the other is the policy working.

**`checkout_no_reader` is dropped rather than shipped wrong.** A kind that fires on every declined card is worse than a missing one, because nothing downstream can tell it is wrong.

**The TX-code log goes.** It is a transaction identifier in logcat, and two documents promise it is never collected.

---

## Required after this phase

`diagnostic_events` is complete apart from the deliberate omission. Remaining: phase 4 (the disclosure screen and its eight translations) and phase 5 (documentation). Carried forward:

- **`KioskCrashHandler` appends with the ordinary `append`**, which reads the whole queue under a shared lock on a dying thread — up to ~5,000 rows and a wait behind a flush's rewrite. Real since 3b. The marker pattern cannot serve it, so it needs the bounded-write work that this phase abandoned, done properly and on its own: the three reviews of that design are on file and name every trap.
- `retryableFailure` is still one global boolean; a refused table backs off the healthy ones. Carried from 2a, and now that eleven kinds enqueue it stalls more than it used to. The most valuable remaining fix.
- `CrashContext.onOutboxDropped` retains one live Activity; it retires when `telemetryStatusStore` stops being Activity-lazy.
- `SettingsBootstrap`'s call site is still untested.
- The 02:00 flush floor is not guaranteed while offline (3a).
