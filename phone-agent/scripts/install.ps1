<#
.SYNOPSIS
  Build Phone Agent, install it over USB and grant every permission through adb (no root needed).

.EXAMPLE
  .\scripts\install.ps1                          build, install, grant, launch
  .\scripts\install.ps1 -Uninstall com.old.app   remove an old app first (package name)
  .\scripts\install.ps1 -NoBuild                 reuse the last built APK

  Needs: JDK 17+, Android SDK with platform 35 (Android Studio installs it), adb on PATH,
  USB debugging enabled on the phone.
#>
param(
    [string]$Uninstall = "",
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$Pkg = "io.github.judegibatron.phoneagent"
$Acc = "$Pkg/$Pkg.access.AgentAccessibilityService"
$Listener = "$Pkg/$Pkg.access.AgentNotificationListener"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found. Install Android platform-tools (Android Studio > SDK Manager) and add it to PATH."
}
adb start-server | Out-Null
$devices = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\s+device\s*$" })
if ($devices.Count -eq 0) {
    adb devices
    throw "No phone in 'device' state. Plug it in, unlock it and accept the USB debugging prompt."
}
$serial = ($devices[0] -split "\s+")[0]
Write-Host "Using device $serial"
$env:ANDROID_SERIAL = $serial   # every adb call below targets this device even if others are attached

if ($Uninstall -ne "") {
    Write-Host "Uninstalling $Uninstall"
    adb uninstall $Uninstall | Out-Null
}

if (-not $NoBuild) {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}

# -g grants every runtime permission at install time.
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw "Install failed" }

function Grant([string[]]$Cmd) {
    # A failing grant writes to stderr; under ErrorActionPreference=Stop, Windows PowerShell 5.1 would
    # turn that into a terminating error, so relax it for the duration of the call.
    $ErrorActionPreference = "Continue"
    $null = & adb shell @Cmd 2>&1
    $ErrorActionPreference = "Stop"
    if ($LASTEXITCODE -eq 0) { Write-Host "ok   $($Cmd -join ' ')" } else { Write-Host "skip $($Cmd -join ' ')" }
}
foreach ($p in "RECORD_AUDIO", "SEND_SMS", "READ_CONTACTS", "CALL_PHONE", "POST_NOTIFICATIONS", "BLUETOOTH_CONNECT", "WRITE_SECURE_SETTINGS") {
    Grant @("pm", "grant", $Pkg, "android.permission.$p")
}
Grant @("appops", "set", $Pkg, "SYSTEM_ALERT_WINDOW", "allow")
Grant @("appops", "set", $Pkg, "WRITE_SETTINGS", "allow")
Grant @("cmd", "notification", "allow_listener", $Listener)
Grant @("cmd", "notification", "allow_dnd", $Pkg)
Grant @("dumpsys", "deviceidle", "whitelist", "+$Pkg")

$current = (adb shell settings get secure enabled_accessibility_services | Out-String).Trim()
if ($current -like "*$Acc*") {
    Write-Host "ok   accessibility service already enabled"
} else {
    $value = if ($current -eq "" -or $current -eq "null") { $Acc } else { "${current}:${Acc}" }
    adb shell settings put secure enabled_accessibility_services $value | Out-Null
}
adb shell settings put secure accessibility_enabled 1 | Out-Null

adb shell am start -n "$Pkg/.ui.MainActivity" | Out-Null
Write-Host ""
Write-Host "Done. Phone Agent is open on the phone."
Write-Host "Next: paste the Claude API key, tap 'Request' next to Root and approve the Magisk/KernelSU prompt."
Write-Host "Watch it live with:  adb logcat -s PhoneAgent"
