param(
    [string]$Serial = "emulator-5554",
    [string]$ClientApk = "$PSScriptRoot\..\preview\runtimeprovidercheck\build\outputs\apk\debug\runtimeprovidercheck-debug.apk",
    [string]$ProviderApk = "$PSScriptRoot\..\runtime\litertlmprovider\app\build\outputs\apk\debug\app-debug.apk",
    [string]$InferenceModel = "",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw "Refusing to run runtime-provider smoke checks on non-emulator serial: $Serial"
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

$state = ((Invoke-Adb get-state) -join "`n").Trim()
if ($state -ne "device") { throw "adb target is not ready: $Serial" }
$qemu = ((Invoke-Adb shell getprop ro.kernel.qemu) -join "`n").Trim()
if ($qemu -ne "1") { throw "Refusing to run: $Serial does not report ro.kernel.qemu=1" }
$androidRelease = ((Invoke-Adb shell getprop ro.build.version.release) -join "`n").Trim()
$apiLevel = [int](((Invoke-Adb shell getprop ro.build.version.sdk) -join "`n").Trim())
$abi = ((Invoke-Adb shell getprop ro.product.cpu.abi) -join "`n").Trim()
if ($apiLevel -lt 35) { throw "Runtime-provider smoke checks require Android API 35 or newer" }
if ($abi -ne "x86_64") { throw "This LiteRT-LM emulator smoke requires x86_64, got: $abi" }

foreach ($path in @($ClientApk, $ProviderApk)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Runtime smoke APK not found: $path" }
}
$clientPath = [IO.Path]::GetFullPath($ClientApk)
$providerPath = [IO.Path]::GetFullPath($ProviderApk)
$clientBytes = (Get-Item -LiteralPath $clientPath).Length
$providerBytes = (Get-Item -LiteralPath $providerPath).Length
$clientSha256 = (Get-FileHash -LiteralPath $clientPath -Algorithm SHA256).Hash.ToLowerInvariant()
$providerSha256 = (Get-FileHash -LiteralPath $providerPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($clientBytes -le 0 -or $providerBytes -le 0 -or
        $clientSha256 -notmatch '^[0-9a-f]{64}$' -or
        $providerSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Runtime smoke APK identity is invalid"
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$providerArchive = [IO.Compression.ZipFile]::OpenRead($providerPath)
try {
    $x86Native = $providerArchive.GetEntry("lib/x86_64/liblitertlm_jni.so")
    if ($null -eq $x86Native -or $x86Native.Length -le 0) {
        throw "LiteRT-LM provider APK lacks its x86_64 native runtime"
    }
} finally {
    $providerArchive.Dispose()
}

$clientPackage = "com.aios.modelbroker"
$providerPackage = "com.aios.runtime.litertlm"
$activity = "$clientPackage/com.aios.runtime.smoke.RuntimeProviderSmokeActivity"
$service = "$providerPackage/com.aios.runtime.litertlm.LiteRtLmRuntimeService"
foreach ($package in @($clientPackage, $providerPackage)) {
    $existing = ((& $adb -s $Serial shell pm path $package 2>$null) -join "`n").Trim()
    if ($existing) { throw "Refusing to replace an existing package: $package" }
}
$clientInstalled = $false
$providerInstalled = $false
$passed = $false
$runRealInference = -not [string]::IsNullOrWhiteSpace($InferenceModel)
$inferenceModelPath = $null
$inferenceModelBytes = 0L
$inferenceModelSha256 = $null
$fixtureToken = [Guid]::NewGuid().ToString("N")
$remoteInferenceModel = "/data/local/tmp/aios-litertlm-$fixtureToken.litertlm"
$providerInferenceModel = $null
if ($runRealInference) {
    if (-not (Test-Path -LiteralPath $InferenceModel -PathType Leaf)) {
        throw "Inference model not found: $InferenceModel"
    }
    $inferenceModelPath = [IO.Path]::GetFullPath($InferenceModel)
    if ([IO.Path]::GetExtension($inferenceModelPath) -ne ".litertlm") {
        throw "Real-inference smoke requires one .litertlm model"
    }
    $inferenceModelBytes = (Get-Item -LiteralPath $inferenceModelPath).Length
    $inferenceModelSha256 = (
        Get-FileHash -LiteralPath $inferenceModelPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    if ($inferenceModelBytes -le 0 -or
            $inferenceModelSha256 -notmatch '^[0-9a-f]{64}$') {
        throw "Inference model identity is invalid"
    }
}

try {
    Invoke-Adb install $clientPath | Out-Null
    $clientInstalled = $true
    Invoke-Adb install $providerPath | Out-Null
    $providerInstalled = $true

    if ($runRealInference) {
        Invoke-Adb push $inferenceModelPath $remoteInferenceModel | Out-Null
        $providerHome = ((
            Invoke-Adb shell run-as $providerPackage pwd
        ) -join "`n").Trim()
        if ($providerHome -notmatch '^/data/user/0/com\.aios\.runtime\.litertlm$') {
            throw "Provider debug data directory is unexpected: $providerHome"
        }
        $providerInferenceModel = "$providerHome/files/emulator-models/runtime-smoke.litertlm"
        Invoke-Adb shell run-as $providerPackage mkdir -p files/emulator-models | Out-Null
        Invoke-Adb shell run-as $providerPackage cp `
            $remoteInferenceModel files/emulator-models/runtime-smoke.litertlm | Out-Null
        Invoke-Adb shell run-as $providerPackage chmod `
            600 files/emulator-models/runtime-smoke.litertlm | Out-Null
        $copiedBytes = [long](((
            Invoke-Adb shell run-as $providerPackage stat -c '%s' `
                files/emulator-models/runtime-smoke.litertlm
        ) -join "`n").Trim())
        if ($copiedBytes -ne $inferenceModelBytes) {
            throw "Provider model copy size does not match the host artifact"
        }
    }

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $unauthorized = (& $adb -s $Serial shell am startservice -n $service 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorAction
    if ($unauthorized -notmatch 'Permission Denial|Requires permission com.aios.permission.PROVIDE_MODEL_RUNTIME') {
        throw "shell caller was not rejected by the provider service permission: $unauthorized"
    }

    Invoke-Adb logcat -c | Out-Null
    if ($runRealInference) {
        Invoke-Adb shell am start -W -n $activity `
            --es inference_model_path $providerInferenceModel `
            --el inference_model_size $inferenceModelBytes `
            --es inference_model_sha256 $inferenceModelSha256 | Out-Null
    } else {
        Invoke-Adb shell am start -W -n $activity | Out-Null
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $log = (Invoke-Adb logcat -d -v brief) -join "`n"
        if ($log -match 'AIOS_RUNTIME_PROVIDER_SMOKE_FAILED') {
            $relevant = ($log -split "`r?`n" | Where-Object {
                $_ -match 'AiosRuntimeProviderSmoke|AndroidRuntime'
            }) -join "`n"
            throw "Runtime-provider smoke fixture failed:`n$relevant"
        }
        if ($log -match 'AIOS_RUNTIME_PROVIDER_SMOKE_OK') {
            if ($runRealInference -and
                    $log -notmatch 'AIOS_RUNTIME_REAL_INFERENCE_OK') {
                throw "Runtime smoke completed without the real-inference marker"
            }
            $passed = $true
            break
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $passed) { throw "Timed out waiting for runtime-provider smoke completion" }

    if ($runRealInference) {
        Invoke-Adb shell run-as $providerPackage rm -f `
            files/emulator-models/runtime-smoke.litertlm | Out-Null
        Invoke-Adb shell rm -f $remoteInferenceModel | Out-Null
        $remainingProviderModels = @(
            Invoke-Adb shell run-as $providerPackage find `
                files/emulator-models -type f | Where-Object { $_ }
        )
        if ($remainingProviderModels.Count -ne 0) {
            throw "Runtime-provider smoke left temporary model weights behind"
        }
    }

    $privateFiles = (Invoke-Adb shell run-as $clientPackage find files -type f) -join "`n"
    if ($privateFiles -match 'not-model-weights') {
        throw "Runtime-provider smoke left temporary fixture bytes behind"
    }

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $evidencePath = Join-Path $EvidenceDirectory "aios-emulator-runtime-provider-smoke.json"
    $evidence = [ordered]@{
        schema_version = 2
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
        production_litert_lm_provider_bound_cross_process = $true
        provider_api_version = 2
        runtime_id = "litert_lm"
        implementation_version = "0.15.0"
        supported_backends = @("gpu", "cpu")
        signature_permission_rejected_shell = $true
        invalid_request_error_verified = $true
        backend_allowlist_verified = $true
        product_model_path_confinement_verified = $true
        single_terminal_callback_verified = $true
        bounded_path_redacted_error_verified = $true
        provider_survived_rejected_model = $true
        temporary_fixture_bytes_are_model_weights = $runRealInference
        real_inference_executed = $runRealInference
        real_inference_model_sha256 = if ($runRealInference) {
            $inferenceModelSha256
        } else { $null }
        real_inference_model_bytes = if ($runRealInference) {
            $inferenceModelBytes
        } else { $null }
        inference_output_recorded = $false
        arm64_provider_evidence = $false
        physical_gate_evidence = $false
        temporary_fixture_files_remaining = 0
        captured_at = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText(
        [IO.Path]::GetFullPath($evidencePath),
        ($evidence | ConvertTo-Json -Depth 4),
        $utf8WithoutBom)
    Write-Output "AIOS emulator runtime-provider smoke checks passed: $evidencePath"
} finally {
    if ($providerInstalled -and $runRealInference) {
        & $adb -s $Serial shell run-as $providerPackage rm -f `
            files/emulator-models/runtime-smoke.litertlm | Out-Null
    }
    & $adb -s $Serial shell rm -f $remoteInferenceModel | Out-Null
    if ($providerInstalled -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $providerPackage | Out-Null
    }
    if ($clientInstalled -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $clientPackage | Out-Null
    }
}

if (-not $KeepInstalled) {
    foreach ($package in @($clientPackage, $providerPackage)) {
        $remaining = ((& $adb -s $Serial shell pm path $package 2>$null) -join "`n").Trim()
        if ($remaining) { throw "Temporary runtime smoke package remains installed: $package" }
    }
}
