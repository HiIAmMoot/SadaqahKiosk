# Telemetry phase 3a — donation events and flush scheduling

Date: 2026-09-07
Branch: `telemetry/phase-3a`, off `telemetry/phase-2c` (PR #5)
Parent spec: `docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md`

Phase 3 of the master spec is "instrumentation": donation events, diagnostic
events, the crash handler, and update outcome reporting — plus the flush
scheduler, which phase 2c's plan deferred here. That is five bodies of work
touching `MainActivity` in a dozen places, and `MainActivity` is the file every
phase so far has had trouble with. So phase 3 is split:

- **3a (this spec)** — the donation event and the flush scheduler. Inertness ends
  here: after this phase a configured kiosk reports donations on its own.
- **3b** — the crash handler, the diagnostic sites, and update outcome reporting.

3a is the half that can be validated end-to-end on a kiosk: a donation appears in
Supabase without anyone pressing anything. 3b then adds more kinds of row to a
pipeline already proven to work.

---

## Non-negotiables

1. **The donation flow is sacred.** No telemetry work runs on the main thread, and
   no telemetry failure can reach the donor's screen. A full disk, a broken
   Keystore, a wedged network: the donation completes and the thank-you screen
   shows, exactly as it does today.
2. **Nothing waits on a human.** A kiosk can run unattended for weeks with only
   donors touching it. Every failure mode resolves itself or is recorded silently
   for whoever eventually looks. No dialog, no badge, no acknowledgement.
3. **Nothing is sent without network.** Already true and not rebuilt here — see
   "What already exists" below.
4. **`analyticsEnabled` means what it says.** A kiosk with the switch off appends
   nothing. Not "appends and declines to send" — appends nothing, so no identified
   donation row is ever written to disk for a purpose the operator declined.

---

## What already exists and is NOT rebuilt

Recorded explicitly because the obvious reading of "make telemetry robust" is to
build retry and offline handling, and both already shipped:

| Behaviour | Where it lives | Shipped in |
|---|---|---|
| No upload attempted while offline | `TelemetryGate.evaluate` returns `NO_NETWORK`, wired to the app's own detector via `networkAvailable = { isOnlineNow() }` | 1 / 2b |
| Exponential backoff, 1 min to a 60 min ceiling | `TelemetryGate.backoffDelayMs`, deadline in `TelemetryStatus.backoffUntilMs` | 1 |
| A success clears the failure state | `TelemetryManager.flush`'s success branch | 2b |
| A permanently refused row is dropped so it cannot wedge the queue | `TelemetryUploader`'s `rejectedIds`, corroborated by siblings | 2a |
| An offline kiosk sheds oldest rather than filling the disk | `TelemetryOutbox` caps: 5,000 rows / 30 days | 1 |
| A stale backoff deadline from a corrected clock fails open | `TelemetryGate.evaluate`'s ceiling check | 1 |

A kiosk that loses wifi for three weeks and gets it back drains its backlog on
`handleNetworkRestored()` with nobody present. 3a adds no new machinery for any of
this; it adds the rows to send and the moments to try.

---

## The donation event

### `telemetry/DonationEvents.kt` — new, pure

```kotlin
sealed class DonationEventResult {
    data class Report(val event: TelemetryEvent.Donation) : DonationEventResult()
    object NotEnabled : DonationEventResult()
    object AmountUnrepresentable : DonationEventResult()
}

object DonationEvents {
    fun eventFor(
        settings: Settings,
        appVersion: String,
        amount: BigDecimal,
        occurredAtMs: Long
    ): DonationEventResult
}
```

A sealed result rather than a nullable event, because there are two distinct
reasons not to produce one and the caller must treat them differently:
`NotEnabled` is the normal state of most kiosks and means *do nothing*, while
`AmountUnrepresentable` is a lost donation and must be logged and counted. A
`null` covering both would make a disabled kiosk record a loss on every donation.

**The enabled gate lives inside this function, not at the call site.** That is the
whole reason the function exists. `MainActivity` is not unit-testable, so an
`if (settings.analyticsEnabled)` there is a decision nothing checks — which is
exactly how phase 2b shipped a mint inside an unreachable branch. The call site
gets a `when` over three cases and decides nothing.

**Amount conversion.** `amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP)`
then `intValueExact()`, returning `AmountUnrepresentable` if that throws.

- `HALF_UP` because currency is conventionally rounded that way and it is what a
  reader assumes. In practice the numpad produces at most two decimals, so
  rounding is near-unreachable — which is precisely why it needs a test rather
  than a guess.
- A scale-3 or worse amount can only come from a malformed stored string, and is
  rounded rather than rejected: a donation that really happened should be reported
  approximately rather than not at all.
- **Negative amounts pass through unchanged.** A negative successful payment is
  nonsense, but `DonationHistory.append` already stores whatever it is handed, and
  a dashboard showing a negative is a signal that something is wrong. Dropping it
  would hide the very anomaly worth seeing.
- Overflow (an amount beyond `Int` cents, about 21.5 million) yields
  `AmountUnrepresentable`. Unreachable through the UI; defined so the function has
  no undefined behaviour.

`occurredAtIso` is `Instant.ofEpochMilli(occurredAtMs).toString()`. `currency`
comes from `settings.currency`. `appVersion` is a parameter rather than a
`BuildConfig` read, so the function has no Android dependency.

### `EventIdentity` gets one construction site

`MainActivity`'s `runtime` lambda already builds `EventIdentity(kioskCode,
installId, VERSION_NAME)`. `DonationEvents` needs the same value, and two
constructions would drift the first time identity sourcing changes. So
`EventIdentity` gains a companion with one factory, in `TelemetryEvent.kt`:

```kotlin
data class EventIdentity(...) {
    companion object {
        fun from(settings: Settings, appVersion: String): EventIdentity
    }
}
```

used by both `DonationEvents.eventFor` and the `runtime` lambda, which is
rewritten to call it. Five lines, removing a duplicate rather than adding an
abstraction. Note this makes `TelemetryEvent.kt` import `model.Settings` for the
first time; that direction is already established (`AnalyticsPresenter` does it)
and the reverse would not be.

### The call site

`MainActivity.onActivityResult`, request 3, success branch. Today:

```kotlin
if (settings.donationTrackingEnabled) { lastPaymentAmount?.let { donationHistory.append(it) } }
```

That line stays unchanged, followed by a **sibling** call, not a nested one:

```kotlin
lastPaymentAmount?.let { appendDonationTelemetry(it) }
```

`donationTrackingEnabled` governs the local history screen and nothing else.
Telemetry has its own master switch, and tying the two would mean an operator who
turns off the on-panel history for privacy silently stops all remote reporting.

`appendDonationTelemetry` is a private `MainActivity` method that dispatches to
`Dispatchers.IO` and returns immediately. Sketch, not source:

```
// on Dispatchers.IO, via lifecycleScope
when (val r = DonationEvents.eventFor(settings, BuildConfig.VERSION_NAME, amount,
                                      System.currentTimeMillis())) {
    NotEnabled            -> {} // the normal state of most kiosks
    AmountUnrepresentable -> { Log.e(...); recordTelemetryLoss() }
    is Report             -> try {
                                 telemetryOutbox.append(r.event.id, r.event.table,
                                                        r.event.payloadJson())
                             } catch (t: Throwable) { Log.e(...); recordTelemetryLoss() }
}
```

`recordTelemetryLoss()` is exactly one statement, and the plan must not invent a
second field for it:

```kotlin
telemetryStatusStore.update { it.copy(droppedCount = it.droppedCount + 1) }
```

Neither `Log.e` call includes the amount or the payload — the message is the
throwable's class name for the append failure, and a fixed string plus the
`BigDecimal`'s *scale* (never its value) for the unrepresentable case.

**Why `Dispatchers.IO` and not the flush thread.** `TelemetryOutbox` is
`synchronized` per file path, so a concurrent append is already safe, and a flush
holds that lock only across `peek` and `remove` — never across the upload. Putting
appends on the single flush thread would park a donation row behind an upload that
can run for minutes in the per-row fallback, for no benefit. A row appended
between a flush's `peek` and `remove` is harmless: `remove` deletes only ids the
uploader named.

**Why a catch-all.** `TelemetryOutbox.append`'s KDoc says it throws on a full disk
or a failed `mkdirs`, that the caller owns that decision, and that the caller sits
on the donation path. This is that caller.

### Failure is recorded, never surfaced

Both loss cases fold into the existing `TelemetryStatus.droppedCount` via
`statusStore.update { }` — never a hand-written read-then-write, because this
fires from a background thread and races the flush's own status writes, which
straddle a network call that can run for minutes (finding I4, Ruling BA).

`droppedCount`'s meaning broadens from "aged out by the caps" to **"rows lost
locally and unrecoverable"**, and its KDoc is updated again to say so. For anyone
who eventually reads the screen it is the same fact — *N events never made it* —
and it costs no new field, no new string in eight languages, and no new UI.

Phase 2c's acknowledge button was removed from PR #5 (`af7cc31`) for the reason
that motivates this section: it assumed an operator who visits.

---

## Flush scheduling

### Triggers

| Trigger | Site | Why this moment |
|---|---|---|
| Screensaver activates | the idle poll's activation branch, and `activateScreensaver()` | The app's own definition of "nobody is using this". Both sites already call `disconnectCardReader()` — the app *already* treats this instant as its housekeeping moment, so contention with a live card transaction is impossible by construction rather than by a flag. |
| 30-minute tick **while** `isScreensaverActive` | new `lifecycleScope` loop in `onCreate` | A single edge flush can be refused by a backoff of up to 60 minutes and then never retried until 02:00. This is what drains a queue across a long idle night. |
| Network restored | `handleNetworkRestored()` | Exactly when a backlog can finally move, and the kiosk was offline so it was not mid-transaction. |
| 02:00 maintenance | `scheduleDailyLoginReset`, after `runDailyMaintenance()` | The floor. |

The master spec asked for "an opportunistic 30-minute timer while idle" and left
*idle* undefined. Defining it as the screensaver uses the app's real state instead
of a proxy such as `lastPaymentAmount != null`.

**Accepted consequence:** a kiosk that never idles for `screensaverIdleTimeoutSec`
during opening hours reports **once a day, at 02:00**. Nothing is lost — a busy
day is a few hundred rows against a 5,000-row cap — but the dashboard is up to a
day behind for the busiest sites. It also means `screensaverIdleTimeoutSec`, which
reads as a cosmetic preference, influences reporting cadence; the 02:00 floor is
what keeps that a latency coupling rather than a data-loss one.

Four triggers, five call sites: the screensaver row covers two existing
assignment sites, which are not funnelled through a helper because one of them
lives inside a composition-scoped `LaunchedEffect` and the other is an activity
method — a shared wrapper across that boundary is more churn than the duplicate
call costs.

The tick is **one always-running loop** started in `onCreate` that delays first
and then reads the flag, rather than a job started and stopped with the
screensaver: entering the screensaver already produces the edge flush, so the
tick's only job is the retry 30 minutes later, and a loop with no lifecycle
coupling has no start/stop ordering to get wrong.

**No `FlushScheduler` class.** The gate already owns *may we flush*. These
triggers decide only *now is a moment worth asking*, and there is no interval
arithmetic or edge detection left to extract — the screensaver edge is two
existing assignment sites, and the tick reads one boolean. A pure policy object
here would wrap a single `if`.

### Serialisation

`TelemetryManager` documents itself as not safe for concurrent callers:
`lastUploadOutcome` and `lastAttemptedIds` are plain fields, and `activate()`
reads them to distinguish a real failure from a row a page never reached.
`onAnalyticsTestConnection` currently runs `activate()` on `Dispatchers.IO`, a
shared pool. Once a timer can also call `flush()`, the two can interleave and
`activate()` can read an outcome the timer's flush wrote.

A single-thread executor, exposed as a coroutine dispatcher and created lazily so
a kiosk that never configures analytics never spawns the thread:

```kotlin
private val telemetryFlushDispatcher by lazy {
    Executors.newSingleThreadExecutor { r -> Thread(r, "telemetry-flush") }.asCoroutineDispatcher()
}
```

`activate()` moves onto it, and one private method becomes the sole caller of
`flush()`:

```kotlin
private fun flushTelemetry(reason: String) {
    lifecycleScope.launch {
        val block = withContext(telemetryFlushDispatcher) { telemetryManager.flush() }
        Log.d("Telemetry", "flush($reason) -> $block")
        refreshAnalyticsSnapshot()
    }
}
```

Serialisation is then a property of the dispatcher rather than a lock nobody can
test. The dispatcher is closed in `onDestroy`.

**Only `flush()` and `activate()` move onto it.** `refreshAnalyticsSnapshot` stays
on `Dispatchers.IO`: it calls `credentials.load()` and `manager.status()`, and
`status()` touches only the status store and `outbox.size()` — never the manager's
outcome fields. Moving it would queue a Keystore decrypt behind an upload that can
run for minutes, and make opening the analytics screen hang on a flush in
progress. Likewise the donation append stays on `Dispatchers.IO` (Ruling BJ).

The unconditional `refreshAnalyticsSnapshot()` is safe and deliberate: its
existing Ruling BF guard returns early when the screen is closed, so this keeps an
open analytics screen current after a network-restore flush without ever
re-decrypting the Keystore for a screen nobody is looking at.

The log line carries a `FlushBlock`, never an error string. `lastError` is
redacted for the screen, and no telemetry error or secret has reached a log in
this feature so far; the whole-phase review checks that invariant again.

---

## The inertness grep changes shape

Every phase so far verified that nothing enqueues and nothing flushes. From 3a
onward that check has a new expected result, and the plan must state it — a plan
still grepping for "exactly one append" would fail for the right reason and then
get "fixed" the wrong way:

- `outbox.append(` in main source: **exactly 2** — `TelemetryManager`'s activation
  row, and `appendDonationTelemetry`.
- `telemetryManager.flush()` in main source: **exactly 1** — inside
  `flushTelemetry`.
- `flushTelemetry(` call sites: **exactly 5** — two screensaver sites, the idle
  tick, `handleNetworkRestored`, and the 02:00 block.
- `activate()` callers: still exactly 1 — the Test connection button.
- No crash handler, no diagnostic append, no update-outcome append. Those are 3b,
  and a hit in `UpdateManager`, `RestartManager` or a receiver is 3b work leaking
  in.

---

## Testing

### JVM-tested

`DonationEventsTest`:

- analytics off yields `NotEnabled`, and no event is constructed
- analytics on yields `Report` carrying the amount, currency, identity, app
  version and ISO timestamp through unchanged
- cents at scale 0, 1, 2 and 3, with the `HALF_UP` boundary pinned in both
  directions
- zero
- a negative amount passes through rather than being dropped
- an amount beyond `Int` cents yields `AmountUnrepresentable`
- `EventIdentity.from` reads `kioskCode`, `installId` and the passed app version

Mutation checks the plan must mandate, each with a distinct failing test:

1. Make `eventFor` ignore `analyticsEnabled` — the `NotEnabled` test fails.
2. Change rounding to truncation — a scale-3 boundary test fails.
3. Return a `Report` on overflow instead of `AmountUnrepresentable` — the overflow
   test fails.
4. Collapse the two non-`Report` cases into one — the caller can no longer tell a
   disabled kiosk from a lost donation, and the `NotEnabled` test asserting *no
   loss recorded* fails.

### Not unit-testable, and labelled as such

Same reasoning as Ruling AN: these need a device, and Robolectric would breach the
no-new-dependencies constraint.

- the five `flushTelemetry` call sites
- the single-thread dispatcher and the serialisation it provides
- `appendDonationTelemetry`'s catch-all and its `droppedCount` bump

### Device checks

1. A donation on an enabled, configured kiosk appends **exactly one** row (read
   the queue count on the analytics screen before and after).
2. A donation on a kiosk with `analyticsEnabled` off appends **nothing**.
3. Letting the screensaver come up on a kiosk with a non-empty queue drains it.
4. A donation taken in airplane mode queues, and drains when wifi returns, with
   nobody touching the app.
5. The queue count survives a process restart with `droppedCount` intact (carried
   over from 2c's device list, still unverified).
6. Repeated donations while offline do not slow the thank-you screen on a kiosk
   already holding thousands of rows.

---

## Decisions

**Ruling BI — the screensaver defines "idle".** Proposed by the user over a
`lastPaymentAmount != null` proxy, and better: the screensaver activation sites
already call `disconnectCardReader()`, so the app already designates that instant
as safe for background work. Accepted consequence recorded above — a permanently
busy kiosk reports at 02:00, and `screensaverIdleTimeoutSec` influences cadence.

**Ruling BJ — appends on `Dispatchers.IO`, flush and activate on one thread.** The
outbox is internally synchronized so appends need no serialisation; the manager's
outcome fields do. Sharing one thread for both would delay a donation row behind a
multi-minute upload for nothing.

**Ruling BK — no operator-facing loss reporting, and 2c's button removed.** A
kiosk runs unattended for weeks, so a control that waits on a human press is a
control never pressed. Both loss cases fold into `droppedCount` silently.
Ruling BD is thereby reversed, and PR #5 amended in `af7cc31`.

**Ruling BL — a sealed result, not a nullable event.** `NotEnabled` and
`AmountUnrepresentable` demand different caller behaviour; a shared `null` would
make every donation on a disabled kiosk record a data loss.

**Ruling BM — one `EventIdentity` construction site.** The manager's `runtime`
lambda and `DonationEvents` need the same value; two constructions would drift.

**Ruling BN — negative amounts are reported, not dropped.** `DonationHistory`
already stores what it is given, and a negative on the dashboard is the anomaly
worth seeing rather than hiding.

---

## Out of scope

- The crash handler, the diagnostic sites (`RestartManager`, the Bluetooth
  watchdog, network outage, card-reader failures) and update outcome reporting —
  **3b**.
- `DisclosureScreen` and its translations — **phase 4**.
- README privacy rewrite and the reference schema — **phase 5**.
- `retryableFailure` being one global boolean, so a refused table backs off the
  healthy ones. Carried in from 2a, unchanged, and not fixed here.
- Any change to `TelemetryUploader`, `TelemetryGate`, `TelemetryCredentials`, or
  the outbox's storage format.

## Required of 3b

- The crash handler must construct its own `TelemetryOutbox` over the same path
  and write synchronously before chaining to the previously installed handler.
  The outbox's file-keyed lock already anticipates this; 3b is what exercises it.
- `SettingsBootstrap`'s call site is still untested and needs instrumented
  coverage — re-gating it today would leave the suite green.
- `formatTimestamp` in `AnalyticsSettingsScreen.kt` is still the one computation
  living in the untestable file (M5, recorded in 2c and not fixed).
