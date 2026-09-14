# Telemetry Phase 3d-i — Per-Table Backoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A diagnostics table the backend refuses stops delaying donations that would upload fine.

**Architecture:** `TelemetryStatus`'s two failure fields become maps keyed by table name, and the uploader — which already sends one request per table — starts reporting which tables failed and which succeeded instead of one global boolean. The flush excludes backed-off tables *inside* `TelemetryOutbox.peek`, before the batch is truncated to its limit, so backed-off rows at the head can never crowd out the rows behind them. Four carried cleanups ride along on the same send path.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, Gson, `java.time`, SharedPreferences. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-14-telemetry-phase-3d-i-per-table-backoff.md`

## Global Constraints

- **No new third-party dependency, and no gradle file is touched.** JUnit 4 only — no Mockito, no MockK, no Robolectric.
- **No new `Strings` member**, and therefore no copy in eight languages.
- **A donation is never delayed indefinitely by another table's failure.** The bound stays what it is today: at most one backoff ceiling.
- **The donation flow is not altered.** No change to `makePayment` or any path a payment travels.
- **Every deletion decision still comes from the uploader**, never re-derived. `TelemetryManager` deletes exactly the ids the uploader names.
- **One corrupt prefs key costs one field, not the whole status.** `droppedCount` must never be zeroed by an unrelated key going bad.
- **A kiosk with `analyticsEnabled` off writes nothing identified to disk.**
- Comments explain a non-obvious *why*, never a *what*.
- Commit messages are short; no AI attribution, no session links, in any commit or PR.
- Run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest` before every commit. Both must pass.

## Plan-level decision the spec does not make

**The spec requires `PrefsStatusStore` tests that the current design cannot support.** Its Testing section asks for a per-table round trip, a one-corrupt-key-costs-one-field check, and a legacy-key migration check. No JVM test in this project can construct a `Context`, and both Robolectric and an instrumented harness are excluded (new dependency; hardware deferred).

So the mapping is extracted into a pure object, `StatusCodec`, that converts between `TelemetryStatus` and a plain `Map<String, Any?>`. `PrefsStatusStore` supplies `prefs.all` on the way in and drives the editor on the way out; it keeps no judgement of its own. Every risky part — the per-table key scheme, the per-key defensiveness, the legacy migration — becomes a pure function a JVM test exercises directly.

This also disposes of the `ClassCastException` catch entirely: reading from `Map<String, Any?>` with `as?` cannot throw, so a bad key yields its own field's default and nothing else. That is the spec's per-key totality requirement, obtained structurally rather than by discipline.

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
- `telemetry/TelemetryUploader.kt` — `UploadOutcome` carries `retryableTables` and `succeededTables`.
- `telemetry/TelemetryManager.kt` — per-table outcome accounting, exclusion, `BACKING_OFF`, `activate()`.
- `telemetry/TelemetryOutbox.kt` — `peek` gains `excludeTables`.
- `telemetry/AnalyticsPresenter.kt` — aggregation, the error rule, `lastSuccessText`.
- `telemetry/TelemetryRedactor.kt` — `truncate` loses its parameter; owns the truncation suffix.
- `telemetry/DiagnosticEvents.kt` — references the shared suffix.
- `MainActivity.kt` — ticker helper, watchdog clear, presenter call site.
- `screens/AnalyticsSettingsScreen.kt` — `formatTimestamp` deleted; renders `view.lastSuccessText`.
- Tests: `TelemetryGateTest`, `TelemetryManagerTest`, `TelemetryOutboxTest`, `AnalyticsPresenterTest`, `TelemetryUploaderTest`, `TelemetryRedactorTest`, `DiagnosticEventsTest`.

---

## Task 1: The representation change, behaviour held constant

Every table moves in lockstep in this task: the *shape* becomes per-table, the *behaviour* stays global. Nothing here should change what a kiosk does. That isolation is the point — Task 3 changes behaviour against a suite that already passes in the new shape.

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryEvent.kt:9-13`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryStatusStore.kt:16-24`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryGate.kt`
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/StatusCodec.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/PrefsStatusStore.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryManager.kt` (call sites only)
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/AnalyticsPresenter.kt` (call sites only)
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt:2001,2008`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/StatusCodecTest.kt` (new), `TelemetryGateTest.kt`, `TelemetryManagerTest.kt`, `AnalyticsPresenterTest.kt`

**Interfaces:**
- Produces: `TelemetryTables.ALL: List<String>`; `TelemetryStatus.backoffUntilMsByTable: Map<String, Long>`, `TelemetryStatus.consecutiveFailuresByTable: Map<String, Int>`, `TelemetryStatus.effectiveBackoffUntilMs(nowMs: Long): Long`; `TelemetryGate.isTableBackedOff(backoffUntilMs: Long, nowMs: Long): Boolean`; `StatusCodec.decode(raw: Map<String, Any?>): TelemetryStatus`, `StatusCodec.encode(status: TelemetryStatus): Map<String, Any?>`.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Add the table list**

In `TelemetryEvent.kt`, replace the `TelemetryTables` object:

```kotlin
object TelemetryTables {
    const val DONATIONS = "donation_events"
    const val DIAGNOSTICS = "diagnostic_events"
    const val ACTIVATIONS = "telemetry_activations"

    /** Enumerated so per-table persistence reads and writes the same set. A
     *  fourth table added above and forgotten here would be written by one and
     *  never read back by the other, which no test would fail on. */
    val ALL = listOf(DONATIONS, DIAGNOSTICS, ACTIVATIONS)
}
```

- [ ] **Step 2: Write the failing test for the status helper**

Add to `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryGateTest.kt`:

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
     * A deadline beyond the ceiling was computed against a clock that has since
     * been corrected, and it is the *aggregate* that makes it dangerous: taking
     * a maximum, one corrupt entry dominates every healthy one and freezes the
     * screen for as long as the bad value says.
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
```

Ensure `org.junit.Assert.assertFalse` and `assertTrue` are imported.

- [ ] **Step 3: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryGateTest*'`
Expected: FAIL — `effectiveBackoffUntilMs` and `isTableBackedOff` are unresolved.

- [ ] **Step 4: Change `TelemetryStatus`**

In `TelemetryStatusStore.kt`, replace the two scalar fields and add the helper. Keep the existing KDoc on the data class and on `droppedCount` unchanged.

```kotlin
data class TelemetryStatus(
    val queued: Int = 0,
    val lastSuccessMs: Long = 0L,
    val lastError: String? = null,
    val lastErrorAtMs: Long = 0L,
    /** Per table, because the uploader sends one request per table and a table
     *  the backend refuses must not delay a table it accepts. A table absent
     *  from either map has no failures and no deadline — absence is the healthy
     *  state, so a fresh kiosk and a fully recovered one are the same value. */
    val consecutiveFailuresByTable: Map<String, Int> = emptyMap(),
    val backoffUntilMsByTable: Map<String, Long> = emptyMap(),
    val droppedCount: Int = 0
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

- [ ] **Step 5: Change `TelemetryGate`**

Remove `backoffUntilMs` from `GateInputs`, drop the two backoff branches from `evaluate`, and add the predicate. `FlushBlock.BACKING_OFF` stays in the enum — `TelemetryManager` returns it now.

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

Update the comment above `evaluate` — it currently promises the reason names "the real problem", which is still true, but the list it walks no longer ends at backoff. Leave the sentence, drop nothing else.

- [ ] **Step 6: Run the gate tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryGateTest*'`
Expected: FAIL to compile — the existing backoff cases still reference the removed field.

- [ ] **Step 7: Port the gate's backoff tests, do not delete them**

In `TelemetryGateTest.kt`: `ready()` at `:16` drops its `backoffUntilMs = 0L` argument. The four cases at `:58-77` assert on `evaluate` returning `BACKING_OFF`; that behaviour moved, so each becomes an `isTableBackedOff` case. Replace them with:

```kotlin
    @Test
    fun `a live deadline backs its table off`() {
        val now = 10_000L
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

The case at `:83` (a disabled kiosk with an absurd deadline reports `DISABLED`) and the one at `:93` now simply drop the `backoffUntilMs` argument — precedence among the remaining checks is unchanged and both still assert something real.

- [ ] **Step 8: Write the failing `StatusCodec` test**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/StatusCodecTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mapping is tested here rather than through [PrefsStatusStore] because no
 * JVM test in this project can construct a Context, and Robolectric is not an
 * available dependency. Everything with judgement in it lives in this object;
 * the store is the SharedPreferences plumbing around it.
 */
class StatusCodecTest {

    @Test
    fun `round trips per-table state`() {
        val status = TelemetryStatus(
            lastSuccessMs = 111L,
            lastError = "HTTP 400 bad column",
            lastErrorAtMs = 222L,
            consecutiveFailuresByTable = mapOf(TelemetryTables.DIAGNOSTICS to 3),
            backoffUntilMsByTable = mapOf(TelemetryTables.DIAGNOSTICS to 999L),
            droppedCount = 7
        )
        val decoded = StatusCodec.decode(StatusCodec.encode(status))
        assertEquals(status.copy(queued = 0), decoded)
    }

    @Test
    fun `a healthy table is absent from both maps rather than stored as zero`() {
        val encoded = StatusCodec.encode(
            TelemetryStatus(consecutiveFailuresByTable = mapOf(TelemetryTables.DIAGNOSTICS to 2))
        )
        val decoded = StatusCodec.decode(encoded)
        assertEquals(mapOf(TelemetryTables.DIAGNOSTICS to 2), decoded.consecutiveFailuresByTable)
    }

    /**
     * droppedCount is an operator's only view of telemetry loss, and it must not
     * be collateral damage from an unrelated key going bad. Before per-key
     * totality one ClassCastException anywhere yielded a wholly default status,
     * silently zeroing it — and going from six keys to ten multiplies the ways
     * that happens.
     */
    @Test
    fun `one corrupt key costs one field and nothing else`() {
        val raw = StatusCodec.encode(
            TelemetryStatus(
                droppedCount = 42,
                lastSuccessMs = 111L,
                backoffUntilMsByTable = mapOf(TelemetryTables.DONATIONS to 999L)
            )
        ).toMutableMap()
        raw["backoff_until_ms.donation_events"] = "not a long"

        val decoded = StatusCodec.decode(raw)
        assertEquals(42, decoded.droppedCount)
        assertEquals(111L, decoded.lastSuccessMs)
        assertEquals(emptyMap<String, Long>(), decoded.backoffUntilMsByTable)
    }

    @Test
    fun `a legacy single value seeds every table on first upgrade`() {
        val decoded = StatusCodec.decode(
            mapOf(
                "consecutive_failures" to 4,
                "backoff_until_ms" to 5_000L
            )
        )
        assertEquals(
            TelemetryTables.ALL.associateWith { 4 },
            decoded.consecutiveFailuresByTable
        )
        assertEquals(
            TelemetryTables.ALL.associateWith { 5_000L },
            decoded.backoffUntilMsByTable
        )
    }

    @Test
    fun `a per-table key wins over the legacy key it supersedes`() {
        val decoded = StatusCodec.decode(
            mapOf(
                "backoff_until_ms" to 5_000L,
                "backoff_until_ms.donation_events" to 9_000L
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

- [ ] **Step 9: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*StatusCodecTest*'`
Expected: FAIL — `StatusCodec` is unresolved.

- [ ] **Step 10: Write `StatusCodec`**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/StatusCodec.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

/**
 * The mapping between [TelemetryStatus] and a flat key-value store.
 *
 * Separate from [PrefsStatusStore] so that everything with judgement in it —
 * the per-table key scheme, the defensiveness against a bad value, the legacy
 * migration — is a pure function a JVM test can exercise. No unit test in this
 * project can construct a Context, so a mapping left inside the store is a
 * mapping nothing checks.
 *
 * Reads go through `as?` rather than a typed getter, so a key holding the wrong
 * type (a hand-edited or malformed adb-pushed prefs file — this project
 * provisions settings that way) costs its own field and nothing else. That
 * matters most for [TelemetryStatus.droppedCount], which is an operator's only
 * view of telemetry loss.
 */
object StatusCodec {

    const val KEY_LAST_SUCCESS = "last_success_ms"
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_LAST_ERROR_AT = "last_error_at_ms"
    const val KEY_DROPPED = "dropped_count"

    /** Also the exact key an install from before this phase holds a single
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
            KEY_DROPPED to status.droppedCount
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
     * rather than resetting to zero and hammering a destination that is still
     * refusing it. It is superseded table by table on the next write, and the
     * legacy key is never written again.
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
            val value = if (raw.containsKey(keyFor(base, table))) {
                cast(raw[keyFor(base, table)])
            } else {
                legacy
            }
            value?.let { table to it }
        }.toMap()
    }
}
```

- [ ] **Step 11: Run the codec tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*StatusCodecTest*'`
Expected: PASS.

- [ ] **Step 12: Rewrite `PrefsStatusStore` around the codec**

Replace the body of `PrefsStatusStore.kt` below the class declaration. The `ClassCastException` catch goes: `prefs.all` hands back `Any?` values and the codec casts defensively, so nothing in `read()` can throw. Keep the class KDoc, adding one line about where the mapping now lives.

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
                null -> editor.remove(key)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                // Unreachable: encode only produces the three types above. A
                // silent skip here would drop a field, so fail loudly instead —
                // this is a programming error, not a data error.
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

Note the `null -> editor.remove(key)` branch: a table that recovers must have its key removed, not left at a stale value, or the next `read` would resurrect the deadline it just cleared.

- [ ] **Step 13: Adapt the manager's call sites, behaviour unchanged**

This step is mechanical. In `TelemetryManager.kt`:

- `activate()`'s precheck `GateInputs(...)` drops `backoffUntilMs = 0L`. Trim the comment above it: the clause "backoff forced to 0 (the reset a few lines down always clears it for real, so a stale deadline genuinely cannot block this press)" describes a simulation that no longer exists — delete that clause and leave the rest.
- `activate()`'s reset becomes, per the spec's "activate clears every table":

```kotlin
        statusStore.update {
            it.copy(
                consecutiveFailuresByTable = emptyMap(),
                backoffUntilMsByTable = emptyMap(),
                lastError = null
            )
        }
```

- `flush`'s `GateInputs(...)` drops `backoffUntilMs = before.backoffUntilMs`. `before` is now unused *only if* nothing else reads it — check, and if so delete the `val before` line.
- The throw branch and the `when` still compile against the old scalars; rewrite both to write every table in the batch, which is exactly today's global behaviour expressed per table. In the throw branch:

```kotlin
            val failedTables = batch.map { it.table }.toSet()
            statusStore.update { fresh ->
                var next = fresh
                for (table in failedTables) {
                    val failures = (fresh.consecutiveFailuresByTable[table] ?: 0) + 1
                    next = next.copy(
                        consecutiveFailuresByTable = next.consecutiveFailuresByTable + (table to failures),
                        backoffUntilMsByTable = next.backoffUntilMsByTable +
                            (table to finishedAt + TelemetryGate.backoffDelayMs(failures))
                    )
                }
                next.copy(lastError = t::class.java.name, lastErrorAtMs = finishedAt)
            }
```

Keep the existing comments in that branch; they are still true.

- In the `when`, the `retryableFailure` arm applies the same loop over `batch.map { it.table }.toSet()`, and the success arm clears both maps entirely (`emptyMap()`) — which is what `backoffUntilMs = 0L` and `consecutiveFailures = 0` meant globally. Task 3 replaces this whole block; it exists here only so the suite stays green.

- [ ] **Step 14: Adapt the presenter and the ticker, behaviour unchanged**

In `AnalyticsPresenter.view`, add one local above the `return` and use it for both backoff fields:

```kotlin
        val effectiveBackoffUntilMs = status.effectiveBackoffUntilMs(nowMs)
```

```kotlin
            backingOff = effectiveBackoffUntilMs > nowMs,
            backoffRemainingSeconds =
                (effectiveBackoffUntilMs - nowMs).coerceAtLeast(0L).let { (it + 999) / 1000 },
            consecutiveFailures = status.consecutiveFailuresByTable.values.maxOrNull() ?: 0,
```

Leave `error` exactly as it is — Task 5 changes it, and changing it here would hide which task the behaviour came from.

In `MainActivity.kt`, `startAnalyticsBackoffTickerIfNeeded` reads the raw field twice (`:2001`, `:2008`). Both become the helper, each with the `now` it is comparing against:

```kotlin
        val backoffUntilMs = analyticsSnapshot?.status?.effectiveBackoffUntilMs(analyticsNowMs) ?: 0L
```

```kotlin
                val stillBackingOff =
                    (analyticsSnapshot?.status?.effectiveBackoffUntilMs(analyticsNowMs) ?: 0L) > analyticsNowMs
```

Missing either leaves the countdown frozen or the ticker never starting.

- [ ] **Step 15: Port the manager and presenter tests**

These read the removed scalars and must be updated, not deleted — each has a per-table equivalent.

- `TelemetryManagerTest.kt:146`, `:343`, `:465`: `status.backoffUntilMs > now` becomes `status.effectiveBackoffUntilMs(now) > now`.
- `:145`, `:168`, `:342`, `:383`, `:423`, `:466`, `:598`: `status.consecutiveFailures` becomes `status.consecutiveFailuresByTable.values.maxOrNull() ?: 0`.
- `:166`, `:214`, `:316-317`, `:550-551`, `:587`: writes that seed `consecutiveFailures = N, backoffUntilMs = X` become `consecutiveFailuresByTable = TelemetryTables.ALL.associateWith { N }, backoffUntilMsByTable = TelemetryTables.ALL.associateWith { X }`.
- `:223-226` seeds a backoff and asserts `flush()` returns `BACKING_OFF`. That path moves from the gate to the flush in Task 4; until then the flush no longer reports it. Mark this test `@Ignore("BACKING_OFF moves from the gate to the flush in Task 4")` with the annotation imported, and Task 4 un-ignores it. Do not delete it — it is the regression test for the behaviour Task 4 restores.
- `:324-325` asserts `status()` reports what the store holds; update both assertions to the map fields.
- `:524` constructs an `UploadOutcome(... retryableFailure = false ...)` — leave it, Task 2 changes that signature.
- `AnalyticsPresenterTest.kt`: any case seeding `backoffUntilMs` seeds `backoffUntilMsByTable = mapOf(TelemetryTables.DONATIONS to X)`; any seeding `consecutiveFailures` seeds `consecutiveFailuresByTable`.

- [ ] **Step 16: Build and run the whole suite**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: PASS, with exactly one `@Ignore`d test.

- [ ] **Step 17: Commit**

```bash
git add -A
git commit -m "Key backoff state by table"
```

---

## Task 2: The uploader reports which tables failed and which succeeded

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryUploader.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryManager.kt` (adapt to the new field)
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryUploaderTest.kt`, `TelemetryManagerTest.kt`

**Interfaces:**
- Consumes: `TelemetryTables.ALL` from Task 1.
- Produces: `UploadOutcome(uploadedIds: Set<String>, rejectedIds: Set<String>, retryableTables: Set<String>, succeededTables: Set<String>, lastError: String?)`.

- [ ] **Step 1: Write the failing tests**

Add to `TelemetryUploaderTest.kt`. Follow the file's existing helpers for building events and a fake poster rather than inventing new ones — read the top of the file first and reuse exactly what is there.

```kotlin
    @Test
    fun `a refused table is reported alone`() {
        // One donation row that succeeds, one diagnostic row that does not.
        // Assert: retryableTables holds only the diagnostics table, and
        // succeededTables holds only the donations table.
    }

    /**
     * Reachable in the per-row fallback: some rows upload and the network then
     * drops mid-sweep, setting retryable while uploadedHere is non-empty. The
     * manager resolves the overlap; the uploader must report it honestly rather
     * than picking a side here.
     */
    @Test
    fun `a table that partly uploaded and then failed appears in both sets`() {
        // A multi-row diagnostics batch, first row 201, second a transport
        // failure. Assert the diagnostics table is in retryableTables AND in
        // succeededTables.
    }

    @Test
    fun `succeededTables names a table whose rows were absorbed as already stored`() {
        // A single-row request answering 409 with a 23505 body counts as
        // uploaded, so its table counts as succeeded.
    }
```

Write each of these out in full against the file's real helpers — the comments above describe the setup, they do not replace it.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryUploaderTest*'`
Expected: FAIL — `retryableTables` and `succeededTables` are unresolved.

- [ ] **Step 3: Change `UploadOutcome`**

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

Extend the class KDoc with the overlap note:

```
 * A table can legitimately appear in both [retryableTables] and
 * [succeededTables] — the per-row fallback can upload some rows and then lose
 * the network. Which one wins is the caller's rule, not this class's.
```

- [ ] **Step 4: Populate them in `upload`**

Replace `var retryable = false` with `val retryableTables = mutableSetOf<String>()`, and add `val succeededTables = mutableSetOf<String>()`. Every existing `retryable = true` becomes `retryableTables += table` — all four sites, including both inside the per-row fallback. The empty-batch early return becomes `UploadOutcome(emptySet(), emptySet(), emptySet(), emptySet(), null)`.

At the end of each `for ((table, forTable) in ...)` iteration, record the success side once rather than at each of the three places a row can land in `uploaded`:

```kotlin
            if (forTable.any { it.id in uploaded }) succeededTables += table
```

and return `UploadOutcome(uploaded, rejected, retryableTables, succeededTables, lastError)`.

- [ ] **Step 5: Run the uploader tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryUploaderTest*'`
Expected: PASS.

- [ ] **Step 6: Adapt the manager, behaviour unchanged**

In `TelemetryManager.flush`, `outcome.retryableFailure` becomes `outcome.retryableTables.isNotEmpty()` — identical behaviour, since the boolean was true exactly when some table failed. Task 3 replaces this branch entirely. Fix the `UploadOutcome` construction in `TelemetryManagerTest.kt:524` to the new arity.

- [ ] **Step 7: Build and run the whole suite**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Report upload outcomes per table"
```

---

## Task 3: Per-table accounting in the flush

This is the behaviour change. Everything before it was shape.

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryManager.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryManagerTest.kt`

**Interfaces:**
- Consumes: `UploadOutcome.retryableTables` / `.succeededTables` (Task 2); `TelemetryStatus`'s maps and `effectiveBackoffUntilMs`, `TelemetryGate.isTableBackedOff` (Task 1).
- Produces: nothing new to later tasks.

- [ ] **Step 1: Write the failing tests**

Add to `TelemetryManagerTest.kt`, using the file's existing `manager(...)`, `outbox`, `poster` and `store` helpers:

```kotlin
    @Test
    fun `a refused diagnostics table leaves donations clear`() {
        // Batch with both tables; diagnostics 500, donations 201.
        // Assert: backoffUntilMsByTable has diagnostics only; donations absent
        // from both maps.
    }

    @Test
    fun `a success clears its table in the same flush where a sibling fails`() {
        // Seed both tables with failures and deadlines. Flush with donations
        // succeeding and diagnostics failing.
        // Assert: donations absent from both maps, diagnostics incremented.
    }

    /**
     * Failure wins. A table with rows that did not send is not healthy, and
     * treating it as healthy retries them on every flush — a tight loop against
     * a broken link.
     */
    @Test
    fun `a table in both sets backs off`() {
        // Drive an UploadOutcome through the `upload` seam with the diagnostics
        // table in retryableTables AND succeededTables.
        // Assert: diagnostics is backed off.
    }

    @Test
    fun `a throw backs off every table in the batch and no other`() {
        // Batch holds donations and diagnostics only; upload seam throws.
        // Assert: both are backed off, and the activations table is absent from
        // both maps.
    }

    @Test
    fun `per-table counts drive per-table delays independently`() {
        // Seed diagnostics with 4 consecutive failures and donations with 1,
        // fail both in one flush.
        // Assert: the diagnostics deadline is strictly further out than the
        // donations deadline.
    }

    @Test
    fun `activate clears every table`() {
        // Seed all three tables with failures and deadlines, then activate().
        // Assert: both maps are empty.
    }

    /**
     * A healthy donations table must not erase the schema error that explains
     * why diagnostics are stuck — it is the only evidence an operator has.
     */
    @Test
    fun `a sibling success does not clear lastError while a table is backed off`() {
        // Donations succeed, diagnostics fail with an error, in one flush.
        // Assert: lastError is the diagnostics error, not null.
    }

    @Test
    fun `lastError clears once no table is backed off`() {
        // Seed a lastError and a diagnostics deadline. Flush with everything
        // succeeding.
        // Assert: lastError is null and both maps are empty.
    }

    @Test
    fun `a flush that neither uploaded nor rejected anything leaves status alone`() {
        // Drive an all-empty UploadOutcome through the seam, with a seeded
        // lastError and a seeded deadline.
        // Assert: status is byte-identical to what was seeded.
    }
```

Write each out in full. The comments state the setup and the assertion; they do not stand in for code.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryManagerTest*'`
Expected: FAIL — global accounting backs off every table together.

- [ ] **Step 3: Factor one recording function**

Both the throw path and the outcome path record the same shape, so write it once. Add to `TelemetryManager`:

```kotlin
    /**
     * The one place per-table failure state is written.
     *
     * Shared by the throw path and the outcome path because they differ only in
     * where the two table sets come from — a transport-level throw has no table
     * attribution, so it names every table in the batch.
     */
    private fun recordOutcome(
        failedTables: Set<String>,
        succeededTables: Set<String>,
        errorText: String?,
        finishedAt: Long
    ) {
        // Nothing to record. Deliberately not folded into the loops below: with
        // both sets empty they would run zero times and then the lastError rule
        // would still evaluate, clearing an error no flush disproved. An outcome
        // that uploaded and rejected nothing is not evidence of anything.
        if (failedTables.isEmpty() && succeededTables.isEmpty()) return

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
            // clearing it here would retry them on the very next flush.
            for (table in succeededTables - failedTables) {
                counts.remove(table)
                deadlines.remove(table)
            }

            // Judged on the state this write produces, not the one it replaced:
            // that is the state the operator will actually be looking at.
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
                lastSuccessMs = if (succeededTables.isEmpty()) fresh.lastSuccessMs else finishedAt
            )
        }
    }
```

- [ ] **Step 4: Call it from both paths**

The throw branch's whole `statusStore.update { ... }` block becomes:

```kotlin
            recordOutcome(
                // A transport-level throw is table-agnostic, so it names the
                // tables actually attempted rather than all three.
                failedTables = batch.map { it.table }.toSet(),
                succeededTables = emptySet(),
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
            errorText = outcome.lastError,
            finishedAt = finishedAt
        )
```

Keep the `val finishedAt = clock()` line above it and its "read fresh" comment — the reason is unchanged.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryManagerTest*'`
Expected: PASS, except the still-`@Ignore`d `BACKING_OFF` case.

- [ ] **Step 6: Build and run the whole suite, then commit**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`

```bash
git add -A
git commit -m "Back off one table at a time"
```

---

## Task 4: Exclude backed-off tables inside the read

The Critical this phase exists to avoid. Filtering *after* `peek` would take the first hundred rows and then drop them, so a hundred backed-off rows at the head produce an empty batch forever and the rows behind them never send at all — strictly worse than the one-hour ceiling being fixed.

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryOutbox.kt:61-63`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryManager.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryOutboxTest.kt`, `TelemetryManagerTest.kt`

**Interfaces:**
- Consumes: `TelemetryStatus.backoffUntilMsByTable`, `TelemetryGate.isTableBackedOff` (Task 1).
- Produces: `TelemetryOutbox.peek(limit: Int = DEFAULT_BATCH, excludeTables: Set<String> = emptySet()): List<QueuedEvent>`.

- [ ] **Step 1: Write the failing outbox tests**

Add to `TelemetryOutboxTest.kt`, using its existing temp-file setup:

```kotlin
    /**
     * The defect this phase exists to avoid, and the reason exclusion lives
     * inside the read. Filtering a batch already truncated to its first `limit`
     * rows yields nothing at all while the head is backed off — and because
     * nothing is removed, the head never advances. A delay would have become
     * permanent starvation.
     *
     * Mutation check: move the filter after `take` and this test must fail.
     */
    @Test
    fun `excluded rows at the head do not crowd out the rows behind them`() {
        repeat(5) { outbox.append("d$it", TelemetryTables.DIAGNOSTICS, "{}") }
        outbox.append("donation", TelemetryTables.DONATIONS, "{}")

        val batch = outbox.peek(limit = 3, excludeTables = setOf(TelemetryTables.DIAGNOSTICS))

        assertEquals(listOf("donation"), batch.map { it.id })
    }

    @Test
    fun `excluded rows stay queued`() {
        outbox.append("d1", TelemetryTables.DIAGNOSTICS, "{}")
        outbox.peek(excludeTables = setOf(TelemetryTables.DIAGNOSTICS))
        assertEquals(1, outbox.size())
    }

    @Test
    fun `exclusion preserves queue order`() {
        outbox.append("a", TelemetryTables.DONATIONS, "{}")
        outbox.append("d", TelemetryTables.DIAGNOSTICS, "{}")
        outbox.append("b", TelemetryTables.DONATIONS, "{}")

        val batch = outbox.peek(excludeTables = setOf(TelemetryTables.DIAGNOSTICS))

        assertEquals(listOf("a", "b"), batch.map { it.id })
    }

    @Test
    fun `peek with no exclusions behaves exactly as before`() {
        outbox.append("a", TelemetryTables.DONATIONS, "{}")
        outbox.append("d", TelemetryTables.DIAGNOSTICS, "{}")
        assertEquals(listOf("a", "d"), outbox.peek().map { it.id })
    }
```

Adjust the `outbox` construction to whatever the file's existing fixture provides.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryOutboxTest*'`
Expected: FAIL — `peek` takes no `excludeTables`.

- [ ] **Step 3: Add the parameter**

```kotlin
    /**
     * The head of the queue, minus any row belonging to [excludeTables].
     *
     * Filtering happens **before** [limit] is applied, and that ordering is the
     * whole point: taking the first [limit] rows and then dropping the excluded
     * ones would return nothing at all while the head is backed off, and since
     * nothing is removed the head would never advance. Rows behind an excluded
     * run would stop sending entirely.
     *
     * Default-empty so every existing caller is unaffected. Queue order is
     * preserved; nothing here groups or sorts by table.
     */
    fun peek(limit: Int = DEFAULT_BATCH, excludeTables: Set<String> = emptySet()): List<QueuedEvent> =
        synchronized(lock) {
            readAll().asSequence()
                .filterNot { it.table in excludeTables }
                .take(limit)
                .toList()
        }
```

- [ ] **Step 4: Run the outbox tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*TelemetryOutboxTest*'`
Expected: PASS.

- [ ] **Step 5: Write the failing flush tests**

Remove the `@Ignore` from the `BACKING_OFF` case added in Task 1 Step 15, port it to seed `backoffUntilMsByTable` for every table, and add:

```kotlin
    @Test
    fun `a donation still uploads while diagnostics are backed off`() {
        // Seed a diagnostics deadline, queue diagnostics rows at the head and a
        // donation behind them, then flush.
        // Assert: the donation's id is in the uploaded set and the diagnostics
        // rows are still queued.
    }

    @Test
    fun `a batch emptied by exclusion reports BACKING_OFF`() {
        // Queue only diagnostics rows, seed a diagnostics deadline, flush.
        // Assert: FlushBlock.BACKING_OFF.
    }

    @Test
    fun `a genuinely empty queue still reports EMPTY_QUEUE`() {
        // Nothing queued, nothing backed off.
        // Assert: FlushBlock.EMPTY_QUEUE.
    }

    @Test
    fun `lastAttemptedIds holds only rows that were actually sent`() {
        // Queue both tables, back off diagnostics, flush, then activate() a row
        // behind the excluded ones — the existing tests around lastAttemptedIds
        // show the shape to follow.
    }
```

- [ ] **Step 6: Exclude in the flush**

In `TelemetryManager.flush`, after the gate check and the `cfg` line:

```kotlin
        val fresh = statusStore.read()
        val backedOffTables = fresh.backoffUntilMsByTable
            .filterValues { TelemetryGate.isTableBackedOff(it, now) }
            .keys

        val batch = outbox.peek(excludeTables = backedOffTables)
        // Recorded regardless of what happens next, so activate() can tell a row
        // this page never reached (FIX 3) apart from one it sent but could not
        // confirm. Automatically correct under exclusion: an excluded row was
        // never in the batch to begin with.
        lastAttemptedIds = batch.map { it.id }.toSet()
        if (batch.isEmpty()) {
            // The gate already reported EMPTY_QUEUE if the queue itself was
            // empty, so an empty batch here means exclusion emptied it — unless
            // the queue drained between the two reads, which is the guard below.
            // This costs one extra readAll on a flush that does no network work
            // at all.
            return if (backedOffTables.isEmpty()) FlushBlock.EMPTY_QUEUE else FlushBlock.BACKING_OFF
        }
```

If Task 1 Step 13 deleted `val before`, this `fresh` read replaces it; if it did not, reuse that value rather than reading twice.

- [ ] **Step 7: Run the tests, build, commit**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: PASS, no `@Ignore` remaining.

```bash
git add -A
git commit -m "Skip backed-off tables when taking a batch"
```

---

## Task 5: What the screen sees

Keeping the error in the store is not enough. The presenter suppresses a stale error with `takeIf { lastSuccessMs <= lastErrorAtMs }`, and now that a sibling's success advances `lastSuccessMs` past a retained `lastErrorAtMs`, the screen would render nothing beside a non-zero failure count. A store-level assertion passes while the screen is blank, so this task's test asserts on `AnalyticsView.error`.

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/AnalyticsPresenter.kt:111`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/AnalyticsPresenterTest.kt`

**Interfaces:**
- Consumes: `TelemetryStatus.effectiveBackoffUntilMs` (Task 1), the retained-`lastError` rule (Task 3).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```kotlin
    /**
     * The store keeps a live error while a table is backed off, but the screen
     * is what the operator reads — and "Failed attempts: 3" beside a blank error
     * line is the outcome the retention rule exists to prevent. Asserting on the
     * store instead would pass with the screen empty.
     */
    @Test
    fun `an error stays on screen while a table is backed off even after a sibling succeeds`() {
        val now = 10_000L
        val view = view(
            status = TelemetryStatus(
                lastError = "HTTP 400 bad column",
                lastErrorAtMs = now - 5_000,
                // The sibling's success, later than the error.
                lastSuccessMs = now - 1_000,
                backoffUntilMsByTable = mapOf(TelemetryTables.DIAGNOSTICS to now + 30_000)
            ),
            now = now
        )
        assertEquals("HTTP 400 bad column", view.error)
    }

    @Test
    fun `an error is suppressed once no table is backed off and a success followed it`() {
        val now = 10_000L
        val view = view(
            status = TelemetryStatus(
                lastError = "HTTP 400 bad column",
                lastErrorAtMs = now - 5_000,
                lastSuccessMs = now - 1_000
            ),
            now = now
        )
        assertNull(view.error)
    }

    @Test
    fun `a fresh error with no success since is still shown`() {
        val now = 10_000L
        val view = view(
            status = TelemetryStatus(
                lastError = "HTTP 503 down",
                lastErrorAtMs = now - 1_000,
                lastSuccessMs = now - 5_000
            ),
            now = now
        )
        assertEquals("HTTP 503 down", view.error)
    }
```

Use the file's existing `view(...)` helper at `:17`, extending its defaults if it does not already take a `status`.

- [ ] **Step 2: Run and watch the first test fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*AnalyticsPresenterTest*'`
Expected: the backed-off case FAILS with `expected:<HTTP 400 bad column> but was:<null>`.

- [ ] **Step 3: Change the rule**

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

- [ ] **Step 4: Run, build, commit**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`

```bash
git add -A
git commit -m "Keep a live error on screen while a table is backed off"
```

---

## Task 6: The carried items on this path

Five small changes that share the send path. Batched into one task because each is a contained edit with its own small test, and splitting them would buy five review seats for one diff.

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryRedactor.kt:33-40`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/DiagnosticEvents.kt:214`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/AnalyticsPresenter.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/screens/AnalyticsSettingsScreen.kt:290,427-428`
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt:607-609,1072-1079`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryManager.kt` (two comments)
- Modify: `app/src/main/java/com/sadaqah/kiosk/recovery/RestartManager.kt` (KDoc only)
- Test: `AnalyticsPresenterTest.kt`, `TelemetryRedactorTest.kt`, `DiagnosticEventsTest.kt`

**Interfaces:**
- Produces: `AnalyticsView.lastSuccessText: String`; `AnalyticsPresenter.view(..., zone: ZoneId, locale: Locale)`; `TelemetryRedactor.TRUNCATION_SUFFIX`.

- [ ] **Step 1: Clear the watchdog slot before each login launch**

In `MainActivity.authenticate`, immediately before `SumUpAPI.openLoginActivity(this@MainActivity, sumupLogin, 1)`:

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

Then trim the watchdog's long comment at `:1096-1106`: the paragraph beginning "What that ordering does not cover" describes the gap this line closes. Replace it with one sentence pointing at the clear above.

Not unit-testable — `MainActivity` is unreachable from JVM tests. Say so in the commit, not in a test that asserts nothing.

- [ ] **Step 2: One definition of the truncation suffix**

In `TelemetryRedactor`, promote the literal and drop the unused parameter:

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

In `DiagnosticEvents.kt:214`, delete the private `TRUNCATION_SUFFIX` and use `TelemetryRedactor.TRUNCATION_SUFFIX` at both `:233` and `:240`. The two truncation *functions* stay as they are — they cut different things for different reasons.

Check `TelemetryRedactorTest.kt` for any call passing an explicit `maxBytes`; rewrite such a case to build an oversized input against `MAX_TEXT_BYTES` rather than shrinking the limit.

- [ ] **Step 3: Write the failing presenter timestamp test**

```kotlin
    @Test
    fun `the last upload timestamp is formatted with the injected zone and locale`() {
        val view = view(
            status = TelemetryStatus(lastSuccessMs = 1_700_000_000_000L),
            now = 1_700_000_001_000L,
            zone = ZoneId.of("UTC"),
            locale = Locale.UK
        )
        assertEquals("14/11/2023, 22:13", view.lastSuccessText)
    }
```

Run it once to read the actual formatted string off the failure message, then pin that exact value — do not guess it. Reading platform defaults inside the presenter instead of taking parameters would make this test machine-dependent, which is the whole reason the formatting is moving.

- [ ] **Step 4: Move the formatting behind the presenter**

Add `val lastSuccessText: String` to `AnalyticsView`, beside `lastSuccessMs`. `view` takes two new required parameters — no defaults, since a default reading `ZoneId.systemDefault()` would put the machine dependence straight back:

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

Imports: `java.time.Instant`, `java.time.ZoneId`, `java.time.format.DateTimeFormatter`, `java.time.format.FormatStyle`, `java.util.Locale`. `java.time` is available unguarded at minSdk 30.

- [ ] **Step 5: Delete the screen's copy**

In `AnalyticsSettingsScreen.kt`, delete `formatTimestamp` at `:427-428` and its now-unused `DateFormat` / `Date` imports, and change `:290` to:

```kotlin
                            if (view.neverUploaded) strings.analyticsNeverUploaded else view.lastSuccessText,
```

In `MainActivity.kt:607-609`, pass the two new arguments:

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

Verify the screen holds no arithmetic or formatting left: `grep -nE "DateFormat|SimpleDateFormat|/ 1000|\* 1000" app/src/main/java/com/sadaqah/kiosk/screens/AnalyticsSettingsScreen.kt` must return nothing.

- [ ] **Step 6: Correct the two comments this phase made untrue**

`TelemetryManager.kt:91-92` and the block at `:107-115` describe backoff as one global deadline, and `:107-115` explains the `backoffUntilMs = 0L` line this phase deleted. Rewrite both to describe what `activate()` now does: it clears every table's failure state, so an operator who has just corrected the URL can retest immediately rather than waiting out a deadline. A comment describing removed code is worse than none.

Also check `MainActivity.kt:2055-2068` — the spec assigns that one to 3d-ii, so leave it.

- [ ] **Step 7: Keep the `RestartManager` getters and say why**

`cardReaderFailures` and `reinitFailures` were listed for removal on a wrong count: six tests read them and two assert counter *isolation*, which `RestartResult` alone cannot express. Add a KDoc so the next reader does not retry this:

```kotlin
    /** Read by tests only, and deliberately kept: two of them assert that a
     *  card-reader failure does not move the reinit count, which is an
     *  invariant RestartResult cannot express. */
```

- [ ] **Step 8: Build, run the whole suite, commit**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`

```bash
git add -A
git commit -m "Clear the login watchdog slot, and move timestamp formatting behind the presenter"
```

---

## Self-review against the spec

**Spec coverage.** `UploadOutcome` two sets → Task 2. Failure-wins → Task 3 Step 3. `TelemetryStatus` maps and `effectiveBackoffUntilMs` → Task 1. Exclusion inside `peek` → Task 4. `GateInputs.backoffUntilMs` removed, `isTableBackedOff` added → Task 1 Step 5. Global-field rules → Task 3 Step 3. Throw backs off the batch's tables → Task 3 Step 4. `PrefsStatusStore` per-key totality, key scheme, legacy migration → Task 1 Steps 8-12. `activate()` clears every table → Task 1 Step 13, tested in Task 3. Presenter aggregation → Task 1 Step 14; the `error` rule → Task 5. Ticker → Task 1 Step 14. Part 2's five items → Task 6. `RestartManager` getters kept → Task 6 Step 7. Every device check in the spec is manual and stays in the spec.

**Two places the plan goes beyond the spec, both recorded above:** the `StatusCodec` extraction (the spec asks for tests the current design cannot support), and `@Ignore`-then-restore on the one `BACKING_OFF` test that spans Tasks 1 and 4 (the alternative is deleting a regression test and hoping it gets rewritten).

**Type consistency.** `retryableTables` / `succeededTables`, `consecutiveFailuresByTable` / `backoffUntilMsByTable`, `effectiveBackoffUntilMs(nowMs)`, `isTableBackedOff(backoffUntilMs, nowMs)`, `peek(limit, excludeTables)`, `StatusCodec.decode` / `.encode` / `.keyFor`, `TelemetryTables.ALL`, `AnalyticsView.lastSuccessText`, `TelemetryRedactor.TRUNCATION_SUFFIX` — each is spelled the same in the task that defines it and every task that consumes it.

**Known incompleteness, by design.** Task 2's and Task 3's and Task 4's test bodies give setup and assertion in prose where the file's own fixtures decide the code. That is not a placeholder — the implementer reads the fixture and writes the test — but it is the one place this plan does not hand over finished code, and a reviewer should hold those tests to the same standard as the ones written out in full.
