# Telemetry Phase 4 — The Disclosure Screen

**Goal:** An operator who points this kiosk at a telemetry destination is told, in their own language, exactly what it will send and what it will never send.

**This screen is a signpost, not the disclosure itself.** The document that discharges clause 17.2 is the one the operator publishes at the privacy-policy and terms URLs — the thing behind the QR code. The app points at it; it does not contain it.

That distinction sets the standard for everything below. The screen's copy is ordinary UI text and is held to ordinary UI standards. What has to be right is that the URLs shown are the ones stored, that the QR encodes exactly the URL printed beside it, and that the summary of what is and is not sent does not contradict what the code actually does.

## What recon changed

### The field the original design planned to use is taken

`docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md:503` says "Showing it writes `analyticsActivatedAtMs` so it is not repeated on every visit." That was written before phase 3a shipped. The field now has an owner and a meaning: `MainActivity.kt:2061-2063` stamps it on the first **successful activation**, and the comment there is explicit that it "names when this kiosk began reporting" and must not drift forward.

Overloading it would break both jobs. A kiosk that activates before the disclosure is dismissed would never show the disclosure; a kiosk that sees the disclosure first would report a reporting-start time from before it ever reported.

**So the disclosure gets its own field, `analyticsDisclosureShownAtMs`.** Same shape, same persistence, different question.

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
| `disclosureIntro` | That a destination has been configured, and that reporting is now on. |
| `disclosureSendsHeading` | What is sent |
| `disclosureSendsBody` | Donation amount, currency and time; the kiosk code and install id; app version; diagnostic events about the kiosk's own health. |
| `disclosureNeverHeading` | What is never sent |
| `disclosureNeverBody` | Donor names, card numbers or any card data, and SumUp transaction identifiers. |
| `disclosureDestinationHeading` | Where it goes |
| `disclosureOffBody` | That clearing the endpoint turns reporting off. |

Plus `disclosurePrivacyLabel`, `disclosureTermsLabel` for the two URL blocks, and `disclosureDismiss` for the button. **Eleven members across eight languages — 88 strings.**

The destination URL, the privacy URL and the terms URL are rendered from settings, not from copy, so they are never translated and never stale.

### The URL blocks

Each renders as: the label, the URL as selectable text, and a QR code beside it. The QR encodes the URL exactly as stored — no shortening, no tracking parameters, nothing added.

If a URL is blank, its block is omitted entirely rather than rendering an empty QR. `AnalyticsPresenter` already computes `policyUrlsMissing` (`:154`) for the settings screen; the same condition governs here.

**A known gap, put to the founder on 2026-09-15 and deliberately left as it is.** Nothing gates reporting on those URLs existing. `policyUrlsMissing`'s only consumer is a warning line at `AnalyticsSettingsScreen.kt:352-354`; `TelemetryGate` has no check on it and neither does `TelemetryManager`. So a kiosk configured with a destination and no privacy URL collects and sends donation data with no published disclosure anywhere. Options offered were: say so on this screen, gate the flush, block the save, or keep the warning line. **The founder chose the warning line** — gating would silently stop reporting on any already-deployed kiosk in that state the moment it updated. Recorded here so it is not rediscovered as a defect, and carried to `docs/known-debt.md`.

---

## Where it appears

After a destination is saved — `onAnalyticsSaveDestination` in `MainActivity.kt:634-638` — and only when `analyticsDisclosureShownAtMs == 0L`. Dismissing it stamps the field.

**Not on activation.** The design's original wording tied it to configuring a destination, and saving the endpoint is that moment. Tying it to activation would mean an operator who saves a destination and never presses "Test connection" is never told what the kiosk reports — and that kiosk still reports, because the flush gate does not require activation for donation rows.

**It is reachable again afterwards.** A one-time screen an operator dismissed six months ago is not a disclosure they can consult. The analytics settings screen gets a row that reopens it, which costs one more `Strings` member (`disclosureReopen`) and makes the total **twelve members, 96 strings**.

---

## The translations

Seven languages beyond English: Dutch, German, French, Spanish, Italian, Turkish, Arabic. Machine-produced, held to the same standard as the 241 members already in `Translations.kt` — this is UI copy, not the binding text, because the binding text lives behind the URL.

The one thing translation must not do is change meaning in the exclusions list. "Card data is never sent" has to stay an absolute in every language; a translation that softens it into "card data is not normally sent" would make the screen contradict the code.

---

## Testing

### JVM-tested

- **Every `Strings` implementation defines all twelve members.** The interface makes this a compile error, which is the real test — but a test that enumerates `Language.entries` and asserts no member is blank catches the copy-paste failure where a member is present and empty.
- **No translated member contains a URL.** URLs come from settings; a hardcoded one in copy would be wrong in every fork. A test that scans all eight implementations for `http` catches it.
- **The QR encoder round-trips.** Encode a URL, decode the bit matrix, get the same string back. ZXing ships a decoder, so this is a real round-trip rather than a golden-image comparison that breaks on any rendering change.
- **A blank URL omits its block** — the pure decision, not the composable.
- **The dismiss stamp is one-way**: a second dismissal does not move `analyticsDisclosureShownAtMs`, exactly as `analyticsActivatedAtMs` does not drift.

### Not unit-testable, and labelled as such

The screen itself. Compose screens are unreachable from JVM tests here, which is why every decision above — which blocks render, whether to show at all, what the stamp does — lives outside the composable in a presenter, as `AnalyticsPresenter` already does for the settings screen.

### Device checks

1. Save a destination on a fresh kiosk: the disclosure appears, states the endpoint just entered, and dismisses.
2. Save a destination again: it does not reappear.
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

**A new field rather than reusing `analyticsActivatedAtMs`.** That field answers "when did this kiosk begin reporting" and is documented not to drift. The disclosure answers a different question and gets its own.

**Shown on save, not on activation.** A kiosk that saves a destination and never tests it still reports.

**Reachable again from settings.** A disclosure that can only be seen once is not one an operator can consult.

**ZXing rather than a hand-rolled encoder.** Founder-ruled, and the reasoning is that correctness on this screen is the whole point.

---

## Required of phase 5

- Documentation, the last phase.
- The 1.4.0 version bump on the preview line, `-preview` suffix first per the repo convention, before device testing.
