# Telemetry Phase 3b — Diagnostic Events

**Goal:** A kiosk that crashes, reverts a bad update, or comes back on a new build says so, without anyone being there to notice.

Phase 3a ended inertness for donations. This phase adds the first three diagnostic kinds — `crash`, `update_rollback`, `update_installed` — and the machinery to record something at a moment when the app is in no position to do work.

**Scope is deliberately three of eleven kinds.** The other eight (`restart_triggered`, `sumup_reinit_failed`, `card_reader_connect_failed`, `card_reader_page_timeout`, `checkout_no_reader`, `bluetooth_watchdog_fired`, `network_outage`, `update_install_failed`) are 3c. They touch the card-payment and pairing paths, which is code where a mistake costs donations; this phase touches neither.

---

## Non-negotiables

1. **The crash handler must not swallow the crash.** `hardRestart` and the update watchdog both depend on normal crash behaviour — the watchdog's entire rollback decision is "did the new build write a heartbeat before dying". A handler that returns instead of chaining turns a crash into a hung kiosk and a rollback into a silent failure.
2. **Recording must never be the reason a crash is worse.** Every step of recording is wrapped. A failure to record is dropped on the floor; chaining happens regardless.
3. **No secret reaches disk.** A stack trace can contain the SumUp affiliate key. It is scrubbed and truncated before it is written, not after it is read.
4. **A kiosk with `analyticsEnabled` off writes nothing identified to disk** — the same promise `DonationEvents` makes. Not "writes and declines to send".
5. **The outbox is the only durable queue.** No second store, no journal, no parallel drain path.
6. **The donation flow is untouched.** This phase adds no code to any path a donation travels.

---

## What already exists and is NOT rebuilt

From phases 1–3a. Read these; do not reimplement them.

- `TelemetryEvent.Diagnostic` — the event type, complete. Takes `kind`, `occurredAtIso`, `detailJson`, `stackTrace`, `affiliateKey`. **Scrubs and truncates in its constructor**, so "redacted" is an invariant of a constructed instance rather than something a serialiser remembers to do. Deliberately not a data class, so no generated `toString` can print the key.
- `DiagnosticKind` — all eleven kinds, with their severities. 3b wires three of them; the enum needs no change.
- `TelemetryRedactor.scrub` / `.truncate` — already applied by the constructor above.
- `TelemetryOutbox.append` — durable, file-keyed lock. Its own KDoc anticipates this phase: *"phase 2's crash handler may construct its own TelemetryOutbox over the same path, and two instances holding separate monitors would let a compaction silently overwrite a concurrent append."*
- `EventIdentity.from(settings, appVersion)` — identity construction.
- `DonationEvents` — the pattern this phase follows: every decision in a pure unit, because its caller sits in `MainActivity`, which no unit test can reach.

---

## The crash handler

### `telemetry/KioskCrashHandler.kt` — new

Implements `java.lang.Thread.UncaughtExceptionHandler`. No Android imports, so it is fully JVM-testable — chaining included.

```kotlin
class KioskCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val outbox: TelemetryOutbox,
    private val settings: () -> Settings,
    private val affiliateKey: () -> String?,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exitProcess: (Int) -> Unit = { Runtime.getRuntime().exit(it) }
) : Thread.UncaughtExceptionHandler
```

**Suppliers, not snapshots.** `settings` and `affiliateKey` are functions because both change after the handler is installed: the handler goes in early in `onCreate`, while the affiliate key arrives when the secret store finishes loading, and an operator can toggle `analyticsEnabled` mid-session. A snapshot taken at install time would report a crash the operator has since opted out of, or fail to scrub a key that was not loaded yet.

**`appVersion` is a value, not a supplier** — it cannot change within a process.

### What it does, in order

1. **Record, guarded.** Inside `try`/`catch (Throwable)`:
   - Ask `DiagnosticEvents.crash(...)` for an event. If analytics is off it returns `NotEnabled` and nothing is written.
   - `outbox.append(event.id, event.table, event.payloadJson())`.
   - The payload is already scrubbed and truncated by the `Diagnostic` constructor, so the bytes that reach disk carry no affiliate key.
2. **Chain, always.** In a `finally`:
   - `previous != null` → `previous.uncaughtException(thread, throwable)`. This is the real path: Android's `RuntimeInit` installs a default handler before any app code runs, so `previous` is non-null on a device.
   - `previous == null` → `exitProcess(2)`. Returning normally from an uncaught-exception handler does **not** end the process; it ends the thread. On the main thread that leaves a kiosk that is running, unresponsive, and showing the last frame it drew — strictly worse than a crash, because the watchdog's heartbeat logic cannot see it either. Exiting is the honest outcome.

### Re-entrancy

If recording itself throws and that throw somehow re-enters the handler, the guard must not recurse. A single `@Volatile` boolean, set before recording and never cleared: a second entry skips straight to chaining. The process is dying; there is no second crash worth reporting.

### Installation

`MainActivity.onCreate`, early — but `onCreate` runs again on every Activity recreation, and installing a second handler whose `previous` is the first would build a chain that grows with every rotation or recreation. Install once: capture `Thread.getDefaultUncaughtExceptionHandler()`, and skip if it is already a `KioskCrashHandler`.

---

## The two update kinds

Neither can be reported at the moment it happens. The rollback receiver runs in a process about to be replaced by the old APK; "this build is new" is only knowable once the new build runs. Both are therefore recorded as a fact in `update_state` prefs — the file `UpdateWatchdogReceiver` already owns and already writes — and converted into events at the next startup.

This is not a second queue. Nothing is serialised, nothing is drained in order, and neither marker survives being read.

### `update_rollback`

`UpdateWatchdogReceiver` writes `KEY_ROLLBACK_AT = clock()` on the path where it has decided to roll back — after confirming the backup APK exists, **before** invoking the installer. Before, so a process that dies mid-install still leaves the fact behind.

This reports that a rollback was **attempted**, not that it succeeded. The receiver cannot know the outcome: the install it is waiting on replaces the very process waiting for it. That limitation is stated on the event, not hidden — `detail` carries `{"outcome": "attempted"}` so a future phase can add a confirmed variant without the dashboard silently reinterpreting old rows.

### `update_installed`

`update_state` gains `KEY_REPORTED_VERSION`, the `versionName` this kiosk last started under.

At startup, compare it with `BuildConfig.VERSION_NAME`:

| Stored | Current | Outcome |
|---|---|---|
| empty | anything | **Seed and report nothing.** A first run is an installation, not an update. |
| same | same | Nothing. |
| different | different | Report `update_installed`, `detail = {"from": <stored>, "to": <current>}`. |

**The stored version always advances, even when analytics is off.** Only the reporting is gated. If the value were held back while disabled, an operator enabling analytics months later would trigger a false "this kiosk just updated" for a build it had been running since spring.

### Startup wiring

In `MainActivity.onCreate`, off the main thread, after telemetry is constructible:

1. Read both markers.
2. Ask `DiagnosticEvents` for the events they imply.
3. Append each to the outbox.
4. Clear `KEY_ROLLBACK_AT`; write the new `KEY_REPORTED_VERSION`.

Order matters: the marker is cleared **after** the append returns. An append that throws leaves the marker in place and the event is reported on the next startup instead. Duplicating a diagnostic is cheap; losing the only record that a kiosk reverted an update is not.

No flush is triggered here. 3a's four triggers already drain the queue, and a startup flush would put network work on the path that renders the first frame.

---

## `telemetry/DiagnosticEvents.kt` — new, pure

Every decision lives here, for the same reason as `DonationEvents`: the callers are `MainActivity` and a `BroadcastReceiver`, and neither is reachable from a unit test.

```kotlin
sealed class DiagnosticEventResult {
    object NotEnabled : DiagnosticEventResult()
    object NothingToReport : DiagnosticEventResult()
    data class Report(val event: TelemetryEvent.Diagnostic) : DiagnosticEventResult()
}

object DiagnosticEvents {
    fun crash(settings, appVersion, throwable, affiliateKey, occurredAtMs): DiagnosticEventResult
    fun updateRollback(settings, appVersion, rollbackAtMs): DiagnosticEventResult
    fun updateInstalled(settings, appVersion, storedVersion, occurredAtMs): DiagnosticEventResult
}
```

`NothingToReport` is distinct from `NotEnabled` on purpose: "no version change" and "operator declined analytics" are different facts, and the version tracker must advance in the second case but has nothing to advance in the first.

`crash` takes the `Throwable` rather than a pre-rendered string so the rendering (`stackTraceToString`) is inside the tested unit rather than at the untestable call site.

---

## The inertness grep changes shape again

3a's grep expected exactly 2 `outbox.append` callers. 3b makes it **4**:

| Caller | Kind |
|---|---|
| `TelemetryManager` | the activation row |
| `MainActivity` donation path | `donation` |
| `MainActivity` startup diagnostics | `update_rollback`, `update_installed` |
| `KioskCrashHandler` | `crash` |

`flush()` callers stay at **1** (3a's dispatcher). `activate()` callers stay at **1** (the Test button). Nothing in this phase flushes.

A grep must still show **zero** callers for the eight kinds deferred to 3c.

---

## Testing

### JVM-tested

`DiagnosticEvents`:
- Analytics off → `NotEnabled` for all three, and nothing constructed.
- `crash` renders the throwable's stack trace, and the constructed event carries a scrubbed trace when the affiliate key appears in it.
- `updateInstalled`: empty stored → `NothingToReport`; identical → `NothingToReport`; changed → `Report` with both `from` and `to` in `detail`.
- `updateRollback` carries `outcome: attempted`.
- Severity and `kind` wire strings match the spec's table.

`KioskCrashHandler`:
- Chains to the previous handler, with the same thread and throwable it received.
- Chains **even when recording throws** — the decisive test. Inject an outbox whose `append` throws; assert the previous handler still ran.
- Chains when analytics is off (nothing recorded, crash still propagates).
- `previous == null` → `exitProcess(2)` called; no silent return.
- Re-entrant invocation records once and chains once.
- Appends exactly one row on a normal crash.

### Not unit-testable, and labelled as such

- The `MainActivity.onCreate` installation and startup drain.
- `UpdateWatchdogReceiver`'s marker write.

Both are wiring over decisions that are tested elsewhere. Neither computes anything.

### Device checks

Nothing below is covered by a unit test.

1. Force an uncaught exception. The app must restart exactly as it does today — the crash handler changes nothing an operator can see.
2. After that crash, the analytics screen shows one more queued row, and the row arrives with a scrubbed stack trace.
3. Crash with `analyticsEnabled` off: queue depth unchanged, restart behaviour unchanged.
4. Install a build with a different `versionName`. Exactly one `update_installed` arrives, with the correct `from`/`to`. Restarting the app again produces no second one.
5. A first install on a wiped device produces **no** `update_installed`.
6. Force the watchdog rollback path with a backup APK present. An `update_rollback` row exists after the rolled-back build comes up — **if** that build has telemetry (see Limitations).
7. Carried over and still unverified: status survives a process restart with `droppedCount` intact (2c), and a donation appends exactly one row (3a).

---

## Limitations, stated rather than buried

**A rollback to a pre-telemetry build strands its own report.** The `update_rollback` row is appended by the new build before it is replaced. If the APK it reverts to predates telemetry, nothing reads the outbox until some later telemetry-capable version installs — at which point the row arrives, correctly stamped but weeks late. Not worth engineering around: the alternative is sending from a process that is about to be overwritten.

**`update_rollback` reports intent, not outcome.** Explained above and encoded in `detail.outcome`.

**A crash during the first moments of `onCreate` is not reported.** The handler cannot be installed before the outbox is constructible. The window is small and is the same window in which `Settings` is not loaded either, so there would be no identity to report under.

**The crash row is written but not sent by the dying process.** It leaves on the next flush — screensaver, network restored, or 02:00. A kiosk that crashes and never comes back never reports the crash. Sending from a dying process was considered and rejected: network I/O on a handler the system is already tearing down would delay the chain to `previous`, which is what restarts the kiosk.

---

## Decisions

**No diagnostic journal.** An earlier draft of this design proposed a separate append-only file per detection site, drained at startup. It was wrong twice over: `TelemetryOutbox` is already exactly that, its file-keyed lock was built for this caller, and the durable part of `append` (`file.appendText`) completes before the `compactIfNeeded` read that made the append look expensive. A second store would also have needed its own stack-trace scrubbing — a second place to get the affiliate key wrong, for no gain.

**Markers in `update_state`, not in the outbox.** The two update kinds are not exceptions to "the outbox is the only queue": a boolean and a version string in a prefs file that already exists are facts about the device, not queued events. Making them outbox rows would mean constructing `Settings` and identity inside a `BroadcastReceiver` running in a doomed process.

**Gate at construction, advance regardless.** Reporting is gated on `analyticsEnabled`; the stored version always advances. Anything else manufactures a false update event the first time an operator opts in.

**Chain in `finally`, not at the end of the happy path.** The single most important line in this phase is the one that runs when everything else has failed.

---

## Out of scope

- The eight remaining diagnostic kinds (3c).
- `retryableFailure` is still one global boolean; a refused table backs off the healthy ones. Carried from 2a.
- `SettingsBootstrap`'s call site is still untested and needs instrumented coverage.
- `formatTimestamp` in `AnalyticsSettingsScreen.kt` remains the one computation in the untestable file (M5, recorded in 2c).
- The 02:00 floor is still not guaranteed — `scheduleDailyLoginReset` does not re-arm itself while offline (recorded in 3a, deliberately not fixed there or here: that scheduler is the card-payment recovery path).

## Required of 3c

- The eight remaining kinds, most of which sit on the card-reader and SumUp paths.
- A decision on whether `update_install_failed` can be reported from the installer's failure callback, which runs in the same doomed-process conditions as the rollback.
