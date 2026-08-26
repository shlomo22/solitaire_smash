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
$AndroidSdkPath = "C:\Users\shlomob\AppData\Local\Android\Sdk" # confirmed from local.properties' sdk.dir - the runner service can't see local.properties (it's gitignored) or your user PATH, so both ANDROID_HOME and adb are pinned to this explicitly below
$AdbPath        = Join-Path $AndroidSdkPath "platform-tools\adb.exe"
$GradlewPath    = Join-Path $PSScriptRoot "..\gradlew.bat"    # adjust if scripts/ is not directly under the repo root

# Gradle needs to find the SDK without local.properties (that file is
# gitignored, so the runner's fresh checkout never has it) and without
# relying on a System environment variable (those need admin rights to
# set, and may not even be visible to whatever account the runner
# service runs as). Setting it here, in-process, sidesteps both issues.
$env:ANDROID_HOME     = $AndroidSdkPath
$env:ANDROID_SDK_ROOT = $AndroidSdkPath
