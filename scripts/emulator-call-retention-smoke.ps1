param(
    [string]$Serial = "emulator-5554",
    [string]$Apk = "$PSScriptRoot\..\preview\callservicecheck\build\outputs\apk\debug\callservicecheck-debug.apk",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw "Refusing to run call-retention smoke checks on non-emulator serial: $Serial"
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$sourceRevision = ((& git -C $repositoryRoot rev-parse HEAD) -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $sourceRevision -notmatch '^[0-9a-f]{40}$') {
    throw "Unable to bind call-retention evidence to an exact AIOS revision"
}
& git -C $repositoryRoot diff --quiet --
$unstagedChanges = $LASTEXITCODE
& git -C $repositoryRoot diff --cached --quiet --
$stagedChanges = $LASTEXITCODE
if ($unstagedChanges -ne 0 -or $stagedChanges -ne 0) {
    throw "Refusing to capture call-retention evidence with tracked source changes"
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
    throw "Call-retention smoke checks require Android API 35 or newer"
}
if (-not (Test-Path -LiteralPath $Apk)) {
    throw "Call Intelligence smoke APK not found: $Apk"
}
$apkPath = [IO.Path]::GetFullPath($Apk)
$apkBytes = (Get-Item -LiteralPath $apkPath).Length
$apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkBytes -le 0 -or $apkSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Call Intelligence smoke APK identity is invalid"
}

$package = "com.aios.callintelligence.compilecheck"
$activity = "$package/com.aios.callintelligence.CallRetentionSmokeActivity"
$installed = $false
$passed = $false

try {
    Invoke-Adb install -r $apkPath | Out-Null
    $installed = $true
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -n $activity | Out-Null

    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $log = (Invoke-Adb logcat -d -v brief) -join "`n"
        if ($log -match 'AIOS_CALL_RETENTION_SMOKE_FAILED') {
            $relevant = ($log -split "`r?`n" | Where-Object {
                $_ -match 'AiosCallRetentionSmoke'
            }) -join "`n"
            throw "Call-retention smoke fixture failed:`n$relevant"
        }
        if ($log -match 'AIOS_CALL_RETENTION_SMOKE_OK') {
            $passed = $true
            break
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $passed) {
        throw "Timed out waiting for call-retention smoke completion"
    }

    $privateFiles = (Invoke-Adb shell run-as $package find files -type f) -join "`n"
    if ($privateFiles -match '(^|/)calls/') {
        throw "Call-retention smoke left private call artifacts behind"
    }

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $evidencePath = Join-Path $EvidenceDirectory "aios-emulator-call-retention-smoke.json"
    $evidence = [ordered]@{
        schema_version = 1
        gate = "integration.emulator_call_retention"
        aios_revision = $sourceRevision
        tracked_source_clean = $true
        serial = $Serial
        qemu = $true
        android_release = $androidRelease
        api_level = $apiLevel
        apk_bytes = $apkBytes
        apk_sha256 = $apkSha256
        exact_retention_hours = 24
        active_writer_closed_before_expiry_delete = $true
        wall_clock_expiry_deleted = $true
        unreadable_metadata_deleted_fail_closed = $true
        fresh_artifact_preserved = $true
        resumed_artifact_deadline_unchanged = $true
        explicit_delete_verified = $true
        nearest_elapsed_alarm_verified = $true
        empty_store_alarm_cancel_path_exercised = $true
        private_artifacts_remaining = 0
        physical_gate_evidence = $false
        captured_at = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText(
        [IO.Path]::GetFullPath($evidencePath),
        ($evidence | ConvertTo-Json -Depth 4),
        $utf8WithoutBom)
    Write-Output "AIOS emulator call-retention smoke checks passed: $evidencePath"
} finally {
    if ($installed -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $package | Out-Null
    }
}

if (-not $KeepInstalled) {
    $remaining = ((& $adb -s $Serial shell pm path $package) -join "`n").Trim()
    if ($LASTEXITCODE -eq 0 -and $remaining) {
        throw "Temporary Call Intelligence smoke APK remains installed"
    }
}
