# Telemetry Phase 3d-i — Per-Table Backoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A diagnostics table the backend refuses stops delaying donations that would upload fine.

**Architecture:** `TelemetryStatus`'s two failure fields become maps keyed by table name, and the uploader — which already sends one request per table — reports which tables failed and which succeeded instead of one global boolean. The flush excludes backed-off tables *inside* `TelemetryOutbox.peek`, before the batch is truncated to its limit, so backed-off rows at the head can never crowd out the rows behind them. Four carried cleanups ride along on the same send path.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, Gson, `java.time`, SharedPreferences. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-14-telemetry-phase-3d-i-per-table-backoff.md`

## Global Constraints

- **No new third-party dependency, and no gradle file is touched.** JUnit 4 only — no Mockito, no MockK, no Robolectric.
- **No new `Strings` member**, and therefore no copy in eight languages.
- **A donation is never delayed indefinitely by another table's failure.** The bound stays what it is today: at most one backoff ceiling.
- **The donation flow is not altered.** No change to `makePayment` or any path a payment travels.
- **Every deletion decision still comes from the uploader**, never re-derived. `TelemetryManager` deletes exactly the ids the uploader names.
- **One corrupt prefs key costs one field, not the whole status.** `droppedCount` must never be zeroed by an unrelated key going bad.
- **A kiosk with `analyticsEnabled` off writes nothing identified to disk.** No task changes this; it is listed so a reviewer knows it was considered and is out of scope.
- Comments explain a non-obvious *why*, never a *what*.
- Commit messages stay short; no AI attribution and no session links in any commit or PR.
- Run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest` before every commit. Both must pass, with no `@Ignore` added by this phase.

## Read the test file before you write a test in it

**Every task below opens with a reconnaissance step, and it is not optional.** A plan review of the first draft of this document found three Criticals and twelve Importants, and its root-cause finding was that the test steps had been written without opening the test files: helpers were named that do not exist, a fixture hard-codes a table every new test needs to vary, and twenty-two existing assertions were left unported under a step that declared "Expected: PASS".

The fixtures, as they actually are at HEAD:

| File | What exists |
|---|---|
| `TelemetryManagerTest.kt` | `manager(outbox, poster, …)` `:49`; `outboxWith(vararg ids)` `:79` — **hard-codes `TelemetryTables.DONATIONS`**; nested `ConstantPoster` `:20` and `ScriptedPoster` `:36`, constructed inline; stores are inline `InMemoryStatusStore()`; `now` is a `var` field `:16` = `1_000_000L`. There is no `outbox`, `poster` or `store` helper. |
| `TelemetryUploaderTest.kt` | `RecordingPoster(respond)` `:13`; `event(id, table = DONATIONS)` `:23`; `uploader(poster)` `:26`. **Reads `retryableFailure` 22 times.** |
| `TelemetryOutboxTest.kt` | `outbox(...)` is a **factory function** `:18`, used as `val box = outbox()`; `file` field; `TelemetryOutbox.appendDonation(id)` extension `:31`. |
| `AnalyticsPresenterTest.kt` | `view(settings, config, status)` `:13` — **`now` is a class field `:11`, not a parameter**; `zone` and `locale` do not exist. |
| `ClearCredentialsTest.kt` | `:27` constructs a `TelemetryStatus` with `consecutiveFailures`; `:35` asserts on it. |

## Plan-level decisions the spec does not make

**1. `StatusCodec`, because the spec requires tests the current design cannot support.** The spec asks for a `PrefsStatusStore` per-table round trip, a one-corrupt-key-costs-one-field check, and a legacy-migration check. No JVM test in this project can construct a `Context`, and Robolectric and an instrumented harness are both excluded. So the mapping moves into a pure object over a plain `Map<String, Any?>`; `PrefsStatusStore` becomes the SharedPreferences plumbing around it. Reading with `as?` also means a bad value cannot throw at all, so per-key totality is obtained structurally rather than by discipline.

**2. Exclusion ships in the same commit that removes the gate's backoff check.** The first draft put the gate change in Task 1 and the exclusion in Task 4, which would have left three commits on the branch with backoff state that nothing reads — a kiosk pointed at a refusing backend retrying on every tick with no delay. Because Task 1 moves every table in lockstep, excluding *all* backed-off tables is exactly today's global behaviour, so the two land together and Task 1's behaviour-preservation claim is true rather than aspirational. No test is `@Ignore`d anywhere in this plan.

---

## File Structure

**Created**
- `app/src/main/java/com/sadaqah/kiosk/telemetry/StatusCodec.kt` — pure mapping between `TelemetryStatus` and a key-value map: the per-table key scheme, per-key defensiveness, legacy migration.
- `app/src/test/java/com/sadaqah/kiosk/telemetry/StatusCodecTest.kt`

**Modified**
- `telemetry/TelemetryEvent.kt` — `TelemetryTables` gains `ALL`.
- `telemetry/TelemetryStatusStore.kt` — `TelemetryStatus`'s two scalars become maps; gains `effectiveBackoffUntilMs`.
- `telemetry/TelemetryGate.kt` — `GateInputs.backoffUntilMs` removed; gains `isTableBackedOff`.
- `telemetry/PrefsStatusStore.kt` — delegates to `StatusCodec`.
- `telemetry/TelemetryOutbox.kt` — `peek` gains `excludeTables`.
- `telemetry/TelemetryUploader.kt` — `UploadOutcome` carries `retryableTables` and `succeededTables`.
- `telemetry/TelemetryManager.kt` — exclusion in the flush, per-table outcome accounting, `BACKING_OFF`, `activate()`.
- `telemetry/AnalyticsPresenter.kt` — aggregation, the error rule, `lastSuccessText`.
- `telemetry/TelemetryRedactor.kt` — `truncate` loses its parameter; owns the truncation suffix.
- `telemetry/DiagnosticEvents.kt` — references the shared suffix.
- `recovery/RestartManager.kt` — comment only.
- `MainActivity.kt` — ticker helper, watchdog clear, presenter call site, one stale comment.
- `screens/AnalyticsSettingsScreen.kt` — `formatTimestamp` deleted; renders `view.lastSuccessText`.
- Tests: `TelemetryGateTest`, `TelemetryManagerTest`, `TelemetryOutboxTest`, `TelemetryUploaderTest`, `AnalyticsPresenterTest`, `TelemetryRedactorTest`, `ClearCredentialsTest`.

---

## Task 1: Per-table state, and exclusion in the same commit

The shape becomes per-table; the behaviour stays global, because every table moves in lockstep. Exclusion is here rather than later so that the enforcement the gate loses is replaced in the same commit.

**Two deliberate exceptions to "behaviour unchanged", both improvements the spec asks for, called out so a reviewer is not left to discover them:**

- A deadline beyond `MAX_BACKOFF_MS` renders `backingOff = true` today (`AnalyticsPresenter.kt:112` compares raw, with no ceiling guard) and `false` afterwards. That is the fail-open guard reaching the screen, which is the point of putting it in `effectiveBackoffUntilMs`.
- `BACKING_OFF` now comes from the flush rather than the gate. `activate()` cannot produce it either way, because its reset clears both maps before `flush` reads them.

**Files:**
- Modify: `telemetry/TelemetryEvent.kt:9-13`, `telemetry/TelemetryStatusStore.kt:16-24`, `telemetry/TelemetryGate.kt`, `telemetry/TelemetryOutbox.kt:61-63`, `telemetry/PrefsStatusStore.kt`, `telemetry/TelemetryManager.kt`, `telemetry/AnalyticsPresenter.kt`, `MainActivity.kt`
- Create: `telemetry/StatusCodec.kt`, `test/.../StatusCodecTest.kt`
- Test: `StatusCodecTest.kt`, `TelemetryGateTest.kt`, `TelemetryOutboxTest.kt`, `TelemetryManagerTest.kt`, `AnalyticsPresenterTest.kt`, `ClearCredentialsTest.kt`

**Interfaces:**
- Produces: `TelemetryTables.ALL: List<String>`; `TelemetryStatus.backoffUntilMsByTable: Map<String, Long>`, `.consecutiveFailuresByTable: Map<String, Int>`, `.effectiveBackoffUntilMs(nowMs: Long): Long`; `TelemetryGate.isTableBackedOff(backoffUntilMs: Long, nowMs: Long): Boolean`; `StatusCodec.decode(raw: Map<String, Any?>): TelemetryStatus`, `.encode(status: TelemetryStatus): Map<String, Any?>`, `.keyFor(base: String, table: String): String`; `TelemetryOutbox.peek(limit: Int = DEFAULT_BATCH, excludeTables: Set<String> = emptySet())`; `TelemetryManagerTest.outboxWithRows(vararg rows: Pair<String, String>)`.

- [ ] **Step 1: Reconnaissance**

Read, in full, before writing anything: `TelemetryGateTest.kt`, `TelemetryOutboxTest.kt`, `TelemetryManagerTest.kt`, `AnalyticsPresenterTest.kt`, `ClearCredentialsTest.kt`. Write down every assertion that reads `TelemetryStatus.consecutiveFailures` or `.backoffUntilMs`, or constructs `GateInputs`. The enumeration in Step 18 is your cross-check, not your source — if you find a site it does not list, port it and say so in your report.

- [ ] **Step 2: Add the table list**

In `TelemetryEvent.kt`, extend `TelemetryTables`:

```kotlin
    /** Enumerated so per-table persistence reads and writes the same set. A
     *  fourth table added above and forgotten here would be written by one and
     *  never read back by the other, which no test would fail on. */
    val ALL = listOf(DONATIONS, DIAGNOSTICS, ACTIVATIONS)
```

- [ ] **Step 3: Write the failing tests for the status helper**

Add to `TelemetryGateTest.kt` (`import org.junit.Assert.*` is already at `:3`; add nothing).

These live here rather than in a `TelemetryStatusStoreTest` because the helper is a thin wrapper over `TelemetryGate.isTableBackedOff` and there is no status-store test file; note it in your report if you disagree.

```kotlin
    @Test
    fun `effectiveBackoffUntilMs takes the latest live deadline`() {
        val now = 10_000L
        val status = TelemetryStatus(
            backoffUntilMsByTable = mapOf(
                TelemetryTables.DONATIONS to now + 5_000,
                TelemetryTables.DIAGNOSTICS to now + 30_000
            )
        )
        assertEquals(now + 30_000, status.effectiveBackoffUntilMs(now))
    }

    @Test
    fun `effectiveBackoffUntilMs ignores an elapsed deadline`() {
        val now = 10_000L
        val status = TelemetryStatus(
            backoffUntilMsByTable = mapOf(TelemetryTables.DONATIONS to now - 1)
        )
        assertEquals(0L, status.effectiveBackoffUntilMs(now))
    }

    /**
     * The aggregate is what makes a corrupt deadline dangerous: taking a
     * maximum, one entry beyond the ceiling dominates every healthy one and
     * freezes the screen for as long as the bad value says.
     *
     * Mutation check: delete the isTableBackedOff filter from
     * effectiveBackoffUntilMs and this test must fail.
     */
    @Test
    fun `effectiveBackoffUntilMs ignores a deadline beyond the ceiling`() {
        val now = 10_000L
        val status = TelemetryStatus(
            backoffUntilMsByTable = mapOf(
                TelemetryTables.DONATIONS to now + 5_000,
                TelemetryTables.DIAGNOSTICS to now + TelemetryGate.MAX_BACKOFF_MS + 1
            )
        )
        assertEquals(now + 5_000, status.effectiveBackoffUntilMs(now))
    }

    @Test
    fun `isTableBackedOff is false at the instant the deadline is reached`() {
        val now = 10_000L
        assertFalse(TelemetryGate.isTableBackedOff(now, now))
        assertTrue(TelemetryGate.isTableBackedOff(now + 1, now))
    }

    @Test
    fun `a deadline exactly at the ceiling still counts`() {
        val now = 10_000L
        assertTrue(TelemetryGate.isTableBackedOff(now + TelemetryGate.MAX_BACKOFF_MS, now))
    }

    @Test
    fun `a deadline past the ceiling fails open`() {
        val now = 10_000L
        assertFalse(
            "a corrected clock must not silence a kiosk for years",
            TelemetryGate.isTableBackedOff(now + TelemetryGate.MAX_BACKOFF_MS + 1, now)
        )
    }
```

- [ ] **Step 4: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryGateTest*'`
Expected: FAIL — `effectiveBackoffUntilMs` and `isTableBackedOff` are unresolved.

- [ ] **Step 5: Change `TelemetryStatus`**

In `TelemetryStatusStore.kt`, replace the two scalar fields and add the helper. Keep the existing KDoc on the data class and on `droppedCount` verbatim.

```kotlin
    /** Per table, because the uploader sends one request per table and a table
     *  the backend refuses must not delay a table it accepts. A table absent
     *  from either map has no failures and no deadline — absence is the healthy
     *  state, so a fresh kiosk and a fully recovered one read identically. */
    val consecutiveFailuresByTable: Map<String, Int> = emptyMap(),
    val backoffUntilMsByTable: Map<String, Long> = emptyMap(),
```

```kotlin
) {
    /**
     * The latest deadline any table is still waiting on, or 0 if none is.
     *
     * Built on [TelemetryGate.isTableBackedOff] rather than repeating its
     * comparison, because the fail-open guard matters more here than it does
     * per table: this is a maximum, so a single deadline beyond the ceiling
     * would dominate every healthy one and freeze the screen for as long as a
     * corrected clock left behind.
     */
    fun effectiveBackoffUntilMs(nowMs: Long): Long =
        backoffUntilMsByTable.values
            .filter { TelemetryGate.isTableBackedOff(it, nowMs) }
            .maxOrNull() ?: 0L
}
```

- [ ] **Step 6: Change `TelemetryGate`**

Remove `backoffUntilMs` from `GateInputs`, drop the two backoff branches from `evaluate`, add the predicate. `FlushBlock.BACKING_OFF` stays in the enum — Step 17 makes the flush return it.

```kotlin
data class GateInputs(
    val enabled: Boolean,
    val configured: Boolean,
    val activated: Boolean,
    val networkAvailable: Boolean,
    val queueDepth: Int
)
```

```kotlin
    fun evaluate(inputs: GateInputs, nowMs: Long): FlushBlock = when {
        !inputs.enabled -> FlushBlock.DISABLED
        !inputs.configured -> FlushBlock.NOT_CONFIGURED
        !inputs.activated -> FlushBlock.NOT_ACTIVATED
        !inputs.networkAvailable -> FlushBlock.NO_NETWORK
        inputs.queueDepth <= 0 -> FlushBlock.EMPTY_QUEUE
        else -> FlushBlock.NONE
    }

    /**
     * Whether one table's deadline is still in force.
     *
     * A deadline further out than the ceiling cannot have come from
     * [backoffDelayMs], so it was computed against a clock that has since been
     * corrected. Failing open toward flushing is the safe direction: the
     * alternative is a kiosk that silently stops reporting for years.
     */
    fun isTableBackedOff(backoffUntilMs: Long, nowMs: Long): Boolean =
        backoffUntilMs - nowMs <= MAX_BACKOFF_MS && nowMs < backoffUntilMs
```

- [ ] **Step 7: Port `TelemetryGateTest`, and delete the one case that stops meaning anything**

- `ready()` at `:16` drops its `backoffUntilMs = 0L` argument.
- `:58-59` and `:64-65` asserted `evaluate` returning `BACKING_OFF` and `NONE` around the deadline. That behaviour is now `isTableBackedOff`, covered by Step 3's `is false at the instant the deadline is reached` — delete both, and say in your report that Step 3 is where they went.
- `:70-77` (stale deadline ignored, deadline at ceiling) are likewise covered by Step 3's ceiling cases. Delete.
- `:80-85`, `aStaleDeadlineDoesNotOutrankTheOtherBlocks`: **delete it.** It is `ready().copy(enabled = false, backoffUntilMs = <stale>)` asserting `DISABLED`; strip the deadline and it is character-for-character `disabled_blocks` at `:26-30`, under a name about staleness the gate no longer knows anything about. A duplicate body under a name that has stopped being true is worse than one test fewer.
- `:89-96`, `disabledOutranksEveryOtherBlock`: drop the `backoffUntilMs` argument and keep it. It still pins `DISABLED` against four other failing inputs.

- [ ] **Step 8: Write the failing `StatusCodec` tests**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/StatusCodecTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping is tested here rather than through [PrefsStatusStore] because no
 * JVM test in this project can construct a Context and Robolectric is not an
 * available dependency. Everything with judgement in it lives in this object;
 * the store is the SharedPreferences plumbing around it.
 */
class StatusCodecTest {

    /**
     * What [PrefsStatusStore.write] leaves in SharedPreferences, which is NOT
     * what [StatusCodec.encode] returns: the store turns a null value into
     * `editor.remove`, so an absent table's key is **missing**, where encode
     * leaves it **present and null**. Decoding encode's raw output would
     * therefore exercise a shape the device never has — and would miss that a
     * missing key falls back to the legacy value.
     */
    private fun asStored(status: TelemetryStatus): Map<String, Any?> =
        StatusCodec.encode(status).filterValues { it != null }

    @Test
    fun `round trips per-table state through the shape the store actually writes`() {
        val status = TelemetryStatus(
            lastSuccessMs = 111L,
            lastError = "HTTP 400 bad column",
            lastErrorAtMs = 222L,
            consecutiveFailuresByTable = mapOf(TelemetryTables.DIAGNOSTICS to 3),
            backoffUntilMsByTable = mapOf(TelemetryTables.DIAGNOSTICS to 999L),
            droppedCount = 7
        )
        assertEquals(status, StatusCodec.decode(asStored(status)))
    }

    /**
     * The defect this test exists for: a table that recovers has its per-table
     * key removed, and if the pre-upgrade global keys were still on disk the
     * fallback would resurrect them — re-backing-off every recovered table and
     * pinning its failure count at the pre-upgrade value for the life of the
     * install, so the very first failure after recovery would cost the full
     * one-hour ceiling instead of a minute.
     *
     * Mutation check: stop emitting the two legacy keys from `encode` and this
     * test must fail.
     */
    @Test
    fun `a write clears the legacy keys, so a recovered table stays recovered`() {
        val upgraded = mutableMapOf<String, Any?>(
            StatusCodec.KEY_FAILURES to 6,
            StatusCodec.KEY_BACKOFF_UNTIL to 5_000L
        )

        // The migration seeds every table on the first read.
        val migrated = StatusCodec.decode(upgraded)
        assertEquals(TelemetryTables.ALL.associateWith { 6 }, migrated.consecutiveFailuresByTable)

        // Everything then recovers, and the store applies the write.
        for ((key, value) in StatusCodec.encode(migrated.copy(
            consecutiveFailuresByTable = emptyMap(),
            backoffUntilMsByTable = emptyMap()
        ))) {
            if (value == null) upgraded.remove(key) else upgraded[key] = value
        }

        assertTrue("the legacy keys must not survive a write", 
            !upgraded.containsKey(StatusCodec.KEY_FAILURES) &&
            !upgraded.containsKey(StatusCodec.KEY_BACKOFF_UNTIL))
        val after = StatusCodec.decode(upgraded)
        assertEquals(emptyMap<String, Int>(), after.consecutiveFailuresByTable)
        assertEquals(emptyMap<String, Long>(), after.backoffUntilMsByTable)
    }

    /**
     * droppedCount is an operator's only view of telemetry loss and must not be
     * collateral damage from an unrelated key. Before per-key totality one
     * ClassCastException anywhere yielded a wholly default status, silently
     * zeroing it — and going from six keys to ten multiplies the ways that
     * happens.
     */
    @Test
    fun `one corrupt key costs one field and nothing else`() {
        val raw = asStored(
            TelemetryStatus(
                droppedCount = 42,
                lastSuccessMs = 111L,
                backoffUntilMsByTable = mapOf(TelemetryTables.DONATIONS to 999L)
            )
        ).toMutableMap()
        raw[StatusCodec.keyFor(StatusCodec.KEY_BACKOFF_UNTIL, TelemetryTables.DONATIONS)] = "not a long"

        val decoded = StatusCodec.decode(raw)
        assertEquals(42, decoded.droppedCount)
        assertEquals(111L, decoded.lastSuccessMs)
        assertEquals(emptyMap<String, Long>(), decoded.backoffUntilMsByTable)
    }

    @Test
    fun `a legacy single value seeds every table on first upgrade`() {
        val decoded = StatusCodec.decode(
            // mapOf<String, Any> is explicit, not decorative: left inferred,
            // Kotlin resolves the bare `4` literal to Long to unify with
            // `5_000L`, so `as? Int` in decode would miss it and this would
            // assert against an empty map for a reason that has nothing to do
            // with the code under test.
            mapOf<String, Any>(StatusCodec.KEY_FAILURES to 4, StatusCodec.KEY_BACKOFF_UNTIL to 5_000L)
        )
        assertEquals(TelemetryTables.ALL.associateWith { 4 }, decoded.consecutiveFailuresByTable)
        assertEquals(TelemetryTables.ALL.associateWith { 5_000L }, decoded.backoffUntilMsByTable)
    }

    @Test
    fun `a per-table key wins over the legacy key it supersedes`() {
        val decoded = StatusCodec.decode(
            mapOf(
                StatusCodec.KEY_BACKOFF_UNTIL to 5_000L,
                StatusCodec.keyFor(StatusCodec.KEY_BACKOFF_UNTIL, TelemetryTables.DONATIONS) to 9_000L
            )
        )
        assertEquals(9_000L, decoded.backoffUntilMsByTable[TelemetryTables.DONATIONS])
        assertEquals(5_000L, decoded.backoffUntilMsByTable[TelemetryTables.DIAGNOSTICS])
    }

    @Test
    fun `an absent error decodes as null`() {
        assertNull(StatusCodec.decode(emptyMap()).lastError)
    }
}
```

- [ ] **Step 9: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*StatusCodecTest*'`
Expected: FAIL — `StatusCodec` is unresolved.

- [ ] **Step 10: Write `StatusCodec`**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/StatusCodec.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

/**
 * The mapping between [TelemetryStatus] and a flat key-value store.
 *
 * Separate from [PrefsStatusStore] so everything with judgement in it — the
 * per-table key scheme, the defensiveness against a bad value, the legacy
 * migration — is a pure function a JVM test can exercise. No unit test in this
 * project can construct a Context, so a mapping left inside the store is a
 * mapping nothing checks.
 *
 * Reads go through `as?` rather than a typed getter, so a key holding the wrong
 * type (a hand-edited or malformed adb-pushed prefs file — this project
 * provisions settings that way) costs its own field and nothing else. That
 * matters most for [TelemetryStatus.droppedCount], an operator's only view of
 * telemetry loss.
 */
object StatusCodec {

    const val KEY_LAST_SUCCESS = "last_success_ms"
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_LAST_ERROR_AT = "last_error_at_ms"
    const val KEY_DROPPED = "dropped_count"

    /** Also the exact keys an install from before this phase holds a single
     *  global value under. */
    const val KEY_FAILURES = "consecutive_failures"
    const val KEY_BACKOFF_UNTIL = "backoff_until_ms"

    /** The per-table key is the global one plus a dot plus the table name.
     *  Tables are a closed set in code, so a flat key needs no parser. */
    fun keyFor(base: String, table: String) = "$base.$table"

    fun decode(raw: Map<String, Any?>): TelemetryStatus = TelemetryStatus(
        queued = 0,
        lastSuccessMs = raw[KEY_LAST_SUCCESS] as? Long ?: 0L,
        lastError = raw[KEY_LAST_ERROR] as? String,
        lastErrorAtMs = raw[KEY_LAST_ERROR_AT] as? Long ?: 0L,
        consecutiveFailuresByTable = perTable(raw, KEY_FAILURES) { it as? Int },
        backoffUntilMsByTable = perTable(raw, KEY_BACKOFF_UNTIL) { it as? Long },
        droppedCount = raw[KEY_DROPPED] as? Int ?: 0
    )

    fun encode(status: TelemetryStatus): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>(
            KEY_LAST_SUCCESS to status.lastSuccessMs,
            KEY_LAST_ERROR to status.lastError,
            KEY_LAST_ERROR_AT to status.lastErrorAtMs,
            KEY_DROPPED to status.droppedCount,
            // Emitted as null on every write — the store turns null into a
            // removal — so the migration below is genuinely one-shot. Without
            // this, a recovered table's per-table key is removed while the
            // pre-upgrade global key survives, and the fallback resurrects it:
            // every recovered table is instantly backed off again, and its
            // failure count reads the pre-upgrade value forever, so the first
            // failure after recovery costs the full ceiling instead of a minute.
            KEY_FAILURES to null,
            KEY_BACKOFF_UNTIL to null
        )
        for (table in TelemetryTables.ALL) {
            out[keyFor(KEY_FAILURES, table)] = status.consecutiveFailuresByTable[table]
            out[keyFor(KEY_BACKOFF_UNTIL, table)] = status.backoffUntilMsByTable[table]
        }
        return out
    }

    /**
     * A table's own key if it has one, otherwise the pre-3d-i global value.
     *
     * The fallback is what keeps a kiosk that upgrades mid-backoff backing off
     * rather than resetting to zero and hammering a destination still refusing
     * it. It survives exactly until the first write, which deletes both legacy
     * keys.
     *
     * A null result drops the table from the map entirely: absence is the
     * healthy state, so a recovered table and a fresh one read identically.
     */
    private fun <T> perTable(
        raw: Map<String, Any?>,
        base: String,
        cast: (Any?) -> T?
    ): Map<String, T> {
        val legacy = cast(raw[base])
        return TelemetryTables.ALL.mapNotNull { table ->
            val key = keyFor(base, table)
            val value = if (raw.containsKey(key)) cast(raw[key]) else legacy
            value?.let { table to it }
        }.toMap()
    }
}
```

- [ ] **Step 11: Run the codec tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*StatusCodecTest*'`
Expected: PASS. Then perform the mutation check the legacy test names: delete `KEY_FAILURES to null, KEY_BACKOFF_UNTIL to null` from `encode`, re-run, confirm `a write clears the legacy keys` fails, and restore. Record the result in your report.

- [ ] **Step 12: Rewrite `PrefsStatusStore` around the codec**

Replace everything below the class declaration. The `ClassCastException` catch goes — `prefs.all` hands back `Any?` and the codec casts defensively, so `read()` cannot throw. Keep the class KDoc, adding a line saying the mapping lives in `StatusCodec`.

```kotlin
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Guards [update]'s read-modify-write. SharedPreferences itself is
     *  thread-safe per call, but nothing stops two concurrent [update]s from
     *  interleaving their read and their write without this. */
    private val lock = Any()

    override fun read(): TelemetryStatus = StatusCodec.decode(prefs.all)

    override fun write(status: TelemetryStatus) {
        val editor = prefs.edit()
        for ((key, value) in StatusCodec.encode(status)) {
            when (value) {
                // A recovered table's key must be removed, not left at a stale
                // value, or the next read would resurrect the deadline this
                // write just cleared.
                null -> editor.remove(key)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                // Unreachable: encode produces only the three types above and
                // null. A silent skip would drop a field, so fail loudly — this
                // would be a programming error, not a data error.
                else -> throw IllegalStateException("unsupported status value for $key")
            }
        }
        editor.apply()
    }

    override fun update(transform: (TelemetryStatus) -> TelemetryStatus) {
        synchronized(lock) { write(transform(read())) }
    }
```

Delete the private companion — the keys live in `StatusCodec` now.

- [ ] **Step 13: Write the failing outbox tests**

Add to `TelemetryOutboxTest.kt`. Note `outbox()` is a **factory function**, so every test starts `val box = outbox()`, and the file's `appendDonation` extension exists for donation rows.

```kotlin
    /**
     * The defect this phase exists to avoid, and the reason exclusion lives
     * inside the read. Filtering a batch already truncated to its first `limit`
     * rows yields nothing at all while the head is excluded — and because peek
     * removes nothing, the head never advances. A bounded delay would have
     * become permanent starvation for every row behind it.
     *
     * Mutation check: move the filter after `take` and this test must fail.
     */
    @Test
    fun excludedRowsAtTheHeadDoNotCrowdOutTheRowsBehindThem() {
        val box = outbox()
        repeat(5) { box.append("d$it", TelemetryTables.DIAGNOSTICS, """{"id":"d$it"}""") }
        box.appendDonation("donation")

        val batch = box.peek(limit = 3, excludeTables = setOf(TelemetryTables.DIAGNOSTICS))

        assertEquals(listOf("donation"), batch.map { it.id })
    }

    @Test
    fun exclusionPreservesQueueOrder() {
        val box = outbox()
        box.appendDonation("a")
        box.append("d", TelemetryTables.DIAGNOSTICS, """{"id":"d"}""")
        box.appendDonation("b")

        val batch = box.peek(excludeTables = setOf(TelemetryTables.DIAGNOSTICS))

        assertEquals(listOf("a", "b"), batch.map { it.id })
    }

    @Test
    fun peekWithNoExclusionsBehavesExactlyAsBefore() {
        val box = outbox()
        box.appendDonation("a")
        box.append("d", TelemetryTables.DIAGNOSTICS, """{"id":"d"}""")
        assertEquals(listOf("a", "d"), box.peek().map { it.id })
    }
```

There is deliberately no `excluded rows stay queued` outbox test. `peek` removes nothing and never has, so such a test passes even if `excludeTables` is ignored entirely, even if the filter is moved after `take`, and even if `peek` is replaced by `{ emptyList() }`. The property it was reaching for — an excluded row is not treated as consumed — is asserted at the flush level in Task 3.

- [ ] **Step 14: Add the parameter**

```kotlin
    /**
     * The head of the queue, minus any row belonging to [excludeTables].
     *
     * Filtering happens **before** [limit] is applied, and that ordering is the
     * whole point: taking the first [limit] rows and then dropping the excluded
     * ones would return nothing at all while the head is excluded, and since
     * peek removes nothing the head would never advance. Rows behind an
     * excluded run would stop sending entirely.
     *
     * Default-empty, so no existing caller changes. Queue order is preserved;
     * nothing here groups or sorts by table.
     */
    fun peek(limit: Int = DEFAULT_BATCH, excludeTables: Set<String> = emptySet()): List<QueuedEvent> =
        synchronized(lock) {
            readAll().asSequence()
                .filterNot { it.table in excludeTables }
                .take(limit)
                .toList()
        }
```

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryOutboxTest*'` — expected PASS. Then run the mutation the head-of-line test names (move `.take(limit)` above `.filterNot`), confirm it fails, restore, and record it.

- [ ] **Step 15: Add the mixed-table fixture**

`outboxWith` at `TelemetryManagerTest.kt:79` hard-codes `TelemetryTables.DONATIONS`. Every multi-table test in this plan needs a queue that varies the table, so add a sibling rather than changing the existing one (thirty-odd tests depend on its shape):

```kotlin
    /** A queue whose rows name their own table, for the per-table backoff tests.
     *  The existing [outboxWith] is donations-only and stays that way — the
     *  tests built on it are about the flush, not about tables. */
    private fun outboxWithRows(vararg rows: Pair<String, String>): TelemetryOutbox {
        val outbox = TelemetryOutbox(temp.newFile())
        rows.forEach { (id, table) -> outbox.append(id, table, """{"id":"$id"}""") }
        return outbox
    }
```

- [ ] **Step 16: Write the failing flush-exclusion tests**

Add to `TelemetryManagerTest.kt`. `now` is the class field `1_000_000L`; posters are constructed inline.

```kotlin
    /**
     * The reason this phase exists. A refused diagnostics table must not hold
     * up a donation queued behind it — and with more backed-off rows at the
     * head than a batch holds, a filter applied after the batch was taken would
     * send nothing at all.
     */
    @Test
    fun aDonationStillUploadsWhileDiagnosticsAreBackedOff() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(
            backoffUntilMsByTable = mapOf(TelemetryTables.DIAGNOSTICS to now + 30_000)
        ))
        val outbox = outboxWithRows(
            "x1" to TelemetryTables.DIAGNOSTICS,
            "x2" to TelemetryTables.DIAGNOSTICS,
            "a" to TelemetryTables.DONATIONS
        )
        val poster = ConstantPoster(HttpResponse(201, null))

        val result = manager(outbox, poster, statusStore = store).flush()

        assertEquals(FlushBlock.NONE, result)
        assertEquals("the excluded rows must still be queued", 2, outbox.size())
        assertEquals(listOf("x1", "x2"), outbox.peek().map { it.id })
    }

    @Test
    fun aBatchEmptiedByExclusionReportsBackingOff() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(
            backoffUntilMsByTable = mapOf(TelemetryTables.DIAGNOSTICS to now + 30_000)
        ))
        val outbox = outboxWithRows("x1" to TelemetryTables.DIAGNOSTICS)
        val poster = ConstantPoster(HttpResponse(201, null))

        assertEquals(
            FlushBlock.BACKING_OFF,
            manager(outbox, poster, statusStore = store).flush()
        )
        assertEquals("nothing may be sent while backing off", 0, poster.callCount)
    }

    @Test
    fun aGenuinelyEmptyQueueStillReportsEmptyQueue() {
        val outbox = TelemetryOutbox(temp.newFile())
        assertEquals(
            FlushBlock.EMPTY_QUEUE,
            manager(outbox, ConstantPoster(HttpResponse(201, null))).flush()
        )
    }

    @Test
    fun anElapsedDeadlineDoesNotExcludeItsTable() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(
            backoffUntilMsByTable = mapOf(TelemetryTables.DIAGNOSTICS to now - 1)
        ))
        val outbox = outboxWithRows("x1" to TelemetryTables.DIAGNOSTICS)
        val poster = ConstantPoster(HttpResponse(201, null))

        assertEquals(FlushBlock.NONE, manager(outbox, poster, statusStore = store).flush())
        assertEquals(0, outbox.size())
    }
```

The existing `aBackoffDeadlineInTheFutureBlocksTheFlush` at `:220-229` keeps its name and its assertion; only its seed changes, in Step 18.

- [ ] **Step 17: Exclusion and `BACKING_OFF` in the flush**

In `TelemetryManager.flush`, `GateInputs(...)` drops `backoffUntilMs = before.backoffUntilMs`. Then, after the `cfg` line:

```kotlin
        val backedOffTables = before.backoffUntilMsByTable
            .filterValues { TelemetryGate.isTableBackedOff(it, now) }
            .keys

        val batch = outbox.peek(excludeTables = backedOffTables)
        // Recorded regardless of what happens next, so activate() can tell a row
        // this page never reached (FIX 3) apart from one it sent but could not
        // confirm. Automatically correct under exclusion: an excluded row was
        // never in the batch to begin with.
        lastAttemptedIds = batch.map { it.id }.toSet()
        if (batch.isEmpty()) {
            // The gate already reported EMPTY_QUEUE if the queue was empty, so
            // an empty batch here almost always means exclusion emptied it. A
            // queue that drained between the two reads while some table was
            // backed off is reported as BACKING_OFF rather than EMPTY_QUEUE —
            // a wrong logcat reason and a wrong Blocked payload, nothing more.
            // The cost of getting here at all is one extra readAll on a flush
            // that does no network work.
            return if (backedOffTables.isEmpty()) FlushBlock.EMPTY_QUEUE else FlushBlock.BACKING_OFF
        }
```

Keep `val before = statusStore.read()` — it is now read for the exclusion set rather than for the gate.

- [ ] **Step 18: Port every remaining call site**

`TelemetryManager.kt`:
- `activate()`'s precheck drops `backoffUntilMs = 0L`. Its comment claims "backoff forced to 0 (the reset a few lines down always clears it for real, so a stale deadline genuinely cannot block this press)" — that simulation no longer exists; delete the clause, keep the rest.
- `activate()`'s reset becomes `it.copy(consecutiveFailuresByTable = emptyMap(), backoffUntilMsByTable = emptyMap(), lastError = null)`.
- The throw branch and the `when` still write the scalars. Rewrite both to write **every table in `TelemetryTables.ALL`** on failure — count+1 and the derived deadline for each — and to clear both maps entirely on success. **Leave every other field of each `copy(...)` exactly as it is**, including `lastError`, `lastErrorAtMs`, and `lastSuccessMs = if (outcome.uploadedIds.isEmpty()) fresh.lastSuccessMs else finishedAt`; dropping that last one would break `aPartialSuccessMovesLastSuccessMsWhileStillBackingOff` a commit early.

  `ALL`, deliberately, and not `batch.map { it.table }`. The batch almost never holds all three tables, so attributing failure to it would mean a donations-only failure leaves diagnostics free to attempt — which is this phase's *intended* behaviour arriving three commits early, under a task that claims to change nothing. It would also make the exponential schedule climb more slowly than today (two failing flushes on different tables reaching 60 s each instead of 120 s once), and a reviewer of this commit would have no reason to expect either. Task 3 narrows failure to the batch's tables, and that is what makes its `aThrowBacksOffEveryTableInTheBatchAndNoOther` a genuine red-first test rather than a restatement of code already written here.

  Task 3 replaces this block wholesale; it exists only so the suite stays green and this commit is genuinely behaviour-preserving.

`AnalyticsPresenter.view` — add one local above the `return` and use it for both backoff fields. Leave `error` alone; Task 4 changes it, and changing it here would blur which task the behaviour came from.

```kotlin
        val effectiveBackoffUntilMs = status.effectiveBackoffUntilMs(nowMs)
```
```kotlin
            backingOff = effectiveBackoffUntilMs > nowMs,
            backoffRemainingSeconds =
                (effectiveBackoffUntilMs - nowMs).coerceAtLeast(0L).let { (it + 999) / 1000 },
            consecutiveFailures = status.consecutiveFailuresByTable.values.maxOrNull() ?: 0,
```

`MainActivity.startAnalyticsBackoffTickerIfNeeded` reads the raw field at `:2001` and `:2008`. Both become the helper, each against the `now` it is comparing to. Missing either leaves the countdown frozen or the ticker never starting.

```kotlin
        val backoffUntilMs = analyticsSnapshot?.status?.effectiveBackoffUntilMs(analyticsNowMs) ?: 0L
```
```kotlin
                val stillBackingOff =
                    (analyticsSnapshot?.status?.effectiveBackoffUntilMs(analyticsNowMs) ?: 0L) > analyticsNowMs
```

Tests. The default port: seeds of the form `consecutiveFailures = N` / `backoffUntilMs = X` become `consecutiveFailuresByTable = TelemetryTables.ALL.associateWith { N }` / `backoffUntilMsByTable = TelemetryTables.ALL.associateWith { X }`; reads become `.values.maxOrNull() ?: 0` and `effectiveBackoffUntilMs(now)`.

**Three sites where that default is wrong. Apply the stated port instead.**

- **`:166` / `:168`, `aSuccessResetsTheFailureCount`.** Seed `consecutiveFailuresByTable = mapOf(TelemetryTables.DONATIONS to 4)`, not `ALL`. Its outbox is `outboxWith("a")`, which is donations-only (`:81`), so from Task 3 onward a success clears `succeededTables - failedTables` = `{DONATIONS}` alone — the other two tables would keep their seeded 4, `maxOrNull()` would return 4, and `assertEquals(0, …)` would fail. It passes either way under Task 1's lockstep clear, which is exactly why the wrong shape would survive this commit and fail in Task 3.
- **`:325`, `statusReportsTheQueueDepth`.** This test is not about backoff: it seeds every stored field to a distinctive value and asserts `status()` carries each one through. Its seed `123_456L` is *earlier* than the test's `now` (`1_000_000L`), so `effectiveBackoffUntilMs(now)` filters it out and returns `0L`. Assert on `reported.backoffUntilMsByTable.values.maxOrNull()` instead.
- **`:223`, `aBackoffDeadlineInTheFutureBlocksTheFlush`.** `ALL.associateWith { now + 30_000 }` is correct here — every table excluded, empty batch, `BACKING_OFF`. Listed so you do not "fix" it.

Known sites — **treat this as a cross-check against your Step 1 recon, not as the complete list**:

- `TelemetryManagerTest.kt` seeds: `:166`, `:214`, `:223`, `:316-317`, `:368`, `:550-551`, `:587`.
- `TelemetryManagerTest.kt` reads: `:145`, `:146`, `:168`, `:217`, `:324-325`, `:342`, `:343`, `:383`, `:423`, `:465`, `:466`, `:598`.
- `ClearCredentialsTest.kt:27` (seed) and `:35` (read). `TelemetryTeardown` itself needs no change — it writes `TelemetryStatus()`, whose new defaults are empty maps.
- `AnalyticsPresenterTest.kt`: any case seeding either scalar.

- [ ] **Step 19: Correct the three comments this task made untrue**

A comment is fixed in the commit that breaks it, or it ships broken in between — and all three break here.

- **`TelemetryManager.kt:91-92`** (`activate()`'s KDoc) and **`:107-115`** (the precheck comment above `GateInputs`) describe backoff as one global deadline, and `:107-115` explains the `backoffUntilMs = 0L` simulation this step deletes. Rewrite both around what `activate()` now does: it clears **every table's** failure state, so a corrected destination is testable on the next press rather than up to an hour later. This supersedes the clause-deletion noted in Step 18 — do the rewrite once, here.
- **`MainActivity.kt:2056-2058`** says `activate()` evaluates the gate "against inputs that force `activated = true`, `backoffUntilMs = 0` and a queue depth…". `GateInputs.backoffUntilMs` ceases to exist in Step 6. The comment's *conclusion* survives — `activate()` still cannot produce `BACKING_OFF`, because its reset clears both maps before `flush` re-reads them for the exclusion set — so correct the justification in place. You are already editing this file at `:2001`/`:2008`.

  **This deviates from the spec**, which defers that comment to 3d-ii because it "describes backoff as a single global deadline". The spec did not anticipate that this phase would make it name a deleted symbol. Note the deviation in your report.

- [ ] **Step 20: Give `AnalyticsPresenterTest.view` a `now` parameter**

Task 4's tests need to vary `now`, and the helper at `:13-17` closes over the class field. Add the parameter now, defaulting to the field, so Task 4 changes nothing structural:

```kotlin
    private fun view(
        settings: Settings = Settings(analyticsEnabled = true, installId = "install-1"),
        config: TelemetryConfig? = TelemetryConfig("https://abc.supabase.co", "publishable-key"),
        status: TelemetryStatus = TelemetryStatus(),
        now: Long = this.now
    ) = AnalyticsPresenter.view(settings, config, status, now)
```

- [ ] **Step 21: Build, run the whole suite, commit**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: PASS, with no `@Ignore` anywhere.

```bash
git add -A
git commit -m "Key backoff state by table"
```

---

## Task 2: The uploader reports which tables failed and which succeeded

**Files:**
- Modify: `telemetry/TelemetryUploader.kt`, `telemetry/TelemetryManager.kt`
- Test: `TelemetryUploaderTest.kt`, `TelemetryManagerTest.kt`

**Interfaces:**
- Produces: `UploadOutcome(uploadedIds: Set<String>, rejectedIds: Set<String>, retryableTables: Set<String>, succeededTables: Set<String>, lastError: String?)`.
- Consumes: nothing from Task 1. This task uses `TelemetryTables.DONATIONS` / `.DIAGNOSTICS`, which exist at HEAD.

- [ ] **Step 1: Reconnaissance**

Read `TelemetryUploaderTest.kt` in full and list every line reading `retryableFailure`. There are 22, at `:107, 117, 142, 161, 173, 184, 228, 239, 250, 265, 290, 320, 349, 369, 389, 412, 431, 448, 546, 570, 592, 636`. All stop compiling in Step 4. If your list differs, trust your list and say so in your report.

Then widen the search, because the arity change reaches past that one file: `grep -rn 'UploadOutcome(' app/src`. Five hits — the declaration and two returns in `TelemetryUploader.kt`, and **two** constructions in `TelemetryManagerTest.kt`. Step 7 handles both; note that the recon for that file's *other* contents happens in Task 3, so this grep is the only thing standing between you and a task that does not compile.

- [ ] **Step 2: Write the failing tests**

Add to `TelemetryUploaderTest.kt`, using its `RecordingPoster`, `event(id, table)` and `uploader(poster)`.

```kotlin
    /**
     * The precedence rule the spec names: **failure wins**. Reachable only in
     * the per-row fallback, which needs a batch-level refusal first — a
     * multi-row group whose first response is a plain failure takes the else
     * arm and never retries rows individually.
     */
    @Test
    fun aTableThatPartlyUploadedAndThenFailedAppearsInBothSets() {
        var call = 0
        val poster = RecordingPoster { _, _ ->
            when (call++) {
                0 -> HttpResponse(400, "batch refused")               // the 2-row batch
                1 -> HttpResponse(201, null)                          // row x1 alone: accepted
                else -> HttpResponse(HttpResponse.TRANSPORT_FAILURE, null)  // row x2: network dies
            }
        }
        val outcome = uploader(poster).upload(listOf(
            event("x1", TelemetryTables.DIAGNOSTICS),
            event("x2", TelemetryTables.DIAGNOSTICS)
        ))

        assertEquals(setOf("x1"), outcome.uploadedIds)
        assertTrue(outcome.succeededTables.contains(TelemetryTables.DIAGNOSTICS))
        assertTrue(outcome.retryableTables.contains(TelemetryTables.DIAGNOSTICS))
    }

    @Test
    fun succeededTablesNamesATableWhoseRowWasAbsorbedAsAlreadyStored() {
        // A single-row 409 confirmed as 23505 counts as uploaded, so its table
        // counts as succeeded. Build the response body the same way the
        // existing already-stored tests in this file do.
    }
```

Write the second body out against the file's existing already-stored fixture rather than inventing one — grep the file for `isAlreadyStored` / `23505` and copy the shape.

Then **strengthen** the existing `oneTableFailingDoesNotLoseAnotherTablesSuccess` at `:254-266` rather than adding a parallel test: it is already the exact per-table-isolation fixture, and a mechanical port of its `assertTrue(outcome.retryableFailure)` would leave a name about isolation on an assertion that has stopped checking it.

```kotlin
        assertEquals(setOf("d1"), outcome.uploadedIds)
        assertEquals(setOf(TelemetryTables.DIAGNOSTICS), outcome.retryableTables)
        assertEquals(setOf(TelemetryTables.DONATIONS), outcome.succeededTables)
```

- [ ] **Step 3: Run and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryUploaderTest*'`
Expected: FAIL — `retryableTables` and `succeededTables` are unresolved.

- [ ] **Step 4: Change `UploadOutcome`**

```kotlin
data class UploadOutcome(
    val uploadedIds: Set<String>,
    val rejectedIds: Set<String>,
    /** Tables with at least one row that did not send and may yet. Narrower
     *  than the boolean it replaces: one refused table used to back off the
     *  healthy ones with it. */
    val retryableTables: Set<String>,
    /** Tables with at least one row named in [uploadedIds]. Reported rather
     *  than re-derived from the batch: attributing a success by matching ids
     *  back to rows would be the caller second-guessing this class, which the
     *  manager's charter forbids. */
    val succeededTables: Set<String>,
    val lastError: String?
)
```

Extend the class KDoc:

```
 * A table can legitimately appear in both [retryableTables] and
 * [succeededTables] — the per-row fallback can upload some rows and then lose
 * the network. Which one wins is the caller's rule, not this class's.
```

- [ ] **Step 5: Populate them in `upload`**

Replace `var retryable = false` (`:58`) with `val retryableTables = mutableSetOf<String>()`, and add `val succeededTables = mutableSetOf<String>()`. **Three** existing `retryable = true` sites become `retryableTables += table`: `:123` and `:142` inside the per-row fallback, and `:156` in the lone-refusal else arm. The empty-batch early return becomes `UploadOutcome(emptySet(), emptySet(), emptySet(), emptySet(), null)`.

At the end of each `for ((table, forTable) in ...)` iteration, record the success side once rather than at each of the three places a row can reach `uploaded`:

```kotlin
            if (forTable.any { it.id in uploaded }) succeededTables += table
```

Return `UploadOutcome(uploaded, rejected, retryableTables, succeededTables, lastError)`.

- [ ] **Step 6: Port the 22 assertions**

Mechanical, except where noted: `assertTrue(outcome.retryableFailure)` → `assertTrue(outcome.retryableTables.isNotEmpty())`; `assertFalse(outcome.retryableFailure)` → `assertTrue(outcome.retryableTables.isEmpty())`. Where a test's name is about a *specific* table, assert the set's contents rather than its emptiness — `:265` is done in Step 2; check `:290`, `:320` and `:448` for the same.

- [ ] **Step 7: Adapt the manager**

`outcome.retryableFailure` becomes `outcome.retryableTables.isNotEmpty()` — identical behaviour, since the boolean was true exactly when some table failed. Task 3 replaces the branch entirely.

**Two** tests construct `UploadOutcome` directly, and they need opposite treatment.

`aDropRecordedWhileAFlushIsInFlightSurvivesTheFlushsOwnStatusWrite` drives the seam with a *successful* outcome and must become:

```kotlin
                UploadOutcome(
                    uploadedIds = batch.map { it.id }.toSet(),
                    rejectedIds = emptySet(),
                    retryableTables = emptySet(),
                    succeededTables = setOf(TelemetryTables.DONATIONS),
                    lastError = null
                )
```

`succeededTables` must **not** be empty here: `outboxWith` is donations-only, so `{DONATIONS}` is what the real uploader would report for this batch, and this test exists to prove that a mid-flight `droppedCount` bump survives the flush's own status write. An outcome naming no table at all would make the flush's write clear nothing and touch less, weakening the very collision the test is built around.

`anOutcomeThatUploadedAndRejectedNothingIsNotRecordedAsASuccess` is the opposite case and its sets must **stay empty** — that all-empty shape is precisely what it pins:

```kotlin
            upload = { _, _ -> UploadOutcome(emptySet(), emptySet(), emptySet(), emptySet(), null) }
```

- [ ] **Step 8: Build, run the whole suite, commit**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`

```bash
git add -A
git commit -m "Report upload outcomes per table"
```

---

## Task 3: Per-table accounting in the flush

The behaviour change. Everything before it was shape.

**Seeding rule for every test in this task:** the flush excludes any table whose deadline is live (Task 1 Step 17). Seed failure *counts* freely; seed *deadlines* in the past, or not at all, unless the test is specifically about exclusion. A live deadline on the table under test means its rows are never attempted and the assertion will be about the wrong thing.

**The same trap arrives without a seed.** A flush that records a failure *produces* a deadline, so in any multi-flush test the failing table is excluded from the next flush — and un-excluded again the moment the clock passes that deadline, at which point its still-queued rows are retried. A retryable failure deletes nothing (`TelemetryManager.kt:241` removes only uploader-named ids), so those rows are always still there. Whenever a test advances the clock past a deadline it produced, decide deliberately what the poster answers on the retry.

**Files:**
- Modify: `telemetry/TelemetryManager.kt`
- Test: `TelemetryManagerTest.kt`

**Interfaces:**
- Consumes: `UploadOutcome.retryableTables` / `.succeededTables` (Task 2); `TelemetryStatus`'s maps, `TelemetryGate.isTableBackedOff`, `outboxWithRows` (Task 1).

- [ ] **Step 1: Reconnaissance**

Read `TelemetryManagerTest.kt` in full. Note `ScriptedPoster` returns one response per call by index and repeats the last; `aPartialSuccessMovesLastSuccessMsWhileStillBackingOff` is the worked example of driving the per-row fallback. Note `outboxWithRows` from Task 1 Step 15.

**Task 1 inserted a helper and several tests into this file, so every line number an earlier task quoted for it has moved.** Locate things by name here, not by line. List the tests that assert on `lastError`, `lastSuccessMs` or the failure counts, since Step 4 changes when each is written.

- [ ] **Step 2: Write the failing tests**

Add to `TelemetryManagerTest.kt`. Each prose body below states the setup and the assertion; write it out against the real fixtures.

```kotlin
    @Test
    fun aRefusedDiagnosticsTableLeavesDonationsClear() {
        // outboxWithRows("a" to DONATIONS, "x1" to DIAGNOSTICS); a
        // RecordingPoster-style ConstantPoster cannot vary by URL, so use a
        // poster that answers 503 for /diagnostic_events and 201 otherwise —
        // the shape TelemetryUploaderTest.kt:256-259 uses.
        // Assert: backoffUntilMsByTable has DIAGNOSTICS and not DONATIONS;
        // consecutiveFailuresByTable likewise.
    }

    @Test
    fun aSuccessClearsItsTableInTheSameFlushWhereASiblingFails() {
        // Seed BOTH tables with consecutiveFailuresByTable = 2 and NO deadlines
        // (see the seeding rule above). Flush with donations 201 and
        // diagnostics 503.
        // Assert: DONATIONS absent from both maps; DIAGNOSTICS at 3.
    }

    @Test
    fun aTableInBothSetsBacksOff() {
        // Drive the `upload` seam directly (manager(..., upload = { _, batch -> ... }))
        // with DIAGNOSTICS in retryableTables AND succeededTables.
        // Assert: DIAGNOSTICS is in backoffUntilMsByTable.
        // Mutation check: change `succeededTables - failedTables` to
        // `succeededTables` and confirm this fails.
    }

    @Test
    fun aThrowBacksOffEveryTableInTheBatchAndNoOther() {
        // outboxWithRows("a" to DONATIONS, "x1" to DIAGNOSTICS); upload seam
        // throws.
        // Assert: both are backed off; ACTIVATIONS is in neither map.
    }

    @Test
    fun perTableCountsDrivePerTableDelaysIndependently() {
        // Seed consecutiveFailuresByTable = mapOf(DIAGNOSTICS to 4, DONATIONS to 1),
        // no deadlines. Fail both in one flush.
        // Assert: the DIAGNOSTICS deadline is strictly further out than the
        // DONATIONS one. (backoffDelayMs(5) = 960s vs backoffDelayMs(2) = 120s.)
    }

    @Test
    fun activateClearsEveryTable() {
        // Seed all three tables with counts and deadlines, then activate().
        // Assert: both maps are empty.
    }

    @Test
    fun aFlushThatNeitherUploadedNorRejectedAnythingLeavesStatusAlone() {
        // Drive the seam with all four sets empty, against a seeded lastError
        // and a seeded (past) deadline.
        // Assert: the status is unchanged, field for field.
    }
```

And the one the spec's `lastError` rule actually rests on, which needs **two** flushes:

```kotlin
    /**
     * A healthy donations table must not erase the schema error that explains
     * why diagnostics are stuck — it is the only evidence an operator has.
     *
     * Two flushes, deliberately. In a single flush where diagnostics fails, the
     * error arrives as `errorText` and the first arm of recordOutcome's `when`
     * returns it regardless — so the retention rule is never exercised and
     * deleting it changes nothing. The rule only fires on a LATER flush, where
     * the healthy table succeeds and the broken one contributes no error
     * because it is excluded and never attempted.
     *
     * Mutation check: delete the `anyBackedOff -> fresh.lastError` arm and this
     * test must fail.
     */
    @Test
    fun aSiblingSuccessDoesNotClearLastErrorWhileATableIsBackedOff() {
        // Flush 1: diagnostics 503, donations 201 — records the error and the
        // diagnostics deadline.
        // Flush 2 (same `now`, so the deadline is still live): queue only a
        // donation, everything 201.
        // Assert: lastError is still the diagnostics error.
    }

    /**
     * The other half of the rule. Note flush 3 must answer **201 for diagnostics
     * too** — the operator fixed the backend.
     *
     * A 503 deletes nothing, so flush 1's diagnostics rows are still queued.
     * Flush 2 does not see them because the deadline excludes them; advancing
     * the clock past that deadline is exactly what stops excluding them, so a
     * flush 3 that still answered 503 would re-attempt the same rows, set
     * errorText again, and leave lastError non-null — asserting the opposite of
     * what this test is named for.
     */
    @Test
    fun lastErrorClearsOnceNoTableIsBackedOff() {
        // Flushes 1 and 2 as above, then advance `now` past the diagnostics
        // deadline and flush with EVERYTHING answering 201.
        // Assert: lastError is null, and both maps are empty.
    }
```

- [ ] **Step 3: Run and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryManagerTest*'`
Expected: FAIL — Task 1's lockstep accounting backs off every table together.

- [ ] **Step 4: Factor one recording function**

```kotlin
    /**
     * The one place per-table failure state is written.
     *
     * Shared by the throw path and the outcome path, which differ only in where
     * the table sets come from — a transport-level throw has no table
     * attribution, so it names every table in the batch.
     */
    private fun recordOutcome(
        failedTables: Set<String>,
        succeededTables: Set<String>,
        /** Whether the uploader named any row as settled — sent or permanently
         *  refused. Kept separate from [succeededTables] because the existing
         *  success branch guarded on `uploadedIds.isNotEmpty() ||
         *  rejectedIds.isNotEmpty()` and documented a refusal to assume the
         *  uploader cannot produce rejections without uploads. That refusal is
         *  preserved here. What per-table state such an outcome would clear is
         *  a question it cannot answer — it names no tables — so it clears
         *  none, and only the global fields move. Note this does advance
         *  [TelemetryStatus.lastSuccessMs], where the old retryable branch did
         *  not — matching what the old *success* branch did on the same shape,
         *  which is the branch this one stands in for. */
        anyRowsSettled: Boolean,
        errorText: String?,
        finishedAt: Long
    ) {
        // Nothing happened, so nothing is recorded. Deliberately not folded into
        // the loops below: with both sets empty they run zero times, and the
        // lastError rule would still evaluate and clear an error no flush
        // disproved.
        if (failedTables.isEmpty() && succeededTables.isEmpty() && !anyRowsSettled) return

        // FIX (I4): transform the value the store hands back, not a snapshot
        // taken before the network call — anything written during the upload (an
        // outbox eviction's droppedCount, chiefly) must survive this write.
        statusStore.update { fresh ->
            val counts = fresh.consecutiveFailuresByTable.toMutableMap()
            val deadlines = fresh.backoffUntilMsByTable.toMutableMap()

            for (table in failedTables) {
                val failures = (counts[table] ?: 0) + 1
                counts[table] = failures
                deadlines[table] = finishedAt + TelemetryGate.backoffDelayMs(failures)
            }
            // Failure wins: a table in both sets has rows that did not send, and
            // clearing it here would retry them on the very next flush — a tight
            // loop against a broken link.
            for (table in succeededTables - failedTables) {
                counts.remove(table)
                deadlines.remove(table)
            }

            // Judged on the state this write produces, not the one it replaces:
            // that is the state the operator will be looking at.
            val anyBackedOff = deadlines.any { TelemetryGate.isTableBackedOff(it.value, finishedAt) }

            fresh.copy(
                consecutiveFailuresByTable = counts,
                backoffUntilMsByTable = deadlines,
                // A donations success must not wipe the schema error that
                // explains why diagnostics are stuck.
                lastError = when {
                    errorText != null -> errorText
                    anyBackedOff -> fresh.lastError
                    else -> null
                },
                // Only advanced when a non-null error is actually written, so a
                // retained error keeps the timestamp that explains it.
                lastErrorAtMs = if (errorText != null) finishedAt else fresh.lastErrorAtMs,
                // "Something reached the backend" is true, and it is what the
                // field means.
                lastSuccessMs =
                    if (succeededTables.isEmpty() && !anyRowsSettled) fresh.lastSuccessMs else finishedAt
            )
        }
    }
```

- [ ] **Step 5: Call it from both paths**

The throw branch's whole `statusStore.update { ... }` becomes:

```kotlin
            recordOutcome(
                // A transport-level throw is table-agnostic, so it names the
                // tables actually attempted rather than all three.
                failedTables = batch.map { it.table }.toSet(),
                succeededTables = emptySet(),
                anyRowsSettled = false,
                // Never the exception's message: it could carry a row value (a
                // donation amount, a stack trace fragment) that never went
                // through the redactor.
                errorText = t::class.java.name,
                finishedAt = clock()
            )
            return FlushBlock.NONE
```

The entire `when { ... }` after `outbox.remove(...)` becomes:

```kotlin
        recordOutcome(
            failedTables = outcome.retryableTables,
            succeededTables = outcome.succeededTables,
            anyRowsSettled = outcome.uploadedIds.isNotEmpty() || outcome.rejectedIds.isNotEmpty(),
            errorText = outcome.lastError,
            finishedAt = finishedAt
        )
```

Keep `val finishedAt = clock()` and its "read fresh" comment — the reason is unchanged.

- [ ] **Step 6: Run the tests, then the two mutations**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryManagerTest*'` — expected PASS.

Then run both mutations the tests name — `succeededTables - failedTables` → `succeededTables`, and deleting the `anyBackedOff -> fresh.lastError` arm — confirming the named test fails each time, and restore. Record both in your report; a test that passes under its own named mutation is a finding, not a pass.

- [ ] **Step 7: Build, run the whole suite, commit**

```bash
git add -A
git commit -m "Back off one table at a time"
```

---

## Task 4: What the screen sees

Keeping the error in the store is not enough. The presenter suppresses a stale error with `takeIf { lastSuccessMs <= lastErrorAtMs }` (`AnalyticsPresenter.kt:111`), and now that a sibling's success advances `lastSuccessMs` past a retained `lastErrorAtMs`, the screen would render nothing beside a non-zero failure count. A store-level assertion passes while the screen is blank, so this task's tests assert on `AnalyticsView.error`.

**Files:**
- Modify: `telemetry/AnalyticsPresenter.kt:111`
- Test: `AnalyticsPresenterTest.kt`

**Interfaces:**
- Consumes: `effectiveBackoffUntilMs` and the `now` parameter on the test helper (Task 1), the retained-`lastError` rule (Task 3).

- [ ] **Step 1: Reconnaissance**

Read `AnalyticsPresenterTest.kt`. Task 1 Step 20 added `now: Long = this.now` to the `view(...)` helper — confirm it is there before writing tests that pass it.

- [ ] **Step 2: Write the failing tests**

```kotlin
    /**
     * The store keeps a live error while a table is backed off, but the screen
     * is what the operator reads — and "Failed attempts: 3" beside a blank
     * error line is the outcome the retention rule exists to prevent. An
     * assertion on the store would pass with the screen empty.
     */
    @Test
    fun anErrorStaysOnScreenWhileATableIsBackedOffEvenAfterASiblingSucceeds() {
        val at = 10_000L
        val result = view(
            status = TelemetryStatus(
                lastError = "HTTP 400 bad column",
                lastErrorAtMs = at - 5_000,
                lastSuccessMs = at - 1_000,   // the sibling's success, later than the error
                backoffUntilMsByTable = mapOf(TelemetryTables.DIAGNOSTICS to at + 30_000)
            ),
            now = at
        )
        assertEquals("HTTP 400 bad column", result.error)
    }

    @Test
    fun anErrorIsSuppressedOnceNoTableIsBackedOffAndASuccessFollowedIt() {
        val at = 10_000L
        val result = view(
            status = TelemetryStatus(
                lastError = "HTTP 400 bad column",
                lastErrorAtMs = at - 5_000,
                lastSuccessMs = at - 1_000
            ),
            now = at
        )
        assertNull(result.error)
    }

    @Test
    fun aFreshErrorWithNoSuccessSinceIsStillShown() {
        val at = 10_000L
        val result = view(
            status = TelemetryStatus(
                lastError = "HTTP 503 down",
                lastErrorAtMs = at - 1_000,
                lastSuccessMs = at - 5_000
            ),
            now = at
        )
        assertEquals("HTTP 503 down", result.error)
    }
```

`assertNull` must be imported — the file currently imports only `assertEquals`, `assertFalse` and `assertTrue`.

- [ ] **Step 3: Run and watch the first one fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*AnalyticsPresenterTest*'`
Expected: `anErrorStaysOnScreen...` FAILS with `expected:<HTTP 400 bad column> but was:<null>`.

- [ ] **Step 4: Change the rule**

```kotlin
            // Shown while any table is backed off, whatever the timestamps say:
            // a sibling's success now advances lastSuccessMs past a retained
            // lastErrorAtMs, and suppressing on that comparison alone would
            // leave the operator reading a failure count with no failure beside
            // it. The comparison still decides the case where nothing is backed
            // off — an error a later success genuinely superseded.
            //
            // Queue depth is not evidence of a success either: flush can empty
            // the outbox by permanently rejecting rows in the same flush that
            // records a retryable failure.
            error = status.lastError?.takeIf {
                effectiveBackoffUntilMs > nowMs || status.lastSuccessMs <= status.lastErrorAtMs
            },
```

- [ ] **Step 5: Build, run the whole suite, commit**

```bash
git add -A
git commit -m "Keep a live error on screen while a table is backed off"
```

---

## Task 5: The carried items on this path

Six contained changes sharing the send path, batched because each is small and splitting them would buy six review seats for one diff.

**Files:**
- Modify: `telemetry/TelemetryRedactor.kt:33-40`, `telemetry/DiagnosticEvents.kt:214`, `telemetry/AnalyticsPresenter.kt`, `recovery/RestartManager.kt:87-88` (comment), `MainActivity.kt`, `screens/AnalyticsSettingsScreen.kt`
- **Not** `telemetry/TelemetryManager.kt` — its stale comments are corrected in Task 1 Step 19, the commit that breaks them.
- Test: `AnalyticsPresenterTest.kt`, `TelemetryRedactorTest.kt`

**Interfaces:**
- Produces: `AnalyticsView.lastSuccessText: String`; `AnalyticsPresenter.view(..., zone: ZoneId, locale: Locale)`; `TelemetryRedactor.TRUNCATION_SUFFIX`.

- [ ] **Step 1: Clear the watchdog slot before each login launch**

In `MainActivity.authenticate`, immediately before `SumUpAPI.openLoginActivity(this@MainActivity, sumupLogin, 1)` (`:1079`):

```kotlin
        // If openLoginActivity ever returns without launching, the watchdog
        // below arms a label that finishActivity cannot clear — and the next
        // genuine login failure then reports as self-inflicted. Whether the SDK
        // can do that is unknowable from this repo, and this does not need to
        // know: every genuine code-1 result comes from a launch, and every
        // launch now clears first.
        //
        // One case it does not cover: a re-entrant authenticate while a login is
        // still outstanding erases a legitimately armed label, so a synthetic
        // close reads as genuine. That is the safe direction — a missing
        // discriminator, not a false one.
        syntheticCloseLogin = null
        SumUpAPI.openLoginActivity(this@MainActivity, sumupLogin, 1)
```

Then trim the watchdog comment at `:1096-1106`: the paragraph from "What that ordering does not cover" describes the gap this closes. Replace it with one sentence pointing at the clear above.

Not unit-testable — `MainActivity` is unreachable from JVM tests. Say so in your report, not in a test that asserts nothing.

- [ ] **Step 2: One definition of the truncation suffix**

In `TelemetryRedactor`:

```kotlin
    /** One definition, because [DiagnosticEvents] measures this string's
     *  JSON-escaped length against its own budget — two copies that drifted
     *  would make that budget silently wrong. */
    const val TRUNCATION_SUFFIX = "\n… truncated"

    fun truncate(text: String?): String? {
        if (text == null) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_TEXT_BYTES) return text
        // A cut can land mid-codepoint; the resulting replacement char is
        // harmless in a diagnostic and cheaper than scanning for a boundary.
        return String(bytes, 0, MAX_TEXT_BYTES, Charsets.UTF_8) + TRUNCATION_SUFFIX
    }
```

In `DiagnosticEvents.kt`, delete the private `TRUNCATION_SUFFIX` at `:214` and use `TelemetryRedactor.TRUNCATION_SUFFIX` at `:233` and `:240`. The two truncation *functions* stay — they cut different things for different reasons.

The two callers of `truncate` (`TelemetryEvent.kt:120`, `TelemetryUploader.kt:192`) already use the default. Check `TelemetryRedactorTest.kt` for a case passing an explicit `maxBytes`; rewrite it to build an oversized input against `MAX_TEXT_BYTES` rather than shrinking the limit.

"The suffix literal has one definition" is a grep property, not a test: `grep -rn '… truncated' app/src/main` must return exactly one line. Record the output.

- [ ] **Step 3: Write the failing presenter timestamp test**

Assert the *dependence*, not a literal. `FormatStyle.SHORT` output is CLDR-data dependent and its exact shape has changed across JDK releases; pinning `"14/11/2023, 22:13"` would make this a JDK-upgrade tripwire, and the property the move behind the presenter exists to establish is that zone and locale are injected rather than read from the platform.

```kotlin
    @Test
    fun theLastUploadTimestampUsesTheInjectedZone() {
        val status = TelemetryStatus(lastSuccessMs = 1_700_000_000_000L)
        assertNotEquals(
            view(status = status, zone = ZoneId.of("UTC"), locale = Locale.UK).lastSuccessText,
            view(status = status, zone = ZoneId.of("Asia/Tokyo"), locale = Locale.UK).lastSuccessText
        )
    }

    @Test
    fun theLastUploadTimestampUsesTheInjectedLocale() {
        val status = TelemetryStatus(lastSuccessMs = 1_700_000_000_000L)
        assertNotEquals(
            view(status = status, zone = ZoneId.of("UTC"), locale = Locale.UK).lastSuccessText,
            view(status = status, zone = ZoneId.of("UTC"), locale = Locale.JAPAN).lastSuccessText
        )
    }
```

Add `zone` and `locale` to the `view(...)` helper with fixed defaults so no existing test changes, and pass them through — Step 4 makes them required, so the existing four-argument call stops compiling:

```kotlin
    private fun view(
        settings: Settings = Settings(analyticsEnabled = true, installId = "install-1"),
        config: TelemetryConfig? = TelemetryConfig("https://abc.supabase.co", "publishable-key"),
        status: TelemetryStatus = TelemetryStatus(),
        now: Long = this.now,
        zone: ZoneId = ZoneId.of("UTC"),
        locale: Locale = Locale.UK
    ) = AnalyticsPresenter.view(settings, config, status, now, zone, locale)
```

Import `assertNotEquals`, `java.time.ZoneId`, `java.util.Locale`.

- [ ] **Step 4: Move the formatting behind the presenter**

Add `val lastSuccessText: String` to `AnalyticsView`, beside `lastSuccessMs`. `view` takes two new **required** parameters — a default reading `ZoneId.systemDefault()` would put the machine dependence straight back inside the presenter:

```kotlin
    fun view(
        settings: Settings,
        config: TelemetryConfig?,
        status: TelemetryStatus,
        nowMs: Long,
        zone: ZoneId,
        locale: Locale
    ): AnalyticsView {
```

```kotlin
            /** Formatted here rather than in the composable: the screen cannot be
             *  unit-tested, so a computation left inside it is a computation
             *  nothing checks. The neverUploaded branch stays a screen concern —
             *  rendering it needs a Strings member the presenter must not reach
             *  for. */
            lastSuccessText = TIMESTAMP_FORMAT.withLocale(locale).withZone(zone)
                .format(Instant.ofEpochMilli(status.lastSuccessMs)),
```

```kotlin
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
```

Imports for `AnalyticsPresenter.kt`: `java.time.Instant`, `java.time.ZoneId`, `java.time.format.DateTimeFormatter`, `java.time.format.FormatStyle`, `java.util.Locale`. `java.time` is available unguarded at minSdk 30.

- [ ] **Step 5: Delete the screen's copy**

In `AnalyticsSettingsScreen.kt`, delete `formatTimestamp` at `:427-428` and its now-unused `DateFormat` / `Date` imports, and change `:290` to:

```kotlin
                            if (view.neverUploaded) strings.analyticsNeverUploaded else view.lastSuccessText,
```

In `MainActivity.kt:607-609`, pass the two new arguments — and **add `java.time.ZoneId` and `java.util.Locale` to `MainActivity`'s imports**, which has neither today:

```kotlin
                    analyticsView = AnalyticsPresenter.view(
                        settings,
                        analyticsSnapshot?.config,
                        analyticsSnapshot?.status ?: TelemetryStatus(),
                        analyticsNowMs,
                        ZoneId.systemDefault(),
                        Locale.getDefault()
                    ),
```

Verify no formatting or arithmetic is left on the screen: `grep -nE "DateFormat|SimpleDateFormat|/ 1000|\* 1000" app/src/main/java/com/sadaqah/kiosk/screens/AnalyticsSettingsScreen.kt` must return nothing. Record the output.

- [ ] **Step 6: Keep the `RestartManager` getters and say why**

`cardReaderFailures` and `reinitFailures` were listed for removal on a wrong count: nine reads across `RestartManagerTest.kt`, two of them asserting counter *isolation*, which `RestartResult` alone cannot express. They are two properties on consecutive lines (`:87-88`), and a KDoc attaches to one declaration — so use a plain comment above the pair:

```kotlin
    // Read by tests only, and deliberately kept: two of them assert that a
    // card-reader failure does not move the reinit count, an invariant
    // RestartResult cannot express.
```

- [ ] **Step 7: Build, run the whole suite, commit**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`

```bash
git add -A
git commit -m "Clear the login watchdog slot, and move timestamp formatting behind the presenter"
```

---

## Self-review against the spec

**A note on line numbers.** Citations into `TelemetryManagerTest.kt` and `MainActivity.kt` are accurate at HEAD and are consumed by Task 1, which then shifts them by adding a helper, several tests and one net line. The same goes for `TelemetryManager.kt`, which Tasks 1, 2 and 3 each edit in turn. Later tasks therefore name tests, helpers and properties rather than lines in those three files. `TelemetryUploaderTest.kt`'s line citations stay valid — no earlier task touches it.

| Spec requirement | Task |
|---|---|
| `UploadOutcome` → two sets, both reported not derived | Task 2 Steps 4-5 |
| Failure wins when a table is in both sets | Task 3 Step 4, with a named mutation check |
| `TelemetryStatus` two maps | Task 1 Step 5 |
| `effectiveBackoffUntilMs`, built on `isTableBackedOff` | Task 1 Steps 3, 5 |
| `lastError` retained across a sibling's success; cleared when nothing is backed off | Task 3 Step 4, tested over two flushes |
| `lastSuccessMs` advances when any table succeeds | Task 3 Step 4 |
| Presenter `error` rule, asserted at view level | Task 4 |
| A throw backs off the batch's tables, not all three | Task 3 Step 5 |
| `peek(excludeTables)` filtering before `limit` | Task 1 Steps 13-14, with the head-of-line mutation check |
| Flush computes the backed-off set; `lastAttemptedIds` narrows with it | Task 1 Step 17 |
| `BACKING_OFF` from the flush; `EMPTY_QUEUE` still distinct | Task 1 Steps 16-17 |
| `GateInputs.backoffUntilMs` removed; `isTableBackedOff` added | Task 1 Step 6 |
| `activate()` clears every table | Task 1 Step 18, tested in Task 3 |
| Per-table key scheme, per-key totality, legacy migration | Task 1 Steps 8-12 |
| `TelemetryTables` gains a list; read and write enumerate it | Task 1 Step 2 |
| Presenter aggregation; `MainActivity` ticker | Task 1 Step 18 |
| Watchdog slot cleared before `openLoginActivity` | Task 5 Step 1 |
| `truncate` loses `maxBytes`; one suffix definition | Task 5 Step 2 |
| `formatTimestamp` behind the presenter, zone and locale injected | Task 5 Steps 3-5 |
| Stale comments corrected | Task 1 Step 19 — the commit that breaks them |
| `RestartManager` getters kept with a reason | Task 5 Step 6 |
| Device checks 1-5 | Manual; stay in the spec |
| Nothing identified written when `analyticsEnabled` is off | Unchanged by this phase |

**Deletion is still uploader-sourced** at the single site `outcome.uploadedIds + outcome.rejectedIds`, covered by the existing `aPartialOutcomeRemovesBothTheUploadedAndTheRejectedRow` (`TelemetryManagerTest.kt:394-412`) and `aThrowingUploadBacksOffRatherThanRetryingInATightLoop` (`:330-350`). No task changes it.

**Two documented deviations from the spec:** `StatusCodec` exists because the spec's own tests are otherwise unwritable (stated above); `MainActivity.kt:2056-2058` is corrected here rather than in 3d-ii because this phase deletes the symbol it names (Task 1 Step 19).

**Three tests are deliberately absent** rather than overlooked: an `excluded rows stay queued` outbox test (passes under every mutation that matters — the property is asserted at flush level instead), and the two `TelemetryGateTest` cases whose bodies became duplicates once the gate stopped knowing about deadlines (Task 1 Step 7).
