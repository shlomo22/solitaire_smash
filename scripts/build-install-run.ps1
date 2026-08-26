<#
  Solitaire Smash - Build, Install, and Launch
  -----------------------------------------------------------
  Runs automatically on every git push (via the self-hosted GitHub
  Actions runner + .github/workflows/auto-build-pull.yml). Builds the
  debug APK via Gradle (same build Android Studio's Run button would
  produce), installs it on the connected device, clears the logcat
  buffer, and launches the app.

  This script does NOT pull anything. Once the app is up, go play/test
  as long as you want, then run scripts\pull-artifacts.ps1 whenever
  you're ready to grab logs/screenshot/save data for that session.
#>

. "$PSScriptRoot\config.ps1"
$ErrorActionPreference = "Stop"

Write-Host "== Building debug APK =="
# --no-daemon: a Gradle daemon started before ANDROID_HOME was ever set
# (i.e. during the very first failed build on this machine) stays alive
# and gets reused for speed - but it keeps the environment it was
# originally launched with, so it never sees env vars set afterward.
# CI has no "next build" soon enough for a daemon to pay for itself
# anyway, so skip it entirely and always get a fresh process.
# --no-configuration-cache: same idea, belt-and-suspenders against a
# stale cached build configuration from that same early failure.
& $GradlewPath assembleDebug --no-daemon --no-configuration-cache
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)" }

Write-Host "== Checking device connection =="
$devices = & $AdbPath devices
if (-not ($devices -match "\bdevice\b")) {
    Write-Warning "No device detected via adb. Build succeeded but install/launch are being skipped."
    exit 0
}

Write-Host "== Installing on device =="
& $GradlewPath installDebug --no-daemon --no-configuration-cache
if ($LASTEXITCODE -ne 0) { throw "Install failed (exit $LASTEXITCODE)" }

Write-Host "== Clearing logcat buffer (so a later pull starts clean from this run) =="
& $AdbPath logcat -c

Write-Host "== Launching app =="
& $AdbPath shell am start -n "$PackageName/$MainActivity"

Write-Host "== Done. Go play/test, then run scripts\pull-artifacts.ps1 whenever you want to capture the current logs/screenshot/save data. =="
