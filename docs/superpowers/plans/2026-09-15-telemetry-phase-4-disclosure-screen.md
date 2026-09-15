# Telemetry Phase 4 — Disclosure Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An operator who points this kiosk at a telemetry destination is shown, in their own language, what it will send and what it will never send — and a QR code to the published policy that actually discharges the obligation.

**Architecture:** A pure `DisclosurePresenter` decides whether the screen shows at all and what it contains; a thin composable renders it and decides nothing. QR codes come from ZXing `core`, encoded to a `BitMatrix` this app draws itself. Copy is fifteen new `Strings` members across eight languages.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose, JUnit 4, ZXing core (new). minSdk 30.

**Spec:** `docs/superpowers/specs/2026-09-15-telemetry-phase-4-disclosure-screen.md`

## Global Constraints

- **The screen is a signpost, not the disclosure.** The document behind the privacy URL discharges clause 17.2. What must be right is that the URL shown is the URL stored, the QR encodes exactly the URL printed beside it, and the summary does not contradict the code.
- **The copy must be true in a fork**, so no vendor is named as recipient. The recipient is the endpoint the operator typed, shown back to them.
- **Not blocking, not a consent gate.** The screen informs; it does not ask.
- **No behaviour change to telemetry itself.** No change to what is collected, queued or sent, and no change to any path a payment travels.
- **Exactly one new dependency: ZXing `core`.** Not `javase`, not `android-core`. Adding a second breaks this constraint.
- **No new persisted `Settings` field.** The trigger is a save, not a visit; nothing is remembered.
- Compose screens and `MainActivity` are unreachable from JVM tests, so every decision lives in a presenter.
- JUnit 4 only — no Mockito, MockK, Robolectric.
- Comments explain a non-obvious *why*, never a *what*.
- **No AI attribution in any commit** — no `Co-Authored-By`, no session link, no "Generated with" footer. The repository is public.
- Run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest` before every commit. Zero failures, zero `@Ignore`.

## What the code actually looks like

Verified at HEAD, because two claims in the first spec draft turned out to be false:

| Thing | Reality |
|---|---|
| `TelemetryGate.evaluate` | `!inputs.activated -> FlushBlock.NOT_ACTIVATED` (`:39`). **No per-table carve-out** — an unactivated kiosk sends nothing. |
| `DonationEvents.eventFor` | Gates on `analyticsEnabled` only (`:48`). Rows queue before activation and upload in bulk at first activation. |
| `Settings.analyticsEnabled` | Defaults **`false`** (`:41`). |
| `TelemetryCredentials.save` | Returns `UrlVerdict` — `Valid(normalised)` or `Invalid(reason)` (`:21-23`, `:119`). |
| `onAnalyticsSaveDestination` | `MainActivity.kt:627-631`, a lambda returning that verdict. |
| `AnalyticsPresenter.policyUrlsMissing` | An **OR over both URLs** (`:154`). Cannot govern a per-block decision. |
| Screen switching | A `when` chain of `showX -> XScreen(...)` inside **`AppUI`, a top-level composable at `MainActivity.kt:2554`** — *not* a member of `MainActivity`. Activity state reaches it only as parameters. |
| `Strings` | An interface of 245 `val`s; eight `object` implementations. Analytics members sit around `:310-337`. |
| Language → `Strings` | `TranslationManager.currentStrings()` (`:31`) maps the **current** language only; `rememberStrings()` (`:44`) is `@Composable`. **No mapping exists for an arbitrary `Language`**, so a JVM test cannot ask for German today. |

---

## File Structure

**Created**
- `telemetry/DisclosurePresenter.kt` — the pure decision: show or not, and what.
- `telemetry/QrEncoder.kt` — a thin wrapper over ZXing producing a `BitMatrix`.
- `screens/DisclosureScreen.kt` — renders a `DisclosureView`, decides nothing.
- `test/.../DisclosurePresenterTest.kt`, `test/.../QrEncoderTest.kt`

**Modified**
- `gradle/libs.versions.toml` — one version, one library.
- `app/build.gradle.kts` — one `implementation`.
- `Translations.kt` — fifteen members in the interface, fifteen overrides in each of eight objects, plus one new `stringsFor` mapping.
- `MainActivity.kt` — one state var, one trigger, one switch arm, one stale KDoc.
- `screens/AnalyticsSettingsScreen.kt` — one row to reopen.
- `model/Settings.kt`, `model/SettingsImport.kt` — stale KDocs only, no fields.

---

## Task 1: The decision, with no UI attached

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/DisclosurePresenter.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/telemetry/DisclosurePresenterTest.kt`

**Interfaces:**
- Produces: `DisclosureView`, `DisclosurePresenter.view(settings: Settings, destinationUrl: String): DisclosureView?`

- [ ] **Step 1: Reconnaissance**

Read `AnalyticsPresenter.kt` and `AnalyticsPresenterTest.kt` in full — this presenter follows their shape exactly, and the test file's fixture style is the one to copy. Read `Settings.kt` for the three fields this reads.

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.sadaqah.kiosk.telemetry

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclosurePresenterTest {

    private fun settings(
        privacy: String = "https://example.org/privacy",
        terms: String = "https://example.org/terms",
        kioskCode: String = "SK-0042"
    ) = Settings(
        analyticsPrivacyPolicyUrl = privacy,
        analyticsTermsUrl = terms,
        kioskCode = kioskCode
    )

    /**
     * The founder's rule: nothing to point at, no screen. This screen exists to
     * direct an operator to the published document that actually discharges the
     * disclosure obligation — with no document there is nothing to direct them
     * to, and a screen rendering everything *except* the policy section would
     * read as complete when it is not.
     */
    @Test
    fun `no privacy url means no screen at all`() {
        assertNull(DisclosurePresenter.view(settings(privacy = ""), "https://abc.supabase.co"))
    }

    @Test
    fun `a blank destination means no screen`() {
        assertNull(DisclosurePresenter.view(settings(), ""))
    }

    /**
     * policyUrlsMissing on AnalyticsPresenter is an OR over both URLs, so it
     * cannot govern this: a kiosk with a policy but no terms would lose the
     * policy block too. The terms block is independently optional.
     */
    @Test
    fun `a missing terms url does not suppress the screen or the policy block`() {
        val view = DisclosurePresenter.view(settings(terms = ""), "https://abc.supabase.co")!!
        assertEquals("https://example.org/privacy", view.privacyUrl)
        assertNull(view.termsUrl)
    }

    @Test
    fun `the destination shown is the one passed in`() {
        val view = DisclosurePresenter.view(settings(), "https://abc.supabase.co")!!
        assertEquals("https://abc.supabase.co", view.destinationUrl)
    }

}
```

**There is deliberately no `kioskCodeMissing` flag.** An earlier draft carried one so the screen could vary the identity line — but the copy reads "its kiosk code if one is set", which is already true whether or not one is. A flag with no rendering variant to select is a field that looks load-bearing and does nothing, and a test pinning it would assert a rationale the copy itself falsifies.

- [ ] **Step 3: Run and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*DisclosurePresenterTest*'`
Expected: FAIL — `DisclosurePresenter` is unresolved.

- [ ] **Step 4: Write the presenter**

```kotlin
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
```

- [ ] **Step 5: Run, then mutate**

Run the focused test — expected PASS. Then mutate: change the privacy-URL guard to check `analyticsTermsUrl` instead. Confirm `no privacy url means no screen at all` fails. Restore. Record it.

- [ ] **Step 6: Build, full suite, commit**

```bash
git add -A
git commit -m "Decide the disclosure screen outside the composable"
```

---

## Task 2: QR codes, from one dependency and no more

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/java/com/sadaqah/kiosk/telemetry/QrEncoder.kt`, `app/src/test/java/com/sadaqah/kiosk/telemetry/QrEncoderTest.kt`

**Interfaces:**
- Produces: `QrEncoder.encode(text: String): BitMatrix?`

- [ ] **Step 1: Add exactly one dependency**

`gradle/libs.versions.toml` — a version in `[versions]` and a library in `[libraries]`, matching the file's existing style:

```toml
zxing-core = "3.5.3"
```
```toml
# QR encoding for the disclosure screen's policy links. core only, deliberately:
# the javase artifact pulls in java.awt image classes this app has no use for.
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing-core" }
```

`app/build.gradle.kts`: `implementation(libs.zxing.core)`.

**Do not add `javase`, `android-core`, or any other ZXing artifact.** One new dependency is a stated constraint of this phase.

- [ ] **Step 2: Write the failing test**

```kotlin
    /**
     * A real round trip rather than a golden-image comparison, which would break
     * on any rendering change without saying anything about correctness.
     *
     * core only: the usual bridge to a BinaryBitmap is
     * BufferedImageLuminanceSource, which lives in zxing's javase artifact — a
     * second dependency this phase does not take. core can back a BinaryBitmap
     * from the BitMatrix directly.
     */
    @Test
    fun `an encoded url decodes back to itself`() {
        val url = "https://example.org/privacy?v=2"
        val decoded = decode(QrEncoder.encode(url))
        assertEquals(url, decoded)
    }

    @Test
    fun `a long url still round trips`() {
        // QR version selection has to grow with the payload; a short-only
        // encoder passes the test above and fails in the field.
        val url = "https://example.org/" + "a".repeat(180)
        assertEquals(url, decode(QrEncoder.encode(url)))
    }
```

**The route is `RGBLuminanceSource` + `HybridBinarizer`, both in `core`.** Walk the `BitMatrix` into an `IntArray` of black and white pixels, wrap it in `RGBLuminanceSource`, wrap that in `BinaryBitmap(HybridBinarizer(...))`, and hand it to `QRCodeReader().decode(...)`. There is no `BitMatrix`-to-`BinaryBitmap` constructor, which is what an earlier draft of this plan and its spec both assumed — the assumption was wrong in both, and the escape hatch it offered would have been taken for the wrong reason.

Because `encode` now returns module resolution, scale each module up when building the pixel array — a binarizer given a 25x25 image has too little to work with.

Add a third test for the null path: `assertNull(QrEncoder.encode(""))`. An empty URL is reachable in production, since nothing validates these fields.

- [ ] **Step 3: Run and watch it fail**

Expected: FAIL — `QrEncoder` unresolved.

- [ ] **Step 4: Write the encoder**

```kotlin
package com.sadaqah.kiosk.telemetry

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes a URL to a QR matrix the screen draws itself.
 *
 * A QR code is the only way an operator reaches a link from this kiosk: lock
 * task mode ([MainActivity] calls `startLockTask`) means no browser can be
 * launched, so a tappable link is inert and the operator's own phone is the
 * only reader.
 */
object QrEncoder {

    /** Error correction M, not L: these codes are read off a kiosk screen at an
     *  angle, often under shop lighting, and M tolerates that for about 10%
     *  more modules. Not H, which would inflate the symbol for no gain at this
     *  payload size. */
    private val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        // Four modules, which is the quiet zone the QR specification requires.
        // Less than that still decodes in a test harness reading a clean
        // matrix and fails against a phone camera pointed at a lit screen —
        // a defect no unit test on this machine can catch.
        EncodeHintType.MARGIN to 4,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )

    /**
     * Returns the symbol at **module resolution** — roughly 25x25 to 60x60 —
     * not at pixel size. The caller scales it when drawing.
     *
     * Asking ZXing for 512x512 would return a 512x512 matrix, and a Canvas
     * drawing one rect per dark module would then issue ~130,000 draw calls
     * per code, twice per frame. Passing 0 lets the writer pick the smallest
     * version that fits, and the screen scales ~45 rects per side instead.
     *
     * Null rather than a throw: these URLs are never validated anywhere
     * (`TelemetryUrl` checks only the Supabase endpoint), so the input can be
     * empty or too long for any QR version, and `QRCodeWriter.encode` throws
     * on both. A throw during composition would take down a kiosk in lock
     * task mode, which cannot be dismissed. The screen renders the URL as
     * text and omits the code.
     */
    fun encode(text: String): BitMatrix? = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    } catch (_: Exception) {
        null
    }
}
```

- [ ] **Step 5: Run, then verify the dependency count**

Focused test passes. Then confirm exactly one artifact was added:

```bash
grep -c "zxing" gradle/libs.versions.toml   # expect 2: one version, one library
./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>/dev/null | grep -i zxing
```

The second command must list `core` and nothing else. Paste both outputs in your report.

- [ ] **Step 6: Build, full suite, commit**

```bash
git add -A
git commit -m "Encode policy URLs as QR, since lock task blocks a browser"
```

---

## Task 3: Fifteen members, eight languages

Mechanical and large: fifteen interface members plus fifteen overrides in each of eight objects — 120 strings. Batched as one task because it is the same edit repeated, and splitting it would buy eight review seats for one diff.

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/Translations.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/TranslationsTest.kt` (create if absent)

- [ ] **Step 1: Reconnaissance**

Read the `Strings` interface and one full implementation. Note where the analytics members sit (`~:324-333`) and put the disclosure members immediately after them in the interface and in every implementation, in the same order — a reader comparing two languages should be able to diff them.

Check whether `TranslationsTest.kt` exists; if not, create it.

- [ ] **Step 2: Write the failing tests**

```kotlin
    /**
     * A member present but empty compiles and ships a blank screen. The
     * interface catches a *missing* override; nothing catches an empty one.
     */
    @Test
    fun `every language defines every disclosure member`() {
        Language.entries.forEach { language ->
            val s = stringsFor(language)
            disclosureMembers(s).forEachIndexed { i, value ->
                assertTrue("${language.code} member $i is blank", value.isNotBlank())
            }
        }
    }

    /**
     * URLs come from settings and differ per deployment. A hardcoded one in
     * copy would be wrong in every fork, and this phase's whole posture is that
     * the recipient is the endpoint the operator typed.
     */
    @Test
    fun `no translated disclosure copy contains a url`() {
        Language.entries.forEach { language ->
            val s = stringsFor(language)
            disclosureMembers(s).forEach {
                assertFalse("${language.code}: copy must not embed a URL", it.contains("http"))
            }
        }
    }
```

**Both tests iterate one shared `disclosureMembers(s): List<String>` helper listing all fifteen, so the two cannot drift apart and neither can silently cover a subset — the URL scan in an earlier draft checked five of fifteen while its name claimed all.

`stringsFor` does not exist yet — add it, as Step 2a below.** An earlier draft of this plan told you to reuse it, which was wrong: `Translations.kt` has `currentStrings()` for the *current* language and `rememberStrings()` which is `@Composable` and unreachable from a JVM test. Neither can be asked for German.

- [ ] **Step 2a: Add the arbitrary-language mapping the tests need**

`TranslationManager` gains one function, and `currentStrings()` delegates to it so the `when` exists once rather than twice:

```kotlin
    /** Exposed for tests, which must be able to ask for a language other than
     *  the current one. [rememberStrings] is @Composable and [currentStrings]
     *  reads the live selection, so neither can answer "what does German say". */
    fun stringsFor(language: Language): Strings = when (language) {
        Language.DUTCH   -> DutchStrings
        Language.ENGLISH -> EnglishStrings
        Language.GERMAN  -> GermanStrings
        Language.FRENCH  -> FrenchStrings
        Language.SPANISH -> SpanishStrings
        Language.ITALIAN -> ItalianStrings
        Language.TURKISH -> TurkishStrings
        Language.ARABIC  -> ArabicStrings
    }

    fun currentStrings(): Strings = stringsFor(_currentLanguage.value)
```

The test calls `TranslationManager.stringsFor(language)`.

- [ ] **Step 3: Write the English copy first, and treat it as the source**

English is the text every translation is made from, so it is worth getting exactly right before it is copied seven times.

```kotlin
    override val disclosureTitle = "What this kiosk reports"
    override val disclosureIntro =
        "A reporting destination has been saved. Once reporting is switched on and tested, this kiosk will send the following to it."
    override val disclosureSendsHeading = "What is sent"
    override val disclosureSendsAmount = "The amount, currency and time of each donation."
    override val disclosureSendsIdentity = "This kiosk's install id, and its kiosk code if one is set."
    override val disclosureSendsHealth = "The app version, and events about this kiosk's own health — restarts, card reader problems, crashes."
    override val disclosureIdentified =
        "These reports identify this kiosk. They are not anonymous."
    override val disclosureNeverHeading = "What is never sent"
    override val disclosureNeverBody =
        "Donor names. Card numbers or any card data. SumUp transaction identifiers."
    override val disclosureDestinationHeading = "Where it goes"
    override val disclosureOffBody =
        "Switching analytics off stops this kiosk recording anything. Clearing the destination stops it sending what it has already recorded."
    override val disclosurePrivacyLabel = "Privacy policy"
    override val disclosureTermsLabel = "Terms"
    override val disclosureDismiss = "Close"
    override val disclosureReopen = "What this kiosk reports"
```

Two lines here are load-bearing and were wrong in an earlier draft.

`disclosureIntro` says "once reporting is switched on and tested" and **not** "reporting is now on". At the moment this screen appears `analyticsEnabled` defaults to `false` (`Settings.kt:41`) and the gate blocks an unactivated kiosk outright (`TelemetryGate.kt:39`), so the latter is false on every fresh kiosk.

`disclosureOffBody` names **both** switches because they do different things. `DonationEvents.eventFor` gates on `analyticsEnabled` alone (`:48`), so clearing the destination stops *sending* while the kiosk carries on *writing identified donation rows to disk*. An earlier draft said only "clearing the destination turns reporting off", which told an operator the wrong way to stop data collection — on the one screen whose whole purpose is being accurate about what the code does.

- [ ] **Step 4: Translate into the remaining seven**

Dutch, German, French, Spanish, Italian, Turkish, Arabic. Match the register of the analytics copy already in each implementation — these are operator-facing, not donor-facing.

**The exclusions list must stay absolute in every language.** "Card data is never sent" cannot become "is not normally sent" or "is not usually sent". A softened translation makes the screen contradict the code, which is the one thing this screen must never do.

- [ ] **Step 5: Run, then mutate**

Run the focused test — expected PASS. Then blank one member in one language, confirm the blankness test fails and names that language, restore. Record it.

- [ ] **Step 6: Build, full suite, commit**

```bash
git add -A
git commit -m "Add disclosure copy in eight languages"
```

---

## Task 4: The screen, and the two places it opens from

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/screens/DisclosureScreen.kt`
- Modify: `MainActivity.kt`, `screens/AnalyticsSettingsScreen.kt`, `model/Settings.kt`, `model/SettingsImport.kt`

**Interfaces:**
- Consumes: `DisclosurePresenter.view` (Task 1), `QrEncoder.encode` (Task 2), the fifteen `Strings` members (Task 3).

- [ ] **Step 1: Reconnaissance**

Read `AnalyticsSettingsScreen.kt` in full — the new screen follows its layout idiom, its `responsiveDp`/`responsiveSp` sizing, and its colour handling. Read `MainActivity.kt:2670-2700` for the screen-switch chain and `:244` for the state-var pattern.

- [ ] **Step 2: The screen**

A composable taking `view: DisclosureView`, `strings: Strings`, `onDismiss: () -> Unit`. It renders the title, the intro, the three what-is-sent lines, the identified line, the never-sent block, the destination, then the policy block and — only when `view.termsUrl != null` — the terms block, then the dismiss button.

Each URL block is the label, the URL as selectable text, and the QR beside it. Draw the `BitMatrix` with Compose `Canvas`, one filled rect per dark module; do not convert to a `Bitmap`.

**When `view.kioskCodeMissing` is true, render `disclosureSendsIdentity` without its kiosk-code clause** — the presenter reports the fact so the screen can say the true thing. Decide how in the composable only if it is a pure string choice; if it needs logic, it belongs in the presenter.

Left-to-right in every language including Arabic: no `LayoutDirection` override anywhere. Founder-ruled; the rest of the app is the same.

- [ ] **Step 3: Trigger it on a successful save**

`MainActivity.kt:244` gains `var disclosureView by mutableStateOf<DisclosureView?>(null)`.

**`AppUI` is a top-level composable (`MainActivity.kt:2554`), not a member of `MainActivity`**, so that state does not reach the switch chain by being in scope — it has to be passed. An earlier draft of this plan wrote the switch arm as though it were inside the Activity, and it would not have compiled.

`AppUI` gains two parameters alongside the analytics ones it already takes:

```kotlin
    disclosureView: DisclosureView?,
    onDismissDisclosure: () -> Unit,
```

and the call site inside `setContent` passes `disclosureView = disclosureView` and `onDismissDisclosure = { disclosureView = null }`. Follow how `analyticsView` and `onShowAnalyticsSettings` are already threaded through — the shape is identical.

`onAnalyticsSaveDestination` (`:627-631`) becomes:

```kotlin
                    onAnalyticsSaveDestination = { url, key ->
                        val verdict = telemetryCredentials.save(url, key)
                        refreshAnalyticsSnapshot()
                        // Only a stored destination is worth disclosing: a save
                        // the credentials layer rejected configured nothing, so
                        // naming a destination that was never kept would be
                        // worse than showing nothing.
                        if (verdict is UrlVerdict.Valid) {
                            disclosureView = DisclosurePresenter.view(settings, verdict.normalised)
                        }
                        verdict
                    },
```

A null from the presenter leaves the state null and no screen appears — which is the no-policy case, handled without a branch here.

**The policy-URL save must trigger it too, or a common order of operations never shows the screen at all.** An operator on a fresh kiosk who saves the destination *before* filling in the policy URLs gets a null view — correctly, there is no policy yet — and then nothing ever calls the presenter again, because the destination is already saved. `onAnalyticsPolicyUrlsChange` (`MainActivity.kt:634-636`) therefore runs the same check after it stores the URLs, against the destination already in `analyticsSnapshot`. Device check 1 exercises exactly this order.

The switch chain gains an arm **before** `showAnalyticsSettings`, so the disclosure sits above the settings screen it was opened from:

```kotlin
            disclosureView != null -> DisclosureScreen(
                view = disclosureView,
                strings = rememberStrings(),
                onDismiss = onDismissDisclosure
            )
```

- [ ] **Step 4: The reopen row**

In `AnalyticsSettingsScreen`, near the policy-URL fields, a row labelled `strings.disclosureReopen` that calls a new `onShowDisclosure` lambda. `MainActivity` wires it to `disclosureView = DisclosurePresenter.view(settings, analyticsSnapshot?.config?.baseUrl.orEmpty())`.

A disclosure an operator dismissed months ago is not one they can consult; this is the path back to it. It goes through the same presenter, so a kiosk with no policy URL gets no screen here either.

- [ ] **Step 5: Correct two stale KDocs**

`Settings.kt:42-44` and `SettingsImport.kt:25-27` both describe `analyticsActivatedAtMs` in terms of a disclosure flag, from when the original design planned to overload it for that. It has never carried that meaning in shipped code, and this phase deliberately adds no field. Rewrite both to describe what it actually records: when this kiosk first successfully reported.

- [ ] **Step 6: Record the gap the spec names**

The spec carries a known gap it explicitly says to record: nothing gates reporting on the policy URLs existing, so a kiosk with a destination and no privacy URL collects and sends with no published disclosure anywhere — and after this phase, with no disclosure screen either. Gating the flush was offered to the founder and declined, because it would silently stop reporting on any already-deployed kiosk in that state.

Add an entry to `docs/known-debt.md` in the shape the file already uses — **What**, **Why deferred**, **What it costs to leave**, **What fixing it needs**, **Established in**. Without this the decision lives only in a spec that a later phase supersedes.

- [ ] **Step 7: Build, full suite, commit**

The screen itself has no JVM test and cannot have one — say so in your report rather than writing a test that asserts nothing. Every decision it depends on is already pinned in Task 1.

```bash
git add -A
git commit -m "Show the disclosure after a destination is saved"
```

---

## Self-review against the spec

| Spec requirement | Task |
|---|---|
| Screen is a signpost; QR encodes the stored URL exactly | Task 2 Steps 2, 4 |
| No policy URL ⇒ no screen | Task 1 Steps 2, 4 |
| Terms block independently optional | Task 1 Step 2 |
| `policyUrlsMissing` cannot govern (OR over both) | Task 1 Step 2, with its own test |
| Copy split per item so it stays true with a blank kiosk code | Task 3 Step 3 + Task 1's `kioskCodeMissing` |
| `disclosureIdentified` — reports identify this kiosk | Task 3 Step 3 |
| Intro must not claim reporting is on | Task 3 Step 3 |
| Exclusions stay absolute in translation | Task 3 Step 4 |
| Trigger on `UrlVerdict.Valid` only | Task 4 Step 3 |
| Reachable again from settings | Task 4 Step 4 |
| Exactly one new dependency | Task 2 Steps 1, 5 — with a verification command |
| No new `Settings` field | Enforced by omission; Task 4 Step 5 corrects the KDocs that implied one |
| Known gap recorded in `docs/known-debt.md` | Task 4 Step 6 |
| LTR in Arabic | Task 4 Step 2 |
| Every decision outside the composable | Task 1 |
| Device checks 1-6 | Manual; stay in the spec |

**Ordering is load-bearing.** Task 4 consumes all three earlier tasks. Tasks 1, 2 and 3 are independent of each other and could run in any order.

**Known incompleteness.** Task 3's seven translations are prose the implementer writes; the plan gives the English source and the one rule that must survive translation. Task 4's composable is described rather than written, because it follows an existing screen's idiom closely and copying that idiom matters more than matching code I would invent here.
