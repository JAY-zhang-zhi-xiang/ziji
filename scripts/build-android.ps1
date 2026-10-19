$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$companionDir = Join-Path $root "android-companion"
$gradlew = Join-Path $companionDir "gradlew.bat"

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

Write-Host "Ziji Android APK build"
Write-Host "Project: $companionDir"

if (-not (Test-Path $gradlew)) {
    Write-Host "Missing Gradle Wrapper: $gradlew"
    exit 1
}

$androidSdk = Find-AndroidSdk
if (-not $androidSdk) {
    Write-Host "Android SDK not found."
    Write-Host "Install Android Studio, then run npm.cmd run doctor again."
    Write-Host "Expected locations include ANDROID_HOME, ANDROID_SDK_ROOT, or %LOCALAPPDATA%\Android\Sdk."
    exit 2
}

$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
Write-Host "Android SDK: $androidSdk"

$aapt2 = Join-Path $androidSdk "build-tools\35.0.0\aapt2.exe"
$gradleArgs = @("assembleDebug")
if (Test-Path $aapt2) {
    $gradleArgs += "-Pandroid.aapt2FromMavenOverride=$aapt2"
    Write-Host "AAPT2: $aapt2"
}

Push-Location $companionDir
try {
    # gradle 会把编译警告写到 stderr。脚本头部设了 ErrorActionPreference=Stop，
    # 原生进程的 stderr 输出会被当成 NativeCommandError 直接中断整个构建——
    # 明明只是「source 8 已过时」这种警告，却报成构建失败。
    # 这里临时放宽为 Continue，并把 stderr 一并转发出来。
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & .\gradlew.bat $gradleArgs 2>&1 | ForEach-Object { Write-Host $_ }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Gradle build failed."
        Write-Host "If this is the first run, make sure this computer can reach services.gradle.org."
        exit $LASTEXITCODE
    }

    $apk = Get-ChildItem -Path ".\build\outputs\apk\debug" -Filter "*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($apk) {
        Write-Host "APK built successfully:"
        Write-Host ("  {0}" -f $apk.FullName)
    } else {
        Write-Host "Build finished, but no debug APK was found under build\outputs\apk\debug."
        exit 3
    }
} finally {
    Pop-Location
}
