param(
    [string]$Serial = "emulator-5554",
    [string]$Apk = "$PSScriptRoot\..\preview\modelservicecheck\build\outputs\apk\debug\modelservicecheck-debug.apk",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw "Refusing to run model-admission smoke checks on non-emulator serial: $Serial"
}

$androidHome = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$adb = Join-Path $androidHome "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb not found at $adb"
}

function Invoke-Adb {
    $output = & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($args -join ' ')"
    }
    return $output
}

$state = ((Invoke-Adb get-state) -join "`n").Trim()
if ($state -ne "device") {
    throw "adb target is not ready: $Serial"
}
$qemu = ((Invoke-Adb shell getprop ro.kernel.qemu) -join "`n").Trim()
if ($qemu -ne "1") {
    throw "Refusing to run: $Serial does not report ro.kernel.qemu=1"
}
$androidRelease = ((Invoke-Adb shell getprop ro.build.version.release) -join "`n").Trim()
$apiLevel = [int](((Invoke-Adb shell getprop ro.build.version.sdk) -join "`n").Trim())
if ($apiLevel -lt 35) {
    throw "Model-admission smoke checks require Android API 35 or newer"
}
if (-not (Test-Path -LiteralPath $Apk)) {
    throw "Model Broker smoke APK not found: $Apk"
}
$apkPath = [IO.Path]::GetFullPath($Apk)
$apkBytes = (Get-Item -LiteralPath $apkPath).Length
$apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkBytes -le 0 -or $apkSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Model Broker smoke APK identity is invalid"
}

$package = "com.aios.modelbenchmark"
$activity = "$package/com.aios.modelbroker.ModelAdmissionSmokeActivity"
$existing = ((& $adb -s $Serial shell pm path $package 2>$null) -join "`n").Trim()
if ($existing) {
    throw "Refusing to replace an existing production-identity package: $package"
}
$installed = $false
$passed = $false

try {
    Invoke-Adb install $apkPath | Out-Null
    $installed = $true
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -n $activity | Out-Null

    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $log = (Invoke-Adb logcat -d -v brief) -join "`n"
        if ($log -match 'AIOS_MODEL_ADMISSION_SMOKE_FAILED') {
            $relevant = ($log -split "`r?`n" | Where-Object {
                $_ -match 'AiosModelAdmissionSmoke|AndroidRuntime'
            }) -join "`n"
            throw "Model-admission smoke fixture failed:`n$relevant"
        }
        if ($log -match 'AIOS_MODEL_ADMISSION_SMOKE_OK') {
            $passed = $true
            break
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $passed) {
        throw "Timed out waiting for Model Broker admission smoke completion"
    }

    $privateFiles = (Invoke-Adb shell run-as $package find no_backup -type f) -join "`n"
    if ($privateFiles -match 'model-admission-smoke') {
        throw "Model-admission smoke left temporary artifact fixtures behind"
    }

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $evidencePath = Join-Path $EvidenceDirectory "aios-emulator-model-admission-smoke.json"
    $evidence = [ordered]@{
        schema_version = 1
        serial = $Serial
        qemu = $true
        android_release = $androidRelease
        api_level = $apiLevel
        apk_bytes = $apkBytes
        apk_sha256 = $apkSha256
        production_broker_aidl_bound = $true
        stock_install_without_product_policy_denied = $true
        artifact_digest_match_accepted = $true
        same_size_artifact_tamper_rejected = $true
        canonical_path_escape_rejected = $true
        ram_tier_catalog_selection_verified = $true
        release_device_admission_verified = $true
        build_fingerprint_mismatch_rejected = $true
        debug_research_candidate_gating_verified = $true
        authorized_client_quota_verified = $true
        temporary_fixture_bytes_are_model_weights = $false
        real_inference_executed = $false
        physical_gate_evidence = $false
        temporary_fixture_files_remaining = 0
        captured_at = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText(
        [IO.Path]::GetFullPath($evidencePath),
        ($evidence | ConvertTo-Json -Depth 4),
        $utf8WithoutBom)
    Write-Output "AIOS emulator Model Broker admission smoke checks passed: $evidencePath"
} finally {
    if ($installed -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $package | Out-Null
    }
}

if (-not $KeepInstalled) {
    $remaining = ((& $adb -s $Serial shell pm path $package 2>$null) -join "`n").Trim()
    if ($remaining) {
        throw "Temporary Model Broker smoke APK remains installed"
    }
}
