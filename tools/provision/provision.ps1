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
        [string]$What = $Command,
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

Write-Host "`nProvisioning $(if ($Serial) { $Serial } else { 'the attached device' })`n"

# --- device present -------------------------------------------------------
Step "waiting for device"
Adb wait-for-device | Out-Null
if ((Adb shell getprop sys.boot_completed).Trim() -ne "1") { Die "device has not finished booting" }
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
    if ($owners -match [regex]::Escape($ADMIN)) {
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
Sh "cmd location set-location-enabled true" -What "location services" -Fatal | Out-Null
Sh "settings put secure location_mode 3" -What "location mode" | Out-Null
Ok "wifi, bluetooth, location on"

# --- wifi -----------------------------------------------------------------
# -m marks the network metered, which is what makes Data Saver below actually
# restrict anything: Data Saver only applies on metered networks and wifi is
# unmetered by default.
if ($Ssid) {
    Step "joining $Ssid"
    if ($WifiPassword) {
        Adb shell "cmd wifi connect-network '$Ssid' wpa2 '$WifiPassword' -m" | Out-Null
    } else {
        Adb shell "cmd wifi connect-network '$Ssid' open -m" | Out-Null
    }
    # connect-network returns before association completes, so a wrong
    # passphrase exits 0 and leaves an offline kiosk. Assert connectivity.
    $joined = $false
    foreach ($i in 1..20) {
        Start-Sleep -Seconds 2
        if ((Adb shell "dumpsys wifi | grep -m1 'mNetworkInfo'") -match "state: CONNECTED") { $joined = $true; break }
        if ((Adb shell "settings get global wifi_on").Trim() -eq "1" -and
            (Adb shell "dumpsys connectivity | grep -m1 'NetworkAgentInfo.*VALIDATED'")) { $joined = $true; break }
    }
    if (-not $joined) { Die "did not associate with '$Ssid' within 40s — check the SSID and passphrase" }
    Ok "wifi associated"
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

if ($script:Warnings.Count -gt 0) {
    Write-Host ""
    Write-Host "  $($script:Warnings.Count) step(s) did not apply cleanly:" -ForegroundColor Yellow
    foreach ($w in $script:Warnings) { Write-Host "    - $w" -ForegroundColor Yellow }
    Write-Host "  The kiosk will run, but check each one before deploying it." -ForegroundColor Yellow
    Write-Host ""
}

Write-Host "PROVISIONED (native steps)" -ForegroundColor Green
