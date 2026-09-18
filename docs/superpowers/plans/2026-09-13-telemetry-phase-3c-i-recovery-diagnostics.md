# Telemetry Phase 3c-i — Recovery Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three silently-recovered conditions — a dead Bluetooth radio, a long network outage, a failed update install — now report themselves, using a reporting helper proven on sites where a mistake costs a log line rather than a donation.

**Architecture:** One guarded helper in `MainActivity`, called from three places. Its `detail` parameter is a **lambda**, not a value, so building the JSON happens inside the guard — two of the three call sites are inside `while (true)` loops whose scope installs no `CoroutineExceptionHandler`. Every decision worth testing lives in a pure unit: the report gate in `DiagnosticEvents`, and the "is this the first re-enable of this outage" rule in `BluetoothRecoveryManager`.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, Gson, minSdk 30. No new third-party dependencies — there is no mocking library and none may be added.

**Spec:** `docs/superpowers/specs/2026-09-13-telemetry-phase-3c-i-recovery-diagnostics.md` — read it before Task 1, especially the Non-negotiables.

## Global Constraints

- **No new third-party dependencies.** Do not touch any gradle file. JUnit 4 only — no Mockito, no MockK, no Robolectric.
- **Recording never makes the thing it records worse.** The helper never throws, and its guard covers building `detail` as well as the append.
- **A diagnostic must never evict a donation.** The outbox is one 5,000-row queue shared with donation rows and `applyCaps` keeps the newest, so a kind that can repeat on a timer needs a once-per-episode rule enforced in a tested unit.
- **A kiosk with `analyticsEnabled` off writes nothing identified to disk.**
- **No recovery decision changes.** Both managers gain read-only members only; every threshold comparison stays exactly where it is.
- **The donation flow is untouched.** No site in this phase is on it.
- Comments explain a non-obvious *why*, never a *what*. No commented-out code.
- **Never commit or push to `master`.** Work happens on `telemetry/phase-3c`, branched from `telemetry/phase-3b`.
- Commit messages stay short — subject plus at most a short paragraph. **No AI attribution or session links in any commit or PR.**

## Context: what already exists

- `telemetry/DiagnosticEvents.kt` — `DiagnosticEventResult` (`NotEnabled`, `NothingToReport`, `Report`), `UpdateInstalledDecision`, `crash`, `updateRollback`, `updateInstalled`, and a private `identityOf(settings, appVersion): EventIdentity?` that returns null when `settings` is null or `analyticsEnabled` is false.
- `telemetry/TelemetryEvent.kt` — `TelemetryEvent.Diagnostic(identity, kind, occurredAtIso, detailJson, stackTrace, affiliateKey, id)`, which scrubs and truncates in its constructor. `DiagnosticKind` with all eleven kinds and their severities.
- `telemetry/CrashContext.kt` — `@Volatile var settings: Settings?` and `@Volatile var affiliateKey: String?`, seeded inside `saveSettings`.
- `MainActivity.kt` — 3b's eagerly-constructed `TelemetryOutbox` for the crash handler (in `onCreate`, around `:330`); the Bluetooth watchdog loop at `:878`; `onNetworkRestored`'s consumer at `:1473` with its `AutoReinit` arm at `:1485`; the `UpdateNotification.InstallFailed` handler at `:2164`.
- `recovery/NetworkRecoveryManager.kt` — `onNetworkLost`, `onNetworkRestored`, `isTrackingOutage`, private `networkLostTimestamp`.
- `recovery/BluetoothRecoveryManager.kt` — `onBluetoothOff`, `onBluetoothOn`, `evaluate(cycleInProgress)`, `isTrackingOutage`, private `offSinceTimestamp`.
- `update/UpdateManager.kt` — `UpdateNotification.InstallFailed` is a `data object` at `:33`, fired at `:334`, `:347`, `:364`, `:403`.

## File Structure

| File | Responsibility |
|---|---|
| `telemetry/DiagnosticEvents.kt` | **Modify.** One generic entry point and three detail builders. |
| `recovery/NetworkRecoveryManager.kt` | **Modify.** Expose the outage duration it already computed. |
| `recovery/BluetoothRecoveryManager.kt` | **Modify.** Expose the off-duration, and count re-enables per outage. |
| `update/UpdateManager.kt` | **Modify.** `InstallFailed` carries which of four failures it was. |
| `MainActivity.kt` | **Modify.** The helper, and three one-line call sites. |

---

## Task 1: `forKind` and the detail builders

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/DiagnosticEvents.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/DiagnosticEventsTest.kt` (exists — append to it)

**Interfaces:**
- Consumes: `Settings`, `TelemetryEvent.Diagnostic`, `DiagnosticKind`, the existing private `identityOf`.
- Produces, and Task 4 depends on these exact names:
  - `DiagnosticEvents.forKind(settings: Settings?, appVersion: String, kind: DiagnosticKind, occurredAtMs: Long, detailJson: String? = null, affiliateKey: String? = null): DiagnosticEventResult`
  - `DiagnosticEvents.networkOutageDetail(downtimeMs: Long, thresholdMs: Long): String`
  - `DiagnosticEvents.bluetoothWatchdogDetail(offMs: Long): String`
  - `DiagnosticEvents.installFailedDetail(reason: String?): String`

- [ ] **Step 1: Write the failing tests**

Append to `DiagnosticEventsTest.kt`, inside the existing class:

```kotlin
    // ── forKind: the generic entry point ─────────────────────────────────────

    @Test
    fun analyticsOffReportsNoDiagnosticOfAnyKind() {
        DiagnosticKind.values().forEach { kind ->
            assertEquals(
                "every kind must respect the master switch, not just the ones with bespoke builders",
                DiagnosticEventResult.NotEnabled,
                DiagnosticEvents.forKind(disabled, appVersion, kind, atMs)
            )
        }
    }

    @Test
    fun nullSettingsReportsNoDiagnosticOfAnyKind() {
        DiagnosticKind.values().forEach { kind ->
            assertEquals(
                DiagnosticEventResult.NotEnabled,
                DiagnosticEvents.forKind(null, appVersion, kind, atMs)
            )
        }
    }

    /**
     * Severity comes from the enum, so a call site cannot pair a kind with the
     * wrong one — but a builder that dropped severity entirely would be
     * invisible without this. Table-driven so a twelfth kind is covered the day
     * it is added.
     */
    @Test
    fun everyKindCarriesItsOwnWireStringsAndSeverity() {
        DiagnosticKind.values().forEach { kind ->
            val row = payload(reported(DiagnosticEvents.forKind(enabled, appVersion, kind, atMs)))
            assertEquals(kind.wire, row["kind"].asString)
            assertEquals(kind.severity.wire, row["severity"].asString)
            assertEquals("2023-11-14T22:13:20Z", row["occurred_at"].asString)
        }
    }

    @Test
    fun forKindWritesToTheDiagnosticsTable() {
        val event = reported(
            DiagnosticEvents.forKind(enabled, appVersion, DiagnosticKind.NETWORK_OUTAGE, atMs)
        )
        assertEquals(TelemetryTables.DIAGNOSTICS, event.table)
    }

    @Test
    fun forKindOmitsDetailEntirelyWhenNoneIsGiven() {
        val row = payload(
            reported(DiagnosticEvents.forKind(enabled, appVersion, DiagnosticKind.NETWORK_OUTAGE, atMs))
        )
        assertFalse(row.has("detail"))
    }

    /** The constructor drops unparseable detail rather than throwing. This is
     *  the phase that starts feeding it constructed JSON, so it is pinned here. */
    @Test
    fun malformedDetailIsDroppedRatherThanThrown() {
        val row = payload(
            reported(
                DiagnosticEvents.forKind(
                    enabled, appVersion, DiagnosticKind.NETWORK_OUTAGE, atMs,
                    detailJson = "{not valid json"
                )
            )
        )
        assertFalse(row.has("detail"))
    }

    @Test
    fun forKindScrubsTheAffiliateKeyOutOfDetail() {
        val key = "sumup-af-7c2e"
        val row = payload(
            reported(
                DiagnosticEvents.forKind(
                    enabled, appVersion, DiagnosticKind.UPDATE_INSTALL_FAILED, atMs,
                    detailJson = """{"reason":"failed for $key"}""",
                    affiliateKey = key
                )
            )
        )
        assertEquals("failed for [redacted]", row["detail"].asJsonObject["reason"].asString)
    }

    /** The testMode case: the key is never loaded, so the 32+ token backstop is
     *  the only rule standing. Same pairing as the crash tests. */
    @Test
    fun forKindStillScrubsAKeyShapedTokenWhenNoKeyIsKnown() {
        val token = "0d1f4c2e-77aa-4b31-9f6e-2c5b8a1d3e40"
        val row = payload(
            reported(
                DiagnosticEvents.forKind(
                    enabled, appVersion, DiagnosticKind.UPDATE_INSTALL_FAILED, atMs,
                    detailJson = """{"reason":"failed for $token"}""",
                    affiliateKey = ""
                )
            )
        )
        assertFalse(row["detail"].asJsonObject["reason"].asString.contains(token))
    }

    // ── The detail builders ──────────────────────────────────────────────────

    @Test
    fun networkOutageDetailCarriesBothTheDowntimeAndTheThresholdItCrossed() {
        val detail = JsonParser.parseString(
            DiagnosticEvents.networkOutageDetail(downtimeMs = 900_000L, thresholdMs = 300_000L)
        ).asJsonObject
        assertEquals(900_000L, detail["downtime_ms"].asLong)
        assertEquals(
            "without the threshold, an operator who later changes it makes every old row unreadable",
            300_000L, detail["threshold_ms"].asLong
        )
    }

    @Test
    fun bluetoothWatchdogDetailCarriesHowLongTheRadioWasOff() {
        val detail = JsonParser.parseString(
            DiagnosticEvents.bluetoothWatchdogDetail(offMs = 61_000L)
        ).asJsonObject
        assertEquals(61_000L, detail["off_ms"].asLong)
    }

    @Test
    fun installFailedDetailCarriesTheReason() {
        val detail = JsonParser.parseString(DiagnosticEvents.installFailedDetail("commit_failed"))
            .asJsonObject
        assertEquals("commit_failed", detail["reason"].asString)
    }

    @Test
    fun installFailedDetailOmitsTheReasonWhenThereIsNone() {
        val detail = JsonParser.parseString(DiagnosticEvents.installFailedDetail(null)).asJsonObject
        assertFalse(detail.has("reason"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "*DiagnosticEventsTest*"`
Expected: FAIL — `Unresolved reference: forKind`.

- [ ] **Step 3: Write the implementation**

Add to `DiagnosticEvents.kt`, beside the existing functions:

```kotlin
    /**
     * The generic entry point, for kinds whose detail is assembled by a builder
     * rather than derived from a decision. The gate is the same one every other
     * function here uses — there is no kind that bypasses the master switch.
     */
    fun forKind(
        settings: Settings?,
        appVersion: String,
        kind: DiagnosticKind,
        occurredAtMs: Long,
        detailJson: String? = null,
        affiliateKey: String? = null
    ): DiagnosticEventResult {
        val identity = identityOf(settings, appVersion) ?: return DiagnosticEventResult.NotEnabled
        return DiagnosticEventResult.Report(
            TelemetryEvent.Diagnostic(
                identity = identity,
                kind = kind,
                occurredAtIso = Instant.ofEpochMilli(occurredAtMs).toString(),
                detailJson = detailJson,
                affiliateKey = affiliateKey
            )
        )
    }

    /** Both numbers, because an operator who later changes the threshold would
     *  otherwise make every stored row uninterpretable. */
    fun networkOutageDetail(downtimeMs: Long, thresholdMs: Long): String =
        JsonObject().apply {
            addProperty("downtime_ms", downtimeMs)
            addProperty("threshold_ms", thresholdMs)
        }.toString()

    fun bluetoothWatchdogDetail(offMs: Long): String =
        JsonObject().apply { addProperty("off_ms", offMs) }.toString()

    /** A short fixed constant chosen at the fire site, never PackageInstaller's
     *  own status text: that is vendor-supplied and would make a poor grouping
     *  key on a dashboard. */
    fun installFailedDetail(reason: String?): String =
        JsonObject().apply {
            if (!reason.isNullOrBlank()) addProperty("reason", reason)
        }.toString()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*DiagnosticEventsTest*"`
Expected: PASS.

- [ ] **Step 5: Mutation-check the gate**

Make `forKind` skip `identityOf` and build the event unconditionally. `analyticsOffReportsNoDiagnosticOfAnyKind` and `nullSettingsReportsNoDiagnosticOfAnyKind` must both fail. Revert. Report the output.

- [ ] **Step 6: Full suite and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/telemetry/DiagnosticEvents.kt app/src/test/java/com/sadaqah/kiosk/telemetry/DiagnosticEventsTest.kt
git commit -m "Report any diagnostic kind through one gated entry point"
```

---

## Task 2: Both managers expose what they already computed

Two classes, the same shape of change: a number each one calculates, discards, and the caller cannot re-derive. No decision moves.

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/recovery/NetworkRecoveryManager.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/recovery/BluetoothRecoveryManager.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/recovery/NetworkRecoveryManagerTest.kt` (exists — append)
- Test: `app/src/test/java/com/sadaqah/kiosk/recovery/BluetoothRecoveryManagerTest.kt` (exists — append)

**Interfaces:**
- Produces, and Task 4 depends on these exact names:
  - `NetworkRecoveryManager.lastOutageMs: Long`
  - `BluetoothRecoveryManager.lastOffMs: Long`
  - `BluetoothRecoveryManager.reEnablesThisOutage: Int`

- [ ] **Step 1: Write the failing tests**

Append to `NetworkRecoveryManagerTest.kt`:

```kotlin
    @Test
    fun theOutageDurationIsZeroBeforeAnyOutage() {
        assertEquals(0L, manager().lastOutageMs)
    }

    @Test
    fun theOutageDurationMatchesTheDowntimeThatTriggeredAutoReinit() {
        val m = manager()
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 900_000L
        assertEquals(NetworkRestoredAction.AutoReinit, m.onNetworkRestored(isLoggedIn = true))
        assertEquals(
            "the call site cannot re-derive this: the timestamp is cleared before the return",
            900_000L, m.lastOutageMs
        )
    }

    @Test
    fun aShortOutageStillRecordsItsOwnDuration() {
        val m = manager()
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 1_000L
        assertEquals(NetworkRestoredAction.ResumeNormally, m.onNetworkRestored(isLoggedIn = true))
        assertEquals(1_000L, m.lastOutageMs)
    }

    /** A short outage must not leave a long one's number standing, or a
     *  diagnostic would report a downtime that never happened. */
    @Test
    fun aShortOutageDoesNotLeaveThePreviousLongOneStale() {
        val m = manager()
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 900_000L
        m.onNetworkRestored(isLoggedIn = true)
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 1_000L
        m.onNetworkRestored(isLoggedIn = true)
        assertEquals(1_000L, m.lastOutageMs)
    }

    @Test
    fun anIgnoredRestorationLeavesTheDurationUntouched() {
        val m = manager()
        m.onNetworkLost(isLoggedIn = true, testMode = false)
        now += 900_000L
        m.onNetworkRestored(isLoggedIn = true)
        m.onNetworkRestored(isLoggedIn = true) // nothing tracked — Ignore
        assertEquals(900_000L, m.lastOutageMs)
    }
```

If the existing test file has no `manager()` helper or mutable `now`, add them matching the file's existing style; do not restructure tests that already pass.

Append to `BluetoothRecoveryManagerTest.kt`:

```kotlin
    @Test
    fun theOffDurationIsMeasuredBeforeTheClockRestarts() {
        val m = manager(offThresholdMs = 60_000L)
        m.onBluetoothOff()
        now += 61_000L
        assertEquals(BluetoothRecoveryAction.ReEnable, m.evaluate(cycleInProgress = false))
        assertEquals(
            "measured before evaluate restarts the clock, or this reads as zero",
            61_000L, m.lastOffMs
        )
    }

    @Test
    fun theFirstReEnableOfAnOutageIsCountedAsTheFirst() {
        val m = manager(offThresholdMs = 60_000L)
        m.onBluetoothOff()
        now += 61_000L
        m.evaluate(cycleInProgress = false)
        assertEquals(1, m.reEnablesThisOutage)
    }

    /**
     * The count is what stops this kind writing ~1,400 rows a day against a
     * queue shared with donations that have not uploaded yet.
     */
    @Test
    fun aSecondReEnableInTheSameOutageIsCountedAsTheSecond() {
        val m = manager(offThresholdMs = 60_000L)
        m.onBluetoothOff()
        now += 61_000L
        m.evaluate(cycleInProgress = false)
        now += 61_000L
        m.evaluate(cycleInProgress = false)
        assertEquals(2, m.reEnablesThisOutage)
    }

    @Test
    fun theCountResetsWhenTheRadioActuallyComesBack() {
        val m = manager(offThresholdMs = 60_000L)
        m.onBluetoothOff()
        now += 61_000L
        m.evaluate(cycleInProgress = false)
        m.onBluetoothOn()
        m.onBluetoothOff()
        now += 61_000L
        m.evaluate(cycleInProgress = false)
        assertEquals("a later outage starts counting again from one", 1, m.reEnablesThisOutage)
    }

    @Test
    fun aTickBelowTheThresholdDoesNotCount() {
        val m = manager(offThresholdMs = 60_000L)
        m.onBluetoothOff()
        now += 10_000L
        assertEquals(BluetoothRecoveryAction.Ignore, m.evaluate(cycleInProgress = false))
        assertEquals(0, m.reEnablesThisOutage)
    }

    @Test
    fun aDeliberateCycleDoesNotCount() {
        val m = manager(offThresholdMs = 60_000L)
        m.onBluetoothOff()
        now += 61_000L
        assertEquals(BluetoothRecoveryAction.Ignore, m.evaluate(cycleInProgress = true))
        assertEquals(0, m.reEnablesThisOutage)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "*RecoveryManagerTest*"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement `NetworkRecoveryManager`**

Add the property, and assign it in `onNetworkRestored` immediately before `networkLostTimestamp` is cleared:

```kotlin
    /** Duration of the most recent outage this manager reported on, kept
     *  because [onNetworkRestored] clears its own timestamp before returning
     *  and the caller has no other way to recover the number. 0 until the first
     *  restoration; an ignored restoration leaves it untouched. */
    var lastOutageMs: Long = 0L
        private set
```

In `onNetworkRestored`, after `val downtime = clock() - networkLostTimestamp` and before the existing `networkLostTimestamp = 0L`:

```kotlin
        lastOutageMs = downtime
```

Change nothing else. The threshold comparison that decides `AutoReinit` stays exactly where it is.

- [ ] **Step 4: Implement `BluetoothRecoveryManager`**

Add both members:

```kotlin
    /** How long the radio had been off when the most recent [evaluate] decided
     *  to re-enable it. Measured before that decision restarts the clock, which
     *  it does deliberately so a radio that refuses to return is retried once
     *  per threshold rather than every tick. */
    var lastOffMs: Long = 0L
        private set

    /** How many re-enables the current outage has produced. The caller reports
     *  only the first: the threshold is a minute and the poll ten seconds, so a
     *  radio that stays dead would otherwise emit ~1,400 rows a day into a queue
     *  shared with donations that have not uploaded yet — and eviction there
     *  keeps the newest rows, so the donations are what would be lost. */
    var reEnablesThisOutage: Int = 0
        private set
```

In `onBluetoothOn`, beside the existing reset:

```kotlin
        reEnablesThisOutage = 0
```

In `evaluate`, replace the `ReEnable` branch's two lines with:

```kotlin
        lastOffMs = clock() - offSinceTimestamp
        reEnablesThisOutage += 1
        offSinceTimestamp = clock()
        return BluetoothRecoveryAction.ReEnable
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*RecoveryManagerTest*"`
Expected: PASS. Every pre-existing test in both files must still pass — these members are additive.

- [ ] **Step 6: Mutation-check the two rules that encode judgement**

Run each, confirm the named test fails, then revert:

1. Move `lastOffMs = clock() - offSinceTimestamp` to *after* `offSinceTimestamp = clock()` → `theOffDurationIsMeasuredBeforeTheClockRestarts` must fail, reading 0.
2. Move `reEnablesThisOutage = 0` out of `onBluetoothOn` → `theCountResetsWhenTheRadioActuallyComesBack` must fail.

- [ ] **Step 7: Full suite and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/recovery/ app/src/test/java/com/sadaqah/kiosk/recovery/
git commit -m "Let the recovery managers report the numbers they already compute"
```

---

## Task 3: `InstallFailed` carries which failure it was

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/update/UpdateManager.kt`

**Interfaces:**
- Produces, and Task 4 depends on it: `UpdateNotification.InstallFailed(val reason: String? = null)`.

The four fire sites collapse into one notification today, so a dashboard cannot tell a preflight refusal from a commit failure. The parameter defaults to null, so every existing `is InstallFailed` arm keeps compiling.

- [ ] **Step 1: Change the type**

At `UpdateManager.kt:33`, change:

```kotlin
    data object InstallFailed : UpdateNotification()
```

to:

```kotlin
    /** [reason] is a short fixed constant chosen at the fire site, never the
     *  installer's own status text — that is vendor-supplied and makes a poor
     *  grouping key. Defaulted so existing `is InstallFailed` arms still compile. */
    data class InstallFailed(val reason: String? = null) : UpdateNotification()
```

- [ ] **Step 2: Give each of the four fire sites its own reason**

Read each site before editing and choose a constant that names what actually failed there, in `lower_snake_case`. The four are at roughly `:334`, `:347`, `:364` and `:403`; the last is the `ApkInstaller.Result.Failed` branch, whose reason is `"commit_failed"`. Pick the other three from what the surrounding code shows has gone wrong — do not guess from the line number.

Each becomes `onNotification(UpdateNotification.InstallFailed("<reason>"))`.

- [ ] **Step 3: Confirm nothing else broke**

```bash
./gradlew testDebugUnitTest assembleDebug
grep -rn "InstallFailed" app/src/main
```

Every `is ... InstallFailed` arm must still compile untouched. Paste the grep in your report, and state which reason you gave each of the four sites and why.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/update/UpdateManager.kt
git commit -m "Say which of the four install failures happened"
```

---

## Task 4: The helper and its three call sites

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt`

**Interfaces:**
- Consumes everything from Tasks 1–3.
- Produces: nothing later tasks use. This is the last task.

**This task has no unit tests, deliberately** — `MainActivity` needs the Android lifecycle and Robolectric would breach the no-new-dependencies constraint. Every decision is already pinned in Tasks 1–3. It must compute nothing: no threshold comparison, no "should I report" check beyond reading a number a manager already decided.

- [ ] **Step 1: Write the helper**

Place it near 3b's telemetry methods:

```kotlin
    /**
     * Records a diagnostic and returns. Never throws, whatever happens inside —
     * every caller is a recovery path, and a diagnostic that breaks the recovery
     * it reports is worse than no diagnostic.
     *
     * [detail] is a lambda rather than a value on purpose: an eager argument
     * would be built *before* this guard is entered, and two callers sit inside
     * while(true) loops on lifecycleScope, which installs no
     * CoroutineExceptionHandler — an escape there kills the loop and the process.
     */
    private fun reportDiagnostic(
        kind: DiagnosticKind,
        detail: (() -> String?)? = null,
        occurredAtMs: Long = System.currentTimeMillis()
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val result = DiagnosticEvents.forKind(
                        settings = CrashContext.settings,
                        appVersion = BuildConfig.VERSION_NAME,
                        kind = kind,
                        occurredAtMs = occurredAtMs,
                        detailJson = detail?.invoke(),
                        affiliateKey = CrashContext.affiliateKey
                    )
                    if (result is DiagnosticEventResult.Report) {
                        val event = result.event
                        // 3b's eagerly-constructed instance, not the `by lazy`
                        // field: lazy's SYNCHRONIZED mode can block if another
                        // thread is mid-init, and a diagnostic is not worth a
                        // stall on a recovery path.
                        crashOutbox.append(event.id, event.table, event.payloadJson())
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.e("Telemetry", "diagnostic ${kind.wire} not recorded: ${t::class.java.name}")
                }
            }
        }
    }
```

3b constructs an eager `TelemetryOutbox` inline when installing the crash handler. Hoist it to a field (`crashOutbox`) so both the handler and this helper use the one instance, and pass that field to `KioskCrashHandler` where the inline construction is today. Do not change what the handler receives in any other way.

- [ ] **Step 2: Wire `network_outage`**

In the `AutoReinit` arm of the `when (networkRecoveryManager.onNetworkRestored(isLoggedIn))` at `:1485`, beside the existing work:

```kotlin
                reportDiagnostic(DiagnosticKind.NETWORK_OUTAGE, detail = {
                    DiagnosticEvents.networkOutageDetail(
                        downtimeMs = networkRecoveryManager.lastOutageMs,
                        thresholdMs = settings.longDowntimeThresholdSec * 1000L
                    )
                })
```

- [ ] **Step 3: Wire `bluetooth_watchdog_fired`**

In the watchdog loop's `ReEnable` arm at `:886`, beside `setBluetoothEnabledHeadless(true)`:

```kotlin
                    // Only the first re-enable of an outage. The manager counts
                    // them; a radio that stays dead must not fill the queue.
                    if (bluetoothRecoveryManager.reEnablesThisOutage == 1) {
                        reportDiagnostic(DiagnosticKind.BLUETOOTH_WATCHDOG_FIRED, detail = {
                            DiagnosticEvents.bluetoothWatchdogDetail(
                                bluetoothRecoveryManager.lastOffMs
                            )
                        })
                    }
```

- [ ] **Step 4: Wire `update_install_failed`**

The `InstallFailed` arm at `:2164` sits inside a value-producing `when (n)` that maps a notification to a toast string. Do **not** put the call in that arm — a side effect inside an expression whose job is to produce a value is the wrong shape, and it would also run inside `runOnUiThread`.

Report at the top of `showUpdateNotification` instead, before the `runOnUiThread` block. `n` is a `val` parameter, so the smart cast holds:

```kotlin
    fun showUpdateNotification(n: com.sadaqah.kiosk.update.UpdateNotification) {
        // Ahead of the toast, and outside runOnUiThread: this is a record, not
        // something the operator is waiting on.
        if (n is com.sadaqah.kiosk.update.UpdateNotification.InstallFailed) {
            reportDiagnostic(DiagnosticKind.UPDATE_INSTALL_FAILED, detail = {
                DiagnosticEvents.installFailedDetail(n.reason)
            })
        }
        runOnUiThread {
```

Leave the `when` exactly as it is.

- [ ] **Step 5: Verify the invariants**

Run all four and paste the real output:

```bash
grep -rn "[Oo]utbox\.append(" app/src/main
grep -rn "\.flush()\|\.activate()" app/src/main
grep -rn "DiagnosticKind\.\(RESTART_TRIGGERED\|SUMUP_REINIT_FAILED\|CARD_READER_CONNECT_FAILED\|CARD_READER_PAGE_TIMEOUT\|CHECKOUT_NO_READER\)" app/src/main
grep -rn "reportDiagnostic(" app/src/main
```

Expected: **5** append sites; **1** real `flush()` and **1** real `activate()` (a third hit is a KDoc line — discount it); **zero** for the five kinds deferred to 3c-ii; and **4** `reportDiagnostic` hits (the declaration plus three call sites). A count that comes out wrong is information — report it rather than editing the grep.

- [ ] **Step 6: Full suite and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/MainActivity.kt
git commit -m "Report a dead radio, a long outage, and a failed install"
```

---

## After the tasks

Not plan steps — the orchestrator's work:

1. **Whole-phase review** over `fcbda4a..HEAD`, dispatched on the most capable model, told to read HEAD state rather than only the diff. Press hardest on the helper's guard — specifically that building `detail` really does happen inside it — and on the once-per-outage rule, since that is what stands between this phase and evicted donation rows.
2. **Push and open a PR** against `telemetry/phase-3b`, stacked on #11. Never a local merge.
3. **The PR body must carry** the spec's device-check list, especially check 1 (Bluetooth off for five minutes must produce exactly one row, not five), and the "Required of 3c-ii" list so the split loses nothing.

## Deferred, on purpose

- The five kinds in 3c-ii, all beside the payment path, with the four requirements the spec records for them.
- The raw vendor logging at the four SumUp sites, including the full `Bundle` dump at `MainActivity:618` — 3c-ii touches those sites anyway.
- `retryableFailure` is still one global boolean; a refused table backs off the healthy ones. Carried from 2a.
- `SettingsBootstrap`'s call site is still untested.
