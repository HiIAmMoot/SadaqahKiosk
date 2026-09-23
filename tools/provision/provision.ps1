<#
    Provisions a freshly reset kiosk tablet.

    Bench use only. Every step here is native adb; the settings import that
    Task 5 adds is the only part that needs the app's cooperation.

    Ordering is not arbitrary. `pm install` must precede `dpm set-device-owner`
    because the admin component has to exist to be named, and device owner
    fails outright once any account exists or setup wizard has completed.
    Everything else is ordered so the app's own onCreate finds the world
    already correct: device owner before the trigger (or the app misses
    lock-task whitelisting and its silent permission grants until the next
    boot), wifi associated before the trigger (or `isNetworkAvailable` is false
    and the freshly imported affiliate key is never authenticated, and
    SettingsBootstrap anchors donation stats to an unsynced RTC), bluetooth on
    before the trigger (or the watchdog seeds itself into recovery on a device
    that is fine).
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [Parameter(Mandatory)][string]$Apk,
    [string]$Ssid,
    [string]$WifiPassword,
    [string]$Payload,
    [string]$Password,
    [string]$KioskCode,
    [string]$KioskName,
    [string]$Logo,
    # Set as the very last step, after the app has provisioned and restarted.
    [string]$Pin,
    # Required only when re-provisioning a device that already has a PIN:
    # every lock_settings command refuses without the existing credential.
    [string]$OldPin
)

$ErrorActionPreference = "Stop"
$PKG = "com.sadaqah.kiosk"
$ADMIN = "$PKG/.KioskDeviceAdminReceiver"
$REMOTE_DIR = "/sdcard/Android/data/$PKG/files/provisioning"

function Adb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Args)
    # Scoped to this call only. adb writes its own error text (e.g. "device
    # owner already set") to stderr; under the script-wide $ErrorActionPreference
    # = Stop, 2>&1 on a native command promotes that stderr text into a
    # terminating exception at the point it's captured, killing the script
    # before Sh() or the device-owner fallback ever inspects the text. Nothing
    # downstream can catch that -- it fires while $out is still being built.
    $ErrorActionPreference = "Continue"
    $full = @()
    if ($Serial) { $full += @("-s", $Serial) }
    $full += $Args
    # Explicit .exe: a bare "adb" resolves to this function itself before the
    # executable on PATH (PowerShell command lookup is case-insensitive and
    # prefers functions), which recurses until the call stack overflows.
    & adb.exe @full 2>&1
}

function Step($name) { Write-Host "  $name" -ForegroundColor Cyan }
function Ok($name)   { Write-Host "  OK   $name" -ForegroundColor Green }
function Warn($msg)  { Write-Host "  WARN $msg" -ForegroundColor Yellow; $script:Warnings += $msg }
function Die($msg)   { Write-Host "  FAIL $msg" -ForegroundColor Red; exit 1 }

$script:Warnings = @()

<#
    Runs one adb shell command and REPORTS anything that looks wrong.

    `adb shell` exits 0 even when the command inside it failed, so an exit code
    on its own proves nothing -- the output has to be read. Without this, a
    settings write that the platform rejected is indistinguishable from one that
    worked, and the script cheerfully reports OK for a device it never
    configured. Silent partial provisioning is the failure mode this whole
    script exists to remove, so no call is allowed to pass unexamined.
#>
function Sh {
    param(
        [Parameter(Mandatory)][string]$Command,
        # No default. A default of $Command would echo the raw command text
        # verbatim into the console and $script:Warnings on any failure, and
        # the only thing keeping today's password and wifi-passphrase calls
        # off that path is each one remembering to override it -- nothing
        # enforces that for a future call built by string interpolation.
        # Making the parameter mandatory turns a forgotten override from a
        # silent leak hazard into a script that refuses to bind until the
        # caller has consciously named what gets logged.
        [Parameter(Mandatory)][string]$What,
        # Fatal when the step is load-bearing; a warning when the kiosk still
        # works without it.
        [switch]$Fatal
    )
    $out = (Adb shell $Command) -join "`n"
    $bad = $LASTEXITCODE -ne 0 -or
           $out -match "Error|error:|Exception|Denied|denied|not found|Failure|Unknown command|Invalid|cannot|Can't|refused"
    if ($bad) {
        $detail = "$What -> $($out.Trim())"
        if ($Fatal) { Die $detail } else { Warn $detail }
        return $false
    }
    return $true
}

<#
    Quotes a value for the DEVICE-side shell that `adb shell` hands the
    command to, not for PowerShell.

    A bare `'$Value'` breaks the instant $Value itself contains an
    apostrophe (an SSID like "Nour's Wifi", or a passphrase with one): the
    device shell sees the quote close early and splits what was meant to be
    one argument into two, silently. The standard POSIX trick handles it --
    close the quote, emit an escaped literal quote outside it, reopen the
    quote -- so the value survives intact however it's spelled.
#>
function ShQuote([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

<#
    Returns the running pid(s) of $PKG as one trimmed string, or "" if it
    isn't running.

    `pidof` prints NOTHING at all on no match -- not even a blank line -- so
    `Adb shell "pidof $PKG"` comes back as $null rather than an empty string,
    and calling .Trim() straight on that throws InvokeMethodOnNull. That is
    exactly the "process is gone" case the priming and trigger steps below
    poll for, so it has to read as an empty value here, not crash the script
    on the primary success path.
#>
function PidOf {
    $out = Adb shell "pidof $PKG"
    if ($null -eq $out) { return "" }
    return ($out -join "`n").Trim()
}

Write-Host "`nProvisioning $(if ($Serial) { $Serial } else { 'the attached device' })`n"

# --- device present -------------------------------------------------------
Step "waiting for device"
Adb wait-for-device | Out-Null
# -join before .Trim(): Adb merges stderr, so one extra line turns the result
# into an array, and .Trim() called straight on an array member-enumerates
# instead of trimming a string. -join first collapses that back into one
# string, which also protects the null case below: -join on $null (a device
# in recovery or sideload -- exactly what wait-for-device above will return
# for -- yields "", not $null, so .Trim() never runs on $null and throws
# InvokeMethodOnNull under this script's $ErrorActionPreference = "Stop".
$boot = (Adb shell getprop sys.boot_completed) -join "`n"
if ($boot.Trim() -ne "1") { Die "device has not finished booting" }
Ok "device ready"

# --- install --------------------------------------------------------------
Step "installing $Apk"
$r = (Adb install -r -g $Apk) -join "`n"
if ($r -notmatch "Success") { Die "install failed: $r" }
Ok "installed"

# --- device owner ---------------------------------------------------------
# Must follow install. Two distinct refusals share one opaque error, so both
# are named: an account exists, or setup wizard was completed rather than
# skipped.
# FATAL, not a warning. Without device owner, Android 10+ aborts the
# startActivity that BootReceiver makes after a reboot:
#
#   E ActivityTaskManager: Abort background activity starts from <uid>
#
# Verified on an emulator: with device owner the kiosk reaches the foreground
# ~28s after unlock; without it the process starts, logs "Device booted --
# launching kiosk app", and is thrown away silently. A kiosk provisioned
# without device owner looks correct on the bench and never comes back from a
# power cut, with the only trace a logcat line nobody reads.
Step "setting device owner"
$r = (Adb shell "dpm set-device-owner $ADMIN") -join "`n"
if ($r -match "Success") {
    Ok "device owner set"
} else {
    # "set-device-owner" failing tells us nothing on its own -- it fails
    # identically whether nobody owns the device yet or $ADMIN just isn't the
    # one that already does. `dpm list-owners` names the actual owner, so the
    # fallback can assert THIS admin holds the role rather than merely that
    # a role exists. A generic "some owner exists" check would rubber-stamp a
    # device stuck on a stale or wrong admin as "already set" and walk
    # straight past the one step this script treats as fatal.
    $owners = (Adb shell "dpm list-owners") -join "`n"
    # $ADMIN and the DeviceOwner marker must be on the SAME line. Checking
    # them independently against the whole blob would let a ProfileOwner
    # entry for this same component -- a different, lesser role -- read as
    # "device owner already set": the exact silent pass this fallback exists
    # to rule out, one line owner-check deeper than the file-wide version.
    $adminIsDeviceOwner = ($owners -split "`n") | Where-Object {
        $_ -match [regex]::Escape($ADMIN) -and $_ -match "DeviceOwner"
    }
    if ($adminIsDeviceOwner) {
        Ok "device owner already set"
    } elseif ($owners -match "DeviceOwner") {
        Write-Host "  FAIL a different component already owns this device:" -ForegroundColor Red
        Write-Host "    $($owners.Trim())" -ForegroundColor Red
        Write-Host "" -ForegroundColor Red
        Write-Host "  This is fatal, not cosmetic. Without $ADMIN as device owner:" -ForegroundColor Red
        Write-Host "    - the kiosk will NOT restart itself after a power cut" -ForegroundColor Red
        Write-Host "    - lock task degrades to consumer pinning, with the blue bar" -ForegroundColor Red
        Write-Host "    - the silent location and wifi-scan permission grants never run" -ForegroundColor Red
        Write-Host "" -ForegroundColor Red
        Write-Host "  This is a stale or mismatched owner, not a missing one -- a" -ForegroundColor Red
        Write-Host "  different fix than 'no owner yet'. Device owner cannot be" -ForegroundColor Red
        Write-Host "  swapped in place; only a factory reset clears it. Wipe the" -ForegroundColor Red
        Write-Host "  device, skip the wizard, add no account, and run this again." -ForegroundColor Red
        exit 1
    } else {
        Write-Host "  FAIL device owner could not be set: $($r.Trim())" -ForegroundColor Red
        Write-Host "" -ForegroundColor Red
        Write-Host "  This is fatal, not cosmetic. Without device owner:" -ForegroundColor Red
        Write-Host "    - the kiosk will NOT restart itself after a power cut" -ForegroundColor Red
        Write-Host "    - lock task degrades to consumer pinning, with the blue bar" -ForegroundColor Red
        Write-Host "    - the silent location and wifi-scan permission grants never run" -ForegroundColor Red
        Write-Host "" -ForegroundColor Red
        Write-Host "  Two causes share this one error: an account exists on the device," -ForegroundColor Red
        Write-Host "  or setup wizard was COMPLETED rather than skipped. Factory reset," -ForegroundColor Red
        Write-Host "  skip the wizard entirely, add no account, and run this again." -ForegroundColor Red
        exit 1
    }
}

# --- radios ---------------------------------------------------------------
# Location SERVICES, not the location permission -- the permission is already
# granted by MainActivity.kt:296-307 once device owner is set. BLUETOOTH_SCAN is
# declared without neverForLocation, so BLE discovery fails with services off,
# and a factory-reset tablet has them off.
Step "enabling radios"
Sh "svc wifi enable" -What "wifi radio" | Out-Null
Sh "svc bluetooth enable" -What "bluetooth radio" | Out-Null
# svc bluetooth enable returns immediately and prints nothing whether or not
# the adapter actually comes up, so Sh's exit-code-plus-error-word check
# cannot see a radio that failed to enable -- unlike wifi, which gets a real
# association assertion below. A warning, not a Die: BluetoothRecoveryManager
# self-heals this after a delay, so the cost of a radio that never reports on
# here is a spurious recovery event and a diagnostic row on an otherwise fine
# kiosk, not a broken one -- but the founder's rule is that a step able to
# fail must at least warn, and until now this one couldn't.
# Check before sleeping, not after: `svc bluetooth enable` is usually already
# done by the time the next line runs, and sleeping first added a second to
# every successful provisioning run to observe something that was already true.
$btOn = $false
foreach ($i in 1..5) {
    $btState = (Adb shell "settings get global bluetooth_on") -join "`n"
    if ($btState.Trim() -eq "1") { $btOn = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $btOn) { Warn "bluetooth radio did not report on within 5s -- it will self-heal via BluetoothRecoveryManager, but verify it by hand" }
Sh "cmd location set-location-enabled true" -What "location services" -Fatal | Out-Null
Sh "settings put secure location_mode 3" -What "location mode" | Out-Null
Ok "wifi, bluetooth, location on"

# --- wifi -----------------------------------------------------------------
# -m marks the network metered, which is what makes Data Saver below actually
# restrict anything: Data Saver only applies on metered networks and wifi is
# unmetered by default.
if ($Ssid) {
    Step "joining $Ssid"
    $ssidQ = ShQuote $Ssid
    if ($WifiPassword) {
        Sh "cmd wifi connect-network $ssidQ wpa2 $(ShQuote $WifiPassword) -m" -What "wifi connect-network" | Out-Null
    } else {
        Sh "cmd wifi connect-network $ssidQ open -m" -What "wifi connect-network" | Out-Null
    }
    # Non-fatal here on purpose: a bad SSID/passphrase surfaces below as a
    # clear, specific Die from the association check, which is a better
    # diagnosis than this call's own error text would give in isolation.
    # connect-network returns before association completes, so a wrong
    # passphrase exits 0 and leaves an offline kiosk.
}

# -Payload OR -Ssid, and both halves are load-bearing.
#
# -Payload, because a device that already holds wifi credentials skips the join
# above entirely but still gets pushed a payload and triggered: the app reads
# isNetworkAvailable once at startup, so the freshly imported affiliate key is
# never authenticated and SettingsBootstrap anchors donationStatsStartedAtMs to
# an unsynced RTC. Both are silent from the app's side.
#
# -Ssid, because the join above is deliberately non-fatal and defers its
# diagnosis to this check. Gating on -Payload alone would leave a native-only
# run (-Ssid, no -Payload) asserting nothing at all: a wrong passphrase would
# exit 0, leave an offline kiosk, and still print PROVISIONED.
if ($Payload -or $Ssid) {
    Step "verifying wifi connectivity"
    # `cmd wifi status` names the SSID actually associated, which the old
    # `dumpsys wifi` CONNECTED check could not: that only proved SOME network
    # was up, so re-provisioning onto a new SSID passed while still joined to
    # the old one, and a wrong passphrase passed against any other saved
    # network. -join before -match is required: -match against a multi-line
    # array filters elements instead of populating $Matches.
    $joined = $false
    $actualSsid = $null
    foreach ($i in 1..20) {
        Start-Sleep -Seconds 2
        $status = (Adb shell "cmd wifi status") -join "`n"
        # Reset each pass, so the timeout message below names what the device is
        # on NOW. Carrying the last-seen value forward would report a network it
        # has since dropped, which is the opposite of a useful bench diagnosis.
        $actualSsid = $null
        if ($status -match 'Wifi is connected to "([^"]*)"') { $actualSsid = $Matches[1] }
        if ($Ssid) {
            if ($actualSsid -eq $Ssid) { $joined = $true; break }
        } elseif ($actualSsid) {
            # No SSID was requested, so there is nothing to compare against —
            # but this still has to prove the transport is wifi specifically.
            # The previous fallback here (wifi_on + "some VALIDATED network")
            # passed on cellular or ethernet, which proves nothing about the
            # wifi this run depends on. `cmd wifi status` reporting a
            # connected SSID at all is wifi-transport-specific by construction.
            $joined = $true; break
        }
    }
    if (-not $joined) {
        if ($Ssid -and $actualSsid) {
            Die "connected to '$actualSsid', not the requested '$Ssid' — check the SSID and passphrase"
        } elseif ($Ssid) {
            Die "did not associate with '$Ssid' within 40s — check the SSID and passphrase"
        } else {
            Die "no wifi connection within 40s — provisioning needs one before the trigger"
        }
    }
    Ok "wifi associated$(if ($actualSsid) { " ('$actualSsid')" })"
}

# --- screen ---------------------------------------------------------------
Step "configuring screen"
Sh "settings put system screen_off_timeout 2147483647" -What "screen timeout" -Fatal | Out-Null
Sh "settings put system screen_brightness_mode 1" -What "adaptive brightness" | Out-Null
Sh "settings put secure lock_to_app_enabled 1" -What "pinning fallback" | Out-Null
Sh "svc power stayon true" -What "stay awake while charging" | Out-Null
Ok "never sleeps, adaptive brightness, pinning allowed"

# --- data reduction -------------------------------------------------------
# The UID is assigned at install and changes on reinstall, so it is read now
# rather than hardcoded — a stale UID would whitelist some other app.
Step "restricting background data"
# -join before -match: grep -m1 closes the pipe after one line, so dumpsys
# logs a "Broken pipe" line on stderr too, making the raw result a 2-element
# array. -match on an array filters elements instead of populating $Matches,
# so $uid silently comes out empty without the join.
$uidLine = (Adb shell "dumpsys package $PKG | grep -m1 userId") -join "`n"
if ($uidLine -match "userId=(\d+)") {
    $uid = $Matches[1]
    Sh "cmd netpolicy set restrict-background true" -What "data saver" | Out-Null
    Sh "cmd netpolicy add restrict-background-whitelist $uid" -What "whitelist uid $uid" | Out-Null
    Ok "data saver on, $PKG (uid $uid) whitelisted"
} else {
    Die "could not read the app's uid from dumpsys"
}

Write-Host "`n  Battery protection is not settable from adb on stock Android." -ForegroundColor Yellow
Write-Host "  Enable it by hand in the device's own battery settings.`n" -ForegroundColor Yellow

# --- settings import ------------------------------------------------------
if ($Payload) {
    if (-not $Password)   { Die "-Payload needs -Password (the export's password)" }
    if (-not $KioskCode)  { Die "-Payload needs -KioskCode — a fleet cloned from one export otherwise reports under a single code" }
    if (-not $KioskName)  { Die "-Payload needs -KioskName — it is attached to every SumUp transaction, so a fleet would bill under one name" }

    $runId = [guid]::NewGuid().ToString()

    <#
        Waits for a NEW pid to replace $PidBefore, up to $MaxTries * 2s.
        Returns the new pid, or $null if it never showed up.

        Shared by the priming trigger below and the real one further down:
        the PBKDF2 decrypt behind a real trigger is 600_000 iterations, so a
        single fixed sleep would either be too short for that or needlessly
        long for the instant no-payload case the priming trigger produces.
    #>
    function WaitForRestart([string]$PidBefore, [int]$MaxTries = 15) {
        foreach ($i in 1..$MaxTries) {
            Start-Sleep -Seconds 2
            $pidNow = PidOf
            if ($pidNow -and $pidNow -ne $PidBefore) { return $pidNow }
        }
        return $null
    }

    # getExternalFilesDir(null) creates only $REMOTE_DIR's PARENT ("files/")
    # automatically — that much the platform does for every installed app
    # regardless of app code. The "provisioning" directory itself is created
    # ONLY by writeProvisioningResult()'s own mkdirs(), which runs solely
    # inside an actual triggered provisioning attempt — a plain `am start`
    # with no extras never reaches that code, so it never creates this
    # directory. Confirmed empirically: after a plain launch, "files/" came
    # up app-owned on its own but "provisioning" stayed entirely absent.
    # A directory THIS SCRIPT mkdir'd instead would be owned by shell
    # (drwxrws--- shell:ext_data_rw), and the app cannot write
    # provision-result.json into that — confirmed via logcat:
    #   W/Provisioning: Could not write the result file: .../provision-result.json:
    #   open failed: EACCES (Permission denied)
    # So the directory has to be created BY the app, which means running the
    # real provisioning path once before anything is pushed. A trigger with
    # no kiosk.json on disk yet fails fast as "no_payload", but still runs
    # writeProvisioningResult() -> mkdirs() on its way there, leaving the
    # directory app-owned for the real push that follows. The placeholder
    # password is never secret — no_payload short-circuits before it's ever
    # read — so it needs no redaction.
    Step "priming the app's data directory"
    # Priming must not decrypt anything, or its bounded wait below becomes
    # exactly the false negative just removed from the real trigger. On a
    # FAILED run the app deliberately keeps kiosk.json (and a pushed logo) so
    # a human can retry by hand without re-pushing -- but this script always
    # re-pushes a fresh payload a few lines down regardless, so a leftover
    # file here has no use to it, only a cost: priming's own no_payload
    # attempt would find a real (if stale) payload and run a genuine
    # 600_000-iteration PBKDF2 decrypt inside a 30s bound. That bites exactly
    # the retry-after-a-wrong-password flow the keep-on-failure design exists
    # to support, so clear both before priming rather than leave the trap.
    Sh "rm -f $REMOTE_DIR/kiosk.json $REMOTE_DIR/logo" -What "clearing any payload left from a previous failed run" | Out-Null
    $pidBefore = PidOf
    $primeId = [guid]::NewGuid().ToString()
    Sh ("am start -n $PKG/.MainActivity -f 0x10008000 " +
               "--es provision_run_id $(ShQuote $primeId) --es provision_password $(ShQuote 'x')") `
       -What "am start -n $PKG/.MainActivity -f 0x10008000 (priming; a harmless no_payload failure is expected)" `
       -Fatal | Out-Null
    if (-not (WaitForRestart $pidBefore)) {
        Die "app never came up to create its data directory — check it isn't crash-looping"
    }
    Ok "data directory ready"

    Step "pushing payload"
    Sh "rm -f $REMOTE_DIR/provision-result.json" -What "clearing any stale result" | Out-Null
    $pushOut = (Adb push $Payload "$REMOTE_DIR/kiosk.json") -join "`n"
    if ($pushOut -notmatch "file pushed") { Die "could not push the payload: $pushOut" }
    if ($Logo) {
        $logoOut = (Adb push $Logo "$REMOTE_DIR/logo") -join "`n"
        if ($logoOut -notmatch "file pushed") { Die "could not push the logo: $logoOut" }
    }
    Ok "payload in place"

    # force-stop CANNOT be used here at all — not merely "unless pinned", which
    # is as far as Task 1's brief went. Verified on this emulator, twice, with
    # logcat proving it: once against a freshly reinstalled process
    # (mLockTaskModeState=NONE, never pinned) and once against a steady-state
    # pinned one (LOCKED) — both produced the exact same line and neither
    # process ever turned over:
    #   W/ActivityManager: Ignoring request to force stop protected package
    #   com.sadaqah.kiosk u0
    # A device-owner package is unconditionally protected from force-stop by
    # the platform itself; pin state never enters into it, so there is no
    # "primary case" where the brief's force-stop step does anything at all.
    # MainActivity also has no launchMode and no onNewIntent, so a bare
    # `am start` against the still-running task just brings it forward and
    # silently discards the extras, same as the brief warned — it's the
    # premise about force-stop fixing that which doesn't hold.
    #
    # What DOES force a genuinely fresh onCreate, confirmed against an
    # actively LOCKED instance via logcat ("Provisioning complete, restarting
    # into a normal boot" + the pid turning over): FLAG_ACTIVITY_NEW_TASK
    # (0x10000000) combined with FLAG_ACTIVITY_CLEAR_TASK (0x00008000) — the
    # exact pair relaunchAfterProvisioning() itself already uses to rebuild
    # the task after a successful run. No force-stop, no reboot, needed for
    # the common case.
    Step "triggering import"
    $pidBefore = PidOf
    # Every value goes through ShQuote, not just the password: a kiosk name
    # or code with an apostrophe (a possessive shop name, say) closes the
    # device-side single quote early and splits the extra in two exactly
    # like an unquoted SSID or wifi password would -- see ShQuote's own doc.
    # -What still omits the password itself; that is the one value on this
    # line a warning must never echo.
    Sh ("am start -n $PKG/.MainActivity -f 0x10008000 " +
               "--es provision_run_id $(ShQuote $runId) " +
               "--es provision_password $(ShQuote $Password) " +
               "--es provision_kiosk_code $(ShQuote $KioskCode) " +
               "--es provision_kiosk_name $(ShQuote $KioskName)") `
       -What "am start -n $PKG/.MainActivity -f 0x10008000 --es provision_run_id/kiosk_code/kiosk_name (password redacted)" `
       -Fatal | Out-Null

    Step "waiting for the device to report"
    # The pid is a hint here, not a gate. A real run decrypts the payload
    # TWICE (once in ProvisioningLoader.decide, again in applyProvisioning's
    # importSettings) at 600_000 PBKDF2 iterations each, so a slow-but-correct
    # device can easily take longer than a short fixed wait to turn its
    # process over -- gating on that separately would report a merely slow
    # run as a crash. The result file is the actual authority; track whether
    # the process ever restarted only to sharpen the message if this times
    # out with nothing.
    $result = $null
    $sawRestart = $false
    foreach ($i in 1..60) {
        Start-Sleep -Seconds 2
        if (-not $sawRestart) {
            $pidNow = PidOf
            if ($pidNow -and $pidNow -ne $pidBefore) { $sawRestart = $true }
        }
        # -join before the null/truthy check below: without it, an extra
        # line from Adb's merged stderr makes $raw a multi-line array, which
        # ConvertFrom-Json then parses per-element (each half invalid JSON on
        # its own), the catch below swallows the error, and this loop times
        # out at 120s for a device that reported fine.
        $raw = (Adb shell "cat $REMOTE_DIR/provision-result.json 2>/dev/null") -join "`n"
        if ($raw) {
            try { $parsed = $raw | ConvertFrom-Json } catch { continue }
            # A stale result from an earlier attempt or another device is
            # indistinguishable from this one's without the run id. The "at"
            # timestamp cannot rescue it: a factory-reset tablet has an unsynced
            # clock.
            if ($parsed.runId -eq $runId) { $result = $parsed; break }
        }
    }
    if (-not $result) {
        if ($sawRestart) {
            Die "no result after 120s — the app restarted but never reported; check logcat on the device"
        } else {
            Die "no result after 120s — the app never came back up after the trigger; it may have crashed on launch, or the intent never reached it. Run 'adb reboot' and re-run this script."
        }
    }

    if ($result.status -ne "applied") { Die "provisioning failed: $($result.reason)" }

    # A golden export taken without ticking "include keys" parses fine and
    # carries nothing, so `applied` alone would pass a kiosk that takes no
    # payments and reports nowhere.
    if (-not $result.affiliateKeyRestored)  { Die "applied, but no affiliate key — was the export taken with keys included?" }
    if (-not $result.destinationConfigured) { Die "applied, but no reporting destination — was the export taken with keys included?" }
    if ($Logo -and -not $result.logoDecodable) { Die "logo was copied but could not be decoded — check the image" }

    Ok "imported: $($result.kioskCode) / $($result.kioskName), install $($result.installId)"

    # Cleared only on success. On failure the app keeps the payload so the
    # operator can retry with a corrected password without re-pushing.
    Sh "rm -f $REMOTE_DIR/kiosk.json $REMOTE_DIR/logo $REMOTE_DIR/provision-result.json" -What "cleaning up the payload" | Out-Null
}

# --- device PIN -----------------------------------------------------------
# LAST, and deliberately after the app has provisioned and restarted. A PIN
# makes the device secure, and a secure device withholds ACTION_BOOT_COMPLETED
# until someone unlocks it — setting this any earlier would strand provisioning
# itself behind a lock screen.
#
# The app needs no change to use it: MainActivity's settings screen already
# builds its unlock prompt with BIOMETRIC_WEAK or DEVICE_CREDENTIAL, so it is
# protected the moment a credential exists.
if ($Pin) {
    Step "setting the device PIN"
    # Every lock_settings command refuses once a credential exists, so
    # re-provisioning a device that already has one must pass the old value.
    $oldArg = if ($OldPin) { "--old $OldPin " } else { "" }
    # -join before -match: the same array-vs-scalar hazard as the uid read
    # above and the original wifi_on check -- without it, -match filters the
    # output array element-by-element instead of testing the whole string,
    # so a multi-line response would silently never match either branch.
    $r = (Adb shell "cmd lock_settings set-pin $oldArg$Pin") -join "`n"
    if ($r -notmatch "Pin set to") {
        if ($r -match "Credential can't be null or empty|old credential") {
            Die "this device already has a lock credential — pass -OldPin to replace it"
        }
        Die "could not set the PIN: $r"
    }
    Ok "PIN set — the settings screen now requires it"

    Write-Host ""
    Write-Host "  NOTE: this device is now secure, so a POWER CUT will leave the" -ForegroundColor Yellow
    Write-Host "  kiosk on a lock screen and it will not take donations until" -ForegroundColor Yellow
    Write-Host "  someone attends it and types the PIN. App restarts, the watchdog" -ForegroundColor Yellow
    Write-Host "  and auto-update are unaffected — none of them reboot the device." -ForegroundColor Yellow
    Write-Host "  Undo on a bench with: adb shell cmd lock_settings clear --old $Pin" -ForegroundColor Yellow
}

if ($script:Warnings.Count -gt 0) {
    Write-Host ""
    Write-Host "  $($script:Warnings.Count) step(s) did not apply cleanly:" -ForegroundColor Yellow
    foreach ($w in $script:Warnings) { Write-Host "    - $w" -ForegroundColor Yellow }
    Write-Host "  The kiosk will run, but check each one before deploying it." -ForegroundColor Yellow
    Write-Host ""
}

Write-Host "PROVISIONED (native steps)" -ForegroundColor Green
