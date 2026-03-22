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
    val screensaverStyle: String = "hue_drift",
    val screensaverCustomMessage: String = "",
    val screensaverCycleMessages: Boolean = true,
    val screensaverIdleTimeoutSec: Int = 300,
    val screensaverDurationSec: Int = 600,
    val screensaverCustomMessageHoldSec: Int = 120,
    val screensaverMessageHoldSec: Int = 6,
    val thankYouDurationSec: Int = 3,
    val testMode: Boolean = false
)
