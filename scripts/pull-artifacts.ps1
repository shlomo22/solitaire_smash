<#
  Solitaire Smash - Pull Artifacts (on demand)
  -----------------------------------------------------------
  Run this whenever you've tapped Golden truth -> Evaluate and want to
  grab the result for Cursor/Claude Code to look at:

    powershell -ExecutionPolicy Bypass -File scripts\pull-artifacts.ps1

  Pulls, into a fresh timestamped folder under /pulled (plus a
  'latest' pointer that always has the newest run):
    - analysis.log    (the app's own evaluate-run log, from internal
                        app-private storage via 'adb exec-out run-as' -
                        the scripted equivalent of downloading it
                        through Android Studio's Device Explorer)
    - screenshot.png  (whatever's on screen right now)
    - logcat.txt      (filtered to just this app's process, when it's
                        still running; falls back to the full device
                        log with a warning otherwise)
#>

. "$PSScriptRoot\config.ps1"
$ErrorActionPreference = "Stop"

$timestamp = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
$runDir = Join-Path $OutputRoot $timestamp
New-Item -ItemType Directory -Path $runDir -Force | Out-Null

Write-Host "== Checking device connection =="
$devices = & $AdbPath devices
if (-not ($devices -match "\bdevice\b")) {
    throw "No device detected via adb. Check the cable / run 'adb devices'."
}

Write-Host "== Pulling analysis.log =="
$logDest = Join-Path $runDir "analysis.log"
& $AdbPath exec-out run-as $PackageName cat $AnalysisLogRelPath > $logDest
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $logDest) -or (Get-Item $logDest).Length -eq 0) {
    Write-Warning "Could not pull analysis.log via 'run-as'. Make sure $PackageName is installed as a debug build, the app has run Evaluate at least once, and $AnalysisLogRelPath in scripts\config.ps1 is still correct."
}

Write-Host "== Finding app process id =="
$appPid = ((& $AdbPath shell pidof $PackageName) -join " ").Trim().Split(" ")[0]

Write-Host "== Pulling logcat =="
if ($appPid) {
    Write-Host "   (filtered to $PackageName, pid $appPid)"
    & $AdbPath logcat -d --pid=$appPid *> (Join-Path $runDir "logcat.txt")
} else {
    Write-Warning "Couldn't find a running process for $PackageName - is the app still open? Pulling full, unfiltered device logcat instead."
    & $AdbPath logcat -d *> (Join-Path $runDir "logcat.txt")
}

Write-Host "== Taking screenshot =="
& $AdbPath exec-out screencap -p > (Join-Path $runDir "screenshot.png")

$latestLink = Join-Path $OutputRoot "latest"
if (Test-Path $latestLink) { Remove-Item $latestLink -Recurse -Force }
Copy-Item -Path $runDir -Destination $latestLink -Recurse

Write-Host "== Done. Artifacts in: $runDir (and copied to $latestLink) =="
