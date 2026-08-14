param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateSet("enable", "disable")]
    [string]$Mode
)

$ErrorActionPreference = "Stop"
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adbPath = if ($null -ne $adbCommand) {
    $adbCommand.Source
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
    throw "adb is not available on PATH or in the standard Android SDK location"
}

function Invoke-AiosAdb {
    param([string[]]$Arguments)
    $output = & $adbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' '): $($output -join ' ')"
    }
    return $output
}

function Get-AiosProperty {
    param([string]$Name)
    return ((Invoke-AiosAdb -Arguments @("shell", "getprop", $Name)) -join "`n").Trim()
}

if (((Invoke-AiosAdb -Arguments @("get-state")) -join "`n").Trim() -ne "device") {
    throw "the selected ADB target is not ready"
}
if (-not (Get-AiosProperty -Name "ro.aios.version")) {
    throw "the selected target is not running AIOS"
}
if ((Get-AiosProperty -Name "ro.debuggable") -ne "1") {
    throw "caller-uplink test mode is forbidden on a non-debuggable build"
}
if ((Get-AiosProperty -Name "ro.boot.qemu") -eq "1") {
    throw "caller-uplink test mode requires a physical device"
}

Invoke-AiosAdb -Arguments @("root") | Out-Null
Invoke-AiosAdb -Arguments @("wait-for-device") | Out-Null
$requested = if ($Mode -eq "enable") { "1" } else { "0" }
Invoke-AiosAdb -Arguments @(
    "shell", "setprop", "persist.aios.debug.call_uplink_test", $requested
) | Out-Null
$observed = Get-AiosProperty -Name "persist.aios.debug.call_uplink_test"
if ($observed -ne $requested) {
    throw "device rejected the development caller-uplink property"
}

# Restart both ends so Phone reloads the current capability projection. No call
# or user data is read, copied, or logged by this helper.
Invoke-AiosAdb -Arguments @("shell", "am", "force-stop", "com.aios.phone") | Out-Null
Invoke-AiosAdb -Arguments @(
    "shell", "am", "force-stop", "com.aios.callintelligence"
) | Out-Null
Invoke-AiosAdb -Arguments @(
    "shell", "am", "start", "-W", "-a", "android.intent.action.DIAL"
) | Out-Null

if ($Mode -eq "enable") {
    Write-Output "Development manual caller-uplink test mode enabled; automatic answering remains locked."
} else {
    Write-Output "Development manual caller-uplink test mode disabled."
}
