$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

function Write-Check($Name, $Ok, $Detail) {
    $status = if ($Ok) { "OK" } else { "MISSING" }
    Write-Host ("[{0}] {1} - {2}" -f $status, $Name, $Detail)
}

function Find-Command($Name) {
    Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Get-LanAddresses {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike "127.*" -and
            $_.IPAddress -notlike "169.254.*" -and
            $_.PrefixOrigin -ne "WellKnown"
        } |
        Select-Object -ExpandProperty IPAddress -Unique
}

Write-Host "Ziji environment check"
Write-Host "Workspace: $root"
Write-Host ""

$node = Find-Command "node"
Write-Check "Node.js" ($null -ne $node) ($(if ($node) { $node.Source } else { "Install Node.js to run the local bridge service" }))

$npm = Find-Command "npm.cmd"
Write-Check "npm.cmd" ($null -ne $npm) ($(if ($npm) { $npm.Source } else { "Install npm.cmd to run project scripts" }))

$java = Find-Command "java"
Write-Check "Java" ($null -ne $java) ($(if ($java) { $java.Source } else { "Java is required for Android companion tests and Gradle builds" }))

$javac = Find-Command "javac"
Write-Check "javac" ($null -ne $javac) ($(if ($javac) { $javac.Source } else { "javac is required for Android companion Java tests" }))

$androidCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $root ".android-sdk"),
    (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
    "C:\Android\Sdk"
) | Where-Object { $_ -and $_.Trim() }

$androidSdk = $androidCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
Write-Check "Android SDK" ($null -ne $androidSdk) ($(if ($androidSdk) { $androidSdk } else { "Not found; install Android Studio or set ANDROID_HOME before building APK" }))

$adb = Find-Command "adb.exe"
if (-not $adb -and $androidSdk) {
    $adbPath = Join-Path $androidSdk "platform-tools\adb.exe"
    if (Test-Path $adbPath) {
        $adb = [pscustomobject]@{ Source = $adbPath }
    }
}
Write-Check "adb.exe" ($null -ne $adb) ($(if ($adb) { $adb.Source } else { "Install Android SDK Platform-Tools before installing APK to phone" }))

$gradlew = Join-Path $root "android-companion\gradlew.bat"
Write-Check "Android Gradle Wrapper" (Test-Path $gradlew) ($(if (Test-Path $gradlew) { $gradlew } else { "Missing android-companion\gradlew.bat" }))

$port = 5188
$listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
Write-Check "Port $port" ($null -eq $listeners) ($(if ($listeners) { "Already used by process $($listeners.OwningProcess -join ',')" } else { "Available for local bridge service" }))

$lanAddresses = @(Get-LanAddresses)
Write-Host ""
if ($lanAddresses.Count -gt 0) {
    Write-Host "Try these URLs on your phone:"
    foreach ($ip in $lanAddresses) {
        Write-Host "  PWA: http://${ip}:$port/"
        Write-Host "  Android companion endpoint: http://${ip}:$port/api/capture"
    }
} else {
    Write-Host "No LAN IPv4 address found. Make sure this computer is connected to Wi-Fi/LAN."
}

Write-Host ""
Write-Host "Useful commands:"
Write-Host "  npm.cmd test"
Write-Host "  npm.cmd run check:ready"
Write-Host "  npm.cmd run start:lan"
Write-Host "  npm.cmd run bridge:preflight -- -Token <token>"
Write-Host "  npm.cmd run verify:bridge -- -Token <token>"
Write-Host "  npm.cmd run bridge:verify-samples -- -Token <token> -Claim"
Write-Host "  npm.cmd run android:setup-sdk -- -AcceptAndroidSdkLicense"
Write-Host "  npm.cmd run android:build"
Write-Host "  npm.cmd run android:install"
Write-Host "  npm.cmd run android:deploy"
Write-Host "  npm.cmd run android:status"
Write-Host "  npm.cmd run android:logs -- -Follow"
Write-Host "  npm.cmd run android:install -- -NoLaunch"
Write-Host "  cd android-companion; .\gradlew.bat assembleDebug"
