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

## Resolved

Items here have been closed; kept briefly so their history is findable.

- *(none yet)*
