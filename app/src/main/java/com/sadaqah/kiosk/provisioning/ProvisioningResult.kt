package com.sadaqah.kiosk.provisioning

import com.google.gson.Gson

/**
 * What the bench operator reads to find out whether provisioning worked.
 *
 * The import happens inside the app, after the adb command has already exited,
 * so without this the only signal is that nothing visibly broke — and a wrong
 * password produces a kiosk that boots, looks perfect, and reports nowhere.
 *
 * No secret appears here. [installId], [kioskCode] and [kioskName] identify the
 * unit; the credentials are reported as booleans and never as values.
 */
data class ProvisioningResult(
    val runId: String,
    val status: String,
    val at: String,
    val appVersion: String,
    val reason: String? = null,
    val installId: String = "",
    val kioskCode: String = "",
    val kioskName: String = "",
    val destinationConfigured: Boolean = false,
    val affiliateKeyRestored: Boolean = false,
    val logoApplied: Boolean = false,
    /** Separate from [logoApplied] because copying bytes proves nothing about
     *  them: LogoColorExtractor.decode returns null and merely logs on a
     *  corrupt image, so a kiosk can report a logo applied and render none. */
    val logoDecodable: Boolean = false
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        const val APPLIED = "applied"
        const val FAILED = "failed"
    }
}
