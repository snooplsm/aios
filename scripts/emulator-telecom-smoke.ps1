param(
    [string]$Serial = "emulator-5554",
    [string]$Apk = "$PSScriptRoot\..\preview\telecomsmoke\build\outputs\apk\debug\telecomsmoke-debug.apk",
    [string]$AssistantApk = "$PSScriptRoot\..\preview\callassistantsmoke\build\outputs\apk\debug\callassistantsmoke-debug.apk",
    [string]$EvidenceDirectory = "$PSScriptRoot\..\preview\screenshots",
    [switch]$AutomaticAnswerOnly,
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
            '(?<id>[^,\r\n]+),\s*UserHandle\{(?<user>\d+)\})|null|none)\s*$')
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
if (-not (Test-Path -LiteralPath $AssistantApk)) {
    throw "Call-assistant smoke APK not found: $AssistantApk"
}
$apkPath = [IO.Path]::GetFullPath($Apk)
$apkBytes = (Get-Item -LiteralPath $apkPath).Length
$apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkBytes -le 0 -or $apkSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Telecom smoke APK identity is invalid"
}
$assistantApkPath = [IO.Path]::GetFullPath($AssistantApk)
$assistantApkBytes = (Get-Item -LiteralPath $assistantApkPath).Length
$assistantApkSha256 = (
    Get-FileHash -LiteralPath $assistantApkPath -Algorithm SHA256
).Hash.ToLowerInvariant()
if ($assistantApkBytes -le 0 -or $assistantApkSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Call-assistant smoke APK identity is invalid"
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
$mainActivity = "$package/com.aios.phone.ui.MainActivity"
$fixtureAccount = "aios-emulator-smoke"
$fixtureSecondaryAccount = "aios-emulator-smoke-secondary"
$assistantPackage = "com.aios.callintelligence"
$assistantActivity = "$assistantPackage/.EmulatorCallAssistantControlActivity"
$assistantAuditFile = "cache/aios-call-assistant-smoke-audit.txt"
$existingPackage = @(
    Invoke-Adb shell pm list packages --user 0 $package |
        Where-Object { $_ -eq "package:$package" }
)
if ($existingPackage.Count -ne 0) {
    throw "Refusing to replace an existing $package installation on the emulator"
}
$existingAssistantPackage = @(
    Invoke-Adb shell pm list packages --user 0 $assistantPackage |
        Where-Object { $_ -eq "package:$assistantPackage" }
)
if ($existingAssistantPackage.Count -ne 0) {
    throw "Refusing to replace an existing $assistantPackage installation on the emulator"
}
$originalHolders = @(
    Invoke-Adb shell cmd role get-role-holders --user 0 $role |
        Where-Object { $_ }
)
$originalOutgoingAccount = Get-SelectedOutgoingAccount
$callStarted = $false
$installed = $false
$assistantInstalled = $false
$registered = $false
$outgoingAccountChanged = $false
$screenPrepared = $false
$screenWasAwake = ((Invoke-Adb shell dumpsys power) -join "`n") -match 'mWakefulness=Awake'
$evidence = $null
$evidencePath = $null
$smokeToken = [Guid]::NewGuid().ToString("N")
$remoteScreenshot = "/sdcard/aios-telecom-smoke-$smokeToken.png"
$remoteUiDump = "/sdcard/aios-telecom-smoke-$smokeToken.xml"
$privateAuditFile = "cache/aios-telecom-smoke-audit.txt"
$screenshot = $null
$outgoingScreenshot = $null
$privateAuditRemoved = $false

function Get-UiHierarchy {
    $lastFailure = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            # Android's uiautomator occasionally wedges on memory-constrained
            # AVDs. Bound the device-side process so one dump cannot strand an
            # active Telecom call or prevent the finally block from restoring
            # the emulator.
            Invoke-Adb shell timeout 10 uiautomator dump $remoteUiDump | Out-Null
            [xml]$hierarchy = (Invoke-Adb shell cat $remoteUiDump) -join "`n"
            return $hierarchy
        } catch {
            $lastFailure = $_
            if ($attempt -lt 3) {
                Start-Sleep -Milliseconds 500
            }
        }
    }
    throw "Could not obtain the emulator UI hierarchy after 3 bounded attempts: $lastFailure"
}

function Get-UiControl {
    param([Parameter(Mandatory)][string]$Text)

    $labels = @()
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        $hierarchy = Get-UiHierarchy
        $labels = @(
            $hierarchy.SelectNodes('//node') |
                Where-Object { $_.text -eq $Text -or $_.'content-desc' -eq $Text }
        )
        if ($labels.Count -eq 1) {
            break
        }
        Start-Sleep -Milliseconds 200
    }
    if ($labels.Count -ne 1) {
        throw "Expected exactly one '$Text' control after retries, found $($labels.Count)"
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

function Get-UiSwitch {
    param([Parameter(Mandatory)][string]$Title)

    $hierarchy = Get-UiHierarchy
    $labels = @($hierarchy.SelectNodes('//node') | Where-Object { $_.text -eq $Title })
    if ($labels.Count -ne 1) {
        throw "Expected one visible '$Title' setting label, found $($labels.Count)"
    }
    # Compose exposes Switch as the next flattened semantics sibling: a
    # checkable/clickable android.view.View rather than android.widget.Switch.
    $switch = $labels[0].NextSibling
    while ($null -ne $switch -and
        ($switch.checkable -ne 'true' -or $switch.clickable -ne 'true')) {
        $switch = $switch.NextSibling
    }
    if ($null -eq $switch -or
        $switch.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "The '$Title' setting does not expose one actionable switch"
    }
    return [pscustomobject]@{
        enabled = $switch.enabled -eq "true"
        checked = $switch.checked -eq "true"
        center_x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        center_y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
}

function Scroll-UntilUiText {
    param(
        [Parameter(Mandatory)][string]$Text,
        [int]$MaximumSwipes = 8
    )

    $sizeLine = (Invoke-Adb shell wm size | Select-Object -First 1) -join ""
    if ($sizeLine -notmatch '(?<width>\d+)x(?<height>\d+)') {
        throw "Could not read emulator display size"
    }
    $displayWidth = [int]$Matches['width']
    $displayHeight = [int]$Matches['height']
    # Keep the gesture inside the Compose scroll container while avoiding the
    # full-width setting controls that occupy the middle of the page.
    $swipeX = [Math]::Max(8, [int]($displayWidth * 0.07))
    $bottomY = [int]($displayHeight * 0.88)
    $topY = [int]($displayHeight * 0.18)
    for ($attempt = 0; $attempt -le $MaximumSwipes; $attempt++) {
        $hierarchy = Get-UiHierarchy
        $focus = Get-FocusedWindow
        if ($focus -notmatch 'com\.aios\.phone/.+SettingsActivity') {
            throw "Left Phone Settings while looking for '$Text': $focus"
        }
        $visibleMatches = @()
        foreach ($node in @($hierarchy.SelectNodes('//node'))) {
            if ($node.text -ne $Text -and $node.'content-desc' -ne $Text) {
                continue
            }
            $bounds = [regex]::Match(
                $node.bounds,
                '^\[(?<left>\d+),(?<top>\d+)\]\[(?<right>\d+),(?<bottom>\d+)\]$')
            if ($bounds.Success -and
                [int]$bounds.Groups['bottom'].Value -gt 0 -and
                [int]$bounds.Groups['top'].Value -lt $displayHeight) {
                $visibleMatches += $node
            }
        }
        if ($visibleMatches.Count -gt 0) {
            return
        }
        if ($attempt -lt $MaximumSwipes) {
            Invoke-Adb shell input swipe $swipeX $bottomY $swipeX $topY 300 | Out-Null
            Start-Sleep -Milliseconds 300
        }
    }
    $visibleText = @(
        $hierarchy.SelectNodes('//node') |
            Where-Object { $_.text } |
            Select-Object -ExpandProperty text -First 16
    ) -join " | "
    throw "Could not reveal '$Text' after $MaximumSwipes settings-page swipes; visible=$visibleText"
}

function Invoke-UiSwitch {
    param([Parameter(Mandatory)][string]$Title)

    Scroll-UntilUiText $Title
    $control = Get-UiSwitch $Title
    if (-not $control.enabled) {
        throw "The '$Title' setting switch is disabled"
    }
    Invoke-Adb shell input tap $control.center_x $control.center_y | Out-Null
    Start-Sleep -Milliseconds 250
}

function Invoke-ScrolledUiControl {
    param([Parameter(Mandatory)][string]$Text)

    Scroll-UntilUiText $Text
    Invoke-UiControl $Text
    Start-Sleep -Milliseconds 250
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

function Set-AssistantPolicy {
    param(
        [Parameter(Mandatory)][string]$AnswerMode,
        [Parameter(Mandatory)][string]$DelayMode,
        [bool]$Available = $true,
        [bool]$ProcessingEnabled = $true
    )

    Invoke-Adb shell am start -W `
        -a com.aios.callintelligence.smoke.CONFIGURE `
        -n $assistantActivity `
        --ez available $Available.ToString().ToLowerInvariant() `
        --es answer_mode $AnswerMode `
        --es answer_delay_mode $DelayMode `
        --ez processing_enabled $ProcessingEnabled.ToString().ToLowerInvariant() | Out-Null
    Start-Sleep -Milliseconds 400
}

function Reset-AutomaticAnswerAudit {
    Invoke-Adb shell am start -W -a com.aios.phone.smoke.RESET_AUDIT `
        -n $fixtureActivity | Out-Null
    Invoke-Adb shell am start -W -a com.aios.callintelligence.smoke.RESET_AUDIT `
        -n $assistantActivity | Out-Null
}

function Get-AssistantAudit {
    $matches = @(
        Invoke-Adb shell run-as $assistantPackage find cache -maxdepth 1 `
            -name "aios-call-assistant-smoke-audit.txt" |
            Where-Object { $_ }
    )
    if ($matches.Count -eq 0) {
        return ""
    }
    return @(
        Invoke-Adb shell run-as $assistantPackage cat $assistantAuditFile |
            Where-Object { $_ }
    ) -join "`n"
}

function Get-ConnectionAudit {
    Invoke-Adb shell am start -W -a com.aios.phone.smoke.EXPORT_AUDIT `
        -n $fixtureActivity | Out-Null
    return @(
        Invoke-Adb shell run-as $package cat $privateAuditFile |
            Where-Object { $_ }
    ) -join "`n"
}

function Wait-ForAssistantAudit {
    param(
        [Parameter(Mandatory)][string]$Pattern,
        [int]$TimeoutMillis = 5000
    )

    $deadline = [Environment]::TickCount64 + $TimeoutMillis
    do {
        $audit = Get-AssistantAudit
        if ($audit -match $Pattern) {
            return $audit
        }
        Start-Sleep -Milliseconds 100
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for call-assistant audit pattern: $Pattern"
}

function Wait-ForTelecomPattern {
    param(
        [Parameter(Mandatory)][string]$Pattern,
        [int]$TimeoutMillis = 5000
    )

    $deadline = [Environment]::TickCount64 + $TimeoutMillis
    do {
        $calls = Get-CurrentTelecomCalls
        if ($calls -match $Pattern) {
            return $calls
        }
        Start-Sleep -Milliseconds 100
    } while ([Environment]::TickCount64 -lt $deadline)
    throw "Timed out waiting for Telecom state pattern: $Pattern"
}

function Start-SmokeIncoming {
    param([Parameter(Mandatory)][string]$Number)

    Invoke-Adb shell am start -W -a com.aios.phone.smoke.INCOMING `
        -n $fixtureActivity --es number $Number | Out-Null
    $script:callStarted = $true
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
}

function End-SmokeCalls {
    Invoke-Adb shell am start -W -a com.aios.phone.smoke.DISCONNECT `
        -n $fixtureActivity | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 300
    if ((Get-CurrentTelecomCalls) -match 'state=(RINGING|ACTIVE|DIALING|ON_HOLD|HOLDING)') {
        throw "Synthetic call survived fixture disconnect"
    }
    $script:callStarted = $false
}

function Invoke-AutomaticAnswerTimingCase {
    param(
        [Parameter(Mandatory)][string]$DelayMode,
        [int]$ExpectedDelayMillis = -1,
        [switch]$RandomDelay,
        [switch]$UsePersistedPolicy
    )

    if (-not $UsePersistedPolicy) {
        Set-AssistantPolicy -AnswerMode "all" -DelayMode $DelayMode
    }
    Reset-AutomaticAnswerAudit
    Start-SmokeIncoming -Number "15551230200"
    $assistantAudit = Wait-ForAssistantAudit -Pattern '(?m)^\d+:decision:\d+:1:'
    $decision = [regex]::Match(
        $assistantAudit,
        '(?m)^(?<at>\d+):decision:(?<delay>\d+):1:')
    if (-not $decision.Success) {
        throw "The production policy did not return an automatic-answer decision"
    }
    $decisionAt = [long]$decision.Groups['at'].Value
    $resolvedDelay = [int]$decision.Groups['delay'].Value
    if ($RandomDelay) {
        if ($resolvedDelay -lt 1010 -or $resolvedDelay -gt 3990) {
            throw "Random automatic-answer delay was outside 1010..3990 ms: $resolvedDelay"
        }
    } elseif ($resolvedDelay -ne $ExpectedDelayMillis) {
        throw "Delay mode $DelayMode resolved to $resolvedDelay ms, expected $ExpectedDelayMillis ms"
    }
    Wait-ForTelecomPattern -Pattern 'state=ACTIVE' `
        -TimeoutMillis ($resolvedDelay + 3000) | Out-Null
    $assistantAudit = Wait-ForAssistantAudit -Pattern '(?m)^\d+:answered:ai:true$'
    $connectionAudit = Get-ConnectionAudit
    $answers = [regex]::Matches($connectionAudit, '(?m)^answer:(?<at>\d+)$')
    if ($answers.Count -ne 1) {
        throw "Automatic answer reached the managed connection $($answers.Count) times"
    }
    $answeredAt = [long]$answers[0].Groups['at'].Value
    $observedDelay = $answeredAt - $decisionAt
    if ($observedDelay -lt $resolvedDelay -or $observedDelay -gt ($resolvedDelay + 1500)) {
        throw "Telecom answer timing was $observedDelay ms for resolved delay $resolvedDelay ms"
    }
    End-SmokeCalls
    return [ordered]@{
        mode = $DelayMode
        resolved_delay_ms = $resolvedDelay
        observed_decision_to_connection_ms = $observedDelay
        ai_answer_callback = $assistantAudit -match '(?m)^\d+:answered:ai:true$'
        connection_answer_count = $answers.Count
    }
}

try {
    Invoke-Adb install -r $assistantApkPath | Out-Null
    $assistantInstalled = $true
    Invoke-Adb install -r $apkPath | Out-Null
    $installed = $true
    Invoke-Adb shell cmd role add-role-holder --user 0 $role $package | Out-Null
    Invoke-Adb shell am start -a com.aios.phone.smoke.REGISTER -n $fixtureActivity | Out-Null
    $registered = $true
    Start-Sleep -Milliseconds 500
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Invoke-Adb shell cmd telecom set-phone-account-enabled $fixtureService $fixtureAccount 0 | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    if (-not $AutomaticAnswerOnly) {
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
    $notificationsAfterAnswer = (
        Invoke-Adb shell dumpsys notification --noredact) -join "`n"
    $phonePidAfterAnswer = (Invoke-Adb shell pidof $package | Select-Object -First 1).Trim()
    $answerState = [ordered]@{
        telecom_active = $telecomAfterAnswer -match 'state=ACTIVE'
        process_survived = $phonePidAfterAnswer -eq $phonePidBeforeActions
        in_call_service_bound = $servicesAfterAnswer -match 'AiosInCallService'
        foreground_service = $servicesAfterAnswer -match 'isForeground=true'
        phone_call_type = $servicesAfterAnswer -match 'types=0x00000004'
        ongoing_channel = $notificationsAfterAnswer -match 'pkg=com\.aios\.phone' -and
            $notificationsAfterAnswer -match 'channel=ongoing_calls_private_v2'
    }
    if ($answerState.Values -contains $false) {
        $observedAnswer = $answerState | ConvertTo-Json -Compress
        $observedNotifications = @(
            $notificationsAfterAnswer -split "`r?`n" |
                Where-Object { $_ -match 'com\.aios\.phone|ongoing_calls|NotificationRecord' } |
                Select-Object -First 24
        ) -join "\n"
        throw "Answer did not retain an active call under the phoneCall foreground service: $observedAnswer; notifications=$observedNotifications"
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
    $dialUi = (Get-UiHierarchy).OuterXml
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
    $notificationsAfterOutgoingActive = (
        Invoke-Adb shell dumpsys notification --noredact) -join "`n"
    $phonePidAfterOutgoingActive = (Invoke-Adb shell pidof $package |
        Select-Object -First 1).Trim()
    $outgoingActiveState = [ordered]@{
        telecom_active = $telecomAfterOutgoingActive -match 'state=ACTIVE'
        process_survived = $phonePidAfterOutgoingActive -eq $phonePidBeforeOutgoing
        foreground_service = $servicesAfterOutgoingActive -match 'isForeground=true'
        phone_call_type = $servicesAfterOutgoingActive -match 'types=0x00000004'
        ongoing_channel = $notificationsAfterOutgoingActive -match 'pkg=com\.aios\.phone' -and
            $notificationsAfterOutgoingActive -match 'channel=ongoing_calls_private_v2'
    }
    if ($outgoingActiveState.Values -contains $false) {
        $observedOutgoing = $outgoingActiveState | ConvertTo-Json -Compress
        throw "The outgoing call did not become active under the phoneCall foreground service: $observedOutgoing"
    }
    $outgoingScreenshot = Join-Path $EvidenceDirectory "aios-telecom-outgoing-smoke.png"
    Invoke-Adb shell screencap -p $remoteScreenshot | Out-Null
    Invoke-Adb pull $remoteScreenshot $outgoingScreenshot | Out-Null

    Invoke-UiControl "Mute"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 300
    $unmuteControl = Get-UiControl "Unmute"
    if (-not $unmuteControl.enabled -or
        (Get-CurrentTelecomCalls) -notmatch 'state=ACTIVE') {
        throw "Mute did not round-trip through Telecom into Compose state"
    }
    Invoke-UiControl "Unmute"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 300
    if (-not (Get-UiControl "Mute").enabled) {
        throw "Unmute did not round-trip through Telecom into Compose state"
    }

    Invoke-UiControl "Hold"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 300
    $telecomAfterHold = Get-CurrentTelecomCalls
    $resumeControl = Get-UiControl "Resume"
    if ($telecomAfterHold -notmatch 'state=(ON_HOLD|HOLDING)' -or
        -not $resumeControl.enabled) {
        throw "Hold did not round-trip through the managed connection into Compose state"
    }
    Invoke-UiControl "Resume"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 300
    if ((Get-CurrentTelecomCalls) -notmatch 'state=ACTIVE' -or
        -not (Get-UiControl "Hold").enabled) {
        throw "Resume did not return the managed connection to ACTIVE"
    }

    Invoke-Adb shell am start -a com.aios.phone.smoke.RESET_AUDIT `
        -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 200
    Invoke-UiControl "Keypad"
    Start-Sleep -Milliseconds 200
    Invoke-UiControl "5"
    Start-Sleep -Milliseconds 350
    Invoke-Adb shell am start -a com.aios.phone.smoke.EXPORT_AUDIT `
        -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 200
    $dtmfAudit = @(
        Invoke-Adb shell run-as $package cat $privateAuditFile |
            Where-Object { $_ }
    ) -join "`n"
    # PhoneRuntime first stops any prior tone, then sends one bounded pulse.
    if ($dtmfAudit -ne "stop`nplay:5`nstop") {
        $observedDtmf = $dtmfAudit.Replace("`r", "\\r").Replace("`n", "\\n")
        throw "Keypad DTMF did not clear a prior tone and produce one bounded play/stop callback pair; observed '$observedDtmf'"
    }
    Invoke-Adb shell run-as $package rm -f $privateAuditFile | Out-Null
    $remainingAudit = @(
        Invoke-Adb shell run-as $package find cache -maxdepth 1 `
            -name "aios-telecom-smoke-audit.txt" |
            Where-Object { $_ }
    )
    if ($remainingAudit.Count -ne 0) {
        throw "The private DTMF smoke audit survived verification"
    }
    $privateAuditRemoved = $true

    Invoke-UiControl "Hide keypad"
    Start-Sleep -Milliseconds 200
    Invoke-Adb shell am start -a com.aios.phone.smoke.RESET_AUDIT `
        -n $fixtureActivity | Out-Null
    Invoke-Adb shell am start -a com.aios.phone.smoke.POST_DIAL_WAIT `
        -n $fixtureActivity | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 300
    $continuePostDial = Get-UiControl "Continue"
    $cancelPostDial = Get-UiControl "Cancel"
    $postDialUi = (Get-UiHierarchy).OuterXml
    if (-not $continuePostDial.enabled -or -not $cancelPostDial.enabled -or
        $postDialUi -match '739164') {
        throw "Post-dial wait controls were unavailable or exposed the remaining digits"
    }
    Invoke-UiControl "Continue"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 300

    Invoke-Adb shell am start -a com.aios.phone.smoke.POST_DIAL_WAIT `
        -n $fixtureActivity | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 300
    Invoke-UiControl "Cancel"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 300
    Invoke-Adb shell am start -a com.aios.phone.smoke.EXPORT_AUDIT `
        -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 200
    $postDialAudit = @(
        Invoke-Adb shell run-as $package cat $privateAuditFile |
            Where-Object { $_ }
    ) -join "`n"
    if ($postDialAudit -ne "post-dial:true`npost-dial:false") {
        $observedPostDial = $postDialAudit.Replace("`r", "\\r").Replace("`n", "\\n")
        throw "Post-dial Continue/Cancel did not reach Telecom callbacks; observed '$observedPostDial'"
    }
    Invoke-Adb shell run-as $package rm -f $privateAuditFile | Out-Null
    $remainingAudit = @(
        Invoke-Adb shell run-as $package find cache -maxdepth 1 `
            -name "aios-telecom-smoke-audit.txt" |
            Where-Object { $_ }
    )
    if ($remainingAudit.Count -ne 0) {
        throw "The private post-dial smoke audit survived verification"
    }
    $privateAuditRemoved = $true

    Invoke-Adb shell am start -a com.aios.phone.smoke.INCOMING -n $fixtureActivity `
        --es number 15551230185 | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 750
    $telecomDuringWaiting = Get-CurrentTelecomCalls
    if ([regex]::Matches($telecomDuringWaiting, 'state=ACTIVE').Count -ne 1 -or
        [regex]::Matches($telecomDuringWaiting, 'state=RINGING').Count -ne 1 -or
        -not (Get-UiControl "Answer").enabled) {
        throw "A waiting call did not preempt the selected active call in Compose"
    }

    Invoke-UiControl "Answer"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 750
    $telecomAfterWaitingAnswer = Get-CurrentTelecomCalls
    if ([regex]::Matches($telecomAfterWaitingAnswer, 'state=ACTIVE').Count -ne 1 -or
        [regex]::Matches($telecomAfterWaitingAnswer, 'state=(ON_HOLD|HOLDING)').Count -ne 1) {
        throw "Answering the waiting call did not retain one active and one held call"
    }

    Invoke-Adb shell am start -a com.aios.phone.smoke.RESET_AUDIT `
        -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 200
    Invoke-UiControl "Merge calls"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 750
    if (-not (Get-UiControl "Separate call").enabled) {
        throw "The managed Telecom conference did not expose child separation in Compose"
    }
    Invoke-UiControl "Separate call"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 750
    Invoke-Adb shell am start -a com.aios.phone.smoke.EXPORT_AUDIT `
        -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 200
    $conferenceAudit = @(
        Invoke-Adb shell run-as $package cat $privateAuditFile |
            Where-Object { $_ }
    ) -join "`n"
    if ($conferenceAudit -ne "conference`nseparate") {
        $observedConference = $conferenceAudit.Replace("`r", "\\r").Replace("`n", "\\n")
        throw "Merge/separate did not reach the managed conference callbacks; observed '$observedConference'"
    }
    Invoke-Adb shell run-as $package rm -f $privateAuditFile | Out-Null
    $remainingAudit = @(
        Invoke-Adb shell run-as $package find cache -maxdepth 1 `
            -name "aios-telecom-smoke-audit.txt" |
            Where-Object { $_ }
    )
    if ($remainingAudit.Count -ne 0) {
        throw "The private conference smoke audit survived verification"
    }
    $privateAuditRemoved = $true

    Invoke-UiControl "End call"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 500
    $afterFirstEnd = Get-CurrentTelecomCalls
    if ([regex]::Matches($afterFirstEnd, 'state=(ACTIVE|ON_HOLD|HOLDING)').Count -ne 1) {
        throw "Ending one separated participant did not retain exactly one managed call"
    }
    Invoke-UiControl "End call"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 500
    if ((Get-CurrentTelecomCalls) -match 'state=(DIALING|ACTIVE|ON_HOLD|HOLDING|RINGING)') {
        throw "The production End call controls did not disconnect both separated calls"
    }
    $callStarted = $false

    Invoke-Adb shell cmd telecom set-phone-account-enabled `
        $fixtureService $fixtureSecondaryAccount 0 | Out-Null
    Invoke-Adb shell cmd telecom set-user-selected-outgoing-phone-account | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Invoke-Adb shell am start -a com.aios.phone.smoke.RESET_AUDIT `
        -n $fixtureActivity | Out-Null
    $multiAccountNumber = "15551230186"
    Invoke-Adb shell am start -W -a android.intent.action.DIAL `
        -d "tel:$multiAccountNumber" -n $mainActivity | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-UiControl "Call"
    $callStarted = $true
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 750
    $telecomChoosingAccount = Get-CurrentTelecomCalls
    $primaryAccountControl = Get-UiControl "AIOS emulator primary"
    $secondaryAccountControl = Get-UiControl "AIOS emulator secondary"
    if ($telecomChoosingAccount -notmatch 'state=SELECT_PHONE_ACCOUNT' -or
        -not $primaryAccountControl.enabled -or -not $secondaryAccountControl.enabled) {
        throw "Telecom did not present both emulator accounts through the Compose selector"
    }
    Invoke-UiControl "AIOS emulator secondary"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 750
    if ((Get-CurrentTelecomCalls) -notmatch 'state=DIALING') {
        throw "Selecting the secondary emulator account did not create a dialing connection"
    }
    Invoke-Adb shell am start -a com.aios.phone.smoke.ACTIVATE `
        -n $fixtureActivity | Out-Null
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 500
    if ((Get-CurrentTelecomCalls) -notmatch 'state=ACTIVE') {
        throw "The secondary-account connection did not become active"
    }
    Invoke-Adb shell am start -a com.aios.phone.smoke.EXPORT_AUDIT `
        -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 200
    $accountAudit = @(
        Invoke-Adb shell run-as $package cat $privateAuditFile |
            Where-Object { $_ }
    ) -join "`n"
    if ($accountAudit -ne "outgoing-account:$fixtureSecondaryAccount") {
        $observedAccount = $accountAudit.Replace("`r", "\\r").Replace("`n", "\\n")
        throw "The selected PhoneAccount did not reach ConnectionService; observed '$observedAccount'"
    }
    Invoke-Adb shell run-as $package rm -f $privateAuditFile | Out-Null
    $remainingAudit = @(
        Invoke-Adb shell run-as $package find cache -maxdepth 1 `
            -name "aios-telecom-smoke-audit.txt" |
            Where-Object { $_ }
    )
    if ($remainingAudit.Count -ne 0) {
        throw "The private account-selection smoke audit survived verification"
    }
    Invoke-UiControl "End call"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 500
    if ((Get-CurrentTelecomCalls) -match 'state=(SELECT_PHONE_ACCOUNT|DIALING|ACTIVE)') {
        throw "The secondary-account call survived the production End call control"
    }
    $callStarted = $false
    $privateAuditRemoved = $true
    } else {
        # Give the production Phone application time to establish its optional
        # AIDL binding before the focused automatic-answer phase configures it.
        Start-Sleep -Seconds 2
    }

    # The normal checks above run with the companion transport unavailable and
    # preserve the production fail-closed behavior. The following emulator-only
    # phase enables a controlled AIDL peer. Decisions come from the production
    # CallPolicyEngine/AnswerDelayPolicy; the production Phone owns the Handler
    # timer and the real Telecom Call.answer() mutation.
    Set-AssistantPolicy -AnswerMode "off" -DelayMode "fixed_2000_ms" `
        -Available $true -ProcessingEnabled $false
    Reset-AutomaticAnswerAudit
    Invoke-Adb shell am start -W -a android.intent.action.MAIN `
        -n $mainActivity | Out-Null
    Start-Sleep -Milliseconds 750
    Invoke-UiControl "Settings"
    Start-Sleep -Milliseconds 750
    Scroll-UntilUiText "Process and transcribe calls"
    $initialProcessingSwitch = Get-UiSwitch "Process and transcribe calls"
    if ($initialProcessingSwitch.checked) {
        throw "The production Settings screen did not load processing disabled"
    }
    Invoke-UiSwitch "Process and transcribe calls"
    Scroll-UntilUiText "Auto AI answer"
    $initialAutoAnswerSwitch = Get-UiSwitch "Auto AI answer"
    if ($initialAutoAnswerSwitch.checked) {
        throw "The production Settings screen did not load automatic answer disabled"
    }
    Invoke-UiSwitch "Auto AI answer"
    Invoke-ScrolledUiControl "Every non-emergency call"
    Invoke-ScrolledUiControl "3s"
    Invoke-ScrolledUiControl "Save assistant settings"
    $settingsPolicyAudit = Wait-ForAssistantAudit `
        -Pattern '(?m)^\d+:policy_update:all:fixed_3000_ms:true$'
    Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Milliseconds 300
    # The fixture persists the same service-owned policy fields as production.
    # Kill it before the call so the next decision proves a reloaded value, not
    # merely the Phone process's current Compose draft.
    $assistantPidBeforeRestart = (Invoke-Adb shell pidof $assistantPackage |
        Select-Object -First 1).Trim()
    if ($assistantPidBeforeRestart -notmatch '^[0-9]+$') {
        throw "Call-assistant companion had no process before persistence restart"
    }
    Invoke-Adb shell am force-stop $assistantPackage | Out-Null
    Start-Sleep -Milliseconds 500
    $assistantPidAfterForceStop = @(
        Invoke-Adb shell pidof $assistantPackage | Where-Object { $_ }
    ) -join ""
    if ($assistantPidAfterForceStop -and
        ($assistantPidAfterForceStop -notmatch '^[0-9]+$' -or
            $assistantPidAfterForceStop -eq $assistantPidBeforeRestart)) {
        throw "Call-assistant companion process was not replaced after force-stop"
    }
    $settingsToTelecomAnswer = Invoke-AutomaticAnswerTimingCase `
        -DelayMode "fixed_3000_ms" -ExpectedDelayMillis 3000 -UsePersistedPolicy
    $assistantPidAfterRestart = (Invoke-Adb shell pidof $assistantPackage |
        Select-Object -First 1).Trim()
    $settingsPolicySurvivedServiceRestart =
        $assistantPidAfterRestart -match '^[0-9]+$' -and
        $assistantPidAfterRestart -ne $assistantPidBeforeRestart
    if (-not $settingsPolicySurvivedServiceRestart) {
        throw "Persisted policy was not served by a replacement companion process"
    }

    $fixedAutomaticAnswer = @(
        Invoke-AutomaticAnswerTimingCase `
            -DelayMode "fixed_1000_ms" -ExpectedDelayMillis 1000
        Invoke-AutomaticAnswerTimingCase `
            -DelayMode "fixed_2000_ms" -ExpectedDelayMillis 2000
        Invoke-AutomaticAnswerTimingCase `
            -DelayMode "fixed_3000_ms" -ExpectedDelayMillis 3000
        Invoke-AutomaticAnswerTimingCase `
            -DelayMode "fixed_4000_ms" -ExpectedDelayMillis 4000
    )
    $randomAutomaticAnswer = Invoke-AutomaticAnswerTimingCase `
        -DelayMode "random_1010_3990_ms" -RandomDelay

    Set-AssistantPolicy -AnswerMode "all" -DelayMode "fixed_4000_ms"
    Reset-AutomaticAnswerAudit
    Start-SmokeIncoming -Number "15551230210"
    Wait-ForAssistantAudit -Pattern '(?m)^\d+:decision:4000:1:' | Out-Null
    Invoke-Adb shell am start -a com.aios.phone.smoke.SHOW `
        -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 250
    Invoke-UiControl "Answer"
    Wait-ForTelecomPattern -Pattern 'state=ACTIVE' -TimeoutMillis 2000 | Out-Null
    Start-Sleep -Milliseconds 4300
    $ownerAnswerAssistantAudit = Get-AssistantAudit
    $ownerAnswerConnectionAudit = Get-ConnectionAudit
    $ownerAnswerCount = [regex]::Matches(
        $ownerAnswerConnectionAudit, '(?m)^answer:\d+$').Count
    if ($ownerAnswerAssistantAudit -notmatch '(?m)^\d+:answered:owner:true$' -or
        $ownerAnswerAssistantAudit -match '(?m)^\d+:answered:ai:' -or
        $ownerAnswerCount -ne 1) {
        throw "Owner Answer did not synchronously cancel the pending AI reservation"
    }
    End-SmokeCalls

    Set-AssistantPolicy -AnswerMode "all" -DelayMode "fixed_4000_ms"
    Reset-AutomaticAnswerAudit
    Start-SmokeIncoming -Number "15551230211"
    Wait-ForAssistantAudit -Pattern '(?m)^\d+:decision:4000:1:' | Out-Null
    Invoke-Adb shell am start -a com.aios.phone.smoke.SHOW `
        -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 250
    Invoke-UiControl "Decline"
    Invoke-Adb shell cmd telecom wait-on-handlers | Out-Null
    Start-Sleep -Milliseconds 4300
    $declineAssistantAudit = Get-AssistantAudit
    $declineConnectionAudit = Get-ConnectionAudit
    if ((Get-CurrentTelecomCalls) -match 'state=(RINGING|ACTIVE)' -or
        $declineAssistantAudit -match '(?m)^\d+:answered:' -or
        $declineConnectionAudit -notmatch '(?m)^reject:\d+$' -or
        $declineConnectionAudit -match '(?m)^answer:\d+$') {
        throw "Decline did not cancel the pending AI reservation before rejecting Telecom"
    }
    $callStarted = $false

    # Ignore is intentionally distinct from Decline: it silences owner-facing
    # ringing while leaving an enabled receptionist policy free to answer.
    Set-AssistantPolicy -AnswerMode "all" -DelayMode "fixed_4000_ms"
    Reset-AutomaticAnswerAudit
    Start-SmokeIncoming -Number "15551230212"
    Wait-ForAssistantAudit -Pattern '(?m)^\d+:decision:4000:1:' | Out-Null
    Invoke-Adb shell am start -a com.aios.phone.smoke.SHOW `
        -n $fixtureActivity | Out-Null
    Start-Sleep -Milliseconds 250
    Invoke-UiControl "Ignore"
    Start-Sleep -Milliseconds 300
    $notificationsAfterAutomaticIgnore = (
        Invoke-Adb shell dumpsys notification --noredact) -join "`n"
    if ((Get-CurrentTelecomCalls) -notmatch 'state=RINGING' -or
        $notificationsAfterAutomaticIgnore -notmatch 'channel=incoming_calls_silent_v1') {
        throw "Ignore did not silence and preserve the pending automatic-answer call"
    }
    Wait-ForTelecomPattern -Pattern 'state=ACTIVE' -TimeoutMillis 6000 | Out-Null
    $ignoreAssistantAudit = Wait-ForAssistantAudit `
        -Pattern '(?m)^\d+:answered:ai:true$'
    End-SmokeCalls

    # A dead optional AI service must revoke its old timer. Android may recreate
    # the still-bound service immediately; recovery is allowed to request a new
    # decision, but that decision must receive a fresh, complete delay.
    Set-AssistantPolicy -AnswerMode "all" -DelayMode "fixed_4000_ms"
    Reset-AutomaticAnswerAudit
    Start-SmokeIncoming -Number "15551230213"
    $serviceLossAudit = Wait-ForAssistantAudit `
        -Pattern '(?m)^\d+:decision:4000:1:'
    $firstServiceDecision = [regex]::Match(
        $serviceLossAudit, '(?m)^(?<at>\d+):decision:4000:1:')
    Start-Sleep -Milliseconds 3200
    Invoke-Adb shell am force-stop $assistantPackage | Out-Null
    $secondServiceDecision = $null
    $serviceDecisionDeadline = [Environment]::TickCount64 + 3000
    do {
        $serviceLossAudit = Get-AssistantAudit
        $serviceDecisions = [regex]::Matches(
            $serviceLossAudit, '(?m)^(?<at>\d+):decision:4000:1:')
        if ($serviceDecisions.Count -ge 2) {
            $secondServiceDecision = $serviceDecisions[1]
            break
        }
        Start-Sleep -Milliseconds 100
    } while ([Environment]::TickCount64 -lt $serviceDecisionDeadline)
    if ($null -eq $secondServiceDecision) {
        throw "Call-assistant replacement did not reevaluate the still-ringing call"
    }
    $firstServiceDecisionAt = [long]$firstServiceDecision.Groups['at'].Value
    $secondServiceDecisionAt = [long]$secondServiceDecision.Groups['at'].Value
    if ($secondServiceDecisionAt -le $firstServiceDecisionAt) {
        throw "Replacement decision did not have a newer elapsed-realtime identity"
    }
    $oldDeadlineRemaining = [Math]::Max(
        0L,
        ($firstServiceDecisionAt + 4300L) - $secondServiceDecisionAt)
    if ($oldDeadlineRemaining -gt 0L) {
        Start-Sleep -Milliseconds ([int]$oldDeadlineRemaining)
    }
    if ((Get-CurrentTelecomCalls) -notmatch 'state=RINGING') {
        throw "The revoked pre-loss automatic-answer deadline still answered the call"
    }
    Wait-ForTelecomPattern -Pattern 'state=ACTIVE' -TimeoutMillis 5500 | Out-Null
    $serviceLossAudit = Wait-ForAssistantAudit `
        -Pattern '(?m)^\d+:answered:ai:true$'
    $serviceLossConnectionAudit = Get-ConnectionAudit
    $serviceLossAnswers = [regex]::Matches(
        $serviceLossConnectionAudit, '(?m)^answer:(?<at>\d+)$')
    if ($serviceLossAnswers.Count -ne 1) {
        throw "Recovered automatic answer reached Telecom $($serviceLossAnswers.Count) times"
    }
    $serviceLossAnsweredAt = [long]$serviceLossAnswers[0].Groups['at'].Value
    $serviceLossRestartedDelay = $serviceLossAnsweredAt - $secondServiceDecisionAt
    if ($serviceLossRestartedDelay -lt 4000L -or
        $serviceLossRestartedDelay -gt 5500L) {
        throw "Recovered AI decision did not receive a fresh four-second delay: $serviceLossRestartedDelay ms"
    }
    End-SmokeCalls

    # Starting the control activity clears force-stop and lets the production
    # client reconnect. A synthetic 911 presentation then verifies that the
    # phone-side emergency gate never consumes the one-second AI policy.
    Set-AssistantPolicy -AnswerMode "all" -DelayMode "fixed_1000_ms"
    Start-Sleep -Seconds 2
    Reset-AutomaticAnswerAudit
    Start-SmokeIncoming -Number "911"
    Start-Sleep -Milliseconds 1800
    $emergencyAssistantAudit = Get-AssistantAudit
    if ((Get-CurrentTelecomCalls) -notmatch 'state=RINGING' -or
        $emergencyAssistantAudit -match '(?m)^\d+:decision:' -or
        $emergencyAssistantAudit -match '(?m)^\d+:answered:ai:') {
        throw "Synthetic emergency presentation reached the automatic-answer decision path"
    }
    # Emulator Telecom does not model the carrier emergency UI contract. End
    # this synthetic presentation through the fixture after proving AI bypass;
    # the physical emergency-control matrix remains a separate release gate.
    End-SmokeCalls

    Invoke-Adb shell run-as $package rm -f $privateAuditFile | Out-Null
    Invoke-Adb shell run-as $assistantPackage rm -f $assistantAuditFile | Out-Null
    $remainingAssistantAudit = @(
        Invoke-Adb shell run-as $assistantPackage find cache -maxdepth 1 `
            -name "aios-call-assistant-smoke-audit.txt" |
            Where-Object { $_ }
    )
    if ($remainingAssistantAudit.Count -ne 0) {
        throw "The private call-assistant smoke audit survived verification"
    }

    $baselineEvidence = if ($AutomaticAnswerOnly) { $null } else { $true }
    $evidence = [ordered]@{
        schema_version = 2
        execution_mode = if ($AutomaticAnswerOnly) { "automatic_answer_only" } else { "full" }
        serial = $Serial
        qemu = $true
        android_release = $androidRelease
        api_level = $apiLevel
        apk_bytes = $apkBytes
        apk_sha256 = $apkSha256
        assistant_apk_bytes = $assistantApkBytes
        assistant_apk_sha256 = $assistantApkSha256
        role_holder = $package
        simulated_number = if ($AutomaticAnswerOnly) { $null } else { "15551230182" }
        simulated_transport = "managed_connection_service"
        in_call_activity_visible = $baselineEvidence
        full_screen_intent_launched_automatically = if ($AutomaticAnswerOnly) {
            $null
        } else {
            $fullScreenIntentVisible
        }
        in_call_service_bound = $baselineEvidence
        incoming_notification_posted = $baselineEvidence
        ignore_preserved_ringing_call = $baselineEvidence
        ignore_selected_silent_channel = $baselineEvidence
        answer_activated_call = $baselineEvidence
        phone_process_survived_answer = $baselineEvidence
        phone_call_foreground_service = $baselineEvidence
        ongoing_notification_posted = $baselineEvidence
        decline_disconnected_call = $baselineEvidence
        ai_action_fail_closed = $baselineEvidence
        automatic_answer_fixed_delays = $fixedAutomaticAnswer
        automatic_answer_random_delay = $randomAutomaticAnswer
        settings_policy_update_reached_binder = `
            $settingsPolicyAudit -match '(?m)^\d+:policy_update:all:fixed_3000_ms:true$'
        settings_policy_survived_service_restart = $settingsPolicySurvivedServiceRestart
        settings_to_telecom_answer = $settingsToTelecomAnswer
        owner_answer_cancelled_pending_ai = $true
        decline_cancelled_pending_ai = $true
        ignore_preserved_automatic_ai = `
            $ignoreAssistantAudit -match '(?m)^\d+:answered:ai:true$'
        service_loss_revoked_old_pending_ai = $true
        service_reconnect_restarted_full_delay_ms = $serviceLossRestartedDelay
        synthetic_emergency_never_evaluated_for_ai = $true
        outgoing_dial_intent_populated = $baselineEvidence
        outgoing_compose_call_action = $baselineEvidence
        outgoing_connection_dialing = $baselineEvidence
        outgoing_in_call_activity_visible = $baselineEvidence
        outgoing_connection_active = $baselineEvidence
        phone_process_survived_outgoing = $baselineEvidence
        mute_unmute_round_trip = $baselineEvidence
        hold_resume_round_trip = $baselineEvidence
        dtmf_play_stop_callbacks = $baselineEvidence
        post_dial_digits_redacted = $baselineEvidence
        post_dial_continue_callback = $baselineEvidence
        post_dial_cancel_callback = $baselineEvidence
        waiting_call_selected = $baselineEvidence
        waiting_answer_held_existing_call = $baselineEvidence
        conference_merge_separate_callbacks = $baselineEvidence
        multi_account_selector_visible = $baselineEvidence
        secondary_phone_account_selected = $baselineEvidence
        selected_account_reached_connection_service = $baselineEvidence
        private_dtmf_audit_removed = $baselineEvidence
        private_post_dial_audit_removed = $baselineEvidence
        private_conference_audit_removed = $baselineEvidence
        private_account_selection_audit_removed = $baselineEvidence
        private_automatic_answer_audits_removed = $true
        outgoing_end_call_disconnected = $baselineEvidence
        screenshot = if ($AutomaticAnswerOnly) { $null } else { [IO.Path]::GetFullPath($screenshot) }
        outgoing_screenshot = if ($AutomaticAnswerOnly) {
            $null
        } else {
            [IO.Path]::GetFullPath($outgoingScreenshot)
        }
        physical_gate_evidence = $false
    }
    $evidenceFileName = if ($AutomaticAnswerOnly) {
        "aios-emulator-auto-answer-smoke.json"
    } else {
        "aios-telecom-smoke.json"
    }
    $evidencePath = Join-Path $EvidenceDirectory $evidenceFileName
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
    if ($installed) {
        & $adb -s $Serial shell run-as $package rm -f $privateAuditFile | Out-Null
    }
    if ($assistantInstalled) {
        & $adb -s $Serial shell run-as $assistantPackage `
            rm -f $assistantAuditFile | Out-Null
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
    if ($assistantInstalled -and -not $KeepInstalled) {
        & $adb -s $Serial uninstall $assistantPackage | Out-Null
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
$remainingAssistantPackage = @(
    Invoke-Adb shell pm list packages --user 0 $assistantPackage |
        Where-Object { $_ -eq "package:$assistantPackage" }
)
if (-not $KeepInstalled -and $remainingAssistantPackage.Count -ne 0) {
    throw "Call-assistant smoke package survived cleanup"
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
$evidence["assistant_package_removed"] = -not $KeepInstalled
$evidence["remote_screenshot_removed"] = $true
$evidence["remote_ui_dump_removed"] = $true
$evidence["captured_at"] = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$utf8WithoutBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText(
    [IO.Path]::GetFullPath($evidencePath),
    ($evidence | ConvertTo-Json -Depth 4),
    $utf8WithoutBom)
Write-Output "AIOS emulator Telecom smoke check passed: $evidencePath"
