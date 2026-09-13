# Telemetry Phase 3c — Recovery Diagnostics

**Goal:** The eight conditions the kiosk already detects and recovers from silently now say so, so a fleet operator can see a reader that never pairs or a kiosk that restarts every night.

Phase 3b added `crash`, `update_rollback` and `update_installed`. This phase adds the remaining eight kinds from the master spec. It completes `diagnostic_events`.

**The risk profile is different from 3b, and it drives the whole design.** 3b touched three sites. This phase touches eight, and several sit beside the card-payment path — `onActivityResult`'s SumUp branches, the checkout result. The danger is not any single event; it is eight opportunities to put a throwing statement somewhere a donation travels.

---

## Non-negotiables

1. **Recording never makes the thing it records worse.** Every one of the eight sites is a recovery path — the code that runs when something has *already* gone wrong. A throw from a diagnostic would turn a recoverable fault into a crash, or worse, into a payment that does not complete. This is the same rule as 3b's, applied eight times instead of two, which is why it is enforced **structurally** (see below) rather than by writing the same guard eight times and hoping.

2. **The donation flow is not altered.** No new abort condition, no new pre-check, no reordering. `checkout_no_reader` is reported by *observing* a failure that already happened — see its section. A kiosk with this phase installed takes payments identically to one without it.

3. **A kiosk with `analyticsEnabled` off writes nothing identified to disk.** Unchanged from 3b.

4. **No *known* secret reaches disk.** Unchanged from 3b, and it matters more here: five of the eight kinds carry **vendor-supplied text** — SumUp error messages, `PackageInstaller` status strings. `TelemetryEvent.Diagnostic` scrubs `detailJson` in its constructor, so that protection is inherited rather than reimplemented. But vendor text is exactly where an unanticipated secret shape could appear, so nothing may log it raw either.

5. **The outbox is the only durable queue.** Unchanged.

6. **`restart_triggered` must be written synchronously**, before `hardRestart` calls `Runtime.exit(0)`. A coroutine would never run. Same dying-process constraint as the crash handler.

---

## What already exists and is NOT rebuilt

- `TelemetryEvent.Diagnostic` — scrubs and truncates `detailJson` and `stackTrace` in its constructor.
- `DiagnosticKind` — all eleven kinds, with severities already assigned. **No enum change in this phase.**
- `DiagnosticEvents` — the pure decision unit from 3b, with `crash`, `updateRollback`, `updateInstalled` and the shared `identityOf` gate. This phase adds one generic entry point beside them.
- `CrashContext` — the process-scoped holder, seeded inside `saveSettings`.
- `TelemetryOutbox.append` — durable, file-keyed lock.
- `RestartManager`, `NetworkRecoveryManager`, `BluetoothRecoveryManager` — the recovery logic. **This phase reads their decisions; it does not change them.**

---

## The structural rule: one guarded helper, eight one-line call sites

The eight sites do not each get their own gate, their own try/catch and their own append. They get one helper in `MainActivity`:

```kotlin
    /**
     * Records a diagnostic and returns. Never throws, whatever happens inside —
     * every one of these call sites is a recovery path, and a diagnostic that
     * breaks the recovery it is reporting is worse than no diagnostic.
     */
    private fun reportDiagnostic(
        kind: DiagnosticKind,
        detail: JsonObject? = null,
        occurredAtMs: Long = System.currentTimeMillis(),
        synchronous: Boolean = false
    )
```

Why this shape rather than eight bespoke paths:

- **The guard is written once.** Eight hand-written try/catch blocks is eight chances to scope one wrongly, and the spec review of 3b found exactly that mistake in a single hand-written site.
- **The inertness grep stays meaningful.** `outbox.append` call sites go from 4 to **5**, not to 12. A count that grows by one per phase is a count someone still reads.
- **The call sites stay reviewable.** Each is one line next to the `Log.w` that is already there, so a reviewer can see at a glance that nothing else changed.

`synchronous = true` exists for exactly one caller, `restart_triggered`, whose process is about to exit. Everything else dispatches to `Dispatchers.IO`, because `TelemetryOutbox.append` reads the whole queue to decide about compaction and none of these paths should pay that on the main thread.

### The generic entry point

`DiagnosticEvents` gains one function beside the three from 3b:

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

It applies the same `identityOf` gate and constructs a `Diagnostic`. The three 3b functions stay as they are — they carry per-kind detail logic that does not generalise, and rewriting them in terms of this one would be churn on tested code for no gain.

**Detail shaping stays in the pure unit where it has a shape worth testing.** For kinds whose detail is a straight copy of values the call site already holds, the call site builds the `JsonObject` — that is transcription, not a decision. For `network_outage`, where the detail encodes whether a threshold was crossed, the decision belongs in the pure unit.

---

## The eight kinds and their sites

| Kind | Severity | Site | Detail |
|---|---|---|---|
| `restart_triggered` | error | `MainActivity.handleRestartResult`, `RESTART` arm, **before** `hardRestart` | `{"reason": <reason>}` |
| `sumup_reinit_failed` | error | `onActivityResult` case 1, login-failed branch | `{"code": <int>, "message": <vendor text>}` |
| `card_reader_connect_failed` | warn | `onActivityResult` case 2, not-connected branch | `{"code": <int>, "message": <vendor text>}` |
| `card_reader_page_timeout` | warn | The 10-minute pairing timeout job, inside `if (isConnectingCardReader)` | `null` |
| `checkout_no_reader` | warn | `onActivityResult` case 3, failure branch — see below | `{"code": <int>, "message": <vendor text>}` |
| `bluetooth_watchdog_fired` | warn | The watchdog loop, `ReEnable` arm | `{"off_ms": <duration>}` |
| `network_outage` | warn | `onNetworkRestored`'s `AutoReinit` arm | `{"downtime_ms": …, "threshold_ms": …}` |
| `update_install_failed` | error | `MainActivity`'s `UpdateNotification.InstallFailed` handler | `{"source": "install"}` |

### `restart_triggered` — the one that must be synchronous

`handleRestartResult`'s `RESTART` arm logs and calls `hardRestart(reason)`, which starts an intent and calls `Runtime.getRuntime().exit(0)`. Anything dispatched to another thread is lost. The report is written synchronously on the calling thread, before `hardRestart`.

This is the one site where the append's cost lands on the calling thread. Accepted: the process is about to die, and a restart that takes an extra few milliseconds is not a restart anyone notices. The alternative — losing the report of every automatic restart — would leave the single most useful fleet signal unreported.

### `checkout_no_reader` — observation, never a new check

`makePayment` checks `testMode` and network availability, and has **no reader check**. It is not getting one. Adding an abort condition to the donation path means a stale `isCardReaderConnected` flag could block a real donation, which is a far worse outcome than a missing diagnostic.

Instead this is reported from the existing checkout-failure branch when the failure is consistent with no reader being present. It is therefore a weaker signal than its name suggests — it depends on SumUp's error text — and the spec says so rather than implying precision it does not have. A dashboard should read it as "checkout failed and a reader problem is the likely cause", not as proof.

### `update_install_failed` — reported from the notification, not the installer

`UpdateManager` fires `onNotification(UpdateNotification.InstallFailed)` from **four** sites (`:334`, `:347`, `:364`, `:403`), and `MainActivity` already handles that notification at `:2164`. Reporting there covers all four failure modes with one edit and **no change to `UpdateManager` at all**, which keeps this phase out of the installer.

The cost: the event cannot distinguish which of the four failures occurred, so `detail` records only that an install failed. Recovering the specific cause means passing it through `UpdateNotification`, which changes a type the update flow depends on — not worth it here. Noted for whoever wants that signal later.

### `network_outage` — the manager must expose what it already computed

`NetworkRecoveryManager.onNetworkRestored` computes `downtime`, compares it against `settings.longDowntimeThresholdSec * 1000L`, returns `AutoReinit` when it is exceeded, and then discards the number. The call site cannot re-derive it: the manager clears `networkLostTimestamp` before returning.

So the manager gains a read-only property holding the duration of the outage it last reported, set immediately before the timestamp is cleared. No behavioural change, no new decision — the comparison that decides `AutoReinit` stays exactly where it is.

The event's detail carries both the duration and the threshold it crossed, because a threshold that an operator later changes would otherwise make old rows uninterpretable.

---

## What this phase does not change

- No `DiagnosticKind` enum change.
- No change to `RestartManager`, `BluetoothRecoveryManager`, or any recovery *decision*. `NetworkRecoveryManager` gains one read-only property and nothing else.
- No change to `UpdateManager`.
- No change to `makePayment` or any donation path.
- No new flush trigger. 3a's four triggers drain the queue.

## The inertness grep after this phase

```bash
grep -rn "[Oo]utbox\.append(" app/src/main
```

Expected **5**: `TelemetryManager`'s activation row, the donation path, 3b's startup drain, 3b's crash handler, and this phase's single `reportDiagnostic` helper.

`flush()` callers stay at **1**, `activate()` callers at **1**. After this phase, a grep for `DiagnosticKind\.<NAME>` finds a construction for **all eleven** kinds — the deferred-kind check that 3b used retires here, because nothing is deferred any more.

---

## Testing

### JVM-tested

`DiagnosticEvents.forKind`:
- Analytics off, and null `Settings`, both give `NotEnabled` for every kind.
- The constructed event carries the right `kind` and `severity` wire strings for all eight — a table-driven test, since the severities come from the enum and a wrong one is invisible otherwise.
- Vendor text containing the affiliate key comes back scrubbed; vendor text containing a 32+ character token comes back scrubbed with a blank key. Both, for the same reason 3b tested both: in `testMode` the key is never loaded and only the backstop applies.
- Malformed `detailJson` is dropped rather than throwing — `Diagnostic`'s constructor already does this, and this is the phase that starts feeding it vendor-supplied strings, so it gets a test here.

`NetworkRecoveryManager`:
- The exposed outage duration matches the downtime that triggered `AutoReinit`.
- It is not left stale from a previous outage after a short one that did not trigger.
- The existing behavioural tests must still pass unchanged — the property is additive.

### Not unit-testable, and labelled as such

All eight call sites, and the `reportDiagnostic` helper's dispatch. `MainActivity` needs the Android lifecycle and Robolectric would breach the no-new-dependencies constraint.

What compensates: each call site is one line beside an existing log statement, and the helper is one function whose guard a reviewer reads once.

### Device checks

1. Force three consecutive card-reader failures to trigger an auto-restart. A `restart_triggered` row must exist **after** the restart — this is the one that proves the synchronous write works.
2. Open the pairing page and walk away for ten minutes: one `card_reader_page_timeout`.
3. Fail a reader connection: one `card_reader_connect_failed` carrying the SumUp code.
4. Turn Bluetooth off and leave it off for over a minute on a device-owner install: one `bluetooth_watchdog_fired`.
5. Pull the network for longer than `longDowntimeThresholdSec`, then restore: one `network_outage` whose `downtime_ms` matches the real outage.
6. Install a deliberately bad APK: one `update_install_failed`.
7. With `analyticsEnabled` off, repeat any two of the above: queue depth unchanged.
8. **Take a donation with a diagnostic pending in the queue.** The donation must complete normally — this is the regression check for non-negotiable #2.
9. Carried and still unverified from earlier phases: status survives a process restart with `droppedCount` intact (2c); a donation appends exactly one row (3a); the disk-full rollback check (3b, device check 7).

---

## Limitations, stated rather than buried

**`checkout_no_reader` is inferred, not observed.** It keys off vendor error text. Discussed above; the alternative was a new abort condition on the donation path.

**`update_install_failed` cannot say which failure it was.** Four call sites collapse into one notification. Recovering the distinction means changing `UpdateNotification`.

**Eight new call sites is eight new chances to be wrong**, and only the helper is tested. The mitigation is that the call sites are uniform and trivial — if one is wrong, it is wrong by being absent or by passing the wrong kind, both of which a reviewer reading eight one-line diffs can see.

**A diagnostic written on a dying process is not sent by it.** Same as 3b's crash: it leaves on the next flush.

---

## Decisions

**One helper, not eight guards.** The single biggest risk in this phase is a throwing statement on a recovery path. Writing the guard once and calling it eight times makes that risk structural rather than a matter of eight separate reviews going well.

**`checkout_no_reader` observes rather than checks.** A missing diagnostic costs a dashboard some signal. A new abort condition on the donation path can cost a donation.

**`update_install_failed` is reported from the notification handler.** It keeps this phase entirely out of `UpdateManager`, at the price of losing which of four failures occurred.

**`NetworkRecoveryManager` exposes, it does not decide.** The threshold comparison stays exactly where it is; only the number it already computed becomes readable.

**Vendor text is passed through, not parsed.** SumUp messages and installer status strings go into `detail` as-is and are scrubbed by the constructor. Parsing them to extract a "cause" would be guessing at a format the vendor can change.

---

## Required after this phase

- `diagnostic_events` is complete. The remaining telemetry work is the dashboard side, not the app.
- `retryableFailure` is still one global boolean; a refused table backs off the healthy ones. Carried from 2a, and now that eleven kinds can enqueue, a single refused table stalls more than it used to. This is the next thing worth fixing.
- `SettingsBootstrap`'s call site is still untested and needs instrumented coverage.
- `MainActivity.onCreate` has an unguarded `filesDir` touch from 3b, dominated today by a pre-existing `DonationHistory(this)` call that would throw first. Worth revisiting as that region grows.
- The 02:00 flush floor is still not guaranteed — `scheduleDailyLoginReset` does not re-arm itself while offline (3a).
