param(
    [string]$BaseUrl = "http://127.0.0.1:5188",
    [string]$Token = $env:ZIJI_TOKEN,
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

function Decode-Utf8Base64($Value) {
    [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($Value))
}

function Sample-Text($Base64, $Stamp) {
    (Decode-Utf8Base64 $Base64).Replace("{stamp}", $Stamp)
}

function Assert-Capture($Name, $Capture, $ExpectedAmount, $ExpectedAccount) {
    if (-not $Capture) {
        Write-Fail "$Name did not return a stored capture." 20
    }

    $amount = 0
    if ($Capture.parsed -and $Capture.parsed.amount) {
        $amount = [double]$Capture.parsed.amount
    }
    if ([Math]::Abs($amount - [double]$ExpectedAmount) -gt 0.001) {
        Write-Fail "$Name parsed amount mismatch. Expected $ExpectedAmount, got $amount." 21
    }

    $account = ""
    if ($Capture.parsed -and $Capture.parsed.account) {
        $account = [string]$Capture.parsed.account
    }
    if ($account -ne $ExpectedAccount) {
        Write-Fail "$Name parsed account mismatch. Expected $ExpectedAccount, got $account." 22
    }

    Write-Host ("[OK] {0}: amount={1}, account={2}" -f $Name, $amount, $account)
}

$ApiBase = Normalize-BaseUrl $BaseUrl
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$alipayAccount = Decode-Utf8Base64 "5pSv5LuY5a6d"
$wechatAccount = Decode-Utf8Base64 "5b6u5L+h"
$bankAccount = Decode-Utf8Base64 "6ZO26KGM5Y2h"
$samples = @(
    @{
        name = "alipay"
        source = "android-notification"
        text = Sample-Text "5pSv5LuY5a6dIDA35pyIMDfml6UgMTI6MzEg5ZCRIOiHquiusOagt+acrOWSluWVoXtzdGFtcH0g5LuY5qy+IDMyLjAw5YWDIOS7mOasvuaWueW8jyDoirHlkZc=" $stamp
        amount = 32.00
        account = $alipayAccount
    },
    @{
        name = "wechat"
        source = "android-notification"
        text = Sample-Text "5b6u5L+h5pSv5LuY5pS25qy+5Yqp5omL77ya5L2g5bey5ZCRIOiHquiusOagt+acrOWkluWNlntzdGFtcH0g5pSv5LuYIMKlNDYuODDvvIzmlK/ku5jmlrnlvI8g6Zu26ZKx77yM5pe26Ze0IDIwMjYtMDctMDcgMTg6NDI=" $stamp
        amount = 46.80
        account = $wechatAccount
    },
    @{
        name = "bank-card"
        source = "android-notification"
        text = Sample-Text "44CQ5oub5ZWG6ZO26KGM44CR5oKo5bC+5Y+3IDg4OTkg55qE5L+h55So5Y2hIDA35pyIMDfml6UgMjA6MTcg5ZyoIOiHquiusOagt+acrOi2heW4gntzdGFtcH0g5raI6LS55Lq65rCR5biBIDEyOC42MCDlhYPjgII=" $stamp
        amount = 128.60
        account = $bankAccount
    },
    @{
        name = "unionpay"
        source = "android-notification"
        text = Sample-Text "5LqR6Zeq5LuYIOaUr+S7mOaIkOWKn++8muaCqOWQkSDoh6rorrDmoLfmnKzkvr/liKnlupd7c3RhbXB9IOaUr+S7mCDCpTg2LjUw" $stamp
        amount = 86.50
        account = $bankAccount
    }
)

Write-Host "Ziji realistic bridge verification"
Write-Host "Bridge: $ApiBase"
Write-Host "Samples: $($samples.Count)"
Write-Host "Claim after verify: $($Claim.IsPresent)"
Write-Host ""

try {
    $health = Invoke-BridgeJson "GET" "/api/health"
} catch {
    Write-Fail "Cannot reach $ApiBase/api/health. Start the bridge first." 2
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

$createdCaptures = @()
foreach ($sample in $samples) {
    $payload = @{
        source = $sample.source
        text = $sample.text
    }

    try {
        $created = Invoke-BridgeJson "POST" "/api/capture" $payload
    } catch {
        Write-Fail "$($sample.name) POST /api/capture failed." 6
    }

    if ($created.added -lt 1) {
        Write-Fail "$($sample.name) was not added. duplicates=$($created.duplicates)" 7
    }

    $capture = @($created.captures)[0]
    Assert-Capture $sample.name $capture $sample.amount $sample.account
    $createdCaptures += $capture
}

try {
    $pendingResponse = Invoke-BridgeJson "GET" "/api/captures"
} catch {
    Write-Fail "GET /api/captures failed after sample posts." 8
}

$pending = @($pendingResponse.captures)
foreach ($capture in $createdCaptures) {
    $match = $pending | Where-Object { $_.id -eq $capture.id } | Select-Object -First 1
    if (-not $match) {
        Write-Fail "Stored capture was not found in pending queue: $($capture.id)" 9
    }
}
Write-Host "[OK] All sample captures are pending"

if ($Claim) {
    foreach ($capture in $createdCaptures) {
        $escapedId = [uri]::EscapeDataString($capture.id)
        try {
            $claimed = Invoke-BridgeJson "POST" "/api/captures/$escapedId/claim"
        } catch {
            Write-Fail "Failed to claim sample capture: $($capture.id)" 10
        }
        if (-not $claimed.ok) {
            Write-Fail "Claim response did not return ok=true for $($capture.id)" 11
        }
    }
    Write-Host "[OK] Sample captures claimed and cleaned up"
} else {
    Write-Host "[OK] Sample captures left pending so the PWA can import them"
}

Write-Host ""
Write-Host "Realistic bridge verification passed."
