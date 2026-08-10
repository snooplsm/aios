param(
    [Parameter(Mandatory = $true)]
    [string]$Output,

    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
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
        return (($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)) |
            ForEach-Object { $_.ToString("x2") }) -join "")
    }
    finally {
        $algorithm.Dispose()
    }
}

function Assert-AiosTimingGroup {
    param([object]$Group, [string]$Name)
    $expected = @(
        "p50_input_preparation_ms", "p50_model_request_ms",
        "p50_observed_to_index_ms", "p50_processing_ms", "p50_queue_to_start_ms",
        "p50_video_audio_duration_ms", "p50_video_audio_pipeline_ms",
        "p50_video_audio_realtime_factor_permille",
        "p95_input_preparation_ms", "p95_model_request_ms",
        "p95_observed_to_index_ms", "p95_processing_ms", "p95_queue_to_start_ms",
        "p95_video_audio_duration_ms", "p95_video_audio_pipeline_ms",
        "p95_video_audio_realtime_factor_permille",
        "sample_count", "video_audio_realtime_factor_sample_count",
        "video_audio_sample_count"
    )
    $actual = @($Group.PSObject.Properties.Name | Sort-Object)
    if (($actual -join ",") -ne ($expected -join ",") -or
        $Group.sample_count -isnot [int] -or
        $Group.sample_count -lt 0 -or $Group.sample_count -gt 100 -or
        $Group.video_audio_sample_count -isnot [int] -or
        $Group.video_audio_realtime_factor_sample_count -isnot [int] -or
        $Group.video_audio_sample_count -lt 0 -or
        $Group.video_audio_sample_count -gt $Group.sample_count -or
        $Group.video_audio_realtime_factor_sample_count -lt 0 -or
        $Group.video_audio_realtime_factor_sample_count -gt
            $Group.video_audio_sample_count) {
        throw "$Name timing group is malformed"
    }
    $counts = @(
        "sample_count", "video_audio_realtime_factor_sample_count",
        "video_audio_sample_count"
    )
    foreach ($field in $expected | Where-Object { $_ -notin $counts }) {
        $value = $Group.$field
        if ($null -ne $value -and ($value -isnot [long] -and $value -isnot [int]) -or
            $null -ne $value -and $value -lt 0) {
            throw "$Name field '$field' must be null or a non-negative integer"
        }
        if ($Group.sample_count -eq 0 -and $null -ne $value) {
            throw "$Name empty timing group must use null percentiles"
        }
    }
    foreach ($prefix in @("p50", "p95")) {
        if (($Group.video_audio_sample_count -eq 0) -ne
            ($null -eq $Group."${prefix}_video_audio_duration_ms") -or
            ($Group.video_audio_sample_count -eq 0) -ne
            ($null -eq $Group."${prefix}_video_audio_pipeline_ms") -or
            ($Group.video_audio_realtime_factor_sample_count -eq 0) -ne
            ($null -eq $Group."${prefix}_video_audio_realtime_factor_permille")) {
            throw "$Name video-audio timing denominator is inconsistent"
        }
    }
}

$state = ((Invoke-AiosAdb -Arguments @("get-state")) -join "`n").Trim()
if ($state -ne "device") {
    throw "adb target is not ready"
}
if ((Get-AiosProperty -Name "ro.debuggable") -ne "1") {
    throw "media timing may be captured only on a debuggable AIOS build"
}
if (-not (Get-AiosProperty -Name "ro.aios.version")) {
    throw "attached target is not an AIOS build"
}
$codename = Get-AiosProperty -Name "ro.product.device"
$fingerprint = Get-AiosProperty -Name "ro.build.fingerprint"
if (-not $codename -or -not $fingerprint) {
    throw "attached target is missing device identity"
}

$dump = Invoke-AiosAdb -Arguments @(
    "shell", "dumpsys", "activity", "service",
    "com.aios.mediaintelligence/.MediaObserverService", "--timing-json"
)
$dumpText = $dump -join "`n"
$matches = [regex]::Matches(
    $dumpText, "(?m)^AIOS_MEDIA_TIMING_BASE64=([A-Za-z0-9+/=]+)\r?$"
)
if ($matches.Count -ne 1) {
    throw "Media Intelligence did not emit exactly one timing payload"
}
try {
    $json = [Text.Encoding]::UTF8.GetString(
        [Convert]::FromBase64String($matches[0].Groups[1].Value))
    $timing = $json | ConvertFrom-Json
}
catch {
    throw "Media Intelligence emitted invalid base64 JSON timing data"
}

$expectedRoot = @(
    "generated_at_epoch_ms", "max_samples_per_kind", "photos", "schema_version", "videos"
)
$actualRoot = @($timing.PSObject.Properties.Name | Sort-Object)
if (($actualRoot -join ",") -ne ($expectedRoot -join ",") -or
    $timing.schema_version -ne 2 -or
    $timing.max_samples_per_kind -ne 100 -or
    $timing.generated_at_epoch_ms -le 0) {
    throw "media timing payload is malformed"
}
Assert-AiosTimingGroup -Group $timing.photos -Name "photo"
Assert-AiosTimingGroup -Group $timing.videos -Name "video"
if ($json -match "(?i)content://|caption|transcript|prompt|media_uri|phone") {
    throw "media timing payload contains prohibited content or identifiers"
}

$evidence = [ordered]@{
    schema_version = 1
    device_codename = $codename
    build_fingerprint_sha256 = Get-AiosSha256 -Value $fingerprint
    captured_at = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    timing = $timing
}
$parent = Split-Path -Parent $outputPath
if ($parent -and -not (Test-Path -LiteralPath $parent)) {
    New-Item -ItemType Directory -Path $parent | Out-Null
}
$utf8WithoutBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText(
    $outputPath, ($evidence | ConvertTo-Json -Depth 10), $utf8WithoutBom)
Write-Output "Captured privacy-minimized media timing evidence at $outputPath"
