param(
    [string]$ApkPath = "",
    [string]$Serial = "",
    [switch]$NoLaunch,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$companionDir = Join-Path $root "android-companion"
$defaultApkDir = Join-Path $companionDir "build\outputs\apk\debug"
$settingsActivity = "com.ziji.ledger/.SettingsActivity"

function Find-AndroidSdk {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $root ".android-sdk"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
        "C:\Android\Sdk"
    ) | Where-Object { $_ -and $_.Trim() }

    $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

function Find-Adb {
    $command = Get-Command "adb.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }

    $sdk = Find-AndroidSdk
    if ($sdk) {
        $candidate = Join-Path $sdk "platform-tools\adb.exe"
        if (Test-Path $candidate) { return $candidate }
    }

    return ""
}

function Find-Apk {
    if ($ApkPath) {
        return (Resolve-Path -LiteralPath $ApkPath -ErrorAction SilentlyContinue).Path
    }

    Get-ChildItem -Path $defaultApkDir -Filter "*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 |
        ForEach-Object { $_.FullName }
}

function Get-ConnectedDevices($Adb) {
    $lines = & $Adb devices
    $lines |
        Where-Object { $_ -match "\tdevice$" } |
        ForEach-Object { ($_ -split "\s+")[0] }
}

Write-Host "Ziji Android APK install"
Write-Host "Project: $companionDir"

$adb = Find-Adb
if (-not $adb) {
    Write-Host "adb.exe not found."
    Write-Host "Install Android Studio / Android SDK Platform-Tools, then run npm.cmd run doctor again."
    exit 2
}
Write-Host "adb: $adb"

$apk = Find-Apk
if (-not $apk) {
    Write-Host "Debug APK not found."
    Write-Host "Run npm.cmd run android:build first, or pass -ApkPath <path-to-apk>."
    exit 3
}
Write-Host "APK: $apk"

if ($DryRun) {
    Write-Host "DryRun: adb install was not executed."
    if (-not $NoLaunch) {
        Write-Host "DryRun: would launch $settingsActivity after install."
    }
    exit 0
}

$devices = @(Get-ConnectedDevices $adb)
if ($Serial) {
    if ($devices -notcontains $Serial) {
        Write-Host "Device serial not found: $Serial"
        Write-Host "Connected devices: $($devices -join ', ')"
        exit 4
    }
} elseif ($devices.Count -eq 0) {
    Write-Host "No Android device is connected."
    Write-Host "Enable USB debugging on the phone, connect USB, then approve the RSA prompt."
    exit 5
} elseif ($devices.Count -gt 1) {
    Write-Host "Multiple Android devices are connected."
    Write-Host "Pass -Serial <device-serial>. Connected devices: $($devices -join ', ')"
    exit 6
} else {
    $Serial = $devices[0]
}

Write-Host "Installing to device: $Serial"
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) {
    Write-Host "adb install failed."
    exit $LASTEXITCODE
}

Write-Host "APK installed successfully."

if (-not $NoLaunch) {
    Write-Host "Launching companion settings..."
    & $adb -s $Serial shell am start -n $settingsActivity | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Host "APK installed, but launching settings activity failed."
        exit $LASTEXITCODE
    }
}

Write-Host "Next: set endpoint/token, tap Check bridge connection, then enable notification access."
