param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[0-9a-f]{64}$")]
    [string]$ExpectedSerialSha256,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[0-9a-f]{64}$")]
    [string]$ExpectedBuildFingerprintSha256
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputPath) {
    throw "refusing to overwrite $outputPath"
}
if ($Serial -match "^emulator-[0-9]+$") {
    throw "warm-retention capture requires physical hardware"
}
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adbPath = if ($null -ne $adbCommand) {
    $adbCommand.Source
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
    throw "adb is not available on PATH or in the standard Android SDK location"
}

function Invoke-AiosAdb {
    param([string[]]$Arguments)
    $value = & $adbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' '): $($value -join ' ')"
    }
    return $value
}

function Get-AiosSha256 {
    param([string]$Value)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return (($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)) |
            ForEach-Object { $_.ToString("x2") }) -join "")
    }
    finally { $algorithm.Dispose() }
}

function Get-AiosProperty {
    param([string]$Name)
    return ((Invoke-AiosAdb -Arguments @("shell", "getprop", $Name)) -join "`n").Trim()
}

if (((Invoke-AiosAdb -Arguments @("get-state")) -join "`n").Trim() -ne "device") {
    throw "the build-bound physical target is not ready"
}
$observedSerial = ((Invoke-AiosAdb -Arguments @("get-serialno")) -join "`n").Trim()
$observedFingerprint = Get-AiosProperty -Name "ro.build.fingerprint"
if ((Get-AiosSha256 -Value $observedSerial) -ne $ExpectedSerialSha256) {
    throw "attached physical serial does not match the authorized target"
}
if ((Get-AiosSha256 -Value $observedFingerprint) -ne $ExpectedBuildFingerprintSha256) {
    throw "attached build fingerprint does not match the authorized build"
}
if ((Get-AiosProperty -Name "ro.product.device") -ne "tegu" -or
    -not (Get-AiosProperty -Name "ro.aios.version") -or
    (Get-AiosProperty -Name "ro.debuggable") -ne "1") {
    throw "warm-retention capture requires the authorized Pixel 9a AIOS userdebug build"
}

New-Item -ItemType Directory -Path $outputPath | Out-Null
Invoke-AiosAdb -Arguments @("root") | Out-Null
Invoke-AiosAdb -Arguments @("wait-for-device") | Out-Null
foreach ($package in @(
        "com.aios.modelbroker",
        "com.aios.runtime.litertlm",
        "com.aios.runtime.whispercpp",
        "com.aios.runtime.sherpatts")) {
    Invoke-AiosAdb -Arguments @("shell", "am", "force-stop", $package) | Out-Null
}

$captureScript = Join-Path $PSScriptRoot "capture-realtime-smoke.ps1"
$coldPath = Join-Path $outputPath "cold.json"
$warmPath = Join-Path $outputPath "warm.json"
& $captureScript -Output $coldPath -Serial $Serial -Mode single
if ($LASTEXITCODE -ne 0) { throw "cold model capture failed" }
& $captureScript -Output $warmPath -Serial $Serial -Mode single
if ($LASTEXITCODE -ne 0) { throw "warm model capture failed" }

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if ($null -eq $pythonCommand) {
    $pythonCommand = Get-Command python3 -ErrorAction SilentlyContinue
}
if ($null -eq $pythonCommand) { throw "Python 3 is not available on PATH" }
$evaluator = Join-Path $repositoryRoot "tools\evaluate_warm_retention.py"
$evidencePath = Join-Path $outputPath "evaluation.json"
& $pythonCommand.Source $evaluator --cold $coldPath --warm $warmPath --output $evidencePath
if ($LASTEXITCODE -ne 0) { throw "warm-retention evaluation failed" }

Write-Output "Captured build/device-bound warm-retention evidence at $outputPath"
