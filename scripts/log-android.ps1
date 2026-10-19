param(
    [string]$Serial = "",
    [int]$Lines = 200,
    [switch]$Clear,
    [switch]$Follow,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

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

Write-Host "Ziji Android logs"

$adb = Find-Adb
if (-not $adb) {
    Write-Host "adb.exe not found."
    Write-Host "Install Android SDK Platform-Tools, then run npm.cmd run doctor again."
    exit 2
}
Write-Host "adb: $adb"

if ($DryRun) {
    Write-Host "DryRun: would select a connected device and read logcat for ZijiLedger."
    if ($Clear) { Write-Host "DryRun: would clear logcat first." }
    if ($Follow) { Write-Host "DryRun: would follow logs continuously." }
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

if ($Clear) {
    & $adb -s $Serial logcat -c
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Failed to clear logcat."
        exit $LASTEXITCODE
    }
    Write-Host "Logcat cleared."
}

$count = [Math]::Max(1, $Lines)
$logcatArgs = @("-s", $Serial, "logcat", "-v", "time")
if (-not $Follow) {
    $logcatArgs += "-d"
    $logcatArgs += "-t"
    $logcatArgs += $count.ToString()
}
$logcatArgs += "ZijiLedger:I"
$logcatArgs += "AndroidRuntime:E"
$logcatArgs += "*:S"

Write-Host "Reading ZijiLedger logs..."
& $adb @logcatArgs
exit $LASTEXITCODE
