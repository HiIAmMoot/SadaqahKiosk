# adb auto-provisioning — design

**Status:** spec
**Supersedes:** the "groundwork for adb auto-provisioning" notes in the phase 2b plan
**Related:** `docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md`

---

## What this is for

Configuring a kiosk by hand takes a person through the setup screen, the SumUp login, the colour pickers, the logo picker, the analytics screen and the policy URL fields. Doing that twenty times produces twenty chances to mistype a Supabase key, and the mistakes are quiet: a kiosk with a wrong destination looks identical to a working one until somebody notices it never reported.

This replaces that with one command against a freshly reset tablet.

The goal is not to automate a rare task for its own sake. It is that **every step this removes is a step that can be got wrong invisibly.**

---

## The provisioning model

**Bench only.** Kiosks are provisioned on a bench before deployment. USB debugging is off in the field, so the provisioning path is only ever reachable on a device someone physically holds and has just reset.

That decision is what makes the rest of this design simple, and every security property below rests on it. If field re-provisioning ever becomes a goal, this spec needs rewriting rather than extending.

**One shared payload, per-kiosk overrides.** Configure one golden kiosk by hand, export it, and push that same file to every unit. What differs per kiosk — its code, optionally its logo — comes from command arguments.

---

## Split: what adb already does, and what the app must gain

### Native, no app change

Every one of these was verified against an API 31 emulator before being written down.

| Step | Command |
|---|---|
| Install | `pm install -r -g <apk>` |
| Device owner | `dpm set-device-owner com.sadaqah.kiosk/.KioskDeviceAdminReceiver` |
| Wi-Fi radio on | `svc wifi enable` |
| Wi-Fi network | `cmd wifi connect-network "<ssid>" wpa2 "<pass>"` |
| Bluetooth on | `svc bluetooth enable` |
| Location services on | `cmd location set-location-enabled true` |
| Location mode | `settings put secure location_mode 3` |
| Screen never sleeps | `settings put system screen_off_timeout 2147483647` |
| Adaptive brightness | `settings put system screen_brightness_mode 1` |
| Screen pinning fallback | `settings put secure lock_to_app_enabled 1` |

**`connect-network` saves as well as connects** — its own help says "and add to saved networks list" — so the kiosk rejoins after a reboot without anything further. `add-network` is the variant for an access point that is not in range on the bench.

**Location services, not the location permission.** The permission is already handled: `MainActivity.kt:296-307` grants `ACCESS_FINE_LOCATION` (and `NEARBY_WIFI_DEVICES` on API 33+) through `setPermissionGrantState` as soon as the app is device owner. What is not handled is the system-wide services toggle. `BLUETOOTH_SCAN` is declared without `neverForLocation`, so Android still treats BLE discovery as location-deriving and the card reader will not be found with location off — and a factory-reset tablet has it off until setup wizard runs, which is the state provisioning starts from.

**Screen pinning is a fallback, not the mechanism.** `MainActivity.kt:292` calls `setLockTaskPackages` when the app is device owner, and `startLockTask()` then enters lock task silently, with no blue bar, no confirmation dialog and no dependency on `lock_to_app_enabled`. That setting governs the consumer screen-pinning feature, which is the degraded mode a kiosk falls into when device-owner provisioning did not happen. It is set anyway because it costs nothing and makes the degraded mode work.

### Ordering

The command is order-free except for one hard pair:

1. `pm install` — the admin component must exist before it can be named.
2. `dpm set-device-owner` — and this **fails if any account exists on the device**, which in practice means provisioning runs against a freshly reset tablet.

Everything else can run in any order.

### Data reduction

Some kiosks run on a metered connection, so background traffic from apps that have nothing to do with taking donations is a running cost. This is handled in two layers, deliberately separated by how much they can break.

#### Layer 1 — restrict background data. Ships with provisioning.

```
cmd wifi connect-network "<ssid>" wpa2 "<pass>" -m    # -m marks the network METERED
cmd netpolicy set restrict-background true             # Data Saver
cmd netpolicy add restrict-background-whitelist <uid>  # this app only
```

**The `-m` is load-bearing and easy to miss.** Data Saver restricts background data only on *metered* networks, and Wi-Fi is unmetered by default. Without it, Data Saver reports itself as `enabled`, nothing is actually restricted, and the configuration looks correct while doing nothing.

The UID is assigned at install and **changes whenever the app is reinstalled**, so the provisioning script reads it from `dumpsys package` at run time. A hardcoded UID would silently whitelist some other app after the next reinstall — including, eventually, none.

Nothing is disabled or removed by this layer, and every part of it is reversible with `cmd netpolicy remove …` and `set restrict-background false`.

#### Layer 2 — removing bloat. Measured first, and not part of this spec's command.

The temptation is to write a list of packages to strip. That list cannot be written from here: the emulator runs a Google APIs image, not Lenovo's, so any list produced against it would be confident and wrong about the device that matters.

**Measure, then cut.** Android accounts network usage per UID, so the question is directly answerable:

```
dumpsys netstats detail          # per-uid byte counts
pm list packages -U              # uid -> package name
```

Provision a kiosk, leave it on the bench for a day on the real network, then map usage to packages and cut from evidence.

**Try restriction before removal.** Two per-app levers are gentler than unlinking a package and fully reversible:

```
am set-standby-bucket <pkg> restricted             # jobs and alarms heavily limited
cmd appops set <pkg> RUN_ANY_IN_BACKGROUND deny    # hard stop on background execution
```

Undone with `set-standby-bucket <pkg> active` and `appops set <pkg> RUN_ANY_IN_BACKGROUND allow`. Nothing is unlinked, so a wrong call here cannot boot-loop the device — which makes this the right first move on anything the day's measurements flag.

**Battery Saver is not one of these levers, despite looking like the obvious one.** It is rejected for two independent reasons. It **turns itself off while the device is charging**, and a kiosk is permanently plugged in — so it would read as enabled on the bench and be inactive for the entire deployed life of the unit. And were it ever active, it defers jobs and alarms indiscriminately, which is precisely the telemetry flush, the 02:00 SumUp reinit, the update watchdog and the Bluetooth watchdog. It would restrict this app's own recovery mechanisms alongside the bloat's. Per-app restriction is both narrower and actually in force.

**Removal uses `pm uninstall --user 0 <package>`**, which despite the name does not delete anything: it unlinks the package for user 0 and leaves the APK in `/system`. `pm install-existing <package>` restores it, and a factory reset restores everything. That reversibility is why this is the mechanism rather than `pm disable-user`.

**Two traps, both worth stating before anyone starts cutting.**

*Google Play Services is the obvious target and the most dangerous one.* It is typically among the largest background consumers, and the SumUp SDK is likely to depend on it. Removing it could leave a kiosk that boots perfectly, looks configured, and fails at the first real card payment — the worst possible failure shape for this product.

*Recovery costs a bench visit.* USB debugging is off in the field by design, so a kiosk broken by a bad removal cannot be fixed where it stands. Every candidate needs `pm install-existing` recorded beside it, and the list needs a completed test payment before it is trusted.

So Layer 2 produces a **checked-in list with a per-package undo**, built from one device's measurements and verified by taking a real payment afterwards. It is follow-on work, not a step in the provisioning command.

### Not determinable from here

**There is no universal Android interface for charge limiting. Verified against the API 35 SDK, not assumed.**

Three classes were checked in `platforms/android-35/android.jar`, which is the newest installed platform and this app's `targetSdk`:

- `BatteryManager` exposes six `BATTERY_PROPERTY_*` constants — capacity, charge counter, current now, current average, energy counter, status. Every one is read-only telemetry. There is no `CHARGING_POLICY` constant and no `setChargingPolicy`.
- `DevicePolicyManager` contains **no** battery or charging symbols at all, so device owner confers no lever here either.
- `PowerManager` offers only `getBatteryDischargePrediction` and `isIgnoringBatteryOptimizations`, both about this app's own background scheduling rather than the charger.

The reason is structural: the charge ceiling is enforced by the charger IC and vendor kernel, and AOSP's health HAL only reports battery state upward. There is no downward call for a public API to bind to, which is why every vendor implements this privately.

**Where it exists on real hardware**, in descending order of reachability: a vendor settings key (Samsung's `protect_battery`, reachable from adb); a vendor sysfs node (`charge_control_limit`, `store_mode`, needing root, so unavailable on a production kiosk); or a vendor app feature with no external interface, which cannot be automated at any price.

**A specific percentage window is unlikely regardless.** Where OEMs ship this it is normally a boolean behind a vendor-chosen ceiling — Samsung caps around 85%, Lenovo's tablet "Battery protection" around 60% when kept plugged in. The intent (don't hold the cell at 100% indefinitely) is achievable; a configurable range is not.

**If the device turns out to have no reachable key, the concept is still achievable outside Android.** A permanently plugged-in kiosk sits at 100% continuously, which is the degradation being avoided; a timer or smart plug cycling the supply reaches the same outcome without the tablet's cooperation. That belongs in deployment guidance rather than in this command, but it means an unreachable key is not a dead end.

Nothing matching `batt` or `charg` exists in the emulator's `global`, `secure` or `system` tables, and the four common vendor keys (`protect_battery`, `battery_protection`, `adaptive_charging_enabled`, `charging_limit`) all read `null`. The emulator's `/sys/class/power_supply/battery` carries no `charge_control_limit`, `store_mode` or `slate_mode` node either.

The realistic outcome of the investigation below is therefore a single vendor on/off key, if anything reachable at all.

The way to find out, run once on the real M9:

```
adb shell "settings list global; settings list secure; settings list system" > before.txt
#   toggle Battery protection by hand on the device
adb shell "settings list global; settings list secure; settings list system" > after.txt
diff before.txt after.txt
```

Until that is done, battery protection is a **manual bench step** and the provisioning script says so in its output rather than silently omitting it.

---

## The payload

**The existing settings export file, unchanged.**

Provisioning and the import an operator does by hand share one format, one parser and one set of guards. A second format would be a second thing to keep in step with `Settings` as fields are added, and the two would drift — the provisioning path being the one nobody exercises by hand.

Reuse also inherits, for free, every device-scoped guard `SettingsImport.merge` already enforces: `installId`, `logoUri`, `donationStatsStartedAtMs`, `analyticsActivatedAtMs`, `skipApkSignatureCheckOnce` and `testMode` all refuse to travel, each for a reason already argued and tested.

The export now carries the reporting destination as well as the affiliate key, both inside the password-encrypted envelope.

### Where it goes

`/sdcard/Android/data/com.sadaqah.kiosk/files/provisioning/`

This is the app's own external files directory, reachable as `getExternalFilesDir(null)`. It was chosen for a property that matters:

- **adb can write it without root** — verified with `adb unroot` against an unrooted shell.
- **The app can read it with no storage permission**, because it owns it.
- **No other app can touch it.** Since Android 11, `Android/data` is per-package restricted.

So placing a payload requires being adb with physical access, or being this app. `/data/local/tmp` fails the second test (shell-only, the app cannot read it) and plain `/sdcard` fails the third and needs a storage permission.

### The password

Passed as an argument to the provisioning script and handed to the app as an intent extra. It reaches the bench machine's shell history and process list, and — verified, not assumed — does not reach the device's storage. See Security below.

The cost is that it appears in the bench machine's shell history and process list. That is accepted: the bench is trusted, and the password protects a file that is itself deleted moments later.

---

## The trigger

```
adb shell am force-stop com.sadaqah.kiosk
adb shell am start -n com.sadaqah.kiosk/.MainActivity \
  --es provision_run_id '<uuid>' \
  --es provision_password '<password>' \
  --es provision_kiosk_code '<code>' \
  --es provision_kiosk_name '<name>'
```

**No new exported component.** `MainActivity` is already the exported launcher; reading extras from the intent that started it adds no surface that was not already there. A broadcast receiver would have added one.

An extra alone does nothing: the payload must also be present in a directory only adb or the app can write. Both together mean physical access with debugging enabled.

### The force-stop is mandatory, not hygiene

`MainActivity` is declared with **no `android:launchMode`** (`AndroidManifest.xml:50-61`), so it is `standard`, and there is **no `onNewIntent` override** in the codebase. `am start` carries `FLAG_ACTIVITY_NEW_TASK`; against an already-running app it brings the existing task to the front and **discards the intent, extras and all**. `onCreate` does not re-run.

So a trigger against a running kiosk silently does nothing. No result, no failure, no reason — and lock task guarantees the app is always running after the first launch.

This project has already been bitten by this. `tools/kiosk-check/run.py:127-131` carries the note: *"`monkey` merely foregrounded an already-running task so onCreate never re-ran."*

The script therefore force-stops before every trigger. `onNewIntent` is deliberately **not** added: it would make provisioning reachable on a live, configured kiosk, which is the one thing the bench-only model exists to prevent.

### The extras are consumed once

After reading them, the app calls `setIntent(Intent(intent).apply { replaceExtras(Bundle()) })`.

`getIntent()` otherwise returns the launching intent for the life of the Activity **and across every recreation** — configuration change, locale change, task restore after process death. `onCreate` is written to run again on recreation (see the guards at `MainActivity.kt:382-384`). Without consuming them, the first rotation after a successful provision re-enters `onCreate` with `provision_password` still set, finds the payload deleted, and overwrites the `applied` result with `failed / no_payload`.

### Required overrides

`provision_kiosk_code` and `provision_kiosk_name` are **required whenever the payload carries a non-blank value for them**. The script refuses to run otherwise.

Both name the physical unit, and the whole model is one payload cloned across a fleet:

- `kioskCode` — `Settings.kt:51-63` already spells out the consequence: every unit reports under the golden kiosk's code, and `code` + `install_id` permanently emits the signal reserved for a re-provisioned unit.
- `kioskName` — worse, and previously unnoticed. `MainActivity.kt:1355-1356` uses it as the SumUp checkout title and `:1377-1378` attaches it to **every payment** as `KioskNaam`. A cloned fleet attributes every transaction in the merchant's records to the golden kiosk. It also satisfies the setup-status checklist row (`SetupStatusScreen.kt:41,84`), so nothing on the device flags it.

Making them optional would build the machine that produces those outcomes and then make the mitigation opt-in. Provisioning is the one path where the operator always knows the per-unit values.

A code supplied this way is recorded as **locally set**, so it does not raise the imported-code warning — it genuinely is this kiosk's own.

### `provision_run_id`

Echoed back in the result file. Without it a stale `provision-result.json` — from a previous attempt, another device, or an interrupted run — is indistinguishable from this run's, and the script reports someone else's outcome. The `"at"` timestamp cannot rescue this: a factory-reset tablet with no network has an unsynced clock.

The script also deletes any existing result before triggering. Both, because either alone leaves a window.

---

## What the app gains

### Provision, then restart

**The import runs on a background thread, and the app restarts itself when it finishes.** It does not apply settings into a live `onCreate` sequence.

Two independent reasons, either sufficient:

**Key derivation cannot run on the main thread.** `SecretsCrypto.kt:35` sets `ITERATIONS = 600_000`. Both existing entry points are documented *"Runs key derivation, so call it off the UI thread"* (`MainActivity.kt:1936-1938`, `:1963-1965`), and the only real caller obeys it — `SettingsScreen.kt:711-712` wraps the import in `withContext(Dispatchers.Default)`. On a Lenovo M9 this is seconds. Inline in `onCreate` it is a guaranteed ANR on the one boot that matters, and an ANR-killed process leaves settings possibly written and the result certainly not.

**There is no correct insertion point.** `onCreate` reads `settings` into seven consumers with contradictory requirements. `SettingsBootstrap` (`:365-371`) must run *after* provisioning or it mints an `installId` that provisioning then overwrites; `authenticate(affiliateKey)` (`:343-353`) must run *after* or the freshly imported key is never authenticated this boot; `LogoColorExtractor.refresh` (`:358`) must run after or it extracts swatches from the old logo; and `RestartManager`, `NetworkRecoveryManager` and `UpdateManager` (`:472-491`) each snapshot `settings` **by value** at construction and never re-read, so anything applied after them is inert until the next boot. No single slot satisfies all of them.

Restarting dissolves the problem rather than solving it. Boot 2 is an ordinary startup that reads provisioned settings from disk in the normal order, with no special cases anywhere in `onCreate`.

It also fixes a problem that would otherwise be invisible. `MainActivity.kt:441-465` decides whether to reset `autoUpdateEnabled`, `autoUpdateTargetVersion`, `autoUpdateGraceDays`, `updateRepoUrl` and `analyticsEnabled` by string-matching the **stored JSON** read at `:315`. Applied mid-`onCreate`, those migrations would read the pre-provisioning string and overwrite values provisioning had just set. After a restart the stored JSON already contains them, so the migrations correctly see them present.

The app already restarts itself — `hardRestart` exists and the crash path uses it — so no new mechanism is needed.

### The flow

Boot 1, when `provision_run_id` and `provision_password` are both present:

1. Consume the extras (`setIntent`), so a recreation cannot re-enter this path.
2. Show a minimal "Provisioning…" surface. No translations: this is only ever seen on a bench, by one person, in English.
3. On `Dispatchers.Default`: read the payload, call `ProvisioningLoader.decide`.
4. On `Apply` — **route through `importSettings`**, not a parallel apply path. See below.
5. Apply the `kioskCode` / `kioskName` overrides, marking the code locally set.
6. Copy the logo into internal storage; point `logoUri` at the copy.
7. Run `SettingsBootstrap.apply` so `installId` exists before the result names it.
8. Write `provision-result.json`, echoing `provision_run_id`.
9. Delete the payload and the pushed image — on success only.
10. Restart the process.

On `Failed`: write the result with the reason, **keep** the payload, and restart anyway so the kiosk is not left sitting on a provisioning screen.

### Why step 4 says "route through `importSettings`"

`importSettings` (`MainActivity.kt:1953-1994`) does three things a fresh apply path would plausibly miss, and one of them is a security defect:

- `:1975-1976` stores the affiliate key in prefs.
- **`:1979` sets `CrashContext.affiliateKey`**, with the comment: *"A stale key here disarms the crash handler's exact-match scrub for exactly the key that was just imported."* `KioskCrashHandler` reads it through the supplier at `KioskCrashHandler.kt:15,40`. Skip it and every provisioned kiosk ships its payment credential into crash reports in the clear, fleet-wide.
- `:1972` calls `TranslationManager.setLanguage` for the imported language.

The spec's whole payload argument is reuse. That argument has to extend to the apply path, not stop at the parser.

### `ProvisioningLoader` — the decision unit

Pure, does no I/O. `MainActivity` is unreachable from unit tests, so a decision made there is a decision nothing checks.

```kotlin
sealed class ProvisioningOutcome {
    data class Apply(
        val settings: Settings,
        val secrets: Map<String, String>
    ) : ProvisioningOutcome()
    data class Failed(val reason: String) : ProvisioningOutcome()
}

object ProvisioningLoader {
    fun decide(
        current: Settings,
        payloadJson: String?,
        password: String?,
        kioskCodeOverride: String?,
        kioskNameOverride: String?
    ): ProvisioningOutcome
}
```

`decide` takes the payload as a **string**, not a path, so every branch is reachable without a filesystem. It returns the secrets map rather than named credential fields — the caller hands it to `importSettings`, which already knows the key names.

There is no `Nothing` outcome: the caller gates on the extras before reading anything, so `decide` is only called when provisioning was actually requested. A payload that is absent at that point is a `Failed`, not a no-op — the operator asked for provisioning and silence would read as success.

### The logo

Copied into internal storage, not referenced where it lands.

`logoUri` is resolved at render time. Pointing it at the provisioning directory would mean the logo vanishes when that directory is cleaned — which this flow does by design.

The destination is `filesDir/logo/kiosk-logo` with **no extension**, and the directory's contents are deleted before the copy. A preserved extension defeats the fixed name: re-provisioning a `.png` over a `.jpg` would leave the `.jpg` behind, unreferenced, forever. `LogoColorExtractor.decode` infers format from content, not from the name.

Sturdier than the existing path, too: a logo chosen through the settings screen arrives as a `content://` SAF URI depending on a persisted permission grant that can be revoked. A `file://` URI into our own storage cannot.

`SettingsImport.merge` continues to null `logoUri`. That stays correct — the URI in the payload describes the golden kiosk's filesystem. Provisioning sets it afterwards from the image it just copied.

---

## The result file

`…/files/provisioning/provision-result.json`

```json
{
  "runId": "9f2c…",
  "status": "applied",
  "at": "2026-09-16T11:02:31Z",
  "appVersion": "1.4.0-preview",
  "installId": "…",
  "kioskCode": "nl-gld-arnhem-nour_al_houda-07",
  "kioskName": "Nour Al Houda — hal",
  "destinationConfigured": true,
  "affiliateKeyRestored": true,
  "logoApplied": true,
  "logoDecodable": true
}
```

On failure, `"status": "failed"` and `"reason"` naming the step.

**This is the difference between a provisioning tool and a hopeful one.** The import happens inside the app, after the adb command has exited. Without a result, a wrong password produces a kiosk that boots, looks perfect, and reports nowhere.

**`logoDecodable` is separate from `logoApplied`** because copying bytes proves nothing about them. `LogoColorExtractor.decode` returns null and merely logs on a corrupt image (`LogoColorExtractor.kt:62-66`), so a kiosk can report a logo applied and render none.

**No secret appears in the result.** `installId`, code and name identify the unit; the destination and key are booleans.

### What the script requires before it reports success

`status == "applied"` **and** `runId` matching the one it sent **and** `affiliateKeyRestored` **and** `destinationConfigured`, unless the operator explicitly asked for a settings-only provision.

`SettingsExportFile.parse` returns `Success` with an empty secrets map when there is no `secrets` block (`SettingsExportFile.kt:63-66`), and `build` omits that block entirely when no password was given (`:51-53`). So a golden export taken without ticking "include keys" yields `applied` with both credentials absent — twenty kiosks that boot, look configured, take no payments and report nowhere, with the script exiting 0.

---

## Failure modes

| Failure | Behaviour |
|---|---|
| No trigger extras | Normal boot. Provisioning is not attempted. |
| Trigger, no payload | `failed` / `no_payload`. The operator asked; silence would read as success. |
| Wrong password | `failed` / `wrong_password`, payload kept. |
| Malformed payload | `failed` / `malformed`, payload kept. |
| Payload has no secrets block | `applied`, both credential flags false. **Script exits non-zero.** |
| Required override missing | Script refuses before triggering. Never reaches the app. |
| Imported URL rejected by validation | `applied`, `destinationConfigured: false`. Previous destination untouched — `TelemetryCredentials.save` returns early at `:121`/`:123` and writes nothing. |
| Credential store unusable | `failed` / `credential_store_failed`. **The previous destination is destroyed**, not preserved: `TelemetryCredentials.kt:128-131` calls `clear()` on a storage failure, removing both keys. Real on a freshly reset tablet where the Keystore is not yet usable. Script treats it as fatal. |
| Logo named but missing | Settings applied, `logoApplied: false`. A missing image is not a reason to discard a correct configuration. |
| Logo present but undecodable | `applied`, `logoApplied: true`, `logoDecodable: false`. |
| Result cannot be written | Logged; import not rolled back. It succeeded, and undoing it would be worse than an unreported success. |
| App crashes or never starts | No result. **The script's poll needs a timeout** or it hangs forever. |
| `dpm set-device-owner` refused | Two distinct causes: an account exists, or setup wizard completed (`user_setup_complete=1`). Same opaque error, different fix. The script reports both. |
| Wi-Fi association fails | `cmd wifi connect-network` returns before association completes, so a wrong passphrase exits 0. The script asserts connectivity **before** triggering. |

---

## Ordering

Only `pm install` → `dpm set-device-owner` is forced by the platform, but **the trigger has more dependencies than anything else and must come last**:

- **After `dpm set-device-owner`** — `MainActivity.kt:289-308` runs `setLockTaskPackages` and the silent `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES` grants only `if (dpm.isDeviceOwnerApp(packageName))` at the moment `onCreate` runs. Trigger first and the app misses both until the next boot.
- **After Wi-Fi is associated** — `:312-313` reads `isNetworkAvailable` once at startup and the `authenticate()` path at `:351` is network-gated, so the freshly imported affiliate key would not be authenticated. And `SettingsBootstrap` anchors `donationStatsStartedAtMs` to the clock, which on a factory-reset tablet with no network is whatever the RTC said — an anchor that `SettingsImport.kt:30-35` explains never self-heals.
- **After `svc bluetooth enable`** — or `:511-514` seeds the Bluetooth watchdog into recovery on a device that is fine.

The script also creates the payload directory before pushing: `/sdcard/Android/data/com.sadaqah.kiosk/` does not exist until the app first calls `getExternalFilesDir`, and provisioning pushes to a package that has never run. `adb shell mkdir -p …/files/provisioning` first.

**Do not use `run.py`'s `push_app_file` helper for this.** It exists for `/data/data` and does `chown`/`restorecon` against the parent, which is wrong for the FUSE-backed external path. A plain `adb push` is correct here.

---

## Security

The payload carries a payment credential. What holds, and what does not:

**Holds — the payload location.** `/sdcard/Android/data/com.sadaqah.kiosk/files/` is writable by adb without root (verified against an unrooted shell), readable by the app with no storage permission, and since Android 11 untouchable by any other app. `/data/local/tmp` fails the second test; plain `/sdcard` fails the third.

**Holds — the encrypted-at-rest window.** The payload is AES-GCM encrypted under a PBKDF2 key and is deleted on success.

**Corrected — the password on disk.** An earlier draft asserted the password "is never written to disk". That was checked: after `am start` with a `--es` extra, the app's persisted task file (`/data/system_ce/0/recent_tasks/*_task.xml`) contains no trace of it. Plain extras live in the Intent's Bundle, which is not persisted; only a `PersistableBundle` would be. The password reaches the bench machine's shell history and process list, and nothing else.

**Accepted, and named rather than hidden — the affiliate key at rest.** `importSettings:1976` stores it in plaintext `app_prefs.xml`, unlike the telemetry credentials which go to the Keystore. `AndroidManifest.xml:41` sets `allowBackup="true"` and both `backup_rules.xml` and `data_extraction_rules.xml` are untouched templates with every rule commented out, so nothing is excluded. This predates provisioning, but provisioning turns it from one bench device into every deployed unit. **`app_prefs.xml` should be excluded from backup as part of this work** — it is two lines of XML and the alternative is shipping the payment credential to cloud backup on every kiosk.

---

## Testing

### `ProvisioningLoader` — unit tests per outcome

No payload, no password, wrong password, malformed, valid, valid with code override, valid with name override, valid with both, payload with no secrets block.

**A mutation the implementer must run:** make the loader return `Apply` for a wrong password and confirm a test fails. A provisioning path that silently accepts a bad password configures nothing and reports success.

### The round-trip test

Provision a device from a known payload, export from the provisioned device, and compare. This is the only check that exercises the payload, the guards, the credential layer and the result file together.

**A raw file comparison fails on a correct provisioning**, for structural reasons. It must be a decoded, field-level comparison against a declared exclusion set, asserting **both** directions: excluded fields differ, everything else is identical.

**Compare decrypted secrets, never the envelope.** `salt`, `iv` and `ciphertext` are regenerated per export from `SecureRandom`, so they differ even for identical plaintext.

**Excluded because `merge` guards them:** `installId`, `logoUri`, `donationStatsStartedAtMs`, `analyticsActivatedAtMs`, `skipApkSignatureCheckOnce`, `testMode`.

**Excluded because the provisioning flow changes them:** `kioskCode`, `kioskName`, `kioskCodeFromImport`, and `telemetryUrl` — which round-trips through `TelemetryUrl.check`, lowercasing the scheme and stripping a trailing slash (`TelemetryCredentials.kt:85-86`), so a source URL written `HTTPS://…/` will not match.

**Run only against a freshly reset or reinstalled device.** On a device with pre-existing stored settings the `onCreate` migrations (`MainActivity.kt:431-465`) also touch `longDowntimeThresholdSec`, `autoUpdateEnabled`, `autoUpdateTargetVersion`, `autoUpdateGraceDays`, `updateRepoUrl` and `analyticsEnabled`, and the diff becomes unexplainable. On a clean device only the `longDowntimeThresholdSec` floor applies.

**Everything else must match exactly**, including every colour, `patternAlpha`, `language`, `currency`, both policy URLs, and the decrypted `affiliateKey` and `telemetryKey`.

#### The exclusion list is derived, not written

A hand-maintained list is the thing that drifts: the next device-scoped field added to `merge` will not appear in it, the test keeps passing, and the fleet-wide leak it exists to catch is what gets through.

1. **Derive the guard set by reflection.** Build two `Settings` with every field distinct, run `SettingsImport.merge(a, b)`, and reflect over `Settings::class.memberProperties` to compute which properties took `a`'s value. Assert that set equals the declared exclusions. Adding or removing a guard without updating the test then fails immediately.
2. **Fail on unclassified fields.** Assert every property of `Settings` appears in exactly one of {guarded, provisioning-mutated, migration-mutated, must-match}. A new field then cannot be added without someone deciding which bucket it is in — which is exactly the decision nobody made for `kioskName`.

#### The payload must come from the app's own exporter

Not from a Python reimplementation of PBKDF2 and AES-GCM in `run.py`. A second implementation of `SecretsCrypto` can drift from the first, and a drift makes the check pass against a format the app no longer writes. Export from a configured device, or build the fixture with `SettingsExportFile.build` from a JVM test.

#### And the device check must force-stop first

`run.py:117-118` has `force_stop()`; `launch()` at `:125` documents why a foregrounded process never re-runs `onCreate`.

---

## Out of scope

- **Field re-provisioning.** The whole security argument rests on bench-only. `onNewIntent` is deliberately not implemented for the same reason.
- **Unattended factory reset.** `dpm set-device-owner` needs a device with no accounts and no completed setup wizard; getting it there is manual.
- **Battery protection**, until the settings diff identifies a key — and a configurable percentage window, which the API 35 evidence says does not exist.
- **Removing bloat packages** and **disabling core services.** Measured on real hardware over a day, then landed as a checked-in list with per-package recovery and a completed test payment.
- **Rotating credentials on deployed kiosks.** That is what the export/import path on the settings screen is for.
