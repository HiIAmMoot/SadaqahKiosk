package com.sadaqah.kiosk.model

/**
 * The values a device gives itself on first run, as opposed to values an
 * operator configures.
 *
 * Pure and separate from MainActivity because the inline version ran inside a
 * "did we have stored settings?" branch, so on a genuinely fresh install neither
 * bootstrap fired at all. That was invisible to every test because none could
 * reach the code.
 */
object SettingsBootstrap {
    data class Result(val settings: Settings, val changed: Boolean)

    fun apply(current: Settings, nowMs: Long, newInstallId: () -> String): Result {
        var next = current
        var changed = false

        // Minted once and never regenerated: a second installId on an existing
        // kiosk reads as a new device and silently splits its history in two.
        if (next.installId.isBlank()) {
            next = next.copy(installId = newInstallId())
            changed = true
        }
        if (next.donationStatsStartedAtMs == 0L) {
            next = next.copy(donationStatsStartedAtMs = nowMs)
            changed = true
        }
        return Result(next, changed)
    }
}
