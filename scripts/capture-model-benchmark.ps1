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
if (Test-Path -LiteralPath $outputPath) {
    throw "refusing to overwrite $outputPath"
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
$adbArguments = @()
if ($Serial) {
    $adbArguments += @("-s", $Serial)
}

function Invoke-AiosAdb {
    param([string[]]$Arguments)
    $value = & $adbPath @adbArguments @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' '): $($value -join ' ')"
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

function Get-AiosRuntimeRssMb {
    $totalKb = 0L
    $lines = Invoke-AiosAdb -Arguments @(
        "shell", "ps", "-A", "-o", "RSS,NAME")
    foreach ($line in $lines) {
        $match = [regex]::Match($line, "^\s*(\d+)\s+(\S+)\s*$")
        if (-not $match.Success) { continue }
        $name = $match.Groups[2].Value
        if ($name -eq "com.aios.modelbroker" -or
            $name -match "^com\.aios\.runtime\.") {
            $totalKb += [int64]$match.Groups[1].Value
        }
    }
    return [math]::Ceiling($totalKb / 1024.0)
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
if ((Get-AiosProperty -Name "ro.boot.qemu") -eq "1" -or
    (Get-AiosProperty -Name "ro.kernel.qemu") -eq "1") {
    throw "model admission requires physical hardware"
}

$admissionPath = Join-Path $repositoryRoot "config\model_admission.json"
$admission = Get-Content -Raw -LiteralPath $admissionPath | ConvertFrom-Json
$suitePath = Join-Path $repositoryRoot "config\model_benchmark_suite.json"
$suite = Get-Content -Raw -LiteralPath $suitePath | ConvertFrom-Json
if ($suite.schema_version -ne 1 -or
    $suite.suite_version -isnot [int] -or
    $suite.suite_version -lt 1) {
    throw "checked-in model benchmark suite is invalid"
}
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
$hostPeakRuntimeRssMb = $null
if ($Measurements) {
    $measurementsPath = (Resolve-Path -LiteralPath $Measurements).Path
}
else {
    Invoke-AiosAdb -Arguments @("root") | Out-Null
    Invoke-AiosAdb -Arguments @("wait-for-device") | Out-Null
    $instrumentationArguments = @($adbArguments) + @(
        "shell", "am", "instrument", "-w", "-r", "-e", "class",
        "com.aios.modelbenchmark.ModelAdmissionBenchmarkTest#runAdmissionBenchmark",
        "com.aios.modelbenchmark.tests/androidx.test.runner.AndroidJUnitRunner")
    $instrumentationStart = [Diagnostics.ProcessStartInfo]::new()
    $instrumentationStart.FileName = $adbPath
    $instrumentationStart.Arguments = $instrumentationArguments -join " "
    $instrumentationStart.UseShellExecute = $false
    $instrumentationStart.RedirectStandardOutput = $true
    $instrumentationStart.RedirectStandardError = $true
    $instrumentationStart.CreateNoWindow = $true
    $instrumentationProcess = [Diagnostics.Process]::new()
    $instrumentationProcess.StartInfo = $instrumentationStart
    $instrumentationText = ""
    $instrumentationError = ""
    $hostPeakRuntimeRssMb = 0
    try {
        if (-not $instrumentationProcess.Start()) {
            throw "could not start model admission instrumentation"
        }
        $instrumentationOutputTask =
            $instrumentationProcess.StandardOutput.ReadToEndAsync()
        $instrumentationErrorTask =
            $instrumentationProcess.StandardError.ReadToEndAsync()
        do {
            $hostPeakRuntimeRssMb = [math]::Max(
                $hostPeakRuntimeRssMb, (Get-AiosRuntimeRssMb))
            $finished = $instrumentationProcess.WaitForExit(500)
        } while (-not $finished)
        $instrumentationProcess.WaitForExit()
        $instrumentationText = $instrumentationOutputTask.Result
        $instrumentationError = $instrumentationErrorTask.Result
        if ($instrumentationProcess.ExitCode -ne 0) {
            throw "model admission instrumentation failed with exit code " +
                "$($instrumentationProcess.ExitCode): $instrumentationError"
        }
    }
    finally {
        if ($null -ne $instrumentationProcess -and
            -not $instrumentationProcess.HasExited) {
            $instrumentationProcess.Kill()
            $instrumentationProcess.WaitForExit()
        }
    }
    if ($hostPeakRuntimeRssMb -le 0) {
        throw "host resource sampler observed no AIOS model runtime memory"
    }
    $instrumentationOutput = @($instrumentationText -split "\r?\n")
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
        $measurementDocument.suite_version -ne $suite.suite_version -or
        $null -eq $measurementDocument.results) {
        throw "measurement input must match the checked-in benchmark suite"
    }
    if ($null -ne $hostPeakRuntimeRssMb) {
        foreach ($result in @($measurementDocument.results)) {
            if ($null -eq $result.metrics -or
                $null -eq $result.metrics.PSObject.Properties["peak_rss_mb"]) {
                throw "benchmark result omitted peak RSS observation"
            }
            # Android limits cross-UID ActivityManager PSS visibility. Use the
            # conservative peak RSS of Broker plus every runtime provider that
            # the rooted userdebug host sampler observed during the full run.
            $result.metrics.peak_rss_mb = [int]$hostPeakRuntimeRssMb
        }
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
