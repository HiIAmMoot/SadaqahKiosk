package com.sadaqah.kiosk.model

data class Settings(
    val logoUri: String? = null,
    val backgroundColor: Long = 0xFFFFFFFF,
    val patternColor: Long = 0x3f006475,
    val buttonColor: Long = 0xFFFFFFFF,
    val buttonBorderColor: Long = 0xFF000000,
    val patternAlpha: Float = 0.5f,
    val kioskName: String? = null,
    val language: String = "nl", // "nl" for Dutch, "en" for English
    val currency: String = "EUR", // "EUR", "USD", "GBP"
    val useTapToPay: Boolean = false,
    val useArabicThankYou: Boolean = true,
    val screensaverStyle: String = "scrolling",
    val screensaverCustomMessage: String = "",
    val screensaverIdleTimeoutSec: Int = 600,
    val screensaverDurationSec: Int = 60,
    val thankYouDurationSec: Int = 3,
    val testMode: Boolean = false,
    // Resilience / auto-recovery
    val maxConsecutiveFailures: Int = 3,
    val restartCooldownSec: Int = 300,           // 5 minutes
    val maxRestartsBeforeGiveUp: Int = 3,
    val longDowntimeThresholdSec: Int = 300,     // 5 minutes
    val restartCountResetSec: Int = 1800,        // 30 minutes
    // Auto-update
    val autoUpdateEnabled: Boolean = true,
    val autoUpdateTargetVersion: String = "latest", // "latest" or pinned semver e.g. "1.3.5" (min 1.3.0)
    val autoUpdateGraceDays: Int = 14,
    val hideUpdatePrompts: Boolean = false,
    val updateRepoUrl: String = "https://github.com/HiIAmMoot/SadaqahKiosk",
    val skipApkSignatureCheckOnce: Boolean = false,
    // Donation history
    val donationTrackingEnabled: Boolean = true,
    /** Wall-clock instant the averages are computed from. 0 = uninitialised; MainActivity bootstraps it to "now" on first start. */
    val donationStatsStartedAtMs: Long = 0L,
    // Telemetry (see docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md).
    // Credentials are deliberately NOT here — they live in TelemetryCredentials so
    // they never reach the settings JSON except through the encrypted export path.
    val analyticsEnabled: Boolean = false,
    /** Wall-clock instant this kiosk first successfully reported: stamped once,
     *  the first time "Test connection" succeeds. 0 = never. Never overwritten
     *  after that, so it answers "when did this kiosk start reporting", not
     *  "is it currently reporting". */
    val analyticsActivatedAtMs: Long = 0L,
    val analyticsPrivacyPolicyUrl: String = "",
    val analyticsTermsUrl: String = "",
    /** Optional printed panel code. Advisory validation only — see KioskCode. */
    val kioskCode: String = "",
    /** True when [kioskCode] arrived from an imported settings file rather than
     *  being typed on this device.
     *
     *  The code travels on import so a replacement tablet keeps the kiosk's
     *  printed code. The cost is that provisioning a fleet from one export
     *  stamps every unit with the source kiosk's code, and `code` + `install_id`
     *  then permanently emits the signal it reserves for a re-provisioned unit —
     *  silently, because nothing on screen would otherwise say the value is
     *  shared. This flag is what the analytics screen warns from.
     *
     *  Set by [SettingsImport.merge], cleared the moment an operator edits the
     *  field on this device. That is what keeps the warning honest: it cannot
     *  outlive the condition it describes. */
    val kioskCodeFromImport: Boolean = false,
    /** True when [kioskName] arrived from an imported settings file rather than
     *  being typed on this device.
     *
     *  The name travels on import for the same reason the code does — a
     *  replacement tablet keeps the kiosk's existing name — but `makePayment`
     *  sends it to SumUp as the checkout title and attaches it to every
     *  transaction as `KioskNaam`. A fleet provisioned from one export would
     *  otherwise book every donation in the merchant's own records against
     *  the golden kiosk's name, with nothing on screen saying so.
     *
     *  Set by [SettingsImport.merge], cleared the moment an operator edits the
     *  field on this device — the same self-healing rule as
     *  [kioskCodeFromImport]. */
    val kioskNameFromImport: Boolean = false,
    /** Random UUID minted on first run. Never a hardware identifier: ANDROID_ID
     *  and friends carry restrictions and privacy baggage for no benefit here. */
    val installId: String = ""
)
