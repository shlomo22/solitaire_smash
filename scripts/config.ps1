<#
  Shared config for the Solitaire Smash automation scripts.
  Edit these once; both build-install-run.ps1 and pull-artifacts.ps1
  dot-source this file.
#>

$PackageName   = "com.personal.solitaireassistant"            # confirmed from app/build.gradle.kts applicationId
$MainActivity  = ".MainActivity"                              # confirmed from AndroidManifest.xml
$AnalysisLogRelPath = "files/logs/analysis.log"               # confirmed via Android Studio Device Explorer: /data/data/<package>/files/logs/analysis.log
                                                                # (internal, private storage - pulled via "adb exec-out run-as" since it's a debug build)
$OutputRoot     = Join-Path $PSScriptRoot "..\pulled"         # where pulled artifacts land locally (gitignored)

# Default SDK location: Android Studio's standard per-user install path.
# This resolves correctly on ANY machine with a normal install, since
# $env:LOCALAPPDATA always expands to "C:\Users\<whoever's logged in>\AppData\Local"
# for the account actually running this script - no username needs to be
# hardcoded here, so this line works unchanged on your laptop, your office
# desktop, or any future machine.
$AndroidSdkPath = Join-Path $env:LOCALAPPDATA "Android\Sdk"

# If one particular machine's SDK lives somewhere non-standard, don't edit
# the line above (it's shared/committed and used by every machine) -
# instead copy scripts\local-machine.ps1.example to scripts\local-machine.ps1
# (gitignored, stays local to that one machine only) and set $AndroidSdkPath
# there. Loading it here, after the default, lets it override just for
# whichever machine has that file.
$localMachineConfig = Join-Path $PSScriptRoot "local-machine.ps1"
if (Test-Path $localMachineConfig) {
    . $localMachineConfig
}

$AdbPath        = Join-Path $AndroidSdkPath "platform-tools\adb.exe"
$GradlewPath    = Join-Path $PSScriptRoot "..\gradlew.bat"    # adjust if scripts/ is not directly under the repo root

# Gradle needs to find the SDK without local.properties (that file is
# gitignored, so the runner's fresh checkout never has it) and without
# relying on a System environment variable (those need admin rights to
# set, and may not even be visible to whatever account the runner
# service runs as). Setting it here, in-process, sidesteps both issues.
$env:ANDROID_HOME     = $AndroidSdkPath
$env:ANDROID_SDK_ROOT = $AndroidSdkPath

# Cursor CLI hand-off, used by pull-artifacts.ps1 after a successful pull.
$RunCursorAgentAfterPull = $false   # the call itself is commented out in pull-artifacts.ps1 for now too - flip both back once the loop-prevention/manual-mode design is built
$CursorAgentPath         = "cursor-agent"   # verify this matches what your install actually put on PATH - some Windows installs register it as "agent" instead; run "cursor-agent --version" (or "agent --version") to check
