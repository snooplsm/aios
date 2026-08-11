param(
    [string]$Serial = "emulator-5554",
    [string]$ClientApk = "$PSScriptRoot\..\preview\runtimeprovidercheck\build\outputs\apk\debug\runtimeprovidercheck-debug.apk",
    [string]$ProviderApk = "$PSScriptRoot\..\runtime\whisperprovider\app\build\outputs\apk\debug\app-debug.apk",
    [string]$InferenceModel = "$PSScriptRoot\..\.cache\asr-emulator-fixtures\ggml-base-q5_1.bin",
    [string]$EnglishWav = "$PSScriptRoot\..\.cache\asr-emulator-fixtures\jfk.wav",
    [string]$SpanishWav = "$PSScriptRoot\..\.cache\asr-emulator-fixtures\Spanish_Can_you_help_me.wav",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw "Refusing to run whisper-provider smoke checks on non-emulator serial: $Serial"
}

$androidHome = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$adb = Join-Path $androidHome "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }

function Invoke-Adb {
    $output = & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($args -join ' ')"
    }
    return $output
}

function Get-VerifiedArtifact {
    param(
        [string]$Path,
        [string]$ExpectedSha256,
        [string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label not found: $Path; run scripts/bootstrap-emulator-asr-fixtures.ps1"
    }
    $resolved = [IO.Path]::GetFullPath($Path)
    $bytes = (Get-Item -LiteralPath $resolved).Length
    $sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($bytes -le 0 -or $sha256 -ne $ExpectedSha256) {
        throw "$Label does not match its reviewed SHA-256"
    }
    return [PSCustomObject]@{ path = $resolved; bytes = $bytes; sha256 = $sha256 }
}

$state = ((Invoke-Adb get-state) -join "`n").Trim()
if ($state -ne "device") { throw "adb target is not ready: $Serial" }
$qemu = ((Invoke-Adb shell getprop ro.kernel.qemu) -join "`n").Trim()
if ($qemu -ne "1") { throw "Refusing to run: $Serial does not report ro.kernel.qemu=1" }
$androidRelease = ((Invoke-Adb shell getprop ro.build.version.release) -join "`n").Trim()
$apiLevel = [int](((Invoke-Adb shell getprop ro.build.version.sdk) -join "`n").Trim())
$abi = ((Invoke-Adb shell getprop ro.product.cpu.abi) -join "`n").Trim()
if ($apiLevel -lt 35) { throw "Whisper-provider smoke requires Android API 35 or newer" }
if ($abi -ne "x86_64") { throw "Whisper-provider emulator smoke requires x86_64, got: $abi" }

foreach ($path in @($ClientApk, $ProviderApk)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Whisper smoke APK not found: $path"
    }
}
$clientPath = [IO.Path]::GetFullPath($ClientApk)
$providerPath = [IO.Path]::GetFullPath($ProviderApk)
$clientBytes = (Get-Item -LiteralPath $clientPath).Length
$providerBytes = (Get-Item -LiteralPath $providerPath).Length
$clientSha256 = (Get-FileHash -LiteralPath $clientPath -Algorithm SHA256).Hash.ToLowerInvariant()
$providerSha256 = (Get-FileHash -LiteralPath $providerPath -Algorithm SHA256).Hash.ToLowerInvariant()

$model = Get-VerifiedArtifact $InferenceModel `
    "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898" `
    "Pixel 9a Whisper base Q5_1 model"
$english = Get-VerifiedArtifact $EnglishWav `
    "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e" `
    "Pinned whisper.cpp English WAV"
$spanish = Get-VerifiedArtifact $SpanishWav `
    "70ef4a2b564905d07f626af2adc2df958f9de584c120f3b9d2278158712d1d70" `
    "CC0 Spanish WAV"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$providerArchive = [IO.Compression.ZipFile]::OpenRead($providerPath)
try {
    $x86Native = $providerArchive.GetEntry("lib/x86_64/libaios_whisper_jni.so")
    if ($null -eq $x86Native -or $x86Native.Length -le 0) {
        throw "whisper.cpp provider APK lacks its x86_64 native runtime"
    }
} finally {
    $providerArchive.Dispose()
}

$clientPackage = "com.aios.modelbroker"
$providerPackage = "com.aios.runtime.whispercpp"
$activity = "$clientPackage/com.aios.runtime.smoke.WhisperProviderSmokeActivity"
$service = "$providerPackage/com.aios.runtime.whispercpp.WhisperRuntimeService"
foreach ($package in @($clientPackage, $providerPackage)) {
    $existing = ((& $adb -s $Serial shell pm path $package 2>$null) -join "`n").Trim()
    if ($existing) { throw "Refusing to replace an existing package: $package" }
}

$fixtureToken = [Guid]::NewGuid().ToString("N")
$remoteModel = "/data/local/tmp/aios-whisper-$fixtureToken.bin"
$remoteEnglish = "/data/local/tmp/aios-whisper-en-$fixtureToken.wav"
$remoteSpanish = "/data/local/tmp/aios-whisper-es-$fixtureToken.wav"
$providerModel = $null
$clientEnglish = $null
$clientSpanish = $null
$clientInstalled = $false
$providerInstalled = $false
$passed = $false

try {
    Invoke-Adb install $clientPath | Out-Null
    $clientInstalled = $true
    Invoke-Adb install $providerPath | Out-Null
    $providerInstalled = $true

    Invoke-Adb push $model.path $remoteModel | Out-Null
    Invoke-Adb push $english.path $remoteEnglish | Out-Null
    Invoke-Adb push $spanish.path $remoteSpanish | Out-Null
    $providerHome = (((Invoke-Adb shell run-as $providerPackage pwd) -join "`n")).Trim()
    $clientHome = (((Invoke-Adb shell run-as $clientPackage pwd) -join "`n")).Trim()
    if ($providerHome -notmatch '^/data/user/0/com\.aios\.runtime\.whispercpp$' -or
            $clientHome -notmatch '^/data/user/0/com\.aios\.modelbroker$') {
        throw "Debug package data directories are unexpected"
    }
    $providerModel = "$providerHome/files/emulator-models/runtime-smoke.bin"
    $clientEnglish = "$clientHome/files/asr-fixtures/english.wav"
    $clientSpanish = "$clientHome/files/asr-fixtures/spanish.wav"
    Invoke-Adb shell run-as $providerPackage mkdir -p files/emulator-models | Out-Null
    Invoke-Adb shell run-as $providerPackage cp $remoteModel `
        files/emulator-models/runtime-smoke.bin | Out-Null
    Invoke-Adb shell run-as $providerPackage chmod 600 `
        files/emulator-models/runtime-smoke.bin | Out-Null
    Invoke-Adb shell run-as $clientPackage mkdir -p files/asr-fixtures | Out-Null
    Invoke-Adb shell run-as $clientPackage cp $remoteEnglish `
        files/asr-fixtures/english.wav | Out-Null
    Invoke-Adb shell run-as $clientPackage cp $remoteSpanish `
        files/asr-fixtures/spanish.wav | Out-Null
    Invoke-Adb shell run-as $clientPackage chmod 600 files/asr-fixtures/english.wav `
        files/asr-fixtures/spanish.wav | Out-Null

    $stagedModelBytes = [long]((((Invoke-Adb shell run-as $providerPackage stat -c '%s' `
        files/emulator-models/runtime-smoke.bin) -join "`n")).Trim())
    $stagedEnglishBytes = [long]((((Invoke-Adb shell run-as $clientPackage stat -c '%s' `
        files/asr-fixtures/english.wav) -join "`n")).Trim())
    $stagedSpanishBytes = [long]((((Invoke-Adb shell run-as $clientPackage stat -c '%s' `
        files/asr-fixtures/spanish.wav) -join "`n")).Trim())
    if ($stagedModelBytes -ne $model.bytes -or
            $stagedEnglishBytes -ne $english.bytes -or
            $stagedSpanishBytes -ne $spanish.bytes) {
        throw "Staged ASR fixture sizes do not match the host artifacts"
    }

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $unauthorized = (& $adb -s $Serial shell am startservice -n $service 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorAction
    if ($unauthorized -notmatch 'Permission Denial|requires.*PROVIDE_MODEL_RUNTIME|not exported') {
        throw "Shell unexpectedly crossed the signature-only runtime-provider boundary"
    }

    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -n $activity `
        --es inference_model_path $providerModel `
        --el inference_model_size $model.bytes `
        --es inference_model_sha256 $model.sha256 `
        --es english_wav_path $clientEnglish `
        --es english_wav_sha256 $english.sha256 `
        --es spanish_wav_path $clientSpanish `
        --es spanish_wav_sha256 $spanish.sha256 | Out-Null
    $deadline = [DateTime]::UtcNow.AddMinutes(4)
    do {
        $log = (Invoke-Adb logcat -d -v brief) -join "`n"
        if ($log -match 'AIOS_WHISPER_PROVIDER_SMOKE_FAILED') {
            $relevant = ($log -split "`n" | Where-Object {
                $_ -match 'AiosWhisperProviderSmoke|WhisperRuntimeService|whisper'
            }) -join "`n"
            throw "Whisper-provider smoke fixture failed:`n$relevant"
        }
        if ($log -match 'AIOS_WHISPER_PROVIDER_SMOKE_OK' -and
                $log -match 'AIOS_WHISPER_REAL_ASR_OK') {
            $passed = $true
            break
        }
        Start-Sleep -Milliseconds 1000
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $passed) { throw "Timed out waiting for real bilingual ASR completion" }

    Invoke-Adb shell run-as $providerPackage rm -f `
        files/emulator-models/runtime-smoke.bin | Out-Null
    Invoke-Adb shell run-as $clientPackage rm -f files/asr-fixtures/english.wav `
        files/asr-fixtures/spanish.wav | Out-Null
    Invoke-Adb shell rm -f $remoteModel $remoteEnglish $remoteSpanish | Out-Null
    $remaining = @(
        @(
            Invoke-Adb shell run-as $providerPackage find files/emulator-models -type f
            Invoke-Adb shell run-as $clientPackage find files/asr-fixtures -type f
        ) | Where-Object { $_ }
    )
    if ($remaining.Count -ne 0) {
        throw "Whisper-provider smoke left model or audio fixtures behind"
    }

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $evidencePath = Join-Path $EvidenceDirectory "aios-emulator-whisper-provider-smoke.json"
    $evidence = [ordered]@{
        schema_version = 1
        serial = $Serial
        qemu = $true
        android_release = $androidRelease
        api_level = $apiLevel
        abi = $abi
        client_apk_bytes = $clientBytes
        client_apk_sha256 = $clientSha256
        provider_apk_bytes = $providerBytes
        provider_apk_sha256 = $providerSha256
        provider_apk_x86_64_native_entry_verified = $true
        production_whisper_provider_bound_cross_process = $true
        provider_api_version = 2
        runtime_id = "whisper_cpp"
        implementation_version = "1.9.4"
        signature_permission_rejected_shell = $true
        invalid_request_error_verified = $true
        product_model_path_confinement_verified = $true
        provider_survived_rejected_model = $true
        real_native_asr_executed = $true
        pixel9a_candidate_model_id = "whisper-base-multilingual-quantized"
        model_sha256 = $model.sha256
        model_bytes = $model.bytes
        english_fixture_sha256 = $english.sha256
        spanish_fixture_sha256 = $spanish.sha256
        english_language_detected = $true
        spanish_language_detected = $true
        nonempty_final_transcripts_verified = $true
        fixture_content_markers_verified = $true
        call_rx_pipeline_verified = $true
        source_audio_chunk_millis = 100
        wall_pace_per_chunk_millis = 250
        emulator_real_time_gate = $false
        transcript_output_recorded = $false
        temporary_fixture_files_remaining = 0
        arm64_provider_evidence = $false
        physical_gate_evidence = $false
        captured_at = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    }
    $json = $evidence | ConvertTo-Json -Depth 5
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($evidencePath), $json + "`n", `
        $utf8WithoutBom)
    Write-Output "AIOS emulator whisper-provider smoke passed: $evidencePath"
} finally {
    if ($providerInstalled) {
        & $adb -s $Serial shell run-as $providerPackage rm -f `
            files/emulator-models/runtime-smoke.bin | Out-Null
    }
    if ($clientInstalled) {
        & $adb -s $Serial shell run-as $clientPackage rm -f `
            files/asr-fixtures/english.wav files/asr-fixtures/spanish.wav | Out-Null
    }
    & $adb -s $Serial shell rm -f $remoteModel $remoteEnglish $remoteSpanish | Out-Null
    if ($providerInstalled -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $providerPackage | Out-Null
    }
    if ($clientInstalled -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $clientPackage | Out-Null
    }
}
