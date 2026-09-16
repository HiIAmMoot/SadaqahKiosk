# Telemetry Phase 4 — The Disclosure Screen

**Goal:** An operator who points this kiosk at a telemetry destination is told, in their own language, exactly what it will send and what it will never send.

**This screen is a signpost, not the disclosure itself.** The document that discharges clause 17.2 is the one the operator publishes at the privacy-policy and terms URLs — the thing behind the QR code. The app points at it; it does not contain it.

That distinction sets the standard for everything below. The screen's copy is ordinary UI text and is held to ordinary UI standards. What has to be right is that the URLs shown are the ones stored, that the QR encodes exactly the URL printed beside it, and that the summary of what is and is not sent does not contradict what the code actually does.

## What recon changed

### No new persisted field, because the trigger is a save and not a visit

The original design says "Showing it writes `analyticsActivatedAtMs` so it is not repeated on every visit" (`2026-09-02-kiosk-telemetry-design.md:503`). That field is now taken: `MainActivity.kt:2061-2063` stamps it on the first **successful activation**, and the comment there is explicit that it names when the kiosk began reporting and must not drift.

A first draft of this spec added `analyticsDisclosureShownAtMs` to replace it. **That was over-built.** The concern the original design was solving is repetition on every *visit* to the settings screen. This spec triggers on a successful **destination save**, which is a rare, deliberate act — so nothing needs remembering, and the screen showing again after a re-save is correct rather than annoying: the operator just changed where data goes.

Deleting the field removes two failure modes with it. A new `Settings` field must be handled in `SettingsImport.merge` or a fleet import silently stamps every kiosk and none ever shows the disclosure; and a stamp that nothing clears means an operator who changes the destination is never told the new one. Both dissolve.

### There is no QR capability in this codebase

The design requires each URL to render as text **plus a QR code**, because lock-task mode (`MainActivity.kt:277`, `:829`) means a browser cannot be launched and a link is inert. There is no encoder here — no ZXing, no barcode library, nothing.

**Ruled by the founder on 2026-09-15: add ZXing core.** It is pure Java with no Android dependencies, encodes to a bit matrix this app draws itself, and it is the only new dependency this phase adds. Writing a QR encoder by hand — Reed-Solomon error correction, mask selection, version sizing — is several hundred lines of dense code on a screen whose entire purpose is being correct.

This is a deliberate exception to the standing "no new third-party dependency" rule. That rule exists to keep telemetry internals auditable; a UI-side encoder that never touches a payload is a different question, and it was put to the founder rather than assumed.

### Arabic ships without RTL, deliberately

`Translations.kt:16` includes Arabic, and the app has **no** RTL handling on any screen today. **Ruled by the founder: leave this screen LTR like every other one.** Arabic text still renders correctly; only the layout direction differs from convention. Mirroring the whole app is its own phase with its own device testing.

---

## Non-negotiables

1. **The copy must be true in a fork as well as a vendor deployment.** This rules out naming Sadaqah Kiosk, or any vendor, as the recipient. The recipient is the endpoint the operator typed, shown back to them.
2. **Not blocking, and not a consent gate.** Supplying an endpoint and credentials is already the decision. The screen informs; it does not ask.
3. **The donation flow is not altered.** No change to any path a payment travels.
4. **It must state what is never sent**, not only what is. The list of exclusions is the part an operator cannot verify for themselves.
5. **No behaviour change to telemetry itself.** This phase adds a screen and copy. It does not change what is collected, queued, or sent.
6. **One new dependency, named and justified**, and no other.

---

## What the screen says

Eight blocks of copy, each a `Strings` member so it translates:

| Member | Content |
|---|---|
| `disclosureTitle` | What this kiosk reports |
| `disclosureIntro` | That a destination has been configured, and what will be sent to it **once reporting is switched on and tested**. |
| `disclosureSendsHeading` | What is sent |
| `disclosureSendsAmount` | Donation amount, currency and time. |
| `disclosureSendsIdentity` | The kiosk's install id, and its kiosk code where one is set. |
| `disclosureSendsHealth` | App version, and diagnostic events about the kiosk's own health. |
| `disclosureIdentified` | That these reports identify **this kiosk**, and are not anonymous. |
| `disclosureNeverHeading` | What is never sent |
| `disclosureNeverBody` | Donor names, card numbers or any card data, and SumUp transaction identifiers. |
| `disclosureDestinationHeading` | Where it goes |
| `disclosureOffBody` | **Both** switches: analytics off stops the kiosk *recording*; clearing the destination stops it *sending* what it already recorded. `DonationEvents.eventFor` gates on `analyticsEnabled` alone (`:48`), so naming only the destination would tell an operator the wrong way to stop collection. |

Plus `disclosurePrivacyLabel`, `disclosureTermsLabel`, `disclosureDismiss`, and `disclosureReopen` for the settings row. **Fifteen members across eight languages — 120 strings.**

**`disclosureIntro` must not claim reporting is on.** At save time it is not: `Settings.analyticsEnabled` defaults to `false` (`:41`) and the gate blocks an unactivated kiosk outright (`TelemetryGate.kt:39`). A first draft of this spec had the intro say "reporting is now on", which would have been false on every fresh kiosk — the screen's one job is being accurate about the code, and that line contradicted it.

**The what-is-sent copy is split by item rather than delivered as one paragraph**, for the same reason: `disclosureSendsIdentity` can be phrased so it stays true when `kioskCode` is blank, which a single blob claiming "the kiosk code" cannot.

**`disclosureIdentified` restores a requirement from the original design** (`2026-09-02-kiosk-telemetry-design.md:64`) that the first draft dropped without noticing: the screen must say these reports identify the kiosk and are not anonymous. That is the single most consequential sentence on the screen, and it was missing.

The destination URL, the privacy URL and the terms URL are rendered from settings, not from copy, so they are never translated and never stale.

### The URL blocks

Each renders as: the label, the URL as selectable text, and a QR code beside it. The QR encodes the URL exactly as stored — no shortening, no tracking parameters, nothing added.

**Nothing to point at means no screen.** Founder ruling, 2026-09-15: if no privacy-policy URL is set, the disclosure is skipped entirely rather than rendered with a hole in it. The screen exists to point at a published document; with no document there is nothing to point at, and a screen that renders the rest while quietly omitting the policy section would read as complete when it is not.

This is self-correcting, which is why it needs no field: nothing is remembered, so if the operator later adds a policy URL and saves, the screen appears then.

`AnalyticsPresenter.policyUrlsMissing` (`:154`) cannot govern this — it is an **OR over both URLs**, so one blank terms URL would suppress a perfectly good privacy URL. The disclosure decides on the privacy URL alone, and renders the terms block only if that URL is non-blank.

**The gap this leaves, knowingly.** Nothing gates reporting on those URLs existing: `policyUrlsMissing`'s only consumer is a warning line at `AnalyticsSettingsScreen.kt:352-354`, and neither `TelemetryGate` nor `TelemetryManager` checks it. A kiosk with a destination and no privacy URL still collects and sends, and now simply shows no disclosure screen either. Gating the flush was offered and declined: it would silently stop reporting on any already-deployed kiosk in that state the moment it updated. Carried to `docs/known-debt.md` so it is not rediscovered as a defect.

---

## Where it appears

After a destination is saved — `onAnalyticsSaveDestination`, `MainActivity.kt:627-631` — **and only when that save returned `UrlVerdict.Valid`**. A save the credentials layer rejected has configured nothing, so disclosing a destination that was not stored would be worse than showing nothing.

### Every decision lives in a presenter, not in the composable

`MainActivity` and Compose screens are unreachable from JVM tests here. The first draft of this spec put the trigger in a composable lambda and then asked for JVM tests of behaviour that would have lived inside it — three of the five proposed tests were unbuildable as written.

So the phase adds `DisclosurePresenter`, in the shape `AnalyticsPresenter` already uses:

```kotlin
    fun view(settings: Settings, config: TelemetryConfig?): DisclosureView?
```

Null means *do not show* — no privacy URL, or no destination. The composable renders a non-null view and decides nothing. Whether to show, which blocks appear, and what each says are then all testable without a device.

**Not on activation, and the first draft of this spec justified that with a false claim.** It asserted that "the flush gate does not require activation for donation rows". `TelemetryGate.kt:39` is `!inputs.activated -> FlushBlock.NOT_ACTIVATED`, with no per-table carve-out: an unactivated kiosk sends nothing at all. The claim was written without reading the gate.

The true reason is better. `DonationEvents.eventFor` gates only on `analyticsEnabled` (`:48`), not on activation — so a kiosk with analytics on and a destination saved **queues donation rows locally straight away**, and that backlog uploads in bulk the moment someone first presses "Test connection". Telling the operator at save time means telling them before the rows exist. Telling them at activation means telling them as the backlog is already leaving.

**It is reachable again afterwards.** A screen an operator dismissed six months ago is not a disclosure they can consult. The analytics settings screen gets a row that reopens it — one more `Strings` member (`disclosureReopen`).

---

## The translations

Seven languages beyond English: Dutch, German, French, Spanish, Italian, Turkish, Arabic. Machine-produced, held to the same standard as the 241 members already in `Translations.kt` — this is UI copy, not the binding text, because the binding text lives behind the URL.

The one thing translation must not do is change meaning in the exclusions list. "Card data is never sent" has to stay an absolute in every language; a translation that softens it into "card data is not normally sent" would make the screen contradict the code.

---

## Testing

### JVM-tested

- **Every `Strings` implementation defines all fifteen members.** The interface makes this a compile error, which is the real test — but a test that enumerates `Language.entries` and asserts no member is blank catches the copy-paste failure where a member is present and empty.
- **No translated member contains a URL.** URLs come from settings; a hardcoded one in copy would be wrong in every fork. A test that scans all eight implementations for `http` catches it.
- **The QR encoder round-trips.** Encode a URL, decode the bit matrix, get the same string back.

  **This must use `core` only.** ZXing's `QRCodeReader` decodes from a `BinaryBitmap`, and the usual bridge to one is `BufferedImageLuminanceSource` — which lives in the `javase` artifact and would be a **second** new dependency, breaking this phase's own rule 6. `core` alone is sufficient: the encoder produces a `BitMatrix`, and `BitMatrix` can back a `BinaryBitmap` through `core`'s own `BitMatrix`-based luminance path, with no image classes involved. If the implementer finds that path does not exist in the pinned version, the honest fallback is to assert the matrix's dimensions and module pattern against a known-good vector and **say in the report that a true round-trip was not achieved** — not to quietly add `javase`.
- **A blank URL omits its block** — the pure decision, not the composable.
- **Re-saving a destination shows the screen again.** There is no stamp and nothing is remembered — saving a destination is a rare, deliberate act, and an operator who has just changed where data goes should be told where it now goes. A test pins that the presenter is pure: the same inputs give the same view, with no hidden first-call behaviour.

### Not unit-testable, and labelled as such

The screen itself. Compose screens are unreachable from JVM tests here, which is why every decision above — which blocks render, whether to show at all, what the stamp does — lives outside the composable in a presenter, as `AnalyticsPresenter` already does for the settings screen.

### Known and not fixed here

**The policy URLs are never validated.** `TelemetryUrl` checks only the Supabase endpoint; the privacy and terms fields accept any text. So the QR faithfully encodes whatever was typed, including something that is not a URL at all. This phase does not add validation — that belongs with the settings screen that collects them, not the screen that displays them — but device check 4 must therefore actually scan and resolve both codes rather than merely confirm a code renders.

**Two stale KDocs to correct while here.** `Settings.kt:42-44` and `SettingsImport.kt:25-27` both describe `analyticsActivatedAtMs` in terms of a disclosure flag, from the era when the original design planned to overload it. Neither is true now.

### Device checks

1. Save a destination on a fresh kiosk: the disclosure appears, states the endpoint just entered, and dismisses.
2. Save a destination again: the disclosure appears again — there is no stamp suppressing it, and the operator has just changed where data goes.
3. Reopen it from the analytics settings screen: it appears with the same content.
4. Scan both QR codes with a phone: each resolves to exactly the URL shown as text beside it.
5. Switch to Arabic and reopen: the copy is Arabic, the layout is LTR, and nothing is clipped.
6. Clear the endpoint: reporting stops, and the disclosure's claim about that is true.

---

## Limitations, stated rather than buried

**The screen is LTR in Arabic.** Founder-ruled. Text renders correctly; layout direction does not match convention for that script.

**The translations are machine-produced.** Stated above rather than discovered later.

**The disclosure is informational and cannot be declined.** That is the design: clearing the endpoint is the off switch, and the screen says so. An operator who objects has an action, not a button.

---

## Decisions

**No new persisted field, and no reuse of `analyticsActivatedAtMs`.** That field answers "when did this kiosk begin reporting" and is documented not to drift. A first draft of this spec gave the disclosure its own field to avoid overloading it — but the trigger is a save, a rare and deliberate act, so nothing needs remembering at all: showing the screen again after a re-save is correct, not a bug to suppress.

**Shown on save, not on activation.** A kiosk that saves a destination and never tests it still reports.

**Reachable again from settings.** A disclosure that can only be seen once is not one an operator can consult.

**ZXing rather than a hand-rolled encoder.** Founder-ruled, and the reasoning is that correctness on this screen is the whole point.

---

## Required of phase 5

- Documentation, the last phase.
- The 1.4.0 version bump on the preview line, `-preview` suffix first per the repo convention, before device testing.
