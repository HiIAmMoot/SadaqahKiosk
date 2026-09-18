# Telemetry Phase 1 — Outbox, Events, Redaction, Gate

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the pure, fully unit-testable foundation of the telemetry subsystem — event types, secret redaction, the on-disk queue, and the flush-decision policy — with nothing yet enqueuing or uploading.

**Architecture:** Four files in a new `com.sadaqah.kiosk.telemetry` package, none of which import anything from `android.*`. That is the whole point: this layer is decidable and testable on the JVM, mirroring how `recovery/` and `settingsio/` are already structured. The queue is an append-only JSONL file addressed by `java.io.File`, so tests drive it with a temp directory rather than a `Context`. Later phases add the Android-facing pieces (credentials, uploader, manager, screens) on top.

**Tech Stack:** Kotlin 2.0.21, Gson (already a project dependency), `java.time` (available at minSdk 30), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md`

## Global Constraints

- **No new Gradle dependencies.** Gson is already present; everything else is JDK.
- **No `android.*` imports in any file this plan creates.** If a task needs one, the design is wrong — stop and escalate.
- **Nothing in this phase enqueues or uploads.** No existing file is modified. The app's behaviour is unchanged after this plan; only new, unreferenced classes and their tests exist.
- **Redaction happens on the way to disk, never on the way to the network.** An unredacted secret must never be written to a file.
- **Caps: 5,000 events or 30 days**, whichever binds first, oldest dropped, enforced on append.
- **Batch size for reads: 100.**
- **`code` may legitimately be empty** — a fork with no kiosk-code scheme. Never treat empty as invalid.
- **`kind` and `severity` are plain text on the wire.** The Kotlin enums are an app-layer convenience; they serialise to lowercase strings and the set will grow.
- **Backoff ceiling: 60 minutes.**
- **Stack traces truncate to 8 KB.**
- Codebase style: self-documenting names; comments explain non-obvious *why*, never *what*; no commented-out code.
- Run tests with `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.*"`. The Bash tool is Git Bash — use `./gradlew`, forward slashes, and a heredoc (`git commit -F - <<'EOF'`) for multi-line commit messages, never PowerShell here-string syntax.
- **Never commit or push to `master`.** Work stays on `preview`; a hook blocks pushes to master.

## File Structure

| File | Responsibility |
|---|---|
| `telemetry/TelemetryRedactor.kt` | Strip secrets and truncate oversized text. Knows nothing about events. |
| `telemetry/TelemetryEvent.kt` | Event types and their Supabase payload JSON. Knows nothing about storage. |
| `telemetry/TelemetryOutbox.kt` | Append-only JSONL queue with caps. Knows nothing about event types — it moves opaque payloads. |
| `telemetry/TelemetryGate.kt` | Pure flush policy and backoff schedule. Knows nothing about anything else. |

The deliberate decoupling is between **events** and **storage**: the outbox deals in `(id, table, payload)` triples, so a new event type never touches queue code and the queue can be tested without constructing events.

---

### Task 1: Secret redaction

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryRedactor.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryRedactorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `TelemetryRedactor.scrub(text: String?, affiliateKey: String?): String?`
  - `TelemetryRedactor.truncate(text: String?): String?`
  - `TelemetryRedactor.REDACTED: String` = `"[redacted]"`
  - `TelemetryRedactor.MAX_TEXT_BYTES: Int` = `8192`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryRedactorTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Test

class TelemetryRedactorTest {

    private val affiliateKey = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

    // ── Affiliate key ────────────────────────────────────────────────────────

    @Test
    fun scrub_removesAffiliateKey() {
        val text = "login failed for key $affiliateKey after retry"
        val out = TelemetryRedactor.scrub(text, affiliateKey)!!
        assertFalse(out.contains(affiliateKey))
        assertTrue(out.contains(TelemetryRedactor.REDACTED))
    }

    @Test
    fun scrub_removesEveryOccurrenceOfTheKey() {
        val text = "$affiliateKey and again $affiliateKey"
        val out = TelemetryRedactor.scrub(text, affiliateKey)!!
        assertFalse(out.contains(affiliateKey))
    }

    @Test
    fun scrub_toleratesNullOrBlankKey() {
        assertEquals("nothing secret here", TelemetryRedactor.scrub("nothing secret here", null))
        assertEquals("nothing secret here", TelemetryRedactor.scrub("nothing secret here", ""))
    }

    @Test
    fun scrub_nullTextStaysNull() {
        assertNull(TelemetryRedactor.scrub(null, affiliateKey))
    }

    // ── Token-shaped strings ─────────────────────────────────────────────────

    @Test
    fun scrub_removesTokenShapedRuns() {
        val token = "sbp_0123456789abcdef0123456789abcdef0123"
        val out = TelemetryRedactor.scrub("key=$token", null)!!
        assertFalse(out.contains(token))
        assertTrue(out.contains(TelemetryRedactor.REDACTED))
    }

    @Test
    fun scrub_removesUuidShapedStrings() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertFalse(TelemetryRedactor.scrub(uuid, null)!!.contains(uuid))
    }

    /**
     * The redactor runs over stack traces. Over-redacting destroys the thing we
     * collected the trace for, so ordinary identifiers must survive.
     */
    @Test
    fun scrub_keepsFullyQualifiedClassNames() {
        val trace = "at com.sadaqah.kiosk.update.UpdateWatchdogReceiver.onReceive(UpdateWatchdogReceiver.kt:47)"
        assertEquals(trace, TelemetryRedactor.scrub(trace, null))
    }

    @Test
    fun scrub_keepsFilePaths() {
        val path = "/data/user/0/com.sadaqah.kiosk/files/telemetry/outbox.jsonl"
        assertEquals(path, TelemetryRedactor.scrub(path, null))
    }

    @Test
    fun scrub_keepsOrdinaryProse() {
        val text = "Card reader connect failed after 3 attempts"
        assertEquals(text, TelemetryRedactor.scrub(text, null))
    }

    @Test
    fun scrub_keepsShortHexRuns() {
        // A 16-char run is under the threshold; commit SHAs and short ids survive.
        val text = "commit 0123456789abcdef"
        assertEquals(text, TelemetryRedactor.scrub(text, null))
    }

    // ── Truncation ───────────────────────────────────────────────────────────

    @Test
    fun truncate_leavesShortTextAlone() {
        assertEquals("short", TelemetryRedactor.truncate("short"))
    }

    @Test
    fun truncate_nullStaysNull() {
        assertNull(TelemetryRedactor.truncate(null))
    }

    @Test
    fun truncate_capsLongText() {
        val long = "x".repeat(20_000)
        val out = TelemetryRedactor.truncate(long)!!
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= TelemetryRedactor.MAX_TEXT_BYTES + 32)
        assertTrue(out.length < long.length)
    }

    @Test
    fun truncate_marksThatItTruncated() {
        val out = TelemetryRedactor.truncate("y".repeat(20_000))!!
        assertTrue(out.endsWith("… truncated"))
    }

    @Test
    fun truncate_exactlyAtLimitIsUntouched() {
        val exact = "z".repeat(TelemetryRedactor.MAX_TEXT_BYTES)
        assertEquals(exact, TelemetryRedactor.truncate(exact))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryRedactorTest"`
Expected: FAIL — `Unresolved reference 'TelemetryRedactor'`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryRedactor.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

/**
 * Strips secrets out of free text and caps its size.
 *
 * Scrubbing runs on the way to disk rather than on the way to the network: the
 * outbox survives crashes and can be pulled off a device, so an unredacted value
 * must never be written to a file in the first place.
 */
object TelemetryRedactor {
    const val REDACTED = "[redacted]"
    const val MAX_TEXT_BYTES = 8 * 1024

    /**
     * Runs of 32+ token characters. The threshold is a deliberate trade: long
     * enough that package names, file paths and short hashes survive — the
     * redactor runs over stack traces, and over-redacting destroys the reason we
     * collected them — short enough to catch keys, tokens and UUIDs.
     */
    private val TOKEN_SHAPED = Regex("[A-Za-z0-9+/=_-]{32,}")

    fun scrub(text: String?, affiliateKey: String?): String? {
        if (text == null) return null
        val withoutKey =
            if (affiliateKey.isNullOrBlank()) text else text.replace(affiliateKey, REDACTED)
        return TOKEN_SHAPED.replace(withoutKey, REDACTED)
    }

    fun truncate(text: String?): String? {
        if (text == null) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_TEXT_BYTES) return text
        // A cut can land mid-codepoint; the resulting replacement char is
        // harmless in a diagnostic and cheaper than scanning for a boundary.
        return String(bytes, 0, MAX_TEXT_BYTES, Charsets.UTF_8) + "\n… truncated"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryRedactorTest"`
Expected: PASS, 15 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryRedactor.kt app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryRedactorTest.kt
git commit -F - <<'EOF'
Add telemetry secret redaction

Scrubs the affiliate key and token-shaped strings, and caps text at 8 KB.
Runs before anything reaches disk, since the outbox outlives the process.
EOF
```

---

### Task 2: Event types and payloads

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryEvent.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryEventTest.kt`

**Interfaces:**
- Consumes: `TelemetryRedactor.scrub`, `TelemetryRedactor.truncate` from Task 1.
- Produces:
  - `data class EventIdentity(val code: String, val installId: String, val appVersion: String)`
  - `enum class DiagnosticSeverity { INFO, WARN, ERROR }` with `val wire: String`
  - `enum class DiagnosticKind(val wire: String, val severity: DiagnosticSeverity)` — 11 entries
  - `object TelemetryTables { const val DONATIONS; const val DIAGNOSTICS; const val ACTIVATIONS }`
  - `sealed class TelemetryEvent` with `val id: String`, `val table: String`, `fun payloadJson(): String`
  - Subclasses `TelemetryEvent.Donation`, `TelemetryEvent.Diagnostic`, `TelemetryEvent.Activation`
  - `TelemetryEvent.nowIso(): String`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryEventTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TelemetryEventTest {

    private val identity = EventIdentity(
        code = "nl-gld-arnhem-nour_al_houda-01",
        installId = "11111111-2222-3333-4444-555555555555",
        appVersion = "1.3.6-preview"
    )

    private fun parse(json: String) = JsonParser.parseString(json).asJsonObject

    // ── Donation ─────────────────────────────────────────────────────────────

    @Test
    fun donation_targetsTheDonationTable() {
        val e = TelemetryEvent.Donation(identity, amountCents = 2500, currency = "EUR",
            occurredAtIso = "2026-09-05T10:00:00Z")
        assertEquals("donation_events", e.table)
    }

    @Test
    fun donation_payloadCarriesEveryField() {
        val e = TelemetryEvent.Donation(identity, amountCents = 2500, currency = "EUR",
            occurredAtIso = "2026-09-05T10:00:00Z")
        val p = parse(e.payloadJson())
        assertEquals(e.id, p.get("id").asString)
        assertEquals("nl-gld-arnhem-nour_al_houda-01", p.get("code").asString)
        assertEquals("11111111-2222-3333-4444-555555555555", p.get("install_id").asString)
        assertEquals(2500, p.get("amount_cents").asInt)
        assertEquals("EUR", p.get("currency").asString)
        assertEquals("2026-09-05T10:00:00Z", p.get("occurred_at").asString)
        assertEquals("1.3.6-preview", p.get("app_version").asString)
    }

    @Test
    fun donation_neverCarriesDonorOrCardFields() {
        val e = TelemetryEvent.Donation(identity, 2500, "EUR", "2026-09-05T10:00:00Z")
        val p = parse(e.payloadJson())
        for (forbidden in listOf("tx_code", "card", "donor", "pan", "name")) {
            assertFalse("payload must not contain $forbidden", p.has(forbidden))
        }
    }

    /** A fork with no kiosk-code scheme is a supported deployment. */
    @Test
    fun donation_acceptsAnEmptyCode() {
        val anon = identity.copy(code = "")
        val p = parse(TelemetryEvent.Donation(anon, 100, "EUR", "2026-09-05T10:00:00Z").payloadJson())
        assertEquals("", p.get("code").asString)
    }

    @Test
    fun donation_idsAreUniquePerEvent() {
        val a = TelemetryEvent.Donation(identity, 100, "EUR", "2026-09-05T10:00:00Z")
        val b = TelemetryEvent.Donation(identity, 100, "EUR", "2026-09-05T10:00:00Z")
        assertNotEquals(a.id, b.id)
    }

    // ── Diagnostic ───────────────────────────────────────────────────────────

    @Test
    fun diagnostic_targetsTheDiagnosticTable() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.NETWORK_OUTAGE,
            occurredAtIso = "2026-09-05T10:00:00Z")
        assertEquals("diagnostic_events", e.table)
    }

    @Test
    fun diagnostic_serialisesKindAndSeverityAsLowercaseText() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CARD_READER_PAGE_TIMEOUT,
            occurredAtIso = "2026-09-05T10:00:00Z")
        val p = parse(e.payloadJson())
        assertEquals("card_reader_page_timeout", p.get("kind").asString)
        assertEquals("warn", p.get("severity").asString)
    }

    @Test
    fun diagnostic_severityComesFromTheKind() {
        assertEquals(DiagnosticSeverity.ERROR, DiagnosticKind.CRASH.severity)
        assertEquals(DiagnosticSeverity.WARN, DiagnosticKind.BLUETOOTH_WATCHDOG_FIRED.severity)
        assertEquals(DiagnosticSeverity.INFO, DiagnosticKind.UPDATE_INSTALLED.severity)
    }

    @Test
    fun diagnostic_hasElevenKinds() {
        assertEquals(11, DiagnosticKind.values().size)
    }

    @Test
    fun diagnostic_wireNamesAreUnique() {
        val wires = DiagnosticKind.values().map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
    }

    @Test
    fun diagnostic_omitsAbsentDetailAndStackTrace() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.NETWORK_OUTAGE,
            occurredAtIso = "2026-09-05T10:00:00Z")
        val p = parse(e.payloadJson())
        assertFalse(p.has("detail"))
        assertFalse(p.has("stack_trace"))
    }

    @Test
    fun diagnostic_detailIsEmbeddedAsJsonNotAString() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.UPDATE_INSTALLED,
            occurredAtIso = "2026-09-05T10:00:00Z",
            detailJson = """{"from_version":"1.3.5","to_version":"1.3.6"}""")
        val p = parse(e.payloadJson())
        assertTrue(p.get("detail").isJsonObject)
        assertEquals("1.3.5", p.getAsJsonObject("detail").get("from_version").asString)
    }

    /** Malformed detail must not corrupt the payload or throw at enqueue time. */
    @Test
    fun diagnostic_malformedDetailIsDropped() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z", detailJson = "not json at all")
        val p = parse(e.payloadJson())
        assertFalse(p.has("detail"))
    }

    @Test
    fun diagnostic_redactsTheAffiliateKeyFromStackTrace() {
        val key = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z",
            stackTrace = "java.lang.IllegalStateException: key $key rejected",
            affiliateKey = key)
        val p = parse(e.payloadJson())
        assertFalse(p.get("stack_trace").asString.contains(key))
    }

    @Test
    fun diagnostic_truncatesAnOversizedStackTrace() {
        val e = TelemetryEvent.Diagnostic(identity, DiagnosticKind.CRASH,
            occurredAtIso = "2026-09-05T10:00:00Z", stackTrace = "x".repeat(50_000))
        val p = parse(e.payloadJson())
        assertTrue(p.get("stack_trace").asString.length < 50_000)
    }

    // ── Activation ───────────────────────────────────────────────────────────

    @Test
    fun activation_targetsTheActivationTable() {
        val e = TelemetryEvent.Activation(identity, activatedAtIso = "2026-09-05T10:00:00Z",
            privacyPolicyUrl = "https://example.invalid/privacy",
            termsUrl = "https://example.invalid/terms")
        assertEquals("telemetry_activations", e.table)
    }

    @Test
    fun activation_payloadUsesActivatedAtNotAgreedAt() {
        val e = TelemetryEvent.Activation(identity, activatedAtIso = "2026-09-05T10:00:00Z",
            privacyPolicyUrl = "https://example.invalid/privacy",
            termsUrl = "https://example.invalid/terms")
        val p = parse(e.payloadJson())
        assertEquals("2026-09-05T10:00:00Z", p.get("activated_at").asString)
        assertFalse("this is an activation record, not a consent record", p.has("agreed_at"))
        assertEquals("https://example.invalid/privacy", p.get("privacy_policy_url").asString)
    }

    // ── Timestamps ───────────────────────────────────────────────────────────

    @Test
    fun nowIso_isUtcAndParseable() {
        val now = TelemetryEvent.nowIso()
        assertTrue("expected a Z-suffixed UTC instant but was $now", now.endsWith("Z"))
        java.time.Instant.parse(now)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryEventTest"`
Expected: FAIL — `Unresolved reference 'EventIdentity'`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryEvent.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.util.UUID

object TelemetryTables {
    const val DONATIONS = "donation_events"
    const val DIAGNOSTICS = "diagnostic_events"
    const val ACTIVATIONS = "telemetry_activations"
}

/**
 * Who is reporting. [code] is the printed panel code and may be empty — a
 * deployment with no kiosk-code scheme is identified by [installId] alone.
 */
data class EventIdentity(
    val code: String,
    val installId: String,
    val appVersion: String
)

enum class DiagnosticSeverity {
    INFO, WARN, ERROR;

    val wire: String get() = name.lowercase()
}

/**
 * Closed here for the app's convenience only. On the wire `kind` is plain text
 * and the database must not constrain it — this list will grow, and a rejecting
 * constraint would stall the outbox until it drops real donations.
 */
enum class DiagnosticKind(val wire: String, val severity: DiagnosticSeverity) {
    CRASH("crash", DiagnosticSeverity.ERROR),
    RESTART_TRIGGERED("restart_triggered", DiagnosticSeverity.ERROR),
    SUMUP_REINIT_FAILED("sumup_reinit_failed", DiagnosticSeverity.ERROR),
    CARD_READER_CONNECT_FAILED("card_reader_connect_failed", DiagnosticSeverity.WARN),
    CARD_READER_PAGE_TIMEOUT("card_reader_page_timeout", DiagnosticSeverity.WARN),
    CHECKOUT_NO_READER("checkout_no_reader", DiagnosticSeverity.WARN),
    BLUETOOTH_WATCHDOG_FIRED("bluetooth_watchdog_fired", DiagnosticSeverity.WARN),
    NETWORK_OUTAGE("network_outage", DiagnosticSeverity.WARN),
    UPDATE_INSTALLED("update_installed", DiagnosticSeverity.INFO),
    UPDATE_INSTALL_FAILED("update_install_failed", DiagnosticSeverity.ERROR),
    UPDATE_ROLLBACK("update_rollback", DiagnosticSeverity.ERROR)
}

/**
 * One reportable thing that happened. Events know how to render their own
 * Supabase payload and nothing about how that payload is stored or sent.
 */
sealed class TelemetryEvent {
    abstract val id: String
    abstract val table: String
    abstract val identity: EventIdentity

    /** The row to insert, already redacted. */
    fun payloadJson(): String {
        val row = JsonObject()
        row.addProperty("id", id)
        row.addProperty("code", identity.code)
        row.addProperty("install_id", identity.installId)
        row.addProperty("app_version", identity.appVersion)
        addFields(row)
        return row.toString()
    }

    protected abstract fun addFields(target: JsonObject)

    data class Donation(
        override val identity: EventIdentity,
        val amountCents: Int,
        val currency: String,
        val occurredAtIso: String,
        override val id: String = UUID.randomUUID().toString()
    ) : TelemetryEvent() {
        override val table = TelemetryTables.DONATIONS
        override fun addFields(target: JsonObject) {
            target.addProperty("amount_cents", amountCents)
            target.addProperty("currency", currency)
            target.addProperty("occurred_at", occurredAtIso)
        }
    }

    data class Diagnostic(
        override val identity: EventIdentity,
        val kind: DiagnosticKind,
        val occurredAtIso: String,
        val detailJson: String? = null,
        val stackTrace: String? = null,
        val affiliateKey: String? = null,
        override val id: String = UUID.randomUUID().toString()
    ) : TelemetryEvent() {
        override val table = TelemetryTables.DIAGNOSTICS

        override fun addFields(target: JsonObject) {
            target.addProperty("occurred_at", occurredAtIso)
            target.addProperty("kind", kind.wire)
            target.addProperty("severity", kind.severity.wire)

            detailAsObject()?.let { target.add("detail", it) }

            val cleaned = TelemetryRedactor.truncate(
                TelemetryRedactor.scrub(stackTrace, affiliateKey)
            )
            if (cleaned != null) target.addProperty("stack_trace", cleaned)
        }

        /** Malformed detail is dropped rather than thrown: a broken diagnostic
         *  must never take down the code path that reported it. */
        private fun detailAsObject(): JsonObject? {
            val raw = detailJson ?: return null
            return try {
                JsonParser.parseString(raw) as? JsonObject
            } catch (e: Exception) {
                null
            }
        }
    }

    data class Activation(
        override val identity: EventIdentity,
        val activatedAtIso: String,
        val privacyPolicyUrl: String,
        val termsUrl: String,
        override val id: String = UUID.randomUUID().toString()
    ) : TelemetryEvent() {
        override val table = TelemetryTables.ACTIVATIONS
        override fun addFields(target: JsonObject) {
            target.addProperty("activated_at", activatedAtIso)
            target.addProperty("privacy_policy_url", privacyPolicyUrl)
            target.addProperty("terms_url", termsUrl)
        }
    }

    companion object {
        /** UTC, second precision, always Z-suffixed. */
        fun nowIso(): String = Instant.now().toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryEventTest"`
Expected: PASS, 18 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryEvent.kt app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryEventTest.kt
git commit -F - <<'EOF'
Add telemetry event types and Supabase payloads

Donation, diagnostic and activation events render their own rows. Kind
and severity serialise as plain lowercase text; the enum is an app-layer
convenience the database must not mirror as a constraint.
EOF
```

---

### Task 3: The outbox

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryOutbox.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryOutboxTest.kt`

**Interfaces:**
- Consumes: nothing (deliberately — it moves opaque payloads, never event types).
- Produces:
  - `data class QueuedEvent(val id: String, val table: String, val payload: String, val queuedAtMs: Long)`
  - `class TelemetryOutbox(file: File, maxEvents: Int = 5000, maxAgeMs: Long = 30 days, clock: () -> Long = System::currentTimeMillis)`
  - `fun append(id: String, table: String, payload: String)`
  - `fun peek(limit: Int = 100): List<QueuedEvent>`
  - `fun remove(ids: Set<String>)`
  - `fun size(): Int`
  - `TelemetryOutbox.DEFAULT_BATCH: Int` = `100`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryOutboxTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelemetryOutboxTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var file: File
    private var now = 1_000_000L

    private fun outbox(maxEvents: Int = 5000, maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000) =
        TelemetryOutbox(file, maxEvents, maxAgeMs) { now }

    @Before
    fun setUp() {
        file = File(temp.newFolder("telemetry"), "outbox.jsonl")
        now = 1_000_000L
    }

    private fun TelemetryOutbox.appendDonation(id: String) =
        append(id, "donation_events", """{"id":"$id","amount_cents":100}""")

    // ── Append and read ──────────────────────────────────────────────────────

    @Test
    fun emptyOutbox_readsAsEmpty() {
        assertEquals(0, outbox().size())
        assertTrue(outbox().peek().isEmpty())
    }

    @Test
    fun append_thenPeek_returnsTheEvent() {
        val box = outbox()
        box.appendDonation("e1")
        val got = box.peek()
        assertEquals(1, got.size)
        assertEquals("e1", got[0].id)
        assertEquals("donation_events", got[0].table)
        assertTrue(got[0].payload.contains("amount_cents"))
    }

    @Test
    fun append_createsParentDirectories() {
        val nested = File(temp.newFolder("a"), "b/c/outbox.jsonl")
        TelemetryOutbox(nested, 5000, 1000L) { now }.append("x", "t", "{}")
        assertTrue(nested.exists())
    }

    @Test
    fun append_stampsQueuedAtFromTheClock() {
        now = 12345L
        val box = outbox()
        box.appendDonation("e1")
        assertEquals(12345L, box.peek()[0].queuedAtMs)
    }

    @Test
    fun peek_preservesInsertionOrder() {
        val box = outbox()
        listOf("e1", "e2", "e3").forEach { box.appendDonation(it) }
        assertEquals(listOf("e1", "e2", "e3"), box.peek().map { it.id })
    }

    @Test
    fun peek_honoursTheLimit() {
        val box = outbox()
        repeat(10) { box.appendDonation("e$it") }
        assertEquals(3, box.peek(3).size)
        assertEquals(listOf("e0", "e1", "e2"), box.peek(3).map { it.id })
    }

    @Test
    fun peek_defaultBatchIsOneHundred() {
        val box = outbox()
        repeat(150) { box.appendDonation("e$it") }
        assertEquals(100, box.peek().size)
    }

    /**
     * A stack trace contains real newlines. One JSON object per line only holds
     * if the payload is escaped on the way in, so this uses a genuine newline
     * rather than an already-escaped one.
     */
    @Test
    fun payloadWithNewlinesDoesNotCorruptTheFile() {
        val box = outbox()
        val multiline = "{\"stack_trace\":\"line1\nline2\nline3\"}"
        box.append("e1", "diagnostic_events", multiline)
        box.appendDonation("e2")

        assertEquals(2, box.size())
        assertEquals(2, file.readLines().count { it.isNotBlank() })
        assertEquals(multiline, box.peek()[0].payload)
    }

    // ── Removal ──────────────────────────────────────────────────────────────

    @Test
    fun remove_dropsOnlyTheNamedIds() {
        val box = outbox()
        listOf("e1", "e2", "e3").forEach { box.appendDonation(it) }
        box.remove(setOf("e1", "e3"))
        assertEquals(listOf("e2"), box.peek().map { it.id })
    }

    @Test
    fun remove_unknownIdIsHarmless() {
        val box = outbox()
        box.appendDonation("e1")
        box.remove(setOf("nope"))
        assertEquals(1, box.size())
    }

    @Test
    fun remove_everything_leavesAnEmptyOutbox() {
        val box = outbox()
        listOf("e1", "e2").forEach { box.appendDonation(it) }
        box.remove(setOf("e1", "e2"))
        assertEquals(0, box.size())
        assertTrue(box.peek().isEmpty())
    }

    // ── Caps ─────────────────────────────────────────────────────────────────

    @Test
    fun countCap_dropsOldestFirst() {
        val box = outbox(maxEvents = 3)
        listOf("e1", "e2", "e3", "e4").forEach { box.appendDonation(it) }
        assertEquals(3, box.size())
        assertEquals(listOf("e2", "e3", "e4"), box.peek().map { it.id })
    }

    @Test
    fun ageCap_dropsEventsPastTheWindow() {
        val box = outbox(maxAgeMs = 10_000L)
        box.appendDonation("old")
        now += 20_000L
        box.appendDonation("fresh")
        assertEquals(listOf("fresh"), box.peek().map { it.id })
    }

    @Test
    fun ageCap_keepsEventsInsideTheWindow() {
        val box = outbox(maxAgeMs = 10_000L)
        box.appendDonation("first")
        now += 5_000L
        box.appendDonation("second")
        assertEquals(listOf("first", "second"), box.peek().map { it.id })
    }

    /** An offline kiosk must lose its oldest telemetry, never its disk. */
    @Test
    fun capsApplyOnAppendNotOnRead() {
        val box = outbox(maxEvents = 2)
        repeat(50) { box.appendDonation("e$it") }
        assertEquals(2, file.readLines().count { it.isNotBlank() })
    }

    // ── Corruption tolerance ─────────────────────────────────────────────────

    @Test
    fun malformedLinesAreSkipped() {
        val box = outbox()
        box.appendDonation("good1")
        file.appendText("this is not json\n")
        box.appendDonation("good2")
        assertEquals(listOf("good1", "good2"), box.peek().map { it.id })
    }

    @Test
    fun blankLinesAreIgnored() {
        val box = outbox()
        box.appendDonation("e1")
        file.appendText("\n\n")
        assertEquals(1, box.size())
    }

    @Test
    fun lineMissingRequiredFieldsIsSkipped() {
        val box = outbox()
        file.appendText("""{"id":"x"}""" + "\n")
        box.appendDonation("e1")
        assertEquals(listOf("e1"), box.peek().map { it.id })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryOutboxTest"`
Expected: FAIL — `Unresolved reference 'TelemetryOutbox'`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryOutbox.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** One line of the outbox: an opaque payload plus what the queue needs to manage it. */
data class QueuedEvent(
    val id: String,
    val table: String,
    val payload: String,
    val queuedAtMs: Long
)

/**
 * Append-only JSONL queue of unsent telemetry.
 *
 * Deliberately knows nothing about event types — it moves `(id, table, payload)`
 * triples — so a new kind of event never touches queue code, and the queue can be
 * tested without constructing one.
 *
 * Storage is separate from [com.sadaqah.kiosk.donations.DonationHistory] on
 * purpose: that file is user-facing and has a Clear button, and an operator
 * clearing their history must not silently destroy unsent telemetry.
 */
class TelemetryOutbox(
    private val file: File,
    private val maxEvents: Int = 5000,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun append(id: String, table: String, payload: String) {
        val queued = QueuedEvent(id, table, payload, clock())
        writeAll(applyCaps(readAll() + queued))
    }

    fun peek(limit: Int = DEFAULT_BATCH): List<QueuedEvent> = readAll().take(limit)

    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        writeAll(readAll().filterNot { it.id in ids })
    }

    fun size(): Int = readAll().size

    // ── Internals ────────────────────────────────────────────────────────────

    /** Caps are enforced here, on write, so an offline kiosk sheds its oldest
     *  telemetry rather than filling the device's storage. */
    private fun applyCaps(events: List<QueuedEvent>): List<QueuedEvent> {
        val cutoff = clock() - maxAgeMs
        return events.filter { it.queuedAtMs >= cutoff }.takeLast(maxEvents)
    }

    private fun readAll(): List<QueuedEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { parseLine(it) }
    }

    private fun writeAll(events: List<QueuedEvent>) {
        file.parentFile?.mkdirs()
        file.writeText(events.joinToString("") { serialise(it) })
    }

    private fun serialise(e: QueuedEvent): String = JsonObject().apply {
        addProperty("id", e.id)
        addProperty("table", e.table)
        addProperty("queuedAt", e.queuedAtMs)
        addProperty("payload", e.payload)
    }.toString() + "\n"

    /** A single unreadable line must not cost the whole queue, so anything that
     *  fails to parse is skipped rather than throwing. */
    private fun parseLine(line: String): QueuedEvent? {
        if (line.isBlank()) return null
        return try {
            val o = JsonParser.parseString(line) as? JsonObject ?: return null
            QueuedEvent(
                id = o.get("id")?.asString ?: return null,
                table = o.get("table")?.asString ?: return null,
                payload = o.get("payload")?.asString ?: return null,
                queuedAtMs = o.get("queuedAt")?.asLong ?: return null
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_BATCH = 100
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryOutboxTest"`
Expected: PASS, 18 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryOutbox.kt app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryOutboxTest.kt
git commit -F - <<'EOF'
Add the telemetry outbox

Append-only JSONL queue capped at 5,000 events or 30 days, oldest
dropped on append so an offline kiosk sheds telemetry rather than disk.
Unreadable lines are skipped instead of failing the whole queue.
EOF
```

---

### Task 4: The flush gate

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryGate.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryGateTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class GateInputs(val enabled: Boolean, val configured: Boolean, val activated: Boolean, val networkAvailable: Boolean, val queueDepth: Int, val backoffUntilMs: Long)`
  - `enum class FlushBlock { NONE, DISABLED, NOT_CONFIGURED, NOT_ACTIVATED, NO_NETWORK, EMPTY_QUEUE, BACKING_OFF }`
  - `TelemetryGate.evaluate(inputs: GateInputs, nowMs: Long): FlushBlock`
  - `TelemetryGate.backoffDelayMs(consecutiveFailures: Int): Long`
  - `TelemetryGate.MAX_BACKOFF_MS: Long` = 3_600_000

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryGateTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Test

class TelemetryGateTest {

    private val now = 1_000_000L

    private fun ready() = GateInputs(
        enabled = true,
        configured = true,
        activated = true,
        networkAvailable = true,
        queueDepth = 5,
        backoffUntilMs = 0L
    )

    // ── Blocking conditions ──────────────────────────────────────────────────

    @Test
    fun allConditionsMet_allowsFlush() {
        assertEquals(FlushBlock.NONE, TelemetryGate.evaluate(ready(), now))
    }

    @Test
    fun disabled_blocks() {
        assertEquals(FlushBlock.DISABLED,
            TelemetryGate.evaluate(ready().copy(enabled = false), now))
    }

    @Test
    fun notConfigured_blocks() {
        assertEquals(FlushBlock.NOT_CONFIGURED,
            TelemetryGate.evaluate(ready().copy(configured = false), now))
    }

    @Test
    fun notActivated_blocks() {
        assertEquals(FlushBlock.NOT_ACTIVATED,
            TelemetryGate.evaluate(ready().copy(activated = false), now))
    }

    @Test
    fun noNetwork_blocks() {
        assertEquals(FlushBlock.NO_NETWORK,
            TelemetryGate.evaluate(ready().copy(networkAvailable = false), now))
    }

    @Test
    fun emptyQueue_blocks() {
        assertEquals(FlushBlock.EMPTY_QUEUE,
            TelemetryGate.evaluate(ready().copy(queueDepth = 0), now))
    }

    @Test
    fun withinBackoffWindow_blocks() {
        assertEquals(FlushBlock.BACKING_OFF,
            TelemetryGate.evaluate(ready().copy(backoffUntilMs = now + 1), now))
    }

    @Test
    fun backoffDeadlinePassed_allowsFlush() {
        assertEquals(FlushBlock.NONE,
            TelemetryGate.evaluate(ready().copy(backoffUntilMs = now), now))
    }

    /** Reported reasons are for logs; the most fundamental one should win so a
     *  log line says "disabled" rather than "no network" on a disabled kiosk. */
    @Test
    fun disabledOutranksEveryOtherBlock() {
        val everythingWrong = GateInputs(
            enabled = false, configured = false, activated = false,
            networkAvailable = false, queueDepth = 0, backoffUntilMs = now + 10_000
        )
        assertEquals(FlushBlock.DISABLED, TelemetryGate.evaluate(everythingWrong, now))
    }

    @Test
    fun configurationOutranksNetwork() {
        val inputs = ready().copy(configured = false, networkAvailable = false)
        assertEquals(FlushBlock.NOT_CONFIGURED, TelemetryGate.evaluate(inputs, now))
    }

    // ── Backoff schedule ─────────────────────────────────────────────────────

    @Test
    fun backoff_firstFailureIsShort() {
        assertEquals(60_000L, TelemetryGate.backoffDelayMs(1))
    }

    @Test
    fun backoff_doublesPerFailure() {
        assertEquals(60_000L, TelemetryGate.backoffDelayMs(1))
        assertEquals(120_000L, TelemetryGate.backoffDelayMs(2))
        assertEquals(240_000L, TelemetryGate.backoffDelayMs(3))
    }

    @Test
    fun backoff_capsAtOneHour() {
        assertEquals(TelemetryGate.MAX_BACKOFF_MS, TelemetryGate.backoffDelayMs(20))
        assertEquals(3_600_000L, TelemetryGate.MAX_BACKOFF_MS)
    }

    @Test
    fun backoff_neverOverflowsOnAbsurdFailureCounts() {
        assertEquals(TelemetryGate.MAX_BACKOFF_MS, TelemetryGate.backoffDelayMs(1_000_000))
    }

    @Test
    fun backoff_zeroOrNegativeFailuresMeansNoDelay() {
        assertEquals(0L, TelemetryGate.backoffDelayMs(0))
        assertEquals(0L, TelemetryGate.backoffDelayMs(-1))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryGateTest"`
Expected: FAIL — `Unresolved reference 'GateInputs'`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryGate.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

data class GateInputs(
    val enabled: Boolean,
    val configured: Boolean,
    val activated: Boolean,
    val networkAvailable: Boolean,
    val queueDepth: Int,
    val backoffUntilMs: Long
)

enum class FlushBlock {
    NONE,
    DISABLED,
    NOT_CONFIGURED,
    NOT_ACTIVATED,
    NO_NETWORK,
    EMPTY_QUEUE,
    BACKING_OFF
}

/**
 * Decides whether telemetry may be sent right now, and how long to wait after a
 * failure. Pure so the policy can be exercised without a device, which is the
 * same split `recovery/` uses.
 */
object TelemetryGate {
    const val MAX_BACKOFF_MS = 60L * 60 * 1000
    private const val BASE_BACKOFF_MS = 60L * 1000

    /**
     * Checks run most-fundamental first so the reason reported in a log names the
     * real problem: a disabled kiosk should say "disabled", not "no network".
     */
    fun evaluate(inputs: GateInputs, nowMs: Long): FlushBlock = when {
        !inputs.enabled -> FlushBlock.DISABLED
        !inputs.configured -> FlushBlock.NOT_CONFIGURED
        !inputs.activated -> FlushBlock.NOT_ACTIVATED
        !inputs.networkAvailable -> FlushBlock.NO_NETWORK
        inputs.queueDepth <= 0 -> FlushBlock.EMPTY_QUEUE
        nowMs < inputs.backoffUntilMs -> FlushBlock.BACKING_OFF
        else -> FlushBlock.NONE
    }

    /** Exponential from one minute, capped at an hour. Shifting is bounded before
     *  it is applied so a runaway failure count cannot overflow into a negative. */
    fun backoffDelayMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        val steps = minOf(consecutiveFailures - 1, 20)
        val delay = BASE_BACKOFF_MS shl steps
        return minOf(delay, MAX_BACKOFF_MS)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryGateTest"`
Expected: PASS, 15 tests.

- [ ] **Step 5: Run the whole suite and build**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL. 115 tests existed before this plan; 66 are added here, for 181.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryGate.kt app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryGateTest.kt
git commit -F - <<'EOF'
Add the telemetry flush gate

Pure policy for whether telemetry may be sent now, plus an exponential
backoff capped at an hour. Block reasons are ordered most-fundamental
first so a log names the real problem.
EOF
```

---

## Verification

After all tasks:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Expected: BUILD SUCCESSFUL, 181 tests passing (115 existing + 66 new).

Then confirm the phase really is inert — nothing in the app references it yet:

```bash
grep -rn "telemetry\." app/src/main/java/com/sadaqah/kiosk --include=*.kt | grep -v "^app/src/main/java/com/sadaqah/kiosk/telemetry/"
```

Expected: no output. If anything matches, a task wired the package into the app, which is out of scope for this phase.

## Out of scope for this phase

These come in later plans and must not be built here:

- `TelemetryCredentials` (AndroidKeyStore), `TelemetryUploader` (Supabase REST), `TelemetryManager` (scheduling)
- Any instrumentation — no donation, diagnostic or crash event is emitted yet
- `AnalyticsSettingsScreen`, `DisclosureScreen`, translations
- `Settings` fields (`kioskCode`, `installId`, `analyticsEnabled`, `analyticsActivatedAtMs`, the policy URLs)
- README reference schema
