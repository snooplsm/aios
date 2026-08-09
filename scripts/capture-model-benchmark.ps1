param(
    [string]$Measurements = "",

    [Parameter(Mandatory = $true)]
    [string]$Output,

    [string]$ProfileId = "pixel_9a_tegu",
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$outputPath = [IO.Path]::GetFullPath($Output)
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    throw "adb is not available on PATH"
}
$adbArguments = @()
if ($Serial) {
    $adbArguments += @("-s", $Serial)
}

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
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        return (($algorithm.ComputeHash($bytes) | ForEach-Object {
            $_.ToString("x2")
        }) -join "")
    }
    finally {
        $algorithm.Dispose()
    }
}

$state = ((Invoke-AiosAdb -Arguments @("get-state")) -join "`n").Trim()
if ($state -ne "device") {
    throw "adb target is not ready"
}
if ((Get-AiosProperty -Name "ro.debuggable") -ne "1") {
    throw "model benchmarks may run only on a debuggable AIOS build"
}
if (-not (Get-AiosProperty -Name "ro.aios.version")) {
    throw "attached target is not an AIOS build"
}

$admissionPath = Join-Path $repositoryRoot "config\model_admission.json"
$admission = Get-Content -Raw -LiteralPath $admissionPath | ConvertFrom-Json
$profile = @($admission.profiles | Where-Object { $_.id -eq $ProfileId })
if ($profile.Count -ne 1) {
    throw "unknown admission profile: $ProfileId"
}
$codename = Get-AiosProperty -Name "ro.product.device"
if ($profile[0].devices -notcontains $codename) {
    throw "attached device '$codename' does not match profile '$ProfileId'"
}
$fingerprint = Get-AiosProperty -Name "ro.build.fingerprint"
if (-not $fingerprint) {
    throw "attached device has no build fingerprint"
}
$memoryText = (Invoke-AiosAdb -Arguments @("shell", "cat", "/proc/meminfo")) -join "`n"
$memoryMatch = [regex]::Match($memoryText, "(?m)^MemTotal:\s+(\d+)\s+kB\s*$")
if (-not $memoryMatch.Success) {
    throw "cannot read device total RAM"
}
$totalRamMb = [math]::Floor([int64]$memoryMatch.Groups[1].Value / 1024)
if ($totalRamMb -lt [int64]$profile[0].min_total_ram_mb -or
    $totalRamMb -gt [int64]$profile[0].max_total_ram_mb) {
    throw "measured RAM does not match profile '$ProfileId'"
}

$temporaryMeasurementsPath = $null
if ($Measurements) {
    $measurementsPath = (Resolve-Path -LiteralPath $Measurements).Path
}
else {
    $instrumentationOutput = Invoke-AiosAdb -Arguments @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class",
        "com.aios.modelbenchmark.ModelAdmissionBenchmarkTest",
        "com.aios.modelbenchmark.tests/androidx.test.runner.AndroidJUnitRunner"
    )
    $instrumentationText = $instrumentationOutput -join "`n"
    $matches = [regex]::Matches(
        $instrumentationText,
        "(?m)^INSTRUMENTATION_(?:STATUS|RESULT): " +
        "aios_measurements_base64=([^\r\n]+)\r?$"
    )
    if ($matches.Count -ne 1) {
        $summary = ($instrumentationOutput | Select-Object -Last 20) -join "`n"
        throw "benchmark runner did not emit exactly one measurement payload:`n$summary"
    }
    try {
        $measurementBytes = [Convert]::FromBase64String(
            $matches[0].Groups[1].Value.Trim())
    }
    catch {
        throw "benchmark runner emitted invalid base64 measurements"
    }
    $temporaryMeasurementsPath = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllBytes($temporaryMeasurementsPath, $measurementBytes)
    $measurementsPath = $temporaryMeasurementsPath
}

$temporaryPath = $null
try {
    $measurementDocument = Get-Content -Raw -LiteralPath $measurementsPath |
        ConvertFrom-Json
    $measurementFields = @($measurementDocument.PSObject.Properties.Name | Sort-Object)
    $expectedFields = @("results", "schema_version", "suite_version")
    if (($measurementFields -join ",") -ne ($expectedFields -join ",") -or
        $measurementDocument.schema_version -ne 1 -or
        $measurementDocument.suite_version -ne 1 -or
        $null -eq $measurementDocument.results) {
        throw "measurement input must contain only schema_version, suite_version, and results"
    }

    $rawDocument = [ordered]@{
        schema_version = 1
        suite_version = [int]$measurementDocument.suite_version
        profile_id = $profile[0].id
        catalog_tier = $profile[0].catalog_tier
        device_codename = $codename
        total_ram_mb = [int]$totalRamMb
        build_fingerprint_sha256 = Get-AiosSha256 -Value $fingerprint
        completed_at = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
        results = $measurementDocument.results
    }

    $temporaryPath = [IO.Path]::GetTempFileName()
    $rawJson = $rawDocument | ConvertTo-Json -Depth 20
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText($temporaryPath, $rawJson, $utf8WithoutBom)
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $pythonCommand) {
        $pythonCommand = Get-Command python3 -ErrorAction SilentlyContinue
    }
    if ($null -eq $pythonCommand) {
        throw "Python 3 is not available on PATH"
    }
    $evaluatorPath = Join-Path $repositoryRoot "tools\evaluate_model_benchmark.py"
    & $pythonCommand.Source $evaluatorPath --raw $temporaryPath --output $outputPath
    if ($LASTEXITCODE -ne 0) {
        throw "benchmark evaluation failed"
    }
}
finally {
    if ($null -ne $temporaryPath) {
        Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    }
    if ($null -ne $temporaryMeasurementsPath) {
        Remove-Item -LiteralPath $temporaryMeasurementsPath -Force -ErrorAction SilentlyContinue
    }
}

Write-Output "Captured privacy-minimized device evidence at $outputPath"
