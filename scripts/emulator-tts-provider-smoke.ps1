param(
    [string]$Serial = "emulator-5554",
    [string]$ClientApk = "$PSScriptRoot\..\preview\runtimeprovidercheck\build\outputs\apk\debug\runtimeprovidercheck-debug.apk",
    [string]$ProviderApk = "$PSScriptRoot\..\runtime\ttsprovider\app\build\outputs\apk\debug\app-debug.apk",
    [string]$FixtureDirectory = "$PSScriptRoot\..\.cache\tts-emulator-fixtures",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw "Refusing to run TTS-provider smoke checks on non-emulator serial: $Serial"
}
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$sourceRevision = ((& git -C $repositoryRoot rev-parse HEAD) -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $sourceRevision -notmatch '^[0-9a-f]{40}$') {
    throw "Unable to bind TTS smoke evidence to an exact AIOS revision"
}
& git -C $repositoryRoot diff --quiet --
$unstagedChanges = $LASTEXITCODE
& git -C $repositoryRoot diff --cached --quiet --
$stagedChanges = $LASTEXITCODE
if ($unstagedChanges -ne 0 -or $stagedChanges -ne 0) {
    throw "Refusing to capture TTS smoke evidence with tracked source changes"
}
$androidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$adb = Join-Path $androidHome "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }

function Invoke-Adb {
    $output = & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed ($LASTEXITCODE): $($args -join ' ')" }
    return $output
}

$state = ((Invoke-Adb get-state) -join "`n").Trim()
if ($state -ne "device") { throw "adb target is not ready: $Serial" }
$qemu = ((Invoke-Adb shell getprop ro.kernel.qemu) -join "`n").Trim()
if ($qemu -ne "1") { throw "Refusing to run: $Serial does not report ro.kernel.qemu=1" }
$androidRelease = ((Invoke-Adb shell getprop ro.build.version.release) -join "`n").Trim()
$apiLevel = [int](((Invoke-Adb shell getprop ro.build.version.sdk) -join "`n").Trim())
$abi = ((Invoke-Adb shell getprop ro.product.cpu.abi) -join "`n").Trim()
if ($apiLevel -lt 35) { throw "TTS-provider smoke requires Android API 35 or newer" }
if ($abi -ne "x86_64") { throw "TTS-provider emulator smoke requires x86_64, got: $abi" }

foreach ($path in @($ClientApk, $ProviderApk)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "TTS smoke APK not found: $path"
    }
}
$clientPath = [IO.Path]::GetFullPath($ClientApk)
$providerPath = [IO.Path]::GetFullPath($ProviderApk)
$clientBytes = (Get-Item -LiteralPath $clientPath).Length
$providerBytes = (Get-Item -LiteralPath $providerPath).Length
$clientSha256 = (Get-FileHash -LiteralPath $clientPath -Algorithm SHA256).Hash.ToLowerInvariant()
$providerSha256 = (Get-FileHash -LiteralPath $providerPath -Algorithm SHA256).Hash.ToLowerInvariant()

Add-Type -AssemblyName System.IO.Compression.FileSystem
$providerArchive = [IO.Compression.ZipFile]::OpenRead($providerPath)
try {
    foreach ($library in @("libonnxruntime.so", "libsherpa-onnx-c-api.so",
            "libsherpa-onnx-cxx-api.so", "libsherpa-onnx-jni.so")) {
        $entry = $providerArchive.GetEntry("lib/x86_64/$library")
        if ($null -eq $entry -or $entry.Length -le 0) {
            throw "Sherpa TTS provider APK lacks x86_64/$library"
        }
    }
    if ($null -ne $providerArchive.GetEntry("lib/arm64-v8a/libsherpa-onnx-jni.so")) {
        throw "TTS debug APK unexpectedly contains the release ARM64 runtime"
    }
} finally {
    $providerArchive.Dispose()
}

$catalog = Get-Content -LiteralPath "$PSScriptRoot\..\config\model_catalog.json" -Raw |
    ConvertFrom-Json
$models = @($catalog.models | Where-Object { $_.id -eq "supertonic3-en-es-int8" })
if ($models.Count -ne 1) { throw "The bilingual Supertonic catalog entry is ambiguous" }
$model = $models[0]
if ($model.reference_bundle.sha256 -ne
        "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427") {
    throw "The reviewed Supertonic archive identity changed"
}
$fixtureRoot = [IO.Path]::GetFullPath($FixtureDirectory)
$bundleDirectory = Join-Path $fixtureRoot "supertonic3-en-es-int8"
$descriptorPath = Join-Path $fixtureRoot "supertonic3-en-es-int8.bundle.json"
if (-not (Test-Path -LiteralPath $descriptorPath -PathType Leaf)) {
    throw "TTS fixtures are absent; review the model license and run bootstrap-emulator-tts-fixtures.ps1"
}
$descriptorValue = Get-Content -LiteralPath $descriptorPath -Raw | ConvertFrom-Json
if ($descriptorValue.schema_version -ne 1 -or
        $descriptorValue.model_id -ne "supertonic3-en-es-int8" -or
        $descriptorValue.source_archive_sha256 -ne $model.reference_bundle.sha256 -or
        @($descriptorValue.members).Count -ne @($model.reference_bundle.members).Count) {
    throw "TTS fixture descriptor identity is invalid"
}
$memberFiles = @()
foreach ($locked in $model.reference_bundle.members) {
    $records = @($descriptorValue.members | Where-Object { $_.name -eq $locked.path })
    if ($records.Count -ne 1) { throw "TTS descriptor member is absent or duplicated" }
    $record = $records[0]
    $path = Join-Path $bundleDirectory ([string]$locked.path)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "TTS fixture member is absent: $($locked.path)"
    }
    $file = Get-Item -LiteralPath $path
    $digest = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($file.Length -ne [long]$locked.size_bytes -or $digest -ne $locked.sha256 -or
            $record.relative_path -ne "models/supertonic3-en-es-int8/$($locked.path)" -or
            [long]$record.size_bytes -ne [long]$locked.size_bytes -or
            $record.sha256 -ne $locked.sha256) {
        throw "TTS fixture member failed its catalog/descriptor lock: $($locked.path)"
    }
    $memberFiles += [PSCustomObject]@{
        name = [string]$locked.path
        path = [IO.Path]::GetFullPath($path)
        bytes = $file.Length
        sha256 = $digest
    }
}
$descriptorBytes = (Get-Item -LiteralPath $descriptorPath).Length
$descriptorSha256 = (Get-FileHash -LiteralPath $descriptorPath -Algorithm SHA256).Hash.ToLowerInvariant()

$clientPackage = "com.aios.modelbroker"
$providerPackage = "com.aios.runtime.sherpatts"
$activity = "$clientPackage/com.aios.runtime.smoke.TtsProviderSmokeActivity"
$service = "$providerPackage/com.aios.runtime.sherpatts.SherpaTtsRuntimeService"
foreach ($package in @($clientPackage, $providerPackage)) {
    $existing = ((& $adb -s $Serial shell pm path $package 2>$null) -join "`n").Trim()
    if ($existing) { throw "Refusing to replace an existing package: $package" }
}

$token = [Guid]::NewGuid().ToString("N")
$remoteRoot = "/data/local/tmp/aios-tts-$token"
$clientInstalled = $false
$providerInstalled = $false
$passed = $false

function Remove-ProviderFixtures {
    if (-not $providerInstalled) { return }
    & $adb -s $Serial shell run-as $providerPackage rm -rf `
        files/emulator-config | Out-Null
}

try {
    Invoke-Adb install $clientPath | Out-Null
    $clientInstalled = $true
    Invoke-Adb install $providerPath | Out-Null
    $providerInstalled = $true
    Invoke-Adb shell mkdir -p $remoteRoot | Out-Null
    Invoke-Adb push $descriptorPath "$remoteRoot/descriptor.json" | Out-Null
    foreach ($member in $memberFiles) {
        Invoke-Adb push $member.path "$remoteRoot/$($member.name)" | Out-Null
    }

    $providerHome = (((Invoke-Adb shell run-as $providerPackage pwd) -join "`n")).Trim()
    if ($providerHome -notmatch '^/data/user/0/com\.aios\.runtime\.sherpatts$') {
        throw "Debug TTS provider data directory is unexpected"
    }
    Invoke-Adb shell run-as $providerPackage mkdir -p `
        files/emulator-config/models/supertonic3-en-es-int8 | Out-Null
    Invoke-Adb shell run-as $providerPackage cp "$remoteRoot/descriptor.json" `
        files/emulator-config/models/supertonic3-en-es-int8.bundle.json | Out-Null
    foreach ($member in $memberFiles) {
        Invoke-Adb shell run-as $providerPackage cp "$remoteRoot/$($member.name)" `
            "files/emulator-config/models/supertonic3-en-es-int8/$($member.name)" | Out-Null
    }

    $stagedDescriptorBytes = [long]((((Invoke-Adb shell run-as $providerPackage stat -c '%s' `
        files/emulator-config/models/supertonic3-en-es-int8.bundle.json) -join "`n")).Trim())
    if ($stagedDescriptorBytes -ne $descriptorBytes) {
        throw "Staged TTS descriptor size changed"
    }
    foreach ($member in $memberFiles) {
        $stagedBytes = [long]((((Invoke-Adb shell run-as $providerPackage stat -c '%s' `
            "files/emulator-config/models/supertonic3-en-es-int8/$($member.name)") -join "`n")).Trim())
        if ($stagedBytes -ne $member.bytes) { throw "Staged TTS member size changed" }
    }

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $unauthorized = (& $adb -s $Serial shell am startservice -n $service 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorAction
    if ($unauthorized -notmatch 'Permission Denial|requires.*PROVIDE_MODEL_RUNTIME|not exported') {
        throw "Shell unexpectedly crossed the signature-only TTS-provider boundary"
    }

    $privateDescriptor = "$providerHome/files/emulator-config/models/supertonic3-en-es-int8.bundle.json"
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -n $activity `
        --es tts_descriptor_path $privateDescriptor `
        --el tts_descriptor_size $descriptorBytes `
        --es tts_descriptor_sha256 $descriptorSha256 | Out-Null
    $deadline = [DateTime]::UtcNow.AddMinutes(10)
    do {
        $log = (Invoke-Adb logcat -d -v brief) -join "`n"
        if ($log -match 'AIOS_TTS_PROVIDER_SMOKE_FAILED') {
            $relevant = ($log -split "`n" | Where-Object {
                $_ -match 'AiosTtsProviderSmoke|SherpaTtsRuntimeService|sherpa|onnx'
            }) -join "`n"
            throw "TTS-provider smoke fixture failed:`n$relevant"
        }
        if ($log -match 'AIOS_TTS_PROVIDER_SMOKE_OK' -and
                $log -match 'AIOS_TTS_REAL_BILINGUAL_OK') {
            $passed = $true
            break
        }
        Start-Sleep -Milliseconds 1000
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $passed) { throw "Timed out waiting for real bilingual TTS completion" }

    Remove-ProviderFixtures
    Invoke-Adb shell rm -rf $remoteRoot | Out-Null
    $remaining = @(
        @(Invoke-Adb shell run-as $providerPackage find files -type f) |
            Where-Object { $_ -like 'files/emulator-config/*' }
    )
    if ($remaining.Count -ne 0) { throw "TTS smoke left model fixtures behind" }

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $evidencePath = Join-Path $EvidenceDirectory "aios-emulator-tts-provider-smoke.json"
    $evidence = [ordered]@{
        schema_version = 1
        gate = "integration.emulator_bilingual_tts_provider"
        aios_revision = $sourceRevision
        tracked_source_clean = $true
        serial = $Serial
        qemu = $true
        android_release = $androidRelease
        api_level = $apiLevel
        abi = $abi
        client_apk_bytes = $clientBytes
        client_apk_sha256 = $clientSha256
        provider_apk_bytes = $providerBytes
        provider_apk_sha256 = $providerSha256
        provider_apk_x86_64_native_entries_verified = $true
        production_tts_provider_bound_cross_process = $true
        provider_api_version = 2
        runtime_id = "sherpa_onnx_tts"
        implementation_version = "1.13.4"
        signature_permission_rejected_shell = $true
        invalid_request_error_verified = $true
        product_model_path_confinement_verified = $true
        provider_survived_rejected_model = $true
        real_native_tts_executed = $true
        model_id = "supertonic3-en-es-int8"
        source_archive_sha256 = [string]$model.reference_bundle.sha256
        descriptor_sha256 = $descriptorSha256
        bundle_member_digests_verified = $true
        english_pcm_verified = $true
        spanish_pcm_verified = $true
        pcm_metadata_matches_stream = $true
        pcm_content_recorded = $false
        temporary_fixture_files_remaining = 0
        arm64_provider_evidence = $false
        physical_gate_evidence = $false
        captured_at = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    }
    $json = $evidence | ConvertTo-Json -Depth 5
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($evidencePath), $json + "`n", `
        $utf8WithoutBom)
    Write-Output "AIOS emulator TTS-provider smoke passed: $evidencePath"
} finally {
    Remove-ProviderFixtures
    & $adb -s $Serial shell rm -rf $remoteRoot | Out-Null
    if ($providerInstalled -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $providerPackage | Out-Null
    }
    if ($clientInstalled -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $clientPackage | Out-Null
    }
}
