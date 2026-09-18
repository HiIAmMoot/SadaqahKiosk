# Known debt

Work that is real, understood, and deliberately not done yet — with the reason it was deferred and what it would cost to leave. Each entry names where the reasoning was established so it does not have to be re-derived.

This file exists because deferred items were previously recorded only in phase specs, which get superseded, and in scratch workspaces, which get deleted.

---

## The 02:00 flush floor is not guaranteed while offline

**What.** `scheduleDailyLoginReset` fires once and does not re-arm itself. It is re-armed only by the login path, and both that and `performReinit` return early while offline. A kiosk with wifi down at 02:00 loses the nightly job until an operator next logs in.

**Why deferred.** That scheduler is the **card-payment recovery path**. A telemetry gap is a poor reason to change code where a mistake costs donations rather than diagnostics. Declined on this reasoning twice — first in phase 3a, which introduced the dependency, and again when scoping 3d.

**What it costs to leave.** A busy kiosk that never idles during opening hours reports at 02:00 and nowhere else; if it is also offline at 02:00, it may not report for a further day. Nothing is lost — a day's rows sit well inside the queue cap — but the dashboard lags.

**What fixing it needs.** Its own phase, with its own non-negotiables and device checks, treated as card-payment recovery work rather than telemetry work. The re-arm is not the hard part; proving it cannot break reinit is.

**Established in.** Phase 3a spec, "Known limitations"; restated in 3b and 3c specs.

---

## `SettingsBootstrap`'s call site is untested

**What.** `SettingsBootstrap.apply` is unit-tested, but the `MainActivity.onCreate` call site that invokes it is not. Re-gating it would leave the suite green. It mints `installId`, so a regression there means rows that identify nothing.

**Why deferred.** It needs **instrumented** coverage — `app/src/androidTest` currently contains only the generated `ExampleInstrumentedTest.kt`, so this means standing up instrumented testing and running it on hardware. Hardware verification is deliberately held until the telemetry phases are complete.

**What it costs to leave.** A silent regression in identity minting would be invisible to the suite and visible only as rows carrying an empty `install_id`.

**What fixing it needs.** A real instrumented harness, a device or emulator in the loop, and a test that asserts a fresh install mints an id exactly once.

**Established in.** Phase 3a spec, "Required of 3b"; restated in 3b and 3c-ii.

---

---

## ~~The truncation suffix's byte budget is guarded only by a grep~~ — RESOLVED 2026-09-16

**Closed in phase 6.** `DiagnosticEventsTest` gained
`aTruncatedMessageEndsWithTheSuffixItsBudgetWasComputedFrom`, which pins the
value actually appended to the one the budget was computed from without
hardcoding either, and `theAssembledDetailFitsTheCapForEveryAdversarialShape`,
which runs the cap against eight escape and UTF-8 shapes rather than the single
one the reserve was sized against.

Verified by mutation: appending a different suffix literal than the one measured
now fails exactly one test, which is the drift the grep was standing in for.
Setting the wrapper reserve to zero fails two. The original text follows.

---

**What.** `DiagnosticEvents.truncateWrappedMessage` reserves budget for the
truncation suffix by measuring `TelemetryRedactor.TRUNCATION_SUFFIX`'s
JSON-escaped length. Phase 3d-i gave that literal a single definition so the two
could not drift. But a reviewer simulated a drifted copy — a suffix longer than
the one the budget was computed against — and **no test failed**: the 512-byte
reserve absorbs the difference.

**Why deferred.** Nothing is wrong today, and the single definition means the two
values cannot actually diverge without someone deliberately reintroducing a
second copy. The finding is that the *test suite* would not notice if they did.
Widening 3d-i to cover it would have meant designing a budget test on a path the
phase otherwise did not touch.

**What it costs to leave.** The grep in phase 3d-i's Task 5 (`grep -rn '… truncated'
app/src/main` must return exactly one line) is the only thing standing between a
reintroduced second copy and a silently wrong budget. Greps are not run by CI.

**What fixing it needs.** A test that pins the relationship rather than the
literal: assert that a message truncated at the budget boundary, plus the suffix,
still fits inside `MAX_TEXT_BYTES` — which fails for any suffix the budget did
not account for, without hardcoding either value.

**Established in.** Phase 3d-i, Task 5 review — found by mutation, not by reading.

---

## Reporting is not gated on a published privacy policy existing

**What.** A kiosk can have a telemetry destination saved and analytics switched
on with no privacy policy URL set. Nothing stops it collecting and sending rows
in that state. Before phase 4 there was no disclosure screen either way; after
phase 4, `DisclosurePresenter.view` returns null with no policy URL, so this
kiosk also never sees the disclosure screen — it reports with no published
disclosure anywhere and no on-device signpost pointing at one.

**Why deferred.** Gating the flush on a policy URL being present was offered to
the founder and declined: an already-deployed kiosk in this state would
silently stop reporting the moment this shipped, which is a worse outcome than
the gap itself for a fleet that is already live.

**What it costs to leave.** An operator can configure a working, reporting
kiosk while skipping the field that is supposed to make that reporting legible
to donors. Nothing in the app currently tells them this is missing outside the
settings screen's existing `analyticsPolicyUrlsMissing` warning.

**What fixing it needs.** A product decision on whether to gate the flush (with
a migration path for kiosks already in this state) or add a more insistent
warning short of gating it — not a code change alone.

**Established in.** Phase 4 spec ("Global Constraints" / known gap), 2026-09-15.

---

## The disclosure's "transaction identifiers never sent" claim is not provable from this repo

**What.** The disclosure screen states SumUp transaction identifiers are never
sent. The app's own fields never carry one — nothing here reads a transaction
code. But three call sites forward SumUp's own error `MESSAGE` string into
diagnostics verbatim (after redaction): `MainActivity.kt:781` and `:825`
(`DiagnosticEvents.sumUpFailureDetail`), and `:876` (`checkoutNoReaderDetail`,
on a payment failure). `TelemetryRedactor.scrub` only removes the affiliate
key and runs of 32+ token characters (`TOKEN_SHAPED`); a SumUp transaction
code is roughly ten characters, well under that floor. If the SDK ever embeds
one in an error string, the "never" is false.

**Why deferred.** The SumUp SDK is a closed binary, pinned deliberately (a
newer version is known to crash on reinit). Nothing in this repository can
show what strings it does or does not put in
`SumUpAPI.Response.MESSAGE` on failure, so the exposure is **inferred from
the redactor's threshold, not demonstrated against a real SDK message**.
Softening the disclosure copy to hedge this was explicitly rejected — an
absolute claim this screen makes is the one thing it must not hedge on
speculation — and widening the redactor is a telemetry behaviour change out
of scope for this phase.

**What it costs to leave.** If a future SumUp SDK version ever emits a
transaction code inside its error message text, that code would reach
diagnostics unredacted, while the disclosure screen tells the operator it
never does.

**What fixing it needs.** Capturing real SumUp failure messages from a
physical device across representative failure modes (login failure, no
reader, no-connectivity payment failure) and checking whether any of them
carries a transaction code. That would either close this out as unfounded or
turn it into a redactor change with a concrete pattern to match.

**Established in.** Phase 4 whole-branch review, 2026-09-16.

## Arabic ships with a left-to-right layout

**What.** Every screen, including the disclosure, arranges itself
left-to-right in Arabic. Text is right-aligned correctly and nothing is
clipped, but the layout mirrors the English arrangement: on
`DisclosureScreen` the QR codes sit to the right of their labels rather than
the left, which is not the convention for the script.

**Why deferred.** Founder-ruled twice, most recently on 2026-09-16 after
reviewing a screenshot of the Arabic disclosure on a device: implementing RTL
is not worth it at this stage. It is a whole-app change rather than a
per-screen one, and the app is legible in Arabic as it stands.

**What it costs to leave.** An Arabic-reading operator gets a layout that
reads as foreign, on every screen. Nothing is unreadable and no information is
lost.

**What fixing it needs.** Its own phase. Compose supports it through
`LocalLayoutDirection`, but every screen would need reviewing for hardcoded
start/end assumptions, and it wants a native reader on the result rather than
a screenshot diff.

**Established in.** Phase 4 spec, "Limitations"; re-affirmed after the phase 6
device check D5, 2026-09-16.

---

## The translations are machine-produced

**What.** All eight languages were produced without a native reader. Phase 4's
review caught the Dutch using "gezondheidsgegevens", the GDPR term for
*medical* data, in a sentence about kiosk health, and the Arabic saying "the
next kiosk's startup" instead of "its next startup". Both were fixed; neither
was found by a test.

**Why deferred.** Accepted as adequate on 2026-09-16. This is UI copy rather
than binding text, and the document behind the privacy URL is what actually
discharges the disclosure obligation.

**What it costs to leave.** A plausible-but-wrong translation reads as
confident and correct. The two already found were both on the privacy screen,
which is where being wrong matters most.

**What fixing it needs.** A native read of the Dutch and Arabic disclosure
copy. Cheap, and worth doing before any vendor deployment into those markets.

**Established in.** Phase 4 spec, "Limitations"; re-affirmed 2026-09-16.

---

## `HttpPosterTest`'s redirect check is intermittently flaky

**What.** `aRedirectIsNotFollowedSoTheKeyNeverReachesAnotherHost` failed once
during phase 6 with `expected:<302> but was:<-1>`, then passed in isolation and
across two further full runs. A `-1` response code is what
`HttpURLConnection` reports when it never got a status line at all, so the
local server the test stands up almost certainly lost a port or a socket race
rather than the production code misbehaving.

**Why deferred.** Seen once in a long session and not reproducible on demand.
Chasing it without a reproduction means guessing at a fix and calling the
absence of a rare failure proof.

**What it costs to leave.** This is the test that proves the publishable key
is never replayed onto a redirect target — the guard
`UrlConnectionPoster` sets `instanceFollowRedirects = false` for. A test that
fails at random on that path trains whoever sees it to re-run rather than
investigate, which is exactly how a real regression there would be waved
through.

**What fixing it needs.** Running it in a loop to get a reproduction, then
either pinning the port allocation or making the assertion tolerate a
connection that never completed by failing with a clearer message. Cheap once
reproduced.

**Established in.** Phase 6, observed 2026-09-16 during the credentials-export
work.

---

## Resolved

Items here have been closed; kept briefly so their history is findable.

- *(none yet)*
