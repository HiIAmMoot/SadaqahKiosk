# Sadaqah Kiosk

An open-source Android donation kiosk app powered by the [SumUp](https://sumup.com) card payment SDK. Designed for mosques, Islamic charities, and community organisations to accept card donations through a self-service touchscreen terminal.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE.md)

---

## Screenshots

<p align="center">
  <img src="docs/screenshots/screensaver.jpeg" width="150">
  <img src="docs/screenshots/login.jpeg" width="150">
  <img src="docs/screenshots/donation_grid.jpeg" width="150">
  <img src="docs/screenshots/custom_amount.jpeg" width="150">
  <img src="docs/screenshots/settings.jpeg" width="150">
</p>

---

## Features

- **Card payments** via SumUp card reader (chip & PIN, contactless)
  - Achieved through the SumUp Android SDK: https://github.com/sumup/sumup-android-sdk
- **Preset donation amounts** on a responsive grid
- **Custom amount** entry via on-screen numpad
- **8 languages**: English, Dutch, German, French, Spanish, Italian, Turkish, Arabic
  - Auto-detected from device locale on first launch
- **Fully themeable**: background, pattern overlay, button color, and text/border color
- **Logo upload**: display your organisation's logo on the donation screen
- **Islamic thank-you screen**: toggle between an Arabic blessing (بارك الله فيكم) and a localised "thank you"
- **Biometric / PIN gate** on the settings screen
- **Export / import settings** as JSON (including affiliate key, with permission)
- **Offline awareness**: warns when internet is unavailable; auto-dismisses SumUp login screen on disconnect and auto-reinitialises after prolonged outage
- **Auto-recovery**: automatic app restart on repeated card reader or reinit failures, with cooldown and max-restart guard to prevent loops
- **Auto-update**: device polls a GitHub releases feed, silently installs updates during nightly maintenance, and rolls back via a 60-second watchdog if the new build crashes on startup. Requires device-owner provisioning. See [Auto-Update](#auto-update) below.
- **Auto-start on boot**: launches automatically when the device powers on
- **Device owner / kiosk mode**: optional silent lock-task mode (no blue notification bar) when set as device owner
- **Screensaver** after configurable idle timeout
- **Auto-reinitialise** at 02:00 daily to keep the SumUp session fresh
- Responsive layout — tuned for Lenovo M9 tablet (800 dp portrait), scales to any Android device

---

## Requirements

| Requirement         | Details                                         |
|---------------------|-------------------------------------------------|
| Android             | 11.0+ (API 30)                                  |
| SumUp account       | [sumup.com](https://sumup.com) free to register |
| SumUp Affiliate Key | Generated in the SumUp developer dashboard      |
| SumUp card reader   | Air, Air Lite, Solo, or any supported reader    |
| Internet connection | Required for SumUp authentication and payments  |

---

## Building from Source

### Prerequisites

- Android Studio Hedgehog or newer
- JDK 11+
- Android SDK with API 35

### Steps

```bash
git clone https://github.com/HiIAmMoot/SadaqahKiosk.git
cd SadaqahKiosk
```

Open the project in Android Studio, or build from the command line:

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires a signing keystore — see Signing below)
./gradlew assembleRelease
```

The debug APK is output to `app/build/outputs/apk/debug/`.

### Signing a Release Build

1. Generate a keystore:
   ```bash
   keytool -genkey -v -keystore kiosk.jks -keyalg RSA -keysize 2048 -validity 10000 -alias kiosk
   ```
2. Create `keystore.properties` in the project root (this file is gitignored):
   ```properties
   storeFile=../kiosk.jks
   storePassword=your_store_password
   keyAlias=kiosk
   keyPassword=your_key_password
   ```
3. Reference it in `app/build.gradle.kts` under `signingConfigs`.

---

## First-Time Setup

1. Install the APK on your Android tablet or phone.
2. Launch the app, it auto-detects your device language. 
3. Tap the **gear icon**, biometrics (fingerprint or PIN) will trigger automatically.
4. In Settings, customize the kiosk name, logo, colors, currency, and language. Tap Save & Back.
5. Log in with your SumUp Affiliate Key, you will then be prompted to log in using your SumUp account's credentials.
6. You will be prompted to connect a device.
7. After successful connection, your donors can now tap to give.

### Device Owner Setup (Optional)

Setting the app as device owner enables silent lock-task mode (no blue "app is pinned" notification) and allows programmatic Bluetooth control. The app works fine without it — this is optional for a more polished kiosk experience.

> **Important:** Device owner can only be set on a device with no accounts added, or via a fresh factory reset. You cannot set device owner on a device that already has a Google account signed in.

1. Enable **Developer Options** and **USB Debugging** on the tablet.
2. Connect the tablet to a computer with ADB installed.
3. If accounts are present, remove them or factory reset:
   ```bash
   adb shell pm list users
   ```
4. Set device owner:
   ```bash
   adb shell dpm set-device-owner com.sadaqah.kiosk/.KioskDeviceAdminReceiver
   ```
5. Verify it worked:
   ```bash
   adb shell dpm list-owners
   ```
   You should see `com.sadaqah.kiosk/.KioskDeviceAdminReceiver`.

To remove device owner later:
```bash
adb shell dpm remove-active-admin com.sadaqah.kiosk/.KioskDeviceAdminReceiver
```

### Settings Reference

| Setting                                     | Description                                                  |
|---------------------------------------------|--------------------------------------------------------------|
| Kiosk Name                                  | Appears on SumUp transaction receipts with a "SK - " prefix  |
| Logo                                        | PNG/JPEG shown on the donation screen, transparency supported |
| Language                                    | UI language; 8 options; auto-detected on first launch        |
| Currency                                    | EUR, USD, or GBP                                             |
| Background / Pattern / Button / Text colors | Full RGBA color picker with history and suggested colors     |
| Connect Card Reader                         | Pairs the SumUp reader (must be logged in first)             |
| Islamic Blessing when donating              | Toggle between Arabic بارك الله فيكم and localised "thank you" |
| Export / Import Settings                    | Back up or copy settings between devices as JSON             |
| Reset App                                   | Clears all stored data and restarts (double-tap to confirm)  |

---

## Auto-Update

The app can update itself directly from a GitHub releases feed — no Play Store, no MDM, no operator intervention. Designed for unattended kiosks. **Requires device-owner provisioning** (see above); a non-DO install will detect updates but never install them, since silent install isn't available without that privilege.

### How it works

End-to-end:

1. **Check.** The app polls the configured GitHub repository's `/releases` API ~30 s after startup, again at 02:00 every day, and on demand when the operator opens Settings. Unauthenticated, 60 requests/hour (plenty for daily polling).
2. **Filter.** Releases must be non-draft, non-prerelease, tagged as semver (`v1.3.0`, `1.3.5`, …), and have an `.apk` asset attached. Versions below `1.3.0` (the first release with this update system) are excluded. So are versions on a different `major.minor` track than what's installed — see [The versionCode convention](#the-versioncode-convention) below.
3. **Notify (optional).** A red dot appears next to the gear icon on the donation screen, plus a row in Settings → Updates showing `Latest: vX.Y.Z`. Hideable via the **Hide update notifications** toggle.
4. **Download (background).** During the 02:00 maintenance window, when auto-update is enabled, the target APK is fetched silently to the app's private cache. If a newer release appears mid-grace, the cached APK is discarded and the new one is fetched.
5. **Validate.** Before installing, the new APK is checked:
   - Same `packageName` as the installed app (`com.sadaqah.kiosk`)
   - Not a versionCode downgrade
   - Signing certificate fingerprint matches the installed app's (SHA-256). Mismatch rejects the install — this is the security boundary that stops a malicious mirror or wrong-keystore build from being silently installed.
6. **Install.** A backup of the currently-installed APK is copied to internal storage, then a 60-second watchdog `AlarmManager` is armed. The app unpins itself, hides any pairing UI, and commits the install via `PackageInstaller` with `USER_ACTION_NOT_REQUIRED` (silent — device-owner only).
7. **Relaunch.** The system replaces the APK and broadcasts `MY_PACKAGE_REPLACED`; a registered receiver immediately launches `MainActivity`, which writes a "fresh startup" heartbeat to disk.
8. **Watchdog.** When the AlarmManager fires (60 s after install), the watchdog reads the heartbeat. If the new build wrote one, install was successful. If it didn't — the new build crashed on startup — the watchdog reinstalls the backup APK to recover the kiosk.

### Update timing

| Setting                      | Behaviour                                                                                                                                                  |
|------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Auto-update **on**, target **Latest** | Update detected → notification shown → after the grace period elapses, install at the next 02:00 window. Settings shows the exact scheduled install date. |
| Auto-update **on**, target **pinned** (e.g. `1.3.5`) | Install at the next 02:00 window (no grace — pinning is opt-in consent).                                                                                  |
| Auto-update **off**          | Notifications still appear; install only happens when the operator taps **Install now** in Settings.                                                       |
| **Install now** (manual)     | Operator taps the badge or the Settings button → biometric prompt → confirmation screen with the changelog → install begins immediately.                   |

Preflight gates that block any install path:
- Not device-owner
- No internet
- Battery below 30%

### Configuring the source repository

By default the app polls this repository (`https://github.com/HiIAmMoot/SadaqahKiosk`). If you fork and self-host releases:

1. Open **Settings → Updates → Update source (GitHub repo)**
2. Enter the full URL, e.g. `https://github.com/your-org/your-fork`
3. Tap **Apply**. The next check uses the new source.

If you're migrating from this repository to a fork signed with a different keystore, flip **Skip signature check on next install** before applying the update. The flag is single-use and clears itself after the next install attempt.

### The versionCode convention

> **Read this if you cut releases.**

Android's `PackageInstaller` refuses silent downgrades for non-platform-signed callers. That includes the watchdog rollback path. To keep the kiosk recoverable, the project uses this rule:

**All releases sharing the same `major.minor` share the same `versionCode`. Only bump `versionCode` when you cut a new minor or major.**

| Releases on the same track | versionCode | Notes                                                                                                |
|----------------------------|-------------|------------------------------------------------------------------------------------------------------|
| `1.3.0`, `1.3.1`, … `1.3.N`| 15          | Any of these can be installed over any other (Android treats them as reinstalls, not downgrades).    |
| `1.4.0`, `1.4.1`, …        | 16          | Same idea for the next minor track.                                                                  |
| `2.0.0`, `2.0.1`, …        | 17          | And so on.                                                                                            |

Why it matters:
- **Watchdog rollback works** when a crashing update has the same versionCode as the backup APK. If a patch in the same minor track is broken, the watchdog can recover the kiosk autonomously.
- **Cross-track upgrades still work** (`1.3.5 → 1.4.0`) because `1.4.0` has a higher versionCode — that's a real upgrade and Android accepts it.
- **Cross-track downgrades don't work** by design. Once you ship a `1.4.0` to a device, you can't auto-roll-back to `1.3.X`. Plan minor cuts accordingly.

When this rule is followed, the target-version dropdown in Settings shows all installable patches: every release in the same `major.minor` track as the current install, plus any higher-track releases as forward upgrades. Releases on lower tracks are hidden.

### Caveats

- **No silent cross-track downgrades.** As above — `1.4.X` can't auto-revert to `1.3.X`. Recovery requires a USB flash. Pick minor versions deliberately.
- **The 02:00 maintenance window** is the only time auto-update installs unattended. If the device is off or offline at 02:00, the install is deferred to the following day.
- **Signature pinning is the security boundary.** The skip-signature toggle exists only for forking the project to a new keystore. Anything more subtle (e.g. a malicious mirror keeping the same package name but a different signing cert) is rejected by default.
- **Battery/network preflight is strict.** A device below 30% battery will keep deferring nightly until charged.
- **GitHub rate limit.** 60 unauthenticated requests/hour per IP. A single device running normal cadence is nowhere near that, but mass repeated manual checks across a fleet can run into it.

### Disabling auto-update

If you want to disable updates entirely:

1. Open **Settings → Updates**
2. Turn off **Auto-update enabled**
3. (Optional) Turn on **Hide update notifications** so the gear-icon dot doesn't surface

The app will continue to check for updates so the Settings page can show what's available, but it will never install one without an explicit operator tap.

---

## Architecture

```
app/src/main/java/com/sadaqah/kiosk/
├── MainActivity.kt              # Activity, SumUp API integration, state management
├── Translations.kt              # Language enum, TranslationManager, all 8 Strings objects
├── ColorHistory.kt              # Recently picked and suggested colors singleton
├── Utils.kt                     # responsiveDp / responsiveSp helpers, grid column logic
├── BootReceiver.kt              # Launches app on BOOT_COMPLETED
├── KioskDeviceAdminReceiver.kt  # Device admin receiver for silent lock-task mode
├── model/
│   └── Settings.kt              # Data class for all persisted settings
├── recovery/
│   ├── KeyValueStore.kt         # SharedPreferences abstraction (testable)
│   ├── RestartManager.kt        # Auto-restart decision logic with guards
│   └── NetworkRecoveryManager.kt # Network outage detection and recovery
├── update/
│   ├── UpdateManager.kt              # Orchestrator: check, download, validate, install, schedule
│   ├── UpdateTypes.kt                # SemVer, ReleaseInfo, UpdateState
│   ├── GitHubReleasesClient.kt       # Unauthenticated GitHub releases REST client
│   ├── ApkDownloader.kt              # APK download to app-private cache, cleanup
│   ├── ApkValidator.kt               # packageName + versionCode + signing-cert checks
│   ├── ApkInstaller.kt               # Silent install via PackageInstaller (device-owner)
│   ├── BackupStore.kt                # Saves the current APK before each install
│   ├── UpdateWatchdogReceiver.kt     # 60 s alarm + rollback if no heartbeat
│   └── PackageReplacedReceiver.kt    # Relaunches the app after self-update
├── screens/
│   ├── DonationScreen.kt        # Main donation grid
│   ├── CustomAmountScreen.kt
│   ├── SettingsScreen.kt
│   ├── SetupStatusScreen.kt     # Network/Bluetooth/reader status checklist
│   ├── ColorPickerScreen.kt
│   ├── LoginScreen.kt
│   ├── ScreensaverScreen.kt
│   ├── ThankYouScreen.kt
│   └── MaintenanceScreen.kt
└── components/
    ├── NumpadButton.kt
    └── ColorComponents.kt
```

Settings are persisted to `SharedPreferences` as JSON (Gson). The SumUp SDK handles all payment processing; this app never touches card data.

---

## Contributing

Contributions are welcome! Please read the guidelines below before opening a PR.

### Reporting Bugs

Use the [Bug Report](.github/ISSUE_TEMPLATE/bug_report.md) template. Include:
- Device model and Android version
- Steps to reproduce
- What you expected vs. what happened
- Logs if available (`adb logcat -s SumUpPayment SumUpLogin NetworkStatus`)

For bugs specifically related to the SumUp SDK, please refer to their github issues page: https://github.com/sumup/sumup-android-sdk/issues

### Suggesting Features

Use the [Feature Request](.github/ISSUE_TEMPLATE/feature_request.md) template.

### Submitting Code

1. Fork the repository and create a branch from `master`:
   ```bash
   git checkout -b feature/my-feature
   ```
2. Make your changes. Keep PRs focused — one feature or fix per PR.
3. Follow the existing code style (Kotlin, Jetpack Compose, Material3).
4. Test on at least one real device (emulator alone is insufficient for SumUp SDK testing).
5. Open a Pull Request using the [PR template](.github/pull_request_template.md).

### Adding a Language

1. Add a new entry to the `Language` enum in `Translations.kt` with the BCP 47 language code, display name, flag emoji, and short code.
2. Implement the `Strings` interface for the new language (copy `EnglishStrings` and translate all fields).
3. Add the new case to `TranslationManager.currentStrings()` and `rememberStrings()`.

### Code Style

- Self-documenting names over comments
- Comments only for non-obvious *why*, never for *what*
- No commented-out code
- Keep Composables small and single-purpose
- No hardcoded colors in screens — always use `settings.buttonColor` / `settings.buttonBorderColor` etc.

---

## Support the Project

If this app has been useful to your masjid or organisation, consider supporting its development.

| Method         | Address / Link                                                 |
|----------------|----------------------------------------------------------------|
| Ko-fi          | [ko-fi.com/iammoot](https://ko-fi.com/iammoot)                 |
| thanks.dev     | [thanks.dev/d/gh/hiiammoot](https://thanks.dev/d/gh/hiiammoot) |
| Bitcoin (BTC)  | `bc1qprtakahp8xtt6tltjacx88wvnp4hgcxlk7kmhe`                   |
| Ethereum (ETH) | `0x6B652e4955b82Fb40eF9f503D30dBBf28c09573a`                   |
| Solana (SOL)   | `CDiKy33RzxpFFXxzxKN89MpfAmCYsZhSN2a2UV5bErqz`                 |

---

## Privacy

This app does **not** collect, store, or transmit any personal data beyond what the SumUp SDK requires for payment processing. No analytics, no crash reporting, no tracking. See [SumUp's privacy policy](https://sumup.com/privacy/) for details on payment data handling.

---

## License

[GNU Affero General Public License v3.0](LICENSE.md) — see the file for full terms.

In short: you may use, modify, and distribute this software freely, but any derivative work must also be released under AGPL v3 with source code available — including if you run a modified version as a hosted or network service.
