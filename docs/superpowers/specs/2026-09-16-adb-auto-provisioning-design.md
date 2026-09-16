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

**Removal uses `pm uninstall --user 0 <package>`**, which despite the name does not delete anything: it unlinks the package for user 0 and leaves the APK in `/system`. `pm install-existing <package>` restores it, and a factory reset restores everything. That reversibility is why this is the mechanism rather than `pm disable-user`.

**Two traps, both worth stating before anyone starts cutting.**

*Google Play Services is the obvious target and the most dangerous one.* It is typically among the largest background consumers, and the SumUp SDK is likely to depend on it. Removing it could leave a kiosk that boots perfectly, looks configured, and fails at the first real card payment — the worst possible failure shape for this product.

*Recovery costs a bench visit.* USB debugging is off in the field by design, so a kiosk broken by a bad removal cannot be fixed where it stands. Every candidate needs `pm install-existing` recorded beside it, and the list needs a completed test payment before it is trusted.

So Layer 2 produces a **checked-in list with a per-package undo**, built from one device's measurements and verified by taking a real payment afterwards. It is follow-on work, not a step in the provisioning command.

### Not determinable from here

**Battery protection has no AOSP settings key, and a 40–60% window is probably not available at all.**

Nothing matching `batt` or `charg` exists in the emulator's `global`, `secure` or `system` tables, and the four common vendor keys (`protect_battery`, `battery_protection`, `adaptive_charging_enabled`, `charging_limit`) all read `null`. The emulator's `/sys/class/power_supply/battery` carries no `charge_control_limit`, `store_mode` or `slate_mode` node either.

Where OEMs implement this at all, it is almost always **a boolean behind a vendor-chosen ceiling rather than a range you set**. Samsung's is `settings put global protect_battery 1` and caps around 85%. Lenovo's tablet "Battery protection" caps around 60% when the device is kept plugged in — close to the intent, but not configurable. A true adjustable window generally exists only through sysfs nodes that need root, which a production kiosk will not have.

So the realistic outcome of the investigation below is a single on/off key, and the spec should not promise 40–60.

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

Passed as an argument to the provisioning script and handed to the app as an intent extra. It is never written to disk.

The cost is that it appears in the bench machine's shell history and process list. That is accepted: the bench is trusted, and the password protects a file that is itself deleted moments later.

---

## The trigger

```
am start -n com.sadaqah.kiosk/.MainActivity \
  --es provision_password '<password>' \
  --es provision_kiosk_code '<code>'
```

**No new exported component.** `MainActivity` is already the exported launcher; reading extras from the intent that started it adds no surface that was not already there. A broadcast receiver would have added a new one.

An extra alone does nothing: the payload must also be present in a directory only adb or the app can write. Both conditions together mean physical access with debugging enabled.

`provision_kiosk_code` is optional. When present it overrides whatever code the shared payload carried, and is recorded as **locally set** rather than imported — so it does not raise the imported-code warning. That warning exists to flag a code shared across a fleet by accident; a code supplied per kiosk at provisioning time is the opposite of that, and warning about it would train operators to ignore the warning.

---

## What the app gains

### `ProvisioningLoader` — the decision unit

A pure unit that decides what provisioning should do, and does no I/O. `MainActivity` is unreachable from unit tests, so a decision made there is a decision nothing checks — the same reason `DisclosurePresenter` and `AnalyticsPresenter` exist.

```kotlin
sealed class ProvisioningOutcome {
    /** No payload present. The overwhelmingly common case: every normal boot. */
    data object Nothing : ProvisioningOutcome()
    data class Apply(
        val settings: Settings,
        val affiliateKey: String?,
        val telemetryUrl: String?,
        val telemetryKey: String?,
        val logoSource: String?
    ) : ProvisioningOutcome()
    data class Failed(val reason: String) : ProvisioningOutcome()
}

object ProvisioningLoader {
    fun decide(
        current: Settings,
        payloadJson: String?,
        password: String?,
        kioskCodeOverride: String?,
        logoPresent: Boolean
    ): ProvisioningOutcome
}
```

`decide` takes the payload as a **string**, not a path — file reading belongs to the caller, so every branch is testable without a filesystem.

### The wiring in `MainActivity`

**Provisioning is attempted only when the launching intent carries `provision_password`.** Not on every startup that happens to find a payload.

That distinction matters. A payload is deliberately kept when provisioning fails, so the operator can retry with the right password. If the app also scanned for payloads on ordinary boots, a kiosk that failed once would re-fail on every boot afterwards, rewriting the result file each time and never succeeding, because the password only ever arrives with the trigger. Gating on the extra makes a leftover payload inert: it does nothing until someone deliberately triggers again.

The normal-boot cost is therefore zero — not even a `File.exists()`, because the extra is absent.

When the extra is present:

1. Read the payload, call `decide`.
2. On `Apply`: persist the merged settings, copy the logo, restore credentials through `telemetryCredentials.save()` so an imported URL faces the same validation a typed one does, write the result, delete the payload and the pushed image.
3. On `Failed`: write the result with the reason and **keep the payload**, so the operator can retry with a corrected password rather than re-pushing.
4. On `Nothing`: carry on.

**The script clears the directory when it finishes**, whichever way it went — payload, logo and result. That is what actually bounds how long an encrypted credential sits on a device: the app deletes on success, and the script deletes whatever survived a failure once it has read the reason. Neither alone is sufficient, because the app cannot know whether the operator still wants to retry and the script cannot know what the app consumed.

### The logo

**Copied into internal storage, not referenced where it lands.**

`logoUri` is a URI the app resolves at render time. Pointing it at a file in the provisioning directory would mean the logo vanishes the moment that directory is cleaned — and the provisioning flow deletes from that directory by design.

So the pushed image is copied to a **fixed** path, `filesDir/logo/kiosk-logo.<ext>`, and `logoUri` points at the copy. Only then is the pushed file deleted.

The name is fixed rather than carried over from the pushed file so that re-provisioning with a different image replaces the old one instead of accumulating logos in internal storage that nothing will ever reference again. The extension is preserved from the source, since the decoder infers format from content rather than name but a correct extension keeps the directory legible to anyone who pulls it.

This is also sturdier than the existing path. A logo chosen through the settings screen arrives as a `content://` URI from the SAF picker, which depends on a persisted permission grant that can be revoked or lost. A `file://` URI into the app's own storage cannot.

`SettingsImport.merge` continues to null `logoUri` on import. That stays correct — the URI in the payload describes the golden kiosk's filesystem and means nothing here. The provisioning path sets it afterwards, from the image it just copied.

---

## The result file

`/sdcard/Android/data/com.sadaqah.kiosk/files/provisioning/provision-result.json`

```json
{
  "status": "applied",
  "at": "2026-09-16T11:02:31Z",
  "appVersion": "1.4.0-preview",
  "installId": "…",
  "kioskCode": "nl-gld-arnhem-nour_al_houda-07",
  "destinationConfigured": true,
  "affiliateKeyRestored": true,
  "logoApplied": true
}
```

On failure, `"status": "failed"` and `"reason"` naming which step. The script polls for this file and exits non-zero on failure.

**This is the difference between a provisioning tool and a hopeful one.** The import happens inside the app, after the adb command has exited. Without a result the operator's only signal is that nothing visibly broke — and a wrong password produces a kiosk that boots, looks perfect, and reports nowhere.

**No secret goes in the result.** `installId` and the kiosk code identify the unit; the destination and key are reported as booleans, never values.

---

## Failure modes, and what each does

| Failure | Behaviour |
|---|---|
| No trigger extra | Normal boot. Provisioning is not attempted at all. |
| Trigger extra, no payload | `failed`, reason `no_payload`. The operator asked for provisioning and nothing was there to apply — silence would read as success. |
| Wrong password | `failed`, reason `wrong_password`, payload kept. |
| Malformed payload | `failed`, reason `malformed`, payload kept. |
| Payload present, no password given | `failed`, reason `password_required`. |
| Logo named but missing | Settings still applied; `logoApplied: false`. A missing image is not a reason to discard a correct configuration. |
| Imported telemetry URL rejected | Settings applied, `destinationConfigured: false`, reason recorded. The previous destination is left alone rather than half-replaced. |
| Result file cannot be written | Logged. The import is not rolled back — it succeeded, and undoing it would be worse than an unreported success. |

---

## Testing

**`ProvisioningLoader` gets a unit test per outcome**: no payload, no password, wrong password, malformed, valid, valid with code override, valid with logo, logo named but absent. Every branch is reachable because `decide` takes strings.

**One device check in `tools/kiosk-check/run.py`**, covering what no JVM test can: push a payload and a logo to the emulator, trigger, and assert the result file says applied, the destination is configured, the kiosk code is the overridden one, the logo file exists in internal storage, and **the payload is gone**.

**A mutation the implementer must run**: make the loader return `Apply` for a wrong password and confirm a test fails. A provisioning path that silently accepts a bad password would configure nothing and report success.

---

## Out of scope

- **Field re-provisioning.** The whole security argument rests on bench-only. Extending to the field is a different spec.
- **Unattended factory reset.** `dpm set-device-owner` needs a device with no accounts; getting it there is a manual step.
- **Battery protection**, until the settings diff above identifies a key — and a configurable 40–60% window, which the evidence says is unlikely to exist on this hardware at all.
- **Removing bloat packages.** Layer 2 above: measured on a real device over a day, then landed as a checked-in list with per-package recovery and a completed test payment. Not part of this command.
- **Disabling core system services.** Deliberately deferred to a bench session on real hardware, for the same reason: a wrong call here is only recoverable with physical access, and this spec's whole model is that the field has none.
- **Provisioning more than one device at once.** The script takes one serial; running it in parallel is the operator's business.
- **Rotating credentials on deployed kiosks.** That is what the export/import path on the settings screen is for.
