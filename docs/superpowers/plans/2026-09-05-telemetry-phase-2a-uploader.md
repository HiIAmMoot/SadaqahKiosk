# Telemetry Phase 2a — Supabase Uploader and HTTP Seam

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the piece that turns a batch of queued events into Supabase inserts — batching, idempotency, response interpretation and poison-row handling — with every decision unit-testable and no network required.

**Architecture:** The existing `GitHubReleasesClient` mixes policy and transport in one `httpGet`, which is why none of it can be tested. This plan splits them: a one-method `HttpPoster` seam carries bytes, and `TelemetryUploader` holds all the judgement — grouping a mixed batch by table, setting the idempotency header, deciding which events to remove from the outbox, and isolating a permanently-rejected row so it cannot stall the queue behind it. Tests drive the uploader through a fake poster; the real `UrlConnectionPoster` is a thin adapter with no branching worth testing.

**Tech Stack:** Kotlin 2.0.21, `java.net.HttpURLConnection` (JDK), Gson, JUnit 4. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md`

## Global Constraints

- **No new Gradle dependencies.**
- **`TelemetryUploader` and `HttpPoster` must not import `android.*`** so they stay JVM-testable. Only `UrlConnectionPoster` may, and only for logging — prefer no logging at all there.
- **This phase stays inert.** Nothing calls the uploader yet; no existing file is modified. Wiring arrives in phase 2b.
- **Every insert uses `Prefer: resolution=ignore-duplicates`** with the client-generated `id`, so retrying after an ambiguous network failure cannot double-count a donation.
- **Batch reads are 100 events** (`TelemetryOutbox.DEFAULT_BATCH`), and one batch may mix tables — group by `table`, one request per table.
- **A permanently-rejected row must never stall the queue, and a configuration fault must never empty it.** *(Superseded during execution — see "Deviations during execution" at the foot of this file. The original wording, "on a 4xx for a batch, retry the rows individually and report the ones that fail so the caller can drop exactly those", is not what shipped and must not be restored: a 4xx range includes 401/403/404, so it deletes a whole queue over a wrong key.)* What shipped: only an explicit allowlist of row-level refusals — **400, 409, 413, 422** — may be reported as permanent; everything else, including every other 4xx, is retryable. A batch refused with one of those is retried row by row to isolate the bad row, and those individual rejections are honoured **only if at least one row in the group succeeded**.
- **A 5xx or a transport failure is retryable** — those events stay queued.
- **The anon key is a header value, never a query parameter**, so it does not land in server logs or redirects.
- Kotlin 2.0.21, JUnit 4, minSdk 30.
- Codebase style: self-documenting names; comments explain non-obvious *why*, never *what*; no commented-out code.
- **Never commit or push to `master`.** Work happens on a branch off `telemetry/phase-1`; a hook blocks pushes to master.

## Context: what already exists

From phase 1, on branch `telemetry/phase-1` (read these; do not modify them):

- `telemetry/TelemetryOutbox.kt` — `data class QueuedEvent(val id: String, val table: String, val payload: String, val queuedAtMs: Long)`, and `peek(limit: Int = DEFAULT_BATCH): List<QueuedEvent>`, `remove(ids: Set<String>)`. `payload` is a complete, already-redacted JSON object string.
- `telemetry/TelemetryEvent.kt` — `TelemetryTables.DONATIONS` / `.DIAGNOSTICS` / `.ACTIVATIONS` hold the three table names.
- `telemetry/TelemetryGate.kt` — unrelated to this plan.

## File Structure

| File | Responsibility |
|---|---|
| `telemetry/HttpPoster.kt` | The seam. One method, carries bytes, knows nothing about telemetry. |
| `telemetry/UrlConnectionPoster.kt` | The real adapter over `HttpURLConnection`. Deliberately branch-free. |
| `telemetry/TelemetryUploader.kt` | All upload policy: grouping, headers, response interpretation, poison isolation. |

The split exists so the third file — the only one with decisions in it — can be exercised without a socket.

---

### Task 1: The HTTP seam and its real adapter

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/HttpPoster.kt`
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/UrlConnectionPoster.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/HttpPosterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class HttpResponse(val code: Int, val body: String?)`
  - `fun interface HttpPoster { fun post(url: String, headers: Map<String, String>, body: String): HttpResponse }`
  - `HttpResponse.TRANSPORT_FAILURE: Int` = `-1`
  - `class UrlConnectionPoster(connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 30_000) : HttpPoster`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/HttpPosterTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.*
import org.junit.Test

/**
 * The seam itself has almost no behaviour — these pin the contract that
 * TelemetryUploader is written against, so a future adapter cannot quietly
 * change what a transport failure looks like.
 */
class HttpPosterTest {

    @Test
    fun transportFailureIsADistinctSentinel() {
        // Any real HTTP status is >= 100, so -1 can never collide with one.
        assertTrue(HttpResponse.TRANSPORT_FAILURE < 100)
    }

    @Test
    fun aPosterCanBeSubstituted() {
        val recorded = mutableListOf<Triple<String, Map<String, String>, String>>()
        val fake = HttpPoster { url, headers, body ->
            recorded += Triple(url, headers, body)
            HttpResponse(201, null)
        }

        val response = fake.post("https://example.invalid/rest/v1/t", mapOf("k" to "v"), "[]")

        assertEquals(201, response.code)
        assertEquals(1, recorded.size)
        assertEquals("https://example.invalid/rest/v1/t", recorded[0].first)
        assertEquals("v", recorded[0].second["k"])
        assertEquals("[]", recorded[0].third)
    }

    @Test
    fun responseBodyMayBeAbsent() {
        assertNull(HttpResponse(204, null).body)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.HttpPosterTest"`
Expected: FAIL — `Unresolved reference 'HttpResponse'`.

- [ ] **Step 3: Write the seam**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/HttpPoster.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

data class HttpResponse(val code: Int, val body: String?) {
    val isSuccess: Boolean get() = code in 200..299

    /** SUPERSEDED — see "Deviations during execution". Shipped as an allowlist:
     *  `code in setOf(400, 409, 413, 422)`. A 4xx *range* also swallows 401, 403
     *  and 404, which are statements about the endpoint, not the row. */
    val isPermanentRejection: Boolean get() = code in 400..499

    companion object {
        /** No HTTP status at all — DNS, socket, timeout. Distinct from any real
         *  status, which is always >= 100, so callers can treat it as retryable
         *  without special-casing null. */
        const val TRANSPORT_FAILURE = -1
    }
}

/**
 * Carries bytes and nothing else.
 *
 * It exists so upload *policy* can be tested without a socket: the decisions
 * live in [TelemetryUploader], and this interface is the only part that has to
 * touch the network. Implementations must never throw — a failed request comes
 * back as [HttpResponse.TRANSPORT_FAILURE].
 */
fun interface HttpPoster {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}
```

- [ ] **Step 4: Write the real adapter**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/UrlConnectionPoster.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import java.net.HttpURLConnection
import java.net.URL

/**
 * The only part of the upload path that touches the network.
 *
 * Deliberately branch-free beyond success-versus-failure: every decision worth
 * testing lives in [TelemetryUploader], because this class cannot be exercised
 * without a socket.
 */
class UrlConnectionPoster(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000
) : HttpPoster {

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                for ((name, value) in headers) setRequestProperty(name, value)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            // Supabase returns the failure reason on the error stream, and it is
            // the only clue an operator gets about a misconfigured endpoint.
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            HttpResponse(code, text)
        } catch (e: Exception) {
            HttpResponse(HttpResponse.TRANSPORT_FAILURE, e.message)
        } finally {
            conn?.disconnect()
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.HttpPosterTest"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/HttpPoster.kt app/src/main/java/com/sadaqah/kiosk/telemetry/UrlConnectionPoster.kt app/src/test/java/com/sadaqah/kiosk/telemetry/HttpPosterTest.kt
git commit -F - <<'EOF'
Add an HTTP seam for telemetry uploads

Splits transport from policy so the uploader's batching and error
handling can be tested without a socket. The existing GitHubReleasesClient
mixes the two, which is why none of it is testable.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5UZ4ccGj1Ekpj8S4UpSEE
EOF
```

---

### Task 2: The uploader

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryUploader.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryUploaderTest.kt`

**Interfaces:**
- Consumes: `HttpPoster`, `HttpResponse` from Task 1; `QueuedEvent` from `TelemetryOutbox.kt`.
- Produces:
  - `data class UploadOutcome(val uploadedIds: Set<String>, val rejectedIds: Set<String>, val retryableFailure: Boolean, val lastError: String?)`
  - `class TelemetryUploader(baseUrl: String, anonKey: String, poster: HttpPoster)`
  - `fun upload(events: List<QueuedEvent>): UploadOutcome`

**Design notes the implementer needs:**

`uploadedIds` and `rejectedIds` are both safe to remove from the outbox — one succeeded, the other will never succeed. They are reported separately only so the caller can log the difference. `retryableFailure` being true means at least one table's events should stay queued and the caller should back off.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryUploaderTest.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TelemetryUploaderTest {

    private val baseUrl = "https://proj.supabase.co"
    private val anonKey = "anon-key-123"

    /** Records every request so tests can assert on what was actually sent. */
    private class RecordingPoster(
        private val respond: (String, String) -> HttpResponse = { _, _ -> HttpResponse(201, null) }
    ) : HttpPoster {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            calls += Triple(url, headers, body)
            return respond(url, body)
        }
    }

    private fun event(id: String, table: String = TelemetryTables.DONATIONS) =
        QueuedEvent(id, table, """{"id":"$id","amount_cents":100}""", 1_000L)

    private fun uploader(poster: HttpPoster) = TelemetryUploader(baseUrl, anonKey, poster)

    // ── Request shape ────────────────────────────────────────────────────────

    @Test
    fun postsToTheRestEndpointForTheTable() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        assertEquals("https://proj.supabase.co/rest/v1/donation_events", poster.calls[0].first)
    }

    @Test
    fun toleratesABaseUrlWithATrailingSlash() {
        val poster = RecordingPoster()
        TelemetryUploader("https://proj.supabase.co/", anonKey, poster).upload(listOf(event("e1")))
        assertEquals("https://proj.supabase.co/rest/v1/donation_events", poster.calls[0].first)
    }

    @Test
    fun sendsTheAnonKeyAsHeadersNeverInTheUrl() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        val (url, headers, _) = poster.calls[0]
        assertEquals(anonKey, headers["apikey"])
        assertEquals("Bearer $anonKey", headers["Authorization"])
        assertFalse("the key must not leak into the URL", url.contains(anonKey))
    }

    @Test
    fun requestsDuplicateTolerantInserts() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        val prefer = poster.calls[0].second["Prefer"]!!
        assertTrue("retries must not double-count: $prefer",
            prefer.contains("resolution=ignore-duplicates"))
    }

    @Test
    fun sendsJsonContentType() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1")))
        assertEquals("application/json", poster.calls[0].second["Content-Type"])
    }

    @Test
    fun bodyIsAJsonArrayOfTheRawPayloads() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1"), event("e2")))
        val array = JsonParser.parseString(poster.calls[0].third).asJsonArray
        assertEquals(2, array.size())
        assertEquals("e1", array[0].asJsonObject.get("id").asString)
        assertEquals("e2", array[1].asJsonObject.get("id").asString)
    }

    // ── Batching by table ────────────────────────────────────────────────────

    @Test
    fun groupsAMixedBatchIntoOneRequestPerTable() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(
            event("d1", TelemetryTables.DONATIONS),
            event("x1", TelemetryTables.DIAGNOSTICS),
            event("d2", TelemetryTables.DONATIONS)
        ))
        assertEquals(2, poster.calls.size)
        val urls = poster.calls.map { it.first }.toSet()
        assertTrue(urls.any { it.endsWith("/donation_events") })
        assertTrue(urls.any { it.endsWith("/diagnostic_events") })
    }

    @Test
    fun emptyInputSendsNothing() {
        val poster = RecordingPoster()
        val outcome = uploader(poster).upload(emptyList())
        assertEquals(0, poster.calls.size)
        assertTrue(outcome.uploadedIds.isEmpty())
        assertFalse(outcome.retryableFailure)
    }

    // ── Success ──────────────────────────────────────────────────────────────

    @Test
    fun successReportsEveryIdAsUploaded() {
        val outcome = uploader(RecordingPoster()).upload(listOf(event("e1"), event("e2")))
        assertEquals(setOf("e1", "e2"), outcome.uploadedIds)
        assertTrue(outcome.rejectedIds.isEmpty())
        assertFalse(outcome.retryableFailure)
        assertNull(outcome.lastError)
    }

    // ── Retryable failures ───────────────────────────────────────────────────

    @Test
    fun serverErrorLeavesEventsQueuedAndFlagsRetry() {
        val poster = RecordingPoster { _, _ -> HttpResponse(503, "unavailable") }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
        assertNotNull(outcome.lastError)
    }

    @Test
    fun transportFailureLeavesEventsQueuedAndFlagsRetry() {
        val poster = RecordingPoster { _, _ ->
            HttpResponse(HttpResponse.TRANSPORT_FAILURE, "no route to host")
        }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertTrue(outcome.uploadedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }

    /** One table failing must not discard another table's successful upload. */
    @Test
    fun oneTableFailingDoesNotLoseAnotherTablesSuccess() {
        val poster = RecordingPoster { url, _ ->
            if (url.endsWith("/diagnostic_events")) HttpResponse(503, "down")
            else HttpResponse(201, null)
        }
        val outcome = uploader(poster).upload(listOf(
            event("d1", TelemetryTables.DONATIONS),
            event("x1", TelemetryTables.DIAGNOSTICS)
        ))
        assertEquals(setOf("d1"), outcome.uploadedIds)
        assertTrue(outcome.retryableFailure)
    }

    // ── Poison rows ──────────────────────────────────────────────────────────

    /**
     * The failure this exists to prevent: one permanently-rejected row sitting at
     * the head of the queue, failing its whole batch forever, while everything
     * behind it ages out unsent.
     */
    @Test
    fun aRejectedBatchIsRetriedRowByRow() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            when {
                isBatch -> HttpResponse(400, "invalid input")
                body.contains("\"poison\"") -> HttpResponse(400, "invalid input")
                else -> HttpResponse(201, null)
            }
        }
        val outcome = uploader(poster).upload(listOf(
            event("good1"), event("poison"), event("good2")
        ))
        assertEquals(setOf("good1", "good2"), outcome.uploadedIds)
        assertEquals(setOf("poison"), outcome.rejectedIds)
        assertFalse("a poison row is permanent, not retryable", outcome.retryableFailure)
    }

    @Test
    fun singleRowRetryDoesNotRunWhenTheBatchSucceeds() {
        val poster = RecordingPoster()
        uploader(poster).upload(listOf(event("e1"), event("e2")))
        assertEquals("one batch request, no per-row fallback", 1, poster.calls.size)
    }

    /** A batch of one that is rejected needs no fallback — it is already isolated. */
    @Test
    fun aSingleRejectedRowIsNotRetried() {
        val poster = RecordingPoster { _, _ -> HttpResponse(400, "invalid") }
        val outcome = uploader(poster).upload(listOf(event("only")))
        assertEquals(1, poster.calls.size)
        assertEquals(setOf("only"), outcome.rejectedIds)
    }

    /** A 5xx during the per-row fallback is still retryable for that row. */
    @Test
    fun serverErrorDuringRowFallbackKeepsThatRowQueued() {
        val poster = RecordingPoster { _, body ->
            val isBatch = JsonParser.parseString(body).asJsonArray.size() > 1
            when {
                isBatch -> HttpResponse(400, "invalid input")
                body.contains("\"flaky\"") -> HttpResponse(503, "down")
                else -> HttpResponse(201, null)
            }
        }
        val outcome = uploader(poster).upload(listOf(event("good1"), event("flaky")))
        assertEquals(setOf("good1"), outcome.uploadedIds)
        assertTrue(outcome.rejectedIds.isEmpty())
        assertTrue(outcome.retryableFailure)
    }

    @Test
    fun lastErrorCarriesTheServerMessage() {
        val poster = RecordingPoster { _, _ -> HttpResponse(500, "boom from postgrest") }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertTrue(outcome.lastError!!.contains("boom from postgrest"))
    }

    @Test
    fun lastErrorNeverContainsTheAnonKey() {
        val poster = RecordingPoster { _, _ -> HttpResponse(401, "bad key $anonKey rejected") }
        val outcome = uploader(poster).upload(listOf(event("e1")))
        assertFalse("an error surfaced in the UI must not expose the key",
            outcome.lastError!!.contains(anonKey))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryUploaderTest"`
Expected: FAIL — `Unresolved reference 'TelemetryUploader'`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryUploader.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

/**
 * What happened to one batch.
 *
 * [uploadedIds] and [rejectedIds] are both safe to remove from the outbox — the
 * first succeeded, the second never will. They are reported apart so the caller
 * can log the difference rather than lose it.
 */
data class UploadOutcome(
    val uploadedIds: Set<String>,
    val rejectedIds: Set<String>,
    val retryableFailure: Boolean,
    val lastError: String?
)

/**
 * Turns queued events into Supabase inserts.
 *
 * All the judgement lives here rather than in the transport, so it can be
 * exercised without a socket: which rows go in which request, what a response
 * means, and how to stop one bad row from stalling everything behind it.
 */
class TelemetryUploader(
    baseUrl: String,
    private val anonKey: String,
    private val poster: HttpPoster
) {
    private val restRoot = baseUrl.trimEnd('/') + "/rest/v1/"

    fun upload(events: List<QueuedEvent>): UploadOutcome {
        if (events.isEmpty()) return UploadOutcome(emptySet(), emptySet(), false, null)

        val uploaded = mutableSetOf<String>()
        val rejected = mutableSetOf<String>()
        var retryable = false
        var lastError: String? = null

        // One request per table: a mixed batch cannot go to a single endpoint,
        // and a failure in one table must not discard another table's success.
        for ((table, forTable) in events.groupBy { it.table }) {
            val response = send(table, forTable)
            when {
                response.isSuccess -> uploaded += forTable.map { it.id }

                // A rejected batch may contain exactly one bad row. Retrying the
                // rows individually isolates it, so the rest of the queue is not
                // held hostage by a row that will never be accepted.
                response.isPermanentRejection && forTable.size > 1 -> {
                    for (event in forTable) {
                        val single = send(table, listOf(event))
                        when {
                            // SUPERSEDED — see "Deviations during execution".
                            // What shipped collects into group-local sets, breaks
                            // out on a retryable row, and discards the group's
                            // rejections entirely unless some row succeeded.
                            single.isSuccess -> uploaded += event.id
                            single.isPermanentRejection -> rejected += event.id
                            else -> {
                                retryable = true
                                lastError = describe(single)
                            }
                        }
                    }
                }

                response.isPermanentRejection -> {
                    rejected += forTable.map { it.id }
                    lastError = describe(response)
                }

                else -> {
                    retryable = true
                    lastError = describe(response)
                }
            }
        }

        return UploadOutcome(uploaded, rejected, retryable, lastError)
    }

    private fun send(table: String, events: List<QueuedEvent>): HttpResponse =
        poster.post(
            url = restRoot + table,
            headers = mapOf(
                "apikey" to anonKey,
                "Authorization" to "Bearer $anonKey",
                "Content-Type" to "application/json",
                // The client generates each id, so a retry after an ambiguous
                // failure is absorbed rather than double-counting a donation.
                "Prefer" to "return=minimal,resolution=ignore-duplicates"
            ),
            body = events.joinToString(",", prefix = "[", postfix = "]") { it.payload }
        )

    /** Surfaced to the operator in Settings, so it must never carry the key. */
    private fun describe(response: HttpResponse): String {
        val body = response.body?.replace(anonKey, "[redacted]") ?: ""
        return "HTTP ${response.code} $body".trim()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.telemetry.TelemetryUploaderTest"`
Expected: PASS, 18 tests.

- [ ] **Step 5: Run the whole suite and build**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL. 199 tests existed before this plan; 21 are added here, for 220.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryUploader.kt app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryUploaderTest.kt
git commit -F - <<'EOF'
Add the telemetry uploader

Groups a mixed batch by table, one request each, with idempotent inserts
so a retry after an ambiguous failure cannot double-count a donation.
A rejected batch is retried row by row, so one permanently-invalid event
cannot stall the queue behind it.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5UZ4ccGj1Ekpj8S4UpSEE
EOF
```

---

## Verification

After both tasks:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Expected: BUILD SUCCESSFUL, 220 tests (199 existing + 21 new).

Confirm the phase is still inert — nothing calls the uploader yet:

```bash
grep -rn "TelemetryUploader\|UrlConnectionPoster" app/src/main/java/com/sadaqah/kiosk --include=*.kt | grep -v "/telemetry/"
```

Expected: no output.

## What this deliberately does not do

These belong to phase 2b, which is where telemetry stops being inert:

- `TelemetryCredentials` — AndroidKeyStore storage of the URL and anon key
- `TelemetryManager` — flush scheduling, backoff bookkeeping, wiring to `MainActivity`'s connectivity poll and 02:00 window
- `AnalyticsSettingsScreen` and its translations
- `Settings` fields (`kioskCode`, `installId`, `analyticsEnabled`, `analyticsActivatedAtMs`, the policy URLs)
- `TelemetryOutbox.clear()`, needed so clearing credentials does not strand identified donation data on disk
- A test pinning that two `TelemetryOutbox` instances over one path share a lock

Nothing here can be verified against a real Supabase project until 2b supplies credentials and a caller. `UrlConnectionPoster` in particular has no test and is verified only by inspection — that is the deliberate cost of putting every decision on the other side of the seam.

## Deviations during execution

Phase 2b is written against this file, so where the code that shipped disagrees with the plan above,
this section is what binds. Everything here moves in one direction: the outbox is the only copy of a
donation record, so a caller that is told "rejected" deletes it. A stalled row is visible and
reversible; a deleted row is neither.

**The 4xx range is not the rejection rule. An allowlist is.** `isPermanentRejection` is
`code in setOf(400, 409, 413, 422)` — malformed, conflict, too large, unprocessable. Every other
status, including the rest of the 4xx range, is retryable. 401 and 403 mean the key is wrong or
rotated and 404 means the table is missing; none of them is a statement about the row, and treating
them as permanent deletes an entire queue of donations over a configuration error. An allowlist also
fails safe for statuses nobody has thought about yet, which is the right default when the cost of a
wrong "permanent" is a lost record and the cost of a wrong "retryable" is disk.

**A batch's row-by-row fallback is only believed when a row got through.** The fallback exists on one
hypothesis: a refused batch may contain exactly one bad row. Rejections found during that pass are
therefore reported only if at least one row in the same table group succeeded — the success is what
demonstrates the endpoint and the schema are fine and the refusal really is about the row. If every
row is refused, or the group stalls on a network failure before any row succeeds, the group reports
**zero** rejections and sets `retryableFailure` instead. PostgREST answers 400 for a stale schema
cache and for an unknown column (`PGRST204`); those are fleet-wide, identical for every row, and they
clear on a reload — under the original rule a batch of 100 would come back as 100 "verified"
rejections and 100 donations would be deleted that the server never accepted.

A group of exactly one refused row is unaffected and is still dropped: there is no sibling row whose
success could contradict the refusal, so the refusal is the only evidence there is.

**The fallback stops at the first retryable row.** When the network drops mid-fallback, every
remaining row would burn a full connect-plus-read timeout and stay queued regardless. The loop breaks
and the next flush retries them.

**`lastError` is captured in the fallback's rejected branch**, from the individual row's response
rather than the batch's — that branch is the one that ends in a deleted donation, so it is the one
whose reason has to survive. It goes through `TelemetryRedactor.scrub` then `truncate`, in that order,
so a byte-level cut cannot bisect a secret into a surviving half.

**The transport refuses redirects.** `instanceFollowRedirects = false`: the request headers carry the
project key, `HttpURLConnection` replays every header onto a redirect target, and a redirect target is
not the host we authenticated to. A 3xx comes back as an ordinary non-success status, which is
retryable. Phase 2b's https-scheme check depends on this — without it, a configured `https://`
endpoint that redirects to `http://` sends the key in cleartext and the scheme check buys nothing.
