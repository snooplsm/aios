param(
    [Parameter(Mandatory = $true)]
    [string]$Output,

    [string]$Serial = "",

    [ValidateSet("full", "audio", "single")]
    [string]$Mode = "full"
)

$ErrorActionPreference = "Stop"
$outputPath = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $outputPath) {
    throw "refusing to overwrite $outputPath"
}
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    throw "adb is not available on PATH"
}
$adbArguments = @()
if ($Serial) { $adbArguments += @("-s", $Serial) }

function Invoke-AiosAdb {
    param([string[]]$Arguments)
    $value = & $adbCommand.Source @adbArguments @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
    return $value
}

function Get-AiosProperty {
    param([string]$Name)
    return ((Invoke-AiosAdb -Arguments @("shell", "getprop", $Name)) -join "`n").Trim()
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

if (((Invoke-AiosAdb -Arguments @("get-state")) -join "`n").Trim() -ne "device") {
    throw "adb target is not ready"
}
if ((Get-AiosProperty -Name "ro.debuggable") -ne "1" -or
    -not (Get-AiosProperty -Name "ro.aios.version")) {
    throw "realtime smoke requires a debuggable AIOS build"
}
$fingerprint = Get-AiosProperty -Name "ro.build.fingerprint"
$codename = Get-AiosProperty -Name "ro.product.device"
$memoryText = (Invoke-AiosAdb -Arguments @("shell", "cat", "/proc/meminfo")) -join "`n"
$memoryMatch = [regex]::Match($memoryText, "(?m)^MemTotal:\s+(\d+)\s+kB\s*$")
if (-not $memoryMatch.Success) { throw "cannot read device total RAM" }

$modeConfig = switch ($Mode) {
    "audio" {
        @{
            TestMethod = "runAudioRealtimeSmoke"
            ExpectedMode = "audio_realtime_smoke"
            ExpectedRoles = 2
            EvidenceKind = "pixel_aios_audio_realtime_smoke"
        }
    }
    "single" {
        @{
            TestMethod = "runSingleModelDiagnostic"
            ExpectedMode = "single_model_diagnostic"
            ExpectedRoles = 4
            EvidenceKind = "pixel_aios_single_model_diagnostic"
        }
    }
    default {
        @{
            TestMethod = "runRealtimeSmoke"
            ExpectedMode = "realtime_smoke"
            ExpectedRoles = 3
            EvidenceKind = "pixel_aios_realtime_model_smoke"
        }
    }
}
$testMethod = $modeConfig.TestMethod
$expectedMode = $modeConfig.ExpectedMode
$expectedRoles = $modeConfig.ExpectedRoles
$evidenceKind = $modeConfig.EvidenceKind

# The selected tags contain lifecycle/timing metadata and errors, never prompt text or PCM.
Invoke-AiosAdb -Arguments @("logcat", "-b", "all", "-c") | Out-Null
$instrumentationOutput = Invoke-AiosAdb -Arguments @(
    "shell", "am", "instrument", "-w", "-r",
    "-e", "class",
    "com.aios.modelbenchmark.ModelAdmissionBenchmarkTest#$testMethod",
    "com.aios.modelbenchmark.tests/androidx.test.runner.AndroidJUnitRunner"
)
$instrumentationText = $instrumentationOutput -join "`n"
$diagnosticPatterns = @(
    "AiosModelDiagnostic",
    "AiosRemoteRuntime",
    "AiosLiteRtLmRuntime",
    "AiosTtsRuntime",
    "AiosWhisperRuntime",
    "lowmemorykiller",
    "lmkd",
    "OutOfMemory",
    "Fatal signal",
    "FATAL EXCEPTION"
)
$diagnosticLog = Invoke-AiosAdb -Arguments @("logcat", "-b", "all", "-d", "-v", "threadtime") |
    Select-String -SimpleMatch -Pattern $diagnosticPatterns |
    ForEach-Object { $_.Line }
$matches = [regex]::Matches(
    $instrumentationText,
    "(?m)^INSTRUMENTATION_(?:STATUS|RESULT): " +
    "aios_measurements_base64=([^\r\n]+)\r?$"
)
if ($matches.Count -ne 1) {
    $summary = ($instrumentationOutput | Select-Object -Last 30) -join "`n"
    throw "realtime smoke emitted no unique measurement payload:`n$summary"
}
$measurementJson = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String($matches[0].Groups[1].Value.Trim()))
$measurement = $measurementJson | ConvertFrom-Json
if ($measurement.schema_version -ne 1 -or
    $measurement.suite_version -ne 4 -or
    $measurement.mode -ne $expectedMode -or
    @($measurement.results).Count -ne $expectedRoles) {
    throw "realtime smoke payload has an unexpected schema or role count"
}

$record = [ordered]@{
    schema_version = 1
    evidence_kind = $evidenceKind
    suite_version = [int]$measurement.suite_version
    device_codename = $codename
    total_ram_mb = [math]::Floor([int64]$memoryMatch.Groups[1].Value / 1024)
    build_fingerprint_sha256 = Get-AiosSha256 -Value $fingerprint
    completed_at = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    admission_evidence = $false
    results = $measurement.results
    diagnostic_log = @($diagnosticLog)
}
$parent = Split-Path -Parent $outputPath
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$temporary = "$outputPath.tmp"
try {
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText(
        $temporary, ($record | ConvertTo-Json -Depth 20), $utf8WithoutBom)
    Move-Item -LiteralPath $temporary -Destination $outputPath
}
finally {
    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
}
Write-Output "Captured focused physical realtime smoke evidence at $outputPath"
