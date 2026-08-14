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
$adbPath = if ($null -ne $adbCommand) {
    $adbCommand.Source
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
    throw "adb is not available on PATH or in the standard Android SDK location"
}
$adbArguments = @()
if ($Serial) { $adbArguments += @("-s", $Serial) }

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
        return (($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)) |
            ForEach-Object { $_.ToString("x2") }) -join "")
    }
    finally { $algorithm.Dispose() }
}

function Get-AiosMemorySnapshot {
    $values = [ordered]@{}
    $lines = Invoke-AiosAdb -Arguments @("shell", "cat", "/proc/meminfo")
    foreach ($line in $lines) {
        $match = [regex]::Match(
            $line, "^(MemAvailable|SwapFree|AnonPages|Cached):\s+(\d+)\s+kB$")
        if ($match.Success) {
            $values[$match.Groups[1].Value + "_mb"] =
                [math]::Round([int64]$match.Groups[2].Value / 1024.0, 1)
        }
    }
    return $values
}

function Get-AiosRuntimeRssSnapshot {
    $processes = [ordered]@{}
    $totalKb = 0L
    $lines = Invoke-AiosAdb -Arguments @(
        "shell", "ps", "-A", "-o", "RSS,NAME")
    foreach ($line in $lines) {
        $match = [regex]::Match($line, "^\s*(\d+)\s+(\S+)\s*$")
        if (-not $match.Success) { continue }
        $name = $match.Groups[2].Value
        if ($name -ne "com.aios.modelbroker" -and
            $name -notmatch "^com\.aios\.runtime\.") { continue }
        $rssKb = [int64]$match.Groups[1].Value
        $totalKb += $rssKb
        $processes[$name] = [math]::Round($rssKb / 1024.0, 1)
    }
    return [ordered]@{
        observed_at = [DateTime]::UtcNow.ToString("o")
        total_rss_mb = [math]::Round($totalKb / 1024.0, 1)
        processes_rss_mb = $processes
    }
}

if (((Invoke-AiosAdb -Arguments @("get-state")) -join "`n").Trim() -ne "device") {
    throw "adb target is not ready"
}
if ((Get-AiosProperty -Name "ro.debuggable") -ne "1" -or
    -not (Get-AiosProperty -Name "ro.aios.version")) {
    throw "realtime smoke requires a debuggable AIOS build"
}
if ((Get-AiosProperty -Name "ro.boot.qemu") -eq "1" -or
    (Get-AiosProperty -Name "ro.kernel.qemu") -eq "1") {
    throw "physical realtime smoke refuses QEMU targets"
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
Invoke-AiosAdb -Arguments @("root") | Out-Null
Invoke-AiosAdb -Arguments @("wait-for-device") | Out-Null
$memoryBefore = Get-AiosMemorySnapshot
Invoke-AiosAdb -Arguments @("logcat", "-b", "all", "-c") | Out-Null
$instrumentationArguments = @($adbArguments) + @(
    "shell", "am", "instrument", "-w", "-r", "-e", "class",
    "com.aios.modelbenchmark.ModelAdmissionBenchmarkTest#$testMethod",
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
$resourceSamples = [Collections.Generic.List[object]]::new()
$instrumentationText = ""
$instrumentationError = ""
try {
    if (-not $instrumentationProcess.Start()) {
        throw "could not start model instrumentation"
    }
    $instrumentationOutputTask =
        $instrumentationProcess.StandardOutput.ReadToEndAsync()
    $instrumentationErrorTask =
        $instrumentationProcess.StandardError.ReadToEndAsync()
    do {
        $resourceSamples.Add((Get-AiosRuntimeRssSnapshot))
        $finished = $instrumentationProcess.WaitForExit(500)
    } while (-not $finished)
    $instrumentationProcess.WaitForExit()
    $instrumentationText = $instrumentationOutputTask.Result
    $instrumentationError = $instrumentationErrorTask.Result
    if ($instrumentationProcess.ExitCode -ne 0) {
        throw "model instrumentation failed with exit code " +
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
$instrumentationOutput = @($instrumentationText -split "\r?\n")
$memoryAfter = Get-AiosMemorySnapshot
$diagnosticPatterns = @(
    "AiosModelDiagnostic",
    "AiosRemoteRuntime",
    "AiosLiteRtLmRuntime",
    "AiosTtsRuntime",
    "AiosWhisperRuntime",
    "AiosWhisperNative",
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

if ($Mode -eq "single") {
    $ttsResults = @($measurement.results | Where-Object {
        $_.capability -eq "speech_synthesis"
    })
    if ($ttsResults.Count -ne 1 -or
        $null -eq $ttsResults[0].metrics.details.time_to_first_audio_ms) {
        throw "single-model diagnostic omitted TTS first-audio timing"
    }
    $ttsResults[0].metrics.first_output_ms =
        [int64]$ttsResults[0].metrics.details.time_to_first_audio_ms
}

$peakTotalRssMb = 0.0
foreach ($sample in $resourceSamples) {
    $peakTotalRssMb = [math]::Max(
        $peakTotalRssMb, [double]$sample["total_rss_mb"])
}
if ($resourceSamples.Count -lt 1 -or $peakTotalRssMb -le 0) {
    throw "host resource sampler observed no AIOS model runtime memory"
}
$processPeakRssMb = [ordered]@{}
foreach ($sample in $resourceSamples) {
    foreach ($entry in $sample["processes_rss_mb"].GetEnumerator()) {
        $current = if ($processPeakRssMb.Contains($entry.Key)) {
            [double]$processPeakRssMb[$entry.Key]
        } else { 0.0 }
        $processPeakRssMb[$entry.Key] = [math]::Max($current, [double]$entry.Value)
    }
}
$instrumentationPssValues = @()
foreach ($result in @($measurement.results)) {
    foreach ($field in @("aios_runtime_peak_pss_mb", "peak_rss_mb")) {
        $property = $result.metrics.PSObject.Properties[$field]
        if ($null -ne $property) {
            $instrumentationPssValues += [double]$property.Value
        }
    }
}
$instrumentationPssAvailable = @(
    $instrumentationPssValues | Where-Object { $_ -gt 0 }).Count -gt 0
$diagnosticText = @($diagnosticLog) -join "`n"

$record = [ordered]@{
    schema_version = 1
    evidence_kind = $evidenceKind
    suite_version = [int]$measurement.suite_version
    device_codename = $codename
    total_ram_mb = [math]::Floor([int64]$memoryMatch.Groups[1].Value / 1024)
    build_fingerprint_sha256 = Get-AiosSha256 -Value $fingerprint
    completed_at = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    admission_evidence = $false
    instrumentation_runtime_pss_available = $instrumentationPssAvailable
    host_resource_sampling = [ordered]@{
        interval_ms = 500
        sample_count = $resourceSamples.Count
        peak_total_aios_runtime_rss_mb = $peakTotalRssMb
        process_peak_rss_mb = $processPeakRssMb
        memory_before = $memoryBefore
        memory_after = $memoryAfter
        samples = @($resourceSamples)
    }
    contains_aios_low_memory_kill =
        [bool]($diagnosticText -match "lowmemorykiller: Kill 'com\.aios\.")
    contains_oom_or_fatal = [bool]($diagnosticText -match
        "OutOfMemory|out of memory|oom-kill|Fatal signal|FATAL EXCEPTION")
    results = $measurement.results
    diagnostic_log = @($diagnosticLog)
    instrumentation_stderr = @($instrumentationError -split "\r?\n" |
        Where-Object { $_ })
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
