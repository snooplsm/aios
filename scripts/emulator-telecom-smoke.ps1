param(
    [string]$Serial = "emulator-5554",
    [string]$Apk = "$PSScriptRoot\..\preview\telecomsmoke\build\outputs\apk\debug\telecomsmoke-debug.apk",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw "Refusing to run Telecom smoke checks on non-emulator serial: $Serial"
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

function Get-SelectedOutgoingAccount {
    $dump = (Invoke-Adb shell dumpsys telecom) -join "`n"
    $match = [regex]::Match(
        $dump,
        '(?m)^\s*defaultOutgoing:\s+(?:(?:ComponentInfo\{(?<component>[^}]+)\},\s*' +
            '(?<id>[^,\r\n]+),\s*UserHandle\{(?<user>\d+)\})|null)\s*$')
    if (-not $match.Success) {
        throw "Could not read Telecom's selected outgoing phone account"
    }
    if (-not $match.Groups['component'].Success) {
        return $null
    }
    return [pscustomobject]@{
        component = $match.Groups['component'].Value.Trim()
        id = $match.Groups['id'].Value.Trim()
        user = [int]$match.Groups['user'].Value
    }
}

function Get-OutgoingAccountKey {
    param([AllowNull()]$Account)
    if ($null -eq $Account) {
        return "<none>"
    }
    return "$($Account.component)`n$($Account.id)`n$($Account.user)"
}

$qemu = (Invoke-Adb shell getprop ro.kernel.qemu | Select-Object -First 1).Trim()
if ($qemu -ne "1") {
    throw "Refusing to run: $Serial does not report ro.kernel.qemu=1"
}
if (-not (Test-Path -LiteralPath $Apk)) {
    throw "Smoke APK not found: $Apk"
}
$apkPath = [IO.Path]::GetFullPath($Apk)
$apkBytes = (Get-Item -LiteralPath $apkPath).Length
$apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkBytes -le 0 -or $apkSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Telecom smoke APK identity is invalid"
}
$androidRelease = (Invoke-Adb shell getprop ro.build.version.release |
    Select-Object -First 1).Trim()
$apiLevel = [int](Invoke-Adb shell getprop ro.build.version.sdk |
    Select-Object -First 1).Trim()
if ($apiLevel -lt 35) {
    throw "Telecom smoke checks require Android API 35 or newer"
}

$role = "android.app.role.DIALER"
$package = "com.aios.phone"
$fixtureActivity = "$package/com.aios.phone.smoke.EmulatorCallActivity"
$fixtureService = "$package/com.aios.phone.smoke.EmulatorConnectionService"
$fixtureAccount = "aios-emulator-smoke"
$existingPackage = @(
    Invoke-Adb shell pm list packages --user 0 $package |
        Where-Object { $_ -eq "package:$package" }
)
if ($existingPackage.Count -ne 0) {
    throw "Refusing to replace an existing $package installation on the emulator"
}
$originalHolders = @(
    Invoke-Adb shell cmd role get-role-holders --user 0 $role |
        Where-Object { $_ }
)
$originalOutgoingAccount = Get-SelectedOutgoingAccount
$callStarted = $false
$installed = $false
$registered = $false
$outgoingAccountChanged = $false
$screenPrepared = $false
$screenWasAwake = ((Invoke-Adb shell dumpsys power) -join "`n") -match 'mWakefulness=Awake'
$evidence = $null
$evidencePath = $null
$smokeToken = [Guid]::NewGuid().ToString("N")
$remoteScreenshot = "/sdcard/aios-telecom-smoke-$smokeToken.png"
$remoteUiDump = "/sdcard/aios-telecom-smoke-$smokeToken.xml"
$screenshot = $null
$outgoingScreenshot = $null

function Get-UiControl {
    param([Parameter(Mandatory)][string]$Text)

    Invoke-Adb shell uiautomator dump $remoteUiDump | Out-Null
    [xml]$hierarchy = (Invoke-Adb shell cat $remoteUiDump) -join "`n"
    $labels = @(
        $hierarchy.SelectNodes('//node') |
            Where-Object { $_.text -eq $Text -or $_.'content-desc' -eq $Text }
    )
    if ($labels.Count -ne 1) {
        throw "Expected exactly one '$Text' control, found $($labels.Count)"
    }
    $control = $labels[0].ParentNode
    if ($null -eq $control -or $control.clickable -ne "true" -or
        $control.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "The '$Text' Compose control is not actionable"
    }
    return [pscustomobject]@{
        enabled = $control.enabled -eq "true"
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

function Get-CurrentTelecomCalls {
    $capturing = $false
    $current = @()
    foreach ($line in @(Invoke-Adb shell dumpsys telecom)) {
        if ($line.Trim() -eq "mCalls:") {
            $capturing = $true
            continue
        }
        if ($capturing -and $line.Trim() -eq "mCallAudioManager:") {
            break
        }
        if ($capturing) {
            $current += $line
        }
    }
    return $current -join "`n"
}

function Get-FocusedWindow {
    return @(
        Invoke-Adb shell dumpsys window |
            Where-Object {
                $_ -match '^\s*mCurrentFocus=' -or $_ -match '^\s*mFocusedApp='
            }
    ) -join "`n"
}

try {
    Invoke-Adb install -r $apkPath | Out-Null
    $installed = $true
    Invoke-Adb shell cmd role add-role-holder --user 0 $role $package | Out-Null
    Invoke-Adb shell am start -a com.aios.phone.smoke.REGISTER -n $fixtureActivity | Out-Null
    $registered = $true
    Start-Sleep -Milliseconds 500
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Invoke-Adb shell cmd telecom set-phone-account-enabled $fixtureService $fixtureAccount 0 | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    # Full-screen call intents are intentionally suppressed while an unlocked
    # app is foreground. Put the emulator to sleep so this also verifies the
    # production turnScreenOn/showWhenLocked path.
    Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Milliseconds 500
    if (((Invoke-Adb shell dumpsys power) -join "`n") -match 'mWakefulness=Awake') {
        Invoke-Adb shell input keyevent KEYCODE_POWER | Out-Null
    }
    $screenPrepared = $true
    Start-Sleep -Milliseconds 750
    Invoke-Adb shell am start -a com.aios.phone.smoke.INCOMING -n $fixtureActivity `
        --es number 15551230182 | Out-Null
    $callStarted = $true
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Seconds 3

    $focus = Get-FocusedWindow
    $telecom = Get-CurrentTelecomCalls
    $services = (Invoke-Adb shell dumpsys activity services $package) -join "`n"
    $notifications = (Invoke-Adb shell dumpsys notification --noredact) -join "`n"
    if ($telecom -notmatch 'state=RINGING' -or $telecom -notmatch 'EmulatorConnectionService') {
        throw "Telecom did not retain the managed synthetic incoming call"
    }
    if ($services -notmatch 'AiosInCallService') {
        throw "Telecom did not bind the production AIOS InCallService"
    }
    if ($notifications -notmatch 'pkg=com\.aios\.phone' -or
        $notifications -notmatch 'channel=incoming_calls_v1' -or
        $notifications -notmatch 'fullscreenIntent=PendingIntent') {
        throw "AIOS did not post its incoming CallStyle notification"
    }

    $fullScreenIntentVisible = $focus -match 'com\.aios\.phone/.+InCallActivity'
    if (-not $fullScreenIntentVisible) {
        # Android throttles repeated full-screen notification launches. Once
        # the real callback, ringing call, and full-screen CallStyle intent are
        # proven above, ask the emulator-only fixture to display the internal
        # production activity so the screenshot remains deterministic.
        Invoke-Adb shell am start -a com.aios.phone.smoke.SHOW -n $fixtureActivity | Out-Null
        Start-Sleep -Seconds 1
        $focus = Get-FocusedWindow
    }
    if ($focus -notmatch 'com\.aios\.phone/.+InCallActivity') {
        throw "AIOS InCallActivity could not be displayed for visual verification"
    }

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $screenshot = Join-Path $EvidenceDirectory "aios-telecom-smoke.png"
    Invoke-Adb shell screencap -p $remoteScreenshot | Out-Null
    Invoke-Adb pull $remoteScreenshot $screenshot | Out-Null

    $aiControl = Get-UiControl "AI"
    if ($aiControl.enabled) {
        throw "AI answering must stay disabled without physical caller-audio evidence"
    }
    $phonePidBeforeActions = (Invoke-Adb shell pidof $package | Select-Object -First 1).Trim()
    if ($phonePidBeforeActions -notmatch '^[0-9]+$') {
        throw "AIOS Phone has no stable process before UI action checks"
    }
    Invoke-UiControl "Ignore"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 500
    $telecomAfterIgnore = Get-CurrentTelecomCalls
    $notificationsAfterIgnore = (Invoke-Adb shell dumpsys notification --noredact) -join "`n"
    if ($telecomAfterIgnore -notmatch 'state=RINGING' -or
        $notificationsAfterIgnore -notmatch 'channel=incoming_calls_silent_v1') {
        throw "Ignore did not preserve the ringing call on the silent notification channel"
    }

    Invoke-Adb shell am start -a com.aios.phone.smoke.SHOW -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-UiControl "Answer"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 500
    $telecomAfterAnswer = Get-CurrentTelecomCalls
    $servicesAfterAnswer = (Invoke-Adb shell dumpsys activity services $package) -join "`n"
    $phonePidAfterAnswer = (Invoke-Adb shell pidof $package | Select-Object -First 1).Trim()
    if ($telecomAfterAnswer -notmatch 'state=ACTIVE' -or
        $phonePidAfterAnswer -ne $phonePidBeforeActions -or
        $servicesAfterAnswer -notmatch 'AiosInCallService' -or
        $servicesAfterAnswer -notmatch 'isForeground=true' -or
        $servicesAfterAnswer -notmatch 'types=0x00000004' -or
        $servicesAfterAnswer -notmatch 'channel=ongoing_calls_v1') {
        throw "Answer did not retain an active call under the phoneCall foreground service"
    }

    Invoke-Adb shell am start -a com.aios.phone.smoke.DISCONNECT `
        -n $fixtureActivity | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    $callStarted = $false
    Start-Sleep -Milliseconds 500
    Invoke-Adb shell am start -a com.aios.phone.smoke.INCOMING -n $fixtureActivity `
        --es number 15551230183 | Out-Null
    $callStarted = $true
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 750
    Invoke-Adb shell am start -a com.aios.phone.smoke.SHOW -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-UiControl "Decline"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 500
    $telecomAfterDecline = Get-CurrentTelecomCalls
    if ($telecomAfterDecline -match 'state=(RINGING|ACTIVE)') {
        throw "Decline did not disconnect the managed Telecom call"
    }
    $callStarted = $false

    Invoke-Adb shell cmd telecom set-user-selected-outgoing-phone-account `
        $fixtureService $fixtureAccount 0 | Out-Null
    $outgoingAccountChanged = $true
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    $outgoingNumber = "15551230184"
    $mainActivity = "$package/com.aios.phone.ui.MainActivity"
    Invoke-Adb shell am start -W -a android.intent.action.DIAL `
        -d "tel:$outgoingNumber" -n $mainActivity | Out-Null
    Start-Sleep -Seconds 1
    Invoke-Adb shell uiautomator dump $remoteUiDump | Out-Null
    $dialUi = (Invoke-Adb shell cat $remoteUiDump) -join "`n"
    if ($dialUi -notmatch [regex]::Escape($outgoingNumber)) {
        throw "The standard DIAL intent did not populate the production Compose dialer"
    }
    $phonePidBeforeOutgoing = (Invoke-Adb shell pidof $package |
        Select-Object -First 1).Trim()
    if ($phonePidBeforeOutgoing -notmatch '^[0-9]+$') {
        throw "AIOS Phone has no stable process before the outgoing call check"
    }
    Invoke-UiControl "Call"
    $callStarted = $true
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Seconds 1
    $telecomAfterDial = Get-CurrentTelecomCalls
    $focusAfterDial = Get-FocusedWindow
    if ($telecomAfterDial -notmatch 'state=DIALING' -or
        $telecomAfterDial -notmatch 'EmulatorConnectionService' -or
        $focusAfterDial -notmatch 'com\.aios\.phone/.+InCallActivity') {
        throw "Compose did not place and present the managed outgoing Telecom call"
    }

    Invoke-Adb shell am start -a com.aios.phone.smoke.ACTIVATE `
        -n $fixtureActivity | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 500
    $telecomAfterOutgoingActive = Get-CurrentTelecomCalls
    $servicesAfterOutgoingActive = (
        Invoke-Adb shell dumpsys activity services $package) -join "`n"
    $phonePidAfterOutgoingActive = (Invoke-Adb shell pidof $package |
        Select-Object -First 1).Trim()
    if ($telecomAfterOutgoingActive -notmatch 'state=ACTIVE' -or
        $phonePidAfterOutgoingActive -ne $phonePidBeforeOutgoing -or
        $servicesAfterOutgoingActive -notmatch 'isForeground=true' -or
        $servicesAfterOutgoingActive -notmatch 'types=0x00000004' -or
        $servicesAfterOutgoingActive -notmatch 'channel=ongoing_calls_v1') {
        throw "The outgoing call did not become active under the phoneCall foreground service"
    }
    $outgoingScreenshot = Join-Path $EvidenceDirectory "aios-telecom-outgoing-smoke.png"
    Invoke-Adb shell screencap -p $remoteScreenshot | Out-Null
    Invoke-Adb pull $remoteScreenshot $outgoingScreenshot | Out-Null
    Invoke-UiControl "End call"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 500
    if ((Get-CurrentTelecomCalls) -match 'state=(DIALING|ACTIVE)') {
        throw "The production End call control did not disconnect the outgoing call"
    }
    $callStarted = $false

    $evidence = [ordered]@{
        schema_version = 1
        serial = $Serial
        qemu = $true
        android_release = $androidRelease
        api_level = $apiLevel
        apk_bytes = $apkBytes
        apk_sha256 = $apkSha256
        role_holder = $package
        simulated_number = "15551230182"
        simulated_transport = "managed_connection_service"
        in_call_activity_visible = $true
        full_screen_intent_launched_automatically = $fullScreenIntentVisible
        in_call_service_bound = $true
        incoming_notification_posted = $true
        ignore_preserved_ringing_call = $true
        ignore_selected_silent_channel = $true
        answer_activated_call = $true
        phone_process_survived_answer = $true
        phone_call_foreground_service = $true
        ongoing_notification_posted = $true
        decline_disconnected_call = $true
        ai_action_fail_closed = $true
        outgoing_dial_intent_populated = $true
        outgoing_compose_call_action = $true
        outgoing_connection_dialing = $true
        outgoing_in_call_activity_visible = $true
        outgoing_connection_active = $true
        phone_process_survived_outgoing = $true
        outgoing_end_call_disconnected = $true
        screenshot = [IO.Path]::GetFullPath($screenshot)
        outgoing_screenshot = [IO.Path]::GetFullPath($outgoingScreenshot)
        physical_gate_evidence = $false
    }
    $evidencePath = Join-Path $EvidenceDirectory "aios-telecom-smoke.json"
} finally {
    if ($callStarted) {
        & $adb -s $Serial shell am start -a com.aios.phone.smoke.DISCONNECT `
            -n $fixtureActivity | Out-Null
        & $adb -s $Serial shell cmd telecom wait-on-handlers | Out-Null
    }
    if ($outgoingAccountChanged) {
        if ($null -eq $originalOutgoingAccount) {
            & $adb -s $Serial shell cmd telecom `
                set-user-selected-outgoing-phone-account | Out-Null
        } else {
            & $adb -s $Serial shell cmd telecom `
                set-user-selected-outgoing-phone-account `
                $originalOutgoingAccount.component $originalOutgoingAccount.id `
                $originalOutgoingAccount.user | Out-Null
        }
        & $adb -s $Serial shell cmd telecom wait-on-handlers | Out-Null
    }
    if ($registered) {
        & $adb -s $Serial shell am start -a com.aios.phone.smoke.UNREGISTER `
            -n $fixtureActivity | Out-Null
        & $adb -s $Serial shell cmd telecom wait-on-handlers | Out-Null
    }
    if ($installed) {
        & $adb -s $Serial shell cmd role remove-role-holder --user 0 $role $package | Out-Null
        foreach ($holder in $originalHolders) {
            & $adb -s $Serial shell cmd role add-role-holder --user 0 $role $holder | Out-Null
        }
        if (-not $KeepInstalled) {
            & $adb -s $Serial uninstall $package | Out-Null
        }
    }
    if ($screenPrepared) {
        $screenIsAwake = ((& $adb -s $Serial shell dumpsys power) -join "`n") -match `
            'mWakefulness=Awake'
        if ($screenIsAwake -ne $screenWasAwake) {
            & $adb -s $Serial shell input keyevent KEYCODE_POWER | Out-Null
        }
    }
    & $adb -s $Serial shell rm -f $remoteScreenshot $remoteUiDump | Out-Null
}

$restoredHolders = @(
    Invoke-Adb shell cmd role get-role-holders --user 0 $role |
        Where-Object { $_ } | Sort-Object
)
$expectedHolders = @($originalHolders | Sort-Object)
if (($restoredHolders -join "`n") -ne ($expectedHolders -join "`n")) {
    throw "Telecom smoke did not restore the original dialer role holders"
}
$restoredOutgoingAccount = Get-SelectedOutgoingAccount
if ((Get-OutgoingAccountKey $restoredOutgoingAccount) -ne
    (Get-OutgoingAccountKey $originalOutgoingAccount)) {
    throw "Telecom smoke did not restore the selected outgoing phone account"
}
$remainingPackage = @(
    Invoke-Adb shell pm list packages --user 0 $package |
        Where-Object { $_ -eq "package:$package" }
)
if (-not $KeepInstalled -and $remainingPackage.Count -ne 0) {
    throw "Telecom smoke package survived cleanup"
}
$telecomAfter = (Invoke-Adb shell dumpsys telecom) -join "`n"
if ($telecomAfter -match [regex]::Escape($fixtureAccount)) {
    throw "Telecom smoke phone account survived cleanup"
}
$screenAfter = ((Invoke-Adb shell dumpsys power) -join "`n") -match 'mWakefulness=Awake'
if ($screenAfter -ne $screenWasAwake) {
    throw "Telecom smoke did not restore the emulator screen state"
}
$remoteScreenshotAfter = @(
    Invoke-Adb shell find /sdcard -maxdepth 1 -type f -name (
        "aios-telecom-smoke-$smokeToken.png") |
        Where-Object { $_ }
)
if ($remoteScreenshotAfter.Count -ne 0) {
    throw "Telecom smoke remote screenshot survived cleanup"
}
$remoteUiDumpAfter = @(
    Invoke-Adb shell find /sdcard -maxdepth 1 -type f -name (
        "aios-telecom-smoke-$smokeToken.xml") |
        Where-Object { $_ }
)
if ($remoteUiDumpAfter.Count -ne 0) {
    throw "Telecom smoke remote UI dump survived cleanup"
}

$evidence["cleanup_verified"] = $true
$evidence["original_role_holders_restored"] = $true
$evidence["original_outgoing_account_restored"] = $true
$evidence["fixture_phone_account_removed"] = $true
$evidence["package_removed"] = -not $KeepInstalled
$evidence["remote_screenshot_removed"] = $true
$evidence["remote_ui_dump_removed"] = $true
$evidence["captured_at"] = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$utf8WithoutBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText(
    [IO.Path]::GetFullPath($evidencePath),
    ($evidence | ConvertTo-Json -Depth 4),
    $utf8WithoutBom)
Write-Output "AIOS emulator Telecom smoke check passed: $screenshot"
