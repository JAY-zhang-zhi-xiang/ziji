param(
    [switch]$SkipTests,
    [switch]$SkipBridge,
    [string]$Token = "ready-check-token"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

function Run-Step($Name, [scriptblock]$Action) {
    Write-Host ""
    Write-Host "== $Name =="
    & $Action
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FAIL] $Name failed with exit code $LASTEXITCODE"
        exit $LASTEXITCODE
    }
    Write-Host "[OK] $Name"
}

function Start-TempBridge($Port, $DataFile, $TokenValue) {
    Start-Process `
        -FilePath "node" `
        -ArgumentList @("server.js", "--host", "127.0.0.1", "--port", "$Port", "--data", $DataFile, "--token", $TokenValue) `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -PassThru
}

function Wait-BridgeReady($Port) {
    for ($i = 0; $i -lt 40; $i++) {
        try {
            $health = Invoke-RestMethod -Method GET -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 1
            if ($health.ok) { return $true }
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    return $false
}

function Get-AssetVersion($Content, $Asset) {
    $escaped = [regex]::Escape($Asset)
    $match = [regex]::Match($Content, "$escaped\?v=(\d+)")
    if (-not $match.Success) {
        throw "Missing versioned asset reference: $Asset"
    }
    return $match.Groups[1].Value
}

function Test-IntentAction($Node, $ActionName) {
    foreach ($intent in @($Node."intent-filter")) {
        foreach ($action in @($intent.action)) {
            if ($action.GetAttribute("name", "http://schemas.android.com/apk/res/android") -eq $ActionName) {
                return $true
            }
        }
    }
    return $false
}

Write-Host "Ziji readiness check"
Write-Host "Workspace: $root"

Push-Location $root
try {
    if (-not $SkipTests) {
        Run-Step "Full test suite" {
            & npm.cmd test
        }
    } else {
        Write-Host ""
        Write-Host "== Full test suite =="
        Write-Host "SkipTests: skipped."
    }

    Run-Step "Node syntax check" {
        & node --check .\app.js
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & node --check .\parser.js
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & node --check .\server.js
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & node --check .\service-worker.js
    }

    Run-Step "PWA asset version check" {
        $indexContent = Get-Content -Raw -Encoding UTF8 .\index.html
        $appContent = Get-Content -Raw -Encoding UTF8 .\app.js
        $serviceWorkerContent = Get-Content -Raw -Encoding UTF8 .\service-worker.js

        $indexAppVersion = Get-AssetVersion $indexContent "./app.js"
        $cachedAppVersion = Get-AssetVersion $serviceWorkerContent "./app.js"
        if ($indexAppVersion -ne $cachedAppVersion) {
            throw "index.html app.js version v$indexAppVersion does not match service-worker.js v$cachedAppVersion"
        }

        $appParserVersion = Get-AssetVersion $appContent "./parser.js"
        $cachedParserVersion = Get-AssetVersion $serviceWorkerContent "./parser.js"
        if ($appParserVersion -ne $cachedParserVersion) {
            throw "app.js parser.js version v$appParserVersion does not match service-worker.js v$cachedParserVersion"
        }

        $cacheNameMatch = [regex]::Match($serviceWorkerContent, 'ziji-ledger-cache-v(\d+)')
        if (-not $cacheNameMatch.Success) {
            throw "service-worker.js cache name is not versioned"
        }
        if ($cacheNameMatch.Groups[1].Value -ne $indexAppVersion) {
            throw "service-worker.js cache version v$($cacheNameMatch.Groups[1].Value) does not match app.js v$indexAppVersion"
        }
    }

    Run-Step "Android manifest check" {
        $manifest = [xml](Get-Content -Raw -Encoding UTF8 .\android-companion\AndroidManifest.xml)
        $androidNs = "http://schemas.android.com/apk/res/android"
        $activities = @($manifest.manifest.application.activity)
        $services = @($manifest.manifest.application.service)

        $settingsActivity = $activities | Where-Object { $_.GetAttribute("name", $androidNs) -eq ".SettingsActivity" } | Select-Object -First 1
        if (-not $settingsActivity) {
            throw "SettingsActivity is missing from AndroidManifest.xml"
        }
        if (-not (Test-IntentAction $settingsActivity "android.intent.action.MAIN")) {
            throw "SettingsActivity is missing MAIN launcher action"
        }

        $shareActivity = $activities | Where-Object { $_.GetAttribute("name", $androidNs) -eq ".ShareCaptureActivity" } | Select-Object -First 1
        if (-not $shareActivity) {
            throw "ShareCaptureActivity is missing from AndroidManifest.xml"
        }
        if (-not (Test-IntentAction $shareActivity "android.intent.action.SEND")) {
            throw "ShareCaptureActivity is missing SEND action"
        }

        $listenerService = $services | Where-Object { $_.GetAttribute("name", $androidNs) -eq ".ZijiNotificationListenerService" } | Select-Object -First 1
        if (-not $listenerService) {
            throw "ZijiNotificationListenerService is missing from AndroidManifest.xml"
        }
        if (-not (Test-IntentAction $listenerService "android.service.notification.NotificationListenerService")) {
            throw "Notification listener service is missing NotificationListenerService action"
        }
    }

    if (-not $SkipBridge) {
        $port = Get-Random -Minimum 5600 -Maximum 6200
        $dataFile = Join-Path $env:TEMP ("ziji-ready-{0}.json" -f ([guid]::NewGuid().ToString("N")))
        $process = $null
        try {
            $process = Start-TempBridge $port $dataFile $Token
            if (-not (Wait-BridgeReady $port)) {
                Write-Host "[FAIL] Temporary bridge did not start."
                exit 30
            }

            Run-Step "Realistic bridge samples" {
                & npm.cmd run bridge:verify-samples -- -BaseUrl "http://127.0.0.1:$port" -Token $Token -Claim
            }
        } finally {
            if ($process -and -not $process.HasExited) {
                Stop-Process -Id $process.Id -Force
                Start-Sleep -Milliseconds 250
            }
            if (Test-Path $dataFile) {
                Remove-Item -LiteralPath $dataFile -Force
            }
        }
    } else {
        Write-Host ""
        Write-Host "== Realistic bridge samples =="
        Write-Host "SkipBridge: skipped."
    }

    Run-Step "Environment doctor" {
        & npm.cmd run doctor
    }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Readiness check passed for local code and bridge simulation."
Write-Host "Real phone completion still requires Android SDK/adb, same LAN, APK install, notification permission, and real payment notification testing."
