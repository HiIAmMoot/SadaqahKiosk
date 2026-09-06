package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings

/** Why "Test connection" is unavailable right now, so the screen can explain
 *  *before* the press rather than after. Null on [AnalyticsView.testUnavailable]
 *  means the test is available. */
enum class TestUnavailable {
    /** No destination is saved at all — that is the step that comes first, so
     *  it takes precedence over [ANALYTICS_OFF] when both apply. */
    NOT_CONFIGURED,

    /** A destination is saved, but [TelemetryGate] refuses a disabled kiosk
     *  before anything else runs, so pressing the button could not tell the
     *  operator anything about whether the destination works. */
    ANALYTICS_OFF
}

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
    val testUnavailable: TestUnavailable?,
    val baseUrl: String,
    val maskedKey: String,
    val keyLooksUnusual: Boolean,
    val kioskCode: String,
    val kioskCodeLooksUnusual: Boolean,
    val installId: String,
    val queued: Int,
    val neverUploaded: Boolean,
    val lastSuccessMs: Long,
    val lastSuccessAgeMs: Long?,
    val error: String?,
    val backingOff: Boolean,
    val backoffRemainingMs: Long,
    val consecutiveFailures: Int,
    val privacyPolicyUrl: String,
    val termsUrl: String,
    val policyUrlsMissing: Boolean
)

object AnalyticsPresenter {

    /** Enough of the tail to tell two keys apart, never enough to use one. */
    private const val VISIBLE_KEY_CHARS = 4

    /** Every Supabase publishable key in existence shares this prefix. A stored
     *  key that doesn't start with it is quite plausibly the adjacent
     *  `service_role` secret instead — mis-pasted from the same dashboard page,
     *  and one that grants full database read/write. Advisory only, same
     *  posture as [KioskCode.looksConventional]: a fork may point at a different
     *  Supabase deployment with a different key shape, and blocking on this
     *  would make the app that vendor's only. */
    private const val PUBLISHABLE_KEY_PREFIX = "sb_publishable_"

    fun view(
        settings: Settings,
        config: TelemetryConfig?,
        status: TelemetryStatus,
        nowMs: Long
    ): AnalyticsView {
        val neverUploaded = status.lastSuccessMs == 0L
        val testUnavailable = when {
            config == null -> TestUnavailable.NOT_CONFIGURED
            !settings.analyticsEnabled -> TestUnavailable.ANALYTICS_OFF
            else -> null
        }
        return AnalyticsView(
            enabled = settings.analyticsEnabled,
            activated = settings.analyticsActivatedAtMs != 0L,
            configured = config != null,
            // Gated on `enabled`: TelemetryGate refuses a disabled kiosk before
            // anything else, so a live button here could not report anything —
            // and activate() resets lastError/consecutiveFailures/backoffUntilMs
            // and appends an unsendable activation row *before* the gate is even
            // consulted, so a press on a disabled kiosk both erases the
            // diagnostic the operator opened this screen to read and leaves a
            // permanently-queued row behind.
            canTestConnection = testUnavailable == null,
            testUnavailable = testUnavailable,
            baseUrl = config?.baseUrl.orEmpty(),
            maskedKey = mask(config?.publishableKey),
            keyLooksUnusual = config?.publishableKey?.let { !it.startsWith(PUBLISHABLE_KEY_PREFIX) } ?: false,
            kioskCode = settings.kioskCode,
            kioskCodeLooksUnusual = !KioskCode.looksConventional(settings.kioskCode),
            installId = settings.installId,
            queued = status.queued,
            neverUploaded = neverUploaded,
            lastSuccessMs = status.lastSuccessMs,
            lastSuccessAgeMs = if (neverUploaded) null else nowMs - status.lastSuccessMs,
            // An error is shown unless a success has happened since it was
            // recorded. Queue depth is not evidence of that: TelemetryManager.flush
            // can empty the outbox by permanently rejecting rows (deleting the
            // donations) in the same flush that records a retryable failure, so
            // an empty queue beside a fresh error is exactly the case that must
            // still be shown, not the case this used to suppress.
            error = status.lastError?.takeIf { status.lastSuccessMs <= status.lastErrorAtMs },
            backingOff = status.backoffUntilMs > nowMs,
            backoffRemainingMs = (status.backoffUntilMs - nowMs).coerceAtLeast(0L),
            consecutiveFailures = status.consecutiveFailures,
            privacyPolicyUrl = settings.analyticsPrivacyPolicyUrl,
            termsUrl = settings.analyticsTermsUrl,
            policyUrlsMissing = settings.analyticsPrivacyPolicyUrl.isBlank() || settings.analyticsTermsUrl.isBlank()
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
