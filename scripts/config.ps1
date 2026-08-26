<#
  Shared config for the Solitaire Smash automation scripts.
  Edit these once; both build-install-run.ps1 and pull-artifacts.ps1
  dot-source this file.
#>

$PackageName   = "com.personal.solitaireassistant"            # confirmed from app/build.gradle.kts applicationId
$MainActivity  = ".MainActivity"                              # confirmed from AndroidManifest.xml
$AnalysisLogRelPath = "files/logs/analysis.log"               # confirmed via Android Studio Device Explorer: /data/data/<package>/files/logs/analysis.log
                                                                # (internal, private storage - pulled via "adb exec-out run-as" since it's a debug build)
$OutputRoot    = Join-Path $PSScriptRoot "..\pulled"          # where pulled artifacts land locally (gitignored)
$AdbPath       = "adb"                                        # must resolve on PATH; set a full path if a service account can't see it, e.g.:
                                                                # "C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$GradlewPath   = Join-Path $PSScriptRoot "..\gradlew.bat"     # adjust if scripts/ is not directly under the repo root
