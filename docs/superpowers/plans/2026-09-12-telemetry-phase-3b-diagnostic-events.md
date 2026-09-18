# Telemetry Phase 3b — Diagnostic Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A kiosk that crashes, reverts a bad update, or comes back on a new build reports it, without anyone being there to notice.

**Architecture:** Three detection sites that are all in a bad position to do work — a dying process, a process about to be replaced by the old APK, and a startup. Each records the smallest durable fact it can, and every decision about what that fact means lives in one pure JVM-tested unit (`DiagnosticEvents`), because the call sites are `MainActivity` and a `BroadcastReceiver` and neither is reachable from a unit test. The existing `TelemetryOutbox` is the only queue; its file-keyed lock was built for this phase's crash handler.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, Gson, minSdk 30. No new third-party dependencies — there is no mocking library and none may be added.

**Spec:** `docs/superpowers/specs/2026-09-12-telemetry-phase-3b-diagnostic-events.md` — read it before Task 1, especially the Non-negotiables.

## Global Constraints

- **No new third-party dependencies.** Do not touch any gradle file. `testImplementation(libs.junit)` is the whole unit-test toolchain — no Mockito, no MockK, no Robolectric.
- **The crash handler must never swallow a crash.** Chaining to the previously installed handler happens in a `finally`, unguarded, on every path including re-entry.
- **Recording must never make the thing it records worse.** Two critical paths get a `try`/`catch (Throwable)` that logs and drops: `KioskCrashHandler.uncaughtException` and the marker write inside `UpdateWatchdogReceiver.onReceive`. An escape from the latter means a bad build is **never rolled back**.
- **A kiosk with `analyticsEnabled` off writes nothing identified to disk.** Not "writes and declines to send".
- **The outbox is the only durable queue.** No journal, no second store.
- **The donation flow is untouched.** No code is added to any path a donation travels.
- **The app never compares version strings.** Emit the `from`/`to` pair; direction is the reader's inference.
- Comments explain a non-obvious *why*, never a *what*. No commented-out code.
- **Never commit or push to `master`.** Work happens on `telemetry/phase-3b`, branched from `telemetry/phase-3a`.
- Commit messages stay short — subject plus at most a short paragraph. Reasoning goes in the PR. **No AI attribution or session links in any commit or PR.**

## Context: what already exists

Read these; do not reimplement them.

- `telemetry/TelemetryEvent.kt` — `TelemetryEvent.Diagnostic(identity, kind, occurredAtIso, detailJson, stackTrace, affiliateKey, id)`. Scrubs and truncates in its constructor. `DiagnosticKind` has all eleven kinds with severities. `EventIdentity.from(settings, appVersion)`. `TelemetryTables.DIAGNOSTICS`.
- `telemetry/TelemetryRedactor.kt` — `scrub(text, affiliateKey)` removes the exact key (case-insensitive) then any run of 32+ `[A-Za-z0-9+=_-]`. `truncate(text)` caps at `MAX_TEXT_BYTES`.
- `telemetry/TelemetryOutbox.kt` — `append(id, table, payload)`. Locking is keyed on the **file**, so two instances over one path are safe.
- `telemetry/DonationEvents.kt` — the pattern this phase copies exactly.
- `update/UpdateWatchdogReceiver.kt` — `onReceive`, and a `companion object` holding `PREFS = "update_state"`, `KEY_LAST_STARTUP_MS`, `KEY_INSTALL_ATTEMPTED_AT`, `prefs(ctx)`, `recordHeartbeat(ctx)`.
- `MainActivity.kt` — `settings` at `:190`, `affiliateKey` at `:192`, `telemetryOutbox by lazy` at `:128`, `SettingsBootstrap.apply` at `:308`, `UpdateWatchdogReceiver.recordHeartbeat(this)` at `:366`.

## File Structure

| File | Responsibility |
|---|---|
| `telemetry/DiagnosticEvents.kt` | **New, pure.** Every decision: whether a diagnostic is reported, what its detail is, and what version to persist. |
| `telemetry/CrashContext.kt` | **New.** Process-scoped `@Volatile` holder so the handler never reads a destroyed Activity. |
| `telemetry/KioskCrashHandler.kt` | **New.** `Thread.UncaughtExceptionHandler`: record, then chain unconditionally. No Android imports. |
| `update/UpdateWatchdogReceiver.kt` | **Modify.** Two new keys and a guarded marker write on the rollback path. |
| `MainActivity.kt` | **Modify.** Install the handler, refresh `CrashContext`, drain the markers at startup. |
| `test/.../DiagnosticEventsTest.kt` | **New.** All decision branches. |
| `test/.../KioskCrashHandlerTest.kt` | **New.** Chaining, including when recording throws. |

---

## Task 1: `DiagnosticEvents` — the pure decision unit

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/DiagnosticEvents.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/DiagnosticEventsTest.kt`

**Interfaces:**
- Consumes: `Settings`, `TelemetryEvent.Diagnostic`, `DiagnosticKind`, `EventIdentity`.
- Produces, and Tasks 2 and 4 depend on these exact names:
  - `sealed class DiagnosticEventResult` with `object NotEnabled`, `object NothingToReport`, `data class Report(val event: TelemetryEvent.Diagnostic)`
  - `data class UpdateInstalledDecision(val result: DiagnosticEventResult, val versionToStore: String)`
  - `DiagnosticEvents.crash(settings: Settings?, appVersion: String, thread: Thread, throwable: Throwable, affiliateKey: String?, occurredAtMs: Long): DiagnosticEventResult`
  - `DiagnosticEvents.updateRollback(settings: Settings?, appVersion: String, rollbackAtMs: Long, fromVersion: String?): DiagnosticEventResult`
  - `DiagnosticEvents.updateInstalled(settings: Settings?, appVersion: String, storedVersion: String, occurredAtMs: Long): UpdateInstalledDecision`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/DiagnosticEventsTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Test

class DiagnosticEventsTest {

    private val appVersion = "1.4.0"

    /** 2023-11-14T22:13:20Z exactly, so the ISO rendering has no fraction. */
    private val atMs = 1_700_000_000_000L

    private val enabled = Settings(
        analyticsEnabled = true,
        kioskCode = "nl-gld-arnhem-nour_al_houda-01",
        installId = "11111111-2222-3333-4444-555555555555"
    )
    private val disabled = enabled.copy(analyticsEnabled = false)

    private fun reported(result: DiagnosticEventResult): TelemetryEvent.Diagnostic =
        (result as DiagnosticEventResult.Report).event

    private fun payload(event: TelemetryEvent.Diagnostic) =
        JsonParser.parseString(event.payloadJson()).asJsonObject

    private fun boom(message: String = "boom"): Throwable =
        IllegalStateException(message).apply { stackTrace = arrayOf() }

    // ── The master switch ────────────────────────────────────────────────────

    @Test
    fun analyticsOffReportsNoCrash() {
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.crash(disabled, appVersion, Thread.currentThread(), boom(), null, atMs)
        )
    }

    @Test
    fun analyticsOffReportsNoRollback() {
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.updateRollback(disabled, appVersion, atMs, "1.4.0")
        )
    }

    @Test
    fun analyticsOffReportsNoUpdateInstalled() {
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.updateInstalled(disabled, appVersion, "1.3.6", atMs).result
        )
    }

    /** Before the first onCreate populates CrashContext there is no identity to
     *  report under — a null must behave like "off", not throw. */
    @Test
    fun nullSettingsReportsNothingRatherThanThrowing() {
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.crash(null, appVersion, Thread.currentThread(), boom(), null, atMs)
        )
        assertEquals(
            DiagnosticEventResult.NotEnabled,
            DiagnosticEvents.updateRollback(null, appVersion, atMs, "1.4.0")
        )
    }

    // ── crash ────────────────────────────────────────────────────────────────

    @Test
    fun crashCarriesKindSeverityAndTheStackTrace() {
        val event = reported(
            DiagnosticEvents.crash(enabled, appVersion, Thread.currentThread(), boom(), null, atMs)
        )
        val row = payload(event)
        assertEquals("crash", row["kind"].asString)
        assertEquals("error", row["severity"].asString)
        assertEquals("2023-11-14T22:13:20Z", row["occurred_at"].asString)
        assertEquals(TelemetryTables.DIAGNOSTICS, event.table)
        assertTrue(row["stack_trace"].asString.contains("IllegalStateException"))
    }

    @Test
    fun crashDetailNamesTheThreadAndWhetherItWasMain() {
        val thread = Thread("payment-worker")
        val event = reported(
            DiagnosticEvents.crash(enabled, appVersion, thread, boom(), null, atMs)
        )
        val detail = payload(event)["detail"].asJsonObject
        assertEquals("payment-worker", detail["thread"].asString)
        assertFalse(detail["main"].asBoolean)
    }

    /** The primary protection: an exact match on the affiliate key. */
    @Test
    fun crashScrubsTheAffiliateKeyOutOfTheStackTrace() {
        val key = "0d1f4c2e-77aa-4b31-9f6e-2c5b8a1d3e40"
        val event = reported(
            DiagnosticEvents.crash(
                enabled, appVersion, Thread.currentThread(), boom("auth failed for $key"), key, atMs
            )
        )
        assertFalse(payload(event)["stack_trace"].asString.contains(key))
    }

    /**
     * The backstop, and the case that actually matters: a testMode kiosk never
     * loads the affiliate key, so at crash time the supplier returns blank and
     * the exact-match rule is inert. A UUID-shaped key is 36 chars of
     * [A-Za-z0-9-], so the 32+ token rule is what holds the line here.
     */
    @Test
    fun crashStillScrubsAKeyShapedTokenWhenNoKeyIsKnown() {
        val key = "0d1f4c2e-77aa-4b31-9f6e-2c5b8a1d3e40"
        val event = reported(
            DiagnosticEvents.crash(
                enabled, appVersion, Thread.currentThread(), boom("auth failed for $key"), "", atMs
            )
        )
        assertFalse(payload(event)["stack_trace"].asString.contains(key))
    }

    // ── update_rollback ──────────────────────────────────────────────────────

    @Test
    fun rollbackCarriesOutcomeAndTheVersionItRolledBackFrom() {
        val event = reported(
            DiagnosticEvents.updateRollback(enabled, "1.3.6", atMs, "1.4.0")
        )
        val row = payload(event)
        assertEquals("update_rollback", row["kind"].asString)
        assertEquals("error", row["severity"].asString)
        assertEquals("2023-11-14T22:13:20Z", row["occurred_at"].asString)
        val detail = row["detail"].asJsonObject
        assertEquals("attempted", detail["outcome"].asString)
        assertEquals(
            "without from_version a failed rollback and a successful one are the same row",
            "1.4.0", detail["from_version"].asString
        )
    }

    @Test
    fun rollbackWithNoRecordedVersionStillReports() {
        val detail = payload(
            reported(DiagnosticEvents.updateRollback(enabled, "1.3.6", atMs, null))
        )["detail"].asJsonObject
        assertEquals("attempted", detail["outcome"].asString)
        assertFalse(detail.has("from_version"))
    }

    // ── update_installed ─────────────────────────────────────────────────────

    @Test
    fun aFirstRunIsAnInstallationNotAnUpdate() {
        val decision = DiagnosticEvents.updateInstalled(enabled, "1.4.0", "", atMs)
        assertEquals(DiagnosticEventResult.NothingToReport, decision.result)
        assertEquals("1.4.0", decision.versionToStore)
    }

    @Test
    fun anUnchangedVersionReportsNothing() {
        assertEquals(
            DiagnosticEventResult.NothingToReport,
            DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.4.0", atMs).result
        )
    }

    @Test
    fun aChangedVersionReportsBothEnds() {
        val detail = payload(
            reported(DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.3.6", atMs).result)
        )["detail"].asJsonObject
        assertEquals("1.3.6", detail["from"].asString)
        assertEquals("1.4.0", detail["to"].asString)
    }

    @Test
    fun updateInstalledIsInfoSeverity() {
        val row = payload(
            reported(DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.3.6", atMs).result)
        )
        assertEquals("update_installed", row["kind"].asString)
        assertEquals("info", row["severity"].asString)
    }

    /**
     * A rollback moves the version backwards. The app does not decide that is a
     * downgrade — it reports the pair and lets the reader infer direction,
     * because versionName has carried a -preview suffix and any in-app
     * comparison rule would be a guess.
     */
    @Test
    fun aBackwardsMoveReportsTheSameWayWithThePairReversed() {
        val detail = payload(
            reported(DiagnosticEvents.updateInstalled(enabled, "1.3.6", "1.4.0", atMs).result)
        )["detail"].asJsonObject
        assertEquals("1.4.0", detail["from"].asString)
        assertEquals("1.3.6", detail["to"].asString)
    }

    // ── The always-advance rule ──────────────────────────────────────────────

    /**
     * The phase's most subtle rule. If the stored version were held back while
     * analytics was off, an operator enabling it months later would get a false
     * "this kiosk just updated" for a build it had been running since spring.
     */
    @Test
    fun theStoredVersionAdvancesEvenWhenNothingIsReported() {
        assertEquals(
            "1.4.0",
            DiagnosticEvents.updateInstalled(disabled, "1.4.0", "1.3.6", atMs).versionToStore
        )
        assertEquals(
            "1.4.0",
            DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.4.0", atMs).versionToStore
        )
        assertEquals(
            "1.4.0",
            DiagnosticEvents.updateInstalled(enabled, "1.4.0", "1.3.6", atMs).versionToStore
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "*DiagnosticEventsTest*"`
Expected: FAIL — `Unresolved reference: DiagnosticEvents`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/DiagnosticEvents.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.sadaqah.kiosk.model.Settings
import java.time.Instant

sealed class DiagnosticEventResult {
    /** The operator declined analytics, or no identity is loaded yet. */
    object NotEnabled : DiagnosticEventResult()

    /** Analytics is on and there is simply nothing worth reporting. Distinct
     *  from [NotEnabled] because the version tracker must still advance. */
    object NothingToReport : DiagnosticEventResult()

    data class Report(val event: TelemetryEvent.Diagnostic) : DiagnosticEventResult()
}

/**
 * The report decision and the value the caller must persist, together. Returned
 * as one value so the always-advance rule is pinned by a test rather than by a
 * comment at a call site no test can reach.
 */
data class UpdateInstalledDecision(
    val result: DiagnosticEventResult,
    val versionToStore: String
)

/**
 * Decides whether a diagnostic is reported and in what shape.
 *
 * Pure, and that is the point: the callers are `MainActivity` and a
 * `BroadcastReceiver`, and no unit test can reach either. Same reasoning as
 * [DonationEvents], and the same bug it was written to avoid.
 */
object DiagnosticEvents {

    fun crash(
        settings: Settings?,
        appVersion: String,
        thread: Thread,
        throwable: Throwable,
        affiliateKey: String?,
        occurredAtMs: Long
    ): DiagnosticEventResult {
        val identity = identityOf(settings, appVersion) ?: return DiagnosticEventResult.NotEnabled
        val detail = JsonObject().apply {
            addProperty("thread", thread.name)
            addProperty("main", thread.name == "main")
        }
        return DiagnosticEventResult.Report(
            TelemetryEvent.Diagnostic(
                identity = identity,
                kind = DiagnosticKind.CRASH,
                occurredAtIso = Instant.ofEpochMilli(occurredAtMs).toString(),
                detailJson = detail.toString(),
                // Rendered here rather than at the call site so the rendering is
                // covered; Diagnostic's constructor scrubs and truncates it.
                stackTrace = throwable.stackTraceToString(),
                affiliateKey = affiliateKey
            )
        )
    }

    fun updateRollback(
        settings: Settings?,
        appVersion: String,
        rollbackAtMs: Long,
        fromVersion: String?
    ): DiagnosticEventResult {
        val identity = identityOf(settings, appVersion) ?: return DiagnosticEventResult.NotEnabled
        val detail = JsonObject().apply {
            // "attempted" is the honest word: the install being waited on
            // replaces the process waiting for it, so no outcome is knowable
            // here. from_version is what makes the outcome recoverable —
            // equal to the row's app_version means the rollback did not take.
            addProperty("outcome", "attempted")
            if (!fromVersion.isNullOrBlank()) addProperty("from_version", fromVersion)
        }
        return DiagnosticEventResult.Report(
            TelemetryEvent.Diagnostic(
                identity = identity,
                kind = DiagnosticKind.UPDATE_ROLLBACK,
                occurredAtIso = Instant.ofEpochMilli(rollbackAtMs).toString(),
                detailJson = detail.toString(),
                affiliateKey = null
            )
        )
    }

    fun updateInstalled(
        settings: Settings?,
        appVersion: String,
        storedVersion: String,
        occurredAtMs: Long
    ): UpdateInstalledDecision {
        // Computed before any gate: the stored version advances whatever the
        // outcome, or enabling analytics later manufactures a false update.
        val decision = { result: DiagnosticEventResult ->
            UpdateInstalledDecision(result, appVersion)
        }
        val identity = identityOf(settings, appVersion)
            ?: return decision(DiagnosticEventResult.NotEnabled)
        // An empty stored version is a first run, which is an installation
        // rather than an update, so it seeds and reports nothing.
        if (storedVersion.isBlank() || storedVersion == appVersion) {
            return decision(DiagnosticEventResult.NothingToReport)
        }
        val detail = JsonObject().apply {
            addProperty("from", storedVersion)
            addProperty("to", appVersion)
        }
        return decision(
            DiagnosticEventResult.Report(
                TelemetryEvent.Diagnostic(
                    identity = identity,
                    kind = DiagnosticKind.UPDATE_INSTALLED,
                    occurredAtIso = Instant.ofEpochMilli(occurredAtMs).toString(),
                    detailJson = detail.toString(),
                    affiliateKey = null
                )
            )
        )
    }

    private fun identityOf(settings: Settings?, appVersion: String): EventIdentity? {
        if (settings == null || !settings.analyticsEnabled) return null
        return EventIdentity.from(settings, appVersion)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*DiagnosticEventsTest*"`
Expected: PASS, 17 tests.

- [ ] **Step 5: Mutation-check the three rules that encode judgement**

Run each, confirm the named test fails, then revert:

1. Make `versionToStore` return `storedVersion` instead of `appVersion` → `theStoredVersionAdvancesEvenWhenNothingIsReported` must fail.
2. Drop the `storedVersion.isBlank()` arm → `aFirstRunIsAnInstallationNotAnUpdate` must fail.
3. Pass `affiliateKey = null` from `crash` → `crashScrubsTheAffiliateKeyOutOfTheStackTrace` must fail while `crashStillScrubsAKeyShapedTokenWhenNoKeyIsKnown` still passes, proving the two tests pin different rules.

- [ ] **Step 6: Full suite and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/telemetry/DiagnosticEvents.kt app/src/test/java/com/sadaqah/kiosk/telemetry/DiagnosticEventsTest.kt
git commit -m "Decide in a testable place what a diagnostic reports"
```

---

## Task 2: `CrashContext` and `KioskCrashHandler`

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/CrashContext.kt`
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/KioskCrashHandler.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/KioskCrashHandlerTest.kt`

**Interfaces:**
- Consumes: `DiagnosticEvents.crash(...)`, `DiagnosticEventResult`, `TelemetryOutbox`.
- Produces, and Task 4 depends on these exact names:
  - `object CrashContext { @Volatile var settings: Settings?; @Volatile var affiliateKey: String? }`
  - `class KioskCrashHandler(previous, outbox, settings, affiliateKey, appVersion, clock, exitProcess) : Thread.UncaughtExceptionHandler`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/KioskCrashHandlerTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KioskCrashHandlerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val appVersion = "1.4.0"
    private val atMs = 1_700_000_000_000L

    private val enabled = Settings(
        analyticsEnabled = true,
        kioskCode = "nl-gld-arnhem-nour_al_houda-01",
        installId = "11111111-2222-3333-4444-555555555555"
    )

    /** Records what it was handed, so a test can prove the chain ran with the
     *  same thread and throwable rather than merely that something ran. */
    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var calls = 0
        var lastThread: Thread? = null
        var lastThrowable: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) {
            calls++
            lastThread = t
            lastThrowable = e
        }
    }

    private fun outbox(file: File = temp.newFile("outbox.jsonl")) =
        TelemetryOutbox(file, clock = { atMs })

    /**
     * There is no mocking library and TelemetryOutbox is final, so "append
     * throws" is produced with the real class over an impossible path: the
     * parent is a regular file, so mkdirs() returns false and appendText
     * throws FileNotFoundException.
     */
    private fun unwritableOutbox() =
        TelemetryOutbox(File(temp.newFile("blocker"), "outbox.jsonl"), clock = { atMs })

    private fun handler(
        previous: Thread.UncaughtExceptionHandler?,
        box: TelemetryOutbox = outbox(),
        settings: () -> Settings? = { enabled },
        affiliateKey: () -> String? = { null },
        exitProcess: (Int) -> Unit = { fail("must not exit while a previous handler exists") }
    ) = KioskCrashHandler(previous, box, settings, affiliateKey, appVersion, { atMs }, exitProcess)

    @Test
    fun chainsToThePreviousHandlerWithTheSameThreadAndThrowable() {
        val previous = RecordingHandler()
        val thread = Thread("payment-worker")
        val boom = IllegalStateException("boom")
        handler(previous).uncaughtException(thread, boom)
        assertEquals(1, previous.calls)
        assertSame(thread, previous.lastThread)
        assertSame(boom, previous.lastThrowable)
    }

    @Test
    fun appendsExactlyOneRowOnACrash() {
        val file = temp.newFile("outbox.jsonl")
        handler(RecordingHandler(), box = outbox(file))
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(1, outbox(file).size())
    }

    /**
     * The decisive test. hardRestart and the update watchdog both depend on
     * normal crash behaviour, so a failure to record must never cost the chain.
     */
    @Test
    fun chainsEvenWhenRecordingThrows() {
        val previous = RecordingHandler()
        handler(previous, box = unwritableOutbox())
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals("a failed append must not swallow the crash", 1, previous.calls)
    }

    @Test
    fun chainsEvenWhenTheSettingsSupplierThrows() {
        val previous = RecordingHandler()
        handler(previous, settings = { error("settings read failed") })
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(1, previous.calls)
    }

    @Test
    fun chainsAndRecordsNothingWhenAnalyticsIsOff() {
        val previous = RecordingHandler()
        val file = temp.newFile("outbox.jsonl")
        handler(previous, box = outbox(file), settings = { enabled.copy(analyticsEnabled = false) })
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(1, previous.calls)
        assertEquals(0, outbox(file).size())
    }

    @Test
    fun chainsAndRecordsNothingBeforeAnIdentityIsLoaded() {
        val previous = RecordingHandler()
        val file = temp.newFile("outbox.jsonl")
        handler(previous, box = outbox(file), settings = { null })
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(1, previous.calls)
        assertEquals(0, outbox(file).size())
    }

    /**
     * Returning from uncaughtException ends the thread, not the process. On the
     * main thread that leaves a kiosk running, unresponsive and showing its last
     * frame — which the watchdog's heartbeat cannot see either.
     */
    @Test
    fun exitsRatherThanReturningWhenThereIsNoPreviousHandler() {
        val exitCodes = mutableListOf<Int>()
        KioskCrashHandler(null, outbox(), { enabled }, { null }, appVersion, { atMs }) {
            exitCodes += it
        }.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(listOf(2), exitCodes)
    }

    /**
     * Chaining is deliberately unguarded, so a re-entrant call chains too and
     * the outer finally chains again. On a device the first chain kills the
     * process, so the second is only observable here.
     */
    @Test
    fun reEntrantInvocationRecordsOnceAndAlwaysChains() {
        val previous = RecordingHandler()
        val file = temp.newFile("outbox.jsonl")
        lateinit var handler: KioskCrashHandler
        var reentered = false
        val reentrantOutbox = object {
            fun trigger() {
                if (!reentered) {
                    reentered = true
                    handler.uncaughtException(Thread.currentThread(), IllegalStateException("again"))
                }
            }
        }
        handler = KioskCrashHandler(
            previous, outbox(file),
            { reentrantOutbox.trigger(); enabled },
            { null }, appVersion, { atMs }, { fail("must not exit") }
        )
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals("the re-entrant call must not record a second row", 1, outbox(file).size())
        assertTrue("chaining is never suppressed", previous.calls >= 1)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "*KioskCrashHandlerTest*"`
Expected: FAIL — `Unresolved reference: KioskCrashHandler`.

- [ ] **Step 3: Write `CrashContext`**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/CrashContext.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings

/**
 * What the crash handler reads, held at process scope rather than on the
 * Activity.
 *
 * The uncaught-exception handler is process-global and installed once, but
 * `MainActivity` declares no `android:configChanges` and is recreated on any
 * configuration change. A handler holding the first Activity would read a dead
 * instance from then on — so an operator who turned analytics off would still
 * get a crash row. Plain @Volatile rather than Compose snapshot state because a
 * snapshot read from a dying thread could block on the snapshot lock, and a
 * blocked crash handler is the hung kiosk the chain exists to prevent.
 */
object CrashContext {
    @Volatile var settings: Settings? = null
    @Volatile var affiliateKey: String? = null
}
```

- [ ] **Step 4: Write `KioskCrashHandler`**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/KioskCrashHandler.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings

/**
 * Records a crash, then gets out of the way.
 *
 * No Android imports, so the chaining behaviour — the part that must never
 * regress — is exercised on the JVM rather than trusted.
 */
class KioskCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val outbox: TelemetryOutbox,
    private val settings: () -> Settings?,
    private val affiliateKey: () -> String?,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exitProcess: (Int) -> Unit = { Runtime.getRuntime().exit(it) }
) : Thread.UncaughtExceptionHandler {

    /** Set before recording and never cleared: on Android any uncaught
     *  exception reaching the default handler is fatal, so there is no second
     *  crash worth reporting. @Volatile gives the visibility a same-thread
     *  re-entry needs; two threads crashing at once can both record, which is
     *  two wanted rows the outbox tolerates. */
    @Volatile
    private var recording = false

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (!recording) {
                recording = true
                // The supplier calls are inside the guard because they can throw
                // too — settings() reads a holder another thread is writing.
                val result = DiagnosticEvents.crash(
                    settings = settings(),
                    appVersion = appVersion,
                    thread = thread,
                    throwable = throwable,
                    affiliateKey = affiliateKey(),
                    occurredAtMs = clock()
                )
                if (result is DiagnosticEventResult.Report) {
                    val event = result.event
                    outbox.append(event.id, event.table, event.payloadJson())
                }
            }
        } catch (_: Throwable) {
            // Deliberately swallowed, and deliberately not logged: this runs in
            // a dying process where a logging call is one more thing that can
            // fail before the chain below.
        } finally {
            // The single most important line in this phase. hardRestart and the
            // update watchdog both depend on normal crash behaviour, and the
            // watchdog's whole rollback decision is "did the new build write a
            // heartbeat before dying".
            val chain = previous
            if (chain != null) {
                chain.uncaughtException(thread, throwable)
            } else {
                // Returning here would end the thread, not the process, leaving
                // an unresponsive kiosk the watchdog cannot detect either.
                // Runtime.exit runs shutdown hooks and can in principle hang
                // where halt() cannot; exit matches hardRestart and this branch
                // is near-unreachable, since RuntimeInit installs a default
                // handler before any app code runs.
                exitProcess(2)
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*KioskCrashHandlerTest*"`
Expected: PASS, 8 tests.

- [ ] **Step 6: Mutation-check the chain**

Run each, confirm the named test fails, then revert:

1. Move the chaining out of `finally` to the end of the `try` → `chainsEvenWhenRecordingThrows` must fail. This is the mutation that matters most; if it passes, the test is not proving anything.
2. Replace `exitProcess(2)` with a bare `return` → `exitsRatherThanReturningWhenThereIsNoPreviousHandler` must fail.
3. Remove the `recording` guard → `reEntrantInvocationRecordsOnceAndAlwaysChains` must fail on the row count.

- [ ] **Step 7: Full suite and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/telemetry/CrashContext.kt app/src/main/java/com/sadaqah/kiosk/telemetry/KioskCrashHandler.kt app/src/test/java/com/sadaqah/kiosk/telemetry/KioskCrashHandlerTest.kt
git commit -m "Record a crash without ever swallowing it"
```

---

## Task 3: Mark the rollback, without ever preventing it

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/update/UpdateWatchdogReceiver.kt`

**Interfaces:**
- Produces, and Task 4 depends on these exact names, added to the existing `companion object`:
  - `const val KEY_ROLLBACK_AT = "update_rollback_at_ms"`
  - `const val KEY_ROLLBACK_FROM_VERSION = "update_rollback_from_version"`

**This task edits the most consequential non-donation path in the app.** `onReceive` currently has no try/catch anywhere before `goAsync()`. An unguarded `SharedPreferences` write that throws — a full disk is the realistic case, and a kiosk offline for a month accumulating an outbox is exactly that kiosk — aborts `onReceive` and **the bad build is never rolled back**. Read the spec's non-negotiable #2 before starting.

- [ ] **Step 1: Add the two keys to the companion object**

In `UpdateWatchdogReceiver.kt`, alongside `KEY_LAST_STARTUP_MS` and `KEY_INSTALL_ATTEMPTED_AT`:

```kotlin
        /** Set when a rollback is about to be attempted, and read once at the
         *  next startup. Not a queue: these do not survive being read. */
        const val KEY_ROLLBACK_AT = "update_rollback_at_ms"
        const val KEY_ROLLBACK_FROM_VERSION = "update_rollback_from_version"
```

- [ ] **Step 2: Write the guarded marker, immediately before the installer call**

In `onReceive`, after `Log.w("UpdateWatchdog", "Rolling back to ...")` and **before** `val pending = goAsync()`:

```kotlin
        // Before the install, so a process that dies mid-install still leaves
        // the fact behind — and commit() rather than apply(), because the very
        // next thing that happens is an installer replacing this process, which
        // an asynchronous write has no guarantee of beating.
        //
        // Wrapped because nothing else in onReceive is: a throw here would
        // abort the receiver before goAsync() and the bad build would never be
        // rolled back. Telemetry must never be the reason a rollback is lost.
        try {
            prefs(context).edit()
                .putLong(KEY_ROLLBACK_AT, System.currentTimeMillis())
                .putString(KEY_ROLLBACK_FROM_VERSION, BuildConfig.VERSION_NAME)
                .commit()
        } catch (t: Throwable) {
            Log.e("UpdateWatchdog", "Could not record rollback marker: ${t::class.java.name}")
        }
```

Add `import com.sadaqah.kiosk.BuildConfig` to the file's imports.

- [ ] **Step 3: Verify the guard by reading, since this path has no unit test**

Confirm all three by eye, and quote them in the task report:
1. The `try`/`catch (Throwable)` encloses the whole `edit()...commit()` chain, including `prefs(context)` itself.
2. The marker write sits **after** the `backupApk.exists()` check and **before** `goAsync()`.
3. Nothing between the `catch` and `goAsync()` can throw.

- [ ] **Step 4: Build and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/update/UpdateWatchdogReceiver.kt
git commit -m "Record that a rollback was attempted, without risking the rollback"
```

---

## Task 4: Install the handler and drain the markers

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt`

**Interfaces:**
- Consumes: `CrashContext`, `KioskCrashHandler`, `DiagnosticEvents`, `DiagnosticEventResult`, `UpdateInstalledDecision`, `UpdateWatchdogReceiver.KEY_ROLLBACK_AT`, `KEY_ROLLBACK_FROM_VERSION`, `KEY_REPORTED_VERSION`.
- Produces: nothing other tasks consume. This is the last task.

**This task has no unit tests, and that is deliberate** — it is wiring over decisions Tasks 1–3 already pinned. It must compute nothing.

- [ ] **Step 1: Add the reported-version key**

In `UpdateWatchdogReceiver`'s companion object, beside the keys from Task 3:

```kotlin
        /** The versionName this kiosk last started under. Always advanced, even
         *  when analytics is off — see DiagnosticEvents.updateInstalled. */
        const val KEY_REPORTED_VERSION = "update_reported_version"
```

- [ ] **Step 2: Refresh `CrashContext` wherever settings or the key change**

Three sites, all required — a stale holder is how an operator who turned analytics off still gets a crash row.

**(a)** In `onCreate`, immediately after the `SettingsBootstrap.apply` block ends (`:308-314`), so the seed carries a minted `installId`:

```kotlin
        // The crash handler is process-global and outlives this Activity, which
        // is recreated on any configuration change. It reads this holder rather
        // than the Activity so it can never report against a dead instance.
        CrashContext.settings = settings
```

**(b)** At the end of `onSettingsChange` (`:1674-1682`), so a mid-session analytics toggle is honoured:

```kotlin
        CrashContext.settings = newSettings
```

**(c)** Wherever `affiliateKey` is assigned — `:275` (`affiliateKey = storedKey`) and the `onAffiliateKeyChange` lambda at `:485`:

```kotlin
        CrashContext.affiliateKey = affiliateKey
```

The key matters for scrubbing, not for identity: without it the redactor falls back to its 32+ token rule, which does catch a UUID-shaped key — but the exact-match rule is the primary protection and should be armed as soon as the key is known.

- [ ] **Step 3: Install the crash handler in `onCreate`**

After the `CrashContext` seed from Step 2:

```kotlin
        // Install once: onCreate runs again on every Activity recreation, and a
        // handler whose `previous` is the last one would build a chain that
        // grows with each. Freshness comes from CrashContext, refreshed above.
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        if (existing !is KioskCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(
                KioskCrashHandler(
                    previous = existing,
                    // Its own instance, constructed eagerly: sharing the lazy
                    // telemetryOutbox would let a crash be what triggers its
                    // initialisation, and lazy's SYNCHRONIZED mode can block if
                    // another thread is mid-init. The outbox's lock is keyed on
                    // the file, so two instances over one path are safe.
                    outbox = TelemetryOutbox(
                        File(File(filesDir, "telemetry").apply { mkdirs() }, "outbox.jsonl")
                    ),
                    settings = { CrashContext.settings },
                    affiliateKey = { CrashContext.affiliateKey },
                    appVersion = BuildConfig.VERSION_NAME
                )
            )
        }
```

- [ ] **Step 4: Drain the markers at startup**

Place the call **after** `UpdateWatchdogReceiver.recordHeartbeat(this)` (`:366`) and after `SettingsBootstrap.apply` (`:308`). Both anchors matter: before the bootstrap, rows would carry `install_id: ""`; and see the catch-all comment below for why the heartbeat must land first.

```kotlin
    /**
     * Turns the two update markers into events, once, at startup.
     *
     * Wrapped for the same reason appendDonationTelemetry and flushTelemetry
     * are: lifecycleScope installs no CoroutineExceptionHandler, so an escaping
     * throwable reaches the default handler and kills the process. Here that is
     * worse than a crash — if the death lands inside the 60s watchdog window
     * and ahead of recordHeartbeat's asynchronous apply(), the watchdog reads a
     * stale heartbeat from disk and rolls back a build that was fine.
     */
    private fun drainUpdateDiagnostics() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val prefs = UpdateWatchdogReceiver.prefs(this@MainActivity)
                    val rollbackAt = prefs.getLong(UpdateWatchdogReceiver.KEY_ROLLBACK_AT, 0L)
                    val rollbackFrom =
                        prefs.getString(UpdateWatchdogReceiver.KEY_ROLLBACK_FROM_VERSION, null)
                    val storedVersion =
                        prefs.getString(UpdateWatchdogReceiver.KEY_REPORTED_VERSION, "") ?: ""
                    val now = System.currentTimeMillis()

                    val events = mutableListOf<TelemetryEvent.Diagnostic>()
                    // Rollback first, so a reader scanning by insertion order
                    // sees cause before effect: a rollback is also a version
                    // change, so both fire at the same startup.
                    if (rollbackAt > 0L) {
                        val result = DiagnosticEvents.updateRollback(
                            CrashContext.settings, BuildConfig.VERSION_NAME, rollbackAt, rollbackFrom
                        )
                        if (result is DiagnosticEventResult.Report) events += result.event
                    }
                    val installed = DiagnosticEvents.updateInstalled(
                        CrashContext.settings, BuildConfig.VERSION_NAME, storedVersion, now
                    )
                    (installed.result as? DiagnosticEventResult.Report)?.let { events += it.event }

                    events.forEach { telemetryOutbox.append(it.id, it.table, it.payloadJson()) }

                    // Cleared only once every append has returned. An append
                    // that throws leaves the markers and the event is reported
                    // next startup instead — duplicating a diagnostic is cheap,
                    // losing the only record that a kiosk reverted is not.
                    prefs.edit()
                        .remove(UpdateWatchdogReceiver.KEY_ROLLBACK_AT)
                        .remove(UpdateWatchdogReceiver.KEY_ROLLBACK_FROM_VERSION)
                        .putString(
                            UpdateWatchdogReceiver.KEY_REPORTED_VERSION, installed.versionToStore
                        )
                        .apply()
                } catch (t: Throwable) {
                    Log.e("Telemetry", "update diagnostics drain failed: ${t::class.java.name}")
                }
            }
        }
    }
```

No flush is triggered here: 3a's four triggers already drain the queue, and a startup flush would put network work on the path that renders the first frame.

- [ ] **Step 5: Verify the inertness invariant**

Run exactly this — a literal `outbox.append(` does not match `telemetryOutbox.append(`:

```bash
rg -n '[Oo]utbox\.append\(' app/src/main
rg -n '\.flush\(\)|\.activate\(\)' app/src/main
rg -n 'RESTART_TRIGGERED|SUMUP_REINIT_FAILED|CARD_READER_CONNECT_FAILED|CARD_READER_PAGE_TIMEOUT|CHECKOUT_NO_READER|BLUETOOTH_WATCHDOG_FIRED|NETWORK_OUTAGE|UPDATE_INSTALL_FAILED' app/src/main
```

Expected: **4** append hits (TelemetryManager's activation row, the donation path, this drain, the crash handler); **1** real `flush()` caller and **1** real `activate()` caller (the flush/activate grep also matches a KDoc reference — discount comment lines); and **zero** constructions of the eight kinds deferred to 3c. Note the third grep must be `DiagnosticKind\.<NAME>`: the enum has declared all eleven kinds since phase 1, so grepping bare names always hits the enum body and can never read zero. Paste all three outputs in the task report.

- [ ] **Step 6: Full suite, build, and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/MainActivity.kt app/src/main/java/com/sadaqah/kiosk/update/UpdateWatchdogReceiver.kt
git commit -m "Report crashes, rollbacks, and the build a kiosk came back on"
```

---

## After the tasks

Not plan steps — the orchestrator's work:

1. **Whole-phase review** over `04db803..HEAD`, dispatched on opus, told to read HEAD state rather than only the diff. Press hardest on `UpdateWatchdogReceiver.onReceive`: it is the one edit in this phase that can cost a kiosk its rollback, and it has no unit test.
2. **Push and open a PR** against `telemetry/phase-3a`, stacked on #6. Never a local merge.
3. **The PR body must carry** the device-check list from the spec — especially check 7, filling the disk and forcing a rollback, which is the regression test for the only Critical the spec review found.

## Deferred, on purpose

- The eight remaining diagnostic kinds (3c), most of which sit on the card-reader and SumUp paths.
- `retryableFailure` is still one global boolean; a refused table backs off the healthy ones. Carried from 2a.
- `SettingsBootstrap`'s call site is still untested and needs instrumented coverage.
- `formatTimestamp` in `AnalyticsSettingsScreen.kt` remains the one computation in the untestable file.
