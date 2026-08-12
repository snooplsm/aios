param(
    [string]$Serial = "emulator-5554",
    [ValidateRange(1024, 65535)]
    [int]$AdbServerPort = 5037,
    [string]$Apk = "$PSScriptRoot\..\preview\messagingcheck\build\outputs\apk\debug\messagingcheck-debug.apk",
    [string]$EmulatorControl = "$PSScriptRoot\..\preview\emulatorcontrol\build\install\emulatorcontrol\bin\emulatorcontrol.bat",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"

if ($Serial -notmatch '^emulator-(?<port>[0-9]+)$') {
    throw "Refusing to run Messaging smoke checks on non-emulator serial: $Serial"
}
$consolePort = [int]$Matches['port']

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$sourceRevision = ((& git -C $repositoryRoot rev-parse HEAD) -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $sourceRevision -notmatch '^[0-9a-f]{40}$') {
    throw "Unable to bind Messaging evidence to an exact AIOS revision"
}
& git -C $repositoryRoot diff --quiet --
$unstagedChanges = $LASTEXITCODE
& git -C $repositoryRoot diff --cached --quiet --
$stagedChanges = $LASTEXITCODE
if ($unstagedChanges -ne 0 -or $stagedChanges -ne 0) {
    throw "Refusing to capture Messaging evidence with tracked source changes"
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
if (-not $env:JAVA_HOME) {
    $bundledJbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path -LiteralPath $bundledJbr) {
        $env:JAVA_HOME = $bundledJbr
    }
}
if (-not $env:JAVA_HOME -or
    -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "JAVA_HOME must point to a Java 17+ runtime"
}

function Invoke-Adb {
    $output = & $adb -P $AdbServerPort -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($args -join ' ')"
    }
    return $output
}

function Get-RoleHolders {
    return @(
        Invoke-Adb shell cmd role get-role-holders --user 0 android.app.role.SMS |
            Where-Object { $_ -and $_.Trim() }
    )
}

function Test-SameValues {
    param([string[]]$Expected, [string[]]$Actual)

    return @(Compare-Object ($Expected | Sort-Object) ($Actual | Sort-Object)).Count -eq 0
}

function Find-DiscoveryFile {
    $running = Join-Path $env:LOCALAPPDATA "Temp\avd\running"
    $discoveryFiles = @()
    if (Test-Path -LiteralPath $running) {
        foreach ($candidate in @(Get-ChildItem -LiteralPath $running -Filter 'pid_*.ini' -File)) {
            if ($candidate.BaseName -notmatch '^pid_(?<pid>[0-9]+)(?:_info)?$') {
                continue
            }
            $emulatorPid = [int]$Matches['pid']
            $emulatorProcess = Get-Process -Id $emulatorPid `
                -ErrorAction SilentlyContinue
            if ($null -eq $emulatorProcess -or
                    $emulatorProcess.ProcessName -notin @('emulator', 'qemu-system-x86_64-headless')) {
                continue
            }
            $lines = @(Get-Content -LiteralPath $candidate.FullName)
            if ($lines -contains "port.serial=$consolePort" -and
                $lines -match '^grpc\.port=[0-9]+$' -and
                $lines -match '^grpc\.token=.+$') {
                $discoveryFiles += $candidate.FullName
            }
        }
    }
    if ($discoveryFiles.Count -ne 1) {
        throw "Expected one authenticated emulator discovery file for $Serial, found $($discoveryFiles.Count)"
    }
    return $discoveryFiles[0]
}

function Get-UiHierarchy {
    Invoke-Adb shell uiautomator dump $remoteUiDump | Out-Null
    return [xml]((Invoke-Adb shell cat $remoteUiDump) -join "`n")
}

function Wait-UiText {
    param([Parameter(Mandatory)][string]$Text)

    for ($attempt = 0; $attempt -lt 24; $attempt++) {
        $hierarchy = Get-UiHierarchy
        $nodes = @(
            $hierarchy.SelectNodes('//node') |
                Where-Object { $_.text -eq $Text -or $_.'content-desc' -eq $Text }
        )
        if ($nodes.Count -gt 0) {
            return $hierarchy
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for '$Text' in the production Compose hierarchy"
}

function Get-UiControl {
    param([Parameter(Mandatory)][string]$Text)

    $hierarchy = Wait-UiText $Text
    $labels = @(
        $hierarchy.SelectNodes('//node') |
            Where-Object { $_.text -eq $Text -or $_.'content-desc' -eq $Text }
    )
    $controls = @()
    foreach ($label in $labels) {
        $candidate = $label
        while ($null -ne $candidate -and $candidate.Name -eq 'node' -and
            $candidate.clickable -ne 'true') {
            $candidate = $candidate.ParentNode
        }
        if ($null -ne $candidate -and $candidate.Name -eq 'node' -and
            $candidate.clickable -eq 'true') {
            $controls += $candidate
        }
    }
    $controls = @($controls | Sort-Object bounds -Unique)
    if ($controls.Count -ne 1 -or
        $controls[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Expected one actionable '$Text' Compose control, found $($controls.Count)"
    }
    return [pscustomobject]@{
        enabled = $controls[0].enabled -eq 'true'
        center_x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        center_y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
}

function Invoke-UiControl {
    param([Parameter(Mandatory)][string]$Text)

    $control = Get-UiControl $Text
    if (-not $control.enabled) {
        throw "The '$Text' Compose control is disabled"
    }
    Invoke-Adb shell input tap $control.center_x $control.center_y | Out-Null
}

function Get-ProviderDump {
    return (Invoke-Adb shell content query --uri content://sms `
        --projection _id:thread_id:address:body:type:read:sub_id) -join "`n"
}

function Wait-ProviderToken {
    param([Parameter(Mandatory)][string]$Token)

    for ($attempt = 0; $attempt -lt 24; $attempt++) {
        $dump = Get-ProviderDump
        if ([regex]::Matches($dump, [regex]::Escape($Token)).Count -eq 1) {
            return
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for one synthetic SMS provider row"
}

function Invoke-Fixture {
    param([Parameter(Mandatory)][string]$Action)

    Invoke-Adb shell am start -W -a $Action -n $fixtureActivity `
        --es incoming $incomingToken --es outgoing $outgoingToken `
        --es address $address | Out-Null
    $raw = (Invoke-Adb shell run-as $package cat "cache/$privateAuditFile") -join "`n"
    return $raw | ConvertFrom-Json
}

$qemu = (Invoke-Adb shell getprop ro.kernel.qemu | Select-Object -First 1).Trim()
if ($qemu -ne "1") {
    throw "Refusing to run: $Serial does not report ro.kernel.qemu=1"
}
if (-not (Test-Path -LiteralPath $Apk)) {
    throw "Messaging smoke APK not found: $Apk"
}
if (-not (Test-Path -LiteralPath $EmulatorControl)) {
    throw "Emulator control helper not found: $EmulatorControl"
}
$apkPath = [IO.Path]::GetFullPath($Apk)
$helperPath = [IO.Path]::GetFullPath($EmulatorControl)
$discoveryFile = Find-DiscoveryFile
$apkBytes = (Get-Item -LiteralPath $apkPath).Length
$apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkBytes -le 0 -or $apkSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Messaging smoke APK identity is invalid"
}
$androidRelease = (Invoke-Adb shell getprop ro.build.version.release |
    Select-Object -First 1).Trim()
$apiLevel = [int](Invoke-Adb shell getprop ro.build.version.sdk |
    Select-Object -First 1).Trim()
if ($apiLevel -lt 35) {
    throw "Messaging smoke checks require Android API 35 or newer"
}

$package = "com.aios.messaging.compilecheck"
$role = "android.app.role.SMS"
$mainActivity = "$package/com.aios.messaging.ui.MainActivity"
$fixtureActivity = "$package/com.aios.messaging.smoke.EmulatorMessagingFixtureActivity"
$privateAuditFile = "aios-messaging-smoke-audit.json"
$address = "+15551234567"
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 12).ToUpperInvariant()
$incomingToken = "AIOSIN$suffix"
$outgoingToken = "AIOSOUT$suffix"
$remoteUiDump = "/sdcard/aios-messaging-smoke-$suffix.xml"
$remoteScreenshot = "/sdcard/aios-messaging-smoke-$suffix.png"
$existingPackage = @(
    Invoke-Adb shell pm list packages --user 0 $package |
        Where-Object { $_ -eq "package:$package" }
)
if ($existingPackage.Count -ne 0) {
    throw "Refusing to replace an existing $package installation on the emulator"
}
$originalHolders = Get-RoleHolders
$screenWasAwake = ((Invoke-Adb shell dumpsys power) -join "`n") -match 'mWakefulness=Awake'
$installed = $false
$roleAssigned = $false
$runSucceeded = $false
$cleanupAudit = $null
$privateAuditRemoved = $false
$packageRemoved = $false
$roleRestored = $false
$providerRowsRemoved = $false
$failure = $null
$cleanupFailure = $null
$screenshot = $null

try {
    Invoke-Adb install -r $apkPath | Out-Null
    $installed = $true
    Invoke-Adb shell cmd role add-role-holder --user 0 $role $package | Out-Null
    $roleAssigned = $true
    if (-not (Test-SameValues @($package) (Get-RoleHolders))) {
        throw "AIOS Messaging did not become the sole SMS role holder"
    }
    Invoke-Adb shell pm grant $package android.permission.READ_PHONE_STATE | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    Invoke-Adb shell wm dismiss-keyguard | Out-Null
    Invoke-Adb shell am force-stop $package | Out-Null

    $injection = & $helperPath send-sms $discoveryFile $address $incomingToken
    if ($LASTEXITCODE -ne 0 -or ($injection -join "`n").Trim() -ne "SMS_DELIVERED") {
        throw "Authenticated emulator SMS injection failed"
    }
    Wait-ProviderToken $incomingToken

    Invoke-Adb shell am start -W -n $mainActivity | Out-Null
    Wait-UiText $incomingToken | Out-Null
    Invoke-UiControl $incomingToken
    Wait-UiText $incomingToken | Out-Null

    Invoke-Adb shell am start -W -a android.intent.action.SENDTO `
        -d "smsto:$address" --es sms_body $outgoingToken -n $mainActivity | Out-Null
    Wait-UiText $outgoingToken | Out-Null
    $send = Get-UiControl "Send SMS"
    if (-not $send.enabled) {
        throw "Send SMS stayed disabled despite one active emulator subscription"
    }
    Invoke-UiControl "Send SMS"
    Wait-ProviderToken $outgoingToken
    Wait-UiText $outgoingToken | Out-Null

    $assertAudit = Invoke-Fixture "com.aios.messaging.smoke.ASSERT"
    if ($assertAudit.passed -ne $true -or $assertAudit.incoming_rows -ne 1 -or
        $assertAudit.outgoing_rows -ne 2 -or $assertAudit.outgoing_sent_rows -ne 1 -or
        $assertAudit.outgoing_inbox_rows -ne 1 -or
        $assertAudit.emulator_loopback -ne $true -or
        $assertAudit.same_thread -ne $true -or $assertAudit.valid_subscriptions -ne $true) {
        throw "Private Messaging provider audit did not pass: $(
            $assertAudit | ConvertTo-Json -Compress)"
    }

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $screenshot = Join-Path $EvidenceDirectory "aios-messaging-smoke.png"
    Invoke-Adb shell cmd statusbar collapse | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-Adb shell screencap -p $remoteScreenshot | Out-Null
    Invoke-Adb pull $remoteScreenshot $screenshot | Out-Null
    $runSucceeded = $true
} catch {
    $failure = $_
} finally {
    try {
        if ($installed -and $roleAssigned) {
            $cleanupAudit = Invoke-Fixture "com.aios.messaging.smoke.CLEAN"
            $providerRowsRemoved = $cleanupAudit.passed -eq $true -and
                $cleanupAudit.remaining_rows -eq 0
            Invoke-Adb shell run-as $package rm -f "cache/$privateAuditFile" | Out-Null
            $privateAuditRemoved = $true
        }
        Invoke-Adb shell rm -f $remoteUiDump $remoteScreenshot | Out-Null
        if ($roleAssigned) {
            Invoke-Adb shell cmd role remove-role-holder --user 0 $role $package | Out-Null
            foreach ($holder in $originalHolders) {
                Invoke-Adb shell cmd role add-role-holder --user 0 $role $holder | Out-Null
            }
        }
        $roleRestored = Test-SameValues $originalHolders (Get-RoleHolders)
        if (-not $roleRestored) {
            throw "Original SMS role holders were not restored"
        }
        if ($installed) {
            Invoke-Adb shell am force-stop $package | Out-Null
            if (-not $KeepInstalled) {
                Invoke-Adb uninstall $package | Out-Null
                $packageRemoved = @(
                    Invoke-Adb shell pm list packages --user 0 $package |
                        Where-Object { $_ -eq "package:$package" }
                ).Count -eq 0
                if (-not $packageRemoved) {
                    throw "Temporary Messaging smoke APK remains installed"
                }
            }
        }
        Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
        if (-not $screenWasAwake -and
            ((Invoke-Adb shell dumpsys power) -join "`n") -match 'mWakefulness=Awake') {
            Invoke-Adb shell input keyevent KEYCODE_POWER | Out-Null
        }
    } catch {
        $cleanupFailure = $_
    }
}

if ($null -ne $failure) {
    if ($null -ne $cleanupFailure) {
        throw "$($failure.Exception.Message); cleanup also failed: $($cleanupFailure.Exception.Message)"
    }
    throw $failure
}
if ($null -ne $cleanupFailure) {
    throw $cleanupFailure
}
if (-not $runSucceeded -or -not $providerRowsRemoved -or
    -not $privateAuditRemoved -or -not $roleRestored -or
    (-not $KeepInstalled -and -not $packageRemoved)) {
    throw "Messaging smoke cleanup invariants did not pass"
}

$evidence = [ordered]@{
    schema_version = 1
    gate = "integration.emulator_messaging"
    aios_revision = $sourceRevision
    tracked_source_clean = $true
    serial = $Serial
    qemu = $true
    android_release = $androidRelease
    api_level = $apiLevel
    apk_path = $apkPath
    apk_bytes = $apkBytes
    apk_sha256 = $apkSha256
    sms_role_assigned = $true
    emulator_grpc_sms_injected = $true
    production_sms_deliver_provider_path = $true
    incoming_provider_row_verified = $true
    incoming_compose_rendered = $true
    sendto_composer_rendered = $true
    outgoing_sms_submission_accepted = $true
    outgoing_provider_row_verified = $true
    emulator_loopback_inbox_verified = $true
    outgoing_compose_rendered = $true
    same_provider_thread_verified = $true
    valid_subscription_ids_verified = $true
    synthetic_rows_removed = $providerRowsRemoved
    private_audit_removed = $privateAuditRemoved
    sms_role_restored = $roleRestored
    package_removed = if ($KeepInstalled) { $false } else { $packageRemoved }
    package_retained_for_debugging = [bool]$KeepInstalled
    carrier_delivery_evidence = $false
    multi_sim_selection_evidence = $false
    mms_transport_evidence = $false
    physical_gate_evidence = $false
}
$evidencePath = Join-Path $EvidenceDirectory "aios-messaging-smoke.json"
$evidence | ConvertTo-Json | Set-Content -LiteralPath $evidencePath -Encoding utf8
Write-Host "Messaging emulator smoke checks passed"
Write-Host "Screenshot: $screenshot"
Write-Host "Evidence: $evidencePath"
