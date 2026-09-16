#!/usr/bin/env python3
"""
Device checks for SadaqahKiosk, driven over adb.

Covers the deterministic subset of docs/hardware-test-checklist.md. Checks that
need a card reader, a real Bluetooth adapter, or a human eye are declared here
as SKIP with the reason, never silently omitted: a run that quietly drops the
nine card-reader checks and prints all-green is worse than no harness at all.

Usage:
    python tools/kiosk-check/run.py                 # every runnable check
    python tools/kiosk-check/run.py --session A     # one session
    python tools/kiosk-check/run.py --list          # what exists, and what is skipped
    python tools/kiosk-check/run.py --out results.md

Requires: adb on PATH or ANDROID_HOME set, one device/emulator attached, and
root (`adb root`) or a debuggable build.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import uuid as uuidlib
from dataclasses import dataclass, field

PKG = "com.sadaqah.kiosk"
PREFS = f"/data/data/{PKG}/shared_prefs"
FILES = f"/data/data/{PKG}/files"
OUTBOX = f"{FILES}/telemetry/outbox.jsonl"

# --------------------------------------------------------------------------
# Redaction.
#
# This device holds live payment credentials. Anything this harness prints or
# writes to a results file goes through here first. The token rule is the app's
# own (TelemetryRedactor.TOKEN_SHAPED) so a value the app would scrub from a
# crash report is also scrubbed from a test report.
# --------------------------------------------------------------------------

TOKEN_SHAPED = re.compile(r"[A-Za-z0-9+=_-]{32,}")
SECRET_KEYS = ("affiliate_key", "user.token", "user.password", "publishable", "apikey")


def redact(text):
    if text is None:
        return None
    return TOKEN_SHAPED.sub("[redacted]", str(text))


def is_secret_key(name):
    return any(s in name.lower() for s in SECRET_KEYS)


# --------------------------------------------------------------------------
# adb
# --------------------------------------------------------------------------


def adb_path():
    for candidate in (
        os.environ.get("ADB"),
        "adb",
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
        os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
    ):
        if not candidate:
            continue
        try:
            subprocess.run([candidate, "version"], capture_output=True, check=True)
            return candidate
        except (OSError, subprocess.CalledProcessError):
            continue
    sys.exit("adb not found. Set ADB or put it on PATH.")


ADB = adb_path()


def adb(*args, check=False, timeout=120):
    r = subprocess.run(
        [ADB, *args], capture_output=True, text=True, timeout=timeout, errors="replace"
    )
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {redact(r.stderr.strip())}")
    return r.stdout.strip()


def sh(cmd, check=False, timeout=120):
    return adb("shell", cmd, check=check, timeout=timeout)


def exists(path):
    return sh(f"test -e {path} && echo yes || echo no") == "yes"


def cat(path):
    return sh(f"cat {path} 2>/dev/null")


# --------------------------------------------------------------------------
# App state
# --------------------------------------------------------------------------


def force_stop():
    sh(f"am force-stop {PKG}")


def pid():
    return sh(f"pidof {PKG}").strip() or None


def launch(settle=6, timeout=45):
    """Start the app and confirm a process actually came up.

    Waiting a fixed interval and assuming success hides two failures: the app
    never started, and `monkey` merely foregrounded an already-running task so
    onCreate never re-ran. Both leave every later assertion reading stale state.
    """
    before = pid()
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    deadline = time.time() + timeout
    while time.time() < deadline:
        now = pid()
        if now and now != before:
            time.sleep(settle)
            return now
        time.sleep(0.5)
    if pid():
        # Already running and not restarted: fine when the caller only wanted it
        # in the foreground, wrong when they wanted onCreate to re-run.
        time.sleep(settle)
        return pid()
    raise RuntimeError(f"{PKG} did not start within {timeout}s")


def settings_json():
    """The app's Settings object, straight out of app_prefs.xml."""
    raw = cat(f"{PREFS}/app_prefs.xml")
    m = re.search(r'<string name="settings">(.*?)</string>', raw, re.S)
    if not m:
        return None
    decoded = (
        m.group(1)
        .replace("&quot;", '"')
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&apos;", "'")
    )
    return json.loads(decoded)


def push_app_file(local, remote):
    """Place a file inside app-private storage so the app can actually read it.

    `adb push` straight into /data/data writes the file with a root-derived
    SELinux label. chown fixes UNIX ownership, so `ls -la` looks correct, but the
    MLS category still belongs to whoever wrote it -- and after a reinstall the
    app gets a NEW category, so any file written before it is stale.

    The app then gets EACCES on its own SharedPreferences, and Android does not
    surface that: SharedPreferencesImpl logs a warning and hands out an EMPTY
    map, so the app runs on defaults. Every check built on a patched setting
    then silently tests the default instead, and passes for the wrong reason.

    Push via /data/local/tmp, copy in, fix owner from the parent directory
    (authoritative, recreated by the installer), then restorecon the label.
    """
    staged = f"/data/local/tmp/{os.path.basename(remote)}"
    adb("push", local, staged, check=True)
    parent = os.path.dirname(remote)
    sh(f"cp {staged} {remote}")
    sh(f"rm -f {staged}")
    owner = sh(f"stat -c '%u:%g' {parent}")
    sh(f"chown {owner} {remote}")
    sh(f"chmod 660 {remote}")
    sh(f"restorecon -F {remote}")

    want = sh(f"ls -Zd {parent}").split()[0]
    got = sh(f"ls -Z {remote}").split()[0]
    if want != got:
        raise RuntimeError(
            f"SELinux label on {remote} is {got}, parent dir is {want}; "
            "the app would silently fall back to default settings"
        )


def patch_settings(**changes):
    """Rewrite Settings with the app stopped, then leave it stopped.

    Writing prefs under a live process loses the change: Android holds the map
    in memory and rewrites the file on its own schedule.
    """
    force_stop()
    time.sleep(1)
    current = settings_json()
    if current is None:
        raise RuntimeError("no settings blob yet; launch the app once first")
    current.update(changes)
    blob = json.dumps(current, separators=(",", ":"), sort_keys=True)
    escaped = (
        blob.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
    )
    xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
        f'    <string name="settings">{escaped}</string>\n</map>\n'
    )
    local = os.path.join(os.environ.get("TEMP", "/tmp"), "app_prefs_patch.xml")
    with open(local, "w", encoding="utf-8") as f:
        f.write(xml)
    # Other keys in app_prefs.xml (affiliate_key, the failure counters) are
    # rewritten by the app itself; only `settings` is patched here, so the file
    # is merged rather than replaced.
    existing = cat(f"{PREFS}/app_prefs.xml")
    others = re.findall(r"^\s*<(?:int|long|boolean|float)\b[^>]*/>$", existing, re.M)
    others += [
        m.group(0)
        for m in re.finditer(r'^\s*<string name="(?!settings")[^"]+">.*?</string>$', existing, re.M | re.S)
    ]
    if others:
        xml = xml.replace("</map>", "\n".join(o.strip() for o in others) + "\n</map>")
        with open(local, "w", encoding="utf-8") as f:
            f.write(xml)
    push_app_file(local, f"{PREFS}/app_prefs.xml")

    # Verify the app itself honours the change, rather than trusting the write.
    # A patch the app cannot read produces defaults, not an error, so the only
    # trustworthy confirmation is reading it back after the app has loaded it.
    launch()
    readback = settings_json()
    if readback is None:
        raise RuntimeError("settings blob unreadable after patch")
    for key, value in changes.items():
        if readback.get(key) != value:
            raise RuntimeError(
                f"patch did not take: {key} is {readback.get(key)!r}, expected "
                f"{value!r}. The app is running on defaults and every check "
                "built on this setting would pass for the wrong reason."
            )


def outbox_rows():
    if not exists(OUTBOX):
        return []
    rows = []
    for line in cat(OUTBOX).splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            rows.append({"_unparseable": redact(line[:200])})
    return rows


def rows_of_kind(kind):
    out = []
    for r in outbox_rows():
        payload = r.get("payload")
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except json.JSONDecodeError:
                continue
        if isinstance(payload, dict) and payload.get("kind") == kind:
            out.append(payload)
        elif r.get("kind") == kind:
            out.append(r)
    return out


def clear_outbox():
    sh(f"rm -f {OUTBOX} {OUTBOX}.tmp")


def reinstall_clean(apk):
    """Full wipe. `pm uninstall` removes every file the app owns."""
    adb("uninstall", PKG)
    adb("install", "-r", "-g", apk, check=True, timeout=300)


# --------------------------------------------------------------------------
# Check registry
# --------------------------------------------------------------------------


@dataclass
class Result:
    cid: str
    title: str
    status: str  # PASS | FAIL | SKIP | ERROR
    detail: str = ""
    observed: list = field(default_factory=list)


CHECKS = []


def check(cid, session, title, skip=None):
    def deco(fn):
        CHECKS.append((cid, session, title, fn, skip))
        return fn

    return deco


class Failure(Exception):
    pass


def expect(condition, message):
    if not condition:
        raise Failure(message)


# --------------------------------------------------------------------------
# Session A — wiped device
# --------------------------------------------------------------------------


def seed_reported_version(version):
    """Make the app believe it last ran as `version`.

    DiagnosticEvents.updateInstalled compares this against BuildConfig.VERSION_NAME,
    so seeding it drives the real detection path without building a second APK.
    """
    force_stop()
    time.sleep(1)
    xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
        f'    <string name="update_reported_version">{version}</string>\n</map>\n'
    )
    local = os.path.join(os.environ.get("TEMP", "/tmp"), "update_state.xml")
    with open(local, "w", encoding="utf-8") as f:
        f.write(xml)
    push_app_file(local, f"{PREFS}/update_state.xml")


def app_version():
    out = sh(f"dumpsys package {PKG} | grep versionName | head -1")
    m = re.search(r"versionName=(\S+)", out)
    return m.group(1) if m else None


@check("A1", "A", "A fresh install reports no update_installed, and the outbox provably works")
def a1(ctx):
    reinstall_clean(ctx.apk)
    launch()
    patch_settings(analyticsEnabled=True)
    launch()

    # The claim.
    rows = rows_of_kind("update_installed")
    expect(
        not rows,
        f"a first install produced {len(rows)} update_installed row(s); a fresh "
        "install is not an upgrade, and reporting one puts a phantom upgrade in "
        "every new kiosk's history",
    )

    # The control, and it is not optional. The assertion above is an absence,
    # and an absence is indistinguishable from an outbox that never works. This
    # proves a row lands under exactly the settings the claim was made under.
    version = app_version()
    expect(version, "could not read versionName from dumpsys")
    seed_reported_version("1.3.6")
    launch()
    control = rows_of_kind("update_installed")
    expect(
        len(control) == 1,
        f"control failed: seeding a prior version produced {len(control)} rows, "
        "expected exactly 1. Without this the absence above proves nothing.",
    )
    detail = control[0].get("detail", {})
    expect(
        detail.get("from") == "1.3.6" and detail.get("to") == version,
        f"control row has wrong from/to: {detail!r}, expected 1.3.6 -> {version}",
    )
    ctx.sample_row = control[0]

    clear_outbox()
    seed_reported_version(version)
    return [
        "clean install wrote no update_installed row",
        f"control: seeding 1.3.6 produced exactly one row, from 1.3.6 to {version}",
        "so the absence above is a real result, not an empty outbox",
    ]


@check("A3", "A", "A real device row matches the schema published in README.md")
def a3(ctx):
    row = getattr(ctx, "sample_row", None)
    if row is None:
        raise Failure("no sample row captured; A1 must run first")

    with open("README.md", encoding="utf-8") as f:
        readme = f.read()
    block = None
    for m in re.finditer(r"```sql\s*\n(.*?)```", readme, re.S):
        if "create table" in m.group(1).lower():
            block = m.group(1)
            break
    expect(block, "no fenced sql block containing 'create table' found in README.md")

    m = re.search(
        r"create\s+table\s+diagnostic_events\s*\((.*?)\);", block, re.S | re.I
    )
    expect(m, "no diagnostic_events table in the README schema")
    declared = {
        line.strip().rstrip(",").split()[0]
        for line in m.group(1).splitlines()
        if line.strip() and not line.strip().startswith("--")
    }

    actual = set(row.keys())
    unknown = actual - declared
    expect(
        not unknown,
        f"the device sent columns the README does not declare: {sorted(unknown)}. "
        "A self-hoster pasting that schema gets an insert failure naming a column "
        "they never typed.",
    )
    # detail and stack_trace are omitted when empty, by design, so a missing
    # optional column is correct rather than drift.
    missing = declared - actual - {"detail", "stack_trace"}
    expect(
        not missing,
        f"the README declares columns the device never sends: {sorted(missing)}",
    )
    return [
        f"device row keys: {sorted(actual)}",
        f"README declares: {sorted(declared)}",
        "stack_trace absent rather than null, as the README states",
    ]


@check("A2", "A", "installId is minted exactly once and survives restarts (settles debt)")
def a2(ctx):
    s = settings_json()
    expect(s is not None, "no settings blob on device")
    first = s.get("installId", "")
    expect(first != "", "installId is blank after first launch; rows would identify nothing")
    try:
        uuidlib.UUID(first)
    except ValueError:
        raise Failure(f"installId is not a UUID: {redact(first)!r}")

    force_stop()
    launch()
    after_restart = settings_json().get("installId", "")
    expect(
        after_restart == first,
        f"installId changed across a force-stop: {first[:8]}... -> {after_restart[:8]}...",
    )

    adb("reboot")
    adb("wait-for-device", timeout=300)
    for _ in range(60):
        if sh("getprop sys.boot_completed") == "1":
            break
        time.sleep(3)
    time.sleep(5)
    adb("root")
    time.sleep(3)
    adb("wait-for-device", timeout=120)
    launch(settle=10)
    after_reboot = settings_json().get("installId", "")
    expect(
        after_reboot == first,
        f"installId changed across a reboot: {first[:8]}... -> {after_reboot[:8]}...",
    )
    return [
        f"installId {first[:8]}... stable across first launch, force-stop and reboot",
        "covers the SettingsBootstrap call site without an instrumented harness",
    ]


# --------------------------------------------------------------------------
# Checks that cannot run here, declared rather than omitted
# --------------------------------------------------------------------------

for _cid, _title, _why in [
    ("G1", "Three card-reader failures restart the kiosk", "needs a physical card reader"),
    ("G2", "Two failures must not restart", "needs a physical card reader"),
    ("G3", "Pairing page timeout", "needs a physical card reader"),
    ("G4", "Stale-arm regression: failure carries no closed_by", "needs a physical card reader"),
    ("G5", "Silent re-auth stalled past the watchdog", "needs a reader and a throttled endpoint"),
    ("G6", "maxRestartsBeforeGiveUp produces gave_up", "needs a physical card reader"),
    ("G7", "checkout_no_reader fires only with the reader off", "needs a reader and a declinable card"),
    ("G8", "A normal donation completes with diagnostics queued", "needs a physical card reader"),
    ("G9", "Real SumUp error text carries no transaction code (settles debt)", "needs real SumUp failures"),
    ("F1", "Bluetooth off 5 min produces exactly one row", "emulator has no real Bluetooth adapter"),
    ("F2", "A second Bluetooth outage produces a second row", "emulator has no real Bluetooth adapter"),
    ("H6", "Donation throughput does not degrade at scale", "x86_64 emulator timings are not the ARM tablet's"),
    ("D5", "Arabic renders correctly and nothing is clipped", "needs a human eye on the screenshot"),
]:
    CHECKS.append((_cid, _cid[0], _title, None, _why))


# --------------------------------------------------------------------------
# Runner
# --------------------------------------------------------------------------


class Ctx:
    def __init__(self, apk):
        self.apk = apk


def run(selected_sessions, apk, out_path):
    ctx = Ctx(apk)
    results = []
    for cid, session, title, fn, skip in sorted(CHECKS, key=lambda c: (c[1], c[0])):
        if selected_sessions and session not in selected_sessions:
            continue
        if skip:
            results.append(Result(cid, title, "SKIP", skip))
            print(f"  SKIP  {cid}  {title}\n        {skip}")
            continue
        print(f"  ....  {cid}  {title}", flush=True)
        try:
            observed = fn(ctx) or []
            results.append(Result(cid, title, "PASS", observed=observed))
            print(f"\r  PASS  {cid}  {title}")
            for line in observed:
                print(f"        {redact(line)}")
        except Failure as e:
            results.append(Result(cid, title, "FAIL", redact(str(e))))
            print(f"\r  FAIL  {cid}  {title}\n        {redact(str(e))}")
        except Exception as e:  # harness bug, not a product failure
            results.append(Result(cid, title, "ERROR", redact(f"{type(e).__name__}: {e}")))
            print(f"\r  ERROR {cid}  {title}\n        {redact(f'{type(e).__name__}: {e}')}")

    npass = sum(1 for r in results if r.status == "PASS")
    nfail = sum(1 for r in results if r.status == "FAIL")
    nerr = sum(1 for r in results if r.status == "ERROR")
    nskip = sum(1 for r in results if r.status == "SKIP")
    print(f"\n{npass} passed, {nfail} failed, {nerr} harness errors, {nskip} need hardware or a human")

    if out_path:
        with open(out_path, "w", encoding="utf-8") as f:
            f.write("# Device check run\n\n")
            f.write(f"{npass} passed, {nfail} failed, {nerr} harness errors, {nskip} skipped\n\n")
            for r in results:
                f.write(f"- **{r.cid}** {r.status} — {r.title}\n")
                if r.detail:
                    f.write(f"  - {redact(r.detail)}\n")
                for line in r.observed:
                    f.write(f"  - {redact(line)}\n")
        print(f"written to {out_path}")

    return 1 if (nfail or nerr) else 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--session", action="append", help="limit to session letter(s)")
    p.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk")
    p.add_argument("--out", help="write a markdown summary here")
    p.add_argument("--list", action="store_true")
    a = p.parse_args()

    if a.list:
        for cid, session, title, fn, skip in sorted(CHECKS, key=lambda c: (c[1], c[0])):
            print(f"{cid:5} [{session}] {title}" + (f"   SKIP: {skip}" if skip else ""))
        return 0

    devices = [l for l in adb("devices").splitlines()[1:] if l.strip().endswith("device")]
    if not devices:
        sys.exit("no device attached")
    print(f"device: {devices[0].split()[0]}   root: {sh('whoami')}\n")
    return run(set(s.upper() for s in (a.session or [])), a.apk, a.out)


if __name__ == "__main__":
    sys.exit(main())
