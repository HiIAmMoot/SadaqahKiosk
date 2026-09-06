# Telemetry Phase 2c — The Analytics Settings Screen

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the operator a screen to configure the telemetry destination, test it, read its status, and clear it — and make the status survive a restart.

**Architecture:** The screen is thin. Every decision it makes — what to show for a given status, whether a code looks conventional, what a masked key looks like, whether the destination is testable — lives in a pure mapper that is unit-tested on the JVM. The composable renders what the mapper returns. This is the same split that made `SettingsBootstrap` catchable after an inline version shipped a bug no test could reach.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose, Material3, JUnit 4, Gson, minSdk 30. No new third-party dependencies.

**Spec:** `docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md`

## Global Constraints

- **No new third-party dependencies.** Do not touch any gradle file.
- **Inertness changes shape in this phase, it does not disappear.** `TelemetryManager` becomes reachable — the operator can press "Test connection", which enqueues one activation row and flushes it. **Nothing else enqueues, and nothing flushes on a timer.** No donation, no diagnostic, no crash handler. That is phase 3. A grep must still show no donation-path caller.
- **No secret in a log, a `toString`, or a stored error string.** The publishable key is shown masked and never logged. `lastError` is displayed to the operator but must not be written to logcat — a PostgREST 4xx body can echo donation values.
- **The outbox is the only copy of a donation.** Clearing credentials clears the queue (that is deliberate — see Task 4), but nothing else may remove rows.
- **Every new user-visible string is translated into all eight languages.** Dutch, English, German, French, Spanish, Italian, Turkish, Arabic. A missing translation is a compile error because `Strings` is an interface — that is the point.
- **`kioskCode` validation is advisory.** Warn, never block saving.
- Codebase style: comments explain non-obvious *why*, never *what*; no commented-out code; match the existing `SettingsScreen` component vocabulary (`BandHeader`, `ToggleRow`, `CollapsibleHeader`, `StatusPanel`).
- **Never commit or push to `master`.** Work happens on `telemetry/phase-2c`, branched from `telemetry/phase-2b`.

## Context: what already exists

From phases 1, 2a and 2b (read these; modify only where a task says so):

- `telemetry/TelemetryManager.kt` — `TelemetryManager(outbox, credentials, statusStore, posterFor, runtime, networkAvailable, clock)`, `flush(): FlushBlock`, `activate(): ActivationResult`, `status(): TelemetryStatus`.
- `telemetry/TelemetryStatusStore.kt` — `interface TelemetryStatusStore { read(): TelemetryStatus; write(status) }`, `InMemoryStatusStore`, `data class TelemetryStatus(queued, lastSuccessMs, lastError, consecutiveFailures, backoffUntilMs)`.
- `telemetry/TelemetryCredentials.kt` — `load(): TelemetryConfig?`, `save(baseUrl, publishableKey): UrlVerdict`, `clear()`, `isConfigured()`. `UrlVerdict.Valid(normalised)` / `UrlVerdict.Invalid(reason)` — the reason is operator-facing copy.
- `telemetry/SecretStore.kt` — `SecretStore`, `KeystoreSecretStore(context)`, `InMemorySecretStore`, `UnwritableSecretStore`.
- `telemetry/TelemetryOutbox.kt` — `clear()` exists and **has no caller yet**. Task 4 gives it one.
- `telemetry/KioskCode.kt` — `looksConventional(code)`, `normalize(code)`, `CONVENTION`.
- `telemetry/UrlConnectionPoster.kt` — the real `HttpPoster`.
- `model/Settings.kt` — `analyticsEnabled`, `analyticsActivatedAtMs`, `analyticsPrivacyPolicyUrl`, `analyticsTermsUrl`, `kioskCode`, `installId`.
- `Translations.kt` — `interface Strings` at line 58, then eight objects: `DutchStrings`, `EnglishStrings`, `GermanStrings`, `FrenchStrings`, `SpanishStrings`, `ItalianStrings`, `TurkishStrings`, `ArabicStrings`.
- `screens/SettingsScreen.kt` — `onShowDonationHistory` at line 69 and its button near line 570 are the model for the new entry point.
- `MainActivity.kt` — `showDonationHistory` state at line 125, `authenticateWithBiometrics` at 1449.

## File Structure

| File | Responsibility |
|---|---|
| `telemetry/PrefsStatusStore.kt` | `TelemetryStatusStore` over `SharedPreferences`, so status survives a restart. |
| `telemetry/AnalyticsPresenter.kt` | Pure. Turns credentials + settings + status into everything the screen renders. |
| `screens/AnalyticsSettingsScreen.kt` | Thin Compose rendering of what the presenter returns. |
| `Translations.kt` | The new strings, eight times. |
| `MainActivity.kt` / `screens/SettingsScreen.kt` | Navigation, wiring, and the clear-credentials action. |

---

### Task 1: `PrefsStatusStore` and the pure presenter

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/PrefsStatusStore.kt`
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/AnalyticsPresenter.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/AnalyticsPresenterTest.kt`

**Interfaces:**
- Consumes: `TelemetryStatus`, `TelemetryStatusStore`, `TelemetryConfig`, `KioskCode`, `FlushBlock`, `Settings`.
- Produces:
  - `class PrefsStatusStore(context: Context, prefsName: String = "telemetry_status") : TelemetryStatusStore`
  - `data class AnalyticsView(...)` and `object AnalyticsPresenter { fun view(...): AnalyticsView }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsPresenterTest {

    private val now = 1_800_000_000_000L

    private fun view(
        settings: Settings = Settings(analyticsEnabled = true, installId = "install-1"),
        config: TelemetryConfig? = TelemetryConfig("https://abc.supabase.co", "publishable-key"),
        status: TelemetryStatus = TelemetryStatus()
    ) = AnalyticsPresenter.view(settings, config, status, now)

    @Test
    fun anUnconfiguredKioskCannotBeTested() {
        assertFalse(view(config = null).canTestConnection)
    }

    @Test
    fun aConfiguredKioskCanBeTested() {
        assertTrue(view().canTestConnection)
    }

    /** Disabling telemetry must not also disable the way to fix a bad destination —
     *  but there is nothing to test if no destination exists. */
    @Test
    fun aDisabledButConfiguredKioskCanStillBeTested() {
        assertTrue(view(settings = Settings(analyticsEnabled = false, installId = "i")).canTestConnection)
    }

    /**
     * The key is shown so an operator can confirm which one is loaded without it
     * being readable over their shoulder, or in a photograph of the screen.
     */
    @Test
    fun theKeyIsMaskedToAShortSuffix() {
        val masked = view(config = TelemetryConfig("https://abc.supabase.co", "sb_publishable_ABCDEFGHIJKL")).maskedKey
        assertTrue("the tail identifies which key it is", masked.endsWith("IJKL"))
        assertFalse("the body must not be readable", masked.contains("ABCDEFGH"))
    }

    @Test
    fun aShortKeyIsMaskedEntirelyRatherThanMostlyRevealed() {
        val masked = view(config = TelemetryConfig("https://abc.supabase.co", "abcd")).maskedKey
        assertFalse(masked.contains("abcd"))
    }

    @Test
    fun anAbsentKeyShowsNothingRatherThanMaskCharacters() {
        assertEquals("", view(config = null).maskedKey)
    }

    @Test
    fun theUrlIsShownInFullBecauseItIsNotASecret() {
        assertEquals("https://abc.supabase.co", view().baseUrl)
    }

    @Test
    fun aConventionalKioskCodeIsNotFlagged() {
        val settings = Settings(kioskCode = "nl-gld-arnhem-nour_al_houda-01", installId = "i")
        assertFalse(view(settings = settings).kioskCodeLooksUnusual)
    }

    @Test
    fun anUnconventionalKioskCodeIsFlaggedButNotBlocking() {
        val settings = Settings(kioskCode = "front door", installId = "i")
        assertTrue(view(settings = settings).kioskCodeLooksUnusual)
    }

    /** A deployment with no code scheme is supported, so blank must never warn. */
    @Test
    fun aBlankKioskCodeIsNotFlagged() {
        assertFalse(view(settings = Settings(kioskCode = "", installId = "i")).kioskCodeLooksUnusual)
    }

    @Test
    fun neverUploadedIsDistinctFromUploadedLongAgo() {
        assertTrue(view(status = TelemetryStatus(lastSuccessMs = 0L)).neverUploaded)
        assertFalse(view(status = TelemetryStatus(lastSuccessMs = now - 60_000)).neverUploaded)
    }

    @Test
    fun theQueueDepthIsCarriedThrough() {
        assertEquals(42, view(status = TelemetryStatus(queued = 42)).queued)
    }

    /**
     * A queue that has drained while an old error is still stored reads as
     * "everything failed and the data is gone" unless the error is cleared from
     * the view once it no longer describes anything pending.
     */
    @Test
    fun aStaleErrorIsNotShownBesideAnEmptyQueueAfterASuccess() {
        val status = TelemetryStatus(queued = 0, lastError = "HTTP 503 down", lastSuccessMs = now - 1000)
        assertEquals(null, view(status = status).error)
    }

    @Test
    fun anErrorIsShownWhileRowsAreStillWaiting() {
        val status = TelemetryStatus(queued = 5, lastError = "HTTP 503 down", lastSuccessMs = now - 1000)
        assertEquals("HTTP 503 down", view(status = status).error)
    }

    @Test
    fun backoffIsReportedOnlyWhileItIsInTheFuture() {
        assertTrue(view(status = TelemetryStatus(backoffUntilMs = now + 30_000)).backingOff)
        assertFalse(view(status = TelemetryStatus(backoffUntilMs = now - 30_000)).backingOff)
    }

    @Test
    fun activationIsReadFromTheSettingsTimestamp() {
        assertFalse(view(settings = Settings(installId = "i", analyticsActivatedAtMs = 0L)).activated)
        assertTrue(view(settings = Settings(installId = "i", analyticsActivatedAtMs = now)).activated)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "*AnalyticsPresenterTest*"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write `AnalyticsPresenter.kt`**

```kotlin
package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings

/**
 * Everything the analytics screen renders, decided here rather than in the
 * composable.
 *
 * The screen cannot be unit-tested — instrumented tests need a device — so any
 * judgement left inside it is judgement nothing checks. Phase 2b shipped a bug
 * for exactly that reason: a mint that sat inside an unreachable branch of
 * `MainActivity`. So the composable renders this and decides nothing.
 */
data class AnalyticsView(
    val enabled: Boolean,
    val activated: Boolean,
    val configured: Boolean,
    val canTestConnection: Boolean,
    val baseUrl: String,
    val maskedKey: String,
    val kioskCode: String,
    val kioskCodeLooksUnusual: Boolean,
    val queued: Int,
    val neverUploaded: Boolean,
    val lastSuccessMs: Long,
    val error: String?,
    val backingOff: Boolean
)

object AnalyticsPresenter {

    /** Enough of the tail to tell two keys apart, never enough to use one. */
    private const val VISIBLE_KEY_CHARS = 4

    fun view(
        settings: Settings,
        config: TelemetryConfig?,
        status: TelemetryStatus,
        nowMs: Long
    ): AnalyticsView {
        val queued = status.queued
        return AnalyticsView(
            enabled = settings.analyticsEnabled,
            activated = settings.analyticsActivatedAtMs != 0L,
            configured = config != null,
            // Deliberately not gated on `enabled`: an operator who has switched
            // telemetry off must still be able to correct a mistyped destination
            // without switching it back on first.
            canTestConnection = config != null,
            baseUrl = config?.baseUrl.orEmpty(),
            maskedKey = mask(config?.publishableKey),
            kioskCode = settings.kioskCode,
            kioskCodeLooksUnusual = !KioskCode.looksConventional(settings.kioskCode),
            queued = queued,
            neverUploaded = status.lastSuccessMs == 0L,
            lastSuccessMs = status.lastSuccessMs,
            // An error that no longer describes anything waiting is history, and
            // showing it beside an empty queue reads as "it all failed and the
            // data is gone" — which is what real data loss would also look like.
            error = status.lastError?.takeIf { queued > 0 },
            backingOff = status.backoffUntilMs > nowMs
        )
    }

    private fun mask(key: String?): String {
        if (key.isNullOrEmpty()) return ""
        // A short key is masked completely: revealing four of six characters
        // would identify the key rather than merely distinguish it.
        if (key.length <= VISIBLE_KEY_CHARS * 2) return "•".repeat(key.length)
        return "•".repeat(key.length - VISIBLE_KEY_CHARS) + key.takeLast(VISIBLE_KEY_CHARS)
    }
}
```

- [ ] **Step 4: Write `PrefsStatusStore.kt`**

```kotlin
package com.sadaqah.kiosk.telemetry

import android.content.Context

/**
 * Flush bookkeeping that survives a restart.
 *
 * Not a secret — a queue depth and a redacted error string — so it goes in plain
 * SharedPreferences rather than through the encrypted [SecretStore]. `queued` is
 * deliberately not persisted: it is recomputed from the outbox on every read, and
 * a stored copy would be a second source of truth that drifts.
 */
class PrefsStatusStore(
    context: Context,
    prefsName: String = "telemetry_status"
) : TelemetryStatusStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun read(): TelemetryStatus = TelemetryStatus(
        queued = 0,
        lastSuccessMs = prefs.getLong(KEY_LAST_SUCCESS, 0L),
        lastError = prefs.getString(KEY_LAST_ERROR, null),
        consecutiveFailures = prefs.getInt(KEY_FAILURES, 0),
        backoffUntilMs = prefs.getLong(KEY_BACKOFF_UNTIL, 0L)
    )

    override fun write(status: TelemetryStatus) {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS, status.lastSuccessMs)
            .putString(KEY_LAST_ERROR, status.lastError)
            .putInt(KEY_FAILURES, status.consecutiveFailures)
            .putLong(KEY_BACKOFF_UNTIL, status.backoffUntilMs)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_LAST_SUCCESS = "last_success_ms"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_BACKOFF_UNTIL = "backoff_until_ms"
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "*AnalyticsPresenterTest*"`
Expected: PASS, 16 tests.

- [ ] **Step 6: Mutation-check three**

1. Make `canTestConnection` also require `settings.analyticsEnabled`. `aDisabledButConfiguredKioskCanStillBeTested` must FAIL. Restore.
2. Drop the `.takeIf { queued > 0 }` from `error`. `aStaleErrorIsNotShownBesideAnEmptyQueueAfterASuccess` must FAIL. Restore.
3. Change `mask` to always reveal the last four. `aShortKeyIsMaskedEntirelyRatherThanMostlyRevealed` must FAIL. Restore.

Record all three failure messages in your report.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/telemetry/PrefsStatusStore.kt \
        app/src/main/java/com/sadaqah/kiosk/telemetry/AnalyticsPresenter.kt \
        app/src/test/java/com/sadaqah/kiosk/telemetry/AnalyticsPresenterTest.kt
git commit -m "Add the analytics presenter and persistent flush status"
```

---

### Task 2: The strings, eight times

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/Translations.kt`

**Interfaces:**
- Produces: new `Strings` members, implemented by all eight objects.

- [ ] **Step 1: Add the members to `interface Strings`**

Add after the existing import/export block (around line 129):

```kotlin
    // Analytics / telemetry
    val analyticsTitle: String
    val analyticsSettings: String
    val analyticsEnabledLabel: String
    val analyticsEnabledHint: String
    val analyticsDestination: String
    val analyticsUrlLabel: String
    val analyticsKeyLabel: String
    val analyticsKeyHint: String
    val analyticsSave: String
    val analyticsTestConnection: String
    val analyticsTesting: String
    val analyticsTestSucceeded: String
    val analyticsTestFailed: String
    val analyticsTestQueued: String
    val analyticsStatus: String
    val analyticsQueued: String
    val analyticsLastUpload: String
    val analyticsNeverUploaded: String
    val analyticsLastError: String
    val analyticsBackingOff: String
    val analyticsActivated: String
    val analyticsNotActivated: String
    val analyticsKioskCode: String
    val analyticsKioskCodeUnusual: String
    val analyticsPolicyUrls: String
    val analyticsPrivacyUrlLabel: String
    val analyticsTermsUrlLabel: String
    val analyticsClearCredentials: String
    val analyticsClearWarning: String
    val analyticsCleared: String
    // Added after Task 1's review: the presenter now decides these, so the
    // screen needs copy for each rather than computing its own.
    val analyticsTestUnavailableNotConfigured: String
    val analyticsTestUnavailableDisabled: String
    val analyticsKeyUnusual: String
    val analyticsPolicyUrlsMissing: String
    val analyticsInstallId: String
    val analyticsFailedAttempts: String
    val analyticsRetryIn: String
```

- [ ] **Step 2: Implement them in all eight objects**

Add the same block to `DutchStrings`, `EnglishStrings`, `GermanStrings`, `FrenchStrings`, `SpanishStrings`, `ItalianStrings`, `TurkishStrings` and `ArabicStrings`, translated. English for reference:

```kotlin
    override val analyticsTitle = "Analytics"
    override val analyticsSettings = "Analytics & reporting"
    override val analyticsEnabledLabel = "Send analytics"
    override val analyticsEnabledHint = "Donation totals and fault reports, from this kiosk only."
    override val analyticsDestination = "Destination"
    override val analyticsUrlLabel = "Project URL"
    override val analyticsKeyLabel = "Publishable key"
    override val analyticsKeyHint = "Stored encrypted on this device."
    override val analyticsSave = "Save destination"
    override val analyticsTestConnection = "Test connection"
    override val analyticsTesting = "Testing..."
    override val analyticsTestSucceeded = "Connected. This kiosk is now reporting."
    override val analyticsTestFailed = "Could not reach the destination."
    override val analyticsTestQueued = "Queued behind existing events; it will send with them."
    override val analyticsStatus = "Status"
    override val analyticsQueued = "Waiting to send"
    override val analyticsLastUpload = "Last upload"
    override val analyticsNeverUploaded = "Never"
    override val analyticsLastError = "Last error"
    override val analyticsBackingOff = "Waiting before retrying"
    override val analyticsActivated = "Reporting is on"
    override val analyticsNotActivated = "Not yet reporting"
    override val analyticsKioskCode = "Kiosk code"
    override val analyticsKioskCodeUnusual = "This does not match the usual code format. It will still be used."
    override val analyticsPolicyUrls = "Policy links"
    override val analyticsPrivacyUrlLabel = "Privacy policy URL"
    override val analyticsTermsUrlLabel = "Terms URL"
    override val analyticsClearCredentials = "Clear credentials"
    override val analyticsClearWarning = "This removes the destination and deletes everything still waiting to send."
    override val analyticsCleared = "Credentials cleared."
    override val analyticsTestUnavailableNotConfigured = "Enter a destination first."
    override val analyticsTestUnavailableDisabled = "Turn analytics on to test the connection."
    override val analyticsKeyUnusual = "This does not look like a publishable key. Check you have not pasted a secret key."
    override val analyticsPolicyUrlsMissing = "No policy links set. Activation will record them as empty."
    override val analyticsInstallId = "Install ID"
    override val analyticsFailedAttempts = "Failed attempts"
    override val analyticsRetryIn = "Retrying in"
```

`analyticsKeyUnusual` is the one an operator must not misread: pasting a Supabase
*secret* key here would store a credential with full database access. Keep the
second sentence in every language.

Translate rather than transliterate. `analyticsClearWarning` must keep its second clause in every language — an operator needs to know queued data is destroyed. Arabic is RTL; follow whatever the existing Arabic entries do.

- [ ] **Step 3: Compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. A missing override in any of the eight objects is a compile error, which is the point of `Strings` being an interface.

- [ ] **Step 4: Verify no object was skipped**

Run: `grep -c "analyticsClearWarning" app/src/main/java/com/sadaqah/kiosk/Translations.kt`
Expected: `9` — one interface declaration plus eight implementations. Repeat the count for `analyticsKeyUnusual` and `analyticsInstallId` too, since those were added later and are the easiest to miss in one of the eight objects.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/Translations.kt
git commit -m "Add analytics screen copy in all eight languages"
```

---

### Task 3: `AnalyticsSettingsScreen`

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/screens/AnalyticsSettingsScreen.kt`

**Interfaces:**
- Consumes: `AnalyticsView`, `Strings`, `Settings`, `UrlVerdict`, `ActivationResult`.
- Produces:

```kotlin
@Composable
fun AnalyticsSettingsScreen(
    view: AnalyticsView,
    settings: Settings,
    strings: Strings,
    testState: TestConnectionState,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSaveDestination: (url: String, key: String) -> UrlVerdict,
    onTestConnection: () -> Unit,
    onKioskCodeChange: (String) -> Unit,
    onPolicyUrlsChange: (privacy: String, terms: String) -> Unit,
    onClearCredentials: () -> Unit
)

sealed class TestConnectionState {
    object Idle : TestConnectionState()
    object Running : TestConnectionState()
    data class Succeeded(val message: String) : TestConnectionState()
    data class Queued(val message: String) : TestConnectionState()
    data class Failed(val message: String) : TestConnectionState()
}
```

- [ ] **Step 1: Read the existing screen first**

Read `screens/SettingsScreen.kt` and reuse its vocabulary — `BandHeader`, `ToggleRow`, `CollapsibleHeader`, `StatusPanel`, `StatusRow`, `TimerInputRow` — and its `ExportDialog`/`ImportDialog` for how a text-and-password dialog is built here. Do not invent a second visual language. Read `screens/DonationHistoryScreen.kt` for how a sibling full-screen settings page is laid out and how it takes `onBack`.

- [ ] **Step 2: Build the screen**

Sections, in order:

1. **Master toggle** — `analyticsEnabledLabel` with `analyticsEnabledHint` beneath.
2. **Destination** — `analyticsUrlLabel` text field, `analyticsKeyLabel` field showing `view.maskedKey` when one is stored and accepting a new value when edited, `analyticsKeyHint` beneath, and `analyticsSave`. On save, call `onSaveDestination` and render a returned `UrlVerdict.Invalid.reason` inline next to the field — those strings are already operator-facing copy, so show them verbatim rather than substituting your own.
3. **Test connection** — a button, disabled unless `view.canTestConnection`. Render `testState`: `Running` shows `analyticsTesting` and disables the button; `Succeeded`/`Queued`/`Failed` show their message.
4. **Status** — `analyticsQueued` with `view.queued`; `analyticsLastUpload` showing a formatted `view.lastSuccessMs`, or `analyticsNeverUploaded` when `view.neverUploaded`; `analyticsLastError` only when `view.error != null`; `analyticsBackingOff` when `view.backingOff`; and `analyticsActivated`/`analyticsNotActivated` from `view.activated`.
5. **Kiosk code** — a text field bound to `view.kioskCode`. When `view.kioskCodeLooksUnusual`, show `analyticsKioskCodeUnusual` as a warning that does **not** block saving.
6. **Policy links** — two fields, `analyticsPrivacyUrlLabel` and `analyticsTermsUrlLabel`.
7. **Clear credentials** — a destructive-styled button that opens a confirmation dialog carrying `analyticsClearWarning`, and only calls `onClearCredentials` on confirm.

Rules for this file:
- **It decides nothing.** No masking, no validation, no "is this configured" logic — all of that is already in `view`. If you find yourself computing something, it belongs in `AnalyticsPresenter` and its test.
- Never render `view.maskedKey` into a field the operator can copy out, and never log any field.
- The screen must be readable at the kiosk's screen size; follow the existing screens' scrolling behaviour.

- [ ] **Step 3: Compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify the screen holds no logic**

Run: `grep -n "\.length\|repeat(\|isBlank\|isNotBlank\|startsWith\|Regex\|looksConventional" app/src/main/java/com/sadaqah/kiosk/screens/AnalyticsSettingsScreen.kt`
Expected: nothing beyond trivial empty-field checks on the two text inputs. Anything else is logic that belongs in the presenter — move it and add a test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/screens/AnalyticsSettingsScreen.kt
git commit -m "Add the analytics settings screen"
```

---

### Task 4: Wiring, and the first caller for `clear()`

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/screens/SettingsScreen.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/ClearCredentialsTest.kt`

**Interfaces:**
- Produces: `object TelemetryTeardown { fun clearEverything(credentials: TelemetryCredentials, outbox: TelemetryOutbox, statusStore: TelemetryStatusStore) }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class ClearCredentialsTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * Queued rows name a kiosk. An operator clearing the destination has withdrawn
     * the basis for holding them, so the queue goes with the credentials — leaving
     * it would strand identified data on disk with nothing left that could ever
     * send it.
     */
    @Test
    fun clearingCredentialsAlsoDropsTheQueueAndTheStatus() {
        val outbox = TelemetryOutbox(temp.newFile())
        outbox.append("a", TelemetryTables.DONATIONS, """{"id":"a"}""")
        val credentials = TelemetryCredentials(InMemorySecretStore())
        credentials.save("https://abc.supabase.co", "publishable-key")
        val statusStore = InMemoryStatusStore()
        statusStore.write(TelemetryStatus(lastError = "HTTP 503", consecutiveFailures = 3))

        TelemetryTeardown.clearEverything(credentials, outbox, statusStore)

        assertFalse("the destination is gone", credentials.isConfigured())
        assertEquals("no identified row may outlive the destination", 0, outbox.size())
        assertNull("a stale error would describe a destination that no longer exists",
            statusStore.read().lastError)
        assertEquals(0, statusStore.read().consecutiveFailures)
    }

    @Test
    fun clearingIsSafeWhenNothingWasEverConfigured() {
        val outbox = TelemetryOutbox(temp.newFile())
        TelemetryTeardown.clearEverything(
            TelemetryCredentials(InMemorySecretStore()), outbox, InMemoryStatusStore()
        )
        assertEquals(0, outbox.size())
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "*ClearCredentialsTest*"`
Expected: FAIL — `Unresolved reference: TelemetryTeardown`.

- [ ] **Step 3: Write it**

Put it in `telemetry/TelemetryTeardown.kt`:

```kotlin
package com.sadaqah.kiosk.telemetry

/**
 * Clearing the destination is three deletions, and leaving any one of them out is
 * a quiet failure rather than a loud one — which is why it is one named operation
 * with a test rather than three calls at a UI call site.
 */
object TelemetryTeardown {
    fun clearEverything(
        credentials: TelemetryCredentials,
        outbox: TelemetryOutbox,
        statusStore: TelemetryStatusStore
    ) {
        credentials.clear()
        // The rows identify a kiosk and nothing remains that could send them.
        outbox.clear()
        // A stored error would describe a destination that no longer exists.
        statusStore.write(TelemetryStatus())
    }
}
```

- [ ] **Step 4: Wire the screen into `MainActivity`**

Follow the `showDonationHistory` pattern exactly:
- add `var showAnalyticsSettings by mutableStateOf(false)` beside it (line ~125)
- construct the telemetry objects once, lazily: `TelemetryOutbox` over a file in `filesDir`, `TelemetryCredentials(KeystoreSecretStore(this))`, `PrefsStatusStore(this)`, and a `TelemetryManager` whose `posterFor` builds a `UrlConnectionPoster`, whose `runtime` reads current `settings`, and whose `networkAvailable` uses whatever the app already uses for that — find it, do not add a second mechanism
- render `AnalyticsSettingsScreen` when the flag is set, and reset it to `false` wherever `showDonationHistory` is reset (line ~352 and ~1394)
- `onTestConnection` runs `activate()` **off the main thread** and maps `ActivationResult` to `TestConnectionState`; `Queued` maps to `analyticsTestQueued`
- `onClearCredentials` calls `TelemetryTeardown.clearEverything`
- `onSaveDestination` returns the `UrlVerdict` straight from `credentials.save` so the screen can show the reason

- [ ] **Step 5: Add the entry point in `SettingsScreen`**

Mirror `onShowDonationHistory` (parameter at line ~69, button near line ~570): add `onShowAnalyticsSettings: () -> Unit = {}` and a button labelled `strings.analyticsSettings`, behind the same biometric gate as Donation History.

- [ ] **Step 6: Verify inertness has only changed in the intended way**

Run: `grep -rn "TelemetryManager(\|TelemetryOutbox(\|TelemetryCredentials(" app/src/main/java/com/sadaqah/kiosk/ --include=*.kt | grep -v "/telemetry/"`
Expected: matches in `MainActivity.kt` only.

Run: `grep -rn "\.append(" app/src/main/java/com/sadaqah/kiosk/ --include=*.kt | grep -i "telemetry\|outbox"`
Expected: only inside the telemetry package. **No donation path, no crash handler, no diagnostic enqueues anything.** If this shows a caller in `DonationScreen`, `MainActivity`'s payment flow, or a receiver, that is phase 3 work leaking in — remove it.

- [ ] **Step 7: Full verification**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, zero failures.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Wire the analytics screen and give clear() its first caller"
```

---

### Task 5: Surface events the outbox dropped

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryOutbox.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryStatusStore.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/AnalyticsPresenter.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/Translations.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/screens/AnalyticsSettingsScreen.kt` (render the new line)
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt` (accumulate the callback)
- Modify: `app/src/main/java/com/sadaqah/kiosk/telemetry/TelemetryTeardown.kt` (nothing to change if it writes a default `TelemetryStatus()` — confirm, do not assume)
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/TelemetryOutboxTest.kt`, `AnalyticsPresenterTest.kt`

**Why this exists.** The outbox evicts rows at its count and age caps and says
nothing. On the screen, a drained queue beside a stale error looks exactly like a
queue that drained successfully — and it is also exactly what silent data loss
looks like. An operator cannot tell "everything sent" from "we gave up and threw
donations away". That distinction is the whole reason this phase shows status at
all.

**Interfaces:**
- Produces: `TelemetryOutbox(..., onDropped: (Int) -> Unit = {})`, `TelemetryStatus.droppedCount`, `AnalyticsView.dropped`.

- [ ] **Step 1: Write the failing tests**

Append to `TelemetryOutboxTest`:

```kotlin
    @Test
    fun droppingOldEventsReportsHowManyWereLost() {
        val dropped = mutableListOf<Int>()
        val box = TelemetryOutbox(file, maxEvents = 2, compactSlack = 0, clock = { now }) { dropped += it }
        box.appendDonation("a")
        box.appendDonation("b")
        box.appendDonation("c")
        assertEquals("the caller must learn a donation was thrown away", 1, dropped.sum())
    }

    @Test
    fun aQueueUnderItsCapReportsNothingDropped() {
        val dropped = mutableListOf<Int>()
        val box = TelemetryOutbox(file, maxEvents = 10, compactSlack = 0, clock = { now }) { dropped += it }
        box.appendDonation("a")
        assertEquals(0, dropped.sum())
    }
```

Append to `AnalyticsPresenterTest`:

```kotlin
    /** A drained queue beside a stale error is indistinguishable from data loss
     *  unless the loss is stated outright. */
    @Test
    fun droppedEventsAreSurfaced() {
        assertEquals(7, view(status = TelemetryStatus(droppedCount = 7)).dropped)
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew testDebugUnitTest --tests "*TelemetryOutboxTest*" --tests "*AnalyticsPresenterTest*"`
Expected: FAIL — no such constructor parameter, no such field.

- [ ] **Step 3: Implement**

- `TelemetryOutbox` gains a trailing `onDropped: (Int) -> Unit = {}` parameter. Default no-op, so every existing construction and test keeps compiling. Wherever compaction discards rows for the count or age cap, call it with how many were discarded, **inside the existing lock**. Do not call it for rows removed by `remove()` or `clear()` — those are accounted for, not lost.
- `TelemetryStatus` gains `val droppedCount: Int = 0`; `PrefsStatusStore` persists it; `TelemetryTeardown` resets it with the rest.
- `AnalyticsView` gains `val dropped: Int`, carried straight through by the presenter.
- Add `analyticsDropped` to `Strings` and all eight objects. English: `"Discarded (storage full or too old)"`. This is the one status line an operator must not have to interpret, so translate it plainly.
- The screen shows it only when `dropped > 0`.

- [ ] **Step 4: Wire the counter in `MainActivity`**

The outbox callback accumulates into the status store: read the current status, add the dropped count, write it back. Do this where the outbox is constructed in Task 4.

- [ ] **Step 5: Mutation-check**

Make `onDropped` never fire on the count cap. `droppingOldEventsReportsHowManyWereLost` must FAIL. Restore and record the message.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Tell the operator when the outbox has discarded events"
```

---

## Required of phase 3

- The crash handler and the donation path get their `append` calls. That is the moment inertness ends, and the moment `TelemetryOutbox`'s IOException contract starts mattering on the donation path.
- The flush scheduler. Nothing calls `flush()` on a timer until then, so a kiosk configured in 2c only sends when an operator presses "Test connection".
- `SettingsBootstrap`'s call site is still untested (re-gating it would leave the suite green). Instrumented coverage belongs with phase 3's device work.

## Carried in, unchanged

- `retryableFailure` is one global boolean; a refused table backs off the healthy ones.
- `status()` parses the outbox file per call — Task 3 must not call it inside a recomposition. Read it once per screen entry and after an action.
- `KeystoreSecretStore` has no unit test; its device check must assert a put/get round trip **after a process restart**.
