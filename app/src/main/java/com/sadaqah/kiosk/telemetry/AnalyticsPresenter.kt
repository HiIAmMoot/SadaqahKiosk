package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings

/**
 * Everything the analytics screen renders, decided here rather than in the
 * composable.
 *
 * The screen cannot be unit-tested — instrumented tests need a device — so any
 * judgement left inside it is judgement nothing checks. Phase 2b shipped a bug
 * for exactly that reason: a mint that sat inside an unreachable branch of
 * `MainActivity`. So the composable renders this and decides nothing.
 */
data class AnalyticsView(
    val enabled: Boolean,
    val activated: Boolean,
    val configured: Boolean,
    val canTestConnection: Boolean,
    val baseUrl: String,
    val maskedKey: String,
    val kioskCode: String,
    val kioskCodeLooksUnusual: Boolean,
    val queued: Int,
    val neverUploaded: Boolean,
    val lastSuccessMs: Long,
    val error: String?,
    val backingOff: Boolean
)

object AnalyticsPresenter {

    /** Enough of the tail to tell two keys apart, never enough to use one. */
    private const val VISIBLE_KEY_CHARS = 4

    fun view(
        settings: Settings,
        config: TelemetryConfig?,
        status: TelemetryStatus,
        nowMs: Long
    ): AnalyticsView {
        val queued = status.queued
        return AnalyticsView(
            enabled = settings.analyticsEnabled,
            activated = settings.analyticsActivatedAtMs != 0L,
            configured = config != null,
            // Deliberately not gated on `enabled`: an operator who has switched
            // telemetry off must still be able to correct a mistyped destination
            // without switching it back on first.
            canTestConnection = config != null,
            baseUrl = config?.baseUrl.orEmpty(),
            maskedKey = mask(config?.publishableKey),
            kioskCode = settings.kioskCode,
            kioskCodeLooksUnusual = !KioskCode.looksConventional(settings.kioskCode),
            queued = queued,
            neverUploaded = status.lastSuccessMs == 0L,
            lastSuccessMs = status.lastSuccessMs,
            // An error that no longer describes anything waiting is history, and
            // showing it beside an empty queue reads as "it all failed and the
            // data is gone" — which is what real data loss would also look like.
            error = status.lastError?.takeIf { queued > 0 },
            backingOff = status.backoffUntilMs > nowMs
        )
    }

    private fun mask(key: String?): String {
        if (key.isNullOrEmpty()) return ""
        // A short key is masked completely: revealing four of six characters
        // would identify the key rather than merely distinguish it.
        if (key.length <= VISIBLE_KEY_CHARS * 2) return "•".repeat(key.length)
        return "•".repeat(key.length - VISIBLE_KEY_CHARS) + key.takeLast(VISIBLE_KEY_CHARS)
    }
}
