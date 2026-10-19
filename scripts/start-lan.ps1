param(
    [int]$Port = 5188,
    [string]$Token = $env:ZIJI_TOKEN,
    [switch]$NoToken,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Get-LanAddresses {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike "127.*" -and
            $_.IPAddress -notlike "169.254.*" -and
            $_.PrefixOrigin -ne "WellKnown"
        } |
        Select-Object -ExpandProperty IPAddress -Unique
}

function New-BridgeToken {
    "ziji-" + [guid]::NewGuid().ToString("N")
}

# 与服务端共用同一个 token 文件。
# 以前这个脚本每次都新生成一个 token 再通过环境变量塞给 node，
# 于是「持久化」被绕过去了：电脑一重启，手机里存的 token 就失效。
$tokenFile = Join-Path $root "data\bridge-token.txt"

function Get-OrCreateBridgeToken {
    if (Test-Path -LiteralPath $tokenFile) {
        $saved = (Get-Content -LiteralPath $tokenFile -Raw -ErrorAction SilentlyContinue)
        if ($saved) {
            $saved = $saved.Trim()
            if ($saved) { return $saved }
        }
    }
    $created = New-BridgeToken
    $parent = Split-Path -Parent $tokenFile
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    Set-Content -LiteralPath $tokenFile -Value $created -Encoding ASCII
    return $created
}

$addresses = @(Get-LanAddresses)
Write-Host "Ziji LAN service"
Write-Host "Port: $Port"

if ($NoToken) {
    $Token = ""
} elseif (-not $Token) {
    $Token = Get-OrCreateBridgeToken
}

if ($Token) {
    $env:ZIJI_TOKEN = $Token
    Write-Host "Token: enabled (saved in data\bridge-token.txt; survives restarts)"
    Write-Host "Bridge token: $Token"
} else {
    Write-Host "Token: disabled; only use this on a trusted LAN"
}

Write-Host ""
if ($addresses.Count -eq 0) {
    Write-Host "No LAN IPv4 address found. Service can start, but your phone may not reach it."
} else {
    Write-Host "Open on phone browser:"
    foreach ($ip in $addresses) {
        Write-Host "  http://${ip}:$Port/"
    }

    Write-Host ""
    Write-Host "Use this in Android companion:"
    foreach ($ip in $addresses) {
        Write-Host "  http://${ip}:$Port/api/capture"
    }

    if ($Token) {
        $encodedToken = [uri]::EscapeDataString($Token)
        Write-Host ""
        Write-Host "Use this token in Android companion:"
        Write-Host "  $Token"
        Write-Host ""
        Write-Host "If the PWA asks for token, open once on phone:"
        foreach ($ip in $addresses) {
            Write-Host "  http://${ip}:$Port/?bridgeToken=$encodedToken"
        }
    }
}

if ($DryRun) {
    Write-Host ""
    Write-Host "DryRun: service not started."
    exit 0
}

Write-Host ""
Write-Host "Starting service. Press Ctrl+C to stop."
Push-Location $root
try {
    if ($NoToken) {
        & node server.js --host 0.0.0.0 --port $Port --no-token
    } else {
        & node server.js --host 0.0.0.0 --port $Port
    }
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
