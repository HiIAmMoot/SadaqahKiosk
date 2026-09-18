package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Why a completed donation did or did not become a reportable event.
 *
 * A sealed result rather than a nullable event, because the two non-reporting
 * cases demand different behaviour from the caller: [NotEnabled] is the normal
 * state of most kiosks and means *do nothing*, while [AmountUnrepresentable] is
 * a donation that will never be reported and must be logged and counted. A
 * shared `null` would make a disabled kiosk record a data loss on every
 * donation it takes.
 */
sealed class DonationEventResult {
    data class Report(val event: TelemetryEvent.Donation) : DonationEventResult()
    object NotEnabled : DonationEventResult()
    object AmountUnrepresentable : DonationEventResult()
}

/**
 * Decides whether a completed donation is reported, and in what shape.
 *
 * Pure, and that is the point: its caller is on the donation path inside
 * `MainActivity`, which no unit test can reach, so every decision — including
 * whether to report at all — lives here where it can be exercised. Phase 2b
 * shipped a bug for exactly the opposite reason: a mint that sat inside an
 * unreachable branch of `MainActivity`.
 *
 * [appVersion] is a parameter rather than a `BuildConfig` read so this file has
 * no Android dependency.
 */
object DonationEvents {

    fun eventFor(
        settings: Settings,
        appVersion: String,
        amount: BigDecimal,
        occurredAtMs: Long
    ): DonationEventResult {
        // The master switch, checked here rather than at the call site. A kiosk
        // with analytics off writes nothing to disk at all — not "writes and
        // declines to send" — so no identified donation row is ever stored for a
        // purpose the operator declined.
        if (!settings.analyticsEnabled) return DonationEventResult.NotEnabled

        val cents = try {
            // HALF_UP because currency is conventionally rounded that way. The
            // numpad produces at most two decimals, so rounding is very nearly
            // unreachable — which is why it is pinned by a test rather than
            // left to whatever the default happens to be.
            amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact()
        } catch (_: ArithmeticException) {
            // Beyond Int cents (about 21.5 million). Unreachable through the
            // UI; defined so this function has no undefined behaviour.
            return DonationEventResult.AmountUnrepresentable
        }

        return DonationEventResult.Report(
            TelemetryEvent.Donation(
                identity = EventIdentity.from(settings, appVersion),
                amountCents = cents,
                currency = settings.currency,
                occurredAtIso = Instant.ofEpochMilli(occurredAtMs).toString()
            )
        )
    }
}
