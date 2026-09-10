# Read-only prerequisite check. It does not install, copy, delete, or repair SDK packages.
param(
    [string]$SdkRoot,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) { throw 'Set JAVA_HOME to a full JDK 21, not a bundled JRE.' }
foreach ($tool in 'java.exe', 'javac.exe', 'jlink.exe') {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin/$tool") -PathType Leaf)) {
        throw "Missing JDK tool: $tool in $JavaHome"
    }
}
$release = Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw
if ($release -notmatch 'JAVA_VERSION="21[."]') { throw 'This checkout requires JDK 21.' }
if (-not $SdkRoot) {
    $local = Join-Path $repo 'local.properties'
    if (-not (Test-Path -LiteralPath $local)) { throw 'Provide -SdkRoot or create untracked local.properties.' }
    $line = Get-Content -LiteralPath $local | Where-Object { $_ -match '^sdk\.dir\s*=' } | Select-Object -First 1
    if (-not $line) { throw 'local.properties is missing sdk.dir.' }
    $SdkRoot = ($line -replace '^sdk\.dir\s*=\s*', '').Replace('\:', ':').Replace('\\', '\')
}
foreach ($relative in 'platforms/android-37/android.jar', 'platforms/android-37/core-for-system-modules.jar',
        'build-tools/37.0.0/aapt2.exe', 'build-tools/37.0.0/d8.bat', 'platform-tools/adb.exe') {
    if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot $relative) -PathType Leaf)) {
        throw "Missing required SDK file: $relative in $SdkRoot"
    }
}
Write-Output "JAVA_HOME=$((Resolve-Path -LiteralPath $JavaHome).Path)"
Write-Output "SDK=$((Resolve-Path -LiteralPath $SdkRoot).Path)"
Get-Content -LiteralPath (Join-Path $SdkRoot 'platforms/android-37/source.properties') |
    Select-String 'AndroidVersion.ApiLevel=|Pkg.Revision='
if (Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms/android-37.0')) {
    Write-Warning 'Both android-37 and android-37.0 exist. This check does not establish clean-SDK reproducibility; see docs/build-environment.md.'
}
Write-Output 'Prerequisite files found. Now run gradlew --version and the documented build/tests; this is not build, lint, device, or SDK provenance validation.'
