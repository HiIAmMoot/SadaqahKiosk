# Telemetry Phase 2b — Credentials and the Flush Manager

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store the Supabase destination securely, add the settings fields that identify a kiosk, and build the manager that decides when to flush and what to do with the result — all headless and JVM-testable.

**Architecture:** Two seams, matching the one phase 2a used for HTTP. `SecretStore` hides AndroidKeyStore so credential *policy* is testable without a device; `TelemetryStatusStore` hides persistence so the manager's bookkeeping is testable without SharedPreferences. `TelemetryManager` is the only class that knows the order of operations: ask the gate, peek the outbox, upload, delete what was accounted for, record the outcome.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, Gson, AndroidKeyStore, minSdk 30. No new third-party dependencies.

**Spec:** `docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md`

## Global Constraints

- **No new third-party dependencies.** Gson and the Android platform only.
- **Ships inert.** Nothing enqueues a donation and nothing flushes automatically at the end of this phase. `TelemetryManager` is constructed by nobody yet; wiring is 2c/phase 3. The app must behave identically with this merged.
- **No secret in a log, a `toString`, or a stored error string.** The Supabase anon key and the SumUp affiliate key are the secrets. Any class holding a key must not be a `data class`.
- **A row may be deleted only when a sibling row in the same request succeeded against the same endpoint in the same flush.** Phase 2a's invariant. This phase consumes `UploadOutcome`; it must not add a second deletion path or re-derive the rule.
- **The destination must be `https`.** A kiosk sending an anon key over cleartext is a credential leak. Reject `http` at the point of entry, not at the point of use.
- **`installId` is a random UUID, never a hardware identifier.** No `ANDROID_ID`, no IMEI, no MAC.
- **`kioskCode` validation is advisory.** A fork with no code scheme is a supported deployment. Warn, never reject.
- **Pure files must not import `android.*`.** Only `KeystoreSecretStore` and `PrefsStatusStore` may, and they stay branch-free.
- Codebase style: self-documenting names; comments explain non-obvious *why*, never *what*; no commented-out code.
- **Never commit or push to `master`.** Work happens on `telemetry/phase-2b`, branched from `telemetry/phase-2a`.

## Context: what already exists

From phases 1 and 2a (read these; do not modify them except where a task says so):

- `telemetry/TelemetryOutbox.kt` — `class TelemetryOutbox(file, maxEvents=5000, maxAgeMs=30d, compactSlack=100, clock)`, with `append(id, table, payload)`, `peek(limit = DEFAULT_BATCH): List<QueuedEvent>`, `remove(ids: Set<String>)`, `size(): Int`. `QueuedEvent(id, table, payload, queuedAtMs)`. Locking is keyed on the canonical file path, not the instance.
- `telemetry/TelemetryGate.kt` — `GateInputs(enabled, configured, activated, networkAvailable, queueDepth, backoffUntilMs)`, `enum FlushBlock { NONE, DISABLED, NOT_CONFIGURED, NOT_ACTIVATED, NO_NETWORK, EMPTY_QUEUE, BACKING_OFF }`, `TelemetryGate.evaluate(inputs, nowMs): FlushBlock`, `TelemetryGate.backoffDelayMs(consecutiveFailures): Long`, `MAX_BACKOFF_MS`.
- `telemetry/TelemetryEvent.kt` — `EventIdentity(code, installId, appVersion)`, `TelemetryEvent.Activation(identity, activatedAtIso, privacyPolicyUrl, termsUrl, id)`, `TelemetryEvent.nowIso()`, `payloadJson()`, `TelemetryTables.DONATIONS/.DIAGNOSTICS/.ACTIVATIONS`.
- `telemetry/TelemetryUploader.kt` — `class TelemetryUploader(baseUrl, anonKey, poster)`, `upload(events): UploadOutcome`. `UploadOutcome(uploadedIds, rejectedIds, retryableFailure, lastError)`.
- `telemetry/HttpPoster.kt` / `UrlConnectionPoster.kt` — the transport seam.
- `settingsio/SecretsCrypto.kt` — `SecretsCrypto.encrypt(plaintext, password, iterations)` / `decrypt(envelope, password): String?`, `SecretsEnvelope`. Used by the export path; **this phase does not touch it**.
- `model/Settings.kt` — the `data class Settings` this phase extends.
- `MainActivity.onCreate` — holds the settings migration block that detects absent keys against the raw JSON string (see `autoUpdateEnabled` and friends around line 219).

## File Structure

| File | Responsibility |
|---|---|
| `telemetry/SecretStore.kt` | The seam: `put`/`get`/`remove` of opaque strings. Plus `InMemorySecretStore` for tests. |
| `telemetry/KeystoreSecretStore.kt` | The real adapter: AES-GCM under an AndroidKeyStore key, ciphertext in `SharedPreferences`. Branch-free. |
| `telemetry/TelemetryCredentials.kt` | Destination policy: validation, normalisation, what "configured" means, clearing. |
| `telemetry/TelemetryStatusStore.kt` | The seam for flush bookkeeping, plus its in-memory implementation. |
| `telemetry/TelemetryManager.kt` | Order of operations: gate, peek, upload, delete, record. Activation. |
| `model/Settings.kt` | Six new fields. |
| `MainActivity.kt` | Migration for those fields, and minting `installId` on first run. |
| `telemetry/TelemetryOutbox.kt` | Gains `clear()`. |

The split keeps every decision in a file with no `android.*` import, so the whole phase is exercised by JVM unit tests.

---

### Task 1: The secret store seam and its Keystore adapter

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/SecretStore.kt`
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/KeystoreSecretStore.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/SecretStoreTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface SecretStore { fun put(key: String, value: String); fun get(key: String): String?; fun remove(key: String) }`
  - `class InMemorySecretStore : SecretStore`
  - `class KeystoreSecretStore(context: Context, prefsName: String = "telemetry_secrets") : SecretStore`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretStoreTest {
    @Test
    fun storesAndReturnsAValue() {
        val store = InMemorySecretStore()
        store.put("url", "https://x.supabase.co")
        assertEquals("https://x.supabase.co", store.get("url"))
    }

    @Test
    fun anAbsentKeyIsNull() {
        assertNull(InMemorySecretStore().get("nothing"))
    }

    @Test
    fun removeDeletesOnlyItsOwnKey() {
        val store = InMemorySecretStore()
        store.put("url", "https://x.supabase.co")
        store.put("key", "anon")
        store.remove("url")
        assertNull(store.get("url"))
        assertEquals("anon", store.get("key"))
    }

    /** The fake must not be quietly more forgiving than the real store, or every
     *  test above it is testing a store the device never uses. */
    @Test
    fun putOverwritesRatherThanAccumulating() {
        val store = InMemorySecretStore()
        store.put("key", "first")
        store.put("key", "second")
        assertEquals("second", store.get("key"))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "*SecretStoreTest*"`
Expected: FAIL — `Unresolved reference: InMemorySecretStore`.

- [ ] **Step 3: Write `SecretStore.kt`**

```kotlin
package com.sadaqah.kiosk.telemetry

/**
 * Opaque string storage that is expected to be encrypted at rest.
 *
 * It exists so credential *policy* — what a valid destination is, what counts as
 * configured, what clearing must remove — can be tested without a device. The
 * Keystore is an implementation detail behind this line, and the only class that
 * touches it stays branch-free.
 *
 * Implementations must never throw: a device whose Keystore has been invalidated
 * behaves as though nothing was stored, which reads as "not configured" and stops
 * telemetry rather than crashing a kiosk mid-donation.
 */
interface SecretStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
}

/** For tests, and for a device where no encrypted store can be constructed. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun put(key: String, value: String) { values[key] = value }
    override fun get(key: String): String? = values[key]
    override fun remove(key: String) { values.remove(key) }
}
```

- [ ] **Step 4: Write `KeystoreSecretStore.kt`**

Deliberately branch-free, and it swallows nothing silently except by returning `null`, which the layer above already treats as "not configured".

```kotlin
package com.sadaqah.kiosk.telemetry

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM under a hardware-backed AndroidKeyStore key, ciphertext in
 * SharedPreferences.
 *
 * This defeats `adb pull`, cloud backup extraction and offline attack on a stolen
 * device. It does NOT defeat root on a running kiosk — any key the app can decrypt
 * in order to use it, an attacker in that position can also read. The real
 * boundary is the insert-only RLS policy on the server, which is why this class is
 * allowed to be this simple.
 */
class KeystoreSecretStore(
    context: Context,
    prefsName: String = "telemetry_secrets"
) : SecretStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ciphertext
        prefs.edit().putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    /** Returns null on any failure. A Keystore key is invalidated by a factory
     *  reset or a lock-screen change, and the stored ciphertext then decrypts to
     *  nothing forever. Reporting that as "absent" makes the kiosk ask to be
     *  reconfigured instead of crashing on every flush. */
    override fun get(key: String): String? = try {
        val packed = Base64.decode(prefs.getString(key, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES)
        )
        String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sadaqah_telemetry_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "*SecretStoreTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Prove the adapter is not referenced by the pure layer**

Run: `grep -rn "android\." app/src/main/java/com/sadaqah/kiosk/telemetry/SecretStore.kt`
Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/SecretStore.kt \
        app/src/main/java/com/sadaqah/kiosk/telemetry/KeystoreSecretStore.kt \
        app/src/test/java/com/sadaqah/kiosk/telemetry/SecretStoreTest.kt
git commit -m "Add the telemetry secret store seam and its Keystore adapter"
```

---

### Task 2: `TelemetryCredentials` — destination policy

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryCredentials.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryCredentialsTest.kt`

**Interfaces:**
- Consumes: `SecretStore`, `InMemorySecretStore`.
- Produces:
  - `class TelemetryConfig(val baseUrl: String, val anonKey: String)` — **not** a data class.
  - `sealed class UrlVerdict { data class Valid(val normalised: String); data class Invalid(val reason: String) }`
  - `object TelemetryUrl { fun check(raw: String): UrlVerdict }`
  - `class TelemetryCredentials(store: SecretStore)` with `load(): TelemetryConfig?`, `save(baseUrl: String, anonKey: String): UrlVerdict`, `clear()`, `isConfigured(): Boolean`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCredentialsTest {

    private fun credentials() = TelemetryCredentials(InMemorySecretStore())

    @Test
    fun anHttpsUrlIsAccepted() {
        val verdict = TelemetryUrl.check("https://abc.supabase.co")
        assertTrue(verdict is UrlVerdict.Valid)
        assertEquals("https://abc.supabase.co", (verdict as UrlVerdict.Valid).normalised)
    }

    /**
     * The anon key travels as a request header. Over cleartext it is readable by
     * anything between the kiosk and the server, and a mosque's wifi is not a
     * controlled network. Rejecting at entry means the operator finds out at the
     * bench rather than never.
     */
    @Test
    fun anHttpUrlIsRejected() {
        val verdict = TelemetryUrl.check("http://abc.supabase.co")
        assertTrue(verdict is UrlVerdict.Invalid)
        assertTrue((verdict as UrlVerdict.Invalid).reason.contains("https", ignoreCase = true))
    }

    @Test
    fun aTrailingSlashIsNormalisedAway() {
        val verdict = TelemetryUrl.check("https://abc.supabase.co/")
        assertEquals("https://abc.supabase.co", (verdict as UrlVerdict.Valid).normalised)
    }

    @Test
    fun surroundingWhitespaceIsTrimmedBecauseOperatorsPaste() {
        val verdict = TelemetryUrl.check("  https://abc.supabase.co  ")
        assertEquals("https://abc.supabase.co", (verdict as UrlVerdict.Valid).normalised)
    }

    @Test
    fun aUrlWithNoHostIsRejected() {
        assertTrue(TelemetryUrl.check("https://") is UrlVerdict.Invalid)
    }

    @Test
    fun blankIsRejected() {
        assertTrue(TelemetryUrl.check("   ") is UrlVerdict.Invalid)
    }

    @Test
    fun somethingThatIsNotAUrlIsRejectedRatherThanThrowing() {
        assertTrue(TelemetryUrl.check("not a url at all") is UrlVerdict.Invalid)
    }

    @Test
    fun savingThenLoadingReturnsTheNormalisedDestination() {
        val creds = credentials()
        assertTrue(creds.save("https://abc.supabase.co/", " anon-key ") is UrlVerdict.Valid)
        val config = creds.load()!!
        assertEquals("https://abc.supabase.co", config.baseUrl)
        assertEquals("anon-key", config.anonKey)
    }

    @Test
    fun anInvalidUrlIsNotStored() {
        val creds = credentials()
        assertTrue(creds.save("http://abc.supabase.co", "anon-key") is UrlVerdict.Invalid)
        assertNull("a rejected destination must not be half-written", creds.load())
        assertFalse(creds.isConfigured())
    }

    @Test
    fun aBlankKeyIsRejectedAndNotStored() {
        val creds = credentials()
        assertTrue(creds.save("https://abc.supabase.co", "  ") is UrlVerdict.Invalid)
        assertNull(creds.load())
    }

    @Test
    fun loadIsNullWhenOnlyOneHalfIsPresent() {
        val store = InMemorySecretStore()
        store.put("telemetry_base_url", "https://abc.supabase.co")
        assertNull("half a destination is not a destination", TelemetryCredentials(store).load())
    }

    @Test
    fun clearRemovesBothHalves() {
        val store = InMemorySecretStore()
        val creds = TelemetryCredentials(store)
        creds.save("https://abc.supabase.co", "anon-key")
        creds.clear()
        assertNull(creds.load())
        assertFalse(creds.isConfigured())
        assertNull("the key must not outlive the url", store.get("telemetry_anon_key"))
    }

    /** A generated toString on a credential holder is how keys reach logcat. */
    @Test
    fun theConfigDoesNotPrintItsKey() {
        val config = TelemetryConfig("https://abc.supabase.co", "super-secret-anon-key")
        assertFalse(config.toString().contains("super-secret-anon-key"))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "*TelemetryCredentialsTest*"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.sadaqah.kiosk.telemetry

import java.net.URI

/**
 * Where telemetry goes and what authenticates it.
 *
 * Deliberately not a data class: a generated toString would print the anon key,
 * and one `Log.d(TAG, "$config")` is all it takes to put a credential in logcat.
 */
class TelemetryConfig(val baseUrl: String, val anonKey: String) {
    override fun toString(): String = "TelemetryConfig(baseUrl=$baseUrl, anonKey=[redacted])"
}

sealed class UrlVerdict {
    data class Valid(val normalised: String) : UrlVerdict()
    data class Invalid(val reason: String) : UrlVerdict()
}

/**
 * Validation for the destination the operator types.
 *
 * The reason strings are operator-facing: they are shown on the settings screen,
 * so they name what to fix rather than what failed internally.
 */
object TelemetryUrl {
    fun check(raw: String): UrlVerdict {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return UrlVerdict.Invalid("Enter the Supabase project URL.")

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return UrlVerdict.Invalid("That is not a valid URL.")
        }

        // Cleartext would put the anon key on the wire in the clear, and a mosque's
        // wifi is not a controlled network. Checked here, at entry, so the operator
        // learns at the bench rather than from a kiosk that never reported.
        if (!"https".equals(uri.scheme, ignoreCase = true)) {
            return UrlVerdict.Invalid("The URL must start with https://")
        }
        if (uri.host.isNullOrBlank()) return UrlVerdict.Invalid("That URL has no host.")

        return UrlVerdict.Valid(trimmed)
    }
}

/**
 * Reads and writes the destination through a [SecretStore].
 *
 * Both halves are written together or not at all: a destination with a URL and no
 * key is indistinguishable from a misconfiguration, and the gate would report it
 * as configured and then fail every flush.
 */
class TelemetryCredentials(private val store: SecretStore) {

    fun load(): TelemetryConfig? {
        val url = store.get(KEY_URL)?.takeIf { it.isNotBlank() } ?: return null
        val key = store.get(KEY_ANON)?.takeIf { it.isNotBlank() } ?: return null
        return TelemetryConfig(url, key)
    }

    /** Validates before writing anything, so a rejected destination leaves the
     *  previous one intact rather than half-replacing it. */
    fun save(baseUrl: String, anonKey: String): UrlVerdict {
        val key = anonKey.trim()
        if (key.isBlank()) return UrlVerdict.Invalid("Enter the anon key.")
        val verdict = TelemetryUrl.check(baseUrl)
        if (verdict !is UrlVerdict.Valid) return verdict

        store.put(KEY_URL, verdict.normalised)
        store.put(KEY_ANON, key)
        return verdict
    }

    fun clear() {
        store.remove(KEY_URL)
        store.remove(KEY_ANON)
    }

    fun isConfigured(): Boolean = load() != null

    private companion object {
        const val KEY_URL = "telemetry_base_url"
        const val KEY_ANON = "telemetry_anon_key"
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "*TelemetryCredentialsTest*"`
Expected: PASS, 13 tests.

- [ ] **Step 5: Mutation-check the two that matter**

This codebase has shipped tests that passed against the pre-fix code. Prove these two do not:
1. Change the scheme check to accept `http` as well. `anHttpUrlIsRejected` must FAIL. Restore.
2. Make `TelemetryConfig` a `data class`. `theConfigDoesNotPrintItsKey` must FAIL. Restore.

Record both failure messages in your report.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryCredentials.kt \
        app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryCredentialsTest.kt
git commit -m "Add telemetry destination policy and credential storage"
```

---

### Task 3: `Settings` fields, migration, and `TelemetryOutbox.clear()`

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/model/Settings.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt` (the migration block near line 219, and the `donationStatsStartedAtMs` bootstrap near line 203)
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryOutbox.kt`
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/KioskCode.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/KioskCodeTest.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryOutboxTest.kt` (add to it)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - Six `Settings` fields: `analyticsEnabled: Boolean = false`, `analyticsActivatedAtMs: Long = 0L`, `analyticsPrivacyPolicyUrl: String = ""`, `analyticsTermsUrl: String = ""`, `kioskCode: String = ""`, `installId: String = ""`.
  - `object KioskCode { const val CONVENTION = ...; fun looksConventional(code: String): Boolean }`
  - `TelemetryOutbox.clear()`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskCodeTest {
    @Test
    fun aConventionalCodeIsRecognised() {
        assertTrue(KioskCode.looksConventional("SK-0042"))
    }

    @Test
    fun lowercaseIsAcceptedBecauseOperatorsType() {
        assertTrue(KioskCode.looksConventional("sk-0042"))
    }

    @Test
    fun anUnconventionalCodeIsFlaggedButThisIsAdvisoryOnly() {
        assertFalse(KioskCode.looksConventional("mosque-front-door"))
    }

    /** A fork with no code scheme is a supported deployment. Blank must not be
     *  reported as a problem, or the warning fires on every such kiosk forever. */
    @Test
    fun blankIsNotFlagged() {
        assertTrue(KioskCode.looksConventional(""))
    }
}
```

And append to `TelemetryOutboxTest`:

```kotlin
    @Test
    fun clearEmptiesTheQueue() {
        val outbox = outbox()
        outbox.appendDonation("a")
        outbox.appendDonation("b")
        outbox.clear()
        assertEquals(0, outbox.size())
        assertTrue(outbox.peek().isEmpty())
    }

    /**
     * Clearing credentials must not strand identified data on disk: the rows name
     * a kiosk, and an operator who turns telemetry off has withdrawn the basis for
     * holding them. The file itself goes, not just its contents.
     */
    @Test
    fun clearRemovesTheFileRatherThanLeavingAnEmptyOne() {
        val outbox = outbox()
        outbox.appendDonation("a")
        outbox.clear()
        assertFalse(file.exists())
    }

    @Test
    fun clearOnAnAbsentFileIsHarmless() {
        outbox().clear()
        assertEquals(0, outbox().size())
    }

    @Test
    fun theQueueStillWorksAfterBeingCleared() {
        val outbox = outbox()
        outbox.appendDonation("a")
        outbox.clear()
        outbox.appendDonation("b")
        assertEquals(listOf("b"), outbox.peek().map { it.id })
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew testDebugUnitTest --tests "*KioskCodeTest*" --tests "*TelemetryOutboxTest*"`
Expected: FAIL — `Unresolved reference: KioskCode`, `Unresolved reference: clear`.

- [ ] **Step 3: Add `clear()` to `TelemetryOutbox`**

Place it directly after `size()`, inside the same locking discipline as its neighbours:

```kotlin
    /** Removes the queue entirely. Used when credentials are cleared: the rows
     *  name a kiosk, so leaving them on disk would strand identified data an
     *  operator has just withdrawn the basis for holding. */
    fun clear(): Unit = synchronized(lock) {
        file.delete()
    }
```

- [ ] **Step 4: Write `KioskCode.kt`**

```kotlin
package com.sadaqah.kiosk.telemetry

/**
 * The printed panel code, and the shape this vendor's codes happen to take.
 *
 * Validation is **advisory**. A fork of this app has no kiosk-code scheme and
 * never will, so rejecting a non-conforming code would mean the software only
 * runs for one vendor. With no code configured at all, `installId` identifies the
 * device, which is everything a single-site operator needs.
 *
 * The convention's source of truth is the sadaqahkiosk.nl site repository; the two
 * cannot import from each other, so it is restated here rather than shared.
 */
object KioskCode {
    /** Two letters, a hyphen, four digits — e.g. SK-0042. */
    private val CONVENTION = Regex("^[A-Za-z]{2}-\\d{4}$")

    /** Blank is conventional: it means "this deployment has no code scheme",
     *  which is supported, not a mistake to warn about on every screen. */
    fun looksConventional(code: String): Boolean =
        code.isBlank() || CONVENTION.matches(code.trim())
}
```

- [ ] **Step 5: Add the six fields to `Settings.kt`**

Append inside the `data class Settings(...)` parameter list, after `donationStatsStartedAtMs`:

```kotlin
    ,
    // Telemetry (see docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md).
    // Credentials are deliberately NOT here — they live in TelemetryCredentials so
    // they never reach the settings JSON except through the encrypted export path.
    val analyticsEnabled: Boolean = false,
    /** 0 = the disclosure has not been shown. Not proof of anything; it exists so
     *  the disclosure is not re-shown on every visit to the Analytics screen. */
    val analyticsActivatedAtMs: Long = 0L,
    val analyticsPrivacyPolicyUrl: String = "",
    val analyticsTermsUrl: String = "",
    /** Optional printed panel code. Advisory validation only — see KioskCode. */
    val kioskCode: String = "",
    /** Random UUID minted on first run. Never a hardware identifier: ANDROID_ID
     *  and friends carry restrictions and privacy baggage for no benefit here. */
    val installId: String = ""
```

- [ ] **Step 6: Add migration in `MainActivity.onCreate`**

Follow the existing raw-JSON pattern in the block around line 219. Add inside the same `if`/`dirty` structure:

```kotlin
            if (!json.contains("\"analyticsEnabled\"")) {
                migrated = migrated.copy(analyticsEnabled = false); dirty = true
            }
            // Minted once and never regenerated: a new installId on an existing
            // kiosk would read as a new device in the data and silently split its
            // history in two.
            if (migrated.installId.isBlank()) {
                migrated = migrated.copy(installId = java.util.UUID.randomUUID().toString())
                dirty = true
            }
```

- [ ] **Step 7: Run the full suite**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, zero failures.

- [ ] **Step 8: Prove nothing enqueues yet**

Run: `grep -rn "TelemetryOutbox(" app/src/main/java/com/sadaqah/kiosk/ --include=*.kt | grep -v "telemetry/"`
Expected: no output — the outbox is still constructed by nobody outside its own package.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add telemetry settings fields, kiosk code convention, and outbox clear"
```

---

### Task 4: `TelemetryManager` — the flush order of operations

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryStatusStore.kt`
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryManager.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryManagerTest.kt`

**Interfaces:**
- Consumes: `TelemetryOutbox`, `TelemetryGate`, `TelemetryCredentials`, `TelemetryUploader`, `HttpPoster`, `TelemetryEvent`, `EventIdentity`.
- Produces:
  - `data class TelemetryStatus(queued, lastSuccessMs, lastError, consecutiveFailures, backoffUntilMs)`
  - `interface TelemetryStatusStore { fun read(): TelemetryStatus; fun write(status: TelemetryStatus) }` and `class InMemoryStatusStore : TelemetryStatusStore`
  - `data class TelemetryRuntime(enabled, activated, identity, privacyPolicyUrl, termsUrl)`
  - `class TelemetryManager(outbox, credentials, statusStore, posterFor, runtime, networkAvailable, clock)`
  - `fun flush(): FlushBlock` and `fun activate(): FlushBlock`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class TelemetryManagerTest {

    @get:Rule val temp = TemporaryFolder()

    private val identity = EventIdentity("SK-0042", "install-1", "1.3.6")
    private var now = 1_000_000L
    private var online = true

    private fun manager(
        outbox: TelemetryOutbox,
        poster: HttpPoster,
        enabled: Boolean = true,
        activated: Boolean = true,
        credentials: TelemetryCredentials = TelemetryCredentials(InMemorySecretStore())
            .apply { save("https://abc.supabase.co", "anon-key") },
        statusStore: TelemetryStatusStore = InMemoryStatusStore()
    ) = TelemetryManager(
        outbox = outbox,
        credentials = credentials,
        statusStore = statusStore,
        posterFor = { poster },
        runtime = {
            TelemetryRuntime(
                enabled = enabled,
                activated = activated,
                identity = identity,
                privacyPolicyUrl = "https://example.org/privacy",
                termsUrl = "https://example.org/terms"
            )
        },
        networkAvailable = { online },
        clock = { now }
    )

    private fun outboxWith(vararg ids: String): TelemetryOutbox {
        val outbox = TelemetryOutbox(temp.newFile())
        ids.forEach { outbox.append(it, TelemetryTables.DONATIONS, """{"id":"$it"}""") }
        return outbox
    }

    @Test
    fun aSuccessfulFlushDeletesTheUploadedRows() {
        val outbox = outboxWith("a", "b")
        val result = manager(outbox, { _, _, _ -> HttpResponse(201, null) }).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(0, outbox.size())
    }

    /**
     * The rows are the only copy. A retryable failure must leave every one of them
     * on disk — this is the assertion that stands between a network blip and a
     * night's donations.
     */
    @Test
    fun aRetryableFailureKeepsEveryRow() {
        val outbox = outboxWith("a", "b")
        val result = manager(outbox, { _, _, _ -> HttpResponse(503, "down") }).flush()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(2, outbox.size())
    }

    @Test
    fun aRetryableFailureBacksOff() {
        val store = InMemoryStatusStore()
        manager(outboxWith("a"), { _, _, _ -> HttpResponse(503, "down") }, statusStore = store).flush()
        val status = store.read()
        assertEquals(1, status.consecutiveFailures)
        assertTrue("a failure must push the next attempt out", status.backoffUntilMs > now)
    }

    @Test
    fun aSuccessResetsTheFailureCount() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(consecutiveFailures = 4, backoffUntilMs = 0L))
        manager(outboxWith("a"), { _, _, _ -> HttpResponse(201, null) }, statusStore = store).flush()
        assertEquals(0, store.read().consecutiveFailures)
        assertEquals(now, store.read().lastSuccessMs)
        assertNull(store.read().lastError)
    }

    @Test
    fun theGateStopsAFlushBeforeAnyRequestIsMade() {
        var called = false
        val outbox = outboxWith("a")
        val result = manager(
            outbox,
            { _, _, _ -> called = true; HttpResponse(201, null) },
            enabled = false
        ).flush()
        assertEquals(FlushBlock.DISABLED, result)
        assertFalse("a disabled kiosk must not touch the network", called)
        assertEquals(1, outbox.size())
    }

    @Test
    fun anUnconfiguredKioskIsBlockedRatherThanUploadingToNowhere() {
        val result = manager(
            outboxWith("a"),
            { _, _, _ -> HttpResponse(201, null) },
            credentials = TelemetryCredentials(InMemorySecretStore())
        ).flush()
        assertEquals(FlushBlock.NOT_CONFIGURED, result)
    }

    @Test
    fun beingOfflineBlocksTheFlush() {
        online = false
        assertEquals(FlushBlock.NO_NETWORK, manager(outboxWith("a"), { _, _, _ -> HttpResponse(201, null) }).flush())
        online = true
    }

    @Test
    fun anEmptyQueueIsNotAFailure() {
        val store = InMemoryStatusStore()
        val result = manager(TelemetryOutbox(temp.newFile()), { _, _, _ -> HttpResponse(500, null) }, statusStore = store).flush()
        assertEquals(FlushBlock.EMPTY_QUEUE, result)
        assertEquals("nothing to send is not a failure", 0, store.read().consecutiveFailures)
    }

    @Test
    fun aBackoffDeadlineInTheFutureBlocksTheFlush() {
        val store = InMemoryStatusStore()
        store.write(store.read().copy(backoffUntilMs = now + 30_000))
        assertEquals(
            FlushBlock.BACKING_OFF,
            manager(outboxWith("a"), { _, _, _ -> HttpResponse(201, null) }, statusStore = store).flush()
        )
    }

    /** Activation is also the configuration smoke test: a mistyped endpoint is
     *  caught at the bench rather than three weeks later. */
    @Test
    fun activateEnqueuesAnActivationRowAndFlushesIt() {
        val outbox = TelemetryOutbox(temp.newFile())
        val bodies = mutableListOf<String>()
        val result = manager(outbox, { _, _, body -> bodies += body; HttpResponse(201, null) })
            .activate()
        assertEquals(FlushBlock.NONE, result)
        assertEquals(0, outbox.size())
        assertTrue(bodies.single().contains("install-1"))
    }

    @Test
    fun aFailedActivationLeavesTheRowQueuedSoTheOperatorCanRetry() {
        val outbox = TelemetryOutbox(temp.newFile())
        manager(outbox, { _, _, _ -> HttpResponse(401, "bad key") }).activate()
        assertEquals(1, outbox.size())
    }

    @Test
    fun theStoredErrorNeverContainsTheAnonKey() {
        val store = InMemoryStatusStore()
        manager(outboxWith("a"), { _, _, _ -> HttpResponse(500, "rejected key anon-key") }, statusStore = store).flush()
        assertFalse(store.read().lastError!!.contains("anon-key"))
    }

    @Test
    fun statusReportsTheQueueDepth() {
        val outbox = outboxWith("a", "b", "c")
        assertEquals(3, manager(outbox, { _, _, _ -> HttpResponse(201, null) }).status().queued)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "*TelemetryManagerTest*"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write `TelemetryStatusStore.kt`**

```kotlin
package com.sadaqah.kiosk.telemetry

/**
 * What the operator sees on the Analytics screen, and what the gate needs to know
 * about the last attempt.
 *
 * [lastError] is already redacted by the uploader before it arrives here.
 */
data class TelemetryStatus(
    val queued: Int = 0,
    val lastSuccessMs: Long = 0L,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val backoffUntilMs: Long = 0L
)

/**
 * A seam so the manager's bookkeeping is testable without SharedPreferences.
 * The persistent implementation arrives with the settings screen that reads it.
 */
interface TelemetryStatusStore {
    fun read(): TelemetryStatus
    fun write(status: TelemetryStatus)
}

class InMemoryStatusStore : TelemetryStatusStore {
    private var status = TelemetryStatus()
    override fun read(): TelemetryStatus = status
    override fun write(status: TelemetryStatus) { this.status = status }
}
```

- [ ] **Step 4: Write `TelemetryManager.kt`**

```kotlin
package com.sadaqah.kiosk.telemetry

/** The parts of the running configuration the manager reads afresh on every
 *  flush, so a toggle takes effect without reconstructing anything. */
data class TelemetryRuntime(
    val enabled: Boolean,
    val activated: Boolean,
    val identity: EventIdentity,
    val privacyPolicyUrl: String,
    val termsUrl: String
)

/**
 * The order of operations for sending telemetry: ask the gate, take a batch,
 * upload it, delete exactly what was accounted for, record the outcome.
 *
 * Every decision it makes is delegated — the gate decides whether to go, the
 * uploader decides what a response means. What lives here is the sequence, and
 * one rule the sequence must never break: **nothing is removed from the outbox
 * that the uploader did not name.** The queue is the only copy of a donation.
 */
class TelemetryManager(
    private val outbox: TelemetryOutbox,
    private val credentials: TelemetryCredentials,
    private val statusStore: TelemetryStatusStore,
    private val posterFor: (TelemetryConfig) -> HttpPoster,
    private val runtime: () -> TelemetryRuntime,
    private val networkAvailable: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun status(): TelemetryStatus = statusStore.read().copy(queued = outbox.size())

    /**
     * Writes the activation row and sends it immediately. This doubles as the
     * configuration smoke test: a successful insert is the operator's confirmation
     * that the destination is real, so a mistyped endpoint surfaces at the bench.
     * A failure leaves the row queued rather than discarding it, so retrying is
     * just another flush.
     */
    fun activate(): FlushBlock {
        val current = runtime()
        val event = TelemetryEvent.Activation(
            identity = current.identity,
            activatedAtIso = TelemetryEvent.nowIso(),
            privacyPolicyUrl = current.privacyPolicyUrl,
            termsUrl = current.termsUrl
        )
        outbox.append(event.id, event.table, event.payloadJson())
        return flush()
    }

    fun flush(): FlushBlock {
        val current = runtime()
        val config = credentials.load()
        val before = statusStore.read()
        val now = clock()

        val block = TelemetryGate.evaluate(
            GateInputs(
                enabled = current.enabled,
                configured = config != null,
                activated = current.activated,
                networkAvailable = networkAvailable(),
                queueDepth = outbox.size(),
                backoffUntilMs = before.backoffUntilMs
            ),
            now
        )
        if (block != FlushBlock.NONE || config == null) return block

        val batch = outbox.peek()
        if (batch.isEmpty()) return FlushBlock.EMPTY_QUEUE

        val outcome = TelemetryUploader(config.baseUrl, config.anonKey, posterFor(config))
            .upload(batch)

        // Both sets are safe to delete and neither is derived here: uploaded rows
        // reached the server, and a rejected row carries the uploader's own
        // sibling-corroborated proof that it never will. Anything absent from both
        // stays queued by construction.
        outbox.remove(outcome.uploadedIds + outcome.rejectedIds)

        statusStore.write(
            if (outcome.retryableFailure) {
                val failures = before.consecutiveFailures + 1
                before.copy(
                    lastError = outcome.lastError,
                    consecutiveFailures = failures,
                    backoffUntilMs = now + TelemetryGate.backoffDelayMs(failures),
                    // A partial success still moves the marker: rows did land, and
                    // an operator reading "last upload: never" while data arrives
                    // would chase a problem that is not there.
                    lastSuccessMs = if (outcome.uploadedIds.isEmpty()) before.lastSuccessMs else now
                )
            } else {
                before.copy(
                    lastError = null,
                    consecutiveFailures = 0,
                    backoffUntilMs = 0L,
                    lastSuccessMs = now
                )
            }
        )
        return FlushBlock.NONE
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "*TelemetryManagerTest*"`
Expected: PASS, 13 tests.

- [ ] **Step 6: Mutation-check the load-bearing three**

1. Change `outbox.remove(outcome.uploadedIds + outcome.rejectedIds)` to `outbox.remove(batch.map { it.id }.toSet())`. `aRetryableFailureKeepsEveryRow` must FAIL. Restore.
2. Move the `TelemetryGate.evaluate` call to after the upload. `theGateStopsAFlushBeforeAnyRequestIsMade` must FAIL. Restore.
3. Delete the `consecutiveFailures = 0` reset on the success branch. `aSuccessResetsTheFailureCount` must FAIL. Restore.

Record all three failure messages in your report.

- [ ] **Step 7: Run the full suite and prove inertness**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, zero failures.

Run: `grep -rn "TelemetryManager(" app/src/main/java/com/sadaqah/kiosk/ --include=*.kt`
Expected: no output — nothing constructs it yet.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryStatusStore.kt \
        app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryManager.kt \
        app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryManagerTest.kt
git commit -m "Add the telemetry flush manager"
```

---

## Carried into this phase from 2a's review

These were parked deliberately and belong to whoever implements the items above:

- **`retryableFailure` is one global boolean.** An all-refused table forces backoff across every table. Splitting it per-table is a change to `UploadOutcome` and is explicitly *not* in this phase — record it again if it still matters after the manager exists.
- **A 3xx yields a bare `HTTP 302` in `lastError`.** Thin diagnostics for a redirecting endpoint. The https check in Task 2 removes the most likely cause.
- **PostgREST 4xx bodies can echo row values into `lastError`.** It is already redacted for secrets, but it is not guaranteed free of donation amounts. **Whoever wires logging must not write `lastError` verbatim to logcat.**
- **The 30-day age cap is load-bearing** for a permanently-malformed row, because 2a stopped deleting uncorroborated refusals. Do not raise or remove it here.

## Not in this phase

- `AnalyticsSettingsScreen` and its eight-language copy — phase 2c.
- `PrefsStatusStore`, the persistent `TelemetryStatusStore` — phase 2c, where the screen first needs status to survive a restart.
- Any automatic flush scheduling. Nothing calls `flush()` on a timer until phase 3.
- Donation and diagnostic instrumentation — phase 3.
- **The `id` uniqueness constraint cannot be verified from the client.** RLS is insert-only so there is no `SELECT` to check with, and `resolution=ignore-duplicates` returns success whether or not the constraint exists. It is a server-side requirement carried by the reference DDL in phase 5's documentation, not something this phase can assert.
