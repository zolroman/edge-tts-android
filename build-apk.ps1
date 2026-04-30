param(
    [ValidateSet("debug", "release", "prerelease")]
    [string]$Variant = "release",

    [switch]$Clean,
    [switch]$Install,
    [string]$OutputDir = "dist"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$Gradle = Join-Path $Root "gradlew.bat"
if (-not (Test-Path $Gradle)) {
    throw "Gradle wrapper not found: $Gradle"
}

$env:GRADLE_USER_HOME = Join-Path $Root ".gradle\user-home"

$variantName = $Variant.Substring(0, 1).ToUpperInvariant() + $Variant.Substring(1)
$assembleTask = ":app:assemble$variantName"

if ($Clean) {
    & $Gradle --no-daemon clean
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle clean failed with exit code $LASTEXITCODE"
    }
}

& $Gradle --no-daemon $assembleTask
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$apkDir = Join-Path $Root "app\build\outputs\apk\$Variant"
$apk = Get-ChildItem -Path $apkDir -Filter "*.apk" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $apk) {
    throw "APK was not found in $apkDir"
}

$resolvedOutputDir = Join-Path $Root $OutputDir
New-Item -ItemType Directory -Path $resolvedOutputDir -Force | Out-Null

$destName = "edge-tts-android-$Variant.apk"
$dest = Join-Path $resolvedOutputDir $destName
Copy-Item -LiteralPath $apk.FullName -Destination $dest -Force

Write-Host "APK ready: $dest"

if ($Install) {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -eq $adb) {
        throw "adb was not found in PATH. Install Android Platform Tools or run without -Install."
    }

    & adb install -r $dest
    if ($LASTEXITCODE -ne 0) {
        throw "adb install failed with exit code $LASTEXITCODE"
    }
}
