package com.sadaqah.kiosk.provisioning

import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.model.SettingsImport
import com.sadaqah.kiosk.settingsio.ImportResult
import com.sadaqah.kiosk.settingsio.SettingsExportFile
import com.sadaqah.kiosk.telemetry.KioskCode

sealed class ProvisioningOutcome {
    // Deliberately does not carry the parsed secrets: a data class here would
    // print the affiliate key and the Supabase publishable key through the
    // generated toString the moment anything logs an outcome (the same reason
    // TelemetryConfig isn't a data class). Nothing needs them on this path —
    // Task 4 restores credentials by routing through `importSettings`, which
    // re-parses the payload itself and confirms what actually landed on the
    // device rather than trusting what this decision intended.
    data class Apply(val settings: Settings) : ProvisioningOutcome()

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
        if (!imported.settings.kioskName.isNullOrBlank() && kioskNameOverride.isNullOrBlank()) {
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

        return ProvisioningOutcome.Apply(merged)
    }
}
