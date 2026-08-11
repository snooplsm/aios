param(
    [ValidateSet("inbox", "conversation", "context-photo")]
    [string]$Scenario = "inbox",
    [ValidateSet("system", "light", "dark")]
    [string]$Theme = "system",
    [string]$Serial = "emulator-5554",
    [string]$Gradle = "gradle",
    [switch]$SkipBuild,
    [string]$Screenshot = ""
)

$ErrorActionPreference = "Stop"

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw "Refusing to install the visual preview on a non-emulator serial: $Serial"
}

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$previewRoot = Join-Path $root "preview"
$apk = Join-Path $previewRoot "app\build\outputs\apk\debug\app-debug.apk"
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
$apiLevel = [int](Invoke-Adb shell getprop ro.build.version.sdk |
    Select-Object -First 1).Trim()
if ($apiLevel -lt 35) {
    throw "AIOS Messages preview requires Android API 35 or newer"
}

if (-not $SkipBuild) {
    $gradlePath = if (Test-Path -LiteralPath $Gradle) {
        [IO.Path]::GetFullPath($Gradle)
    } else {
        (Get-Command $Gradle -ErrorAction Stop).Source
    }
    Push-Location $previewRoot
    try {
        & $gradlePath --offline --no-daemon :app:assembleDebug
        if ($LASTEXITCODE -ne 0) {
            throw "AIOS Messages preview build failed"
        }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Preview APK not found at $apk; build it or omit -SkipBuild"
}

Invoke-Adb install -r $apk | Out-Null
Invoke-Adb shell am force-stop com.aios.phone.preview | Out-Null
Invoke-Adb shell am start -n `
    com.aios.phone.preview/com.aios.messaging.preview.MessagesPreviewActivity `
    --es aios_messages_preview_scenario $Scenario `
    --es aios_messages_preview_theme $Theme | Out-Null

$expectedText = @{
    inbox = "AIOS Messages"
    conversation = "On-device context"
    "context-photo" = "Photo: replacement-valve.jpg"
}[$Scenario]
$token = [Guid]::NewGuid().ToString("N")
$remoteUi = "/sdcard/aios-messages-preview-$token.xml"
$remoteScreenshot = "/sdcard/aios-messages-preview-$token.png"

try {
    $hierarchy = $null
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Start-Sleep -Milliseconds 250
        try {
            Invoke-Adb shell uiautomator dump $remoteUi | Out-Null
            [xml]$candidate = (Invoke-Adb shell cat $remoteUi) -join "`n"
        } catch {
            continue
        }
        if (@($candidate.SelectNodes('//node') | Where-Object {
            $_.text -eq $expectedText -or $_.'content-desc' -eq $expectedText
        }).Count -gt 0) {
            $hierarchy = $candidate
            break
        }
    }
    if ($null -eq $hierarchy) {
        throw "Messages preview scenario '$Scenario' did not render '$expectedText'"
    }
    if ($Scenario -ne "inbox") {
        foreach ($required in @("Call", "On-device context", "Send SMS")) {
            if ($Scenario -eq "context-photo" -and $required -eq "Send SMS") {
                $required = "Send photo"
            }
            if (@($hierarchy.SelectNodes('//node') | Where-Object {
                $_.text -eq $required -or $_.'content-desc' -eq $required
            }).Count -eq 0) {
                throw "Messages conversation did not render '$required'"
            }
        }
    }

    if ([string]::IsNullOrWhiteSpace($Screenshot)) {
        $Screenshot = Join-Path $previewRoot (
            "screenshots\aios-messages-preview-$Scenario-$Theme.png")
    }
    $screenshotPath = [IO.Path]::GetFullPath($Screenshot)
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($screenshotPath)) |
        Out-Null
    Invoke-Adb shell screencap -p $remoteScreenshot | Out-Null
    Invoke-Adb pull $remoteScreenshot $screenshotPath | Out-Null
    if (-not (Test-Path -LiteralPath $screenshotPath) -or
        (Get-Item -LiteralPath $screenshotPath).Length -le 0) {
        throw "Messages preview screenshot was not captured"
    }
    Write-Output "AIOS Messages preview ready: scenario=$Scenario theme=$Theme"
    Write-Output "Screenshot: $screenshotPath"
} finally {
    & $adb -s $Serial shell rm -f $remoteUi $remoteScreenshot | Out-Null
}
