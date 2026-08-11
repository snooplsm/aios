param(
    [string]$Serial = "emulator-5554",
    [string]$Apk = "$PSScriptRoot\..\preview\mediascancheck\build\outputs\apk\debug\mediascancheck-debug.apk",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw "Refusing to run Media Intelligence smoke checks on non-emulator serial: $Serial"
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

function Wait-SmokeMarker {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Success,

        [Parameter(Mandatory = $true)]
        [string]$Failure
    )

    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $log = (Invoke-Adb logcat -d -v brief) -join "`n"
        if ($log -match [regex]::Escape($Failure)) {
            $relevant = ($log -split "`r?`n" | Where-Object {
                ($_ -match 'AiosVideo(Mux|Recovery)Smoke') -or
                    ($_ -match 'AiosMediaPolicySmoke') -or
                    ($_ -match 'AiosMediaRecoverySmoke')
            }) -join "`n"
            throw "Media smoke fixture failed:`n$relevant"
        }
        if ($log -match [regex]::Escape($Success)) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "Timed out waiting for media smoke marker: $Success"
}

function Find-FixtureUri {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DisplayName
    )

    $rows = @(
        Invoke-Adb shell content query `
            --uri content://media/external/video/media `
            --projection _id:_display_name |
            Where-Object {
                $_ -match "(?:^|, )_display_name=$([regex]::Escape($DisplayName))(?:,|$)"
            }
    )
    if ($rows.Count -gt 1) {
        throw "MediaStore returned duplicate rows for the unique smoke fixture"
    }
    if ($rows.Count -eq 0) {
        return $null
    }
    $id = [regex]::Match($rows[0], '(?:^| )_id=([0-9]+)(?:,|$)')
    if (-not $id.Success) {
        throw "MediaStore returned a malformed smoke fixture row"
    }
    return "content://media/external/video/media/$($id.Groups[1].Value)"
}

function Find-ImageFixtureUri {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DisplayName
    )

    $rows = @(
        Invoke-Adb shell content query `
            --uri content://media/external/images/media `
            --projection _id:_display_name |
            Where-Object {
                $_ -match "(?:^|, )_display_name=$([regex]::Escape($DisplayName))(?:,|$)"
            }
    )
    if ($rows.Count -gt 1) {
        throw "MediaStore returned duplicate image fixtures"
    }
    if ($rows.Count -eq 0) {
        return $null
    }
    $id = [regex]::Match($rows[0], '(?:^| )_id=([0-9]+)(?:,|$)')
    if (-not $id.Success) {
        throw "MediaStore returned a malformed image fixture row"
    }
    return "content://media/external/images/media/$($id.Groups[1].Value)"
}

function New-SmokeImage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RemotePath,

        [Parameter(Mandatory = $true)]
        [string]$DisplayName
    )

    Invoke-Adb shell screencap -p $RemotePath | Out-Null
    $size = [long](((Invoke-Adb shell stat -c '%s' $RemotePath) -join "`n").Trim())
    if ($size -le 0) {
        throw "Emulator screencap produced an empty image fixture"
    }
    Invoke-Adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE `
        -d "file://$RemotePath" | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $uri = Find-ImageFixtureUri -DisplayName $DisplayName
        if ($uri) { return $uri }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "MediaStore did not discover image fixture: $DisplayName"
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
    throw "Media smoke checks require Android API 35 or newer"
}
if (-not (Test-Path -LiteralPath $Apk)) {
    throw "Media smoke APK not found: $Apk"
}
$apkPath = [IO.Path]::GetFullPath($Apk)
$apkBytes = (Get-Item -LiteralPath $apkPath).Length
$apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkBytes -le 0 -or $apkSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Media smoke APK identity is invalid"
}

$package = "com.aios.mediascancheck"
$policyActivity = "$package/com.aios.mediaintelligence.MediaPolicySmokeActivity"
$observerRecoveryActivity = "$package/com.aios.mediaintelligence.MediaObserverRecoverySmokeActivity"
$muxActivity = "$package/com.aios.mediaintelligence.VideoMuxerSmokeActivity"
$recoveryActivity = "$package/com.aios.mediaintelligence.VideoExportRecoverySmokeActivity"
$fixtureToken = [Guid]::NewGuid().ToString("N")
$fixtureName = "aios_mux_source_$fixtureToken.mp4"
$remoteDirectory = "/sdcard/Movies/AIOSSmoke"
$remoteFixture = "$remoteDirectory/$fixtureName"
$imageDirectory = "/sdcard/Pictures/AIOSSmoke"
$historicalName = "aios_historical_$fixtureToken.png"
$firstBurstName = "aios_restart_burst_1_$fixtureToken.png"
$secondBurstName = "aios_restart_burst_2_$fixtureToken.png"
$historicalPath = "$imageDirectory/$historicalName"
$firstBurstPath = "$imageDirectory/$firstBurstName"
$secondBurstPath = "$imageDirectory/$secondBurstName"
$fixtureUri = $null
$historicalUri = $null
$firstBurstUri = $null
$secondBurstUri = $null
$installed = $false

try {
    Invoke-Adb install -r -g $apkPath | Out-Null
    $installed = $true
    Invoke-Adb shell pm clear $package | Out-Null
    Invoke-Adb shell pm grant $package android.permission.READ_MEDIA_IMAGES | Out-Null
    Invoke-Adb shell pm grant $package android.permission.READ_MEDIA_VIDEO | Out-Null

    Invoke-Adb shell mkdir -p $imageDirectory | Out-Null
    $historicalUri = New-SmokeImage `
        -RemotePath $historicalPath -DisplayName $historicalName
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -n $observerRecoveryActivity `
        --es action baseline | Out-Null
    Wait-SmokeMarker `
        -Success "AIOS_MEDIA_RECOVERY_BASELINE_OK" `
        -Failure "AIOS_MEDIA_RECOVERY_FAILED"

    Invoke-Adb shell am force-stop $package | Out-Null
    $firstBurstUri = New-SmokeImage `
        -RemotePath $firstBurstPath -DisplayName $firstBurstName
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -n $observerRecoveryActivity `
        --es action start_observer | Out-Null
    Wait-SmokeMarker `
        -Success "AIOS_MEDIA_RECOVERY_OBSERVER_STARTED" `
        -Failure "AIOS_MEDIA_RECOVERY_FAILED"
    $secondBurstUri = New-SmokeImage `
        -RemotePath $secondBurstPath -DisplayName $secondBurstName
    Start-Sleep -Seconds 7
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -n $observerRecoveryActivity `
        --es action assert `
        --es historical_name $historicalName `
        --es first_name $firstBurstName `
        --es second_name $secondBurstName | Out-Null
    Wait-SmokeMarker `
        -Success "AIOS_MEDIA_RECOVERY_ASSERT_OK" `
        -Failure "AIOS_MEDIA_RECOVERY_FAILED"

    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -n $policyActivity | Out-Null
    Wait-SmokeMarker `
        -Success "AIOS_MEDIA_POLICY_SMOKE_OK" `
        -Failure "AIOS_MEDIA_POLICY_SMOKE_FAILED"

    Invoke-Adb shell mkdir -p $remoteDirectory | Out-Null
    Invoke-Adb shell screenrecord --time-limit 2 --bit-rate 1000000 $remoteFixture |
        Out-Null
    $size = [long](((Invoke-Adb shell stat -c '%s' $remoteFixture) -join "`n").Trim())
    if ($size -le 0) {
        throw "Emulator screenrecord produced an empty MP4 fixture"
    }

    Invoke-Adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE `
        -d "file://$remoteFixture" | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $fixtureUri = Find-FixtureUri -DisplayName $fixtureName
        if ($fixtureUri) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $fixtureUri) {
        throw "MediaStore did not discover the temporary MP4 fixture"
    }

    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -a android.intent.action.VIEW `
        --grant-read-uri-permission -d $fixtureUri -n $muxActivity | Out-Null
    Wait-SmokeMarker -Success "AIOS_MUX_SMOKE_OK" -Failure "AIOS_MUX_SMOKE_FAILED"

    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start -W -a android.intent.action.VIEW `
        --grant-read-uri-permission -d $fixtureUri -n $recoveryActivity | Out-Null
    Wait-SmokeMarker `
        -Success "AIOS_VIDEO_RECOVERY_SMOKE_OK" `
        -Failure "AIOS_VIDEO_RECOVERY_SMOKE_FAILED"

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $evidencePath = Join-Path $EvidenceDirectory "aios-emulator-media-smoke.json"
    $evidence = [ordered]@{
        schema_version = 3
        serial = $Serial
        qemu = $true
        android_release = $androidRelease
        api_level = $apiLevel
        apk_bytes = $apkBytes
        apk_sha256 = $apkSha256
        fixture_bytes = $size
        isolated_photo_immediate = $true
        photo_burst_deferred = $true
        restart_burst_settlement_verified = $true
        historical_library_not_imported = $true
        video_deferred = $true
        deferred_requires_charging = $true
        deferred_requires_80_percent = $true
        active_call_preempts_media = $true
        android_job_constraints_verified = $true
        mux_and_embedded_metadata_round_trip = $true
        encoded_source_samples_verified = $true
        timed_subtitle_metadata_read = $true
        subtitle_renderer_exercised = $false
        interrupted_export_recovery = $true
        original_opened_writable = $false
        physical_gate_evidence = $false
        captured_at = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText(
        [IO.Path]::GetFullPath($evidencePath),
        ($evidence | ConvertTo-Json -Depth 4),
        $utf8WithoutBom)
    Write-Output "AIOS emulator media smoke checks passed: $evidencePath"
} finally {
    if (-not $fixtureUri) {
        try {
            $fixtureUri = Find-FixtureUri -DisplayName $fixtureName
        } catch {
            $fixtureUri = $null
        }
    }
    if ($fixtureUri -and $fixtureUri -match '^content://media/external/video/media/[0-9]+$') {
        & $adb -s $Serial shell content delete --uri $fixtureUri | Out-Null
    }
    foreach ($imageUri in @($historicalUri, $firstBurstUri, $secondBurstUri)) {
        if ($imageUri -and $imageUri -match '^content://media/external/images/media/[0-9]+$') {
            & $adb -s $Serial shell content delete --uri $imageUri | Out-Null
        }
    }
    & $adb -s $Serial shell rm -f $remoteFixture | Out-Null
    & $adb -s $Serial shell rm -f `
        $historicalPath $firstBurstPath $secondBurstPath | Out-Null
    if ($installed -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $package | Out-Null
    }
}
