param(
    [switch]$AcceptAndroidSdkLicense,
    [switch]$DryRun,
    [string]$SdkRoot,
    [string]$CommandLineToolsZip,
    [string]$CommandLineToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$companionDir = Join-Path $root "android-companion"
$tmpDir = Join-Path $root ".tmp\android-sdk-setup"
$zipPath = Join-Path $tmpDir "commandlinetools-win-latest.zip"
$extractDir = Join-Path $tmpDir "extract"

if (-not $SdkRoot) {
    $SdkRoot = Join-Path $root ".android-sdk"
}

function Write-Step($Message) {
    Write-Host ""
    Write-Host "== $Message =="
}

function Invoke-Or-Print($CommandLine, [scriptblock]$Action) {
    if ($DryRun) {
        Write-Host "DryRun: $CommandLine"
        return
    }

    & $Action
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

function Get-SdkManagerPath {
    Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
}

function Convert-ToLocalPropertiesPath($Path) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    return $resolved.Replace("\", "/")
}

function Assert-UnderDirectory($Path, $Directory, $Name) {
    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    $resolvedDirectory = [System.IO.Path]::GetFullPath($Directory).TrimEnd("\") + "\"
    if (-not $resolvedPath.StartsWith($resolvedDirectory, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name is outside the expected directory: $resolvedPath"
    }
}

Write-Host "Ziji Android SDK setup"
Write-Host "Workspace: $root"
Write-Host "SDK root: $SdkRoot"
Write-Host "Command-line tools: $CommandLineToolsUrl"
if ($CommandLineToolsZip) {
    Write-Host "Local command-line tools zip: $CommandLineToolsZip"
}

if (-not $DryRun -and -not $AcceptAndroidSdkLicense) {
    Write-Host ""
    Write-Host "Android SDK setup requires accepting Google's Android SDK License Agreement."
    Write-Host "Review it on the official Android Studio download page before continuing:"
    Write-Host "  https://developer.android.com/studio#command-tools"
    Write-Host ""
    Write-Host "Run again with:"
    Write-Host "  npm.cmd run android:setup-sdk -- -AcceptAndroidSdkLicense"
    exit 2
}

$sdkManager = Get-SdkManagerPath
if (-not (Test-Path $sdkManager)) {
    if ($CommandLineToolsZip) {
        Write-Step "Use local command-line tools zip"
        Invoke-Or-Print "Copy local zip to `"$zipPath`"" {
            $resolvedZip = Resolve-Path -LiteralPath $CommandLineToolsZip -ErrorAction SilentlyContinue
            if (-not $resolvedZip) {
                throw "Local command-line tools zip not found: $CommandLineToolsZip"
            }
            New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
            Copy-Item -LiteralPath $resolvedZip.Path -Destination $zipPath -Force
        }
    } else {
        Write-Step "Download command-line tools"
        Invoke-Or-Print "curl.exe -L --fail --retry 5 --retry-all-errors -o `"$zipPath`" `"$CommandLineToolsUrl`"" {
            New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
            curl.exe -L --fail --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 30 -o $zipPath $CommandLineToolsUrl
            if ($LASTEXITCODE -ne 0) {
                Write-Host ""
                Write-Host "Download failed. If the network keeps resetting, download this file in a browser:"
                Write-Host "  $CommandLineToolsUrl"
                Write-Host "Then run:"
                Write-Host "  npm.cmd run android:setup-sdk -- -AcceptAndroidSdkLicense -CommandLineToolsZip <path-to-zip>"
            }
        }
    }

    Write-Step "Extract command-line tools"
    Invoke-Or-Print "Expand-Archive `"$zipPath`" `"$extractDir`"" {
        if (Test-Path $extractDir) {
            Assert-UnderDirectory $extractDir (Join-Path $root ".tmp") "Extract directory"
            Remove-Item -LiteralPath $extractDir -Recurse -Force
        }
        Expand-Archive -LiteralPath $zipPath -DestinationPath $extractDir -Force

        $sourceDir = Join-Path $extractDir "cmdline-tools"
        if (-not (Test-Path $sourceDir)) {
            throw "Downloaded archive did not contain cmdline-tools."
        }

        $latestDir = Join-Path $SdkRoot "cmdline-tools\latest"
        if (Test-Path $latestDir) {
            throw "Target already exists but sdkmanager was not found: $latestDir"
        }

        New-Item -ItemType Directory -Force -Path $latestDir | Out-Null
        Copy-Item -Path (Join-Path $sourceDir "*") -Destination $latestDir -Recurse -Force
    }
} else {
    Write-Host "sdkmanager already exists: $sdkManager"
}

$sdkManager = Get-SdkManagerPath
Write-Step "Install required SDK packages"
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$packages = @("platform-tools", "platforms;android-35", "build-tools;35.0.0")
$packageText = $packages -join " "
$yesInput = ("y`n" * 40)

Invoke-Or-Print "sdkmanager --licenses" {
    $yesInput | & $sdkManager "--sdk_root=$SdkRoot" --licenses
}

Invoke-Or-Print "sdkmanager $packageText" {
    $yesInput | & $sdkManager "--sdk_root=$SdkRoot" $packages
}

Write-Step "Write Gradle local.properties"
Invoke-Or-Print "Set android-companion/local.properties sdk.dir" {
    $sdkPath = Convert-ToLocalPropertiesPath $SdkRoot
    Set-Content -LiteralPath (Join-Path $companionDir "local.properties") -Encoding ASCII -Value "sdk.dir=$sdkPath"
}

Write-Host ""
Write-Host "Android SDK setup finished."
Write-Host "Next:"
Write-Host "  npm.cmd run doctor"
Write-Host "  npm.cmd run android:build"
