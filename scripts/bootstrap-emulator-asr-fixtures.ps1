param(
    [string]$OutputDirectory = "$PSScriptRoot\..\.cache\asr-emulator-fixtures"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$catalogPath = [IO.Path]::GetFullPath("$PSScriptRoot\..\config\model_catalog.json")
$catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
$models = @(
    $catalog.models | Where-Object { $_.id -eq "whisper-base-multilingual-quantized" }
)
if ($models.Count -ne 1) {
    throw "The Pixel 9a Whisper catalog candidate is absent or ambiguous"
}
$model = $models[0]
if ($model.reference_artifact.sha256 -ne
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898") {
    throw "The reviewed Pixel 9a Whisper model identity changed"
}

$artifacts = @(
    [ordered]@{
        name = "ggml-base-q5_1.bin"
        url = [string]$model.reference_artifact.url
        sha256 = [string]$model.reference_artifact.sha256
        license = [string]$model.license_url
    },
    [ordered]@{
        name = "jfk.wav"
        url = "https://raw.githubusercontent.com/ggml-org/whisper.cpp/306c88f4d1286aec1bf96e544632897886af5501/samples/jfk.wav"
        sha256 = "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e"
        license = "https://github.com/ggml-org/whisper.cpp/blob/306c88f4d1286aec1bf96e544632897886af5501/LICENSE"
    },
    [ordered]@{
        name = "Spanish_Can_you_help_me.wav"
        url = "https://commons.wikimedia.org/wiki/Special:Redirect/file/Spanish_Can_you_help_me.wav"
        sha256 = "70ef4a2b564905d07f626af2adc2df958f9de584c120f3b9d2278158712d1d70"
        license = "https://commons.wikimedia.org/wiki/File:Spanish_Can_you_help_me.wav"
    }
)

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$headers = @{
    "User-Agent" = "AIOS research emulator fixture fetch/0.1 (https://github.com/snooplsm/aios)"
}
foreach ($artifact in $artifacts) {
    $destination = Join-Path $OutputDirectory $artifact.name
    if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
        $partial = "$destination.partial"
        Invoke-WebRequest -UseBasicParsing -Headers $headers -MaximumRedirection 8 `
            -Uri $artifact.url -OutFile $partial
        $downloaded = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($downloaded -ne $artifact.sha256) {
            Remove-Item -LiteralPath $partial -Force
            throw "Downloaded fixture digest mismatch for $($artifact.name)"
        }
        Move-Item -LiteralPath $partial -Destination $destination
    }
    $digest = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($digest -ne $artifact.sha256) {
        throw "Cached fixture digest mismatch for $($artifact.name)"
    }
    [PSCustomObject]@{
        path = [IO.Path]::GetFullPath($destination)
        bytes = (Get-Item -LiteralPath $destination).Length
        sha256 = $digest
        license = $artifact.license
    }
}
