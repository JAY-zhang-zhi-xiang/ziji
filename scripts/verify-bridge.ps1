param(
    [string]$BaseUrl = "http://127.0.0.1:5188",
    [string]$Token = $env:ZIJI_TOKEN,
    [string]$Source = "android-notification",
    [string]$Text = "",
    [switch]$Claim
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

function Invoke-BridgeJson($Method, $Path, $Body = $null) {
    $headers = @{}
    if ($Token) {
        $headers["X-Ziji-Token"] = $Token
    }

    $uri = "$ApiBase$Path"
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
    }

    $json = $Body | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes
}

function Write-Fail($Message, $ExitCode) {
    Write-Host "[FAIL] $Message"
    exit $ExitCode
}

if (-not $Text) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $Text = "Alipay $stamp paid ZijiBridgeTestCoffee CNY 88.88 by balance"
}

$ApiBase = Normalize-BaseUrl $BaseUrl

Write-Host "Ziji bridge verification"
Write-Host "Bridge: $ApiBase"
Write-Host "Source: $Source"
Write-Host "Claim after verify: $($Claim.IsPresent)"
Write-Host ""

try {
    $health = Invoke-BridgeJson "GET" "/api/health"
} catch {
    Write-Fail "Cannot reach $ApiBase/api/health. Start the bridge with npm.cmd start or npm.cmd run start:lan." 2
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

Write-Host "[OK] Health check passed"

$payload = @{
    source = $Source
    text = $Text
}

try {
    $created = Invoke-BridgeJson "POST" "/api/capture" $payload
} catch {
    Write-Fail "POST /api/capture failed. Check the endpoint and token." 6
}

if (($created.added -lt 1) -and ($created.duplicates -lt 1)) {
    Write-Fail "Capture was neither added nor recognized as a duplicate." 7
}

Write-Host ("[OK] Capture POST accepted: added={0}, duplicates={1}" -f $created.added, $created.duplicates)

try {
    $pendingResponse = Invoke-BridgeJson "GET" "/api/captures"
} catch {
    Write-Fail "GET /api/captures failed. Check the token used by the PWA/companion." 8
}

$pending = @($pendingResponse.captures)
$match = $pending | Where-Object { $_.text -eq $Text } | Select-Object -First 1

if (-not $match) {
    Write-Fail "The test capture was not found in the pending queue." 9
}

$amount = 0
if ($match.parsed -and $match.parsed.amount) {
    $amount = [double]$match.parsed.amount
}

if ($amount -le 0) {
    Write-Fail "The pending capture was stored, but parser did not detect a positive amount." 10
}

Write-Host ("[OK] Pending queue contains capture id={0}, amount={1}" -f $match.id, $amount)

if ($Claim) {
    $escapedId = [uri]::EscapeDataString($match.id)
    try {
        $claimed = Invoke-BridgeJson "POST" "/api/captures/$escapedId/claim"
    } catch {
        Write-Fail "POST /api/captures/:id/claim failed." 11
    }

    if (-not $claimed.ok) {
        Write-Fail "Claim response did not return ok=true." 12
    }

    Write-Host "[OK] Test capture claimed and cleaned up"
} else {
    Write-Host "[OK] Test capture left pending so the PWA can import it"
}

Write-Host ""
Write-Host "Bridge verification passed."
