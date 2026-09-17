# Hardware test checklist

Everything deferred across telemetry phases 2c through 5, consolidated and ordered by setup cost rather than by phase. Nothing here is covered by a unit test, and several of these checks are the only evidence that a decision taken on paper actually holds.

Run the sessions in order. Each one assumes the state the previous session left behind, so jumping around means re-provisioning.

**Build under test:** `1.4.0-preview`, versionCode 15.

Four checks are marked **settles debt** — they are the only way to close their `docs/known-debt.md` entry, and phase 6 depends on their result.

---

## Run the automated ones first

Eighteen of these no longer need a human. They run against an attached device or an emulator:

```bash
python tools/kiosk-check/run.py              # every runnable check
python tools/kiosk-check/run.py --session D  # one session
python tools/kiosk-check/run.py --list       # what exists, and what is skipped
python tools/kiosk-check/run.py --out results.md
```

Needs adb on `PATH` (or `ANDROID_HOME` set), one device attached, and either root (`adb root`, available on a non-Play-Store emulator image) or a debuggable build. `--session D` additionally needs `opencv-python-headless` to decode the QR codes.

**The runner reports `SKIP` with a reason for everything it cannot do**, rather than quietly leaving it out. A run that omitted the nine card-reader checks and still printed all-green would be worse than no runner at all, so the skip list is part of the output and the reason is always stated.

Checks below carrying a **`[auto]`** marker are covered. The rest need hands, hardware, or your eyes.

**A caution that cost an hour to learn.** The runner writes settings into app-private storage over adb. Pushing a file there as root gives it a root-derived SELinux label, and Android does not report the resulting `EACCES` — `SharedPreferencesImpl` logs a warning and hands the app an *empty* map, so it silently runs on defaults and every check built on a patched setting passes for the wrong reason. `push_app_file` restores the label and verifies it against the parent directory. Do not bypass it.

---

## What you need

| Item | Needed for |
|---|---|
| Lenovo M9 (or the target tablet), factory-resettable | Session A |
| Device-owner provisioning over adb | Sessions B onward |
| SumUp card reader, paired | Session G |
| A real card that can be declined | Session G |
| A Supabase project you can break on purpose | Sessions C, H |
| A second APK with a different `versionName` | Session E |
| A deliberately corrupt APK | Session E |
| A phone with a QR scanner | Session D |

---

## Session A — wiped device

Destructive, so it goes first. Factory reset, then install.

- [x] `[auto]` **A1. First install on a wiped device produces no `update_installed`.** A fresh install is not an update, and reporting one would put a phantom upgrade in every new kiosk's history. *(3b.5)*
- [x] `[auto]` **A2 — settles debt. `installId` is minted exactly once.** Note the install ID on the analytics screen. Force-stop, reopen, restart the device, reopen. The ID must be identical every time, and must not be blank. This is the only way to cover `SettingsBootstrap`'s call site without an instrumented harness, and a regression there produces rows that identify nothing. *(known-debt: "`SettingsBootstrap`'s call site is untested")*

- [x] `[auto]` **A3. A real device row's columns match the schema published in README.md.** Added in phase 6: the published schema is asserted against actual device output rather than against a constructed object.
- [x] `[auto]` **A4. `testMode` pre-authenticates on a cold start.** Added in phase 6 after the device run found `MainActivity` reading `settings.testMode` twelve lines before settings were loaded, so a test kiosk opened SumUp's real login screen instead of coming up ready.

---

## Session B — analytics off

Provision as device owner. Configure normally but leave **Send analytics** off. Every check here asserts a negative, so record the queue depth before each one.

- [ ] **B1. A donation appends nothing.** *(3a.2)*
- [x] `[auto]` **B2. A forced crash leaves queue depth unchanged**, and restart behaviour is exactly as it is today. *(3b.3)*
- [ ] **B3. A Bluetooth outage and a network outage both append nothing.** *(3c-i.6)*
- [ ] **B4. A forced restart leaves queue depth unchanged and the marker store empty afterwards.** The marker store is what this check measures: with analytics off the queue never moves, so queue depth alone proves nothing. *(3c-ii.9)*

---

## Session C — backend, and the schema we just published

Stand the backend up from the README rather than from memory. If the published schema is wrong, this is where it shows.

- [ ] **C1. Paste the README's SQL block into a new Supabase project.** It must run clean with no edits. Three tables, RLS enabled, grants revoked then re-granted as INSERT only.
- [ ] **C2. A kiosk configured against it lands rows in all three tables.** Donation, diagnostic, and one activation row on the first successful **Test connection**.
- [ ] **C3 — proves the warning. Granting SELECT makes the key readable.** Temporarily `grant select on donation_events to anon`, then query the table with the publishable key. It should return rows. Revoke it again. This is the one check that demonstrates rather than asserts why the README calls no-SELECT the most consequential line in the schema. Do it on a throwaway project.
- [ ] **C4. A donation appends exactly one row.** Read the queue count before and after. *(3a.1)*
- [ ] **C5. Letting the screensaver come up drains a non-empty queue.** *(3a.3)*
- [ ] **C6. A donation in airplane mode queues, and drains when wifi returns**, with nobody touching the app. *(3a.4)*
- [ ] **C7. Queue count and `droppedCount` both survive a process restart.** Carried unverified since phase 2c and restated in every phase since. *(2c, 3a.5)*
- [ ] **C8. Repeated offline donations do not slow the thank-you screen** on a kiosk already holding thousands of rows. *(3a.6)*

---

## Session D — the disclosure screen

- [x] `[auto]` **D1. Saving a destination shows the disclosure**, stating the endpoint just entered, and it dismisses. *(4.1)*
- [x] `[auto]` **D2. Saving a destination again shows it again.** There is no stamp suppressing it, by design. *(4.2)*
- [x] `[auto]` **D3. Reopening from the analytics screen shows the same content.** *(4.3)*
- [x] `[auto]` **D4. Both QR codes scan, with a long URL, not a short one.** Each must resolve to exactly the URL printed beside it. Use a realistic privacy-policy URL of 150 characters or more: the original sizing defect passed on short URLs and failed on long ones, which is exactly how it hid. *(4.4)*
- [x] **D5. Arabic renders correctly.** Reviewed on an emulator screenshot and accepted 2026-09-16: nothing clipped, text right-aligned, layout left-to-right as ruled. Translations accepted as they stand. Both the RTL layout and the machine translations are now entries in `docs/known-debt.md`. Re-check only if the disclosure copy or layout changes. *(4.5)*
- [x] `[auto]` **D6. Saving with no privacy-policy URL shows no disclosure at all.** Then add a URL and save: it appears. *(phase 5)*
- [x] `[auto]` **D7. Clearing the endpoint stops reporting and deletes the queue**, and the disclosure's claim about that is true. *(4.6)*

---

## Session E — crash, update, rollback

- [x] `[auto]` **E1. A forced uncaught exception restarts the app exactly as it does today.** The handler changes nothing an operator can see. *(3b.1)*
- [x] `[auto]` **E2. That crash produces one more queued row, with a scrubbed trace.** Read the trace and confirm no affiliate key appears in it. *(3b.2)*
- [ ] **E3. Installing a build with a different `versionName` produces exactly one `update_installed`**, with correct `from` and `to`. Restarting again produces no second one. *(3b.4)*
- [ ] **E4. A deliberately corrupt APK produces one `update_install_failed` with a `reason`.** *(3c-i.5)*
- [ ] **E5. A forced watchdog rollback with a backup APK present produces both an `update_rollback` and an `update_installed`**, rollback first, joinable on the version pair. *(3b.6)*
- [ ] **E6. Fill the disk, then force the watchdog rollback. The rollback must still be attempted.** Flagged in the 3b spec as the most important check on its list, and it is a regression check for a specific finding from that phase's review. *(3b.7)*

---

## Session F — recovery diagnostics

Device-owner install required. Do not rotate the device during F1.

- [ ] **F1. Bluetooth off for five minutes produces exactly one `bluetooth_watchdog_fired`, not five.** The once-per-outage check. *(3c-i.1)*
- [ ] **F2. Bluetooth back on, then off again past the threshold, produces a second row** with its own `off_ms`. *(3c-i.2)*
- [ ] **F3. A network outage longer than `longDowntimeThresholdSec` produces one `network_outage`** whose `downtime_ms` matches the real outage. *(3c-i.3)*
- [ ] **F4. An outage shorter than the threshold produces no row.** *(3c-i.4)*

---

## Session G — the payment path

The highest-risk session. Five of these call sites sit beside the payment path and only their decisions are unit-tested.

- [ ] **G1. Three consecutive card-reader failures restart the kiosk**, and afterwards both a `card_reader_connect_failed` and a `restart_triggered` with `outcome: restarted` have arrived. The markers survived the process exit. *(3c-ii.1)*
- [ ] **G2. Two consecutive failures must not restart the kiosk.** The double-count check. "Three causes a restart" cannot detect a double increment; only this can. *(3c-ii.2)*
- [ ] **G3. Leaving the pairing page open for ten minutes produces a `card_reader_page_timeout`**, and a `card_reader_connect_failed` carrying `closed_by: pairing_timeout`. *(3c-ii.3)*
- [ ] **G4. Dismissing the screensaver repeatedly with no pairing page open, then failing a reader connection for real, produces a failure carrying no `closed_by`.** The regression check for the stale-arm bug. *(3c-ii.4)*
- [ ] **G5. Stalling a silent re-auth past the watchdog produces `sumup_reinit_failed` with `closed_by: login_watchdog`.** A real interactive login failure produces one without `closed_by`. *(3c-ii.5)*
- [ ] **G6. Exhausting `maxRestartsBeforeGiveUp` produces one `restart_triggered` with `outcome: gave_up`.** Keep failing: the causing diagnostic keeps arriving on every failure, but no further `restart_triggered` until a success clears the counters and the latch. *(3c-ii.6)*
- [ ] **G7. A checkout with the reader physically off produces one `checkout_no_reader`. Declining a card with the reader connected produces none.** *(3c-ii.7)*
- [ ] **G8. A normal donation with diagnostics queued completes normally.** *(3c-ii.8)*
- [ ] **G9 — settles debt. Capture the real SumUp failure text from G1, G5 and G7.** Read the `message` field in each `detail` object and check whether any of them carries a transaction code, an order code, or any other identifier. This is the only thing that can settle whether the disclosure screen's absolute claim holds, because the SDK is a pinned closed binary and nothing in the repository can answer it. *(known-debt: "the 'transaction identifiers never sent' claim is not provable from this repo")*

---

## Session H — queue and backoff at scale

Needs a backend you can break deliberately.

- [ ] **H1. Break one table by renaming a column, then take a donation.** The donation arrives, the broken table's rows stay queued, the screen shows a backoff. *(3d-i.1)*
- [ ] **H2. Let the broken table's rows exceed a batch and sit at the head, then take a donation. The donation still uploads.** The head-of-line check, and the reason phase 3d-i exists. *(3d-i.2)*
- [ ] **H3. Fix the backend and press Test connection: backoff clears for every table at once.** *(3d-i.3)*
- [ ] **H4. Upgrading a kiosk mid-backoff keeps it backing off** rather than resetting. *(3d-i.4)*
- [x] `[auto]` **H5. Fill the queue past the cap with old donations under newer diagnostics, then take a donation.** The donation is still queued afterwards and the diagnostics count has fallen. *(3d-ii.1)*
- [ ] **H6. Point analytics at a dead endpoint until the queue saturates: donation throughput does not degrade as the queue grows.** The O(n) fix, observable only at scale. *(3d-ii.2)*
- [ ] **H7. Leave a low-volume kiosk running past the compaction interval with a poison row at the head: the row is retired and the queue behind it drains.** *(3d-ii.3)*
- [ ] **H8. Crash the app with a saturated queue: the crash row is written and the process still dies promptly.** *(3d-ii.4)*

---

## Session I — the fleet workflow

This session exists to confirm the three provisioning bugs found in phase 5 before phase 6 fixes them, so the fix has a before and an after.

- [ ] **I1. Export settings from a configured kiosk, import onto a second one.** Expected today: the second kiosk arrives with the affiliate key but **no reporting destination**, and silently never reports. Confirm that is what happens. *(phase 5, finding 3)*
- [x] `[auto]` **I2. An imported kiosk code is flagged on screen, and the flag clears when edited.** The code still travels by design; what phase 6 added is a warning that it may be shared, which disappears the moment an operator types their own. *(was: confirm the fleet reports under one identity)*
- [x] `[auto]` **I3. A kiosk code is trimmed before it is stored and before it ships.** Phase 6 gave `KioskCode.normalize` its missing call site. *(was: confirm the untrimmed code reaches the wire)*

---

## Session P — provisioning

Tests the `tools/provision/provision.ps1` script, which configures a freshly reset tablet end to end. Needs a real fixture: set `KIOSK_PAYLOAD` to an export taken from a configured device and `KIOSK_PAYLOAD_PASSWORD` to its password. The fixture must come from the app's own exporter — a reimplementation of the app's own crypto in the test harness would be a second implementation that can drift, and a drift would make this check pass against a format the app no longer writes.

On a device with pre-existing stored settings, `onCreate` migrations rewrite `longDowntimeThresholdSec`, the four `autoUpdate` fields and `analyticsEnabled`, which makes the diff unexplainable. The harness verifies the package is really uninstalled and stops with a clear message if it is not. Remove the device owner or wipe the emulator first. This is an operator precondition, not a troubleshooting note.

- [x] `[auto]` **P1. A provisioned device exports the configuration it was given.** Round trip: provision from a known payload, export from the device, compare. A raw file comparison fails on a correct provisioning because salt, iv and ciphertext are regenerated on every export. The check compares decoded settings in both directions against a declared set of device-scoped fields.
- [ ] **P2. Wrong password reports `provisioning failed: wrong_password`.**
- [ ] **P3. Omitting `-KioskCode` is refused before anything is pushed.**
- [ ] **P4. A corrupt payload reports `provisioning failed: malformed`.**
- [ ] **P5. Running twice in a row with the same arguments succeeds both times.** The trigger is repeatable without `am force-stop` — Android refuses that for a device-owner package. The app relaunches itself with `NEW_TASK|CLEAR_TASK`, which forces a fresh `onCreate` in the same process.
- [ ] **P6. The PIN is set last, and re-provisioning needs the old one.** With `-Pin 1234` the run completes, and `adb shell cmd lock_settings get-disabled` afterwards fails with `Credential can't be null or empty` — which is what a set credential looks like from the shell. Re-running with `-Pin 1234` and no `-OldPin` must refuse with the "already has a lock credential" message rather than failing obscurely. Re-running with `-Pin 5678 -OldPin 1234` succeeds.
- [ ] **P7. Clean up the bench device.** `adb shell cmd lock_settings clear --old 5678`, then confirm `get-disabled` reads `true` again. **Leaving a test PIN on an emulator makes every later device check fail in ways that look unrelated.**

---

## After the run

Four outcomes decide phase 6's scope:

| Check | If it passes | If it fails |
|---|---|---|
| A2 | Close the `SettingsBootstrap` debt entry | Real identity bug, fix it in phase 6 |
| C1–C3 | The published schema is correct | The README is wrong and ships wrong; fix before merge |
| E6 | Close the disk-full rollback regression check | Serious: rollback is the kiosk's only autonomous recovery |
| G9 | If no identifier appears, the claim holds and the debt entry closes; the README and the disclosure screen can both state it plainly | If one appears, the redactor needs work and the disclosure screen is currently wrong in eight languages |

Record what each check actually produced, not just pass or fail. A row that arrived with the wrong `detail` shape is a pass on "did it arrive" and a failure on everything the field is for.
