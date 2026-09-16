package com.sadaqah.kiosk.model

/**
 * Merges an imported configuration onto this device's current settings.
 *
 * Some fields describe the *device*, not the configuration, and must never be
 * taken from an imported file. Fleet provisioning clones one export onto many
 * kiosks, so a field that identifies a device would identify all of them: every
 * kiosk would report the same `install_id` and the fleet would read as a single
 * machine that restarts a lot.
 */
object SettingsImport {
    fun merge(current: Settings, imported: Settings): Settings = imported.copy(
        // Minted once per device on first run. Never travels in an export.
        installId = current.installId,
        // `kioskCode` deliberately DOES travel — a tablet swapped into an
        // existing kiosk keeps that kiosk's printed code without retyping. The
        // cost is that cloning a fleet from one export stamps every unit with
        // the source code, and `code` + `install_id` then emits the signal it
        // reserves for a re-provisioned unit, fleet-wide and silently.
        //
        // So record where the code came from rather than blocking it. The flag
        // is set here and cleared the moment an operator types the field on this
        // device, which is what makes the warning self-healing: it cannot
        // outlive the condition it describes.
        kioskCodeFromImport = imported.kioskCode.isNotBlank(),
        // A logo is a local file URI that means nothing on another device.
        logoUri = null,
        // Bootstrapped per device and used as the donation-throughput denominator
        // and the "Measuring since" label. An importing kiosk would otherwise
        // permanently adopt the source device's anchor, and it never self-heals
        // because the bootstrap only fires on 0 — so preserve the current
        // device's value always, including when it is itself 0, which lets the
        // bootstrap give this device its own anchor.
        donationStatsStartedAtMs = current.donationStatsStartedAtMs,
        // A record that THIS device's telemetry has reported. Inheriting it
        // would make a kiosk that has never reported look like it had.
        analyticsActivatedAtMs = current.analyticsActivatedAtMs,
        // A one-shot signature-check bypass. Riding an export onto a whole fleet
        // would disable that check across every kiosk that imports it, so force
        // it off on import rather than ever inheriting it.
        skipApkSignatureCheckOnce = false,
        // Test mode forces isLoggedIn and isCardReaderConnected true and bypasses
        // the biometric gate. An export taken from a bench device would carry that
        // onto every kiosk importing it, unlocking the settings screen on machines
        // standing in public. Same reasoning as the signature-check bypass above:
        // a security relaxation is never inherited, only chosen on the device.
        testMode = false
    )
}
