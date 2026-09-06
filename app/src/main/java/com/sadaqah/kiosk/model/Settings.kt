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
)
