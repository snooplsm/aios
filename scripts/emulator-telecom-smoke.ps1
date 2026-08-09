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

$qemu = (Invoke-Adb shell getprop ro.kernel.qemu | Select-Object -First 1).Trim()
if ($qemu -ne "1") {
    throw "Refusing to run: $Serial does not report ro.kernel.qemu=1"
}
if (-not (Test-Path -LiteralPath $Apk)) {
    throw "Smoke APK not found: $Apk"
}

$role = "android.app.role.DIALER"
$package = "com.aios.phone"
$fixtureActivity = "$package/com.aios.phone.smoke.EmulatorCallActivity"
$fixtureService = "$package/com.aios.phone.smoke.EmulatorConnectionService"
$fixtureAccount = "aios-emulator-smoke"
$originalHolders = @(
    Invoke-Adb shell cmd role get-role-holders --user 0 $role |
        Where-Object { $_ -and $_ -ne $package }
)
$callStarted = $false
$installed = $false
$registered = $false
$screenPrepared = $false
$screenWasAwake = ((Invoke-Adb shell dumpsys power) -join "`n") -match 'mWakefulness=Awake'

try {
    Invoke-Adb install -r $Apk | Out-Null
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

    $focus = (Invoke-Adb shell dumpsys window) -join "`n"
    $telecom = (Invoke-Adb shell dumpsys telecom) -join "`n"
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
        $focus = (Invoke-Adb shell dumpsys window) -join "`n"
    }
    if ($focus -notmatch 'com\.aios\.phone/.+InCallActivity') {
        throw "AIOS InCallActivity could not be displayed for visual verification"
    }

    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    $remoteScreenshot = "/sdcard/aios-telecom-smoke.png"
    $screenshot = Join-Path $EvidenceDirectory "aios-telecom-smoke.png"
    Invoke-Adb shell screencap -p $remoteScreenshot | Out-Null
    Invoke-Adb pull $remoteScreenshot $screenshot | Out-Null

    $evidence = [ordered]@{
        schema_version = 1
        serial = $Serial
        qemu = $true
        android_release = (Invoke-Adb shell getprop ro.build.version.release | Select-Object -First 1).Trim()
        api_level = [int](Invoke-Adb shell getprop ro.build.version.sdk | Select-Object -First 1).Trim()
        role_holder = $package
        simulated_number = "15551230182"
        simulated_transport = "managed_connection_service"
        in_call_activity_visible = $true
        full_screen_intent_launched_automatically = $fullScreenIntentVisible
        in_call_service_bound = $true
        incoming_notification_posted = $true
        screenshot = $screenshot
        physical_gate_evidence = $false
    }
    $evidence | ConvertTo-Json | Set-Content -Encoding UTF8 (
        Join-Path $EvidenceDirectory "aios-telecom-smoke.json"
    )
    Write-Output "AIOS emulator Telecom smoke check passed: $screenshot"
} finally {
    if ($callStarted) {
        & $adb -s $Serial shell am start -a com.aios.phone.smoke.DISCONNECT `
            -n $fixtureActivity | Out-Null
    }
    if ($registered) {
        & $adb -s $Serial shell am start -a com.aios.phone.smoke.UNREGISTER `
            -n $fixtureActivity | Out-Null
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
}
