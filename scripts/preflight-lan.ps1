param(
    [int]$Port = 5188,
    [string]$BaseUrl = "",
    [string]$Token = $env:ZIJI_TOKEN
)

$ErrorActionPreference = "Stop"

function Normalize-BaseUrl($Url) {
    $value = $Url.Trim().TrimEnd("/")
    if ($value.EndsWith("/api/capture")) {
        return $value.Substring(0, $value.Length - "/api/capture".Length)
    }
    if ($value.EndsWith("/api")) {
        return $value.Substring(0, $value.Length - "/api".Length)
    }
    return $value
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

function Invoke-BridgeHealth($Url) {
    $headers = @{}
    if ($Token) {
        $headers["X-Ziji-Token"] = $Token
    }
    Invoke-RestMethod -Method GET -Uri "$Url/api/health" -Headers $headers
}

function Write-Fail($Message, $ExitCode) {
    Write-Host "[FAIL] $Message"
    exit $ExitCode
}

if (-not $BaseUrl) {
    $BaseUrl = "http://127.0.0.1:$Port"
}
$ApiBase = Normalize-BaseUrl $BaseUrl
$EffectivePort = $Port
try {
    $uri = [uri]$ApiBase
    if ($uri.Port -gt 0) {
        $EffectivePort = $uri.Port
    }
} catch {
    $EffectivePort = $Port
}

Write-Host "Ziji LAN preflight"
Write-Host "Bridge: $ApiBase"

try {
    $health = Invoke-BridgeHealth $ApiBase
} catch {
    Write-Fail "Cannot reach bridge health endpoint. Start service with npm.cmd run start:lan first." 2
}

if (-not $health.ok) {
    Write-Fail "Bridge health check did not return ok=true." 3
}

if ($health.requiresToken -and -not $Token) {
    Write-Fail "Bridge requires a token. Pass -Token or set ZIJI_TOKEN." 4
}

if ($health.requiresToken -and $Token -and ($health.PSObject.Properties.Name -contains "authorized") -and -not $health.authorized) {
    Write-Fail "Bridge token check failed. The token is missing or incorrect." 5
}

Write-Host "[OK] Bridge health and token check passed"

$addresses = @(Get-LanAddresses)
if ($addresses.Count -eq 0) {
    Write-Host "[WARN] No LAN IPv4 address found. Phone may not reach this computer."
} else {
    Write-Host ""
    Write-Host "Open on phone browser:"
    foreach ($ip in $addresses) {
        Write-Host "  http://${ip}:$EffectivePort/"
    }

    Write-Host ""
    Write-Host "Use this in Android companion endpoint:"
    foreach ($ip in $addresses) {
        Write-Host "  http://${ip}:$EffectivePort/api/capture"
    }

    if ($Token) {
        $encodedToken = [uri]::EscapeDataString($Token)
        Write-Host ""
        Write-Host "Use this token in Android companion:"
        Write-Host "  $Token"
        Write-Host ""
        Write-Host "If the PWA asks for token, open once on phone:"
        foreach ($ip in $addresses) {
            Write-Host "  http://${ip}:$EffectivePort/?bridgeToken=$encodedToken"
        }
    }
}

Write-Host ""
Write-Host "Next checks:"
Write-Host "  npm.cmd run verify:bridge -- -BaseUrl $ApiBase -Token <token> -Claim"
Write-Host "  npm.cmd run android:logs -- -Follow"
Write-Host "LAN preflight finished."
