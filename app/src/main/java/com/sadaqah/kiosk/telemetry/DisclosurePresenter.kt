package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings

/**
 * Everything the disclosure screen renders, decided here rather than in the
 * composable — the screen cannot be unit-tested, so judgement left inside it is
 * judgement nothing checks. [AnalyticsPresenter] carries the same note and the
 * same reason.
 */
data class DisclosureView(
    val destinationUrl: String,
    val privacyUrl: String,
    /** Null when no terms URL is set. The terms block is optional; the privacy
     *  policy is not — without it there is no screen at all. */
    val termsUrl: String?
)

object DisclosurePresenter {

    /**
     * Null means **do not show the screen**.
     *
     * Two things make it null, and both mean the screen would have nothing
     * honest to say: no destination, so nothing is configured; or no published
     * privacy policy, so there is no document to point at. Rendering the rest
     * around a missing policy section would read as complete when it is not.
     *
     * Nothing here is remembered, and that is deliberate — the caller triggers
     * on a destination *save*, which is rare, so a kiosk that later gains a
     * policy URL shows the screen on the next save without needing a stored
     * flag to be cleared.
     */
    fun view(settings: Settings, destinationUrl: String): DisclosureView? {
        if (destinationUrl.isBlank()) return null
        if (settings.analyticsPrivacyPolicyUrl.isBlank()) return null
        return DisclosureView(
            destinationUrl = destinationUrl,
            privacyUrl = settings.analyticsPrivacyPolicyUrl,
            termsUrl = settings.analyticsTermsUrl.takeIf { it.isNotBlank() }
        )
    }
}
