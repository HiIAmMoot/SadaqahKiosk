# Telemetry Phase 3b — Diagnostic Events

**Goal:** A kiosk that crashes, reverts a bad update, or comes back on a new build says so, without anyone being there to notice.

Phase 3a ended inertness for donations. This phase adds the first three diagnostic kinds — `crash`, `update_rollback`, `update_installed` — and the machinery to record something at a moment when the app is in no position to do work.

**Scope is deliberately three of eleven kinds.** The other eight (`restart_triggered`, `sumup_reinit_failed`, `card_reader_connect_failed`, `card_reader_page_timeout`, `checkout_no_reader`, `bluetooth_watchdog_fired`, `network_outage`, `update_install_failed`) are 3c. They sit on the card-payment and pairing paths; this phase touches neither.

---

## Non-negotiables

1. **The crash handler must not swallow the crash.** `hardRestart` and the update watchdog both depend on normal crash behaviour — the watchdog's entire rollback decision is "did the new build write a heartbeat before dying". A handler that returns instead of chaining turns a crash into a hung kiosk and a rollback into a silent failure.

2. **Recording must never make the thing it records worse.** This phase writes to **two** critical paths, and both get the same treatment:
   - `KioskCrashHandler.uncaughtException` — every step wrapped; chaining happens regardless.
   - **`UpdateWatchdogReceiver.onReceive`** — the rollback decision path. It currently has no try/catch anywhere before `goAsync()` (`UpdateWatchdogReceiver.kt:26-52`), so an unguarded `SharedPreferences` write inserted there can throw on a full disk and abort `onReceive` **before the rollback is attempted at all**. A kiosk that has been offline for a month accumulating an outbox is exactly the kiosk with a full disk. Telemetry causing a bad build to be kept forever is the worst outcome this app has.

3. **No *known* secret reaches disk.** `TelemetryEvent.Diagnostic` scrubs and truncates in its constructor. `TelemetryRedactor.scrub` removes the affiliate key by exact case-insensitive match, and runs of 32+ `[A-Za-z0-9+=_-]` as a backstop. A secret of an unanticipated shape embedded in an exception message — a short password in a `user:pass@host` URL, say — is **not** covered. That is accepted and stated here so no later phase leans on a stronger claim than the code supports.

4. **A kiosk with `analyticsEnabled` off writes nothing identified to disk** — the same promise `DonationEvents` makes. Not "writes and declines to send".

5. **The outbox is the only durable queue.** No second store, no journal, no parallel drain path.

6. **The donation flow is untouched.** This phase adds no code to any path a donation travels.

---

## What already exists and is NOT rebuilt

From phases 1–3a. Read these; do not reimplement them.

- `TelemetryEvent.Diagnostic` — the event type, complete. **Scrubs and truncates in its constructor** (`TelemetryEvent.kt:119-137`), scrub-then-truncate so a cut cannot leave half a key. Raw trace and key are constructor parameters, not properties, so they cannot be read back. Deliberately not a data class; `toString` is hand-written to exclude both.
- `DiagnosticKind` — all eleven kinds with their severities. 3b wires three; the enum needs no change.
- `TelemetryRedactor.scrub` / `.truncate` — already applied by the constructor above.
- `TelemetryOutbox.append` — durable, file-keyed lock. Its KDoc anticipates this phase: *"phase 2's crash handler may construct its own TelemetryOutbox over the same path…"*
- `EventIdentity.from(settings, appVersion)`.
- `DonationEvents` — the pattern this phase follows: every decision in a pure unit, because its callers sit in `MainActivity` and a `BroadcastReceiver`, neither reachable from a unit test.

---

## The crash handler

### Where the handler reads its inputs

`settings` and `affiliateKey` are per-instance `mutableStateOf` fields on `MainActivity` (`:190`, `:192`). `MainActivity` declares no `android:configChanges` (`AndroidManifest.xml:51-55`), so it is **recreated on configuration change** — and the uncaught-exception handler is process-global. A handler installed once holding a reference to the first Activity would, after any recreation, read a dead instance: an operator who turns analytics off would still get a crash row, breaching non-negotiable #4 through the very mechanism meant to keep the reads fresh.

So the handler reads from a **process-scoped holder**, not from an Activity:

```kotlin
object CrashContext {
    @Volatile var settings: Settings? = null
    @Volatile var affiliateKey: String? = null
}
```

Every `onCreate` refreshes it; the handler reads it. Plain `@Volatile` rather than Compose snapshot state is deliberate — a snapshot read from a dying thread could in principle block on the snapshot lock, and a blocked crash handler is the hung kiosk non-negotiable #1 exists to prevent. It also removes the Activity leak.

### `telemetry/KioskCrashHandler.kt` — new

Implements `java.lang.Thread.UncaughtExceptionHandler`. No Android imports, so chaining is JVM-testable.

```kotlin
class KioskCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val outbox: TelemetryOutbox,
    private val settings: () -> Settings?,
    private val affiliateKey: () -> String?,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exitProcess: (Int) -> Unit = { Runtime.getRuntime().exit(it) }
) : Thread.UncaughtExceptionHandler
```

`settings` returns nullable: before the first `onCreate` populates `CrashContext` there is no identity to report under, and a null means "record nothing".

**The handler owns its outbox, constructed eagerly at install time.** This matches 3a's "Required of 3b" wording and avoids a real hazard: `MainActivity.telemetryOutbox` is `by lazy` (`:128`), so sharing it would let a crash be the thing that triggers initialisation, and `lazy`'s default `SYNCHRONIZED` mode can block if another thread is mid-init. The file-keyed lock makes two instances over one path safe.

### What it does, in order

1. **Record, guarded.** Inside `try` / `catch (Throwable)` — with the **supplier calls inside the guard**, since `settings()` and `affiliateKey()` can themselves throw:
   - Set the re-entrancy flag.
   - `DiagnosticEvents.crash(...)`; `NotEnabled` or a null `Settings` means nothing is written.
   - `outbox.append(event.id, event.table, event.payloadJson())`.
2. **Chain, always.** In a `finally`:
   - `previous != null` → `previous.uncaughtException(thread, throwable)`. This is the real path: Android's `RuntimeInit` installs a default handler before any app code runs.
   - `previous == null` → `exitProcess(2)`. Returning normally from an uncaught-exception handler ends the *thread*, not the process; on the main thread that leaves a kiosk running, unresponsive, and showing its last frame — worse than a crash, because the watchdog's heartbeat logic cannot see it either. (`Runtime.exit` runs shutdown hooks and can in principle hang where `Runtime.halt` cannot; `exit` is kept for consistency with `hardRestart` (`MainActivity.kt:1499`) and because this branch is near-unreachable on a device.)

**Chaining is never suppressed**, including on re-entry. On a device the first chain kills the process so a second call cannot happen; the only place a double chain is observable is a test, and guarding it would put the one thing this phase must not break behind a condition.

### Re-entrancy

A single `@Volatile` boolean, set before recording and never cleared. A re-entrant call skips recording and goes straight to chaining. Never clearing is correct: on Android any uncaught exception reaching the default handler is fatal. `@Volatile` gives visibility but not atomicity, so two threads crashing simultaneously can both record — that yields two wanted rows, and the outbox tolerates the interleave.

### `crash` detail

`{"thread": <name>, "main": <bool>}`. The first thing anyone triaging a crash wants, free to collect, and not sensitive.

### Installation

`MainActivity.onCreate`. Install once — capture `Thread.getDefaultUncaughtExceptionHandler()` and skip if it is already a `KioskCrashHandler` — so recreation cannot build a chain that grows on every rotation. Freshness comes from `CrashContext`, which every `onCreate` refreshes whether or not it installs.

---

## The two update kinds

Neither can be reported when it happens. The rollback receiver runs in a process about to be replaced by the old APK; "this build is new" is only knowable once the new build runs. Both are recorded as a fact in `update_state` prefs — the file `UpdateWatchdogReceiver` already owns — and converted into events at the next startup.

This is not a second queue: nothing is serialised, nothing is drained in order, and neither marker survives being read.

### `update_rollback`

`UpdateWatchdogReceiver` records two values on the path where it has decided to roll back — after confirming the backup APK exists, **before** invoking the installer:

- `KEY_ROLLBACK_AT` = now
- `KEY_ROLLBACK_FROM_VERSION` = `BuildConfig.VERSION_NAME` — the build being rolled back *from*

Three requirements, all load-bearing:

- **Wrapped in its own `try`/`catch (Throwable)`**, failure logged and dropped. See non-negotiable #2: an escape here means the rollback never happens.
- **`commit()`, not `apply()`.** The next thing this receiver does is hand the process to an installer that will replace it. `apply()`'s asynchronous write has no guarantee of landing first, which would silently defeat the "before, so a death mid-install still leaves the fact behind" reasoning that justified the ordering. A `commit()` that throws is what the catch above is for.
- **Before the installer call**, as stated.

`detail` = `{"outcome": "attempted", "from_version": <stored>}`.

Recording *attempt*, not outcome, is forced: the install being waited on replaces the process waiting for it. But `from_version` makes the outcome recoverable anyway — the event's own `app_version` is whichever build reports it, so:

| Comparison | Meaning |
|---|---|
| `from_version` ≠ `app_version` | The rollback took. The reporting build is the restored one. |
| `from_version` == `app_version` | The rollback **failed** — `ApkInstaller.install` returned `Failed` (`UpdateWatchdogReceiver.kt:58-61`, logged and otherwise ignored), the process was never replaced, and the same build is reporting its own rollback attempt. |

Without `from_version` those two are the same row.

### `update_installed`

`update_state` gains `KEY_REPORTED_VERSION`, the `versionName` this kiosk last started under.

At startup, compare with `BuildConfig.VERSION_NAME`:

| Stored | Current | Outcome |
|---|---|---|
| empty | anything | **Seed, report nothing.** A first run is an installation, not an update. |
| same | same | Nothing. |
| different | different | Report, `detail = {"from": <stored>, "to": <current>}`. |

**The app never decides which direction the version moved.** `from` and `to` are both on the row; whether that is an upgrade, a downgrade, or a re-flash is the reader's inference. This is deliberate: `versionName` here is dotted-numeric and has carried a `-preview` suffix in this repo's history (commit `6b6b489`), so any in-app comparison rule would be a guess that has to be defined, tested, and maintained to produce a fact the dashboard can already read off the pair.

**The stored version always advances, even when analytics is off, and even when nothing is reported.** Only reporting is gated. If the value were held back while disabled, an operator enabling analytics months later would trigger a false "this kiosk just updated" for a build it had been running since spring.

### When both are pending

A watchdog rollback produces both markers at the same startup: the restored build is a different version, so `update_installed` fires too. They are **both emitted, and correlated rather than suppressed** — suppressing would lose the fact that the version actually moved.

- **Order: `update_rollback` first**, then `update_installed`, so a reader scanning by insertion order sees cause before effect. `occurred_at` resolves it anyway (the rollback's stamp is `KEY_ROLLBACK_AT`, the install's is now), but leaving order unstated means two implementers write it two ways.
- They join on the version pair: the rollback's `from_version` is the build that was replaced, which is the `update_installed`'s `from`.

This is reachable without any race, and is the normal case: build B installs, B is slow to boot, the +60s watchdog fires (`UpdateManager.kt:375`), B's startup reports its own `update_installed` and advances the stored version to B, the rollback lands, and A comes up to find stored=B, current=A.

### Startup wiring

In `MainActivity.onCreate`, off the main thread, **after `SettingsBootstrap` (`:314`) and after `recordHeartbeat` (`:366`)**. "After telemetry is constructible" is not a location — `telemetryOutbox` is `by lazy` and constructible from the first line of `onCreate` — and placing the drain before `SettingsBootstrap` would emit rows carrying `install_id: ""`.

1. Read all three markers.
2. Ask `DiagnosticEvents` for the events they imply, in the stated order.
3. Append them — **one append call site**, looping over the returned list.
4. Clear `KEY_ROLLBACK_AT` and `KEY_ROLLBACK_FROM_VERSION`; write the new `KEY_REPORTED_VERSION`.

**Wrapped in a catch-all**, for the reason 3a already documents twice (`MainActivity.kt:1901-1915`, `:1953-1962`): `lifecycleScope` installs no `CoroutineExceptionHandler`, so an escaping throwable reaches the default handler and kills the process. Here that is not merely a crash — if the death lands inside the 60s watchdog window and ahead of the heartbeat's asynchronous `apply()` flush, the watchdog reads a stale `KEY_LAST_STARTUP_MS` from disk and **rolls back a build that was fine**.

Markers are cleared only on the success path. An append that throws leaves them in place and the event is reported next startup. Duplicating a diagnostic is cheap; losing the only record that a kiosk reverted an update is not.

No flush is triggered here. 3a's four triggers already drain the queue, and a startup flush would put network work on the path that renders the first frame.

---

## `telemetry/DiagnosticEvents.kt` — new, pure

```kotlin
sealed class DiagnosticEventResult {
    object NotEnabled : DiagnosticEventResult()
    object NothingToReport : DiagnosticEventResult()
    data class Report(val event: TelemetryEvent.Diagnostic) : DiagnosticEventResult()
}

/** The report decision *and* the value the caller must persist, together, so the
 *  "always advance" rule is pinned by a test rather than by a comment. */
data class UpdateInstalledDecision(
    val result: DiagnosticEventResult,
    val versionToStore: String
)

object DiagnosticEvents {
    fun crash(settings: Settings?, appVersion: String, thread: Thread, throwable: Throwable,
              affiliateKey: String?, occurredAtMs: Long): DiagnosticEventResult

    fun updateRollback(settings: Settings?, appVersion: String, rollbackAtMs: Long,
                       fromVersion: String?): DiagnosticEventResult

    fun updateInstalled(settings: Settings?, appVersion: String, storedVersion: String,
                        occurredAtMs: Long): UpdateInstalledDecision
}
```

`updateInstalled` returns the value to persist because the "always advance" rule holds across all three outcomes, and leaving it at the call site would put the phase's most subtle invariant in the one file no test can reach — the exact mistake 2b shipped, cited in `DonationEvents`' own KDoc.

`crash` takes the `Throwable` and `Thread` rather than pre-rendered strings, so `stackTraceToString()` and the thread detail are inside the tested unit rather than at the untestable call site.

Timestamps: `Instant.ofEpochMilli(...)` of `rollbackAtMs` for the rollback, of `occurredAtMs` for the other two.

---

## The inertness grep changes shape again

Exact command, because a literal `outbox.append(` does **not** match `telemetryOutbox.append(`:

```bash
rg -n '[Oo]utbox\.append\(' app/src/main
```

Expected: **4** hits.

| Caller | Kind |
|---|---|
| `TelemetryManager` | the activation row |
| `MainActivity` donation path | `donation` |
| `MainActivity` startup drain (one site, looping) | `update_rollback`, `update_installed` |
| `KioskCrashHandler` | `crash` |

`flush()` callers stay at **1** (3a's dispatcher). `activate()` callers stay at **1** (the Test button). Nothing here flushes. A grep must still show **zero** *constructions* of the eight kinds deferred to 3c. Grep for `DiagnosticKind\.<NAME>`, not the bare name: `DiagnosticKind` has declared all eleven kinds since phase 1, so a bare-name grep always hits the enum body and can never read zero.

---

## Testing

### JVM-tested

**`DiagnosticEvents`:**
- Analytics off → `NotEnabled` for all three; null `Settings` likewise; nothing constructed.
- `crash` renders the throwable's stack trace and carries `thread`/`main` in `detail`.
- A stack trace containing the affiliate key comes back scrubbed — **and a second test with a blank affiliate key**, proving the 32+ token rule catches a UUID-shaped key on its own. That is the `testMode` case, where `affiliateKey` is never loaded (`MainActivity.kt:269-278`), and it is the backstop rule rather than the primary one holding the line.
- `updateInstalled`: empty stored → `NothingToReport`; identical → `NothingToReport`; changed → `Report` with both `from` and `to`; a backwards move reports the same way with the pair reversed.
- `updateInstalled` returns `versionToStore = current` in **all three** outcomes, including `NotEnabled`.
- `updateRollback` carries `outcome: attempted` and `from_version`.
- Severity and `kind` wire strings match the master spec's table.

**`KioskCrashHandler`:**
- Chains to the previous handler with the same thread and throwable.
- **Chains even when recording throws** — the decisive test. There is no mocking library (`app/build.gradle.kts:57` is `testImplementation(libs.junit)` and nothing else) and `TelemetryOutbox` is final, so injection is done with the **real** outbox over an unwritable path: `TelemetryOutbox(File(temp.newFile("blocker"), "outbox.jsonl"))`, where `parentFile.mkdirs()` returns false and `appendText` throws `FileNotFoundException`. No production change, no new dependency.
- Chains when analytics is off, and when `settings()` returns null (nothing recorded, crash still propagates).
- Chains when a **supplier itself throws**.
- `previous == null` → `exitProcess(2)` called; no silent return.
- Re-entrant invocation **records once and chains at least once** — the chain is deliberately unguarded, so the outer `finally` fires too.
- Appends exactly one row on a normal crash.

### Not unit-testable, and labelled as such

- `MainActivity.onCreate` installation, `CrashContext` refresh, and the startup drain.
- `UpdateWatchdogReceiver`'s marker write.

Wiring over decisions tested elsewhere. Neither computes anything.

### Device checks

Nothing below is covered by a unit test.

1. Force an uncaught exception. The app restarts exactly as it does today — the handler changes nothing an operator can see.
2. After that crash, the analytics screen shows one more queued row, and it arrives with a scrubbed trace.
3. Crash with `analyticsEnabled` off: queue depth unchanged, restart behaviour unchanged.
4. Install a build with a different `versionName`. Exactly one `update_installed`, correct `from`/`to`. Restarting again produces no second one.
5. A first install on a wiped device produces **no** `update_installed`.
6. Force the watchdog rollback with a backup APK present: an `update_rollback` and an `update_installed` both arrive, rollback first, joinable on the version pair.
7. **Fill the disk, then force the watchdog rollback.** The rollback must still be attempted — this is C-1's regression check and the most important one on this list.
8. Carried over and still unverified: status survives a process restart with `droppedCount` intact (2c); a donation appends exactly one row (3a).

---

## Limitations, stated rather than buried

**A rollback to a pre-telemetry build strands its own report.** The row is appended by the new build before it is replaced. If the APK it reverts to predates telemetry, nothing reads the outbox until some later telemetry-capable version installs — at which point the row arrives, correctly stamped but weeks late. The alternative is sending from a process about to be overwritten.

**`update_rollback` reports intent, not outcome** — mitigated, not removed, by `from_version`.

**A crash in the first moments of `onCreate` is not reported.** The handler cannot be installed before the outbox is constructible, and that is the same window in which `Settings` is not loaded, so there would be no identity to report under.

**The crash row is written but not sent by the dying process.** It leaves on the next flush. A kiosk that crashes and never comes back never reports the crash. Sending from a dying process was rejected: network I/O on a handler the system is tearing down delays the chain to `previous`, which is what restarts the kiosk.

**Scrubbing is best-effort against known shapes** — see non-negotiable #3.

---

## Decisions

**No diagnostic journal.** An earlier draft proposed a separate append-only file per detection site, drained at startup. Wrong twice over: `TelemetryOutbox` is already exactly that and its file-keyed lock was built for this caller, and the durable part of `append` (`file.appendText`) completes before the `compactIfNeeded` read that made the append look expensive. A second store would also have needed its own stack-trace scrubbing — a second place to get the affiliate key wrong, for no gain.

**Markers in `update_state`, not in the outbox.** A boolean, a timestamp and two version strings in a prefs file that already exists are facts about the device, not queued events. Making them outbox rows would mean constructing `Settings` and identity inside a `BroadcastReceiver` running in a doomed process.

**The app never compares version strings.** Emitting the `from`/`to` pair and letting the reader infer direction avoids defining a comparison rule for a scheme that has carried `-preview` suffixes. The correlation that actually matters — was this move a rollback — is answered by the presence of the rollback marker, not by parsing.

**Gate at construction, advance regardless**, with the value to advance returned from the pure unit so a test pins it.

**Chain in `finally`, unguarded.** The single most important line in this phase is the one that runs when everything else has failed.

**`commit()` in the receiver, `try`/`catch` around it.** The only place in this design where a synchronous disk write on the main thread is the right call, because the process is about to be replaced.

---

## Out of scope

- The eight remaining diagnostic kinds (3c).
- `retryableFailure` is still one global boolean; a refused table backs off the healthy ones. Carried from 2a.
- `SettingsBootstrap`'s call site is still untested and needs instrumented coverage.
- `formatTimestamp` in `AnalyticsSettingsScreen.kt` remains the one computation in the untestable file (M5, 2c).
- The 02:00 floor is still not guaranteed — `scheduleDailyLoginReset` does not re-arm itself while offline (3a). That scheduler is the card-payment recovery path and is not touched for a telemetry reason.

## Required of 3c

- The eight remaining kinds, most of which sit on the card-reader and SumUp paths.
- `update_install_failed` has a natural marker-write site at `UpdateManager.kt:400-406`, where the failure callback already runs and already calls `UpdateWatchdogReceiver.disarm` — symmetric with this phase's rollback marker, and subject to the same non-negotiable #2 treatment.
