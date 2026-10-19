param(
    [string]$Serial = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$packageName = "com.ziji.ledger"
$settingsActivity = "com.ziji.ledger/.SettingsActivity"
$listenerComponent = "com.ziji.ledger/com.ziji.ledger.ZijiNotificationListenerService"

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

function Get-ConnectedDevices($Adb) {
    $lines = & $Adb devices
    $lines |
        Where-Object { $_ -match "\tdevice$" } |
        ForEach-Object { ($_ -split "\s+")[0] }
}

function Write-Check($Name, $Ok, $Detail) {
    $status = if ($Ok) { "OK" } else { "MISSING" }
    Write-Host ("[{0}] {1} - {2}" -f $status, $Name, $Detail)
}

Write-Host "Ziji Android status"

$adb = Find-Adb
if (-not $adb) {
    Write-Host "adb.exe not found."
    Write-Host "Install Android SDK Platform-Tools, then run npm.cmd run doctor again."
    exit 2
}
Write-Host "adb: $adb"

if ($DryRun) {
    Write-Host "DryRun: would select a device and check package install plus notification access."
    exit 0
}

$devices = @(Get-ConnectedDevices $adb)
if ($Serial) {
    if ($devices -notcontains $Serial) {
        Write-Host "Device serial not found: $Serial"
        Write-Host "Connected devices: $($devices -join ', ')"
        exit 3
    }
} elseif ($devices.Count -eq 0) {
    Write-Host "No Android device is connected."
    Write-Host "Enable USB debugging on the phone, connect USB, then approve the RSA prompt."
    exit 4
} elseif ($devices.Count -gt 1) {
    Write-Host "Multiple Android devices are connected."
    Write-Host "Pass -Serial <device-serial>. Connected devices: $($devices -join ', ')"
    exit 5
} else {
    $Serial = $devices[0]
}

Write-Host "Device: $Serial"

$model = ((& $adb -s $Serial shell getprop ro.product.model) -join "").Trim()
$androidVersion = ((& $adb -s $Serial shell getprop ro.build.version.release) -join "").Trim()
if ($model) { Write-Host "Model: $model" }
if ($androidVersion) { Write-Host "Android: $androidVersion" }

$packages = (& $adb -s $Serial shell pm list packages $packageName) -join "`n"
$installed = $packages -match "package:$packageName"
Write-Check "Companion installed" $installed ($(if ($installed) { $packageName } else { "Run npm.cmd run android:deploy" }))

$enabledListeners = ((& $adb -s $Serial shell settings get secure enabled_notification_listeners) -join "").Trim()
$notificationAccess = $enabledListeners.Contains($listenerComponent)
Write-Check "Notification access" $notificationAccess ($(if ($notificationAccess) { "enabled" } else { "Open companion settings, then tap notification access button" }))

Write-Host ""
Write-Host "Useful next commands:"
if (-not $installed) {
    Write-Host "  npm.cmd run android:deploy"
} else {
    Write-Host "  adb -s $Serial shell am start -n $settingsActivity"
}
Write-Host "  npm.cmd run android:logs -- -Serial $Serial -Follow"
Write-Host "  npm.cmd run bridge:preflight -- -Token <token>"

if (-not $installed -or -not $notificationAccess) {
    exit 1
}

Write-Host "Android status looks ready for notification testing."
