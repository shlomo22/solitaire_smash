<#
  Fetch the desktop OpenCV native needed to run Evaluate locally.
  -----------------------------------------------------------
    powershell -ExecutionPolicy Bypass -File scripts\fetch-test-opencv.ps1

  Without this, SmashGoldenTruthTest runs with OpenCV disabled
  ("no opencv_java4 in java.library.path"), template matching degrades, and
  the local numbers drift far enough from the device to be misleading -
  occupancy 47 vs the device's 28, missing 21 vs 4.

  Drops a Windows x86_64 opencv_java4.dll into app/src/test/jniLibs, which
  Gradle already puts on the unit-test java.library.path. The file is ~50MB
  and gitignored, so each machine fetches its own copy.

  Source is org.openpnp:opencv, which repackages official OpenCV natives for
  desktop platforms. It is 4.9.0 against the app's 4.10.0 Android AAR, and
  that mismatch is fine here *only* because the one Android-specific entry
  point (org.opencv.android.Utils.bitmapToMat, backed by the NDK-only
  nBitmapToMat2) is replaced on the test classpath by
  app/src/test/java/org/opencv/android/Utils.java. What remains - cvtColor,
  resize, matchTemplate, minMaxLoc, Mat - is stable core/imgproc API.

  Still not covered locally: ML Kit text recognition. Waste-OCR behaviour
  can only be judged on a device.
#>

$ErrorActionPreference = "Stop"

$version = "4.9.0-0"
$url = "https://repo1.maven.org/maven2/org/openpnp/opencv/$version/opencv-$version.jar"
$entryName = "nu/pattern/opencv/windows/x86_64/opencv_java490.dll"

$repoRoot = Split-Path $PSScriptRoot -Parent
$destDir = Join-Path $repoRoot "app\src\test\jniLibs"
$dest = Join-Path $destDir "opencv_java4.dll"

if (Test-Path $dest) {
    Write-Host "Already present: $dest ({0:N1} MB)" -f ((Get-Item $dest).Length / 1MB)
    Write-Host "Delete it first if you want to re-fetch."
    exit 0
}

New-Item -ItemType Directory -Force -Path $destDir | Out-Null
$jar = Join-Path $env:TEMP "openpnp-opencv-$version.jar"

if (-not (Test-Path $jar)) {
    Write-Host "== Downloading $url (~105MB) =="
    Invoke-WebRequest -Uri $url -OutFile $jar -UseBasicParsing
}

Write-Host "== Extracting $entryName =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq $entryName }
    if (-not $entry) { throw "Entry '$entryName' not found in $jar" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
} finally {
    $zip.Dispose()
}

Write-Host ("== Done: {0} ({1:N1} MB) ==" -f $dest, ((Get-Item $dest).Length / 1MB))
Write-Host ""
Write-Host "Now run a local Evaluate with:"
Write-Host '  .\gradlew.bat :app:testDebugUnitTest --tests "*SmashGoldenTruthTest.desktopEvaluatePrintsSameReportAsDevice"'
