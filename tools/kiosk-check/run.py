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
    # UTF-8 explicitly. `text=True` decodes with the system locale, which on a
    # Windows host is cp1252 -- every non-ASCII string the device returns then
    # arrives as mojibake, and comparisons against it fail for reasons that have
    # nothing to do with the device. The Arabic checks depend on this.
    r = subprocess.run(
        [ADB, *args],
        capture_output=True,
        timeout=timeout,
        encoding="utf-8",
        errors="replace",
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
    """Uninstall, verify the package is actually gone, then install fresh.

    `pm uninstall` refuses when this package is device owner, and does so
    without this harness noticing unless it looks: the app (and its data) is
    still there afterwards. Installing on top of that produces a live app
    with its old settings intact while every caller downstream believes it
    got a clean device. Removing device-owner status automatically is not
    this harness's call, so it refuses instead of guessing.
    """
    adb("uninstall", PKG)
    if sh(f"pm path {PKG}"):
        raise Failure(
            f"{PKG} is still installed after `adb uninstall` -- likely still "
            "device owner on this unit, which blocks uninstall silently. "
            "Remove it as device owner or wipe the emulator, then re-run."
        )
    adb("install", "-r", "-g", apk, check=True, timeout=300)


# --------------------------------------------------------------------------
# UI driving
#
# Compose exposes its semantics tree to accessibility, so uiautomator sees real
# labels rather than opaque boxes. Nodes are matched on visible text, which
# means these helpers read like the screen does.
# --------------------------------------------------------------------------

NODE_RE = re.compile(r"<node\b([^>]*)/?>")
ATTR_RE = re.compile(r'(\S+?)="([^"]*)"')
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def ui_nodes():
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    xml = cat("/sdcard/ui.xml")
    nodes = []
    for m in NODE_RE.finditer(xml):
        attrs = dict(ATTR_RE.findall(m.group(1)))
        b = BOUNDS_RE.search(attrs.get("bounds", ""))
        if not b:
            continue
        x1, y1, x2, y2 = (int(g) for g in b.groups())
        nodes.append(
            {
                "text": attrs.get("text", ""),
                "desc": attrs.get("content-desc", ""),
                "cls": attrs.get("class", ""),
                "clickable": attrs.get("clickable") == "true",
                "center": ((x1 + x2) // 2, (y1 + y2) // 2),
                "bounds": (x1, y1, x2, y2),
            }
        )
    return nodes


def field_below(label, nodes=None):
    """The editable field belonging to a label.

    Coordinates must be resolved at the moment of use, never cached: this screen
    relaunches itself as state changes -- saving a destination masks the key,
    which reflows everything under it -- so a position read one action ago
    silently addresses the wrong field.
    """
    nodes = nodes or ui_nodes()
    anchor = None
    for n in nodes:
        if label.lower() in (n["text"] or n["desc"]).lower():
            anchor = n
            break
    if anchor is None:
        raise Failure(f"no label matching {label!r}; visible: {[n['text'] for n in nodes if n['text']][:30]}")
    # Compose draws a TextField's label inside the field's own box and emits it
    # as a sibling AFTER the EditText, so "the field below the label" finds the
    # wrong one. Nearest by vertical centre is what actually matches.
    fields = [n for n in nodes if "EditText" in n["cls"]]
    if not fields:
        raise Failure(f"no EditText on screen while looking for {label!r}")
    ay = anchor["center"][1]
    best = min(fields, key=lambda n: abs(n["center"][1] - ay))
    if abs(best["center"][1] - ay) > 120:
        raise Failure(f"no EditText near {label!r} (nearest is {abs(best['center'][1]-ay)}px away)")
    return best


def dismiss_keyboard():
    """Close the IME without navigating.

    KEYCODE_BACK would dismiss it too, but falls through to a screen
    transition when no keyboard is up, which silently leaves the flow.
    """
    if "mInputShown=true" in sh("dumpsys input_method | grep mInputShown"):
        sh("input keyevent 111")  # ESCAPE: closes the IME, never navigates
        time.sleep(1)


def scroll_to(label, tries=8):
    """Bring `label` and its field on-screen, scrolling either way."""
    for direction in ("down", "up"):
        for _ in range(tries):
            nodes = ui_nodes()
            try:
                field_below(label, nodes)
                return nodes
            except Failure:
                pass
            if direction == "down":
                sh("input swipe 400 1000 400 500 300")
            else:
                sh("input swipe 400 500 400 1000 300")
            time.sleep(1)
    raise Failure(f"could not bring {label!r} and its field on-screen")


def set_field(label, value):
    """Type into the field under `label`, and confirm it actually took."""
    dismiss_keyboard()
    scroll_to(label)
    f = field_below(label)
    tap_xy(*f["center"])
    # Clear, then confirm it is actually empty. A partial clear leaves the old
    # value in front of the new one, and the check that follows then fails on a
    # string it never typed.
    for attempt in range(4):
        sh("input keyevent KEYCODE_MOVE_END")
        for _ in range(60):
            sh("input keyevent KEYCODE_DEL")
        time.sleep(0.5)
        if not field_below(label)["text"]:
            break
        tap_xy(*field_below(label)["center"])
    else:
        raise Failure(f"could not clear {label!r}; it still reads {field_below(label)['text']!r}")
    if not value:
        return ""
    sh("input text " + value.replace(" ", "%s"))
    time.sleep(1.5)
    after = field_below(label)
    got = after["text"]
    # A masked field (the publishable key) never reads back what was typed, so
    # "all bullets and the right length" is the strongest confirmation available.
    masked = got and all(ch in "•*•●" for ch in got)
    if masked:
        if len(got) < 8:
            raise Failure(f"{label!r} masked but only {len(got)} chars; typing likely failed")
        return got
    if value[:20] not in got:
        raise Failure(
            f"typing into {label!r} did not take: field reads {got!r}, expected "
            f"something starting {value[:20]!r}"
        )
    return got


def labels():
    return [n["text"] or n["desc"] for n in ui_nodes() if n["text"] or n["desc"]]


def find(text, exact=False):
    t = text.lower()
    for n in ui_nodes():
        for field in (n["text"], n["desc"]):
            if not field:
                continue
            if (field.lower() == t) if exact else (t in field.lower()):
                return n
    return None


def tap_xy(x, y):
    sh(f"input tap {x} {y}")
    time.sleep(1)


def tap(text, exact=False, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        n = find(text, exact)
        if n:
            tap_xy(*n["center"])
            return n
        time.sleep(1)
    raise Failure(f"no on-screen element matching {text!r}; visible: {labels()}")


def type_into(text_of_field, value):
    tap(text_of_field)
    sh("input keyevent KEYCODE_MOVE_END")
    for _ in range(80):
        sh("input keyevent KEYCODE_DEL")
    sh("input text " + value.replace(" ", "%s").replace("&", "\\&"))
    time.sleep(1)


def wait_for_text(text, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find(text):
            return True
        time.sleep(1)
    return False


def foreground_package():
    # One pattern, no alternation: `\|` gets mangled passing through the host
    # shell and adb, and the grep then silently matches nothing -- which reads
    # as "no app is in the foreground" rather than as a broken command.
    out = sh("dumpsys window | grep mCurrentFocus")
    m = re.search(r"([a-zA-Z][\w.]+)/[\w.$]+}", out)
    return m.group(1) if m else None


def ensure_foreground():
    """Never drive the UI unless our own app is on top.

    Blind coordinate taps against whatever happens to be showing walk out into
    the launcher and the system Settings app, and every later check then fails
    describing someone else's screen.
    """
    if foreground_package() == PKG:
        return
    launch()
    if foreground_package() != PKG:
        raise Failure(
            f"{PKG} is not in the foreground (top is {foreground_package()!r}); "
            "refusing to tap blind"
        )


def goto_analytics():
    ensure_foreground()
    """Navigate from wherever the app is to the analytics settings screen.

    The login step is unconditional because MainActivity.kt:315 reads
    settings.testMode twelve lines before settings are loaded at :327, so the
    testMode pre-authentication never fires on a cold start and the kiosk always
    lands on the login screen. Pressing LOG IN reaches authenticate(), where
    settings ARE loaded and the testMode branch works.
    """
    if find("What this kiosk reports") and find("Close"):
        tap("Close")
        time.sleep(2)
    if find("Analytics &") and find("Save destination"):
        return  # already there
    # The app auto-detects UI language from the device locale, so the settings
    # entry point is not reliably called "SETTINGS". Pin English rather than
    # matching eight translations of every label.
    s = settings_json()
    if s and s.get("language") != "en":
        patch_settings(language="en")
    if find("LOG IN"):
        tap("LOG IN")
        time.sleep(3)
    if not find("BRANDING"):
        entry = find("SETTINGS") or find("Instellingen")
        if entry:
            tap_xy(*entry["center"])
        else:
            raise Failure(f'no settings entry point on screen; visible: {labels()[:15]}')
        time.sleep(3)
    for _ in range(10):
        if find("Analytics &"):
            tap("Analytics &")
            time.sleep(2)
            return
        sh("input swipe 400 1000 400 300 300")
        time.sleep(1)
    raise Failure(f"could not reach analytics settings; visible: {labels()[:20]}")


def screencap(local_path):
    remote = "/sdcard/shot.png"
    sh(f"screencap -p {remote}")
    adb("pull", remote, local_path, check=True)
    sh(f"rm -f {remote}")
    return local_path


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


class Skip(Exception):
    """Raise from inside a check body when it cannot run at all -- a missing
    optional fixture or credential, not a wrong one. A check that never ran
    is not a check that failed, and reporting it red trains people to stop
    reading a red suite; this keeps that distinction alive at runtime the
    same way the registration-time `skip=` argument already does statically.
    """


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


@check("A4", "A", "testMode pre-authenticates on a cold start instead of opening SumUp's login")
def a4(ctx):
    # MainActivity.onCreate reads settings.testMode before it loads settings from
    # disk, so the flag is always its default on a cold start: the branch is dead,
    # the else path runs, and authenticate() is called for real. On a kiosk with a
    # stored affiliate key that means the SumUp SDK's own email/password activity
    # opens on top of the app every time it starts.
    patch_settings(testMode=True)
    sh(f"am force-stop {PKG}")
    time.sleep(1)
    launch(settle=10)

    labels_now = labels()
    sumup_login = any(
        "Email address" in l or "Forgot password" in l for l in labels_now
    )
    expect(
        not sumup_login,
        "a cold start with testMode on opened SumUp's real login screen; "
        "settings.testMode is read before settings are loaded, so the bypass "
        "never fires",
    )
    expect(
        not find("LOG IN"),
        "a cold start with testMode on landed on the affiliate login screen; "
        "a test kiosk should come up ready rather than waiting for a human",
    )
    return ["testMode kiosk came up authenticated, with no SumUp login activity"]


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
# Session D — the disclosure screen
# --------------------------------------------------------------------------

PRIVACY_URL = "https://nouralhouda.nl/privacy-policy-for-the-kiosk-fleet"
TERMS_URL = "https://nouralhouda.nl/terms"


def save_destination(url, key):
    set_field("Project URL", url)
    set_field("Publishable key", key)
    dismiss_keyboard()
    tap("Save destination")
    time.sleep(2)


def exit_analytics():
    dismiss_keyboard()
    tap("Back")
    time.sleep(3)


def clear_credentials():
    """Clear via the confirmation dialog.

    The red button only opens an AlertDialog; tapping the same spot again lands
    outside that dialog and dismisses it, which looks exactly like a clear that
    silently did nothing.
    """
    scroll_to("Terms URL")
    btn = None
    for n in ui_nodes():
        if (n["text"] or n["desc"]) == "Clear credentials":
            btn = n
    if not btn:
        raise Failure("no Clear credentials button on screen")
    tap_xy(*btn["center"])
    time.sleep(2)
    confirm = [n for n in ui_nodes() if (n["text"] or n["desc"]) == "Clear credentials"]
    if len(confirm) < 2:
        raise Failure(f"confirmation dialog did not open; visible: {labels()}")
    tap_xy(*confirm[-1]["center"])
    time.sleep(3)


def disclosure_showing():
    return bool(find("What this kiosk reports")) and bool(find("Close"))


def close_disclosure():
    if disclosure_showing():
        tap("Close")
        time.sleep(2)


def ensure_configured(url="https://configured00000.supabase.co", key="sb_publishable_CONFIGUREDKEY001"):
    """Leave the kiosk with policy URLs and a saved destination.

    Every D check calls this instead of inheriting whatever the previous one
    left behind. D6 deliberately blanks the policy URLs, so a suite that shares
    state has checks passing or failing on their neighbour's leftovers rather
    than on the behaviour they name.
    """
    close_disclosure()
    goto_analytics()
    if not find(PRIVACY_URL):
        set_field("Privacy policy URL", PRIVACY_URL)
    if not find(TERMS_URL):
        set_field("Terms URL", TERMS_URL)
    if not find("supabase"):
        save_destination(url, key)
    dismiss_keyboard()


@check("D1", "D", "Saving a destination shows the disclosure on exit, naming that endpoint")
def d1(ctx):
    goto_analytics()
    if find("supabase"):
        clear_credentials()
    set_field("Privacy policy URL", PRIVACY_URL)
    set_field("Terms URL", TERMS_URL)
    save_destination("https://checkone11111.supabase.co", "sb_publishable_CHECKONEKEY000001")
    exit_analytics()
    expect(disclosure_showing(), f"no disclosure after exiting a save; visible: {labels()[:10]}")
    expect(
        find("https://checkone11111.supabase.co"),
        "the disclosure does not name the endpoint just saved",
    )
    ctx.disclosure_labels = labels()
    return ["disclosure appeared on exit and named the endpoint just entered"]


@check("D2", "D", "Saving a second destination shows the disclosure again, with the new endpoint")
def d2(ctx):
    ensure_configured()
    save_destination("https://checktwo22222.supabase.co", "sb_publishable_CHECKTWOKEY000002")
    exit_analytics()
    expect(disclosure_showing(), "no disclosure after a second save; there is no stamp suppressing it")
    expect(
        find("https://checktwo22222.supabase.co"),
        "the disclosure still names the previous endpoint, not the one just saved",
    )
    return [
        "second save showed the disclosure again, naming the new endpoint",
        "note: re-saving requires re-entering the key, which is never redisplayed",
    ]


@check("D3", "D", "The disclosure is reachable again from the analytics screen")
def d3(ctx):
    ensure_configured()
    scroll_to("Terms URL")
    tap("What this kiosk reports")
    time.sleep(3)
    expect(disclosure_showing(), "the reopen row did not show the disclosure")
    for needed in ("These reports identify this kiosk", "What is never sent", "Where it goes"):
        expect(find(needed), f"disclosure is missing {needed!r}")
    return ["reopened from settings with the same content"]


@check("D4", "D", "Both QR codes decode to exactly the URLs shown beside them")
def d4(ctx):
    try:
        import cv2
    except ImportError:
        raise Failure("opencv not installed; pip install opencv-python-headless")

    if not disclosure_showing():
        ensure_configured()
        scroll_to("Terms URL")
        tap("What this kiosk reports")
        time.sleep(3)

    shot = os.path.join(os.environ.get("TEMP", "/tmp"), "disclosure_qr.png")
    screencap(shot)
    img = cv2.imread(shot)
    expect(img is not None, "screencap produced no readable image")

    det = cv2.QRCodeDetector()
    found = {}
    # Decoded per-region: detectAndDecodeMulti reliably returns only one of the
    # two on this layout, which reads as a failing QR when both are in fact fine.
    h = img.shape[0]
    for y0 in range(0, h - 200, 100):
        crop = img[y0 : y0 + 320, :]
        try:
            s, pts, _ = det.detectAndDecode(crop)
        except cv2.error:
            continue
        if s:
            found[s] = True

    for expected in (PRIVACY_URL, TERMS_URL):
        expect(
            expected in found,
            f"no QR on screen decoded to {expected!r}; decoded: {sorted(found)}. "
            "A QR that does not scan has failed at its only job, and a long URL "
            "is where undersized modules show up first.",
        )
        expect(find(expected), f"{expected!r} is encoded but not printed as text beside the code")
    return [f"decoded {len(found)} codes, both matching the text shown: {sorted(found)}"]


@check("D6", "D", "With no privacy policy URL, no disclosure is shown at all")
def d6(ctx):
    close_disclosure()
    goto_analytics()
    clear_credentials()
    set_field("Privacy policy URL", "")
    set_field("Terms URL", "")
    save_destination("https://nopolicy33333.supabase.co", "sb_publishable_NOPOLICYKEY00003")
    expect(
        find("No policy links set"),
        "expected the missing-policy warning once both URLs are blank",
    )
    exit_analytics()
    expect(
        not disclosure_showing(),
        "a disclosure was shown with no privacy policy URL configured; there "
        "would be nothing honest to point at",
    )
    return ["destination saved with no policy URL, and no disclosure was shown"]


@check("D7", "D", "Clearing the destination deletes the queue, as the disclosure claims")
def d7(ctx):
    close_disclosure()
    # Put something in the queue so the deletion is observable.
    version = app_version()
    seed_reported_version("1.3.0")
    launch()
    expect(outbox_rows(), "could not queue a row to prove deletion against")
    before = len(outbox_rows())

    goto_analytics()
    clear_credentials()

    expect(not exists(OUTBOX), f"outbox file still present after clearing credentials")
    expect(not outbox_rows(), "rows survived a credential clear")
    expect(find("Enter a destination first"), "destination does not read as cleared")
    seed_reported_version(version)
    return [
        f"{before} queued row(s) before, 0 after, and the outbox file is gone",
        "the disclosure's 'deletes anything still queued' claim holds on device",
    ]


# --------------------------------------------------------------------------
# Sessions B, E, H
#
# `am crash` delivers a genuine uncaught exception to the main thread, so it
# reaches KioskCrashHandler exactly as a real crash would — verified by the
# resulting row carrying a real stack trace rather than a synthesised one.
# --------------------------------------------------------------------------


def force_crash():
    """Crash the app and wait for the process to actually turn over.

    Waiting for `pidof` to go empty is the wrong condition: this is a kiosk, and
    it brings itself back after a crash, so the pid can be non-empty again within
    a second. What matters is that the ORIGINAL process is gone.
    """
    before = pid()
    expect(before, "cannot crash the app: it is not running")
    # `am crash` is delivered as a message to the main looper, and an app still
    # working through startup can swallow it. Resend rather than conclude the
    # handler failed.
    # Sent exactly once. An earlier version retried when the pid had not visibly
    # turned over inside the timeout, which produced TWO crash rows: the crash
    # had landed and been recorded, only the process-turnover detection was
    # slow. Callers assert on what the crash produced, which is the real
    # question, so a missed turnover here is not worth a second crash.
    sh(f"am crash {PKG}")
    for _ in range(60):
        now = pid()
        if now is None or now != before:
            break
        time.sleep(0.5)
    time.sleep(3)  # let the handler finish its write before anyone reads it


def inject_rows(n, table="diagnostic_events", kind="network_outage", age_days=0):
    """Append synthetic queued rows straight into the outbox file.

    Saturating the queue through the UI would mean thousands of real events.
    The envelope shape is TelemetryOutbox's own: one JSON object per line
    wrapping the already-serialised payload.
    """
    now = int(time.time() * 1000) - age_days * 86_400_000
    lines = []
    for i in range(n):
        rid = f"{uuidlib.uuid4()}"
        payload = json.dumps(
            {
                "id": rid,
                "code": "",
                "install_id": "synthetic",
                "app_version": "1.4.0-preview",
                "occurred_at": "2026-09-16T00:00:00Z",
                "kind": kind,
                "severity": "warn",
            },
            separators=(",", ":"),
        )
        lines.append(
            json.dumps(
                {"id": rid, "table": table, "queuedAt": now, "payload": payload},
                separators=(",", ":"),
            )
        )
    local = os.path.join(os.environ.get("TEMP", "/tmp"), "inject.jsonl")
    with open(local, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    staged = "/data/local/tmp/inject.jsonl"
    adb("push", local, staged, check=True)
    sh(f"mkdir -p {FILES}/telemetry")
    sh(f"cat {staged} >> {OUTBOX}")
    sh(f"rm -f {staged}")
    owner = sh(f"stat -c '%u:%g' {FILES}")
    sh(f"chown {owner} {OUTBOX}; chmod 660 {OUTBOX}; restorecon -F {OUTBOX}")


@check("B2", "B", "A crash with analytics off writes nothing to the queue")
def b2(ctx):
    patch_settings(analyticsEnabled=False, testMode=True)
    launch()
    clear_outbox()
    force_crash()
    launch()
    rows = outbox_rows()
    expect(
        not rows,
        f"a crash with analytics off queued {len(rows)} row(s); the master switch "
        "is supposed to stop collection at the source",
    )
    return ["crash with analytics off queued nothing"]


@check("E1", "E", "A forced crash queues exactly one crash row and the process dies promptly")
def e1(ctx):
    patch_settings(analyticsEnabled=True, testMode=True)
    launch()
    clear_outbox()
    force_crash()
    launch()

    rows = rows_of_kind("crash")
    expect(len(rows) == 1, f"expected exactly one crash row, got {len(rows)}")
    row = rows[0]
    expect(row.get("severity") == "error", f"crash severity is {row.get('severity')!r}")
    expect(row.get("stack_trace"), "the crash row carries no stack trace")
    expect(
        (row.get("detail") or {}).get("thread"),
        f"the crash row names no thread: detail={row.get('detail')!r}",
    )
    ctx.crash_row = row
    return [
        f"one crash row, severity error, thread={row['detail'].get('thread')!r}",
        "the original process turned over promptly, so the handler does not hang "
        "the dying thread",
        "the kiosk brought itself back on its own after the crash",
    ]


@check("E2", "E", "The crash trace is bounded and carries no affiliate key")
def e2(ctx):
    marker = "sup_afk_DEVICECHECKMARKER0123456789ABCD"
    force_stop()
    time.sleep(1)
    # Put a known key where the app reads it, so "the key is absent" is a claim
    # about redaction rather than about a key that was never there to leak.
    raw = cat(f"{PREFS}/app_prefs.xml")
    if "affiliate_key" in raw:
        patched = re.sub(
            r'<string name="affiliate_key">.*?</string>',
            f'<string name="affiliate_key">{marker}</string>',
            raw,
            flags=re.S,
        )
    else:
        patched = raw.replace("</map>", f'    <string name="affiliate_key">{marker}</string>\n</map>')
    local = os.path.join(os.environ.get("TEMP", "/tmp"), "prefs_marker.xml")
    with open(local, "w", encoding="utf-8") as f:
        f.write(patched)
    push_app_file(local, f"{PREFS}/app_prefs.xml")

    launch()
    clear_outbox()
    force_crash()
    launch()

    whole_file = cat(OUTBOX)
    expect(
        marker not in whole_file,
        "the affiliate key appears verbatim in the outbox after a crash; the "
        "queue file survives a crash and can be pulled off a device, which is "
        "why scrubbing happens before the write rather than before the send",
    )
    rows = rows_of_kind("crash")
    expect(rows, "no crash row to inspect")
    trace = rows[0].get("stack_trace", "")
    size = len(trace.encode("utf-8"))
    expect(size <= 8 * 1024 + 64, f"stack trace is {size} bytes, above the 8 KB cap")
    return [
        f"trace {size} bytes, within the 8 KB cap",
        "the affiliate key does not appear anywhere in the queue file",
        "note: this proves the key is absent, not that a key embedded mid-trace "
        "would be caught — the redactor's exact-match rule covers that and is "
        "unit-tested",
    ]


@check("H5", "H", "A saturated queue sheds diagnostics before donations")
def h5(ctx):
    patch_settings(analyticsEnabled=True, testMode=True)
    launch()
    clear_outbox()

    # One donation row under a flood of newer diagnostics. The outbox protects
    # donations, so compaction must shed the diagnostics and keep this.
    inject_rows(1, table="donation_events", kind="donation", age_days=1)
    inject_rows(6000, table="diagnostic_events", kind="network_outage")
    before = len(outbox_rows())
    expect(before > 5000, f"only injected {before} rows; the cap is 5000")

    # Appending through the app is what triggers compaction.
    version = app_version()
    seed_reported_version("1.3.0")
    launch(settle=12)
    time.sleep(5)
    after = outbox_rows()
    seed_reported_version(version)

    expect(
        len(after) < before,
        f"the queue did not shed: {before} rows before, {len(after)} after",
    )
    donations = [r for r in after if r.get("table") == "donation_events"]
    expect(
        donations,
        f"compaction dropped the donation row while keeping "
        f"{len(after)} rows; donations are supposed to be evicted last",
    )
    return [
        f"{before} rows shed to {len(after)}",
        f"{len(donations)} donation row(s) survived the compaction",
    ]


# --------------------------------------------------------------------------
# Session I — the fleet workflow
#
# These exist to pin the provisioning bugs found in phase 5 BEFORE phase 6
# fixes them, so the fix has a before and an after.
# --------------------------------------------------------------------------


@check("I2", "I", "An imported kiosk code is flagged, and the flag clears when edited")
def i2(ctx):
    # The flag is set by SettingsImport.merge, which is covered by JVM tests. What
    # only a device can show is that the warning actually reaches the screen, and
    # that editing the field clears it -- the property that stops the warning
    # outliving the condition it describes.
    patch_settings(
        kioskCode="nl-gld-arnhem-nour_al_houda-01",
        kioskCodeFromImport=True,
        analyticsEnabled=True,
    )
    goto_analytics()
    scroll_to("Kiosk code")
    warning = find("came from an imported file")
    expect(
        warning,
        "no imported-code warning shown for a code that arrived from a file; "
        f"visible: {labels()[:25]}",
    )

    # Typing here is the operator claiming the code for this kiosk.
    set_field("Kiosk code", "nl-gld-arnhem-nour_al_houda-07")
    dismiss_keyboard()
    time.sleep(2)
    expect(
        not (settings_json() or {}).get("kioskCodeFromImport", True),
        "editing the kiosk code did not clear the imported flag, so the warning "
        "would outlive the condition it describes",
    )
    expect(
        not find("came from an imported file"),
        "the warning is still on screen after the operator typed their own code",
    )
    return [
        "imported code warned about on screen",
        "editing the field cleared both the flag and the warning",
    ]


@check("I3", "I", "A kiosk code is trimmed before it is stored, and before it ships")
def i3(ctx):
    # Start from a known-empty field via prefs rather than by deleting through
    # the IME: clearing a whitespace-only field with KEYCODE_DEL is unreliable,
    # and a partial clear would leave the check asserting against a value it
    # never typed. The typing itself still goes through the real UI, which is
    # what exercises the persist site.
    patch_settings(kioskCode="", analyticsEnabled=True)
    goto_analytics()
    dirty = "nl-gld-arnhem-test-01 "
    set_field("Kiosk code", dirty)
    dismiss_keyboard()
    time.sleep(2)

    stored = (settings_json() or {}).get("kioskCode", "")
    expect(stored.strip(), f"kiosk code did not persist at all (read {stored!r})")
    expect(
        stored == dirty.strip(),
        f"kiosk code was stored as {stored!r}; KioskCode.normalize should have "
        f"trimmed it to {dirty.strip()!r} at the persist site",
    )

    # The stored value is only half of it. What matters is that the untrimmed
    # code reaches the wire, because that is what splits one kiosk into two
    # groups for whoever queries the table.
    patch_settings(analyticsEnabled=True)
    clear_outbox()
    version = app_version()
    seed_reported_version("1.3.0")
    launch()
    rows = outbox_rows()
    expect(rows, "no row produced to inspect the code field on")
    payload = json.loads(rows[0]["payload"]) if isinstance(rows[0].get("payload"), str) else rows[0]
    shipped = payload.get("code", "")
    seed_reported_version(version)

    expect(
        shipped == shipped.strip(),
        f"the wire carries code={shipped!r}, untrimmed. KioskCode.normalize exists "
        "for exactly this and has no call site: MainActivity.kt:689 persists the "
        "raw field. A self-hoster grouping by code gets two groups for one kiosk, "
        "with nothing on screen saying so.",
    )
    return ["kiosk code is trimmed before it reaches the wire"]


# --------------------------------------------------------------------------
# Session P — the provisioning round trip
# --------------------------------------------------------------------------

@dataclass
class DeviceScopedField:
    """One row of PROVISION_DEVICE_SCOPED.

    `assert_ok` and `reason` both take (provisioned, source, code, name) so a
    field like kioskCode can check itself against the CLI override instead of
    a fixed constant; fields that don't need the override just ignore it.
    `load_bearing` takes only `source` -- whether an assertion could ever have
    caught a broken guard depends entirely on what the fixture itself already
    carries for that field, never on what the device sends back.
    """

    field: str
    assert_ok: object
    reason: object
    load_bearing: object


# Fields SettingsImport.merge takes from the device rather than the payload,
# paired with the assertion that proves the guard actually ran. This replaces
# a bare set of names to skip in the forward comparison: that shape let a
# field be excluded from one direction and never asserted in the other, which
# is exactly how five of these nine went unchecked. A field added here without
# an assertion is a TypeError, not a silent gap.
PROVISION_DEVICE_SCOPED = [
    DeviceScopedField(
        "installId",
        lambda p, s, c, n: bool(p.get("installId")) and p.get("installId") != s.get("installId"),
        lambda p, s, c, n: (
            "the device has no installId after provisioning; every unit would "
            "boot without a device identity"
        ) if not p.get("installId") else (
            "the device kept the payload's installId; SettingsImport.merge is "
            "no longer guarding it and a cloned fleet would share one identity"
        ),
        # True regardless of the fixture: the non-blank half of the assertion
        # catches a device that finished provisioning with no identity at all,
        # and no fixture can make that check vacuous.
        lambda s: True,
    ),
    DeviceScopedField(
        "testMode",
        lambda p, s, c, n: p.get("testMode") is False,
        lambda p, s, c, n: (
            "testMode was left set after provisioning; a kiosk cloned from a "
            "bench export would boot with the biometric gate bypassed on a "
            "machine standing in public"
        ),
        lambda s: bool(s.get("testMode")),
    ),
    DeviceScopedField(
        "skipApkSignatureCheckOnce",
        lambda p, s, c, n: p.get("skipApkSignatureCheckOnce") is False,
        lambda p, s, c, n: (
            "skipApkSignatureCheckOnce was left set after provisioning; a fleet "
            "cloned from this export would run with the signature check disabled"
        ),
        lambda s: bool(s.get("skipApkSignatureCheckOnce")),
    ),
    DeviceScopedField(
        "logoUri",
        lambda p, s, c, n: not p.get("logoUri"),
        lambda p, s, c, n: (
            "a logoUri survived provisioning; a per-device local file path from "
            "another kiosk points at nothing on this one"
        ),
        # Always False, never conditioned on the fixture: applyProvisioning
        # re-stamps logoUri unconditionally after merge --
        # `settings = settings.copy(logoUri = copyProvisionedLogo()?.uri)` at
        # MainActivity.kt:2129 -- and P1 never passes -Logo, so this comes back
        # null regardless of what SettingsImport.merge did to it. Deleting the
        # `logoUri = null` guard from merge would not change this assertion's
        # outcome under any fixture P1 can construct, so it cannot be
        # load-bearing here. The guard itself is proven by
        # SettingsFieldClassificationTest instead.
        lambda s: False,
    ),
    DeviceScopedField(
        "analyticsActivatedAtMs",
        lambda p, s, c, n: not p.get("analyticsActivatedAtMs"),
        lambda p, s, c, n: (
            "analyticsActivatedAtMs carried over from the payload; a unit that "
            "has never reported would read as already active"
        ),
        lambda s: bool(s.get("analyticsActivatedAtMs")),
    ),
    DeviceScopedField(
        "donationStatsStartedAtMs",
        lambda p, s, c, n: (
            (p.get("donationStatsStartedAtMs") or 0) > 0
            and p.get("donationStatsStartedAtMs") != s.get("donationStatsStartedAtMs")
        ),
        lambda p, s, c, n: (
            "donationStatsStartedAtMs is 0 after provisioning; neither "
            "bootstrap call site ran, so this unit has no anchor for its own "
            "donation averages"
        ) if not (p.get("donationStatsStartedAtMs") or 0) > 0 else (
            "donationStatsStartedAtMs came over from the payload; this unit "
            "would compute its donation averages from the golden kiosk's start "
            "date instead of its own"
        ),
        # Two clauses, because the floor alone proves nothing. runProvisioning
        # calls SettingsBootstrap.apply a SECOND time, on the already-merged
        # settings (MainActivity.kt:2087), and that re-stamps a 0 to now. So a
        # device whose merge guard was deleted still comes back positive and a
        # bare `> 0` passes under every possible fixture -- it can only ever
        # catch both bootstrap call sites failing at once.
        #
        # The inequality is what catches a deleted guard: the guard's whole job
        # is to keep the device's own timestamp, so losing it leaves the
        # payload's value in place. The device's genuine value is its own
        # post-wipe bootstrap and cannot collide with the source unit's to the
        # millisecond. That makes this row load-bearing exactly when the
        # fixture carries a non-zero value -- the ordinary case. A zero-value
        # fixture is undetectable here no matter what is asserted, because the
        # second bootstrap masks the regression either way.
        lambda s: bool(s.get("donationStatsStartedAtMs")),
    ),
    DeviceScopedField(
        "kioskCode",
        lambda p, s, c, n: p.get("kioskCode") == c,
        lambda p, s, c, n: "the code override did not take",
        lambda s: True,
    ),
    DeviceScopedField(
        "kioskName",
        lambda p, s, c, n: p.get("kioskName") == n,
        lambda p, s, c, n: "the name override did not take",
        lambda s: True,
    ),
    DeviceScopedField(
        "kioskCodeFromImport",
        lambda p, s, c, n: not p.get("kioskCodeFromImport"),
        lambda p, s, c, n: (
            "a per-unit code was flagged as imported, which would warn on the "
            "one case that is correct"
        ),
        # Load-bearing, but against ProvisioningLoader's override rather than
        # SettingsImport.merge's derivation, which is what the rows above guard.
        # merge sets this from `imported.kioskCode.isNotBlank()`, then
        # ProvisioningLoader.kt:73 clears it whenever a code override is passed
        # -- and P1 always passes one. So deleting merge's derivation changes
        # nothing here, while deleting the override's clear makes this fail.
        # Verify against the override when this row stops passing, not against
        # merge.
        lambda s: True,
    ),
]


@check("P1", "P", "A provisioned device exports the configuration it was given")
def p1(ctx):
    """Round trip: provision from a known payload, export, compare.

    Run only against a clean device (reinstall_clean() below does this). On a
    device with pre-existing stored settings, MainActivity's onCreate migration
    blocks also rewrite settings, which makes the diff unexplainable.

    Which ones can actually re-fire is narrower than it first looks, and worth
    stating exactly. autoUpdateEnabled and analyticsEnabled are gated on the
    field being ABSENT from the stored json; once any saveSettings has run Gson
    emits them, so those two are genuinely one-shot. The ones that re-fire on
    every boot are the value-gated blocks: longDowntimeThresholdSec (< 300),
    autoUpdateGraceDays (<= 0), updateRepoUrl (blank) and
    autoUpdateTargetVersion (blank). A payload that sets any of those below its
    floor is silently reverted on boot 2 -- which is this check's problem,
    because provisioning restarts into an ordinary boot by design.

    The fixture is assumed to sit above those floors. Nothing asserts it: it
    comes from KIOSK_PAYLOAD, so a fixture with, say, a 120s
    longDowntimeThresholdSec produces a diff that reads as a provisioning bug
    and is not one.

    A raw file comparison fails on a CORRECT provisioning: salt, iv and
    ciphertext are regenerated from SecureRandom on every export, so the
    envelope differs even for identical plaintext. This compares decoded
    settings against PROVISION_DEVICE_SCOPED, in both directions.

    What this check does NOT cover: the credentials themselves. It compares the
    settings block only. The sole credential evidence is the two booleans the
    app reports -- affiliateKeyRestored and destinationConfigured -- and those
    say a key was RESTORED, not that it is CORRECT. A truncated key, or an
    affiliate key and a telemetry key swapped into each other's slots, passes
    here. What blocks closing it is the EXPECTED value, not the actual one: the
    affiliate key sits in plaintext in app_prefs.xml and this harness already
    reads it, but the payload's copy is inside the encrypted envelope, and
    decrypting that here means reimplementing SecretsCrypto -- the second
    implementation this check exists to avoid. The telemetry key is harder
    still; it never leaves the Keystore. Accepted on 2026-09-16
    because the affiliate key is identical on every device in the fleet, so
    there is no per-unit divergence for it to catch. If that stops being true,
    this is the gap to close first.
    """
    payload_path = os.environ.get("KIOSK_PAYLOAD")
    password = os.environ.get("KIOSK_PAYLOAD_PASSWORD")
    if not payload_path or not password:
        raise Skip(
            "set KIOSK_PAYLOAD to an export taken from a configured device and "
            "KIOSK_PAYLOAD_PASSWORD to its password. The fixture must come from "
            "the app's own exporter -- a reimplementation of SecretsCrypto here "
            "would be a second implementation that can drift, and a drift makes "
            "this check pass against a format the app no longer writes."
        )
    if not os.path.isfile(payload_path):
        raise Failure(f"KIOSK_PAYLOAD does not exist: {payload_path}")

    with open(payload_path, encoding="utf-8") as f:
        source = json.load(f)["settings"]

    reinstall_clean(ctx.apk)
    launch()

    was_root = "root" in sh("whoami")
    code = "nl-gld-arnhem-nour_al_houda-07"
    name = "Arnhem hal"
    try:
        # provision.ps1's own `adb push` has to land the payload under
        # /sdcard/Android/data/<pkg>/... as the app's own uid would see it. Rooted,
        # the push writes the file owned by root, the app's scoped-storage view of
        # it comes back permission-denied, and provisioning fails with "no_payload"
        # -- reproduced twice on this emulator. Drop root for the run, restore it
        # after: settings_json() below needs it to read app_prefs.xml.
        #
        # This has to sit inside the try: if wait-for-device times out here, the
        # daemon is left unrooted, and without the finally below every check
        # after this one in the same session fails to read app_prefs.xml for a
        # reason nowhere near this line.
        if was_root:
            adb("unroot")
            adb("wait-for-device", timeout=30)
        try:
            result = subprocess.run(
                ["powershell", "-File", "tools/provision/provision.ps1",
                 "-Apk", ctx.apk, "-Payload", payload_path, "-Password", password,
                 "-KioskCode", code, "-KioskName", name],
                capture_output=True, timeout=600,
            )
        except subprocess.TimeoutExpired:
            raise Failure("provision.ps1 did not finish within 600s")
        if result.returncode != 0:
            # Not check=True: CalledProcessError's default message echoes the
            # whole argv, including -Password in plain text. redact() only
            # catches long token-shaped secrets, and this password can be
            # anything, so that path would print it straight into the results
            # file. Report the script's own output instead, redacted.
            out = redact((result.stdout or b"").decode(errors="replace"))
            raise Failure(f"provision.ps1 failed (exit {result.returncode}):\n{out}")
    finally:
        # Unconditional: a Failure raised above must not leave the daemon
        # unrooted for every check that runs after this one in the same session.
        if was_root:
            adb("root")
            adb("wait-for-device", timeout=30)

    provisioned = settings_json()
    expect(provisioned, "no settings on the device after provisioning")

    # A field renamed in Settings.kt without this table being updated would
    # otherwise fail silent: `source.get()` and `provisioned.get()` both just
    # return None for a name that no longer exists, and the assertion below
    # would pass against two absences instead of catching the drift.
    for entry in PROVISION_DEVICE_SCOPED:
        expect(
            entry.field in source,
            f"fixture is missing device-scoped field {entry.field!r}; "
            "Settings.kt may have renamed it and PROVISION_DEVICE_SCOPED in "
            "run.py needs to follow",
        )

    device_scoped = {entry.field for entry in PROVISION_DEVICE_SCOPED}
    mismatched = []
    for key, want in source.items():
        if key in device_scoped:
            continue
        got = provisioned.get(key)
        if got != want:
            mismatched.append(f"{key}: payload={want!r} device={got!r}")
    expect(
        not mismatched,
        "fields that should have transferred did not:\n  " + "\n  ".join(mismatched),
    )

    # The other direction: every entry's own assertion runs here, not just
    # installId's. A reverse check that only re-tested installId is exactly
    # how the other eight fields regressed without this check ever turning
    # red -- excluding a field from the forward comparison is a claim that
    # something checks it going the other way, and only actually running that
    # something makes the claim true.
    load_bearing = 0
    vacuous = []
    for entry in PROVISION_DEVICE_SCOPED:
        expect(
            entry.assert_ok(provisioned, source, code, name),
            entry.reason(provisioned, source, code, name),
        )
        if entry.load_bearing(source):
            load_bearing += 1
        else:
            vacuous.append(entry.field)

    total = len(PROVISION_DEVICE_SCOPED)
    coverage = f"{total} device-scoped fields asserted"
    if vacuous:
        coverage += (
            f" ({load_bearing} load-bearing, {len(vacuous)} vacuous: "
            + ", ".join(vacuous)
            + " -- nothing this fixture can produce would make these fail, so "
            "they prove nothing about the guards behind them)"
        )
    else:
        coverage += " (all load-bearing)"

    checked = len(source) - len(device_scoped & set(source))
    return [
        f"{checked} configuration fields transferred exactly",
        coverage,
        f"overrides applied: {code} / {name}",
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
    (
        "I1",
        "An import restores a working reporting destination",
        "export goes through a SAF file picker (ActivityResultContracts.CreateDocument), "
        "so the full round trip needs a human to drive the system Documents UI. The "
        "envelope half is covered by SettingsExportFileTest; what remains untested is "
        "the MainActivity wiring, which no JVM test can reach. Guided check.",
    ),
    (
        "D5",
        "Arabic renders correctly and nothing is clipped",
        "reviewed and accepted 2026-09-16: nothing clipped, text right-aligned, "
        "layout LTR as ruled. Translations accepted as-is; RTL layout judged not "
        "worth doing. Re-run only if the disclosure copy or layout changes.",
    ),
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
        except Skip as e:
            # Reported exactly like a registration-time skip -- same status,
            # same detail line -- so the two are indistinguishable in output;
            # only the timing (known up front vs. discovered mid-run) differs.
            results.append(Result(cid, title, "SKIP", str(e)))
            print(f"\r  SKIP  {cid}  {title}\n        {str(e)}")
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
