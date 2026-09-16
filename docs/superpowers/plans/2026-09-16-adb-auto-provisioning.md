# adb auto-provisioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One command configures a freshly reset tablet end to end — wifi, radios, screen, device owner, app install, settings, credentials and logo — and reports whether it actually worked.

**Architecture:** Everything except the settings import is native adb. The app gains one pure decision unit and a startup path that imports on a background thread and then restarts the process, so the import never lands mid-`onCreate`. A PowerShell script drives the sequence and polls a result file the app writes.

**Tech Stack:** adb / PowerShell 5.1 on the bench, Kotlin 2.0.21 + JUnit 4 in the app. No new dependencies — the classification test uses Java reflection rather than `kotlin-reflect`.

**Spec:** `docs/superpowers/specs/2026-09-16-adb-auto-provisioning-design.md`

## Global Constraints

- **Bench only.** adb is off in the field. `onNewIntent` is deliberately NOT implemented — it would make provisioning reachable on a live kiosk.
- **Every trigger is preceded by `am force-stop`.** `MainActivity` has no `launchMode` and no `onNewIntent`; `am start` against a running app discards the extras and `onCreate` never re-runs.
- **Never run key derivation on the main thread.** `SecretsCrypto.ITERATIONS = 600_000`.
- **The apply path routes through `importSettings`**, never a parallel copy — `MainActivity.kt:1979` sets `CrashContext.affiliateKey`, and skipping it ships the payment credential into crash reports.
- **`provision_kiosk_code` and `provision_kiosk_name` are required** whenever the payload carries non-blank values for them.
- **No secret in the result file**, in logs, or in any `toString`.
- **Payload path:** `/sdcard/Android/data/com.sadaqah.kiosk/files/provisioning/`. Plain `adb push` — NOT `run.py`'s `push_app_file`, which does `chown`/`restorecon` and is wrong for the FUSE-backed external path.
- **No AI attribution** in any commit or PR. The repository is public.
- Work on `telemetry/phase-6-device-checks` or a branch from it. Never commit to master.
- JUnit 4 only. No Mockito, MockK or Robolectric.
- Commit messages stay short.

---

## File structure

| File | Responsibility |
|---|---|
| `tools/provision/provision.ps1` | Create. Drives the whole bench sequence and reports pass/fail. |
| `app/src/main/java/com/sadaqah/kiosk/provisioning/ProvisioningLoader.kt` | Create. Pure decision unit: payload + password + overrides → outcome. No I/O. |
| `app/src/main/java/com/sadaqah/kiosk/provisioning/ProvisioningResult.kt` | Create. The result-file data class and its JSON shape. |
| `app/src/test/java/com/sadaqah/kiosk/provisioning/ProvisioningLoaderTest.kt` | Create. One test per outcome. |
| `app/src/test/java/com/sadaqah/kiosk/model/SettingsFieldClassificationTest.kt` | Create. Every `Settings` field must be classified; derives the guard set by reflection. |
| `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt` | Modify. The provisioning branch in `onCreate`, and `relaunchAfterProvisioning()`. |
| `tools/kiosk-check/run.py` | Modify. The round-trip device check. |

---

## Task 1: The provisioning script, native steps only

Everything that needs no app change. Independently runnable and verifiable today.

**Files:**
- Create: `tools/provision/provision.ps1`

**Interfaces:**
- Produces: a script taking `-Serial`, `-Apk`, `-Ssid`, `-WifiPassword`, `-Payload`, `-Password`, `-KioskCode`, `-KioskName`, `-Logo`. Task 5 adds the push/trigger/poll to the same file.

- [ ] **Step 1: Write the script skeleton with the native sequence**

Create `tools/provision/provision.ps1`:

```powershell
<#
    Provisions a freshly reset kiosk tablet.

    Bench use only. Every step here is native adb; the settings import that
    Task 5 adds is the only part that needs the app's cooperation.

    Ordering is not arbitrary. `pm install` must precede `dpm set-device-owner`
    because the admin component has to exist to be named, and device owner
    fails outright once any account exists or setup wizard has completed.
    Everything else is ordered so the app's own onCreate finds the world
    already correct: device owner before the trigger (or the app misses
    lock-task whitelisting and its silent permission grants until the next
    boot), wifi associated before the trigger (or `isNetworkAvailable` is false
    and the freshly imported affiliate key is never authenticated, and
    SettingsBootstrap anchors donation stats to an unsynced RTC), bluetooth on
    before the trigger (or the watchdog seeds itself into recovery on a device
    that is fine).
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [Parameter(Mandatory)][string]$Apk,
    [string]$Ssid,
    [string]$WifiPassword,
    [string]$Payload,
    [string]$Password,
    [string]$KioskCode,
    [string]$KioskName,
    [string]$Logo
)

$ErrorActionPreference = "Stop"
$PKG = "com.sadaqah.kiosk"
$ADMIN = "$PKG/.KioskDeviceAdminReceiver"
$REMOTE_DIR = "/sdcard/Android/data/$PKG/files/provisioning"

function Adb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Args)
    $full = @()
    if ($Serial) { $full += @("-s", $Serial) }
    $full += $Args
    & adb @full 2>&1
}

function Step($name) { Write-Host "  $name" -ForegroundColor Cyan }
function Ok($name)   { Write-Host "  OK   $name" -ForegroundColor Green }
function Die($msg)   { Write-Host "  FAIL $msg" -ForegroundColor Red; exit 1 }

Write-Host "`nProvisioning $(if ($Serial) { $Serial } else { 'the attached device' })`n"

# --- device present -------------------------------------------------------
Step "waiting for device"
Adb wait-for-device | Out-Null
if ((Adb shell getprop sys.boot_completed).Trim() -ne "1") { Die "device has not finished booting" }
Ok "device ready"

# --- install --------------------------------------------------------------
Step "installing $Apk"
$r = Adb install -r -g $Apk
if ($r -notmatch "Success") { Die "install failed: $r" }
Ok "installed"

# --- device owner ---------------------------------------------------------
# Must follow install. Two distinct refusals share one opaque error, so both
# are named: an account exists, or setup wizard was completed rather than
# skipped.
Step "setting device owner"
$r = Adb shell "dpm set-device-owner $ADMIN"
if ($r -match "Success") {
    Ok "device owner set"
} elseif ((Adb shell "dumpsys device_policy") -match "Device Owner") {
    Ok "device owner already set"
} else {
    Write-Host "  WARN device owner not set: $r" -ForegroundColor Yellow
    Write-Host "       usual causes: an account exists on the device, or setup" -ForegroundColor Yellow
    Write-Host "       wizard was completed instead of skipped. Factory reset and" -ForegroundColor Yellow
    Write-Host "       skip the wizard. Lock task will be the degraded pinning mode." -ForegroundColor Yellow
}

# --- radios ---------------------------------------------------------------
Step "enabling radios"
Adb shell "svc wifi enable" | Out-Null
Adb shell "svc bluetooth enable" | Out-Null
Adb shell "cmd location set-location-enabled true" | Out-Null
Adb shell "settings put secure location_mode 3" | Out-Null
Ok "wifi, bluetooth, location on"

# --- wifi -----------------------------------------------------------------
# -m marks the network metered, which is what makes Data Saver below actually
# restrict anything: Data Saver only applies on metered networks and wifi is
# unmetered by default.
if ($Ssid) {
    Step "joining $Ssid"
    if ($WifiPassword) {
        Adb shell "cmd wifi connect-network '$Ssid' wpa2 '$WifiPassword' -m" | Out-Null
    } else {
        Adb shell "cmd wifi connect-network '$Ssid' open -m" | Out-Null
    }
    # connect-network returns before association completes, so a wrong
    # passphrase exits 0 and leaves an offline kiosk. Assert connectivity.
    $joined = $false
    foreach ($i in 1..20) {
        Start-Sleep -Seconds 2
        if ((Adb shell "dumpsys wifi | grep -m1 'mNetworkInfo'") -match "state: CONNECTED") { $joined = $true; break }
        if ((Adb shell "settings get global wifi_on").Trim() -eq "1" -and
            (Adb shell "dumpsys connectivity | grep -m1 'NetworkAgentInfo.*VALIDATED'")) { $joined = $true; break }
    }
    if (-not $joined) { Die "did not associate with '$Ssid' within 40s — check the SSID and passphrase" }
    Ok "wifi associated"
}

# --- screen ---------------------------------------------------------------
Step "configuring screen"
Adb shell "settings put system screen_off_timeout 2147483647" | Out-Null
Adb shell "settings put system screen_brightness_mode 1" | Out-Null
Adb shell "settings put secure lock_to_app_enabled 1" | Out-Null
Adb shell "svc power stayon true" | Out-Null
Ok "never sleeps, adaptive brightness, pinning allowed"

# --- data reduction -------------------------------------------------------
# The UID is assigned at install and changes on reinstall, so it is read now
# rather than hardcoded — a stale UID would whitelist some other app.
Step "restricting background data"
$uidLine = Adb shell "dumpsys package $PKG | grep -m1 userId"
if ($uidLine -match "userId=(\d+)") {
    $uid = $Matches[1]
    Adb shell "cmd netpolicy set restrict-background true" | Out-Null
    Adb shell "cmd netpolicy add restrict-background-whitelist $uid" | Out-Null
    Ok "data saver on, $PKG (uid $uid) whitelisted"
} else {
    Die "could not read the app's uid from dumpsys"
}

Write-Host "`n  Battery protection is not settable from adb on stock Android." -ForegroundColor Yellow
Write-Host "  Enable it by hand in the device's own battery settings.`n" -ForegroundColor Yellow

Write-Host "PROVISIONED (native steps)" -ForegroundColor Green
```

- [ ] **Step 2: Run it against the emulator**

Run:
```
adb emu kill 2>$null; emulator -avd Lenovo_M9 -no-snapshot-load -no-boot-anim
./gradlew :app:assembleDebug
powershell -File tools/provision/provision.ps1 -Apk app/build/outputs/apk/debug/app-debug.apk
```

Expected: every step reports `OK` except device owner (the emulator may already have it, which the script reports as "already set"). The final line reads `PROVISIONED (native steps)`.

- [ ] **Step 3: Verify each setting actually took**

Run:
```
adb shell "settings get system screen_off_timeout"      # 2147483647
adb shell "settings get system screen_brightness_mode"  # 1
adb shell "settings get secure lock_to_app_enabled"     # 1
adb shell "cmd location is-location-enabled"            # true
adb shell "cmd netpolicy get restrict-background"       # enabled
adb shell "cmd netpolicy list restrict-background-whitelist"  # contains the app's uid
```

Every one must match. A script that reports OK for a setting that did not take is worse than one that fails.

- [ ] **Step 4: Commit**

```bash
git add tools/provision/provision.ps1
git commit -m "Add the native half of kiosk provisioning"
```

---

## Task 2: `ProvisioningLoader`

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/provisioning/ProvisioningLoader.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/provisioning/ProvisioningLoaderTest.kt`

**Interfaces:**
- Consumes: `SettingsExportFile.parse(json, password): ImportResult`, `SettingsImport.merge(current, imported): Settings`, `KioskCode.normalize(code): String`.
- Produces: `ProvisioningLoader.decide(current, payloadJson, password, kioskCodeOverride, kioskNameOverride): ProvisioningOutcome`, with `ProvisioningOutcome.Apply(settings: Settings, secrets: Map<String, String>)` and `ProvisioningOutcome.Failed(reason: String)`. Task 4 consumes both.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/sadaqah/kiosk/provisioning/ProvisioningLoaderTest.kt`:

```kotlin
package com.sadaqah.kiosk.provisioning

import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.settingsio.SettingsExportFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningLoaderTest {

    private val fast = 1000

    private val golden = Settings(
        kioskName = "Golden Bench Unit",
        kioskCode = "nl-gld-arnhem-nour_al_houda-01",
        currency = "USD",
        language = "ar",
        analyticsEnabled = true,
        analyticsPrivacyPolicyUrl = "https://example.org/privacy",
        installId = "the-golden-kiosks-id",
        testMode = true
    )

    private val secrets = mapOf(
        SettingsExportFile.KEY_AFFILIATE to "sup_afk_GOLDEN",
        SettingsExportFile.KEY_TELEMETRY_URL to "https://abc.supabase.co",
        SettingsExportFile.KEY_TELEMETRY_KEY to "sb_publishable_GOLDEN"
    )

    private fun payload(password: String? = "hunter2") =
        SettingsExportFile.build(golden, secrets, password, fast)

    private fun decide(
        current: Settings = Settings(installId = "this-device"),
        json: String? = payload(),
        password: String? = "hunter2",
        code: String? = "nl-gld-arnhem-nour_al_houda-07",
        name: String? = "Arnhem — hal"
    ) = ProvisioningLoader.decide(current, json, password, code, name)

    private fun applied(outcome: ProvisioningOutcome): ProvisioningOutcome.Apply {
        assertTrue("expected Apply but was $outcome", outcome is ProvisioningOutcome.Apply)
        return outcome as ProvisioningOutcome.Apply
    }

    private fun failed(outcome: ProvisioningOutcome): String {
        assertTrue("expected Failed but was $outcome", outcome is ProvisioningOutcome.Failed)
        return (outcome as ProvisioningOutcome.Failed).reason
    }

    /** The operator asked for provisioning and there was nothing to apply.
     *  Reporting success here would ship an unconfigured kiosk. */
    @Test
    fun anAbsentPayloadIsAFailureNotANoOp() {
        assertEquals("no_payload", failed(decide(json = null)))
    }

    @Test
    fun aWrongPasswordFails() {
        assertEquals("wrong_password", failed(decide(password = "not-it")))
    }

    @Test
    fun aMissingPasswordFails() {
        assertEquals("password_required", failed(decide(password = null)))
    }

    @Test
    fun malformedJsonFails() {
        assertEquals("malformed", failed(decide(json = "this is not json")))
    }

    @Test
    fun aValidPayloadCarriesItsSecrets() {
        val result = applied(decide())
        assertEquals("sup_afk_GOLDEN", result.secrets[SettingsExportFile.KEY_AFFILIATE])
        assertEquals("https://abc.supabase.co", result.secrets[SettingsExportFile.KEY_TELEMETRY_URL])
        assertEquals("sb_publishable_GOLDEN", result.secrets[SettingsExportFile.KEY_TELEMETRY_KEY])
    }

    /** Configuration crosses; device identity does not. */
    @Test
    fun configurationTravelsAndDeviceIdentityDoesNot() {
        val result = applied(decide())
        assertEquals("USD", result.settings.currency)
        assertEquals("ar", result.settings.language)
        assertEquals("https://example.org/privacy", result.settings.analyticsPrivacyPolicyUrl)
        assertEquals("this-device", result.settings.installId)
        assertFalse("a bench device's test mode must never reach a kiosk", result.settings.testMode)
    }

    /** The whole model is one payload cloned across a fleet, so the two fields
     *  that name the physical unit have to be replaced, not inherited. */
    @Test
    fun theOverridesReplaceTheGoldenKiosksIdentity() {
        val result = applied(decide())
        assertEquals("nl-gld-arnhem-nour_al_houda-07", result.settings.kioskCode)
        assertEquals("Arnhem — hal", result.settings.kioskName)
    }

    /** A code supplied per unit at provisioning time is this kiosk's own, so it
     *  must not raise the shared-code warning. */
    @Test
    fun anOverriddenCodeIsNotMarkedAsImported() {
        assertFalse(applied(decide()).settings.kioskCodeFromImport)
    }

    @Test
    fun anOverriddenCodeIsTrimmed() {
        val result = applied(decide(code = "  nl-gld-arnhem-nour_al_houda-07  "))
        assertEquals("nl-gld-arnhem-nour_al_houda-07", result.settings.kioskCode)
    }

    /** Refusing here is what stops a fleet reporting and billing under one
     *  identity. The script also checks, but the app is the last line. */
    @Test
    fun aMissingCodeOverrideFailsWhenThePayloadCarriesOne() {
        assertEquals("kiosk_code_required", failed(decide(code = null)))
    }

    @Test
    fun aMissingNameOverrideFailsWhenThePayloadCarriesOne() {
        assertEquals("kiosk_name_required", failed(decide(name = null)))
    }

    /** A payload with nothing to inherit needs no override. */
    @Test
    fun overridesAreOptionalWhenThePayloadCarriesNeither() {
        val blank = SettingsExportFile.build(
            golden.copy(kioskCode = "", kioskName = ""), secrets, "hunter2", fast
        )
        val result = applied(decide(json = blank, code = null, name = null))
        assertEquals("", result.settings.kioskCode)
        assertEquals("", result.settings.kioskName)
    }

    /** An export taken without ticking "include keys" parses fine and carries
     *  nothing. It must apply, and the caller reports both credentials absent
     *  so the script can refuse. */
    @Test
    fun aPayloadWithNoSecretsBlockAppliesWithAnEmptySecretsMap() {
        val noSecrets = SettingsExportFile.build(golden, emptyMap(), null, fast)
        val result = applied(decide(json = noSecrets, password = "hunter2"))
        assertTrue(result.secrets.isEmpty())
        assertEquals("USD", result.settings.currency)
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProvisioningLoaderTest*"`
Expected: compilation failure — `ProvisioningLoader` does not exist.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/sadaqah/kiosk/provisioning/ProvisioningLoader.kt`:

```kotlin
package com.sadaqah.kiosk.provisioning

import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.model.SettingsImport
import com.sadaqah.kiosk.settingsio.ImportResult
import com.sadaqah.kiosk.settingsio.SettingsExportFile
import com.sadaqah.kiosk.telemetry.KioskCode

sealed class ProvisioningOutcome {
    data class Apply(
        val settings: Settings,
        val secrets: Map<String, String>
    ) : ProvisioningOutcome()

    data class Failed(val reason: String) : ProvisioningOutcome()
}

/**
 * Decides what provisioning should do, and does no I/O.
 *
 * `MainActivity` cannot be reached from a unit test, so a decision taken there
 * is a decision nothing checks — the same reason `DisclosurePresenter` and
 * `AnalyticsPresenter` exist. The payload arrives as a string rather than a
 * path so every branch here is reachable without a filesystem.
 */
object ProvisioningLoader {

    fun decide(
        current: Settings,
        payloadJson: String?,
        password: String?,
        kioskCodeOverride: String?,
        kioskNameOverride: String?
    ): ProvisioningOutcome {
        // There is no "nothing to do" outcome. The caller gates on the trigger
        // extras before reading anything, so reaching this function means the
        // operator asked for provisioning — and silence would read as success.
        if (payloadJson.isNullOrBlank()) return ProvisioningOutcome.Failed("no_payload")

        val parsed = SettingsExportFile.parse(payloadJson, password)
        val imported = when (parsed) {
            is ImportResult.Success -> parsed
            ImportResult.WrongPassword -> return ProvisioningOutcome.Failed("wrong_password")
            ImportResult.PasswordRequired -> return ProvisioningOutcome.Failed("password_required")
            ImportResult.Malformed -> return ProvisioningOutcome.Failed("malformed")
        }

        // Both of these name the physical unit, and the model is one payload
        // cloned across a fleet. Inheriting `kioskCode` makes every unit emit
        // the signal `code` + `install_id` reserves for a re-provisioned one;
        // inheriting `kioskName` attributes every SumUp transaction in the
        // merchant's records to the golden kiosk. Refuse rather than warn.
        if (imported.settings.kioskCode.isNotBlank() && kioskCodeOverride.isNullOrBlank()) {
            return ProvisioningOutcome.Failed("kiosk_code_required")
        }
        if (imported.settings.kioskName.isNotBlank() && kioskNameOverride.isNullOrBlank()) {
            return ProvisioningOutcome.Failed("kiosk_name_required")
        }

        var merged = SettingsImport.merge(current, imported.settings)

        if (!kioskCodeOverride.isNullOrBlank()) {
            merged = merged.copy(
                kioskCode = KioskCode.normalize(kioskCodeOverride),
                // Supplied per unit by the operator, so it is this kiosk's own
                // code rather than one inherited from a shared file. Marking it
                // imported would raise a warning about the one case that is not
                // a mistake, which is how warnings get ignored.
                kioskCodeFromImport = false
            )
        }
        if (!kioskNameOverride.isNullOrBlank()) {
            merged = merged.copy(kioskName = kioskNameOverride.trim())
        }

        return ProvisioningOutcome.Apply(merged, imported.secrets)
    }
}
```

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProvisioningLoaderTest*"`
Expected: 12 tests, all green.

- [ ] **Step 5: Mutation-check the password branch**

The spec requires this one specifically: a provisioning path that silently accepts a bad password configures nothing and reports success.

Temporarily replace the `ImportResult.WrongPassword ->` arm with
`ImportResult.WrongPassword -> return ProvisioningOutcome.Apply(current, emptyMap())`,
run the tests, and confirm `aWrongPasswordFails` fails. Revert.

Then do the same for the two override guards: change `kiosk_code_required` to fall through to the merge, confirm `aMissingCodeOverrideFailsWhenThePayloadCarriesOne` fails, revert. Confirm `git diff --stat` shows only the new files afterwards.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/provisioning/ app/src/test/java/com/sadaqah/kiosk/provisioning/
git commit -m "Add the provisioning decision unit"
```

---

## Task 3: Every `Settings` field must be classified

The gap this closes is concrete: `kioskName` was never classified as device-scoped, so nothing stopped it travelling — and it reaches the payment processor on every transaction. The next field added will have the same problem unless adding one forces the decision.

**Files:**
- Test: `app/src/test/java/com/sadaqah/kiosk/model/SettingsFieldClassificationTest.kt`

**Interfaces:**
- Consumes: `SettingsImport.merge`. Produces nothing other tasks consume.

- [ ] **Step 1: Write the test**

Java reflection, not `kotlin-reflect` — the project has no such dependency and this needs no new one.

Create `app/src/test/java/com/sadaqah/kiosk/model/SettingsFieldClassificationTest.kt`:

```kotlin
package com.sadaqah.kiosk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every field of [Settings] must be deliberately classified as either
 * device-scoped (guarded by [SettingsImport.merge]) or configuration (travels).
 *
 * This exists because `kioskName` was neither: it silently travelled, and it is
 * attached to every SumUp transaction, so a fleet cloned from one export
 * attributed every payment in the merchant's records to the golden kiosk. The
 * guard set was not wrong so much as never decided.
 *
 * The guarded set is DERIVED by reflection rather than restated, so adding a
 * guard without updating this test fails immediately, and so does removing one.
 */
class SettingsFieldClassificationTest {

    /** Fields `merge` refuses to take from an imported file, keeping this
     *  device's value instead. Six. */
    private val expectedGuarded = setOf(
        "installId",
        "logoUri",
        "donationStatsStartedAtMs",
        "analyticsActivatedAtMs",
        "skipApkSignatureCheckOnce",
        "testMode"
    )

    /** Neither kept nor taken: `merge` DERIVES this one from the imported
     *  code (`kioskCodeFromImport = imported.kioskCode.isNotBlank()`). It needs
     *  its own bucket — the guarded-set derivation below looks for fields that
     *  kept this device's value, and a computed field does not, so listing it
     *  as guarded would make that assertion fail. */
    private val expectedComputed = setOf("kioskCodeFromImport")

    /** Fields that travel, each a deliberate decision that sharing it across a
     *  fleet is correct. Thirty. */
    private val expectedTravelling = setOf(
        "kioskName", "language", "currency", "kioskCode",
        "backgroundColor", "patternColor", "patternAlpha", "buttonColor", "buttonBorderColor",
        "useArabicThankYou", "useTapToPay", "thankYouDurationSec",
        "screensaverStyle", "screensaverDurationSec", "screensaverIdleTimeoutSec",
        "screensaverCustomMessage",
        "maxConsecutiveFailures", "maxRestartsBeforeGiveUp", "restartCooldownSec",
        "restartCountResetSec", "longDowntimeThresholdSec",
        "autoUpdateEnabled", "autoUpdateTargetVersion", "autoUpdateGraceDays",
        "hideUpdatePrompts", "updateRepoUrl",
        "donationTrackingEnabled",
        "analyticsEnabled", "analyticsPrivacyPolicyUrl", "analyticsTermsUrl"
    )

    private fun fieldNames(): Set<String> =
        Settings::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

    private fun valueOf(settings: Settings, name: String): Any? =
        Settings::class.java.getDeclaredField(name).apply { isAccessible = true }.get(settings)

    /** Two instances differing in every field, so "took a's value" is
     *  unambiguous for each one. */
    private val mine = Settings(
        kioskName = "mine", language = "en", currency = "EUR",
        kioskCode = "mine-01", installId = "mine-install",
        logoUri = "file:///mine.png", donationStatsStartedAtMs = 111L,
        analyticsActivatedAtMs = 111L, skipApkSignatureCheckOnce = false,
        testMode = false, kioskCodeFromImport = false,
        analyticsEnabled = false, analyticsPrivacyPolicyUrl = "https://mine/p",
        analyticsTermsUrl = "https://mine/t", autoUpdateEnabled = false,
        autoUpdateGraceDays = 1, hideUpdatePrompts = false,
        donationTrackingEnabled = false, thankYouDurationSec = 1
    )

    private val theirs = Settings(
        kioskName = "theirs", language = "nl", currency = "USD",
        kioskCode = "theirs-02", installId = "theirs-install",
        logoUri = "file:///theirs.png", donationStatsStartedAtMs = 999L,
        analyticsActivatedAtMs = 999L, skipApkSignatureCheckOnce = true,
        testMode = true, kioskCodeFromImport = true,
        analyticsEnabled = true, analyticsPrivacyPolicyUrl = "https://theirs/p",
        analyticsTermsUrl = "https://theirs/t", autoUpdateEnabled = true,
        autoUpdateGraceDays = 9, hideUpdatePrompts = true,
        donationTrackingEnabled = true, thankYouDurationSec = 9
    )

    /** The point of the whole file: a new field cannot be added to Settings
     *  without someone deciding whether it describes the device or the
     *  configuration. */
    @Test
    fun everyFieldIsClassified() {
        val classified = expectedGuarded + expectedComputed + expectedTravelling
        assertEquals("the three buckets must not overlap", classified.size,
            expectedGuarded.size + expectedComputed.size + expectedTravelling.size)
        val actual = fieldNames()
        val unclassified = actual - classified
        assertTrue(
            "new Settings field(s) $unclassified are not classified. Decide whether " +
                "each describes THIS DEVICE (add a guard to SettingsImport.merge and " +
                "list it in expectedGuarded) or the CONFIGURATION (list it in " +
                "expectedTravelling). Getting this wrong for kioskName meant a cloned " +
                "fleet billed every SumUp transaction to the golden kiosk.",
            unclassified.isEmpty()
        )
        val stale = classified - actual
        assertTrue("classified field(s) $stale no longer exist on Settings", stale.isEmpty())
    }

    /** Derived, not restated: the guard set is computed from merge's actual
     *  behaviour, so the two cannot drift apart. */
    @Test
    fun theDerivedGuardSetMatchesTheDeclaredOne() {
        val merged = SettingsImport.merge(mine, theirs)
        val derived = fieldNames().filter { name ->
            valueOf(merged, name) == valueOf(mine, name) &&
                valueOf(mine, name) != valueOf(theirs, name)
        }.toSet()
        assertEquals(expectedGuarded, derived)
    }

    /** And the other direction, so a guard added by mistake is caught too. */
    @Test
    fun everyTravellingFieldActuallyTravels() {
        val merged = SettingsImport.merge(mine, theirs)
        for (name in expectedTravelling) {
            if (valueOf(mine, name) == valueOf(theirs, name)) continue
            assertEquals(
                "$name is declared as travelling but merge kept this device's value",
                valueOf(theirs, name), valueOf(merged, name)
            )
        }
    }

    /** The computed field is derived from the imported code, not copied from
     *  either side. Pinned in both directions so a change to how it is derived
     *  cannot pass unnoticed. */
    @Test
    fun theImportedCodeFlagIsDerivedFromTheImportedCode() {
        val withCode = SettingsImport.merge(mine, theirs.copy(kioskCode = "theirs-02"))
        assertTrue("an imported non-blank code must be flagged", withCode.kioskCodeFromImport)

        val withoutCode = SettingsImport.merge(mine, theirs.copy(kioskCode = ""))
        assertFalse(
            "a blank imported code leaves nothing to warn about, whatever the " +
                "source device's own flag said",
            withoutCode.kioskCodeFromImport
        )
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:testDebugUnitTest --tests "*SettingsFieldClassificationTest*"`

The three sets were derived from `Settings.kt` as it stands — 6 guarded, 1 computed, 30 travelling, 37 total — so they should match on the first run. If `everyFieldIsClassified` fails, a field has been added since this plan was written. **Do not adjust the assertion to pass.** Read the field, decide whether it describes THIS DEVICE or the CONFIGURATION, and put it in the right set — adding a guard to `SettingsImport.merge` if it is the former.

`theDerivedGuardSetMatchesTheDeclaredOne` failing means `expectedGuarded` does not match what `merge` actually does — fix the declared set, not the derivation.

- [ ] **Step 3: Confirm it catches a new field**

Temporarily add `val someNewSetting: String = ""` to `Settings.kt`, run the test, and confirm `everyFieldIsClassified` fails naming it. Revert.

This is the check that matters: the test is worthless if a new field slips through silently.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/sadaqah/kiosk/model/SettingsFieldClassificationTest.kt
git commit -m "Require every Settings field to be classified"
```

---

## Task 4: The app's provisioning path

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/provisioning/ProvisioningResult.kt`
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt`

**Interfaces:**
- Consumes: `ProvisioningLoader.decide(...)`, `ProvisioningOutcome.Apply/Failed`.
- Produces: the result JSON contract Task 5's script polls.

- [ ] **Step 1: Write the result file's data class**

Create `app/src/main/java/com/sadaqah/kiosk/provisioning/ProvisioningResult.kt`:

```kotlin
package com.sadaqah.kiosk.provisioning

import com.google.gson.Gson

/**
 * What the bench operator reads to find out whether provisioning worked.
 *
 * The import happens inside the app, after the adb command has already exited,
 * so without this the only signal is that nothing visibly broke — and a wrong
 * password produces a kiosk that boots, looks perfect, and reports nowhere.
 *
 * No secret appears here. [installId], [kioskCode] and [kioskName] identify the
 * unit; the credentials are reported as booleans and never as values.
 */
data class ProvisioningResult(
    val runId: String,
    val status: String,
    val at: String,
    val appVersion: String,
    val reason: String? = null,
    val installId: String = "",
    val kioskCode: String = "",
    val kioskName: String = "",
    val destinationConfigured: Boolean = false,
    val affiliateKeyRestored: Boolean = false,
    val logoApplied: Boolean = false,
    /** Separate from [logoApplied] because copying bytes proves nothing about
     *  them: LogoColorExtractor.decode returns null and merely logs on a
     *  corrupt image, so a kiosk can report a logo applied and render none. */
    val logoDecodable: Boolean = false
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        const val APPLIED = "applied"
        const val FAILED = "failed"
    }
}
```

- [ ] **Step 2: Add the provisioning branch to `onCreate`**

In `MainActivity.kt`, immediately after `prefs` is assigned (`prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)`, around `:283`) and **before** anything reads `settings`, insert:

```kotlin
        // Provision, then restart. Deliberately not applied into this onCreate:
        // SecretsCrypto runs 600_000 PBKDF2 iterations, which is seconds on kiosk
        // hardware and a guaranteed ANR on the main thread — and onCreate reads
        // `settings` into seven consumers with contradictory ordering needs
        // (SettingsBootstrap must follow provisioning, authenticate() and
        // LogoColorExtractor must precede it, and RestartManager /
        // NetworkRecoveryManager / UpdateManager each snapshot settings by value
        // at construction). There is no slot that satisfies all of them.
        //
        // Restarting dissolves that instead of solving it: boot 2 is an ordinary
        // startup reading provisioned settings from disk in the normal order. It
        // also fixes the migration blocks below, which string-match the STORED
        // json — after a restart that json already holds the provisioned values,
        // so they no longer overwrite what provisioning just applied.
        if (isProvisioningRequested(intent)) {
            runProvisioning(intent)
            return
        }
```

Add these as private members of `MainActivity`:

```kotlin
    private val provisioningDir: File
        get() = File(getExternalFilesDir(null), "provisioning")

    private fun isProvisioningRequested(source: Intent): Boolean =
        !source.getStringExtra("provision_run_id").isNullOrBlank() &&
            source.getStringExtra("provision_password") != null

    private fun runProvisioning(source: Intent) {
        val runId = source.getStringExtra("provision_run_id").orEmpty()
        val password = source.getStringExtra("provision_password")
        val codeOverride = source.getStringExtra("provision_kiosk_code")
        val nameOverride = source.getStringExtra("provision_kiosk_name")

        // Consumed immediately. getIntent() otherwise returns these extras for
        // the life of the Activity AND across every recreation — a rotation
        // after a successful provision would re-enter this path, find the
        // payload deleted, and overwrite an `applied` result with
        // `failed / no_payload`.
        setIntent(Intent(source).apply { replaceExtras(Bundle()) })

        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                Text("Provisioning…", color = Color.White, fontSize = 24.sp)
            }
        }

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                // Off the main thread because SecretsCrypto runs 600_000 PBKDF2
                // iterations — seconds on kiosk hardware, and an ANR here would
                // kill the process with settings possibly written and the result
                // certainly not.
                val payloadFile = File(provisioningDir, "kiosk.json")
                val json = if (payloadFile.isFile) payloadFile.readText() else null
                ProvisioningLoader.decide(settings, json, password, codeOverride, nameOverride)
            }
            applyProvisioning(runId, outcome, password)
            relaunchAfterProvisioning()
        }
    }

    private suspend fun applyProvisioning(
        runId: String,
        outcome: ProvisioningOutcome,
        password: String?
    ) {
        val now = Instant.now().toString()
        if (outcome is ProvisioningOutcome.Failed) {
            // The payload is deliberately kept so the operator can retry with a
            // corrected password rather than re-pushing.
            writeProvisioningResult(
                ProvisioningResult(
                    runId = runId, status = ProvisioningResult.FAILED, at = now,
                    appVersion = BuildConfig.VERSION_NAME, reason = outcome.reason
                )
            )
            return
        }

        val apply = outcome as ProvisioningOutcome.Apply
        val payloadFile = File(provisioningDir, "kiosk.json")

        // Routed through importSettings rather than reimplemented: :1979 sets
        // CrashContext.affiliateKey, and a parallel path that skipped it would
        // disarm the crash handler's scrub for exactly the key just imported,
        // shipping the payment credential into crash reports on every kiosk.
        val imported = withContext(Dispatchers.Default) {
            importSettings(payloadFile.readText(), password)
        }
        val credentialsOk = imported is ImportResult.Success

        // importSettings merges without the overrides, so re-apply them over it.
        settings = apply.settings
        // installId must exist before the result names it: on a first provision
        // the device has never completed a boot, so SettingsBootstrap has never
        // run and the field is still blank.
        val bootstrapped = SettingsBootstrap.apply(settings, System.currentTimeMillis()) {
            java.util.UUID.randomUUID().toString()
        }
        settings = bootstrapped.settings

        val logo = copyProvisionedLogo()
        settings = settings.copy(logoUri = logo?.uri.orEmpty())
        saveSettings(settings)

        writeProvisioningResult(
            ProvisioningResult(
                runId = runId, status = ProvisioningResult.APPLIED, at = now,
                appVersion = BuildConfig.VERSION_NAME,
                installId = settings.installId,
                kioskCode = settings.kioskCode,
                kioskName = settings.kioskName,
                affiliateKeyRestored = credentialsOk && affiliateKey.isNotBlank(),
                destinationConfigured = telemetryCredentials.isConfigured(),
                logoApplied = logo != null,
                logoDecodable = logo?.decodable == true
            )
        )

        payloadFile.delete()
        File(provisioningDir, "logo").delete()
    }

    private fun writeProvisioningResult(result: ProvisioningResult) {
        try {
            provisioningDir.mkdirs()
            File(provisioningDir, "provision-result.json").writeText(result.toJson())
        } catch (e: Exception) {
            // Not rolled back: the import succeeded, and undoing it would be
            // worse than an unreported success.
            Log.w("Provisioning", "Could not write the result file: ${e.message}")
        }
    }

    private data class ProvisionedLogo(val uri: String, val decodable: Boolean)

    /** Copies the pushed image into internal storage and returns where it
     *  landed, or null when no image was pushed.
     *
     *  The destination is a FIXED name with NO extension, and the directory is
     *  emptied first: a preserved extension would defeat the fixed name, so
     *  re-provisioning a .png over a .jpg would leave the .jpg behind forever,
     *  unreferenced. LogoColorExtractor.decode infers format from content. */
    private fun copyProvisionedLogo(): ProvisionedLogo? {
        val source = File(provisioningDir, "logo")
        if (!source.isFile) return null
        val dir = File(filesDir, "logo").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val dest = File(dir, "kiosk-logo")
        return try {
            source.copyTo(dest, overwrite = true)
            ProvisionedLogo(
                uri = Uri.fromFile(dest).toString(),
                decodable = BitmapFactory.decodeFile(dest.absolutePath) != null
            )
        } catch (e: Exception) {
            Log.w("Provisioning", "Could not copy the logo: ${e.message}")
            null
        }
    }
```

The gate inserted in Step 2 therefore reads:

```kotlin
        if (isProvisioningRequested(intent)) {
            runProvisioning(intent)
            return
        }
```

Imports to add: `android.graphics.BitmapFactory`, `android.net.Uri`, `java.io.File`, `java.time.Instant`, `com.sadaqah.kiosk.provisioning.*`, and the Compose `Box`/`Text`/`Modifier` set if not already present.

- [ ] **Step 3: Add the relaunch**

Beside `hardRestart` (`MainActivity.kt:1839`), add:

```kotlin
    /** Restart after provisioning. Separate from [hardRestart] so the restart
     *  does not read as an auto-recovery event: hardRestart logs under
     *  "AutoRestart" against restartManager's counter, and a provisioning
     *  relaunch is neither a failure nor something the give-up latch should
     *  ever see. */
    private fun relaunchAfterProvisioning() {
        Log.i("Provisioning", "Provisioning complete, restarting into a normal boot")
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
```

- [ ] **Step 4: Copy the logo**

The destination is `filesDir/logo/kiosk-logo` with **no extension**, and the directory's contents are deleted before the copy. A preserved extension defeats the fixed name — re-provisioning a `.png` over a `.jpg` leaves the `.jpg` behind, unreferenced, forever. `LogoColorExtractor.decode` infers format from content, not from the name.

Set `logoUri` to `Uri.fromFile(dest).toString()`. `SettingsImport.merge` nulls `logoUri` on import, which stays correct — the URI in the payload describes the golden kiosk's filesystem. Provisioning sets it afterwards from the image it just copied.

Set `logoDecodable` from whether `BitmapFactory.decodeFile(dest.absolutePath)` returns non-null.

- [ ] **Step 5: Build and confirm a normal boot is untouched**

Run: `./gradlew :app:assembleDebug` then install and launch with no extras.

Expected: the app starts exactly as before. The provisioning branch is gated on the intent extras, so a normal boot does not even look for a payload.

Run the full suite: `./gradlew :app:testDebugUnitTest` — expected green, unchanged count plus Tasks 2 and 3's new tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/provisioning/ProvisioningResult.kt app/src/main/java/com/sadaqah/kiosk/MainActivity.kt
git commit -m "Provision on startup, then restart into a normal boot"
```

---

## Task 5: The script's push, trigger and poll

**Files:**
- Modify: `tools/provision/provision.ps1`

**Interfaces:**
- Consumes: the result JSON contract from Task 4.

- [ ] **Step 1: Add the payload steps to the end of the script**

Append before the final `PROVISIONED` line:

```powershell
# --- settings import ------------------------------------------------------
if ($Payload) {
    if (-not $Password)   { Die "-Payload needs -Password (the export's password)" }
    if (-not $KioskCode)  { Die "-Payload needs -KioskCode — a fleet cloned from one export otherwise reports under a single code" }
    if (-not $KioskName)  { Die "-Payload needs -KioskName — it is attached to every SumUp transaction, so a fleet would bill under one name" }

    $runId = [guid]::NewGuid().ToString()

    # The directory does not exist until the app first calls
    # getExternalFilesDir, and provisioning pushes to a package that has never
    # run. Plain adb push, NOT run.py's push_app_file: that helper chowns and
    # restorecons against the parent, which is wrong for this FUSE-backed path.
    Step "pushing payload"
    Adb shell "mkdir -p $REMOTE_DIR" | Out-Null
    Adb shell "rm -f $REMOTE_DIR/provision-result.json" | Out-Null
    Adb push $Payload "$REMOTE_DIR/kiosk.json" | Out-Null
    if ($Logo) { Adb push $Logo "$REMOTE_DIR/logo" | Out-Null }
    Ok "payload in place"

    # force-stop is mandatory, not hygiene. MainActivity has no launchMode and
    # no onNewIntent, so `am start` against a running app brings the existing
    # task to the front and DISCARDS the extras — onCreate never re-runs and the
    # trigger silently does nothing.
    Step "triggering import"
    Adb shell "am force-stop $PKG" | Out-Null
    Start-Sleep -Seconds 2
    Adb shell ("am start -n $PKG/.MainActivity " +
               "--es provision_run_id '$runId' " +
               "--es provision_password '$Password' " +
               "--es provision_kiosk_code '$KioskCode' " +
               "--es provision_kiosk_name '$KioskName'") | Out-Null

    Step "waiting for the device to report"
    $result = $null
    foreach ($i in 1..60) {
        Start-Sleep -Seconds 2
        $raw = Adb shell "cat $REMOTE_DIR/provision-result.json 2>/dev/null"
        if ($raw) {
            try { $parsed = $raw | ConvertFrom-Json } catch { continue }
            # A stale result from an earlier attempt or another device is
            # indistinguishable from this one's without the run id. The "at"
            # timestamp cannot rescue it: a factory-reset tablet has an unsynced
            # clock.
            if ($parsed.runId -eq $runId) { $result = $parsed; break }
        }
    }
    if (-not $result) { Die "no result after 120s — the app may have crashed or never started" }

    if ($result.status -ne "applied") { Die "provisioning failed: $($result.reason)" }

    # A golden export taken without ticking "include keys" parses fine and
    # carries nothing, so `applied` alone would pass a kiosk that takes no
    # payments and reports nowhere.
    if (-not $result.affiliateKeyRestored)  { Die "applied, but no affiliate key — was the export taken with keys included?" }
    if (-not $result.destinationConfigured) { Die "applied, but no reporting destination — was the export taken with keys included?" }
    if ($Logo -and -not $result.logoDecodable) { Die "logo was copied but could not be decoded — check the image" }

    Ok "imported: $($result.kioskCode) / $($result.kioskName), install $($result.installId)"

    # Cleared only on success. On failure the app keeps the payload so the
    # operator can retry with a corrected password without re-pushing.
    Adb shell "rm -f $REMOTE_DIR/kiosk.json $REMOTE_DIR/logo $REMOTE_DIR/provision-result.json" | Out-Null
}
```

- [ ] **Step 2: Produce a payload to test with**

**From the app's own exporter, never a reimplementation.** A Python or PowerShell reimplementation of PBKDF2 + AES-GCM would be a second `SecretsCrypto` that can drift, and a drift makes the whole check pass against a format the app no longer writes.

Configure the emulator by hand (colours, currency, kiosk name, analytics destination), then Settings → Export, tick the include-keys box, set a password, and pull the file:

```
adb pull /sdcard/Download/<exported>.json bench/golden.json
```

- [ ] **Step 3: Run the whole script end to end**

```
powershell -File tools/provision/provision.ps1 `
  -Apk app/build/outputs/apk/debug/app-debug.apk `
  -Payload bench/golden.json -Password '<the export password>' `
  -KioskCode 'nl-gld-arnhem-nour_al_houda-07' -KioskName 'Arnhem hal' `
  -Logo bench/logo.png
```

Expected: every step OK, then `imported: nl-gld-arnhem-nour_al_houda-07 / Arnhem hal, install <uuid>`.

- [ ] **Step 4: Verify the failure paths report rather than hang**

Each of these must exit non-zero with a clear message, not hang or report success:

1. Wrong password → `provisioning failed: wrong_password`
2. Omit `-KioskCode` → refused before anything is pushed
3. Corrupt payload (`echo nonsense > bad.json`) → `provisioning failed: malformed`
4. Run twice in a row with the same arguments → the second run must also succeed, proving the force-stop makes the trigger repeatable. **This is the check for the defect that made the first design unworkable.**

- [ ] **Step 5: Commit**

```bash
git add tools/provision/provision.ps1
git commit -m "Add payload push, trigger and result polling"
```

---

## Task 6: The round-trip device check

Provision from a known payload, export from the provisioned device, compare. The only check that exercises the payload, the guards, the credential layer and the result file together.

**Files:**
- Modify: `tools/kiosk-check/run.py`

- [ ] **Step 1: Add the check**

Add to `run.py`, in a new session `P`. The fixture and password come from the environment so the harness never holds a credential of its own, and the check skips with a clear reason when they are absent rather than silently passing:

```python
# Fields that MUST differ between the payload and a provisioned device's own
# export. Everything not listed here must match exactly. Kept beside the check
# rather than inline so the reason for each is readable.
PROVISION_EXPECTED_DIFFS = {
    # Guarded by SettingsImport.merge — device-scoped by design.
    "installId", "logoUri", "donationStatsStartedAtMs",
    "analyticsActivatedAtMs", "skipApkSignatureCheckOnce", "testMode",
    # Derived by merge, then forced false by the code override.
    "kioskCodeFromImport",
    # Replaced per unit at provisioning time.
    "kioskCode", "kioskName",
}


@check("P1", "P", "A provisioned device exports the configuration it was given")
def p1(ctx):
    """Round trip: provision from a known payload, export, compare.

    A raw file comparison fails on a CORRECT provisioning: salt, iv and
    ciphertext are regenerated from SecureRandom on every export, so the
    envelope differs even for identical plaintext. This compares decoded
    settings against a declared exclusion set, in both directions.
    """
    payload_path = os.environ.get("KIOSK_PAYLOAD")
    password = os.environ.get("KIOSK_PAYLOAD_PASSWORD")
    if not payload_path or not password:
        raise Failure(
            "set KIOSK_PAYLOAD to an export taken from a configured device and "
            "KIOSK_PAYLOAD_PASSWORD to its password. The fixture must come from "
            "the app's own exporter -- a reimplementation of SecretsCrypto here "
            "would be a second implementation that can drift, and a drift makes "
            "this check pass against a format the app no longer writes."
        )
    if not os.path.isfile(payload_path):
        raise Failure(f"KIOSK_PAYLOAD does not exist: {payload_path}")

    with open(payload_path, encoding="utf-8") as f:
        source = json.load(f)["settings"]

    # Only meaningful against a clean device: on one with stored settings the
    # onCreate migrations also rewrite longDowntimeThresholdSec, the four
    # autoUpdate fields and analyticsEnabled, and the diff becomes unreadable.
    reinstall_clean(ctx.apk)
    launch()

    code = "nl-gld-arnhem-nour_al_houda-07"
    name = "Arnhem hal"
    subprocess.run(
        ["powershell", "-File", "tools/provision/provision.ps1",
         "-Apk", ctx.apk, "-Payload", payload_path, "-Password", password,
         "-KioskCode", code, "-KioskName", name],
        check=True, capture_output=True, timeout=600,
    )

    provisioned = settings_json()
    expect(provisioned, "no settings on the device after provisioning")

    mismatched = []
    for key, want in source.items():
        if key in PROVISION_EXPECTED_DIFFS:
            continue
        got = provisioned.get(key)
        if got != want:
            mismatched.append(f"{key}: payload={want!r} device={got!r}")
    expect(
        not mismatched,
        "fields that should have transferred did not:\n  " + "\n  ".join(mismatched),
    )

    # The other direction: an excluded field that happens to match means the
    # exclusion is wrong or the guard stopped working. installId is the one
    # that matters -- if it matched, every kiosk in a fleet would share one.
    expect(
        provisioned.get("installId") != source.get("installId"),
        "the device kept the payload's installId; SettingsImport.merge is no "
        "longer guarding it and a cloned fleet would share one identity",
    )
    expect(provisioned.get("kioskCode") == code, "the code override did not take")
    expect(provisioned.get("kioskName") == name, "the name override did not take")
    expect(not provisioned.get("kioskCodeFromImport"),
           "a per-unit code was flagged as imported, which would warn on the one case that is correct")

    checked = len(source) - len(PROVISION_EXPECTED_DIFFS & set(source))
    return [
        f"{checked} configuration fields transferred exactly",
        f"{len(PROVISION_EXPECTED_DIFFS)} device-scoped fields correctly differ",
        f"overrides applied: {code} / {name}",
    ]
```

Add `import subprocess` at the top of `run.py` if not already present.

The exclusion set above is the whole contract, and each entry has a reason:

**Compare decrypted secrets, never the envelope.** `salt`, `iv` and `ciphertext` come from `SecureRandom` on every `encrypt`, so they differ even for identical plaintext.

**Must differ — guarded by `merge`:** `installId`, `logoUri`, `donationStatsStartedAtMs`, `analyticsActivatedAtMs`, `skipApkSignatureCheckOnce`, `testMode`, `kioskCodeFromImport`.

**Must differ — changed by provisioning:** `kioskCode`, `kioskName`, and `telemetryUrl`, which round-trips through `TelemetryUrl.check` (lowercases the scheme, strips a trailing slash — `TelemetryCredentials.kt:85-86`), so a source written `HTTPS://…/` will not match byte for byte.

**Must match exactly:** everything else, including every colour, `patternAlpha`, `language`, `currency`, both policy URLs, and the decrypted `affiliateKey` and `telemetryKey`.

- [ ] **Step 2: Run only against a clean device**

Add to the check's docstring and to the checklist: **run this only against a freshly reset or reinstalled device.** On a device with pre-existing stored settings the `onCreate` migrations (`MainActivity.kt:431-465`) also touch `longDowntimeThresholdSec`, `autoUpdateEnabled`, `autoUpdateTargetVersion`, `autoUpdateGraceDays`, `updateRepoUrl` and `analyticsEnabled`, and the diff becomes unexplainable. On a clean device only the `longDowntimeThresholdSec` floor applies.

- [ ] **Step 3: Run it**

Run: `python tools/kiosk-check/run.py --session P`
Expected: PASS, listing which fields differed by design and confirming the rest matched.

- [ ] **Step 4: Commit**

```bash
git add tools/kiosk-check/run.py
git commit -m "Add the provisioning round-trip check"
```

---

## Task 7: Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/hardware-test-checklist.md`

- [ ] **Step 1: Document provisioning in the README**

A new section after First-Time Setup covering: what the command does, the golden-kiosk workflow, the required per-unit overrides and why they are required, and the two manual steps (battery protection, and factory-reset-with-wizard-skipped before device owner can be set).

- [ ] **Step 2: Add the provisioning session to the checklist**

A Session P with the round-trip check and the failure paths from Task 5 Step 4, marked `[auto]` where the runner covers them.

- [ ] **Step 3: Commit**

```bash
git add README.md docs/hardware-test-checklist.md
git commit -m "Document kiosk provisioning"
```

---

## Verification before finishing

- [ ] `./gradlew :app:testDebugUnitTest` — green, with Tasks 2 and 3's tests included
- [ ] `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL
- [ ] A normal boot with no extras behaves exactly as before
- [ ] The script run twice in a row succeeds both times
- [ ] All four failure paths from Task 5 Step 4 report rather than hang
- [ ] The mutation checks in Task 2 Step 5 and Task 3 Step 3 both confirmed
- [ ] Attribution scan across the whole branch, not per commit
- [ ] Whole-branch review by a fresh opus reviewer
- [ ] `superpowers:finishing-a-development-branch` → push, stacked PR

## Not in this plan

- Field re-provisioning, and `onNewIntent`. Both deliberately excluded — see the spec.
- Removing bloat packages and disabling core services. Measured on real hardware first.
- Battery protection, until the settings diff identifies a key.
- Excluding `app_prefs.xml` from backup. Raised in review and ruled acceptable on 2026-09-16: the affiliate key is one merchant account's and is identical on every kiosk, so provisioning does not multiply the exposure.
