param(
    [string]$OutputDirectory = "$PSScriptRoot\..\.cache\tts-emulator-fixtures",
    [switch]$AcceptModelLicense
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$catalogPath = [IO.Path]::GetFullPath("$PSScriptRoot\..\config\model_catalog.json")
$catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
$models = @($catalog.models | Where-Object { $_.id -eq "supertonic3-en-es-int8" })
if ($models.Count -ne 1) { throw "The bilingual Supertonic catalog candidate is absent or ambiguous" }
$model = $models[0]
$licenseUrl = [string]$model.license_url
if ($licenseUrl -ne
        "https://huggingface.co/Supertone/supertonic-3/blob/724fb5abbf5502583fb520898d45929e62f02c0b/LICENSE") {
    throw "The reviewed Supertonic model license changed"
}
if (-not $AcceptModelLicense) {
    throw "Review $licenseUrl and rerun with -AcceptModelLicense for local research use"
}
$bundle = $model.reference_bundle
if ($bundle.sha256 -ne "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427" -or
        [long]$bundle.size_bytes -ne 128774318 -or
        $bundle.archive_root -ne "sherpa-onnx-supertonic-3-tts-int8-2026-05-11" -or
        [int]$model.sample_rate_hz -ne 44100) {
    throw "The reviewed Supertonic bundle identity changed"
}

$output = [IO.Path]::GetFullPath($OutputDirectory)
$archive = Join-Path $output "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
$bundleDirectory = Join-Path $output "supertonic3-en-es-int8"
$descriptor = Join-Path $output "supertonic3-en-es-int8.bundle.json"
New-Item -ItemType Directory -Force -Path $output | Out-Null

if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    $partial = "$archive.partial"
    Invoke-WebRequest -UseBasicParsing -MaximumRedirection 8 `
        -Headers @{"User-Agent" = "AIOS research emulator fixture fetch/0.1"} `
        -Uri ([string]$bundle.url) -OutFile $partial
    $partialFile = Get-Item -LiteralPath $partial
    $partialSha = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($partialFile.Length -ne [long]$bundle.size_bytes -or $partialSha -ne $bundle.sha256) {
        Remove-Item -LiteralPath $partial -Force
        throw "Downloaded Supertonic archive does not match its reviewed identity"
    }
    Move-Item -LiteralPath $partial -Destination $archive
}
$archiveFile = Get-Item -LiteralPath $archive
$archiveSha = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($archiveFile.Length -ne [long]$bundle.size_bytes -or $archiveSha -ne $bundle.sha256) {
    throw "Cached Supertonic archive does not match its reviewed identity"
}

if (-not (Test-Path -LiteralPath $bundleDirectory -PathType Container)) {
    $temporary = Join-Path $output ("extract-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $temporary | Out-Null
    try {
        & tar.exe -xjf $archive -C $temporary --strip-components=1
        if ($LASTEXITCODE -ne 0) { throw "tar failed to extract the Supertonic archive" }
        Move-Item -LiteralPath $temporary -Destination $bundleDirectory
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Recurse -Force
        }
    }
}

$descriptorMembers = @()
foreach ($member in $bundle.members) {
    $path = Join-Path $bundleDirectory ([string]$member.path)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Supertonic bundle member is absent: $($member.path)"
    }
    $file = Get-Item -LiteralPath $path
    $sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($file.Length -ne [long]$member.size_bytes -or $sha256 -ne $member.sha256) {
        throw "Supertonic bundle member failed verification: $($member.path)"
    }
    $descriptorMembers += [ordered]@{
        name = [string]$member.path
        relative_path = "models/supertonic3-en-es-int8/$($member.path)"
        size_bytes = [long]$member.size_bytes
        sha256 = [string]$member.sha256
    }
}
$ttsConfiguration = Get-Content -LiteralPath (Join-Path $bundleDirectory "tts.json") -Raw |
    ConvertFrom-Json
if ([int]$ttsConfiguration.ae.sample_rate -ne [int]$model.sample_rate_hz -or
        [int]$ttsConfiguration.ae.encoder.spec_processor.sample_rate -ne
            [int]$model.sample_rate_hz) {
    throw "Supertonic bundle sample rate does not match the model catalog"
}
$descriptorValue = [ordered]@{
    schema_version = 1
    model_id = "supertonic3-en-es-int8"
    source_archive_sha256 = [string]$bundle.sha256
    members = $descriptorMembers
}
$json = $descriptorValue | ConvertTo-Json -Depth 6
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($descriptor, $json + "`n", $utf8WithoutBom)

[PSCustomObject]@{
    archive = $archive
    archive_bytes = $archiveFile.Length
    archive_sha256 = $archiveSha
    bundle_directory = $bundleDirectory
    descriptor = $descriptor
    descriptor_bytes = (Get-Item -LiteralPath $descriptor).Length
    descriptor_sha256 = (Get-FileHash -LiteralPath $descriptor -Algorithm SHA256).Hash.ToLowerInvariant()
    model_license = $licenseUrl
    model_license_accepted_for_local_research = $true
}
