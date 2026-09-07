# Telemetry Phase 3a Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A configured kiosk reports its donations to Supabase on its own, without anyone pressing anything.

**Architecture:** One pure unit decides whether a completed donation becomes a reportable event and what its cents are, so the untestable `MainActivity` call site decides nothing. Flushes are triggered at four moments the app already knows are safe — the screensaver coming up, a 30-minute retry while it stays up, network restored, and the 02:00 maintenance window — and every flush is serialised onto one dedicated thread because `TelemetryManager` keeps its last outcome in plain fields. One bounded-sweep fix in the uploader stops an unwatched flusher from firing 100 pointless requests per flush against a drifted schema.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4, Gson, `HttpURLConnection` via `UrlConnectionPoster`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-07-telemetry-phase-3a-donation-events-and-flush-scheduling.md`

## Global Constraints

- **No new dependencies.** Not WorkManager, not Robolectric, not a JSON library. The `app/build.gradle.kts` dependency block must be byte-identical at the end of this phase.
- **No new user-facing strings.** This phase adds nothing to `Translations.kt`. If you reach for a string, you have added operator-facing UI, which this phase deliberately has none of — stop and re-read the spec's "Nothing waits on a human".
- **The donation flow is sacred.** No telemetry work on the main thread, and no telemetry failure may reach the donor's screen or the thank-you flow.
- **The composable computes nothing.** No arithmetic, comparison, formatting, masking or validation inside `AnalyticsSettingsScreen.kt` or any `@Composable`. This phase does not touch that file at all.
- **Nothing waits on a human.** No dialog, badge, acknowledgement or operator action for any failure.
- **No secret and no error string reaches a log.** Log a `FlushBlock`, a throwable's `::class.java.name`, or a fixed message. Never `lastError`, never a response body, never a donation amount, never a key.
- **No pre-flight health check before a flush.** Rejected in the spec (Ruling BP): a ping cannot tell you whether an *insert* will be accepted, so it adds a round trip and a failure mode without removing one. `TelemetryGate` already refuses an offline attempt and backoff already bounds the retry rate. Do not add one, and do not "improve" the fallback by probing first.
- **Baseline before you start:** `assembleDebug` successful, **384** unit tests, 0 failures, working tree clean, on branch `telemetry/phase-3a` at commit `a5a4c82`.
- **Build command:** `./gradlew assembleDebug testDebugUnitTest`
- **Test count command:** `grep -ho 'tests="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | awk -F'"' '{a[$1]+=$2} END {for(k in a) print k, a[k]}'`
- **Line numbers in this plan are from `a5a4c82` and drift as you edit.** Anchor on the quoted code, not the number.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/com/sadaqah/kiosk/telemetry/DonationEvents.kt` | **create** | Pure: does this donation get reported, and what are its cents? |
| `app/src/test/java/com/sadaqah/kiosk/telemetry/DonationEventsTest.kt` | **create** | JVM tests for the above. |
| `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryEvent.kt` | modify | `EventIdentity` gains a companion factory, so identity has one construction site. |
| `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryUploader.kt` | modify | The per-row fallback stops after 10 refusals with no success. |
| `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryUploaderTest.kt` | modify | Five tests for the cap, including one pinning its cost. |
| `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryStatusStore.kt` | modify | `droppedCount`'s KDoc broadens to "lost locally and unrecoverable". |
| `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt` | modify | The donation call site, the loss counter, the flush dispatcher, and the four triggers. |

**Task ordering is forced by one file.** Tasks 3 and 4 both modify `MainActivity.kt` and must run sequentially, in that order — Task 4's `flushTelemetry` sits beside Task 3's `appendDonationTelemetry`, and Task 4 changes a line Task 3 does not touch. Tasks 1 and 2 are genuinely independent of each other and of 3/4, but Task 3 consumes Task 1's `DonationEvents` and `EventIdentity.from`, so run them in numerical order.

---

## Task 1: `DonationEvents` — the pure report decision

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/DonationEvents.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryEvent.kt:18-22` (`EventIdentity`)
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/DonationEventsTest.kt` (create)

**Interfaces:**
- Consumes: `TelemetryEvent.Donation(identity, amountCents, currency, occurredAtIso, id)` and `EventIdentity(code, installId, appVersion)`, both already in `TelemetryEvent.kt`. `com.sadaqah.kiosk.model.Settings` fields `analyticsEnabled: Boolean`, `kioskCode: String`, `installId: String`, `currency: String`.
- Produces, relied on by Task 3:
  - `DonationEvents.eventFor(settings: Settings, appVersion: String, amount: BigDecimal, occurredAtMs: Long): DonationEventResult`
  - `sealed class DonationEventResult` with `data class Report(val event: TelemetryEvent.Donation)`, `object NotEnabled`, `object AmountUnrepresentable`
  - `EventIdentity.from(settings: Settings, appVersion: String): EventIdentity`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/DonationEventsTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DonationEventsTest {

    private val appVersion = "1.3.6-preview"

    /** 2023-11-14T22:13:20Z exactly, so the ISO rendering has no fraction. */
    private val atMs = 1_700_000_000_000L

    private val enabled = Settings(
        analyticsEnabled = true,
        kioskCode = "nl-gld-arnhem-nour_al_houda-01",
        installId = "11111111-2222-3333-4444-555555555555",
        currency = "EUR"
    )

    private fun result(amount: String, settings: Settings = enabled) =
        DonationEvents.eventFor(settings, appVersion, BigDecimal(amount), atMs)

    private fun cents(amount: String): Int {
        val r = result(amount)
        assertTrue("expected a Report for $amount, got $r", r is DonationEventResult.Report)
        return (r as DonationEventResult.Report).event.amountCents
    }

    // ── The gate ─────────────────────────────────────────────────────────────

    /**
     * The whole reason this function exists. MainActivity cannot be unit-tested,
     * so an `if (settings.analyticsEnabled)` at the call site is a decision
     * nothing checks — which is how phase 2b shipped a mint inside an
     * unreachable branch.
     *
     * Asserting the exact result, not merely "not a Report": NotEnabled and
     * AmountUnrepresentable must stay distinguishable, because the caller
     * records a loss for one and does nothing for the other. A shared nullable
     * return would make every donation on a disabled kiosk a recorded loss.
     * Do not add a separate `assertNotEquals(AmountUnrepresentable, …)` test —
     * this assertion already implies it, and `DonationEventResult` having three
     * distinct members means collapsing them does not even compile.
     */
    @Test
    fun analyticsOffProducesNotEnabled() {
        assertEquals(
            DonationEventResult.NotEnabled,
            result("10.00", Settings())
        )
    }

    @Test
    fun anEnabledKioskProducesAReport() {
        assertTrue(result("10.00") is DonationEventResult.Report)
    }

    // ── The identity and the payload ─────────────────────────────────────────

    @Test
    fun theReportCarriesTheKioskIdentityAndCurrency() {
        val event = (result("10.00") as DonationEventResult.Report).event
        assertEquals("nl-gld-arnhem-nour_al_houda-01", event.identity.code)
        assertEquals("11111111-2222-3333-4444-555555555555", event.identity.installId)
        assertEquals("1.3.6-preview", event.identity.appVersion)
        assertEquals("EUR", event.currency)
        assertEquals(TelemetryTables.DONATIONS, event.table)
    }

    @Test
    fun theOccurredAtIsTheIsoInstantOfThePassedClock() {
        val event = (result("10.00") as DonationEventResult.Report).event
        assertEquals("2023-11-14T22:13:20Z", event.occurredAtIso)
    }

    @Test
    fun thePayloadRendersCentsAndCurrencyForTheWire() {
        val event = (result("12.34") as DonationEventResult.Report).event
        val row = JsonParser.parseString(event.payloadJson()).asJsonObject
        assertEquals(1234, row.get("amount_cents").asInt)
        assertEquals("EUR", row.get("currency").asString)
        assertEquals("2023-11-14T22:13:20Z", row.get("occurred_at").asString)
    }

    @Test
    fun eventIdentityFromReadsTheKioskCodeInstallIdAndPassedAppVersion() {
        val identity = EventIdentity.from(enabled, "9.9.9")
        assertEquals("nl-gld-arnhem-nour_al_houda-01", identity.code)
        assertEquals("11111111-2222-3333-4444-555555555555", identity.installId)
        assertEquals("9.9.9", identity.appVersion)
    }

    // ── Cents ────────────────────────────────────────────────────────────────

    @Test
    fun aWholeNumberAmountBecomesCents() {
        assertEquals(1000, cents("10"))
    }

    @Test
    fun aTwoDecimalAmountBecomesExactCents() {
        assertEquals(1234, cents("12.34"))
    }

    @Test
    fun aOneDecimalAmountBecomesExactCents() {
        assertEquals(1250, cents("12.5"))
    }

    /**
     * Scale 3 can only come from a malformed stored amount, and is rounded
     * rather than rejected: a donation that really happened should be reported
     * approximately rather than not at all. HALF_UP, pinned in both directions
     * so a switch to truncation fails here rather than passing silently.
     */
    @Test
    fun aScaleThreeAmountAtTheBoundaryRoundsUp() {
        assertEquals(1001, cents("10.005"))
    }

    @Test
    fun aScaleThreeAmountBelowTheBoundaryRoundsDown() {
        assertEquals(1000, cents("10.004"))
    }

    @Test
    fun zeroIsReportedAsZeroCents() {
        assertEquals(0, cents("0"))
    }

    /**
     * A negative successful payment is nonsense, but DonationHistory already
     * stores whatever it is handed, and a negative on the dashboard is the
     * anomaly worth seeing rather than hiding.
     */
    @Test
    fun aNegativeAmountIsReportedRatherThanDropped() {
        assertEquals(-1000, cents("-10.00"))
    }

    @Test
    fun anAmountBeyondIntCentsIsUnrepresentable() {
        assertEquals(
            DonationEventResult.AmountUnrepresentable,
            result("100000000000")
        )
    }

    @Test
    fun theLargestRepresentableAmountIsStillReported() {
        // Int.MAX_VALUE cents = 21474836.47
        assertEquals(Int.MAX_VALUE, cents("21474836.47"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*DonationEventsTest*"`
Expected: FAIL — compilation error, `Unresolved reference: DonationEvents` and `Unresolved reference: from`.

- [ ] **Step 3: Add the `EventIdentity` companion factory**

In `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryEvent.kt`, add the import beside the existing ones at the top of the file:

```kotlin
import com.sadaqah.kiosk.model.Settings
```

Then replace:

```kotlin
data class EventIdentity(
    val code: String,
    val installId: String,
    val appVersion: String
)
```

with:

```kotlin
data class EventIdentity(
    val code: String,
    val installId: String,
    val appVersion: String
) {
    companion object {
        /** One construction site, so the manager's runtime snapshot and a
         *  donation event can never disagree about who this kiosk is. Before
         *  this existed, `MainActivity`'s `runtime` lambda built the only copy;
         *  a second one in [DonationEvents] would have drifted the first time
         *  identity sourcing changed. */
        fun from(settings: Settings, appVersion: String) = EventIdentity(
            code = settings.kioskCode,
            installId = settings.installId,
            appVersion = appVersion
        )
    }
}
```

- [ ] **Step 4: Create `DonationEvents.kt`**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/DonationEvents.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Why a completed donation did or did not become a reportable event.
 *
 * A sealed result rather than a nullable event, because the two non-reporting
 * cases demand different behaviour from the caller: [NotEnabled] is the normal
 * state of most kiosks and means *do nothing*, while [AmountUnrepresentable] is
 * a donation that will never be reported and must be logged and counted. A
 * shared `null` would make a disabled kiosk record a data loss on every
 * donation it takes.
 */
sealed class DonationEventResult {
    data class Report(val event: TelemetryEvent.Donation) : DonationEventResult()
    object NotEnabled : DonationEventResult()
    object AmountUnrepresentable : DonationEventResult()
}

/**
 * Decides whether a completed donation is reported, and in what shape.
 *
 * Pure, and that is the point: its caller is on the donation path inside
 * `MainActivity`, which no unit test can reach, so every decision — including
 * whether to report at all — lives here where it can be exercised. Phase 2b
 * shipped a bug for exactly the opposite reason: a mint that sat inside an
 * unreachable branch of `MainActivity`.
 *
 * [appVersion] is a parameter rather than a `BuildConfig` read so this file has
 * no Android dependency.
 */
object DonationEvents {

    fun eventFor(
        settings: Settings,
        appVersion: String,
        amount: BigDecimal,
        occurredAtMs: Long
    ): DonationEventResult {
        // The master switch, checked here rather than at the call site. A kiosk
        // with analytics off writes nothing to disk at all — not "writes and
        // declines to send" — so no identified donation row is ever stored for a
        // purpose the operator declined.
        if (!settings.analyticsEnabled) return DonationEventResult.NotEnabled

        val cents = try {
            // HALF_UP because currency is conventionally rounded that way. The
            // numpad produces at most two decimals, so rounding is very nearly
            // unreachable — which is why it is pinned by a test rather than
            // left to whatever the default happens to be.
            amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact()
        } catch (_: ArithmeticException) {
            // Beyond Int cents (about 21.5 million). Unreachable through the
            // UI; defined so this function has no undefined behaviour.
            return DonationEventResult.AmountUnrepresentable
        }

        return DonationEventResult.Report(
            TelemetryEvent.Donation(
                identity = EventIdentity.from(settings, appVersion),
                amountCents = cents,
                currency = settings.currency,
                occurredAtIso = Instant.ofEpochMilli(occurredAtMs).toString()
            )
        )
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*DonationEventsTest*"`
Expected: PASS, 15 tests.

- [ ] **Step 6: Run the full suite and build**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, **399** tests (384 + 15), 0 failures. Report the number you actually see.

- [ ] **Step 7: Mutation check (i) — the gate is load-bearing**

Temporarily delete the `if (!settings.analyticsEnabled) return DonationEventResult.NotEnabled` line.
Run: `./gradlew testDebugUnitTest --tests "*DonationEventsTest*"`
Expected: FAIL at `analyticsOffProducesNotEnabled`.
**Restore the line.** Record the failure message in your report.

This check also covers the spec's fourth mandated mutation — returning
`AmountUnrepresentable` instead of `NotEnabled` for a disabled kiosk fails this same
test, and collapsing the two into one member does not compile, because
`DonationEventResult` declares them as distinct types. There is no separate check for it.

- [ ] **Step 8: Mutation check (ii) — the rounding mode is load-bearing**

Temporarily change `RoundingMode.HALF_UP` to `RoundingMode.DOWN`.
Run: `./gradlew testDebugUnitTest --tests "*DonationEventsTest*"`
Expected: FAIL at `aScaleThreeAmountAtTheBoundaryRoundsUp` (1000 instead of 1001), while `aScaleThreeAmountBelowTheBoundaryRoundsDown` still passes — which is what proves both boundary cases are genuinely doing work rather than one covering for the other.
**Restore `HALF_UP`.** Record both outcomes.

- [ ] **Step 9: Mutation check (iii) — overflow is not silently reported**

Temporarily change `.intValueExact()` to `.toInt()` (which truncates instead of throwing).
Run: `./gradlew testDebugUnitTest --tests "*DonationEventsTest*"`
Expected: FAIL at `anAmountBeyondIntCentsIsUnrepresentable`.
**Restore `.intValueExact()`.** Record the failure.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/DonationEvents.kt \
        app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryEvent.kt \
        app/src/test/java/com/sadaqah/kiosk/telemetry/DonationEventsTest.kt
git commit -m "Decide in a testable place whether a donation gets reported"
```

---

## Task 2: Bound the per-row fallback

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryUploader.kt:85-112`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryUploaderTest.kt` (append)

**Interfaces:**
- Consumes: `HttpResponse(code, body)`, `HttpResponse.isSuccess`, `.isPermanentRejection`, `.isAlreadyStored`, `HttpResponse.TRANSPORT_FAILURE`; `UploadOutcome(uploadedIds, rejectedIds, retryableFailure, lastError)`; the test file's existing `RecordingPoster`, `event(id, table)` and `uploader(poster)` helpers.
- Produces: no new public API. Behaviour change only.

**Read before editing:** `TelemetryUploader.upload`'s KDoc and the comment block above the fallback branch. The invariant that a row lands in `rejectedIds` **only** when a sibling succeeded in the same flush is the most carefully reasoned rule in this feature. This task must not weaken it — and does not, because the cap can only fire while nothing has succeeded, which routes it into the existing "report nothing rejected" path.

- [ ] **Step 1: Write the failing tests**

Append these to `TelemetryUploaderTest.kt`, inside the class, before the closing brace:

```kotlin
    // ── The fallback's sweep is bounded (phase 3a) ───────────────────────────

    /** Thirty rows, e01…e30, so a full sweep would be visibly different from a
     *  capped one. */
    private fun thirtyRows() = (1..30).map { event("e%02d".format(it)) }

    private fun isBatchBody(body: String) =
        JsonParser.parseString(body).asJsonArray.size() > 1

    /**
     * Schema drift refuses every row in the fleet. The fallback exists to test
     * one hypothesis — one bad row among many — and uniform refusal refutes it,
     * so the remaining rows teach nothing. Before this cap an unwatched flusher
     * fired 1 + 100 requests per flush, at every trigger, indefinitely.
     */
    @Test
    fun aFallbackRefusingEveryRowStopsAfterTenRequests() {
        val poster = RecordingPoster { _, _ -> HttpResponse(400, "PGRST204 column not in schema cache") }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals("one batch attempt plus ten single rows", 11, poster.calls.size)
        assertTrue("no row may be deleted on a fleet-wide refusal", outcome.rejectedIds.isEmpty())
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue("the batch must stay queued for the next flush", outcome.retryableFailure)
    }

    /**
     * The accepted cost of the cap, pinned rather than pretended away: bad rows
     * at the head of the queue block the good rows behind them until the
     * outbox's 30-day age cap retires them. Ten leaves margin for a scattered
     * handful, and uniform refusal is far likelier than a clustered few because
     * payloads are generated by our own code with a fixed shape.
     */
    @Test
    fun theCapCanHideAGoodRowSittingBehindTenBadOnes() {
        val poster = RecordingPoster { _, body ->
            when {
                isBatchBody(body) -> HttpResponse(400, "invalid input")
                body.contains("\"e11\"") -> HttpResponse(201, null)
                else -> HttpResponse(400, "invalid input")
            }
        }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals(11, poster.calls.size)
        assertTrue("e11 is never reached, and that is the documented cost", outcome.uploadedIds.isEmpty())
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }

    /**
     * Once a row lands, the hypothesis is confirmed and every later refusal is
     * corroborated — removing it drains the queue, so those requests are
     * productive and the sweep must run to completion.
     */
    @Test
    fun aSuccessOnTheFirstFallbackRowLetsTheSweepRunToCompletion() {
        val poster = RecordingPoster { _, body ->
            when {
                isBatchBody(body) -> HttpResponse(400, "invalid input")
                body.contains("\"e01\"") -> HttpResponse(201, null)
                else -> HttpResponse(400, "invalid input")
            }
        }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals("one batch attempt plus all thirty single rows", 31, poster.calls.size)
        assertEquals(setOf("e01"), outcome.uploadedIds)
        assertEquals(29, outcome.rejectedIds.size)
        assertFalse("a corroborated refusal is permanent, not retryable", outcome.retryableFailure)
    }

    /**
     * The counter must stop mattering the moment anything succeeds, not merely
     * be reset. Four refusals then a success then twenty-five more refusals:
     * a counter that kept incrementing would stop at ten and strand fifteen
     * permanently-refused rows in the queue forever.
     */
    @Test
    fun aSuccessPartWayThroughStopsTheCapFromEverFiring() {
        val poster = RecordingPoster { _, body ->
            when {
                isBatchBody(body) -> HttpResponse(400, "invalid input")
                body.contains("\"e05\"") -> HttpResponse(201, null)
                else -> HttpResponse(400, "invalid input")
            }
        }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals(31, poster.calls.size)
        assertEquals(setOf("e05"), outcome.uploadedIds)
        assertEquals(29, outcome.rejectedIds.size)
    }

    /**
     * The pre-existing `break` on a transport failure must still win outright,
     * ahead of the cap: every remaining row would burn a full connect-plus-read
     * timeout and stay queued regardless.
     */
    @Test
    fun aTransportFailureMidFallbackStopsBeforeTheCapEverCounts() {
        val poster = RecordingPoster { _, body ->
            if (isBatchBody(body)) HttpResponse(400, "invalid input")
            else HttpResponse(HttpResponse.TRANSPORT_FAILURE, null)
        }
        val outcome = uploader(poster).upload(thirtyRows())

        assertEquals("one batch attempt plus exactly one row", 2, poster.calls.size)
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "*TelemetryUploaderTest*"`
Expected: FAIL. `aFallbackRefusingEveryRowStopsAfterTenRequests` reports 31 calls instead of 11, and `theCapCanHideAGoodRowSittingBehindTenBadOnes` finds `e11` uploaded and 29 rows rejected. The other three should already pass — they describe existing behaviour and exist to stop the cap from breaking it.

- [ ] **Step 3: Add the cap**

In `TelemetryUploader.kt`, in the `(response.isPermanentRejection || response.isAlreadyStored) && forTable.size > 1` branch, replace:

```kotlin
                    val uploadedHere = mutableSetOf<String>()
                    val rejectedHere = mutableSetOf<String>()
                    for (event in forTable) {
                        val single = send(table, listOf(event))
                        when {
                            single.isSuccess || single.isAlreadyStored -> uploadedHere += event.id
                            single.isPermanentRejection -> {
                                rejectedHere += event.id
                                lastError = describe(single)
                            }
```

with:

```kotlin
                    val uploadedHere = mutableSetOf<String>()
                    val rejectedHere = mutableSetOf<String>()
                    // Refusals seen while nothing has landed yet. This fallback
                    // exists to test one hypothesis — one bad row among many —
                    // and a run of refusals with no success refutes it, so the
                    // remaining rows have nothing left to teach. Left unbounded
                    // it costs a request per row (up to DEFAULT_BATCH = 100) on
                    // every flush, forever, against a drifted schema — which
                    // mattered less when a human pressed the button and watched
                    // it fail, and matters now that a timer presses it unwatched.
                    //
                    // Counting deliberately STOPS at the first success rather
                    // than resetting: from then on the hypothesis is confirmed
                    // and every further refusal is corroborated, so removing it
                    // drains the queue and the sweep is worth finishing.
                    var refusalsWithoutSuccess = 0
                    for (event in forTable) {
                        val single = send(table, listOf(event))
                        when {
                            single.isSuccess || single.isAlreadyStored -> uploadedHere += event.id
                            single.isPermanentRejection -> {
                                rejectedHere += event.id
                                lastError = describe(single)
                                // Nothing is deleted by a capped sweep: the cap
                                // can only fire while uploadedHere is empty, so
                                // breaking out lands in the isEmpty() branch
                                // below, which discards rejectedHere and keeps
                                // every row. The only new behaviour is stopping.
                                if (uploadedHere.isEmpty() &&
                                    ++refusalsWithoutSuccess >= MAX_FALLBACK_REFUSALS_WITHOUT_SUCCESS
                                ) break
                            }
```

Then add a companion object immediately before `TelemetryUploader`'s closing brace, after the `describe` function:

```kotlin
    companion object {
        /** How many individual refusals the per-row fallback absorbs before
         *  giving up, while nothing has yet succeeded.
         *
         *  Ten, not three: the cap's cost is a stall — genuinely bad rows at the
         *  head of the queue block the rows behind them until the outbox's
         *  30-day age cap retires them — and ten leaves margin for a scattered
         *  handful while still cutting a uniform sweep by 90%. A stall is also
         *  the visible, reversible direction: consecutiveFailures and a
         *  lastError reading "HTTP 400 …" are on the analytics screen, and that
         *  is the diagnostic that leads someone to the schema. */
        private const val MAX_FALLBACK_REFUSALS_WITHOUT_SUCCESS = 10
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*TelemetryUploaderTest*"`
Expected: PASS, all pre-existing uploader tests included. If `aRejectedBatchIsRetriedRowByRow`, `aFallbackThatRefusesEveryRowReportsNothingRejected` or `oneRowGettingThroughIsEnoughToHonourAnotherRowsRejection` now fails, you have changed behaviour for batches of 3 — the cap must not fire below 10 refusals.

- [ ] **Step 5: Run the full suite and build**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, **404** tests (399 + 5), 0 failures.

- [ ] **Step 6: Mutation check (iv) — counting must stop at the first success**

Temporarily change the guard from `if (uploadedHere.isEmpty() && ++refusalsWithoutSuccess >= …)` to `if (++refusalsWithoutSuccess >= …)`.
Run: `./gradlew testDebugUnitTest --tests "*TelemetryUploaderTest*"`
Expected: FAIL at `aSuccessOnTheFirstFallbackRowLetsTheSweepRunToCompletion` (12 calls, 10 rejected) **and** at `aSuccessPartWayThroughStopsTheCapFromEverFiring`. That is the trap this fix has: a counter that keeps running strands permanently-refused rows in the queue forever.
**Restore the guard.** Record both failures.

- [ ] **Step 7: Mutation check (v) — the cap is load-bearing**

Temporarily change `MAX_FALLBACK_REFUSALS_WITHOUT_SUCCESS` to `1000`.
Run: `./gradlew testDebugUnitTest --tests "*TelemetryUploaderTest*"`
Expected: FAIL at `aFallbackRefusingEveryRowStopsAfterTenRequests` with 31 calls.
**Restore `10`.** Record the failure.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryUploader.kt \
        app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryUploaderTest.kt
git commit -m "Stop the per-row fallback sweeping a hundred rows a backend refuses"
```

---

## Task 3: Append the donation, and count what is lost

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt` — the payment-success branch (~line 621-625), the `runtime` lambda (~line 146-158), and two new private methods
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryStatusStore.kt:24-32` (`droppedCount` KDoc)
- Test: none. Every decision worth testing is in Task 1; what remains here is wiring, and `MainActivity` cannot be unit-tested. See "Deliberately untested" below.

**Interfaces:**
- Consumes from Task 1: `DonationEvents.eventFor`, `DonationEventResult.{Report, NotEnabled, AmountUnrepresentable}`, `EventIdentity.from`.
- Consumes existing: `telemetryOutbox: TelemetryOutbox` (`append(id, table, payload)`), `telemetryStatusStore: TelemetryStatusStore` (`update {}`), `settings: Settings`, `BuildConfig.VERSION_NAME`, `lifecycleScope`, `Dispatchers.IO`.
- Produces, relied on by nothing later: `appendDonationTelemetry(amount: BigDecimal)`, `recordTelemetryLoss()`.

**Deliberately untested, and say so in your report:** `appendDonationTelemetry`'s catch-all and its `droppedCount` bump have no JVM test. They need `SharedPreferences` and a real filesystem, and Robolectric would breach the no-new-dependencies constraint. Same reasoning as Ruling AN. They go on the device-check list in Task 4, Step 10.

- [ ] **Step 1: Broaden `droppedCount`'s KDoc**

In `TelemetryStatusStore.kt`, replace:

```kotlin
    /** Rows the outbox's count/age caps discarded outright — never sent, never
     *  recoverable. Distinct from a retryable failure: this is loss, not a
     *  pending retry.
```

with:

```kotlin
    /** Rows lost locally and unrecoverable — never sent, never recoverable.
     *  Two sources, deliberately one number: the outbox's count/age caps
     *  discarding rows outright, and a donation that could not be appended at
     *  all (a full disk, a failed mkdirs, an amount beyond Int cents). For
     *  anyone who eventually reads the screen these are the same fact — N
     *  events never made it — and splitting them would buy a second field and
     *  a second string in eight languages to say it twice.
     *
     *  Distinct from a retryable failure: this is loss, not a pending retry.
```

Leave the rest of that KDoc — the "Displayed but never acknowledged" paragraph and the `TelemetryTeardown` sentence — exactly as it is.

- [ ] **Step 2: Route the runtime lambda through the new factory**

In `MainActivity.kt`, inside the `telemetryManager by lazy` block, replace:

```kotlin
                    identity = EventIdentity(
                        code = settings.kioskCode,
                        installId = settings.installId,
                        appVersion = BuildConfig.VERSION_NAME
                    ),
```

with:

```kotlin
                    identity = EventIdentity.from(settings, BuildConfig.VERSION_NAME),
```

- [ ] **Step 3: Add the two private methods**

In `MainActivity.kt`, immediately after the `onAnalyticsClearCredentials` function and before the `// ── Update flow entry points (called from UI) ───` comment, add:

```kotlin
    /**
     * Queues one completed donation for telemetry, off the main thread, and
     * never fails the donation flow.
     *
     * Every decision lives in [DonationEvents.eventFor] — including whether to
     * report at all — because this method cannot be unit-tested and a decision
     * here is a decision nothing checks.
     *
     * `settings` is read on the main thread and captured before the launch:
     * it is Compose state, and reading it from a background dispatcher would be
     * a cross-thread read of a `mutableStateOf`.
     *
     * Deliberately NOT on `telemetryFlushDispatcher`: [TelemetryOutbox] is
     * synchronized per file path so a concurrent append is already safe, and a
     * flush holds that lock only across `peek` and `remove`, never across the
     * upload. Sharing the flush thread would park this row behind an upload
     * that can run for minutes in the per-row fallback, for no benefit. A row
     * appended between a flush's `peek` and `remove` is harmless — `remove`
     * deletes only the ids the uploader named.
     */
    private fun appendDonationTelemetry(amount: BigDecimal) {
        val settingsNow = settings
        val occurredAtMs = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            when (val result = DonationEvents.eventFor(
                settingsNow, BuildConfig.VERSION_NAME, amount, occurredAtMs
            )) {
                // The normal state of most kiosks. Nothing is written to disk.
                DonationEventResult.NotEnabled -> Unit

                DonationEventResult.AmountUnrepresentable -> {
                    // The scale, never the value: a log line is not a redacted
                    // sink, and the amount is donor-adjacent data.
                    Log.e("Telemetry", "donation not representable in cents (scale=${amount.scale()})")
                    recordTelemetryLoss()
                }

                is DonationEventResult.Report -> try {
                    telemetryOutbox.append(
                        result.event.id,
                        result.event.table,
                        result.event.payloadJson()
                    )
                } catch (t: Throwable) {
                    // TelemetryOutbox.append documents that it throws on a full
                    // disk or a failed mkdirs, that the caller owns that
                    // decision, and that the caller sits on the donation path.
                    // This is that caller, so the only correct decision is to
                    // lose the row quietly and record that it happened.
                    Log.e("Telemetry", "outbox append failed: ${t::class.java.name}")
                    recordTelemetryLoss()
                }
            }
        }
    }

    /**
     * One event lost locally and unrecoverably. Folded into
     * [TelemetryStatus.droppedCount] rather than a field of its own: for anyone
     * who eventually reads the screen it is the same fact as an eviction, and
     * this kiosk can run unattended for weeks, so nothing here waits on a human
     * to see or clear it.
     *
     * Through `update {}` rather than a read-then-write, because this runs on a
     * background thread and races the flush's own status writes, which straddle
     * a network call that can run for minutes (finding I4, Ruling BA).
     */
    private fun recordTelemetryLoss() {
        telemetryStatusStore.update { it.copy(droppedCount = it.droppedCount + 1) }
    }
```

- [ ] **Step 4: Add the call at the payment-success site**

In `MainActivity.onActivityResult`, request `3`, in the `if (resultCode == 1 && data != null)` branch, replace:

```kotlin
                    // Append to donation history if tracking is on.
                    if (settings.donationTrackingEnabled) {
                        lastPaymentAmount?.let { donationHistory.append(it) }
                    }
                    lastPaymentAmount = null
```

with:

```kotlin
                    // Append to donation history if tracking is on.
                    if (settings.donationTrackingEnabled) {
                        lastPaymentAmount?.let { donationHistory.append(it) }
                    }
                    // A sibling of the line above, never nested inside it:
                    // donationTrackingEnabled governs the on-panel history
                    // screen and nothing else. Tying telemetry to it would mean
                    // an operator who hides the local history for privacy at the
                    // panel silently stops all remote reporting too. Telemetry
                    // has its own master switch, checked inside eventFor.
                    lastPaymentAmount?.let { appendDonationTelemetry(it) }
                    lastPaymentAmount = null
```

- [ ] **Step 5: Build and run the full suite**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, **404** tests, 0 failures — this task adds no tests and must break none.

- [ ] **Step 6: Verify the append is off the main thread and the amount never reaches a log**

Run:
```bash
grep -n "appendDonationTelemetry" app/src/main/java/com/sadaqah/kiosk/MainActivity.kt
grep -n "Log.e(\"Telemetry\"" app/src/main/java/com/sadaqah/kiosk/MainActivity.kt
```
Expected: `appendDonationTelemetry` appears exactly twice — its definition and the one call site. Both `Log.e` lines interpolate only `amount.scale()` and `t::class.java.name`; neither contains `$amount`, `$result`, `lastError`, or a payload. Confirm by reading the two lines, not by assuming.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/MainActivity.kt \
        app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryStatusStore.kt
git commit -m "Report the donation, and count the ones the disk refuses"
```

---

## Task 4: The flush dispatcher and the four triggers

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt` — imports, a file-scope constant, two fields, two new methods, `onCreate`, `onDestroy`, `onAnalyticsTestConnection`, and five call sites
- Test: none — see "Deliberately untested" below.

**Interfaces:**
- Consumes existing: `telemetryManager.flush(): FlushBlock`, `telemetryManager.activate(): ActivationResult`, `refreshAnalyticsSnapshot(then: (() -> Unit)? = null)`, `isScreensaverActive: Boolean`, `lifecycleScope`, `Job`.
- Produces: `flushTelemetry(reason: String)`, `startTelemetryFlushTicker()`, `telemetryFlushDispatcher`.

**Deliberately untested, and say so in your report:** the five trigger sites and the single-thread dispatcher have no JVM test. They live in `MainActivity` and depend on the Android lifecycle. The serialisation they provide is a property of the dispatcher rather than logic, so there is nothing pure to extract. Device checks are Step 10.

- [ ] **Step 1: Add the imports and the tick constant**

In `MainActivity.kt`, add to the import block:

```kotlin
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
```

And beside the existing `INACTIVITY_BOUNCE_MS` file-scope constant, add:

```kotlin
/** How often a flush is retried while the screensaver stays up. The screensaver
 *  coming up already flushes once; this exists because that attempt can be
 *  refused by a backoff of up to 60 minutes and would then never be retried
 *  until 02:00. */
private const val TELEMETRY_FLUSH_TICK_MS = 30L * 60 * 1000 // 30 min
```

- [ ] **Step 2: Add the dispatcher and the ticker job field**

In `MainActivity.kt`, immediately after the `telemetryManager by lazy { … }` block, add:

```kotlin
    /**
     * Serialises every flush against every other flush and against `activate()`.
     *
     * [TelemetryManager] documents itself as unsafe for concurrent callers: it
     * keeps its last upload outcome and last attempted ids in plain fields, and
     * `activate()` reads them to tell a row it never sent apart from one it sent
     * and could not confirm. Before this phase only the Test button called into
     * it; now a timer does too, and two callers on `Dispatchers.IO` could
     * interleave and let one read the other's outcome. One thread makes that
     * impossible by construction rather than by a lock no test can reach.
     *
     * Held through a `lazy` rather than `by lazy` so [onDestroy] can close it
     * only if something actually used it — a kiosk that never configures
     * analytics never spawns the thread.
     */
    private val telemetryFlushDispatcherLazy = lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "telemetry-flush") }
            .asCoroutineDispatcher()
    }
    private val telemetryFlushDispatcher get() = telemetryFlushDispatcherLazy.value
    private var telemetryFlushTickerJob: Job? = null
```

- [ ] **Step 3: Add `flushTelemetry` and `startTelemetryFlushTicker`**

In `MainActivity.kt`, immediately after `recordTelemetryLoss()` from Task 3, add:

```kotlin
    /**
     * Asks the manager to flush, off the main thread and serialised.
     *
     * Decides nothing. [TelemetryGate] owns whether a flush may happen — it
     * refuses a disabled, unconfigured, unactivated, offline, empty-queued or
     * backing-off kiosk — so the call sites only pick moments worth asking at.
     * [reason] exists so the log says which moment.
     */
    private fun flushTelemetry(reason: String) {
        lifecycleScope.launch {
            val block = withContext(telemetryFlushDispatcher) { telemetryManager.flush() }
            // A FlushBlock, never an error string: lastError may carry a server
            // response body, and it is redacted for the screen, not for logcat.
            Log.d("Telemetry", "flush($reason) -> $block")
            // Keeps an open analytics screen current after a flush the operator
            // did not trigger. Safe when the screen is closed: this function's
            // own guard returns early rather than decrypting the Keystore for a
            // screen nobody is looking at (Ruling BF).
            refreshAnalyticsSnapshot()
        }
    }

    /**
     * Retries a flush every 30 minutes for as long as the screensaver is up.
     *
     * One always-running loop that reads the flag, rather than a job started and
     * stopped alongside the screensaver: entering the screensaver already
     * produces its own flush, so this loop's only job is the later retry, and a
     * loop with no lifecycle coupling has no start/stop ordering to get wrong.
     */
    private fun startTelemetryFlushTicker() {
        telemetryFlushTickerJob?.cancel()
        telemetryFlushTickerJob = lifecycleScope.launch {
            while (true) {
                delay(TELEMETRY_FLUSH_TICK_MS)
                if (isScreensaverActive) flushTelemetry("idle-tick")
            }
        }
    }
```

- [ ] **Step 4: Start the ticker in `onCreate` and tear down in `onDestroy`**

In `onCreate`, replace (the following lines are included so the anchor is unique — `startConnectivityPolling` also appears as its own definition further down the file):

```kotlin
        startConnectivityPolling()
        if (!isNetworkAvailable) {
```

with:

```kotlin
        startConnectivityPolling()
        startTelemetryFlushTicker()
        if (!isNetworkAvailable) {
```

In `onDestroy`, replace:

```kotlin
        bluetoothWatchdogJob?.cancel()
        if (::updateManager.isInitialized) updateManager.dispose()
```

with:

```kotlin
        bluetoothWatchdogJob?.cancel()
        telemetryFlushTickerJob?.cancel()
        // Only if something actually flushed — otherwise this would spawn the
        // thread purely in order to shut it down.
        if (telemetryFlushDispatcherLazy.isInitialized()) telemetryFlushDispatcher.close()
        if (::updateManager.isInitialized) updateManager.dispose()
```

- [ ] **Step 5: Trigger — the screensaver coming up from idle**

In the `setContent` idle poll, replace:

```kotlin
                        if (idleTime > screensaverDelay && !isScreensaverActive && isLoggedIn && !isEditingSettings && !showThankYou && maintenanceReason == null) {
                            isScreensaverActive = true
                            Log.d("Screensaver", "Activated after idle")
                            disconnectCardReader()
                        }
```

with:

```kotlin
                        if (idleTime > screensaverDelay && !isScreensaverActive && isLoggedIn && !isEditingSettings && !showThankYou && maintenanceReason == null) {
                            isScreensaverActive = true
                            Log.d("Screensaver", "Activated after idle")
                            disconnectCardReader()
                            // The app's own definition of "nobody is using this",
                            // and it has already released the card reader on the
                            // line above — so a flush here cannot contend for the
                            // network with a live transaction.
                            flushTelemetry("screensaver")
                        }
```

- [ ] **Step 6: Trigger — the screensaver activated deliberately**

Replace:

```kotlin
    fun activateScreensaver() {
        isEditingSettings = false
        isScreensaverActive = true
        finishActivity(2)
        disconnectCardReader()
    }
```

with:

```kotlin
    fun activateScreensaver() {
        isEditingSettings = false
        isScreensaverActive = true
        finishActivity(2)
        disconnectCardReader()
        // Same reasoning as the idle path: the reader is released, so this is a
        // safe moment. Not funnelled through a shared helper with that site —
        // one lives inside a composition-scoped LaunchedEffect and this is an
        // activity method, and a wrapper across that boundary costs more than
        // the duplicated call.
        flushTelemetry("screensaver")
    }
```

- [ ] **Step 7: Trigger — network restored**

In `handleNetworkRestored()`, replace its tail — the last `when` branch plus the two closing braces:

```kotlin
            NetworkRestoredAction.AutoReinit -> {
                Log.d("NetworkRecovery", "Long downtime — auto-reinitializing")
                lifecycleScope.launch {
                    delay(2000L) // Brief pause for network to stabilise
                    performReinit()
                }
            }
        }
    }
```

with:

```kotlin
            NetworkRestoredAction.AutoReinit -> {
                Log.d("NetworkRecovery", "Long downtime — auto-reinitializing")
                lifecycleScope.launch {
                    delay(2000L) // Brief pause for network to stabilise
                    performReinit()
                }
            }
        }
        // Outside the `when`, so every restore path flushes: exactly when a
        // backlog can finally move. Edge-triggered — the connectivity poll calls
        // this only on a genuine online/offline transition (`online !=
        // isNetworkAvailable`), so it does not fire every second while online.
        // The kiosk was offline a moment ago, so it was not mid-transaction.
        flushTelemetry("network-restored")
    }
```

- [ ] **Step 8: Trigger — the 02:00 maintenance window**

In `scheduleDailyLoginReset`, replace:

```kotlin
            try {
                updateManager.refreshSettings(settings)
                updateManager.runDailyMaintenance()
            } catch (e: Exception) {
                Log.e("UpdateManager", "Daily maintenance threw: ${e.message}")
            }
```

with:

```kotlin
            try {
                updateManager.refreshSettings(settings)
                updateManager.runDailyMaintenance()
            } catch (e: Exception) {
                Log.e("UpdateManager", "Daily maintenance threw: ${e.message}")
            }
            // The floor. A kiosk busy enough never to idle for
            // screensaverIdleTimeoutSec during opening hours reports here and
            // nowhere else, so this must sit outside the try above — an update
            // maintenance failure must not also cost the nightly flush.
            flushTelemetry("nightly")
```

- [ ] **Step 9: Move `activate()` onto the flush dispatcher**

In `onAnalyticsTestConnection()`, replace:

```kotlin
            val result = withContext(Dispatchers.IO) { telemetryManager.activate() }
```

with:

```kotlin
            // The flush dispatcher, not Dispatchers.IO: activate() reads the
            // manager's last-outcome fields, and a scheduled flush running
            // concurrently on a shared pool could overwrite them between this
            // call's own flush and its read.
            val result = withContext(telemetryFlushDispatcher) { telemetryManager.activate() }
```

Leave `refreshAnalyticsSnapshot`'s `Dispatchers.IO` alone. It calls `credentials.load()` and `manager.status()`, and `status()` touches only the status store and `outbox.size()` — never the manager's outcome fields. Moving it would queue a Keystore decrypt behind an upload that can run for minutes and make opening the analytics screen hang on a flush in progress.

- [ ] **Step 10: Build, run the full suite, and verify the new inertness shape**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, **404** tests, 0 failures.

Then run each of these and check the count against the expected value. **These numbers changed in this phase** — a previous phase's expectation of "exactly one append" is now wrong, and a mismatch here is a real finding, not a stale grep:

```bash
# Appends in main source: exactly 2 — the activation row, and the donation.
grep -rn "outbox.append(\|telemetryOutbox.append(" app/src/main/java | grep -v "^.*://"

# flush() callers in main source: exactly 1 — inside flushTelemetry.
grep -rn "telemetryManager.flush()" app/src/main/java

# flushTelemetry call sites: exactly 5.
grep -rn 'flushTelemetry("' app/src/main/java

# activate() callers: still exactly 1 — the Test connection button.
grep -rn "telemetryManager.activate()" app/src/main/java

# 3b work must not have leaked in: expect NO output from all three.
grep -rn "setDefaultUncaughtExceptionHandler" app/src/main/java
grep -rn "DiagnosticKind\." app/src/main/java
grep -rn "TelemetryEvent.Diagnostic(" app/src/main/java
```

- [ ] **Step 11: Record the device-check list in your report**

Copy this verbatim into your task report — it is the phase's only coverage for everything above that has no JVM test, and it must reach the PR body:

1. A donation on an enabled, configured kiosk appends **exactly one** row (read the queue count on the analytics screen before and after).
2. A donation on a kiosk with `analyticsEnabled` off appends **nothing**.
3. Letting the screensaver come up on a kiosk with a non-empty queue drains it.
4. A donation taken in airplane mode queues, and drains when wifi returns, with nobody touching the app.
5. Status survives a process restart with `droppedCount` intact (carried over from 2c, still unverified).
6. Repeated donations while offline do not slow the thank-you screen on a kiosk already holding thousands of rows.
7. Pressing "Test connection" while a scheduled flush is in flight still reports the correct result rather than the timer's.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/MainActivity.kt
git commit -m "Flush when the kiosk goes quiet, on one thread, without being asked"
```

---

## After the tasks

Not plan steps — the orchestrator's work:

1. **Whole-phase review** over `a5a4c82..HEAD`, dispatched on opus, told to read HEAD state rather than only the diff. The seam to press hardest: `TelemetryUploader`'s sibling-corroboration invariant, since Task 2 is the first change to that file since 2a and it is the one place a wrong edit deletes donations.
2. **Push and open a PR** against `telemetry/phase-2c`, stacked on #5. Never a local merge.
3. **The PR body must carry** the device-check list above, the deliberately-untested items from Tasks 3 and 4, and the accepted cost of the fallback cap (bad rows at the head of the queue stall the rows behind them until the 30-day age cap).

## Deferred, on purpose

- `retryableFailure` is one global boolean, so a single refused table backs off the healthy ones. Carried in from 2a. Automatic flushing makes it bite harder than it did when a human pressed the button, but fixing it means changing what `UploadOutcome` means, which is not this phase.
- `formatTimestamp` in `AnalyticsSettingsScreen.kt` is still the one computation living in the untestable file (M5, recorded in 2c).
- `SettingsBootstrap`'s call site is still untested; instrumented coverage belongs with 3b's device work.
