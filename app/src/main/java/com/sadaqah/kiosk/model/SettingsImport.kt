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
        // A logo is a local file URI that means nothing on another device.
        logoUri = null
    )
}
