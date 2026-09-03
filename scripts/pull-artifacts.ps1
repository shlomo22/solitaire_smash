<#
  Solitaire Smash - Pull Artifacts (on demand)
  -----------------------------------------------------------
  Run this whenever you've tapped Golden truth -> Evaluate and want to
  grab the result for Cursor/Claude Code to look at:

    powershell -ExecutionPolicy Bypass -File scripts\pull-artifacts.ps1

  Pulls, into a fresh timestamped folder under /pulled (plus a
  'latest' pointer that always has the newest run):
    - analysis.log      (the app's own evaluate-run log, from internal
                          app-private storage via 'adb exec-out run-as' -
                          the scripted equivalent of downloading it
                          through Android Studio's Device Explorer)
    - analysis.log.1..3 (rotated generations, when they exist)
    - analysis-full.log (all of the above stitched oldest-first; read this
                          one for anything that happened earlier than the
                          last few minutes of play)
    - screenshot.png    (whatever's on screen right now)
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

# PowerShell's own '>' redirection re-encodes/re-interprets a native
# command's raw stdout (line-ending translation, text encoding) before
# writing it to disk - harmless for plain ASCII, but it corrupts binary
# data like a PNG, and can subtly mangle non-ASCII bytes in a log too.
# Routing the redirection through cmd.exe instead writes the child
# process's bytes straight to disk, untouched.
function Invoke-AdbToFile {
    param([string[]]$AdbArgs, [string]$DestPath)
    $quotedArgs = ($AdbArgs | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $cmdLine = '"' + $AdbPath + '" ' + $quotedArgs + ' > "' + $DestPath + '"'
    cmd /c $cmdLine
    return $LASTEXITCODE
}

Write-Host "== Pulling analysis.log =="
$logDest = Join-Path $runDir "analysis.log"
$exitCode = Invoke-AdbToFile -AdbArgs @("exec-out", "run-as", $PackageName, "cat", $AnalysisLogRelPath) -DestPath $logDest
if ($exitCode -ne 0 -or -not (Test-Path $logDest) -or (Get-Item $logDest).Length -eq 0) {
    Write-Warning "Could not pull analysis.log via 'run-as'. Make sure $PackageName is installed as a debug build, the app has run Evaluate at least once, and $AnalysisLogRelPath in scripts\config.ps1 is still correct."
}

# The live log holds only the newest slice of a session - a per-slot
# 'recognition:' block on every outcome fills a generation in minutes, so a
# whole game spans several. Pull the rotated generations too and stitch them
# into analysis-full.log oldest-first, which is what any question about an
# arrow from earlier in the game actually needs. '.bak' is the pre-v1.4.130
# single-generation name, still pulled so an older build's log isn't missed.
Write-Host "== Pulling rotated log generations =="
$rotatedNames = @("analysis.log.3", "analysis.log.2", "analysis.log.1", "analysis.log.bak")
$pulledRotated = @()
foreach ($name in $rotatedNames) {
    $dest = Join-Path $runDir $name
    $rel = "files/logs/$name"
    Invoke-AdbToFile -AdbArgs @("exec-out", "run-as", $PackageName, "cat", $rel) -DestPath $dest | Out-Null
    if (-not (Test-Path $dest)) { continue }
    # 'adb exec-out' folds the remote command's stderr into the same stream, so
    # a missing generation lands on disk as cat's own error text rather than as
    # an empty file - a plain size check treats that as real content and then
    # splices "No such file or directory" into the middle of the combined log.
    $head = ""
    if ((Get-Item $dest).Length -gt 0) {
        $head = (Get-Content $dest -TotalCount 3 -ErrorAction SilentlyContinue) -join "`n"
    }
    $isError = $head -match 'No such file or directory|Permission denied|^\s*cat:'
    if ((Get-Item $dest).Length -eq 0 -or $isError) {
        Remove-Item $dest -Force
        continue
    }
    $pulledRotated += $dest
    Write-Host ("   {0} ({1:N0} bytes)" -f $name, (Get-Item $dest).Length)
}

$fullDest = Join-Path $runDir "analysis-full.log"
$ordered = @($pulledRotated) + @($logDest) | Where-Object { Test-Path $_ }
if ($ordered.Count -gt 1) {
    Set-Content -Path $fullDest -Value $null
    foreach ($part in $ordered) {
        Add-Content -Path $fullDest -Value "=== [pull-artifacts] begin $(Split-Path $part -Leaf) ==="
        Get-Content -Path $part | Add-Content -Path $fullDest
    }
    Write-Host ("== Combined {0} files into analysis-full.log ({1:N0} bytes) ==" -f $ordered.Count, (Get-Item $fullDest).Length)
} else {
    Write-Host "== No rotated generations present; analysis.log is the whole session =="
}

Write-Host "== Taking screenshot =="
$screenshotDest = Join-Path $runDir "screenshot.png"
Invoke-AdbToFile -AdbArgs @("exec-out", "screencap", "-p") -DestPath $screenshotDest | Out-Null

$latestLink = Join-Path $OutputRoot "latest"
if (Test-Path $latestLink) { Remove-Item $latestLink -Recurse -Force }
Copy-Item -Path $runDir -Destination $latestLink -Recurse

Write-Host "== Done. Artifacts in: $runDir (and copied to $latestLink) =="

<#
  Cursor agent hand-off - DISABLED for now (holding off until the
  loop-prevention / manual-mode design is actually built: script-enforced
  "[skip ci]" on any auto-commit, plus a local .manual-mode flag file to
  pause the whole auto chain on demand). Uncomment this block once that's
  in place and you're ready to let it run unattended again.

if ($RunCursorAgentAfterPull) {
    Write-Host "== Handing off to Cursor agent =="
    # --force auto-approves whatever the agent decides to run (edits,
    # shell commands, git commit/push) with no prompt in between - that's
    # the whole point of running it unattended here, but it does mean
    # this step can commit and push code changes with nobody watching.
    # Set RunCursorAgentAfterPull to $false in scripts\config.ps1 any
    # time you want pull-artifacts.ps1 to go back to just pulling files.
    $repoRoot = Split-Path $PSScriptRoot -Parent
    $prompt = "A fresh Golden truth Evaluate run just finished. Look at " +
              "pulled/latest/analysis.log and pulled/latest/screenshot.png, " +
              "following this repo's existing validation discipline documented " +
              "in CLAUDE.md. If you find a concrete, evidence-backed recognition " +
              "bug (not just a hunch), fix it, bump versionCode/versionName per " +
              "CLAUDE.md's workflow, and commit + push in the same turn. If " +
              "nothing concrete stands out, just report what you see - don't " +
              "guess at a fix without real evidence."
    Push-Location $repoRoot
    try {
        & $CursorAgentPath -p $prompt --output-format text --force --trust
    } catch {
        Write-Warning "Could not run cursor-agent ('$CursorAgentPath'). Is it installed and on PATH? Try 'cursor-agent --version' (some Windows installs register it as 'agent' instead). Set RunCursorAgentAfterPull to false in scripts\config.ps1 to disable this step."
    } finally {
        Pop-Location
    }
}
#>
