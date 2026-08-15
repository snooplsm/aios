param(
    [string]$Serial = "emulator-5554",
    [string]$Apk = "$PSScriptRoot\..\preview\callcontextcheck\build\outputs\apk\debug\callcontextcheck-debug.apk",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw "Refusing to run context-lifecycle smoke checks on non-emulator serial: $Serial"
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$sourceRevision = ((& git -C $repositoryRoot rev-parse HEAD) -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $sourceRevision -notmatch '^[0-9a-f]{40}$') {
    throw "Unable to bind context-lifecycle evidence to an exact AIOS revision"
}
& git -C $repositoryRoot diff --quiet --
$unstagedChanges = $LASTEXITCODE
& git -C $repositoryRoot diff --cached --quiet --
$stagedChanges = $LASTEXITCODE
if ($unstagedChanges -ne 0 -or $stagedChanges -ne 0) {
    throw "Refusing to capture context-lifecycle evidence with tracked source changes"
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
    throw "Context-lifecycle smoke checks require Android API 35 or newer"
}
if (-not (Test-Path -LiteralPath $Apk)) {
    throw "Communication Context smoke APK not found: $Apk"
}
$apkPath = [IO.Path]::GetFullPath($Apk)
$apkBytes = (Get-Item -LiteralPath $apkPath).Length
$apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkBytes -le 0 -or $apkSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Communication Context smoke APK identity is invalid"
}

$package = "com.aios.callintelligence"
$activity = "$package/com.aios.contextintelligence.ContextLifecycleSmokeActivity"
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
        if ($log -match 'AIOS_CONTEXT_LIFECYCLE_SMOKE_FAILED') {
            $relevant = ($log -split "`r?`n" | Where-Object {
                $_ -match 'AiosContextSmoke|AndroidRuntime'
            }) -join "`n"
            throw "Communication Context smoke fixture failed:`n$relevant"
        }
        if ($log -match 'AIOS_CONTEXT_LIFECYCLE_SMOKE_OK') {
            $passed = $true
            break
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $passed) {
        throw "Timed out waiting for Communication Context smoke completion"
    }

    $privateFiles = (Invoke-Adb shell run-as $package find . -type f) -join "`n"
    if ($privateFiles -match 'communication_context\.db|opaque_identity') {
        throw "Communication Context smoke left its private database or identity secret behind"
    }

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $evidencePath = Join-Path $EvidenceDirectory "aios-emulator-context-lifecycle-smoke.json"
    $evidence = [ordered]@{
        schema_version = 1
        gate = "integration.emulator_context_lifecycle"
        aios_revision = $sourceRevision
        tracked_source_clean = $true
        serial = $Serial
        qemu = $true
        android_release = $androidRelease
        api_level = $apiLevel
        apk_bytes = $apkBytes
        apk_sha256 = $apkSha256
        production_aidl_service_bound = $true
        opaque_identity_verified = $true
        equivalent_number_convergence_verified = $true
        sqlite_fts_verified = $true
        hybrid_retrieval_verified = $true
        source_scoped_retrieval_verified = $true
        query_limit_verified = $true
        sms_revision_and_tombstone_verified = $true
        media_bulk_delete_watermark_verified = $true
        call_artifact_binder_tombstone_verified = $true
        call_artifact_24h_expiry_verified = $true
        raw_address_absent_from_database = $true
        private_context_state_remaining = 0
        physical_gate_evidence = $false
        captured_at = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText(
        [IO.Path]::GetFullPath($evidencePath),
        ($evidence | ConvertTo-Json -Depth 4),
        $utf8WithoutBom)
    Write-Output "AIOS emulator Communication Context smoke checks passed: $evidencePath"
} finally {
    if ($installed -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $package | Out-Null
    }
}

if (-not $KeepInstalled) {
    $remaining = ((& $adb -s $Serial shell pm path $package 2>$null) -join "`n").Trim()
    if ($remaining) {
        throw "Temporary Communication Context smoke APK remains installed"
    }
}
