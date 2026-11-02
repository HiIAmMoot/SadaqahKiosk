package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    /** Meaningless when [neverUploaded] is true — formats epoch 0 in that case.
     *  The screen picks [neverUploaded]'s own string instead of rendering this. */
    val lastSuccessText: String,
    val error: String?,
    val backingOff: Boolean,
    /** Seconds, not millis: the screen shows seconds, and converting in the
     *  composable is arithmetic no test can reach. Rounded up, so a wait of
     *  900ms reads as "1" rather than "0" — a countdown that displays zero
     *  while still waiting reads as a stuck kiosk. */
    val backoffRemainingSeconds: Long,
    val consecutiveFailures: Int,
    val dropped: Int,
    val privacyPolicyUrl: String,
    val termsUrl: String,
    val policyUrlsMissing: Boolean,
    /** The kiosk code arrived from an imported file rather than being typed
     *  here, so it may be shared with every other kiosk provisioned from that
     *  same export. Only ever true while a code is actually set — a blank code
     *  cannot be shared, and warning about one would be noise. */
    val kioskCodeFromImport: Boolean
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

    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

    fun view(
        settings: Settings,
        config: TelemetryConfig?,
        status: TelemetryStatus,
        nowMs: Long,
        zone: ZoneId,
        locale: Locale
    ): AnalyticsView {
        val neverUploaded = status.lastSuccessMs == 0L
        val effectiveBackoffUntilMs = status.effectiveBackoffUntilMs(nowMs)
        val isBackedOff = effectiveBackoffUntilMs > nowMs
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
            // and activate() resets lastError and every table's failure state
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
            // Formatted here rather than in the composable: the screen cannot be
            // unit-tested, so a computation left inside it is a computation
            // nothing checks. The neverUploaded branch stays a screen concern —
            // rendering it needs a Strings member the presenter must not reach
            // for.
            lastSuccessText = TIMESTAMP_FORMAT.withLocale(locale).withZone(zone)
                .format(Instant.ofEpochMilli(status.lastSuccessMs)),
            // Shown while any table is backed off, whatever the timestamps — or
            // the table — say: a sibling's success now advances lastSuccessMs
            // past a retained lastErrorAtMs, and suppressing on that comparison
            // alone would leave the operator reading a failure count with no
            // failure beside it. The comparison still decides the case where
            // nothing is backed off — an error a later success genuinely
            // superseded. Because lastError is one global field, the text shown
            // while backed off can name a table that has since recovered (e.g.
            // diagnostics fails, donations fails later and overwrites lastError,
            // donations recovers, diagnostics is still stuck) — backingOff,
            // backoffRemainingSeconds and consecutiveFailures stay correct
            // alongside it, so the operator is never told everything is fine,
            // just possibly about the wrong table.
            //
            // Queue depth is not evidence of a success either: flush can empty
            // the outbox by permanently rejecting rows in the same flush that
            // records a retryable failure.
            error = status.lastError?.takeIf {
                isBackedOff || status.lastSuccessMs <= status.lastErrorAtMs
            },
            backingOff = isBackedOff,
            backoffRemainingSeconds =
                (effectiveBackoffUntilMs - nowMs).coerceAtLeast(0L).let { (it + 999) / 1000 },
            consecutiveFailures = status.consecutiveFailuresByTable.values.maxOrNull() ?: 0,
            dropped = status.droppedCount,
            privacyPolicyUrl = settings.analyticsPrivacyPolicyUrl,
            termsUrl = settings.analyticsTermsUrl,
            policyUrlsMissing = settings.analyticsPrivacyPolicyUrl.isBlank() || settings.analyticsTermsUrl.isBlank(),
            kioskCodeFromImport = settings.kioskCodeFromImport && settings.kioskCode.isNotBlank()
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
