param(
    [Parameter(Mandatory = $true)]
    [string]$Output,

    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [ValidateRange(10, 600)]
    [int]$DurationSeconds = 180,

    [switch]$DryRun
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

function Invoke-AiosAdb {
    param([string[]]$Arguments)
    $value = & $adbPath -s $Serial @Arguments 2>&1
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
    finally {
        $algorithm.Dispose()
    }
}

function Write-AiosText {
    param([string]$Path, [string]$Value)
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText($Path, $Value, $utf8WithoutBom)
}

function Save-AiosAdb {
    param([string]$Name, [string[]]$Arguments)
    $value = (Invoke-AiosAdb -Arguments $Arguments) -join "`n"
    Write-AiosText -Path (Join-Path $outputPath $Name) -Value ($value + "`n")
}

function Save-AiosFilteredAdb {
    param([string]$Name, [string[]]$Arguments, [string]$Pattern)
    $value = Invoke-AiosAdb -Arguments $Arguments |
        Select-String -Pattern $Pattern |
        ForEach-Object { $_.Line }
    Write-AiosText -Path (Join-Path $outputPath $Name) `
        -Value (($value -join "`n") + "`n")
}

function Get-AiosMetric {
    param([string[]]$Lines, [string]$Pattern)
    $values = @()
    foreach ($line in $Lines) {
        $match = [regex]::Match($line, $Pattern)
        if ($match.Success) {
            $values += [int64]$match.Groups[1].Value
        }
    }
    return @($values)
}

if ($Serial -match "^emulator-[0-9]+$") {
    throw "physical call capture refuses emulator serials"
}
if (((Invoke-AiosAdb -Arguments @("get-state")) -join "`n").Trim() -ne "device") {
    throw "the selected ADB target is not ready"
}
if (-not (Get-AiosProperty -Name "ro.aios.version")) {
    throw "the selected target is not running AIOS"
}
if ((Get-AiosProperty -Name "ro.debuggable") -ne "1") {
    throw "physical call capture requires a debuggable AIOS build"
}
if ((Get-AiosProperty -Name "ro.boot.qemu") -eq "1" -or
    (Get-AiosProperty -Name "ro.kernel.qemu") -eq "1") {
    throw "physical call capture refuses QEMU targets"
}

Invoke-AiosAdb -Arguments @("root") | Out-Null
Invoke-AiosAdb -Arguments @("wait-for-device") | Out-Null

$manualTest = Get-AiosProperty -Name "persist.aios.debug.call_uplink_test"
$validatedUplink = Get-AiosProperty -Name "ro.aios.call_uplink_validated"
if (-not $DryRun -and $manualTest -ne "1" -and $validatedUplink -ne "true") {
    throw "manual caller-uplink test mode or a validated release route is required"
}

$fingerprint = Get-AiosProperty -Name "ro.build.fingerprint"
$codename = Get-AiosProperty -Name "ro.product.device"
$buildId = Get-AiosProperty -Name "ro.build.id"
$aiosVersion = Get-AiosProperty -Name "ro.aios.version"
if (-not $fingerprint -or -not $codename -or -not $buildId) {
    throw "the selected target is missing build identity"
}

New-Item -ItemType Directory -Path $outputPath | Out-Null
$runtimeLogPath = Join-Path $outputPath "runtime.log"
$runtimeErrorPath = Join-Path $outputPath "runtime-stderr.log"
$memorySamplesPath = Join-Path $outputPath "memory-samples.log"
$startedAt = [DateTime]::UtcNow

Save-AiosAdb -Name "pre-meminfo.txt" -Arguments @("shell", "cat", "/proc/meminfo")
Save-AiosAdb -Name "pre-battery.txt" -Arguments @("shell", "dumpsys", "battery")
Save-AiosAdb -Name "pre-thermal.txt" -Arguments @("shell", "dumpsys", "thermalservice")
Save-AiosFilteredAdb -Name "pre-processes.txt" -Arguments @(
    "shell", "ps", "-A", "-o", "USER,PID,PPID,VSZ,RSS,STAT,NAME") `
    -Pattern "com\.aios\."
Save-AiosAdb -Name "pre-tombstones.txt" -Arguments @(
    "shell", "ls", "-ln", "/data/tombstones")

Invoke-AiosAdb -Arguments @("logcat", "-b", "all", "-c") | Out-Null
$logcatArguments = @(
    "-s", $Serial, "logcat", "-b", "all", "-v", "threadtime",
    "AiosCallIntelligence:I", "AiosReceptionist:I", "AiosCallCapture:I",
    "AiosCallerUplink:I", "AiosRemoteRuntime:I", "AiosWhisperRuntime:I",
    "AiosLiteRtLmRuntime:I", "AiosTtsRuntime:I", "AndroidRuntime:E",
    "libc:F", "DEBUG:F", "lmkd:I", "lowmemorykiller:I", "*:S")
$logStart = [Diagnostics.ProcessStartInfo]::new()
$logStart.FileName = $adbPath
$logStart.Arguments = $logcatArguments -join " "
$logStart.UseShellExecute = $false
$logStart.RedirectStandardOutput = $true
$logStart.RedirectStandardError = $true
$logStart.CreateNoWindow = $true
$logProcess = [Diagnostics.Process]::new()
$logProcess.StartInfo = $logStart
if (-not $logProcess.Start()) {
    throw "could not start focused logcat"
}
$logOutput = $logProcess.StandardOutput.ReadToEndAsync()
$logError = $logProcess.StandardError.ReadToEndAsync()

if ($DryRun) {
    Invoke-AiosAdb -Arguments @(
        "shell", "am", "force-stop", "com.aios.callintelligence") | Out-Null
    Invoke-AiosAdb -Arguments @(
        "shell", "am", "force-stop", "com.aios.phone") | Out-Null
    Invoke-AiosAdb -Arguments @(
        "shell", "am", "start", "-a", "android.intent.action.DIAL",
        "-p", "com.aios.phone") | Out-Null
}

try {
    $deadline = [DateTime]::UtcNow.AddSeconds($DurationSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($logProcess.HasExited) {
            throw "focused logcat exited before capture completed"
        }
        $timestamp = [DateTime]::UtcNow.ToString("o")
        $meminfo = Invoke-AiosAdb -Arguments @("shell", "cat", "/proc/meminfo") |
            Select-String -Pattern "^(MemFree|MemAvailable|SwapFree|AnonPages|Cached):" |
            ForEach-Object { $_.Line }
        $processes = Invoke-AiosAdb -Arguments @(
            "shell", "ps", "-A", "-o", "PID,RSS,NAME") |
            Select-String -Pattern "com\.aios\." |
            ForEach-Object { $_.Line }
        [IO.File]::AppendAllText(
            $memorySamplesPath,
            ("[{0}]`n{1}`n{2}`n" -f
                $timestamp, ($meminfo -join "`n"), ($processes -join "`n")),
            [Text.UTF8Encoding]::new($false))
        $remaining = [math]::Ceiling(($deadline - [DateTime]::UtcNow).TotalSeconds)
        if ($remaining -gt 0) {
            Start-Sleep -Seconds ([math]::Min(5, $remaining))
        }
    }
}
finally {
    if ($null -ne $logProcess -and -not $logProcess.HasExited) {
        $logProcess.Kill()
    }
    if ($null -ne $logProcess) { $logProcess.WaitForExit() }
    Write-AiosText -Path $runtimeLogPath -Value $logOutput.Result
    Write-AiosText -Path $runtimeErrorPath -Value $logError.Result
}

$completedAt = [DateTime]::UtcNow
Save-AiosAdb -Name "post-meminfo.txt" -Arguments @("shell", "cat", "/proc/meminfo")
Save-AiosAdb -Name "post-battery.txt" -Arguments @("shell", "dumpsys", "battery")
Save-AiosAdb -Name "post-thermal.txt" -Arguments @("shell", "dumpsys", "thermalservice")
Save-AiosFilteredAdb -Name "post-cpuinfo.txt" `
    -Arguments @("shell", "dumpsys", "cpuinfo") -Pattern "com\.aios\."
Save-AiosFilteredAdb -Name "post-processes.txt" -Arguments @(
    "shell", "ps", "-A", "-o", "USER,PID,PPID,VSZ,RSS,STAT,NAME") `
    -Pattern "com\.aios\."
Save-AiosAdb -Name "post-tombstones.txt" -Arguments @(
    "shell", "ls", "-ln", "/data/tombstones")

$packages = @(
    "com.aios.phone", "com.aios.callintelligence", "com.aios.modelbroker",
    "com.aios.contextintelligence", "com.aios.runtime.whispercpp:runtime",
    "com.aios.runtime.litertlm:runtime", "com.aios.runtime.sherpatts:runtime")
foreach ($package in $packages) {
    $safeName = $package.Replace(".", "-").Replace(":", "-")
    Save-AiosAdb -Name "post-meminfo-$safeName.txt" -Arguments @(
        "shell", "dumpsys", "meminfo", $package)
}

$logLines = if (Test-Path -LiteralPath $runtimeLogPath) {
    @(Get-Content -LiteralPath $runtimeLogPath)
} else {
    @()
}
$diagnosticPattern = [regex]::new(
    "OutOfMemory|out of memory|Fatal signal|FATAL EXCEPTION|lowmemorykiller|" +
    "lmkd|SESSION_FAILED|INFERENCE_FAILED|DECODE_FAILED|SYNTHESIS_FAILED|" +
    "ERROR runtime=|capture_unavailable|transcript_storage_failed|" +
    "transcript_context_restore_failed|assistant_speech_unavailable",
    [Text.RegularExpressions.RegexOptions]::IgnoreCase)
$diagnosticLines = @($logLines | Where-Object { $diagnosticPattern.IsMatch($_) })
$kernelDiagnostics = Invoke-AiosAdb -Arguments @("shell", "dmesg") |
    Select-String -Pattern "Out of memory|oom-kill|Killed process|lowmemorykiller" |
    ForEach-Object { $_.Line }
$diagnosticLines += @($kernelDiagnostics)
Write-AiosText -Path (Join-Path $outputPath "diagnostics.log") `
    -Value (($diagnosticLines -join "`n") + "`n")

$measurement = [ordered]@{
    schema_version = 1
    evidence_kind = if ($DryRun) {
        "pixel_aios_physical_call_capture_dry_run"
    } else {
        "pixel_aios_physical_call_capture"
    }
    device_codename = $codename
    serial_sha256 = Get-AiosSha256 -Value $Serial
    build_id = $buildId
    aios_version = $aiosVersion
    build_fingerprint_sha256 = Get-AiosSha256 -Value $fingerprint
    started_at = $startedAt.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    completed_at = $completedAt.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    duration_seconds = $DurationSeconds
    manual_call_test = $manualTest -eq "1"
    automatic_uplink_validated = $validatedUplink -eq "true"
    timings_ms = [ordered]@{
        asr_decode = @(Get-AiosMetric -Lines $logLines `
            -Pattern "AiosWhisperRuntime.*DECODE_DONE.*elapsed_ms=(\d+)")
        broker_asr_first_chunk = @(Get-AiosMetric -Lines $logLines `
            -Pattern "AiosRemoteRuntime.*FIRST_CHUNK.*capability=streaming_asr.*elapsed_ms=(\d+)")
        llm_first_token = @(Get-AiosMetric -Lines $logLines `
            -Pattern "AiosLiteRtLmRuntime.*FIRST_TOKEN.*elapsed_ms=(\d+)")
        llm_complete = @(Get-AiosMetric -Lines $logLines `
            -Pattern "AiosLiteRtLmRuntime.*INFERENCE_DONE.*elapsed_ms=(\d+)")
        tts_first_audio = @(Get-AiosMetric -Lines $logLines `
            -Pattern "AiosTtsRuntime.*FIRST_AUDIO.*elapsed_ms=(\d+)")
        tts_complete = @(Get-AiosMetric -Lines $logLines `
            -Pattern "AiosTtsRuntime.*SYNTHESIS_DONE.*elapsed_ms=(\d+)")
    }
    diagnostic_event_count = $diagnosticLines.Count
    contains_oom_marker = [bool]($diagnosticLines -match "OutOfMemory|out of memory|oom-kill")
    contains_fatal_marker = [bool]($diagnosticLines -match "Fatal signal|FATAL EXCEPTION")
    contains_aios_failure_marker = [bool]($diagnosticLines -match
        "SESSION_FAILED|INFERENCE_FAILED|DECODE_FAILED|SYNTHESIS_FAILED|" +
        "ERROR runtime=|capture_unavailable|assistant_speech_unavailable")
    admission_evidence = $false
}
Write-AiosText -Path (Join-Path $outputPath "summary.json") `
    -Value (($measurement | ConvertTo-Json -Depth 12) + "`n")

Write-Output "Captured privacy-minimized physical call evidence at $outputPath"
