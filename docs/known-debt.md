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

## The truncation suffix's byte budget is guarded only by a grep

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

## Resolved

Items here have been closed; kept briefly so their history is findable.

- *(none yet)*
