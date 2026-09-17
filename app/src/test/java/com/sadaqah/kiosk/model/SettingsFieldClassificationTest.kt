package com.sadaqah.kiosk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every field of [Settings] must be deliberately classified as either
 * device-scoped (guarded by [SettingsImport.merge]) or configuration (travels).
 *
 * This exists because `kioskName` was neither: it silently travelled, and it is
 * attached to every SumUp transaction, so a fleet cloned from one export
 * attributed every payment in the merchant's records to the golden kiosk. The
 * guard set was not wrong so much as never decided.
 *
 * The guarded set is DERIVED by reflection rather than restated, so adding a
 * guard without updating this test fails immediately, and so does removing one.
 */
class SettingsFieldClassificationTest {

    /** Fields `merge` refuses to take from an imported file, keeping this
     *  device's value instead (or forcing a constant). Six. */
    private val expectedGuarded = setOf(
        "installId",
        "logoUri",
        "donationStatsStartedAtMs",
        "analyticsActivatedAtMs",
        "skipApkSignatureCheckOnce",
        "testMode"
    )

    /** Neither kept nor taken: `merge` DERIVES this one from the imported
     *  code (`kioskCodeFromImport = imported.kioskCode.isNotBlank()`). It needs
     *  its own bucket — the guarded-set derivation below looks for fields where
     *  merge refused the imported value outright, and a computed field's result
     *  can coincide with the imported value, so listing it as guarded would be
     *  unreliable. */
    private val expectedComputed = setOf("kioskCodeFromImport")

    /** Fields that travel, each a deliberate decision that sharing it across a
     *  fleet is correct. Thirty. */
    private val expectedTravelling = setOf(
        "kioskName", "language", "currency", "kioskCode",
        "backgroundColor", "patternColor", "patternAlpha", "buttonColor", "buttonBorderColor",
        "useArabicThankYou", "useTapToPay", "thankYouDurationSec",
        "screensaverStyle", "screensaverDurationSec", "screensaverIdleTimeoutSec",
        "screensaverCustomMessage",
        "maxConsecutiveFailures", "maxRestartsBeforeGiveUp", "restartCooldownSec",
        "restartCountResetSec", "longDowntimeThresholdSec",
        "autoUpdateEnabled", "autoUpdateTargetVersion", "autoUpdateGraceDays",
        "hideUpdatePrompts", "updateRepoUrl",
        "donationTrackingEnabled",
        "analyticsEnabled", "analyticsPrivacyPolicyUrl", "analyticsTermsUrl"
    )

    private fun fieldNames(): Set<String> =
        Settings::class.java.declaredFields
            // `$` never appears in a Kotlin identifier, so this only ever drops
            // compiler-generated fields — notably the Compose compiler's
            // `$stable` stability marker, added because Settings is a data
            // class passed to composables. It isn't flagged isSynthetic, so
            // that filter alone lets it through.
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .map { it.name }
            .toSet()

    private fun valueOf(settings: Settings, name: String): Any? =
        Settings::class.java.getDeclaredField(name).apply { isAccessible = true }.get(settings)

    /** Two instances differing on every classified field — enforced, not just
     *  intended, by [fixturesDisagreeOnEveryClassifiedField] below: a field
     *  left at its constructor default in both fixtures agrees silently, and
     *  every derivation in this file gates on `mine != theirs`, so an
     *  agreeing field is invisible to the tests that are supposed to prove
     *  its bucket. `theirs` also disagrees with every constant `merge`
     *  forces (a non-null `logoUri`, `testMode = true`,
     *  `skipApkSignatureCheckOnce = true`) so a forced field can't be
     *  mistaken for an unrefused one. */
    private val mine = Settings(
        kioskName = "mine", language = "en", currency = "EUR",
        kioskCode = "mine-01", installId = "mine-install",
        logoUri = "file:///mine.png", donationStatsStartedAtMs = 111L,
        analyticsActivatedAtMs = 111L, skipApkSignatureCheckOnce = false,
        testMode = false, kioskCodeFromImport = false,
        analyticsEnabled = false, analyticsPrivacyPolicyUrl = "https://mine/p",
        analyticsTermsUrl = "https://mine/t", autoUpdateEnabled = false,
        autoUpdateGraceDays = 1, hideUpdatePrompts = false,
        donationTrackingEnabled = false, thankYouDurationSec = 1,
        backgroundColor = 0x11111111L, patternColor = 0x11111112L,
        buttonColor = 0x11111113L, buttonBorderColor = 0x11111114L,
        patternAlpha = 0.1f, useTapToPay = false, useArabicThankYou = true,
        screensaverStyle = "scrolling", screensaverCustomMessage = "mine-msg",
        screensaverIdleTimeoutSec = 600, screensaverDurationSec = 60,
        maxConsecutiveFailures = 3, restartCooldownSec = 300,
        maxRestartsBeforeGiveUp = 3, longDowntimeThresholdSec = 300,
        restartCountResetSec = 1800, autoUpdateTargetVersion = "latest",
        updateRepoUrl = "https://github.com/mine/repo"
    )

    private val theirs = Settings(
        kioskName = "theirs", language = "nl", currency = "USD",
        kioskCode = "theirs-02", installId = "theirs-install",
        logoUri = "file:///theirs.png", donationStatsStartedAtMs = 999L,
        analyticsActivatedAtMs = 999L, skipApkSignatureCheckOnce = true,
        testMode = true, kioskCodeFromImport = true,
        analyticsEnabled = true, analyticsPrivacyPolicyUrl = "https://theirs/p",
        analyticsTermsUrl = "https://theirs/t", autoUpdateEnabled = true,
        autoUpdateGraceDays = 9, hideUpdatePrompts = true,
        donationTrackingEnabled = true, thankYouDurationSec = 9,
        backgroundColor = 0x22222221L, patternColor = 0x22222222L,
        buttonColor = 0x22222223L, buttonBorderColor = 0x22222224L,
        patternAlpha = 0.9f, useTapToPay = true, useArabicThankYou = false,
        screensaverStyle = "static", screensaverCustomMessage = "theirs-msg",
        screensaverIdleTimeoutSec = 601, screensaverDurationSec = 61,
        maxConsecutiveFailures = 4, restartCooldownSec = 301,
        maxRestartsBeforeGiveUp = 4, longDowntimeThresholdSec = 301,
        restartCountResetSec = 1801, autoUpdateTargetVersion = "1.5.0",
        updateRepoUrl = "https://github.com/theirs/repo"
    )

    /** The tests above only prove a field's bucket if `mine` and `theirs`
     *  actually disagree on it — an unclassified field is caught by
     *  [everyFieldIsClassified] regardless, but a MISCLASSIFIED one (the
     *  actual `kioskName` bug: present, but in the wrong bucket) is only
     *  caught by a derivation that gates on `mine != theirs`. A field left
     *  at its shared constructor default agrees in both fixtures and is
     *  invisible to that gate.
     *
     *  Checked against [fieldNames] — every field Settings actually has —
     *  rather than the static `expectedGuarded`/`expectedComputed`/
     *  `expectedTravelling` union, deliberately: a brand-new field is by
     *  definition not yet in any of those sets, so restricting this check to
     *  them would let a new, unset field pass here even though it is exactly
     *  the case this test exists to catch. Checking every reflected field
     *  means a new field with no fixture values fails BOTH this test and
     *  [everyFieldIsClassified] at once, instead of silently waiting for
     *  someone to also remember to classify it before this test would ever
     *  notice its default was never overridden. */
    @Test
    fun fixturesDisagreeOnEveryField() {
        val agreeing = fieldNames().filter { name -> valueOf(mine, name) == valueOf(theirs, name) }
        assertTrue(
            "mine and theirs agree on $agreeing — widen the fixtures so every " +
                "Settings field has a genuinely different value in each, or the " +
                "tests above cannot tell whether merge actually guarded/travelled it",
            agreeing.isEmpty()
        )
    }

    /** The point of the whole file: a new field cannot be added to Settings
     *  without someone deciding whether it describes the device or the
     *  configuration. */
    @Test
    fun everyFieldIsClassified() {
        val classified = expectedGuarded + expectedComputed + expectedTravelling
        assertEquals("the three buckets must not overlap", classified.size,
            expectedGuarded.size + expectedComputed.size + expectedTravelling.size)
        val actual = fieldNames()
        val unclassified = actual - classified
        assertTrue(
            "new Settings field(s) $unclassified are not classified. Decide whether " +
                "each describes THIS DEVICE (add a guard to SettingsImport.merge and " +
                "list it in expectedGuarded) or the CONFIGURATION (list it in " +
                "expectedTravelling). Getting this wrong for kioskName meant a cloned " +
                "fleet billed every SumUp transaction to the golden kiosk.",
            unclassified.isEmpty()
        )
        val stale = classified - actual
        assertTrue("classified field(s) $stale no longer exist on Settings", stale.isEmpty())
    }

    /** Derived, not restated: a field is guarded when merge REFUSED the
     *  imported value outright — `merged != theirs` — considered only where
     *  `mine != theirs`, so a field can't look refused merely because the two
     *  fixtures happen to agree. Checking `merged == mine` instead (as a first
     *  draft of this test did) is wrong: `logoUri` is forced to null on merge,
     *  which never equals `mine`'s own logo path, so that check would falsely
     *  exclude a field merge genuinely guards. */
    @Test
    fun theDerivedGuardSetMatchesTheDeclaredOne() {
        val merged = SettingsImport.merge(mine, theirs)
        val derived = fieldNames().filter { name ->
            valueOf(mine, name) != valueOf(theirs, name) &&
                valueOf(merged, name) != valueOf(theirs, name)
        }.toSet()
        assertEquals(expectedGuarded, derived)
    }

    /** And the other direction, so a guard added by mistake is caught too. */
    @Test
    fun everyTravellingFieldActuallyTravels() {
        val merged = SettingsImport.merge(mine, theirs)
        for (name in expectedTravelling) {
            if (valueOf(mine, name) == valueOf(theirs, name)) continue
            assertEquals(
                "$name is declared as travelling but merge kept this device's value",
                valueOf(theirs, name), valueOf(merged, name)
            )
        }
    }

    /** The computed field is derived from the imported code, not copied from
     *  either side. Pinned in both directions so a change to how it is derived
     *  cannot pass unnoticed. */
    @Test
    fun theImportedCodeFlagIsDerivedFromTheImportedCode() {
        val withCode = SettingsImport.merge(mine, theirs.copy(kioskCode = "theirs-02"))
        assertTrue("an imported non-blank code must be flagged", withCode.kioskCodeFromImport)

        val withoutCode = SettingsImport.merge(mine, theirs.copy(kioskCode = ""))
        assertFalse(
            "a blank imported code leaves nothing to warn about, whatever the " +
                "source device's own flag said",
            withoutCode.kioskCodeFromImport
        )
    }
}
