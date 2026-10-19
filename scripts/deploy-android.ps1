param(
    [string]$Serial = "",
    [switch]$NoLaunch,
    [switch]$SkipBuild,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$buildScript = Join-Path $PSScriptRoot "build-android.ps1"
$installScript = Join-Path $PSScriptRoot "install-android.ps1"

Write-Host "Ziji Android deploy"

if (-not (Test-Path $buildScript)) {
    Write-Host "Missing build script: $buildScript"
    exit 1
}
if (-not (Test-Path $installScript)) {
    Write-Host "Missing install script: $installScript"
    exit 1
}

if ($DryRun) {
    if ($SkipBuild) {
        Write-Host "DryRun: would skip build and use the latest existing debug APK."
    } else {
        Write-Host "DryRun: would run build first."
    }
    Write-Host "DryRun: would run install step."
    if ($Serial) {
        Write-Host "DryRun: target device serial would be $Serial"
    }
    if ($NoLaunch) {
        Write-Host "DryRun: companion settings would not be launched."
    } else {
        Write-Host "DryRun: companion settings would be launched after install."
    }
    exit 0
}

if (-not $SkipBuild) {
    & $buildScript
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build step failed."
        exit $LASTEXITCODE
    }
} else {
    Write-Host "SkipBuild: using the latest existing debug APK."
}

$installArgs = @()
if ($Serial) {
    $installArgs += "-Serial"
    $installArgs += $Serial
}
if ($NoLaunch) {
    $installArgs += "-NoLaunch"
}

& $installScript @installArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "Install step failed."
    exit $LASTEXITCODE
}

Write-Host "Android deploy finished."
