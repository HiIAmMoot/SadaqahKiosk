# Telemetry Phase 3c-ii — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The last five diagnostic kinds, including the auto-restart — which until now took the reason for the restart down with it.

**Architecture:** Nothing is written to the outbox from a thread about to end the process. A restart records a bounded prefs marker and the next startup converts it, the pattern 3b already ships. Every decision — what a restart should report, whether a give-up has already been reported, whether an activity result was the app's own doing — lives in a pure unit, because the call sites are `MainActivity` and no unit test reaches them.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, Gson, minSdk 30. No new third-party dependencies.

**Spec:** `docs/superpowers/specs/2026-09-13-telemetry-phase-3c-ii-payment-path-diagnostics.md` — read it before Task 1. It went through six review rounds and the Non-negotiables are where the findings landed.

## Global Constraints

- **No new third-party dependencies.** No gradle file. JUnit 4 only — no Mockito, no MockK, no Robolectric.
- **The donation flow is not altered.** No new abort condition, no new pre-check, no reordering. `makePayment` is not touched.
- **Nothing is written to the outbox from a thread about to end the process.**
- **Nothing unredacted reaches a file.** Marker detail is scrubbed **before** it is stored, with `CrashContext.affiliateKey` as the key.
- **A kiosk with `analyticsEnabled` off writes nothing identified to disk.**
- **`recordCardReaderFailure()` and `recordReinitFailure()` are called exactly once per failure.** A double call restarts a kiosk early.
- Comments explain a non-obvious *why*, never a *what*. No commented-out code.
- **Never commit or push to `master`.** Work happens on `telemetry/phase-3c-ii`.
- Commit messages stay short. **No AI attribution or session links.**

## Context: what already exists

- `telemetry/DiagnosticEvents.kt` — `forKind(settings, appVersion, kind, occurredAtMs, detailJson, affiliateKey)`, the private `identityOf` gate, `DiagnosticEventResult`, and three detail builders (`networkOutageDetail`, `bluetoothWatchdogDetail`, `installFailedDetail`).
- `telemetry/DiagnosticReporter.record` — the guarded body; `detail` is a lambda invoked inside the guard.
- `MainActivity.reportDiagnostic(kind, detail, occurredAtMs)` — the asynchronous dispatcher.
- `MainActivity.drainUpdateDiagnostics()` at `:2064` — 3b's marker drain, off-main, catch-all, clears only after append returns.
- `update/UpdateWatchdogReceiver.prefs(ctx)` — the prefs file markers live in.
- `recovery/RestartManager.kt` — `recordCardReaderFailure()`, `recordReinitFailure()`, `clearCounters()`, `tryRestart()`, and `RestartResult` with four arms.
- `recovery/KeyValueStore.kt` — `getInt`/`putInt`/`getLong`/`putLong` only. **No boolean.**
- `telemetry/TelemetryRedactor.scrub(text, affiliateKey)`.

## File Structure

| File | Responsibility |
|---|---|
| `telemetry/PendingDiagnostics.kt` | **New, pure.** Encode/decode/add/remove the marker list. String in, string out. |
| `telemetry/PendingDiagnosticStore.kt` | **New.** Owns the prefs key and serialises the read-modify-write. |
| `telemetry/RestartReporting.kt` | **New, pure.** What a `RestartResult` should report, and whether it sets the give-up latch. |
| `telemetry/DiagnosticEvents.kt` | **Modify.** Detail builders for the new kinds. |
| `recovery/RestartManager.kt` | **Modify.** The give-up latch: one Int key, a reader, a setter, two clears. |
| `MainActivity.kt` | **Modify.** Synthetic-close slots, five call sites, `reportRestart`, the drain, the logcat cleanup. |

---

## Task 1: `PendingDiagnostics` — the pure marker list

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/PendingDiagnostics.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/PendingDiagnosticsTest.kt`

**Interfaces — later tasks depend on these exact names:**
- `data class PendingDiagnostic(val id: String, val kind: DiagnosticKind, val occurredAtMs: Long, val detailJson: String?)`
- `PendingDiagnostics.MAX_ENTRIES: Int` = 8
- `PendingDiagnostics.encode(pending: List<PendingDiagnostic>): String`
- `PendingDiagnostics.decode(raw: String?): List<PendingDiagnostic>`
- `PendingDiagnostics.add(raw: String?, entries: List<PendingDiagnostic>): String`
- `PendingDiagnostics.remove(raw: String?, drainedIds: Set<String>): String`

- [ ] **Step 1: Write the failing tests**

Create `PendingDiagnosticsTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Test

class PendingDiagnosticsTest {

    private fun entry(
        id: String,
        kind: DiagnosticKind = DiagnosticKind.RESTART_TRIGGERED,
        atMs: Long = 1_700_000_000_000L,
        detail: String? = null
    ) = PendingDiagnostic(id, kind, atMs, detail)

    @Test
    fun anEntryRoundTrips() {
        val entries = listOf(entry("a", detail = """{"reason":"card_reader_failures"}"""))
        val decoded = PendingDiagnostics.decode(PendingDiagnostics.encode(entries))
        assertEquals(1, decoded.size)
        assertEquals("a", decoded[0].id)
        assertEquals(DiagnosticKind.RESTART_TRIGGERED, decoded[0].kind)
        assertEquals(1_700_000_000_000L, decoded[0].occurredAtMs)
        assertEquals("""{"reason":"card_reader_failures"}""", decoded[0].detailJson)
    }

    @Test
    fun anEntryWithNoDetailRoundTrips() {
        val decoded = PendingDiagnostics.decode(
            PendingDiagnostics.encode(listOf(entry("a", detail = null)))
        )
        assertNull(decoded[0].detailJson)
    }

    /**
     * The kind is stored by its wire string, never its ordinal: an ordinal
     * would silently re-map every stored entry the day a kind is inserted into
     * the middle of the enum.
     */
    @Test
    fun theKindIsStoredByWireStringNotOrdinal() {
        val encoded = PendingDiagnostics.encode(
            listOf(entry("a", kind = DiagnosticKind.CARD_READER_PAGE_TIMEOUT))
        )
        assertTrue(encoded.contains(DiagnosticKind.CARD_READER_PAGE_TIMEOUT.wire))
    }

    @Test
    fun addAppendsToAnExistingList() {
        val first = PendingDiagnostics.add(null, listOf(entry("a")))
        val both = PendingDiagnostics.add(first, listOf(entry("b")))
        assertEquals(listOf("a", "b"), PendingDiagnostics.decode(both).map { it.id })
    }

    /** A restart writes both its markers in one commit. */
    @Test
    fun addTakesSeveralEntriesAtOnce() {
        val raw = PendingDiagnostics.add(null, listOf(entry("a"), entry("b")))
        assertEquals(listOf("a", "b"), PendingDiagnostics.decode(raw).map { it.id })
    }

    @Test
    fun addKeepsOnlyTheNewestEntriesWhenTheCapIsExceeded() {
        var raw: String? = null
        repeat(PendingDiagnostics.MAX_ENTRIES + 3) { i ->
            raw = PendingDiagnostics.add(raw, listOf(entry("id$i")))
        }
        val ids = PendingDiagnostics.decode(raw).map { it.id }
        assertEquals(PendingDiagnostics.MAX_ENTRIES, ids.size)
        assertEquals("id3", ids.first())
        assertEquals("id${PendingDiagnostics.MAX_ENTRIES + 2}", ids.last())
    }

    @Test
    fun removeDropsOnlyTheNamedIds() {
        val raw = PendingDiagnostics.add(null, listOf(entry("a"), entry("b"), entry("c")))
        val left = PendingDiagnostics.remove(raw, setOf("a", "c"))
        assertEquals(listOf("b"), PendingDiagnostics.decode(left).map { it.id })
    }

    /**
     * The drain race. An entry written between the drain's read and its clear
     * must survive — which is the whole reason remove takes ids rather than
     * clearing the key.
     */
    @Test
    fun removePreservesAnEntryAddedSinceTheDrainRead() {
        val atDrainRead = PendingDiagnostics.add(null, listOf(entry("a")))
        val afterRestart = PendingDiagnostics.add(atDrainRead, listOf(entry("b")))
        val left = PendingDiagnostics.remove(afterRestart, setOf("a"))
        assertEquals(listOf("b"), PendingDiagnostics.decode(left).map { it.id })
    }

    // ── decode is total: it runs during onCreate ─────────────────────────────

    @Test
    fun decodeOfNullIsEmpty() = assertTrue(PendingDiagnostics.decode(null).isEmpty())

    @Test
    fun decodeOfBlankIsEmpty() = assertTrue(PendingDiagnostics.decode("  ").isEmpty())

    @Test
    fun decodeOfMalformedJsonIsEmpty() =
        assertTrue(PendingDiagnostics.decode("{not json").isEmpty())

    @Test
    fun decodeOfATruncatedListIsEmpty() {
        val raw = PendingDiagnostics.encode(listOf(entry("a")))
        assertTrue(PendingDiagnostics.decode(raw.substring(0, raw.length / 2)).isEmpty())
    }

    /** One bad entry must not cost the others — a corrupt marker cannot be
     *  allowed to stop a kiosk reporting anything at all. */
    @Test
    fun decodeKeepsTheEntriesThatParseAndDropsTheRest() {
        val good = PendingDiagnostics.encode(listOf(entry("a"), entry("b")))
        val corrupted = good.replace(DiagnosticKind.RESTART_TRIGGERED.wire, "not_a_kind", ignoreCase = false)
        // Both entries used the same kind, so corrupting the wire string drops
        // both; re-encode one good entry alongside to prove partial survival.
        val mixed = PendingDiagnostics.add(corrupted, listOf(entry("c")))
        assertEquals(listOf("c"), PendingDiagnostics.decode(mixed).map { it.id })
    }

    @Test
    fun decodeDropsAnEntryWithAnUnknownKind() {
        val raw = PendingDiagnostics.encode(listOf(entry("a")))
            .replace(DiagnosticKind.RESTART_TRIGGERED.wire, "invented_kind")
        assertTrue(PendingDiagnostics.decode(raw).isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify they fail**

`./gradlew testDebugUnitTest --tests "*PendingDiagnosticsTest*"` → FAIL, unresolved reference.

- [ ] **Step 3: Implement**

Create `PendingDiagnostics.kt`. Use Gson (already a dependency) to encode a JSON array of objects with keys `id`, `kind`, `at`, `detail`. Requirements the tests pin, restated so you do not have to infer them:

- `decode` never throws. Wrap the whole parse; on any failure return what parsed, or empty.
- An entry whose `kind` string matches no `DiagnosticKind.wire` is dropped, not defaulted.
- `add` appends then takes the last `MAX_ENTRIES`.
- `remove` filters by id and re-encodes. Re-encoding is also what lets a partially corrupt store heal itself.
- `encode(emptyList())` must produce something `decode` reads back as empty.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Mutation-check the two rules that matter**

1. Make `add` keep the **oldest** `MAX_ENTRIES` → `addKeepsOnlyTheNewestEntriesWhenTheCapIsExceeded` must fail.
2. Store the kind by `ordinal` instead of `wire` → `theKindIsStoredByWireStringNotOrdinal` must fail.

Report both with their actual output.

- [ ] **Step 6: Full suite and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/telemetry/PendingDiagnostics.kt app/src/test/java/com/sadaqah/kiosk/telemetry/PendingDiagnosticsTest.kt
git commit -m "Hold a bounded list of diagnostics a restart still owes"
```

---

## Task 2: the restart decision and the give-up latch

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/RestartReporting.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/recovery/RestartManager.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/RestartReportingTest.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/recovery/RestartManagerTest.kt` (exists — append)

**Interfaces — Task 5 depends on these:**
- `data class RestartReport(val toMarker: List<PendingDiagnostic>, val toReportNow: List<PendingDiagnostic>, val gaveUpReported: Boolean)`
- `RestartReporting.restartReport(result: RestartResult, causing: PendingDiagnostic, reason: String, alreadyGaveUp: Boolean, nowMs: Long, idFor: () -> String): RestartReport`
- `RestartManager.gaveUpReported: Boolean`, `RestartManager.markGaveUpReported()`

`idFor` is a parameter so tests get deterministic ids; production passes `{ UUID.randomUUID().toString() }`.

- [ ] **Step 1: Write the failing tests**

Create `RestartReportingTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import com.sadaqah.kiosk.recovery.RestartResult
import org.junit.Assert.*
import org.junit.Test

class RestartReportingTest {

    private val atMs = 1_700_000_000_000L
    private val causing = PendingDiagnostic(
        "causing", DiagnosticKind.CARD_READER_CONNECT_FAILED, atMs, """{"code":-1}"""
    )

    private fun report(result: RestartResult, alreadyGaveUp: Boolean = false) =
        RestartReporting.restartReport(
            result = result,
            causing = causing,
            reason = "card_reader_failures",
            alreadyGaveUp = alreadyGaveUp,
            nowMs = atMs,
            idFor = { "generated" }
        )

    private fun detailOf(entry: PendingDiagnostic) =
        JsonParser.parseString(entry.detailJson).asJsonObject

    /**
     * A restart is the case the markers exist for: hardRestart calls exit(0)
     * milliseconds later, so an asynchronous write of either row would be lost.
     */
    @Test
    fun aRestartMarksBothTheCausingFailureAndTheRestart() {
        val r = report(RestartResult.RESTART)
        assertEquals(listOf("causing", "generated"), r.toMarker.map { it.id })
        assertTrue(r.toReportNow.isEmpty())
        assertFalse(r.gaveUpReported)
    }

    @Test
    fun aRestartRowSaysItRestarted() {
        val detail = detailOf(report(RestartResult.RESTART).toMarker[1])
        assertEquals("card_reader_failures", detail["reason"].asString)
        assertEquals(
            "both restart_triggered rows carry an outcome, so they differ by a present field",
            "restarted", detail["outcome"].asString
        )
    }

    @Test
    fun belowThresholdReportsOnlyTheCausingFailureAndMarksNothing() {
        val r = report(RestartResult.BELOW_THRESHOLD)
        assertTrue(r.toMarker.isEmpty())
        assertEquals(listOf("causing"), r.toReportNow.map { it.id })
        assertFalse(r.gaveUpReported)
    }

    /** Cooldown is the policy working; the failure that put the kiosk there is
     *  reported through the causing diagnostic. */
    @Test
    fun cooldownReportsOnlyTheCausingFailure() {
        val r = report(RestartResult.COOLDOWN_ACTIVE)
        assertTrue(r.toMarker.isEmpty())
        assertEquals(listOf("causing"), r.toReportNow.map { it.id })
    }

    @Test
    fun theFirstGiveUpReportsItAndSaysSo() {
        val r = report(RestartResult.MAX_RESTARTS, alreadyGaveUp = false)
        assertTrue("nothing is restarting, so nothing needs a marker", r.toMarker.isEmpty())
        assertEquals(listOf("causing", "generated"), r.toReportNow.map { it.id })
        assertEquals("gave_up", detailOf(r.toReportNow[1])["outcome"].asString)
        assertTrue(r.gaveUpReported)
    }

    /**
     * MAX_RESTARTS is a level, not an edge: tryRestart returns it on every
     * failure once the cap is reached, and the failure counters clear only on
     * success. Without the latch this row would repeat forever.
     */
    @Test
    fun aSecondGiveUpReportsOnlyTheCausingFailure() {
        val r = report(RestartResult.MAX_RESTARTS, alreadyGaveUp = true)
        assertEquals(listOf("causing"), r.toReportNow.map { it.id })
        assertFalse(r.gaveUpReported)
    }
}
```

Append to `RestartManagerTest.kt`, matching its existing construction style:

```kotlin
    @Test
    fun theGiveUpLatchStartsUnset() {
        assertFalse(createManager().gaveUpReported)
    }

    @Test
    fun markingTheGiveUpLatchSticksAcrossFurtherFailures() {
        val m = createManager()
        m.markGaveUpReported()
        m.recordCardReaderFailure()
        assertTrue(m.gaveUpReported)
    }

    @Test
    fun clearingTheCountersClearsTheGiveUpLatch() {
        val m = createManager()
        m.markGaveUpReported()
        m.clearCounters()
        assertFalse(m.gaveUpReported)
    }

    /**
     * Without this, raising maxRestartsBeforeGiveUp on a kiosk that had already
     * given up would let it restart and then give up a second time in silence.
     */
    @Test
    fun anActualRestartClearsTheGiveUpLatch() {
        val m = createManager(maxRestarts = 5)
        m.markGaveUpReported()
        repeat(settingsThreshold) { m.recordCardReaderFailure() }
        assertFalse(m.gaveUpReported)
    }
```

Adapt the last test's construction and threshold to the file's existing helpers; the assertion is what matters. If the file has no way to build a manager with a given `maxRestarts`, add one without modifying existing tests.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement `RestartManager`'s latch**

```kotlin
        const val KEY_GAVE_UP_REPORTED = "gave_up_reported"
```

```kotlin
    /** Int 0/1 rather than a boolean: KeyValueStore exposes only int and long
     *  accessors, and widening it would drag the SharedPreferences
     *  implementation and the in-memory test fake along for one flag. */
    val gaveUpReported: Boolean get() = store.getInt(KEY_GAVE_UP_REPORTED) == 1

    fun markGaveUpReported() { store.putInt(KEY_GAVE_UP_REPORTED, 1) }
```

Clear it in `clearCounters()` alongside the counters it already clears, and in `tryRestart()` on the branch that returns `RestartResult.RESTART`. Change no other behaviour — every existing test must pass unmodified.

- [ ] **Step 4: Implement `RestartReporting`**

Pure, no Android imports. It builds `PendingDiagnostic` values only; it never writes anything and never calls `RestartManager`. The `restart_triggered` detail is `{"reason": <reason>, "outcome": "restarted"|"gave_up"}` built with Gson.

- [ ] **Step 5: Run to verify they pass**

- [ ] **Step 6: Mutation-check the latch**

1. Make `restartReport` return `gaveUpReported = true` for the `alreadyGaveUp = true` case → `aSecondGiveUpReportsOnlyTheCausingFailure` must fail.
2. Remove the latch clear from `tryRestart`'s `RESTART` branch → `anActualRestartClearsTheGiveUpLatch` must fail.

- [ ] **Step 7: Full suite and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/telemetry/RestartReporting.kt app/src/main/java/com/sadaqah/kiosk/recovery/RestartManager.kt app/src/test/java/com/sadaqah/kiosk/telemetry/RestartReportingTest.kt app/src/test/java/com/sadaqah/kiosk/recovery/RestartManagerTest.kt
git commit -m "Decide what a restart reports, and report a give-up once"
```

---

## Task 3: the detail builders

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/DiagnosticEvents.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/DiagnosticEventsTest.kt` (exists — append)

**Interfaces — Tasks 5 and 6 depend on these:**
- `DiagnosticEvents.sumUpFailureDetail(code: Int, message: String?, closedBy: String?): String`
- `DiagnosticEvents.pageTimeoutDetail(): String`
- `DiagnosticEvents.checkoutNoReaderDetail(code: Int, message: String?): String`

`sumUpFailureDetail` serves `sumup_reinit_failed` and `card_reader_connect_failed`, which carry the same shape.

- [ ] **Step 1: Write the failing tests**

Append to `DiagnosticEventsTest.kt`:

```kotlin
    @Test
    fun aSumUpFailureDetailCarriesTheCodeAndMessage() {
        val d = JsonParser.parseString(
            DiagnosticEvents.sumUpFailureDetail(code = 7, message = "reader not found", closedBy = null)
        ).asJsonObject
        assertEquals(7, d["code"].asInt)
        assertEquals("reader not found", d["message"].asString)
        assertFalse("absent closed_by is what marks a genuine failure", d.has("closed_by"))
    }

    @Test
    fun aSyntheticCloseIsNamedOnTheRow() {
        val d = JsonParser.parseString(
            DiagnosticEvents.sumUpFailureDetail(code = -1, message = null, closedBy = "pairing_timeout")
        ).asJsonObject
        assertEquals("pairing_timeout", d["closed_by"].asString)
        assertFalse(d.has("message"))
    }

    @Test
    fun aPageTimeoutSaysWhatClosedIt() {
        val d = JsonParser.parseString(DiagnosticEvents.pageTimeoutDetail()).asJsonObject
        assertEquals("timeout", d["closed_by"].asString)
    }

    @Test
    fun aCheckoutWithNoReaderCarriesTheFailureItObserved() {
        val d = JsonParser.parseString(
            DiagnosticEvents.checkoutNoReaderDetail(code = 3, message = "declined")
        ).asJsonObject
        assertEquals(3, d["code"].asInt)
        assertEquals("declined", d["message"].asString)
    }
```

- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Implement**, matching the existing builders' shape: a `JsonObject`, fields omitted when null or blank, `.toString()` returned.
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Full suite and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/telemetry/DiagnosticEvents.kt app/src/test/java/com/sadaqah/kiosk/telemetry/DiagnosticEventsTest.kt
git commit -m "Shape the detail for the last diagnostic kinds"
```

---

## Task 4: the marker store and the drain

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/PendingDiagnosticStore.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt` (`drainUpdateDiagnostics` only)

**Interfaces — Task 5 depends on these:**
- `class PendingDiagnosticStore(prefs: SharedPreferences)` with `@Synchronized fun add(entries: List<PendingDiagnostic>)`, `@Synchronized fun read(): List<PendingDiagnostic>`, `@Synchronized fun removeDrained(ids: Set<String>)`

**No unit tests**, for the same reason `PrefsStatusStore` has none: `SharedPreferences` is not in the desktop JRE and Robolectric would breach the no-new-dependencies constraint. It holds no decisions — every one is in `PendingDiagnostics`.

- [ ] **Step 1: Write the store**

It owns `KEY_PENDING = "pending_diagnostics"` in the prefs file `UpdateWatchdogReceiver.prefs(ctx)` returns. `add` uses `commit()`, not `apply()` — its caller is about to restart the process, exactly as 3b's rollback marker does.

```kotlin
    @Synchronized
    fun removeDrained(ids: Set<String>) {
        // Re-read inside the lock. Applying to the snapshot the drain opened
        // with would discard an entry a restart wrote in between — which is the
        // one thing remove-by-id exists to prevent.
        val current = prefs.getString(KEY_PENDING, null)
        prefs.edit().putString(KEY_PENDING, PendingDiagnostics.remove(current, ids)).commit()
    }
```

- [ ] **Step 2: Extend the drain**

In `drainUpdateDiagnostics`, **before** the existing update-marker work, drain the pending list:

- read the store;
- for each entry, `DiagnosticEvents.forKind(CrashContext.settings, BuildConfig.VERSION_NAME, kind, occurredAtMs, detailJson, affiliateKey = null)`;
- append any `Report` result;
- collect the id into `drainedIds` **whether or not it produced an event** — a gate-declined entry that is skipped rather than drained sits in the store forever and is re-read on every boot;
- after the loop, `removeDrained(drainedIds)`.

`affiliateKey = null` is correct: the detail was scrubbed before it was stored, matching `updateRollback` and `updateInstalled`.

An append that throws must leave its entry behind — the existing catch-all already gives that, since `removeDrained` sits after the loop.

- [ ] **Step 3: Verify by reading and report it**

There is no test here, so the report must state: where the pending drain sits relative to the update-marker drain and to `recordHeartbeat`; that `drainedIds` collects gate-declined entries; and that `removeDrained` is unreachable if an append throws.

- [ ] **Step 4: Build and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/telemetry/PendingDiagnosticStore.kt app/src/main/java/com/sadaqah/kiosk/MainActivity.kt
git commit -m "Drain the diagnostics a restart left behind"
```

---

## Task 5: the restart call sites

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt`

**No unit tests** — `MainActivity` needs the Android lifecycle. Every decision is pinned by Tasks 1–3.

- [ ] **Step 1: Add `reportRestart`**

A thin method. It calls `RestartReporting.restartReport(...)`, writes `toMarker` through `PendingDiagnosticStore.add` in one call, dispatches each `toReportNow` entry through the existing `reportDiagnostic`, and calls `restartManager.markGaveUpReported()` when `gaveUpReported` is true. **It makes no decision of its own** — no `when`, no threshold comparison, no check of `RestartResult` beyond handing it over.

The marker write is wrapped in `try`/`catch (Throwable)`: it runs immediately before a restart and a throw must not be what prevents one.

- [ ] **Step 2: Rewrite both call sites**

`:680` and `:711`. The result is hoisted to a local so the diagnostic can be marked when a restart is imminent, and **`handleRestartResult` is still called** — both sites are its only callers and dropping it deletes auto-restart:

```kotlin
                    val result = restartManager.recordCardReaderFailure()
                    reportRestart(
                        result,
                        DiagnosticKind.CARD_READER_CONNECT_FAILED,
                        DiagnosticEvents.sumUpFailureDetail(errorCode, errorMessage, closedBy),
                        "card_reader_failures"
                    )
                    handleRestartResult(result, "card_reader_failures")
```

and the mirror at `:680` with `recordReinitFailure()`, `SUMUP_REINIT_FAILED` and `"reinit_failures"`.

**`recordCardReaderFailure()` / `recordReinitFailure()` must appear exactly once each in the file after your edit.** Grep and paste the count.

- [ ] **Step 3: Scrub the detail before it is stored**

The detail passed to `reportRestart` may be stored in prefs, so it is scrubbed at construction: pass the already-scrubbed message, using `TelemetryRedactor.scrub(errorMessage, CrashContext.affiliateKey)`. Nothing unredacted reaches a file.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
grep -c "recordCardReaderFailure()\|recordReinitFailure()" app/src/main/java/com/sadaqah/kiosk/MainActivity.kt
git add app/src/main/java/com/sadaqah/kiosk/MainActivity.kt
git commit -m "Report why a kiosk restarted itself"
```

---

## Task 6: synthetic closes, the remaining kinds, and the logcat cleanup

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt`

**No unit tests.** Read the spec's "Synthetic activity results" section in full before starting — the arming rule is the part that has been got wrong twice.

- [ ] **Step 1: Add the two slots and arm them conditionally**

```kotlin
    private var syntheticCloseReader: String? = null   // request code 2
    private var syntheticCloseLogin: String? = null    // request code 1
```

Arm at all six `finishActivity` sites with the labels in the spec's table — **but only when that activity is actually outstanding**. `finishActivity` is a no-op otherwise, so an arm with no callback coming survives to mislabel the next genuine failure, which is exactly what `resetScreensaver` would do on every screensaver dismissal.

- Code 2 (`:840`, `:943`, `:1068`, `:1627`): arm only when `isConnectingCardReader` is true.
- Code 1 (`:1017`, `:1503`): arm only when a login activity is outstanding.

- [ ] **Step 2: Consume at the top of `onActivityResult`**

Before any branching, take and clear the slot for the code being delivered into a local `closedBy`. Leave the other slot alone — it belongs to an activity that has not returned yet.

- [ ] **Step 3: Report the three observation kinds**

- **`card_reader_page_timeout`** in the timeout job, with `DiagnosticEvents.pageTimeoutDetail()`.
- **`checkout_no_reader`** in case 3's failure branch, only when the live SDK query says no reader:
  ```kotlin
  val readerPresent = try {
      ReaderModuleCoreState.Instance()?.mReaderCoreManager?.isCardReaderConnected() == true
  } catch (e: Exception) { false }
  ```
  the same call already used at `:686` and `:826`. **Do not** use the `isCardReaderConnected` field — it is set false by `disconnectCardReader()` from the idle screensaver path and never restored, so it reads false in steady state.
- The `closedBy` local flows into the two SumUp failure details at the sites Task 5 rewrote.

- [ ] **Step 4: The logcat cleanup**

- `:650` — drop `extras=${data?.extras}`; keep `requestCode` and `resultCode`.
- `:678` and `:737` — keep `errorCode`, drop the raw `errorMessage`.
- `:717` — delete the `TX Code` log entirely. It is a SumUp transaction identifier and the telemetry design spec promises it is never collected.

- [ ] **Step 5: Verify the invariants**

```bash
grep -rnE '[Oo]utbox\.append\(' app/src/main
grep -rn 'DiagnosticKind\.' app/src/main | grep -v 'DiagnosticEvents.kt' | grep -v 'TelemetryEvent.kt'
grep -rn 'data?.extras\|TX Code' app/src/main
```

Expected: **5** appends, unchanged — every kind reaches the outbox through `reportDiagnostic` or the drain. Constructions for all eleven kinds across the call sites. **Zero** hits for the third.

- [ ] **Step 6: Full suite and commit**

```bash
./gradlew testDebugUnitTest assembleDebug
git add app/src/main/java/com/sadaqah/kiosk/MainActivity.kt
git commit -m "Tell a self-inflicted activity close from a real failure"
```

---

## After the tasks

Not plan steps — the orchestrator's work:

1. **Whole-phase review** over `b5360bb..HEAD`, on the most capable model, told to read HEAD state rather than only the diff. Press hardest on the arming rule: six `finishActivity` sites, and a label armed when no callback is coming is the defect two review rounds found.
2. **Push and open a PR** against `telemetry/phase-3c`, stacked on #12. Never a local merge.
3. **The PR body must carry** the spec's device-check list — especially check 2 (two failures must *not* restart, the double-count check) and check 4 (dismiss the screensaver repeatedly, then fail a reader for real, and see no `closed_by`).

## Deferred, on purpose

- **`KioskCrashHandler` still uses the ordinary `append`**, which reads the whole queue under a shared lock on a dying thread. Real since 3b. The marker pattern cannot serve a crash; it needs the bounded-write work this phase family abandoned, and the three reviews of that design are on file.
- `retryableFailure` is one global boolean; a refused table backs off the healthy ones.
- `CrashContext.onOutboxDropped` retains one live Activity until `telemetryStatusStore` stops being Activity-lazy.
- `SettingsBootstrap`'s call site is still untested.
- The 02:00 flush floor is not guaranteed while offline.
